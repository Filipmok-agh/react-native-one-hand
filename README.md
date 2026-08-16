# react-native-one-hand

One-hand mode for React Native (iOS + Android). When activated, the **entire app** —
including native modals, alerts and action sheets — is scaled down to 75% of its size (by
default) and docked into a bottom corner of the screen, within thumb's reach. Everything
stays visible and fully interactive: scrolling and tapping keep working in the docked app.
(Typing is the exception: opening the keyboard exits the mode and the whole app returns to
full size — see the keyboard policy under Known limitations.)

Unlike the system one-handed modes (which only slide the screen down), the whole UI
remains on screen — just smaller and closer to your thumb.

<p align="center">
  <img
    src="https://raw.githubusercontent.com/Filipmok-agh/react-native-one-hand/main/assets/demo.gif"
    alt="One-hand mode on iOS: the app docks into the bottom-right corner; an Alert, a bottom sheet, the text-selection toolbar and the share sheet all dock with it, then the app switches to the left corner and exits the mode."
    width="280"
  />
</p>

<p align="center">
  <sub>
    Recorded in the
    <a href="https://github.com/Filipmok-agh/react-native-one-hand-example">example app</a>.
  </sub>
</p>

**Wrap once, nothing else.** In a native build the mode covers the native `Modal`,
`Alert.alert`, `ActionSheetIOS` (iOS) and third-party overlay libraries automatically —
no replacement components, no adapters, no per-library configuration.

## Requirements

- **iOS 15.1+**, or **Android 10 (API 29) or newer** (older Android devices: the module is
  inert — the app renders unchanged; see [ARCHITECTURE.md](./ARCHITECTURE.md) for why).
- **Expo SDK 55+ / React Native 0.76+** with the **Expo modules runtime** (`expo` package)
  and a **native build**
  (`expo prebuild` / `expo run:ios` / `expo run:android`). The library does not work in
  Expo Go — without the native module it logs a one-time warning and renders your app
  unchanged (one-hand mode is simply unavailable).

## Installation

```bash
npm install react-native-one-hand
```

Peer dependencies (install in your app): `expo` (SDK 55+), `react` (18+), `react-native`
(0.76+) — nothing else. Optionally, if `react-native-safe-area-context` is installed (it
ships in the default Expo template and is required by react-navigation), the library uses
it to reclaim the top safe-area inset while docked (see "Safe areas while docked").

Then rebuild the native app (`npx expo prebuild && npx expo run:ios` /
`npx expo run:android`, or your dev-build
pipeline) so the native module gets compiled in.

## Quick start

Wrap your entire app content with `OneHandWindowsContainer`, as high in the tree as
possible (inside `GestureHandlerRootView` / `SafeAreaProvider`). This is the only
required integration:

```tsx
import { OneHandWindowsContainer } from 'react-native-one-hand';

export default function RootLayout() {
  return <OneHandWindowsContainer>{/* navigation and all screens */}</OneHandWindowsContainer>;
}
```

## How users control it

- **Enter:** press and hold (~0.5 s) in a **bottom corner** of the screen — the right
  corner docks the app to the right, the left corner to the left.
- **Switch sides:** while docked, hold the opposite bottom corner **of the docked app
  itself** (the shrunk one). Holding the physical screen corner beyond the app means
  touching the backdrop, which exits the mode instead.
- **Exit:** tap the gray backdrop around the docked app (it shows a configurable hint).
- **Programmatically:** use the `useOneHand()` hook anywhere below the wrapper.

```tsx
import { useOneHand } from 'react-native-one-hand';

const { active, side, enable, disable, toggleSide } = useOneHand();

enable('left'); // enter the mode docked to the left ('right' for the right corner)
toggleSide(); // switch the docking side (left ↔ right)
disable(); // exit the mode
```

## Configuration

```tsx
<OneHandWindowsContainer
  scale={0.75} // downscale factor, 0.3–0.85 (default: 0.75)
  initialSide="right" // initial docking side (default: 'right')
  cornerGestures={{
    // enable/disable the press-and-hold activation gesture per corner
    left: false, // e.g. disable the left corner (default: both enabled)
    right: true,
  }}
  dismissHint="Touch anywhere to dismiss one-hand mode" // backdrop hint text
  backdropColor="#DCEEFB" // backdrop fill around the docked app (default: neutral gray)
  dismissHintColor="#0F2A43" // hint text color (default: dark gray)
>
  {children}
</OneHandWindowsContainer>
```

## Safe areas while docked

Docked, the app hangs from the **bottom** of the screen and its top edge is guaranteed
to sit below the notch / Dynamic Island / status bar (that is what the `0.85` scale cap
is for). Reserving space for a cutout the app is no longer under would be pure waste —
a scaled-down dead band at the top of the docked app. So while the mode is active the
library **zeroes the top safe-area inset** for your app subtree:

