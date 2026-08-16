# iOS keyboard docking — investigation findings

The record of a full investigation into scaling the system keyboard together with the
docked app (July 2026, iOS 26.5 simulator, iPhone 17 / 17 Pro Max). A complete
implementation was built and verified live, then **deliberately parked** — the code is
not in the tree; this document is what to resume from. Everything below is measured, not
assumed, unless marked otherwise.

## TL;DR

The system keyboard **can** be scaled and docked with the app on iOS: the implementation
is ~60 lines, typing through the scaled keyboard works, and the transform survives
hide/re-show cycles. What stops it from shipping is not feasibility but a
**keyboard-geometry reporting tradeoff**: no dock translation keeps the app bottom-flush
in its corner while the keyboard frame iOS reports stays correct for every consumer of
that rect (the algebra is below), plus a handful of secondary issues.

(The library long believed the keyboard could not be scaled at all. That belief was
wrong, and the next section explains how it survived: the experiment that "proved" it
had transformed a window it never actually obtained. README, ARCHITECTURE and the class
docs were corrected when this investigation established the real mechanics.)

## Why the keyboard was believed unscalable

`UIRemoteKeyboardWindow` is **not published in `UIWindowScene.windows`** (measured:
`windowScene.windows.contains(keyboardWindow) == false`). The module's `forEachWindow`
enumerates `connectedScenes → windows` only, so the keyboard window was simply never
reached — the `isKeyboardWindow` exclusion predicate never fired for it (dead code), and
the old "transforming that hosting window has no visual effect" note recorded a test
that had transformed a window it never obtained. Nor does the app hold a mere remote
placeholder: the window hosts the real keyboard view tree, in-process:

```
UIRemoteKeyboardWindow
  UIInputSetContainerView
    UIInputSetHostView
      UIKBInputBackdropView
      UIKeyboardDockView → UIKeyboardDockItemButton
      _UIKBCompatInputView → UIKeyboardAutomatic
      TUISystemInputAssistantView
```

The window **is** reachable through `UIWindow.didBecomeVisibleNotification` (the same
route react-native-keyboard-controller uses), it accepts a `CGAffineTransform`, the
transform survives hide/re-show cycles, and **key taps land on the intended keys**
through the scaled window (tapping Q,W,E,R,T typed exactly `Qwert`; digits on the number
plane land too).

## The working implementation (recipe)

1. **Off-scene window registry.** `NSHashTable<UIWindow>.weakObjects()`; on every window
   notification record windows for which `windowScene.windows.contains(self) == false`.
   Fold the registry into `forEachWindow` (dedupe by `ObjectIdentifier` — a window can
   migrate into the scene list later).
2. **Observers must live for the module's lifetime** (`OnCreate`/`OnDestroy`), not only
   while the mode is active: the keyboard window typically first becomes visible on the
   first text-field focus, long before the mode is entered.
3. **Backdrop ordering trap (regression found live):** with lifetime observers,
   `didBecomeVisible` for the backdrop window is delivered _synchronously_ during
   `window.isHidden = false`. Assign `Self.backdropWindow` **before** unhiding, or the
   handler fails the `window === backdropWindow` guard and docks the backdrop itself
   (symptom: black surround instead of the gray backdrop).
4. Drop the transform exclusion for keyboard windows in `apply(to:)`. Keep a
   `hostsSystemKeyboard` classifier ONLY as an edit-menu anchor filter
   (`selectionScreenRect` must never source the anchor from the emoji plane's own search
   field) and to skip keyboard windows in `reconcileEditMenus`.
5. JS: the keyboard policy (block `enable()` while the keyboard is up + auto-exit)
   becomes **Android-only** (`Platform.OS !== 'ios'`). On Android the IME is a separate
   application in its own process — `WindowInspector.getGlobalWindowViews()` cannot reach
   it, the policy must stay.

Verified matrix (example app): enter from both corners → gray backdrop ✅ · keyboard docks
✅ · typing correct ✅ · plane switch (letters→numbers) stays docked ✅ · keyboard dismiss
while docked → mode stays ✅ · exit via backdrop with keyboard open → app and keyboard
restore to full size, focus preserved ✅.

## The geometry problem — why this is parked

iOS reports the keyboard frame (`UIKeyboardFrameEndUserInfoKey`) as an **untransformed
screen rect**, and consumers disagree about what to do with it:

- **converting consumers** map it through the app window's transform: React Native's
  `RCTKeyboardObserver` (→ `KeyboardAvoidingView`), reanimated's `keyboardWillChangeFrame`
  path (→ `useAnimatedKeyboard`, keyboard-controller);
