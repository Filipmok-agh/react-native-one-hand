import { useEffect, useMemo, type PropsWithChildren } from 'react';
import { processColor, StyleSheet, View, type ColorValue } from 'react-native';
import { CornerGestures, type CornerGesturesConfig } from './CornerGestures';
import { DockedInsets } from './DockedInsets';
import {
  DEFAULT_ONE_HAND_SCALE,
  OneHandProvider,
  useOneHand,
  type OneHandSide,
} from './OneHandContext';
import {
  addDismissListener,
  isOneHandWindowsAvailable,
  oneHandUnavailabilityReason,
  resetWindowsScale,
  setWindowsScale,
} from './native/OneHandWindows';

const DEFAULT_DISMISS_HINT = 'Touch anywhere to dismiss one-hand mode';

let warnedUnavailable = false;

type OneHandWindowsContainerProps = PropsWithChildren<{
  /**
   * Downscale factor, 0.3–0.85. Out-of-range values fall back to the default with a
   * one-time warning. The 0.85 cap guarantees the docked app's top edge clears the
   * hardware cutout on every device (which is what allows the top safe-area inset to
   * be zeroed while docked) and keeps a comfortably tappable backdrop. Defaults
   * to 0.75.
   */
  scale?: number;
  /** Initial docking side. Defaults to 'right'. */
  initialSide?: OneHandSide;
  /**
   * Corner activation gestures: press and hold in a bottom corner of the screen to enter
   * the mode docked to that side. Can be disabled per corner, e.g.
   * `cornerGestures={{ left: false }}`. Both corners are enabled by default.
   */
  cornerGestures?: CornerGesturesConfig;
  /** Hint shown on the backdrop around the docked app (tapping the backdrop exits the mode). */
  dismissHint?: string;
  /**
   * Background color of the backdrop around the docked app. Any React Native color value
   * (`'#DCEEFB'`, `'rgba(…)'`, a named color). Defaults to a neutral gray.
   */
  backdropColor?: ColorValue;
  /** Color of the `dismissHint` text. Defaults to a dark gray. */
  dismissHintColor?: ColorValue;
}>;

/**
 * The single required integration point of the library (wrap once): wrap your entire app
 * content with this component, as high in the tree as possible (inside
 * `GestureHandlerRootView` / `SafeAreaProvider`).
 *
 * A native module transforms every window of the process, so the native `Modal`,
 * `Alert.alert`, `ActionSheetIOS`, and third-party overlays dock together with the app
 * automatically. A gray backdrop with a hint is shown around the app; tapping it exits
 * the mode.
 *
 * Requires a native build (`expo prebuild` + `expo run:ios` / `expo run:android`;
 * Android 10+ at runtime). When one-hand mode is unavailable — the native module is not
 * compiled in (e.g. Expo Go), or the device runs Android older than 10 — the container
 * renders children unchanged and logs a one-time warning.
 *
 * Controls: corner press-and-hold gestures, a tap on the backdrop (exit), and the
 * programmatic API via `useOneHand()` → `enable(side?)` / `disable()` / `toggleSide()`.
 */
export function OneHandWindowsContainer({
  children,
  scale = DEFAULT_ONE_HAND_SCALE,
  initialSide = 'right',
  cornerGestures,
  dismissHint = DEFAULT_DISMISS_HINT,
  backdropColor,
  dismissHintColor,
}: OneHandWindowsContainerProps) {
  if (!isOneHandWindowsAvailable) {
    if (!warnedUnavailable) {
      // Deliberate impurity: a once-per-process dev warning. The write is idempotent and
      // nothing renders from it, so a render React discards at worst costs a duplicate log.
      // eslint-disable-next-line react-hooks/globals
      warnedUnavailable = true;
      console.warn(
        oneHandUnavailabilityReason === 'unsupported-os'
          ? 'react-native-one-hand: one-hand mode is disabled — it requires Android 10 ' +
              '(API 29) or newer, and this device runs an older version. The app renders ' +
              'unchanged.'
          : 'react-native-one-hand: native module not found — one-hand mode is disabled. ' +
              'The library requires a development/native build (`expo prebuild` + ' +
              '`expo run:ios` / `expo run:android`); ' +
              'it does not work in Expo Go.',
      );
    }
    // Keep the provider mounted so useOneHand() consumers do not crash; enable() will
    // update JS state only, with no visual effect.
    return (
      <OneHandProvider scale={scale} initialSide={initialSide}>
        <View style={styles.root}>{children}</View>
      </OneHandProvider>
    );
  }

  return (
    <OneHandProvider scale={scale} initialSide={initialSide}>
      <Inner
        cornerGestures={cornerGestures}
        dismissHint={dismissHint}
        backdropColor={backdropColor}
        dismissHintColor={dismissHintColor}
      >
        {children}
      </Inner>
    </OneHandProvider>
  );
}

type InnerProps = PropsWithChildren<{
  cornerGestures?: CornerGesturesConfig;
  dismissHint: string;
  backdropColor?: ColorValue;
  dismissHintColor?: ColorValue;
}>;

function Inner({
  children,
  cornerGestures,
  dismissHint,
  backdropColor,
  dismissHintColor,
}: InnerProps) {
  const { active, side, scale, disable } = useOneHand();

  // Colors cross the bridge as processColor ints (AARRGGBB); null = native defaults.
  // An unparseable value also falls back to the defaults rather than crashing.
  const processedBackdropColor = useMemo(
    () => (processColor(backdropColor) as number | null | undefined) ?? null,
    [backdropColor],
  );
  const processedHintColor = useMemo(
    () => (processColor(dismissHintColor) as number | null | undefined) ?? null,
    [dismissHintColor],
  );

  // Keep the native module in sync with the JS state.
  useEffect(() => {
    if (active) {
      setWindowsScale(scale, side, dismissHint, processedBackdropColor, processedHintColor);
    } else {
      resetWindowsScale();
    }
  }, [active, side, scale, dismissHint, processedBackdropColor, processedHintColor]);

  // A tap on the native backdrop requests exiting the mode.
  useEffect(() => {
    const subscription = addDismissListener(disable);
    return () => subscription?.remove();
  }, [disable]);

  // Never leave windows transformed if the container unmounts.
  useEffect(() => {
    return () => resetWindowsScale();
  }, []);

  return (
    <View style={styles.root}>
      <CornerGestures {...cornerGestures}>
        {/* While docked, the top safe-area inset is zeroed for the whole subtree —
            the docked app's top edge is guaranteed to sit below the hardware cutout
            (see MAX_SCALE), so reserving space for it is pure waste. */}
        <DockedInsets active={active}>{children}</DockedInsets>
      </CornerGestures>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
});