- Covered automatically: everything that reads `useSafeAreaInsets` /
  `SafeAreaInsetsContext` from `react-native-safe-area-context` — including
  react-navigation / expo-router headers and tab bars.
- The **bottom** inset is deliberately kept: the docked app's bottom edge coincides
  with the physical bottom of the screen, so the home indicator really does overlap it.
- Not covered (these do not read the context): the native `SafeAreaView` components
  (React Native's built-in one, and the one from `react-native-safe-area-context`,
  which computes insets natively since v3) and code measuring `StatusBar.currentHeight`
  directly. Prefer `useSafeAreaInsets` for layouts that should tighten up while docked.
- No `react-native-safe-area-context` installed, or no `SafeAreaProvider` above the
  container? The feature quietly does nothing.

## API reference

### `<OneHandWindowsContainer>`

The single required wrapper. A native module scales every window of the process — which
is why native and third-party overlays dock together with the app. A gray backdrop with a
hint is shown around the docked app; tapping it exits the mode.

When one-hand mode is unavailable (no native module — e.g. Expo Go — or Android older
than 10), the container logs a one-time warning and renders children unchanged;
`useOneHand()` keeps working but has no visual effect.

| Prop               | Type                                  | Default                                     | Description                                                                                                                                                                                                                                                                                  |
| ------------------ | ------------------------------------- | ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `scale`            | `number`                              | `0.75`                                      | Downscale factor, `0.3`–`0.85`. Out-of-range values fall back to the default with a warning. The `0.85` cap guarantees the docked app always clears the hardware cutout (enabling the top-inset handling — see "Safe areas while docked" above) and keeps the backdrop comfortably tappable. |
| `initialSide`      | `'left' \| 'right'`                   | `'right'`                                   | Initial docking side.                                                                                                                                                                                                                                                                        |
| `cornerGestures`   | `{ left?: boolean; right?: boolean }` | both `true`                                 | Enables/disables the press-and-hold activation gesture per corner.                                                                                                                                                                                                                           |
| `dismissHint`      | `string`                              | `"Touch anywhere to dismiss one-hand mode"` | Text shown on the backdrop.                                                                                                                                                                                                                                                                  |
| `backdropColor`    | `ColorValue`                          | neutral gray                                | Background color of the backdrop around the docked app. Any React Native color value.                                                                                                                                                                                                        |
| `dismissHintColor` | `ColorValue`                          | dark gray                                   | Color of the `dismissHint` text.                                                                                                                                                                                                                                                             |

### `useOneHand()`

Returns the mode state and actions. Must be called below the wrapper.

| Field           | Type                           | Description                                                                                            |
| --------------- | ------------------------------ | ------------------------------------------------------------------------------------------------------ |
| `active`        | `boolean`                      | Whether the mode is currently active.                                                                  |
| `side`          | `'left' \| 'right'`            | Current docking corner.                                                                                |
| `scale`         | `number`                       | Downscale factor in effect (the validated `scale` prop; out-of-range values fall back to the default). |
| `enable(side?)` | `(side?: OneHandSide) => void` | Enters the mode; optionally docks to the given side.                                                   |
| `disable()`     | `() => void`                   | Exits the mode.                                                                                        |
| `toggleSide()`  | `() => void`                   | Switches the docking side.                                                                             |

### `isOneHandWindowsAvailable`

`boolean` — whether one-hand mode can actually work here: the native module is compiled
in (a native build, not Expo Go) and, on Android, the device runs Android 10 (API 29) or
newer. `false` means one-hand mode is unavailable — useful for hiding your own UI entry
points to the mode.

### `DEFAULT_ONE_HAND_SCALE`

The default downscale factor (`0.75`).

### Types

`OneHandSide` (`'left' | 'right'`), `OneHandValue` (the shape returned by
`useOneHand()`), `CornerGesturesConfig` (the `cornerGestures` prop shape).

## What is covered

