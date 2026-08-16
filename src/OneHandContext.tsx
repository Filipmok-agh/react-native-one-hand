import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PropsWithChildren,
} from 'react';
import { Dimensions, Keyboard } from 'react-native';

/** The docking corner: the bottom-left or bottom-right corner of the screen. */
export type OneHandSide = 'left' | 'right';

export type OneHandValue = {
  /** Whether one-hand mode is currently active. */
  active: boolean;
  /** The docking corner: left or right. */
  side: OneHandSide;
  /** The downscale factor in effect (0.3–0.85; an out-of-range config falls back to the default 0.75). */
  scale: number;
  /**
   * Enters one-hand mode; optionally docks to the given side (defaults to the current one).
   * No-op while the system keyboard is visible (the keyboard is not scaled, so the mode
   * is not allowed to start underneath it) and in landscape — one-hand mode is
   * portrait-only.
   */
  enable: (side?: OneHandSide) => void;
  /** Exits one-hand mode (restores full size). */
  disable: () => void;
  /** Switches the docking side (left ↔ right). */
  toggleSide: () => void;
};

/** The default downscale factor. */
export const DEFAULT_ONE_HAND_SCALE = 0.75;

const OneHandContext = createContext<OneHandValue | null>(null);

/**
 * Tracks system keyboard visibility. Subscribes to both `will*` (iOS, fires before the
 * animation for a snappy reaction) and `did*` events (a robust fallback); duplicate
 * updates are coalesced by React state equality.
 */
function useKeyboardVisible(): boolean {
  const [visible, setVisible] = useState(() => Keyboard.isVisible());

  useEffect(() => {
    const subscriptions = [
      Keyboard.addListener('keyboardWillShow', () => setVisible(true)),
      Keyboard.addListener('keyboardDidShow', () => setVisible(true)),
      Keyboard.addListener('keyboardWillHide', () => setVisible(false)),
      Keyboard.addListener('keyboardDidHide', () => setVisible(false)),
    ];
    return () => subscriptions.forEach((subscription) => subscription.remove());
  }, []);

  return visible;
}

/**
 * Tracks the window size. Feeds the orientation policy: the portrait-only entry guard in
 * `enable`, and the auto-exit that keys off ANY change of the window dimensions rather
 * than an orientation flip alone: a rotation is the common case,
 * but entering split-screen, a freeform/desktop-window resize or a foldable posture
 * change resizes the window without necessarily flipping orientation — and those
 * invalidate the native transform exactly the same way. (On Android an opening keyboard
 * may also resize the window; the keyboard policy exits the mode in that case anyway, so
 * the outcome is identical.)
 */
function useWindowSize(): { width: number; height: number } {
  const [size, setSize] = useState(() => {
    const { width, height } = Dimensions.get('window');
    return { width, height };
  });

  useEffect(() => {
    const subscription = Dimensions.addEventListener('change', ({ window }) => {
      setSize((prev) =>
        prev.width === window.width && prev.height === window.height
          ? prev
          : { width: window.width, height: window.height },
      );
    });
    return () => subscription.remove();
  }, []);

  return size;
}

let warnedScale = false;

/**
 * Validates the configured scale, falling back to the default (with a one-time warning)
 * on an out-of-range value. A scale of 1 or more is not merely useless: the "docked" app
 * then covers the whole screen (or overflows it), so there is no backdrop left to tap
 * and the mode cannot be exited by its documented gesture.
 */
function useSafeScale(scale: number): number {
  return useMemo(() => {
    if (Number.isFinite(scale) && scale >= MIN_SCALE && scale <= MAX_SCALE) return scale;
    if (!warnedScale) {
      // Deliberate impurity: a once-per-process dev warning. The write is idempotent and
      // nothing renders from it, so a render React discards at worst costs a duplicate log.
      // eslint-disable-next-line react-hooks/globals
      warnedScale = true;
      console.warn(
        `react-native-one-hand: scale must be between ${MIN_SCALE} and ${MAX_SCALE} ` +
          `(got ${scale}). Using ${DEFAULT_ONE_HAND_SCALE} instead.`,
      );
    }
    return DEFAULT_ONE_HAND_SCALE;
  }, [scale]);
}

/** Smallest usable downscale factor (below this the app is unreadable). */
const MIN_SCALE = 0.3;
/**
 * Largest allowed downscale factor. 0.85 is chosen so that the docked app's top edge
 * ALWAYS clears the hardware cutout: the top edge renders at `height · (1 − scale)`
 * from the top of the screen, and the tallest cutouts (Dynamic Island phones) only
 * reach it at scale ≈ 0.93. That guarantee is what lets the top safe-area inset be
 * zeroed unconditionally while docked (see DockedInsets) — no per-device overlap math.
 * It also keeps the backdrop strip large enough that tap-to-exit stays comfortable
 * (at the old 0.95 maximum the strip was a near-untappable 5% of the screen).
 */