- **raw consumers** use it as-is: `ScrollView.automaticallyAdjustKeyboardInsets`, plus
  UIKit-internal ones (`keyboardLayoutGuide`, `UIAlertController` field avoidance) that a
  library cannot patch.

With screen height `H`, keyboard height `h`, reported top `K = H − h`, scale `s`, dock
translation `ty` (center-anchored transform, center `C`):

| dock variant                                                | converting consumers                                                                                                             | raw consumers | visual                                                                                                   |
| ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ------------- | -------------------------------------------------------------------------------------------------------- |
| **bottom-flush** (`ty = H(1−s)/2`), keyboard co-transformed | over-pad by `h(1−s)/s` (~115 pt at s=0.75; **degenerates below s ≈ h/H ≈ 0.36** — reported top goes negative, content collapses) | correct       | glued to the corner — the look users expect                                                              |
| **keyboard-anchored** (`ty = (K − C_y)(1−s)`)               | correct                                                                                                                          | correct       | whole composition lifts by `(H−K)(1−s)` (~86 pt) while typing, backdrop strip below — reads as "unglued" |

The anchored variant works because requiring window point `K` to render at screen `K` makes
`(K − ty)/s = K` an identity at every scale — converted, raw, and true values coincide.
**Measured:** RN reported `endCoordinates.screenY == 611` identically docked and undocked
under the anchor (and `width == 586.67 > screen 440` proved RN really does convert — no
real keyboard is wider than the screen).

Both variants were built and verified live. There is **no `ty` that satisfies both consumer
families while keeping the dock bottom-flush** (algebra is forced: raw-correct ⇒ keyboard
rendered at its reported position; converting-correct ⇒ same; both ⇒ the anchor formula).
The anchored variant was rejected on UX grounds (the lift), the flush variant knowingly
mis-sizes `KeyboardAvoidingView`-style layouts. That tension is the actual blocker — not
feasibility.

## Secondary issues (open when resuming)

- **Autocorrect bubble** (`_UITextChoiceAccelerationBubble`, direct child of
  `UITextEffectsWindow`): UIKit places it from screen coordinates, so under the window
  transform it double-maps. Position error ≈ `(1−s)(y − C_y) + ty` — near-zero for fields
  around mid-dock, tens of points off for fields near the dock's top/bottom. Fix: extend
  the existing `reconcileEditMenus` CADisplayLink pass to this view class (baseline
  measured undocked: bubble sits +12.3 pt below the field's window-frame top).
- **Corner gesture is unreachable while the keyboard is open** — the keyboard window covers
  both bottom corners and takes those touches. With the keyboard policy removed on iOS, a
  user typing in a text field has no gesture path into the mode; entry needs a programmatic
  control (`useOneHand().enable()`) or a different affordance.
- **System keyboard notes:** predictions bar docks with the keyboard; the keyboard's own
  bottom-row controls (globe/dictation) sit partially over the app's tab bar area at 0.75 —
  functional, slightly crowded.
- **App Store risk** of transforming a system-owned window: not assessed.
- Permission prompts (notifications/camera/mic) are presented by SpringBoard in its own
  window — the app holds no window for them, so they can never dock. PHPicker / the
  document picker / EventKit ARE hosted in app-owned windows, but their content renders
  and hit-tests in a **remote process**: on real devices (verified on an iPhone 13 Pro
  Max, iOS 18.7) the remote grid draws unscaled across the docked sheet and taps on it
  never connect — which is why the shipped remote-content policy exits the mode when they
  appear (see ARCHITECTURE.md, "Remote-content policy"). An earlier "docks fine"
  observation predates that on-device verification and matches simulator behavior, where
  remote services render in-process. The keyboard's view tree is in-process (dockable);
  SpringBoard alerts are not.

## Measurement notes (for reproducing)

- Simulator: `xcrun simctl spawn <udid> log stream --predicate 'eventMessage CONTAINS
"[OneHand]"'` with the module's `debugLogging = true`.
- RN-side: `Keyboard.addListener('keyboardDidShow', e => console.log(JSON.stringify(
e.endCoordinates)))` — the `width > screen` signature instantly tells you whether the
  value was converted through the transform.
- Accessibility frames (as reported by accessibility-inspection tooling, e.g. XCUITest
  element frames) report **screen space with the window transform applied** — key
  positions like `Q at x=0.258, P at x=0.922, prediction bar width 0.750` are the cheap
  way to verify the keyboard really is docked (dock region starts at 0.250 for right-dock
  at s=0.75).
- Yarn caches `file:` tarballs by name+version — bump the version on every repack or the
  host app silently keeps the old code.
