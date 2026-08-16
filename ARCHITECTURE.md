# Architecture & Internals

This document explains how `react-native-one-hand` works under the hood, why it is built
the way it is, and which platform quirks it has to work around. It is the reference for
anyone developing the library further. For user-facing documentation, see
[README.md](./README.md).

## The problem in one sentence

A React Native "shrink the app" transform can only affect what renders inside the
transformed React subtree — but several important surfaces (native `Modal`, `Alert.alert`,
`ActionSheetIOS`, the system keyboard) render in **separate native windows**, outside any
React subtree, so they escape every JS-level transform.

The library therefore operates at the native window level — twin engines,
`ios/OneHandWindowsModule.swift` and `android/.../OneHandWindowsModule.kt` — and covers
everything except the system keyboard and, on iOS real devices, out-of-process (remote)
system UI — both handled by explicit policies described below.

There is deliberately **no JS fallback**: a pure-JS in-tree transform cannot reach
native-window overlays, so it would silently lack the library's core promise. When the
native module is not compiled in (e.g. Expo Go), `OneHandWindowsContainer` logs a
one-time warning and renders children unchanged — detection is
`requireOptionalNativeModule('OneHandWindows')` returning `null`, plus a runtime
Android-API-29 check (`oneHandUnavailabilityReason` distinguishes `missing-module` from
`unsupported-os`; on older Android the module is compiled in but inert — see "Gating" in
the Android section). (A JS fallback engine
existed early on and was removed; it also carried the library's only runtime
dependencies — reanimated etc. — so dropping it slimmed the peer-dependency surface to
`expo`/`react`/`react-native`.)

## Module map

```
src/
  index.ts                     public API (kept intentionally minimal)
  OneHandContext.tsx           state: { active, side, scale } + enable/disable/toggleSide
  OneHandWindowsContainer.tsx  THE wrapper: provider + availability check + gestures + native sync
  CornerGestures.tsx           corner press-and-hold activation (passive touch observation)
  DockedInsets.tsx             zeroes the top safe-area inset while docked (optional peer)
  native/OneHandWindows.ts     typed bridge to the native module (optional, event listener)
  __tests__/, __mocks__/       JS-layer unit tests (see README "Development"); not published
ios/
  OneHandWindowsModule.swift  the iOS engine + backdrop window
  OneHandWindows.podspec      CocoaPods spec (ExpoModulesCore dependency)
android/
  build.gradle                expo-module-gradle-plugin library config
  src/main/java/expo/modules/onehand/OneHandWindowsModule.kt  the Android engine
expo-module.config.json       Expo autolinking entry (apple + android modules)
```

## Why overlays escape (mechanism, verified against RN 0.85 sources)

The deciding factor is **where a surface renders relative to the transform boundary**,
not its API:

| Surface                                    | Native reality                                                                                                                                                                                | Consequence                                                                                                                                                     |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| RN `<Modal>`                               | presents a view controller **in the app's own window** (`RCTModalHostView` → `presentViewController:` on the React VC); its JS children stay in the same React tree                           | escapes a JS subtree transform, but IS covered by a window-level transform                                                                                      |
| `Alert.alert`                              | `RCTAlertController` lazily creates **its own `UIWindow`** at `UIWindowLevelAlert + 1` and calls `makeKeyAndVisible`; content is fully native (no React children)                             | escapes both a JS transform and a transform applied only to the main window — the window must be caught as it appears                                           |
| `ActionSheetIOS`                           | presented in the app window                                                                                                                                                                   | covered by the window-level transform                                                                                                                           |
| System keyboard                            | renders in `UIRemoteKeyboardWindow` — an app-owned window hosting the real keyboard view tree, but **not published in `UIWindowScene.windows`**, so the module's enumeration never reaches it | stays full-sized; scaling it IS feasible but deliberately parked — see [KEYBOARD-FINDINGS.md](./KEYBOARD-FINDINGS.md)                                           |
| Portals (`@gorhom`, Paper)                 | plain React rendering at the host's tree position                                                                                                                                             | trivially covered — they live in the app window like everything else                                                                                            |
| `FullWindowOverlay` (react-native-screens) | a native layer intentionally above everything                                                                                                                                                 | docks with the app (verified live on the iOS 26 simulator): it layers above the app's content, and the window transform covers it like any other in-app surface |

## Native engine: "all windows" mode

### Core loop

On `setScale(scale, side, dismissHint, backdropColor, dismissHintColor)` the module:

1. Derives all docking geometry from the **screen** bounds (never from a window's frame —
   see the geometry trap below) in one place, the `DockGeometry` struct: the dock
   translation `t = (±W·(1−s)/2, H·(1−s)/2)` (the sign selects the left/right corner), the
   resulting on-screen `dockRect`, and the point mapping `screen = C + t + s·(p − C)`
   where `C` is the screen center. Everything else — the per-window transform, the
   reconciler's equality guards, the selection mapping — consumes that one struct, so the
   pieces cannot drift apart (a drifted guard silently disables the edit-menu reconciler).
2. Applies a transform to **every** `UIWindow` in every connected scene
   (`UIApplication.connectedScenes → UIWindowScene.windows`), excluding the backdrop
   window and keyboard windows. UIKit anchors a view transform at the view's **own
   center**, so each window is scaled about its center and translated so that this center
   lands where `DockGeometry` maps it. For a full-screen window the window center IS the
   screen center, so this reduces exactly to `translate(t) ∘ scale(s)` — the classic
   bottom-corner dock. A deliberately non-full-screen window (see below) scales in place
   and travels toward the corner proportionally.