const MAX_SCALE = 0.85;

type OneHandProviderProps = PropsWithChildren<{
  /** Downscale factor applied in one-hand mode (0.3–0.85). Defaults to 0.75. */
  scale?: number;
  /** Initial docking side. Defaults to 'right'. */
  initialSide?: OneHandSide;
}>;

/**
 * Internal state provider for one-hand mode. Mounted by `OneHandWindowsContainer` —
 * you do not need to use it directly. It only holds `active` and `side`; the actual
 * transform is performed by the container.
 *
 * Keyboard policy (the system keyboard stays full-sized, see ARCHITECTURE.md):
 *  - while the keyboard is visible, `enable()` is a no-op,
 *  - when the keyboard opens while the mode is active, the mode exits automatically.
 *
 * Orientation policy (see ARCHITECTURE.md): one-hand mode is PORTRAIT-ONLY.
 *  - `enable()` (and thus the corner gestures) is a no-op while in landscape,
 *  - when the window dimensions change while the mode is active — a rotation, but also
 *    split-screen, a freeform resize or a foldable posture change — the mode exits
 *    automatically. Such a change re-sets native window geometry under the active
 *    transform (verified live on iOS: an unusable stuck state), so a controlled exit is
 *    the only sane response; the mode can simply be re-entered back in portrait.
 */
export function OneHandProvider({
  children,
  scale: rawScale = DEFAULT_ONE_HAND_SCALE,
  initialSide = 'right',
}: OneHandProviderProps) {
  const [active, setActive] = useState(false);
  const [side, setSide] = useState<OneHandSide>(initialSide);
  const scale = useSafeScale(rawScale);
  const keyboardVisible = useKeyboardVisible();

  const windowSize = useWindowSize();

  // Read through refs so `enable` keeps a stable identity: the corner gesture arms a
  // 450 ms timer that calls it, and a policy-dependent identity meant the timer ran
  // the closure from touch-start — evaluating the policy against a stale value.
  /* eslint-disable react-hooks/refs -- The "latest ref" pattern, written during render on
     purpose. Both values are read ONLY from the `enable` callback (never during render),
     and writing them from an effect instead would leave them one commit stale — which is
     exactly the failure this pattern was introduced to fix. See the comment above. */
  const keyboardVisibleRef = useRef(keyboardVisible);
  keyboardVisibleRef.current = keyboardVisible;
  const windowSizeRef = useRef(windowSize);
  windowSizeRef.current = windowSize;
  /* eslint-enable react-hooks/refs */

  const enable = useCallback((nextSide?: OneHandSide) => {
    if (keyboardVisibleRef.current) return;
    // Portrait-only policy: the mode cannot be entered while in landscape.
    if (windowSizeRef.current.width > windowSizeRef.current.height) return;
    if (nextSide) setSide(nextSide);
    setActive(true);
  }, []);
  const disable = useCallback(() => setActive(false), []);
  const toggleSide = useCallback(
    () => setSide((prev) => (prev === 'right' ? 'left' : 'right')),
    [],
  );

  // Auto-exit: a full-sized keyboard over a docked app is not a usable state.
  useEffect(() => {
    // The cascading render is the point: this reacts to an EXTERNAL event (the system
    // keyboard), which cannot be derived during render. It settles after one extra pass,
    // because `active` is false from then on.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (active && keyboardVisible) setActive(false);
  }, [active, keyboardVisible]);

  const previousWindowSize = useRef(windowSize);

  // Auto-exit on any window RESIZE (a rotation, but also split-screen or a freeform
  // resize): a resize breaks the native window geometry under the active transform, so
  // the mode performs a controlled exit instead. Together with the portrait-only guard
  // in `enable` this means the mode is never active outside portrait.
  useEffect(() => {
    if (
      previousWindowSize.current.width === windowSize.width &&
      previousWindowSize.current.height === windowSize.height
    ) {
      return;
    }
    previousWindowSize.current = windowSize;
    setActive(false);
  }, [windowSize]);

  const value = useMemo<OneHandValue>(
    () => ({ active, side, scale, enable, disable, toggleSide }),
    [active, side, scale, enable, disable, toggleSide],
  );

  return <OneHandContext.Provider value={value}>{children}</OneHandContext.Provider>;
}

/** Returns one-hand mode state and actions. */
export function useOneHand(): OneHandValue {
  const ctx = useContext(OneHandContext);
  if (!ctx) {
    throw new Error('useOneHand must be used inside <OneHandWindowsContainer>.');
  }
  return ctx;
}