| Surface                                                                | Covered                                                                                  |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| App screens, navigation, lists, inputs                                 | ✅                                                                                       |
| In-tree overlays (e.g. `@gorhom/bottom-sheet`)                         | ✅                                                                                       |
| Portal-based overlays (`BottomSheetModal`, Paper)                      | ✅                                                                                       |
| Native `<Modal>` and libraries built on it (e.g. `react-native-modal`) | ✅                                                                                       |
| `Alert.alert`                                                          | ✅                                                                                       |
| `ActionSheetIOS` (iOS-only API)                                        | ✅ on iOS                                                                                |
| Text-selection edit menu (Cut / Copy / Paste)                          | ✅                                                                                       |
| Anchored popups (dropdowns, the selection toolbar)                     | ✅ (Android: some items in the toolbar's ⋮ overflow don't respond while docked)          |
| Windows other SDKs create (in-app banners, toasts, floating widgets)   | ✅ on iOS; Android: floating windows dock too, system toasts deliberately stay full-size |
| Share sheet, camera                                                    | iOS: ✅ (in-process, docks with the app); Android: separate activity, mode survives      |
| Photo / document picker, calendar editor (out-of-process UI)           | iOS: auto-exit; Android: separate activity, mode survives                                |
| Native fullscreen video (`expo-video` and friends)                     | iOS: ✅ (presented in the app's window); Android: separate activity, mode survives       |
| System keyboard                                                        | ❌ see below                                                                             |

## Known limitations

- **The system keyboard is not scaled — on either platform.** On Android this is a hard
  boundary: the IME is a separate application drawing its own window in its own process,
  out of the app's reach. On iOS scaling the keyboard turned out to be technically
  possible, but is deliberately not shipped: iOS reports the keyboard frame as an
  untransformed screen rect, and no docked position satisfies every consumer of that rect
  (see [KEYBOARD-FINDINGS.md](./KEYBOARD-FINDINGS.md)). The library therefore enforces a
  keyboard policy instead: **entering one-hand mode is blocked while the keyboard is
  open** (`enable()` and the corner gestures are no-ops), and **opening the keyboard
  while docked exits the mode automatically**, so a full-sized keyboard is never combined
  with a scaled-down app.
- **Out-of-process system UI exits the mode on iOS (real devices).** The photo picker,
  the Files/document picker and the calendar event editor render and hit-test in a
  separate system process anchored to untransformed screen geometry — under the dock
  transform their content draws misplaced and touches never connect (simulators do not
  reproduce this; their remote services render in-process). The library detects remote
  content in a presented hierarchy and exits the mode automatically, exactly like the
  keyboard policy, so those pickers always appear full-size and fully interactive.
  In-process system UI — the share sheet, the camera — keeps docking normally. On
  Android these pickers are separate full-screen activities; the mode survives the round
  trip and the app returns still docked.
- **Android requires Android 10 (API 29)+** — the public window-enumeration API
  (`WindowInspector`) does not exist earlier; the module is inert on older devices.
- **One-hand mode is portrait-only.** In landscape, `enable()` and the corner gestures
  are no-ops, and any window resize while docked exits the mode automatically — rotating
  is the common case, but entering split-screen or a foldable posture change does too (a
  controlled exit, mirroring the keyboard policy — a resize invalidates the native window
  geometry the mode relies on). Re-enter after rotating back to portrait.
- **Android with 3-button navigation: the corner gesture is hard to hit.** The navigation
  bar is a separate system window that takes touches in the bottom ~48 dp for itself, so
  very little of the corner hot zone reaches the app. Everything else works normally in
  this mode — including the backdrop and tap-to-exit — and `useOneHand().enable()` from
  your own UI is unaffected. Gesture navigation is not affected at all.

## Internals

Curious how it works — window transforms, the UIKit geometry trap, the backdrop window,
gesture design decisions? See [ARCHITECTURE.md](./ARCHITECTURE.md).

## Development

Run `npm install` in this directory first. Nothing runs automatically — these are the
commands:

| Command                | What it does                                                         |
| ---------------------- | -------------------------------------------------------------------- |
| `npm test`             | Unit tests (Jest) for the JS layer                                   |
| `npm run test:watch`   | The same, in watch mode                                              |
| `npm run typecheck`    | `tsc --noEmit` over `src`, including the tests                       |
| `npm run lint`         | ESLint — typescript-eslint plus the `react-hooks` rules              |
| `npm run lint:fix`     | The same, applying fixes                                             |
| `npm run format`       | Prettier, writing changes                                            |
| `npm run format:check` | Prettier, checking only                                              |
| `npm run check`        | All four checks in sequence — run this before opening a pull request |

The unit tests cover what can be verified without a device: the keyboard and portrait-only
policies, scale validation, the docked safe-area override, and the availability logic
(missing native module vs. Android below API 29). `expo` and
`react-native-safe-area-context` are peer dependencies that are deliberately not installed
here; the suite substitutes its own stand-ins (`src/__mocks__`).

Everything below the JS layer — the window transforms themselves, native overlays docking
with the app, edit-menu placement — needs a real build, and the
[example app](https://github.com/Filipmok-agh/react-native-one-hand-example) is the
fixture for it. Its **Overlays** and **Widgets** tabs collect the awkward cases —
`ActionSheetIOS` and a separate banner window on Overlays; an anchored selection toolbar
and a `DateTimePicker` dialog on Widgets.

## License

MIT