3. Subscribes to window lifecycle notifications and **re-asserts** the transform on all
   windows whenever anything happens:
   - `UIWindow.didBecomeVisibleNotification` — a NEW window appeared (this is how alert /
     action-sheet / third-party overlay windows get caught; it is public API),
   - `UIWindow.didBecomeKeyNotification` — key-window changes (`makeKeyAndVisible` of an
     alert) can re-layout other windows,
   - `UIWindow.didBecomeHiddenNotification` — dismissals also touch window geometry,
   - `UIResponder.keyboardDidShowNotification` — keyboard presentation may reset geometry.

   Re-assertion runs three times: immediately, on the next runloop turn, and after
   ~0.45 s (past the presentation animation). `apply(to:)` is idempotent, so redundant
   passes cost nothing.

### The UIKit geometry trap (the hardest bug — read this before touching `apply(to:)`)

`UIView.frame` is **undefined while `transform != identity`** (documented UIKit behavior).
During presentations (observed live with `Alert.alert`) UIKit re-sets the main window's
_frame_ to screen size. With our transform still installed, the window's **bounds inflate
to `frame / scale`** — the window _looks_ full-sized again even though `window.transform`
still equals the target. Any repair logic that only compares transforms will conclude
nothing is wrong and skip the fix (this exact failure shipped in the first two iterations
of the module).

The fix: `apply(to:)` also validates `bounds.size` — against the window's **own recorded
frame** (see "Non-full-screen windows" below), not against the screen. On mismatch it
normalizes geometry in strict order — `transform = .identity` → `frame = the recorded
frame` → re-apply the target transform. `reset()` performs the same normalization after
removing transforms.

Crucially, this repair path must be **instantaneous** (all three property sets in one
render transaction, no `UIView.animate`): the intermediate identity state must never
paint. An earlier version animated the re-application, which produced a visible
"expand then shrink" glitch during alert presentation and a white full-screen flash
during modal dismissal. Animation is used **only** for the user-visible transitions
driven by `setScale` — entering the mode and switching sides. Every notification-driven
pass snaps, including the first application to a freshly appeared alert/overlay window:
animating there made alerts visibly "slide down" from full size instead of appearing
already docked.

### Non-full-screen windows (banner SDKs, floating widgets)

iOS lets an app create `UIWindow`s that are deliberately smaller than the screen — in-app
banner SDKs (Firebase In-App Messaging in banner mode), toast libraries, floating
widgets. Treating "bounds ≠ screen size" as proof of the geometry trap therefore
misfires on them, and the consequence was severe: entering the mode "repaired" a 300×64
banner window to full screen, and `reset()` normalized it the same way, leaving it
**permanently full-screen above the app, swallowing every touch until an app restart**
(reproduced with the example app's `banner-window` fixture, which exists for exactly this
case).

The module therefore records each window's untransformed `frame` while the window is at
identity — the only moment `frame` is defined — in `managedFrames` (weak keys). That
recorded frame is what the repair path and `reset()` restore, so the entire class of "we
normalized a foreign window to screen size" bugs is gone by construction. The frame is
re-recorded on every sighting at identity, which covers an owner repositioning its window
while the mode is off. A window first seen carrying a **foreign transform** is left alone
entirely: its true geometry is unknowable, so the conservative choice is not to touch it.

Because the per-window transform is anchored at each window's own center (see the core
loop), such a window then docks _with_ the app — it scales in place and moves toward the
corner proportionally, staying glued to the content it belongs to — rather than being
excluded like the keyboard.

### The backdrop window

The area around the docked app would otherwise be black (nothing renders there — all
windows are scaled away). The module creates one dedicated, **unscaled** `UIWindow` at
`UIWindow.Level.normal − 1` (just below application windows) hosting
`OneHandBackdropController`: a gray fill by default (`UIColor(white: 0.72)`; both the
fill and the hint color are configurable via the container's `backdropColor` /
`dismissHintColor` props), a hint label pinned to the top safe area, and a tap
recognizer. A tap emits the `onDismissRequest` event through
the Expo module event emitter; the JS side (`OneHandWindowsContainer`) listens and calls
`disable()` — the native side never mutates mode state on its own, JS state stays the
single source of truth.

Why taps reach the backdrop: hit-testing converts the touch point through each window's
transform, so a scaled-down window returns `nil` for touches outside its visual area and
the system falls through to the next window — the backdrop. (Note: iOS 18 reportedly made
visible interactive windows greedier in `hitTest`; this configuration worked in our live
verification on iOS 26, but keep it in mind if touch fall-through ever regresses.)

The backdrop window is created lazily on first enable, then hidden/shown on subsequent
toggles. It is skipped by both `apply(to:)` and `reset()`. On disable it stays visible
until the restore animation finishes (~0.3 s) — hiding it up front would expose black
behind the still-shrunk app for the duration of the animation. That delayed hide is a
**cancellable** `DispatchWorkItem` (`pendingBackdropHide`), cancelled both by a newer
`reset()` and by `showBackdrop()` on re-enable; the `active` check inside it is a second
line of defense. With a bare timer, a rapid disable→enable→disable let the FIRST
disable's timer fire in the middle of the second exit animation and hide the backdrop
under the still-shrunk app (~100 ms of black — reproduced with a scripted sequence and
confirmed frame by frame on video). The Android engine guards the mirror-image case with
`pendingBackdropRestore`.

