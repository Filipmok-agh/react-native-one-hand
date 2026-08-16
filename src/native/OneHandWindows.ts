import { requireOptionalNativeModule } from 'expo';
import { Platform } from 'react-native';
import type { OneHandSide } from '../OneHandContext';

// Note: we intentionally do not use the `NativeModule` type from expo-modules-core.
// In SDK 56 the exported type resolves to `typeof` the class (a constructor type without
// instance members), so the module surface — including the EventEmitter's `addListener` —
// is declared inline here.
type EventSubscription = { remove: () => void };

type OneHandWindowsModule = {
  /** Subscribes to backdrop taps (a request to exit one-hand mode). */
  addListener: (eventName: 'onDismissRequest', listener: () => void) => EventSubscription;
  /**
   * Scales ALL windows of the process, docking them to the bottom corner on `side`.
   * Colors are `processColor` outputs (AARRGGBB ints); null = the native defaults.
   */
  setScale: (
    scale: number,
    side: OneHandSide,
    dismissHint: string,
    backdropColor: number | null,
    dismissHintColor: number | null,
  ) => Promise<void>;
  /** Restores all windows to full size and hides the backdrop. */
  reset: () => Promise<void>;
};

// `requireOptionalNativeModule` returns null when the native module is not compiled in
// (e.g. Expo Go). The container then logs a one-time warning and one-hand mode is
// unavailable — there is deliberately no JS fallback (see ARCHITECTURE.md).
const nativeModule = requireOptionalNativeModule<OneHandWindowsModule>('OneHandWindows');

// The Android module always compiles in but is INERT below API 29 (the public
// window-enumeration API does not exist there), so module presence alone would report
// availability on a device where nothing happens — and the flag is documented for hiding
// UI entry points. The runtime API level is therefore part of the answer.
const ANDROID_MIN_API = 29;
const supportedRuntime = Platform.OS !== 'android' || Number(Platform.Version) >= ANDROID_MIN_API;

/**
 * Why one-hand mode is unavailable, or null when it is available. Internal — lets the
 * container log an accurate warning (a missing module and a too-old Android are very
 * different problems for the person reading the log).
 */
export const oneHandUnavailabilityReason: 'missing-module' | 'unsupported-os' | null =
  nativeModule == null ? 'missing-module' : supportedRuntime ? null : 'unsupported-os';

/**
 * Whether one-hand mode can actually do anything in this binary on this device:
 * the native module is compiled in (a native build, not Expo Go) AND, on Android,
 * the runtime is API 29+.
 */
export const isOneHandWindowsAvailable = oneHandUnavailabilityReason == null;

// The native functions cannot reject in normal operation, but a call racing module
// teardown (JS reload, app shutdown) would surface as an unhandled rejection in the
// consumer's app. Swallow it: there is nothing for the caller to do about it, and the
// JS state stays the single source of truth either way.
function ignoreRejection(promise: Promise<void> | undefined): void {
  promise?.catch(() => undefined);
}

export function setWindowsScale(
  scale: number,
  side: OneHandSide,
  dismissHint: string,
  backdropColor: number | null,
  dismissHintColor: number | null,
): void {
  ignoreRejection(
    nativeModule?.setScale(scale, side, dismissHint, backdropColor, dismissHintColor),
  );
}

export function resetWindowsScale(): void {
  ignoreRejection(nativeModule?.reset());
}

/**
 * Subscribes to dismiss requests (a tap on the backdrop around the docked app).
 * Returns the subscription, or null when the native module is unavailable.
 */
export function addDismissListener(listener: () => void): EventSubscription | null {
  return nativeModule?.addListener('onDismissRequest', listener) ?? null;
}
