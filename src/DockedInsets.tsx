import { useContext, useMemo, type Context, type PropsWithChildren } from 'react';

type EdgeInsets = { top: number; right: number; bottom: number; left: number };

/**
 * `SafeAreaInsetsContext` from react-native-safe-area-context, resolved lazily and
 * optionally: the package is an OPTIONAL peer dependency, so the require lives in a
 * try/catch (Metro marks try/catch-wrapped requires as optional dependencies — the
 * bundle builds without the package installed).
 */
let InsetsContext: Context<EdgeInsets | null> | null = null;
try {
  InsetsContext = require('react-native-safe-area-context').SafeAreaInsetsContext ?? null;
} catch {
  InsetsContext = null;
}

type DockedInsetsProps = PropsWithChildren<{
  /** Whether one-hand mode is currently active (the app is docked). */
  active: boolean;
}>;

/**
 * Zeroes the TOP safe-area inset for the app subtree while the mode is active.
 *
 * Rationale: docked, the app hangs from the BOTTOM of the screen and its top edge sits
 * far below the hardware cutout (guaranteed by the 0.85 scale cap — see MAX_SCALE), so
 * any top inset the app applies is pure wasted space, scaled down and plainly visible
 * as a dead band above the content. The bottom inset is deliberately KEPT: the docked
 * app's bottom edge coincides with the physical screen bottom, so the home indicator
 * really does overlap it. Left/right insets are zero in portrait, and the mode is
 * portrait-only.
 *
 * Coverage: everything that reads `useSafeAreaInsets` / `SafeAreaInsetsContext` —
 * including react-navigation headers and tab bars. NOT covered (documented in the
 * README): the native `SafeAreaView` components (both React Native's built-in one and
 * the one from react-native-safe-area-context, which since v3 computes insets natively
 * without reading this context), and any code measuring `StatusBar.currentHeight`
 * directly.
 *
 * When react-native-safe-area-context is not installed, or there is no
 * `SafeAreaProvider` above the container, this renders children unchanged.
 */
export function DockedInsets({ active, children }: DockedInsetsProps) {
  if (!InsetsContext) return children;
  return <DockedInsetsOverride active={active}>{children}</DockedInsetsOverride>;
}

/** Split out so hooks are only called when the optional module is present. */
function DockedInsetsOverride({ active, children }: DockedInsetsProps) {
  const Ctx = InsetsContext!;
  const insets = useContext(Ctx);
  const value = useMemo(
    () => (insets && active ? { ...insets, top: 0 } : insets),
    [insets, active],
  );
  // No SafeAreaProvider above the container — nothing to override.
  if (!insets) return children;
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