### Keyboard exclusion

The system keyboard stays full-sized while docked, for a mundane reason:
`UIRemoteKeyboardWindow` is an app-owned window hosting the real keyboard view tree
in-process, but it is **not published in `UIWindowScene.windows`** (measured), so
`forEachWindow` never reaches it and no transform is ever applied. The `isKeyboardWindow` predicate (class-name substring
`"KeyboardWindow"`, no private API) is a defensive guard for the day the OS publishes
it — not the active mechanism.

Scaling the keyboard IS technically possible: the window is reachable through
`UIWindow.didBecomeVisibleNotification`, it accepts a transform, and typing through the
scaled window works. A complete implementation was built, verified live, and
deliberately parked — iOS reports the keyboard frame as an untransformed screen rect,
and no dock position satisfies both families of consumers of that rect. The full
investigation, working recipe and geometry algebra are in
[KEYBOARD-FINDINGS.md](./KEYBOARD-FINDINGS.md). Until that ships, the JS keyboard policy
(block entry, auto-exit) keeps a full-sized keyboard from ever combining with a docked
app.

`UITextEffectsWindow` is **deliberately NOT excluded**, despite its keyboard-accessory
role. It is an in-process window hosting the text-selection edit menu; leaving it
unscaled rendered that menu at full size next to a docked app. It is scaled — and then
repositioned, see the next section. (Its accessory role does not matter while docked: the
keyboard policy exits the mode whenever the keyboard opens, and `reset()` restores the
window at that point.)

### Remote-content policy (out-of-process pickers)

The photo picker (`PHPickerViewController`), the Files/document picker and the calendar
event editor host their content as **remote views** (`_UIRemoteView`): the UI renders and
hit-tests in a separate system process, anchored to untransformed screen geometry. Under
the dock transform — verified live on an iPhone 13 Pro Max (iOS 18.7), via an XCUITest
probe — the remote grid painted full-size across the docked sheet, its accessibility
frame was larger than the screen with a negative origin, and taps on it never connected;
only the host-side chrome (Cancel) responded. **Simulators do not reproduce any of
this** — their remote services render in-process, so the sheet scales perfectly there.
A transform in this process cannot reach another process's geometry, so this is a hard
boundary of the same kind as the Android keyboard.

The module therefore mirrors the keyboard policy. `remotePolicyTick()` (piggybacked on
the edit-menu reconciler's display link) watches for a NEW presented view controller —
walking to the topmost presentation level, so a picker opened from inside a modal is
caught too — and scans its subtree for `_UIRemoteView` for ~2 s after it appears (the
remote view attaches asynchronously). On detection it emits `onDismissRequest`: the JS
side exits the mode and the picker is full-size and fully interactive. Detection is
generic rather than a class blocklist:
in-process system UI (the share sheet, the camera) never contains a remote view and keeps
docking, and any OS version that hosts a given picker in-process keeps docking it too.

A suspension variant — windows return to identity while the picker is up and the dock
re-applies automatically on dismissal, without leaving the mode — was also built and
verified end-to-end on device, then REJECTED as a product decision: the app jumping back
into the corner after every picker felt worse than a clean exit. This paragraph is its
record, should that preference ever flip.

Do not retry the counter-scale idea without new information — it was tried and measured
(device, iOS 18.7): scaling `_UIRemoteView` by the dock scale does put the remote grid
back inside the docked sheet visually, and even restores host-side touch routing (before
it, touches over the picker bypassed every app window's `sendEvent`; after it, they
hit-test into `_UIRemoteView`). But touches forwarded into the remote view produce NO
reaction in the remote process under any coordinate-mapping hypothesis (window space,
screen space, extra-scale) — the remote hit-tests interactions against its own inflated
geometry (screen/scale, e.g. 570.7×1234.7 on a 428×926 screen; the same inflation is
visible on `UITextEffectsWindow.bounds`), negotiated over a private XPC channel the host
cannot influence. "Looks right but doesn't respond" is worse than the auto-exit.

### The edit-menu placement trap

The text-selection edit menu (Cut / Copy / Paste) renders in `UITextEffectsWindow` — an
in-process window that IS scaled by the module (excluding it left the menu full-sized).
Scaling alone misplaces it though: UIKit computes the menu's position from the
selection's on-**screen** rect (which already reflects the app window's dock transform)
and writes those screen coordinates into the `_UIEditMenuContainerView`-local frame of
the menu, assuming the hosting window maps 1:1 onto the screen. With our transform on
that window the position gets mapped a **second** time, landing the menu ~60–90 pt away
from the docked text (correct scale, wrong place — verified live on iOS 26 by dumping
the native hierarchy: the menu's window-local frame equals the docked selection's screen
rect plus the standard 8 pt gap).

