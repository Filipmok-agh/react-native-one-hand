// The single required integration point (wrap once). Everything else is driven through
// gestures, the backdrop, or the useOneHand() hook.
export { OneHandWindowsContainer } from './OneHandWindowsContainer';

// State and actions: enable(side?), disable(), toggleSide() + active/side/scale.
export {
  useOneHand,
  DEFAULT_ONE_HAND_SCALE,
  type OneHandSide,
  type OneHandValue,
} from './OneHandContext';

// Type of the wrapper's `cornerGestures` prop.
export type { CornerGesturesConfig } from './CornerGestures';

// Diagnostics: whether one-hand mode can work here — native module compiled in (any
// native build, not Expo Go) and, on Android, API 29+.
export { isOneHandWindowsAvailable } from './native/OneHandWindows';