The fix is a per-frame reconciler (`CADisplayLink`, running only while the mode is
active — the iOS analog of the Android `Choreographer` pass): every edit-menu container
in a docked window gets a translation `d = (L' − C − t) / s − (L − C)`, where `L` is the
geometric center of the menu platters as UIKit laid them out, `L'` that center clamped
into the dock rect, `C` the window center and `t` the dock translation. The window
transform then renders the menu's center exactly at `L'` — scaled by the window,
positioned where UIKit aimed.

A sub-trap inside the trap: the platter's geometric rect must be computed from `bounds`
via `convert`, never read off `frame`/`center`. UIKit parks the platter's layer
**anchorPoint at the presentation-zoom origin** (observed live: `anchorPoint (1,1)`),
and `UIView.center` returns the anchor's position — for a `(1,1)` anchor that is the
bottom-RIGHT corner, ~170 pt away from the actual middle of a full-width menu. The first
iteration of the reconciler pinned `center` and misplaced exactly those menus whose zoom
origin sat far from their middle.

Second sub-trap: **while docked, UIKit always picks the BELOW-the-selection placement**,
although the full-size system behavior is menu-above-selection. The bias is source-side —
verified live by excluding the text-effects window from the transform entirely: the
(then full-size) menu still landed below the docked selection, so UIKit's above/below
decision breaks on the transformed selection geometry itself and no window-transform
arrangement can restore "above". The reconciler therefore re-decides the side: it reads
the real selection rect from the first responder via the public `UITextInput` protocol
(`selectedTextRange` → `selectionRects(for:)`, `caretRect` fallback), maps it through the
dock transform to screen coordinates, and places the menu above the selection when the
dock has room, below otherwise. When there is no text selection to anchor to (a non-text
edit menu), it keeps UIKit's intended spot, just clamped into the dock rect.

The clamp into the dock rect is a tappability requirement, not cosmetics: UIKit clamps
the menu to _screen_ margins, so for a selection near the docked app's edge the intended
position sticks out of the dock rect — and any part of a window rendered outside its
transformed bounds is unreachable (`hitTest` bounds-checks the window before descending),
so taps there fall through to the backdrop and exit the mode (verified live: an
unclamped "Cut" at screen x≈1–75 pt with the dock starting at x=100.5 dismissed the mode
instead of cutting).

Containers are matched by class-name substring (no private API, same policy as keyboard
windows), the pass is idempotent (equality-guarded), and `reset()` animates the
containers back together with their windows so a still-visible menu travels coherently
with the restoring app.

Because the pass runs on every frame the mode is active — up to 120×/s — the class-name
predicates are memoized per class object rather than re-deriving the name each time:
`NSStringFromClass` allocates a bridged `String` and `contains` then scans it, and that
happened for every window plus every one of its subviews, every frame. A class's name
cannot change, so the answer is computed once and looked up from then on. The window
enumeration itself is likewise written as plain nested loops instead of
`compactMap`/`flatMap`, which built two throwaway arrays per call.

### Event & state flow

```
corner hold ──▶ enable(side) ─┐
backdrop tap ─▶ onDismissRequest ─▶ disable() ─┤   (JS context state)
useOneHand() calls ───────────┘                │
                                               ▼
                    OneHandWindowsContainer effect
                     active ? setScale(scale, side, hint, colors) : reset()
```

The native module is a pure executor; it holds no authoritative state beyond what is
needed to re-assert transforms between JS updates. Both native modules also mirror the
JS input validation at the bridge (scale clamped to 0.3–0.85, non-finite → the default;
the dismiss hint length-capped): the module registry is callable by any in-process code,
so the native layer does not trust the JS clamp alone.

**Keyboard policy** (enforced in the provider, so it covers gestures and programmatic
calls alike): `enable()` is a no-op while the system keyboard is visible, and the mode
auto-exits when the keyboard opens while docked. Visibility is tracked via
`Keyboard.isVisible()` + `keyboardWill/DidShow/Hide` listeners. Rationale: the keyboard
is not scaled (see "Keyboard exclusion"), and a full-sized keyboard over a docked app is
not a usable state.

**Orientation policy** (in the provider, cross-platform): one-hand mode is
**portrait-only**.

- `enable()` — and therefore the corner gestures — is a no-op while the window is
  landscape (`width > height`).
- When the window dimensions change while the mode is active, the mode auto-exits — a
  controlled version of what rotation would otherwise do violently. Verified live on
  iOS 26 (via `expo-screen-orientation` locks): rotating while docked re-sets window
  frames under the active transform, the frame/bounds trap inflates bounds to
  `frame / scale`, and the docked app silently returns to visual FULL SIZE covering the
  whole screen — which also makes the backdrop unreachable for taps, so the mode could
  not even be exited (a stuck state requiring an app restart). None of the observed
  window notifications fire on rotation (especially a programmatic `lockAsync`), so the
  exit is driven from JS: the provider watches `Dimensions` 'change' events.

The iOS module additionally defends itself around that JS-driven exit (verified live:
without this, rotating while docked left black bands and misplaced content). All frames
in `managedFrames` were recorded for the screen geometry the mode was entered with
(`dockScreenBounds`); after a rotation they are stale — in the OLD orientation. So once
the screen bounds no longer match: `apply(to:)` refuses to touch windows (the repair path
re-setting a pre-rotation frame would fight UIKit's own re-layout), and `reset()` snaps
transforms to identity un-animated, restores formerly full-screen windows to the CURRENT
screen bounds instead of their stale recorded frames, and drops the stale records.

The old 180° edge case (landscape-left ↔ landscape-right does not change dimensions and
slipped past the resize exit) is moot under the portrait-only policy: the mode can no
longer be active in landscape at all, and a portrait 180° flip keeps the transforms
valid.

**Safe-area policy** (`DockedInsets`, JS-only): while docked, the top inset is zeroed
for the app subtree by re-providing `SafeAreaInsetsContext` (from
react-native-safe-area-context, an OPTIONAL peer resolved via a try/catch require —
Metro treats those as optional dependencies) with `{...insets, top: 0}`. The geometry
that makes an unconditional zero safe is the `MAX_SCALE = 0.85` cap: the docked top
edge renders at `height · (1 − scale)` ≥ 15% of the screen, while the tallest hardware
cutouts (Dynamic Island) would only reach it at scale ≈ 0.93. The bottom inset is kept
— the dock's bottom edge IS the physical screen bottom, the home indicator genuinely
overlaps it. Left/right are zero in portrait and the mode is portrait-only. Native
`SafeAreaView` components bypass the JS context (safe-area-context's is native since
v3) and are documented as not covered; the transform itself does not change what UIKit
reports natively, because safe-area insets are computed against the window's
UNtransformed geometry.

### Verified live (iOS 26 simulator, dev build)

| Case                                                                                                   | Result                                                                                                                                                                                                                            |
| ------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Native `<Modal>`                                                                                       | docks with the app                                                                                                                                                                                                                |
| `Alert.alert`                                                                                          | docks; appears docked from its first frame (snap-on-notification verified by frame-by-frame video analysis); app stays docked during and after                                                                                    |
| `ActionSheetIOS`                                                                                       | docks                                                                                                                                                                                                                             |
| `react-native-modal` (raw, third-party)                                                                | docks; AX frames match the dock transform to <0.005                                                                                                                                                                               |
| Text-selection edit menu (Cut/Copy/Paste)                                                              | docks: scaled by the text-effects window transform and repositioned next to the selection, clamped into the dock rect; items work (Cut/Copy verified on both dock sides, mode stays active)                                       |
| Non-full-screen window (300×64 banner, `banner-window` fixture)                                        | docks with the app; returns to its exact original frame on exit; a banner shown WHILE docked appears already docked                                                                                                               |
| `FullWindowOverlay` (react-native-screens)                                                             | docks: opened while docked it renders inside the docked app region (backdrop and hint stay visible), closes cleanly, mode stays active                                                                                            |
| System keyboard                                                                                        | stays full-sized (verified before the keyboard policy existed); the policy now auto-exits the mode when the keyboard opens — also verified live (tapping an input inside the docked app exits the mode, keyboard opens full-size) |
| Landscape entry attempt / rotating while docked                                                        | landscape `enable()` is blocked (portrait-only policy); rotation performs the controlled auto-exit in both directions with clean geometry after the rotation-safe reset, re-entry back in portrait works immediately              |
| Backdrop tap-to-dismiss, corner gestures, exit animation (gray backdrop stays until restore completes) | works                                                                                                                                                                                                                             |
| Side switch by holding the docked app's opposite corner                                                | works; holding the physical screen corner beyond the app hits the backdrop and exits instead                                                                                                                                      |
| Rapid disable→enable→disable inside the backdrop-hide window                                           | no black frame (cancellable `pendingBackdropHide`; checked frame by frame on video)                                                                                                                                               |

Additional log-verified facts (diagnostic `debugLogging` + `log stream`):

- **RN `<Modal>` open/close emits NO window notifications at all** — the presentation is
  fully in-window. The module is blind to it, and that is fine: the window transform
  simply persists. Do not expect `didBecomeVisible/Hidden` hooks for RN modals.
- `Alert.alert` presentation: `didBecomeVisible` (new alert window — snapped while still
  at identity/screen-sized) followed ~30 ms later by `didBecomeKey`, at which point one
  window shows inflated bounds (`screen / scale`) and is repaired in the same pass.
- A backdrop-flicker seen when closing `react-native-modal` was traced by frame-by-frame
  video comparison to react-native-modal itself (identical with one-hand mode OFF): its
  backdrop does not fade in on open (New Architecture quirk) and pops in before fading on
  close. Working workaround: `backdropTransitionOutTiming={1}` — note the
  upstream-documented `{0}` does NOT work (the animation library treats 0 as falsy and
  falls back to its ~300 ms default).

## Corner activation gestures

`CornerGestures` wraps the app content in a plain `View` that **observes touches without
claiming them**, via the passive `onTouchStart` / `onTouchMove` / `onTouchEnd` props.
Those bubble to ancestors regardless of which view holds the responder, so regular taps
pass through to the UI underneath even inside the corner hot zones:

- `onTouchStart` checks whether the touch began inside a 56 pt square anchored at an
  enabled bottom corner; if so, a 450 ms timer arms `enable(side)`.
- Movement beyond 16 pt (`onTouchMove`), an early release (`onTouchEnd`), a cancel, or
  unmount clears the timer.
- The tradeoff of observing rather than claiming: a child sitting exactly in a corner may
  react to the same long press. Acceptable — the zones are the physical screen corners.

Rejected alternatives, for the record:

- _Responder capture phase_ (`onStartShouldSetResponderCapture` returning `true` inside
  the corner zones) — the original implementation. It claims the touch, which **swallowed
  taps**: buttons whose edges reached a corner appeared dead. Replaced by passive
  observation.
- _Corner-positioned `Pressable` hotspots_: same problem, worse — they consume ALL touches
  in the corner (in practice: the edges of the first/last tab).
- _react-native-gesture-handler `LongPress` on the whole app_: without manual activation
  it cancels child interactions on every long hold anywhere; with manual activation it
  needs worklet-side timers — more machinery for no benefit.

Coordinate note: touch coordinates arrive in the app window's coordinate space, which is
unaffected by the native window transform, so the zone math needs no adjustment while
docked. Physically, though, the zones travel with the app: the corner on the docking side
still coincides with the screen corner, but the **opposite** corner of the docked app now
sits at ~0.25·W (for a 0.75 dock), and everything beyond it is backdrop. Holding the
docked app's own opposite corner therefore switches sides (`enable(side)` also updates
the side), while holding the physical screen corner beyond the app hits the backdrop and
**exits** the mode. Both verified live on iOS 26.

## Android engine (`android/.../OneHandWindowsModule.kt`)

The same "all windows" idea, built on Android's window model. An early conclusion that
Android was infeasible rested on `WindowManagerGlobal` being non-SDK; that was superseded
by the discovery of a public equivalent:

- **Window enumeration**: `android.view.inspector.WindowInspector.getGlobalWindowViews()`
  (API 29+/Android 10, plain public SDK, no permission, production-safe — Espresso uses it)
  returns the root/decor views of **every window this process attached**: the activity,
  RN `<Modal>`'s fullscreen `ComponentDialog` (`Theme.FullScreenDialog`,
  `windowIsFloating=false`), `Alert.alert`'s floating `AlertDialog` (a `DialogFragment`),
  popups. Windows of other processes — notably the IME/keyboard — are not in the list:
  on Android the keyboard is genuinely unreachable (the IME is a separate application in
  its own process), which is why the JS keyboard policy must stay cross-platform
  regardless of what iOS ever ships (see "Keyboard exclusion").

- **The Android touch trap** (analog of the iOS frame/bounds trap): transforms must be
  applied to a **child of the decor view, never the decor itself**. Touch coordinates are
  mapped through a view's inverse matrix by its _parent ViewGroup_ during dispatch
  (`dispatchTransformedTouchEvent`); the decor has no parent view, so scaling it leaves
  taps landing at unscaled coordinates. Scaling `decor.getChildAt(0)` keeps hit-testing
  correct for free.

- **Three window shapes**, classified per window each frame:
  1. **Fullscreen** — `LayoutParams.width` **and** `height` both `MATCH_PARENT` (activity,
     RN Modal). Checking width alone is wrong: a full-width but wrap-height window (a
     full-width dialog, a bottom banner) is a floating window and must be repositioned,
     not corner-scaled. These get a **top-left pivot plus an explicit translation** on
     their content child, applied once (stable). Geometrically that equals a
     bottom-corner-pivot scale, but the translation **animates** on a side switch, while
     a pivot change teleports the content to the other corner (pivots are not animatable).
  2. **Centered floating** (gravity CENTER in both axes: alerts, pickers) — a center scale
     on the child plus a **window reposition** via
     `WindowManager.updateViewLayout(root, params)` with `x/y = origin + (±W·(1−s)/2,
H·(1−s)/2)`.
  3. **Anchored / edge-gravity** (dropdowns via `showAsDropDown`, the text-selection
     toolbar, edge banners) — **scale only, no reposition**. The owner positioned such a
     window from its anchor's `getLocationInWindow()`, which already reflects our child
     scale, so offsetting it would double-transform. The child's pivot is set to the
     gravity-resolved corner (TOP|START → top-left) so the content stays glued to the
     anchor point instead of shrinking away from it.

     One exception to "no reposition": the text-selection toolbar positions itself in
     MIXED coordinates — scaled anchor location plus unscaled text offsets and toolbar
     dimensions, clamped against the full screen — which left the docked pill sideways
     of the selection and floating too high above it. It is re-anchored instead: the
     selection is read from the focused `TextView` (layout offsets mapped through the
     scale) and the pill is seated just above the selected line, centered on the
     selection and clamped into the docked region; other TOP|LEFT popups are only
     clamped. Reconciled per frame, restored from `floatingOrigins` on exit, like the
     centered flavor.

     Known limitation: the toolbar's **overflow panel** (⋮) reports its touchable region
     to WindowManager in unscaled content coordinates, while hit-testing inside the
     window maps through our scale — the two only overlap partially, so some overflow
     items (the back arrow) don't respond while docked. The region is set via the hidden
     `OnComputeInternalInsetsListener` API, which a library cannot correct without
     reflection on a blocked interface; the main panel (Cut/Copy/Paste/Share/Select all)
     is unaffected. Exiting the mode restores full overflow behavior.
  - Two cases are skipped outright. `TYPE_TOAST` windows are excluded from the pass —
    system-positioned and short-lived, transforming them is not worth the churn. (An
    asymmetry with iOS, where toast-library windows are ordinary small `UIWindow`s and
    dock with the app.) And windows belonging to **another activity of this process** are
    left alone entirely — see below.

- **Foreign activity windows are not docked.** `WindowInspector` enumerates the whole
  PROCESS, so a second activity — `expo-video`'s fullscreen player, any native screen —
  lands in the list too. It is a different screen, not part of the docked app: docking it
  showed the video scaled into the corner surrounded by that activity's own white theme
  background (the gray backdrop lives on the tracked activity's window). Skipped, it
  behaves like the separate-activity pickers — the mode survives underneath.

  Detection keys on the window TYPE: an activity's main window is the only one typed
  `TYPE_BASE_APPLICATION`, stamped by `ActivityThread` before the window is added — so it
  is already correct on the pre-layout frame where discovery first (and decisively) sees
  the window. Two alternatives measured and rejected: unwrapping the decor's context
  chain (an activity decor is built with a `DecorContext` based on the APPLICATION
  context, so it resolves null for every decor — a first attempt built on it skipped
  nothing) and `applicationWindowToken` (still null on that first frame). A dialog shown
  BY the foreign activity keeps `TYPE_APPLICATION` but its context does unwrap to its
  owner — the secondary signal. Windows resolving to no activity (application context:
  banners, floating widgets) still dock.

  The JS orientation policy applies on top: a player that lets the device rotate resizes
  the window and the mode performs its controlled exit — which is why a real phone can
  exit the mode on entering fullscreen video while an emulator (fixed sensor) does not.
  - Floating windows re-assert their own layout after we touch them, so they are
    reconciled **every frame** (both scale and position guarded by equality checks — once
    the dialog settles at the target they are no-ops, so no `updateViewLayout` churn and
    no jitter). A one-shot approach silently reverted.
  - **`FLAG_LAYOUT_NO_LIMITS` is required** on repositioned floating windows. A dialog
    window is often ~0.87 screen-wide — wider than the 0.75 dock region — so WindowManager
    clamps a horizontal offset back on-screen (vertical has room, so without the flag the
    alert docks in Y but not X — a genuinely confusing half-working state). The flag lets
    the window extend past the screen edge; since the content is only _render_-scaled
    (0.75) and centered, the overflow is the window's transparent margin — invisible —
    while the visible content lands in the corner. Cleared on reset.
  - **Hide until settled.** The child scale is synchronous, but `updateViewLayout` is
    ASYNC — the move lands a frame or two later — so a freshly discovered floating window
    would paint at its original position before snapping into the dock. It is therefore
    hidden (`alpha = 0`) on first sight and revealed only once it has demonstrably settled:
    scale on, no move pending, laid out, its real `getLocationOnScreen` stable across two
    consecutive passes AND different from where it started (unless it was caught before
    layout, where the recorded pre-move location — (0,0) — is meaningless). Two reveal
    criteria that do
    NOT work: trusting `params.x/y == target` (the reconciler mutates the window's live
    LayoutParams in place, so that check passes a frame before WindowManager applies the
    move), and predicting the expected on-screen position (WindowManager centers a
    CENTER-gravity window inside the area left by the system bars, so the prediction is off
    by half the navigation-bar height and the gate never opens). A hard cap of
    `MAX_HIDDEN_FRAMES` (10) still guarantees a window that keeps fighting our reposition
    can never stay invisible.
  - **The card background must be moved from the decor onto the scaled child.** Because we
    scale the decor's _child_ (not the decor — that would break touch), any drawable the
    theme paints as the WINDOW/decor background stays full-size. Dark Material draws the
    rounded dialog card exactly there, so the card rendered full-width and overflowed while
    the content shrank correctly (a theme-dependent bug invisible in light mode, where the
    card sits on the child). Fix: once per floating window, move `decor.background` onto
    `child.background` and null the decor's; restored on reset. This is why the AX frame of
    the content looked correctly docked even while the visible card overflowed — they were
    two different views.

- **New-window catching**: a `Choreographer` frame callback runs while the mode is active
  and processes any root not yet handled. For windows that have not been laid out yet the
  transform is installed from a one-shot `OnPreDrawListener` (after layout, before first
  draw) — the Android equivalent of iOS's "snap before first paint". While the app window
  is not visible (backgrounded, or between activities during a recreation) the loop drops
  to a 500 ms heartbeat instead of waking the main thread at vsync rate, and re-arms at
  full rate as soon as the window is visible again.

  The pass deliberately runs EVERY frame while the mode is active — do not throttle it.
  It runs in the Choreographer animation stage, which precedes the traversal (layout +
  draw) stage of the same frame: a window discovered here is hidden and repositioned
  BEFORE its first layout, so it lays out directly at the docked position and never
  paints anywhere else. A discovery throttle was tried (enumerate every 4th idle frame,
  with a window-focus-change listener forcing an immediate pass): the focus event arrives
  only after the new window's first traversal, so a date/time picker was first seen
  already laid out and painted at the screen center — the exact flash this loop exists to
  prevent. Measured per-frame evidence (Pixel 9 emulator, API 36): with the throttle the
  first sighting had `decor.width != 0`; without it the same picker is first seen at
  (0,0) with no size. The full pass costs ~43 µs per idle frame — a glitch-free first
  frame is worth that.

- **Backdrop without an extra window**: the exposed area of a scaled window shows that
  window's background, so the activity's window background drawable is swapped for a gray
  `Drawable` (by default — the same `backdropColor` / `dismissHintColor` props apply) that
  also draws the hint text. Restored (delayed past the exit animation) on reset.

- **Dismissal**: taps that no child claims land on the decor itself — exactly the exposed
  backdrop area. An `OnTouchListener` on every fullscreen root (activity AND fullscreen
  dialogs — with a modal open, the dialog's window receives the touches) emits
  `onDismissRequest`; JS stays the single source of truth, as on iOS.

- **Lifecycle**: all state is instance-level and `OnDestroy` restores everything — a JS
  reload can never leave windows transformed (the failure mode the iOS backdrop once had).
  The iOS module mirrors this with its own `OnDestroy`: its state is static and survives
  instance death, so the hook additionally clears the instance-bound closures (backdrop
  tap, remote-content exit) before performing the full reset.

- **Gating**: requires API 29 (Android 10). On older devices the module is inert and logs
  a warning. A greylist reflection fallback (`WindowManagerGlobal.mViews`) would be
  technically possible for API < 29 but is deliberately NOT implemented — there is no such
  code path in the module.

### Why the system-level approaches are out of reach (for the record)

- The IME draws its window from the InputMethodService's **own process** — no app can
  touch it (hence the shared keyboard policy).
- AOSP's one-handed mode only _translates_ the display (SurfaceControl leash in WM Shell,
  SystemUI privileges); Samsung One UI's scaling variant is also SystemUI-level. No public
  app-side equivalent exists — which is exactly the gap this library fills.
- App-side window scaling has no published prior art; the child-of-decor touch corollary
  is derived from AOSP dispatch sources — treat it as an invariant to re-verify whenever
  touch behavior regresses.

### Verified live (Android 10+ emulator, Pixel 9)

| Case                                            | Result                                                                                                                                                  |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Activity content                                | docks bottom-right / bottom-left at exactly 0.75 (AX frame `(0.25,0.25,0.75,0.75)`)                                                                     |
| Touch through the scaled app (tab nav, buttons) | works — the child-of-decor corollary holds                                                                                                              |
| RN `<Modal>`                                    | docks with the app (fullscreen `ComponentDialog`)                                                                                                       |
| `Alert.alert`                                   | docks: scaled 0.75 (panel `0.870 → 0.653` wide) and centered on the docked region (center `(0.625, 0.64)`); OK/Cancel remain tappable                   |
| Gray backdrop + hint, tap-to-dismiss            | works (window-background drawable; decor `OnTouchListener`)                                                                                             |
| JS reload while docked                          | no leaked transforms (all state instance-level, `OnDestroy` restores)                                                                                   |
| Attempting to enter the mode in landscape       | blocked (portrait-only policy; historical note: before the policy, landscape docking itself worked — bottom corner, tabs tappable, clean backdrop exit) |
| Rotating while docked                           | controlled auto-exit (orientation policy), both directions, re-entry works immediately                                                                  |
| 3-button navigation                             | docking, backdrop, alert docking and tap-to-exit all work; the corner gesture zone is the open issue (see limitations)                                  |

Diagnostic logging lives behind `DEBUG` in the module (off by default): it dumps the
params of each newly discovered FLOATING window and a sampled (every 20th frame) reconcile
decision — the tool that pinned the `FLAG_LAYOUT_NO_LIMITS` clamp.

## Known limitations & future work

- **Keyboard is never scaled.** On Android a hard boundary (the IME lives in another
  process); on iOS feasible but parked on the keyboard-frame reporting tradeoff — see
  [KEYBOARD-FINDINGS.md](./KEYBOARD-FINDINGS.md) for the working recipe and the open
  issues. Nearer-term alternative: an in-tree custom keyboard for specific inputs
  (PIN pads etc.).
- **One-hand mode is portrait-only** (orientation policy, see above): landscape entry is
  blocked and rotating while docked performs a controlled auto-exit instead of
  re-docking into the new orientation. Staying docked across a rotation was prototyped
  on iOS (a per-frame transform re-assert heals the geometry and re-docks correctly —
  verified live) but deliberately not shipped: the product decision is that rotation is
  a context switch and the mode should simply exit. Landscape docking itself also worked
  when it was allowed (verified live on both platforms) — the restriction is a product
  simplification, not a technical limit.
- **Android 3-button navigation: the corner gesture is barely reachable.** The app window
  is edge-to-edge (it extends under the navigation bar), but the navbar is a separate
  system window that takes those touches for itself — so of the 56 dp corner zone only the
  ~8 dp above the buttons actually reaches the app. Docking, the backdrop, alert docking
  and tap-to-exit all work in this mode; only the entry gesture is impractical. Verified
  live on a Pixel 9 (Android 16). Fixing it means growing/offsetting the zone by the
  bottom inset — RN core exposes no insets, so it would mean reusing the existing optional
  `react-native-safe-area-context` peer (today consumed only by `DockedInsets`) inside
  `CornerGestures`, or a configurable zone size. Not decided yet.
  (Cosmetic sibling: the docked app's bottom edge renders under the navbar, so a sliver
  of its tab bar background is hidden — the tab centers stay tappable, and an
  inset-aware app keeps its own content clear of it.)
- **Single-scene assumption**: the backdrop attaches to the first foreground-active scene;
  multi-scene (iPad) setups are untested. Related: the backdrop is bound to the scene it
  was created on; if that scene has been disconnected by the time the mode is re-entered,
  `showBackdrop` drops the dead window and re-creates the backdrop on the current
  foreground-active scene.
- **iOS 18 `hitTest` behavior change**: backdrop tap fall-through worked in verification,
  but Apple has been tightening window-level touch consumption — re-verify on new majors.

## Development notes

- **Metro + symlinked library**: Metro does not reliably pick up edits inside the
  symlinked package — restart with `expo start --clear` after changing library sources.
- **Expo CLI launch step**: `expo run:ios` may fail at the final "activate Simulator"
  osascript step when the Simulator GUI is not frontmost; the build/install has already
  succeeded — start Metro and launch the app manually in that case.
