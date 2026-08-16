import ExpoModulesCore
import UIKit

/// Backdrop view controller shown behind the scaled-down app: a gray fill with a hint
/// label. A tap anywhere on it requests dismissal of one-hand mode.
///
/// It is rendered in its own, UNSCALED window placed just below the application windows,
/// so the area around the docked app is not black and remains interactive.
final class OneHandBackdropController: UIViewController {
  var onTap: (() -> Void)?
  var hint: String = "" {
    didSet { label.text = hint }
  }
  var fillColor = OneHandBackdropController.defaultFillColor {
    didSet { if isViewLoaded { view.backgroundColor = fillColor } }
  }
  var hintColor = OneHandBackdropController.defaultHintColor {
    didSet { if isViewLoaded { label.textColor = hintColor } }
  }

  static let defaultFillColor = UIColor(white: 0.72, alpha: 1.0)
  static let defaultHintColor = UIColor(white: 0.25, alpha: 1.0)

  private let label = UILabel()

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = fillColor

    label.text = hint
    label.textColor = hintColor
    label.font = .systemFont(ofSize: 15, weight: .medium)
    label.numberOfLines = 0
    label.textAlignment = .center
    label.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(label)
    NSLayoutConstraint.activate([
      label.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 32),
      label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
      label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
    ])

    view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleTap)))
  }

  @objc private func handleTap() {
    onTap?()
  }
}

/**
 * Native "all windows" one-hand mode (iOS).
 *
 * Instead of transforming a single React subtree, this module applies a scale + translate
 * transform to EVERY `UIWindow` of the process and keeps observing window lifecycle
 * notifications (public API) to catch windows created later. This is what makes overlays
 * that live in separate native windows — React Native's `Alert.alert` (which presents in
 * its own window at `UIWindowLevelAlert + 1`), action sheets, and third-party modals —
 * dock together with the app, with no per-library adapters.
 *
 * Implementation notes:
 *
 * - UIKit trap (verified live): `UIView.frame` is undefined while `transform != identity`.
 *   During presentations UIKit may re-set a window's frame; with a transform in place the
 *   window's bounds then inflate to `frame / scale` and the window LOOKS full-sized even
 *   though its `transform` still equals the target. Comparing the transform alone is
 *   therefore insufficient — `apply(to:)` also validates `bounds.size` against the
 *   window's own recorded frame (see managedFrames) and normalizes geometry
 *   (identity → frame = recorded frame → target transform) on mismatch.
 *
 * - Transforms are re-asserted across all windows on every window lifecycle event
 *   (immediately, on the next runloop turn, and after the presentation animation).
 *   The operation is idempotent, so redundant passes are effectively free.
 *
 * - System keyboard: `UIRemoteKeyboardWindow` is an app-owned window hosting the real
 *   keyboard view tree, but it is NOT published in `UIWindowScene.windows` — so
 *   `forEachWindow` never reaches it and the keyboard simply stays full-sized. The
 *   `isKeyboardWindow` guard is defensive (should the OS ever publish it), not the active
 *   mechanism. Scaling the keyboard is feasible but deliberately parked — see
 *   KEYBOARD-FINDINGS.md for the recipe and why it does not ship.
 *
 * - Deliberately non-fullscreen windows (in-app banner SDKs, floating widgets, toasts)
 *   dock too: every managed window scales about its own center toward where the dock
 *   maps that center (for a full-screen window this reduces exactly to the standard
 *   dock transform). Each window's untransformed frame is recorded at identity and is
 *   what the repair path and reset() restore — never screen size (see the managedFrames
 *   doc for the verified-live banner incident). Windows first seen with a foreign
 *   transform are left alone.
 *
 * - Edit-menu placement trap (verified live on iOS 26): UIKit positions the text-selection
 *   edit menu (`_UIEditMenuListView`) from the selection's on-SCREEN rect — which already
 *   reflects the app window's dock transform — and writes those screen coordinates into
 *   the menu container's local frame, assuming the hosting window maps 1:1 onto the
 *   screen. With our transform on that window the position gets mapped a SECOND time and
 *   the menu lands ~60–90 pt away from the text (the scale, however, is correct). A
 *   per-frame reconciler (CADisplayLink, running only while the mode is active — the iOS
 *   analog of the Android module's Choreographer pass) therefore translates each edit-menu
 *   container so the menu's rendered center equals the screen position UIKit intended
 *   (the same link drives the remote-content policy below).
 */
public class OneHandWindowsModule: Module {
  private static var active = false

  /// Scale bounds, mirroring the JS `useSafeScale` clamp — the bridge is callable by any
  /// in-process code, and a non-finite/zero scale blanks every window (>= 1 leaves no
  /// backdrop to exit with).
  private static let defaultScale: CGFloat = 0.75
  private static let minScale: CGFloat = 0.3
  private static let maxScale: CGFloat = 0.85

  private static var scale = defaultScale
  private static var side: String = "right"
  /// Screen bounds at the moment the mode was entered. Every recorded frame in
  /// `managedFrames` is only meaningful for THIS screen geometry: after a rotation the
  /// screen bounds change and all recorded frames are stale (in the old orientation).
  /// `apply(to:)` and `reset` compare against this to detect the rotated state — the JS
  /// orientation policy is exiting the mode then, and the native side must neither
  /// re-assert nor "restore" stale geometry (doing so parked portrait-sized windows in a
  /// landscape interface — verified live: black bands + misplaced content).
  private static var dockScreenBounds: CGRect = .zero
  private static var observers: [NSObjectProtocol] = []
  private static var backdropWindow: UIWindow?
  private static var backdropController: OneHandBackdropController?
  private static var pendingBackdropHide: DispatchWorkItem?
  private static var editMenuLink: CADisplayLink?

  /// `CADisplayLink` retains its target, so the tick goes through a tiny static proxy
  /// instead of a module instance (instances die on every JS reload; the static state
  /// does not).
  private final class EditMenuTicker: NSObject {
    @objc func tick() {
      OneHandWindowsModule.reconcileEditMenus()
      OneHandWindowsModule.remotePolicyTick()
    }
  }

  private static let editMenuTicker = EditMenuTicker()

  /// Duration of the user-visible transitions: entering the mode, switching sides, and
  /// the restore on exit. Mirrors the Android engine's `ANIMATION_MS`.
  private static let animationDuration: TimeInterval = 0.25

  /// How long the backdrop stays up after `reset()`. MUST exceed `animationDuration` —
  /// hiding it earlier exposes black behind the still-shrunk app (the Android engine
  /// derives the same value as `ANIMATION_MS + 50`).
  private static let backdropHideDelay: TimeInterval = animationDuration + 0.05

  /// Delay of the last re-assert pass after a window notification: long enough to land
  /// past a presentation animation, which may re-set window geometry as it settles.
  private static let reassertSettleDelay: TimeInterval = 0.45

  /// Cap on the backdrop hint length — an unbounded bridge string (e.g. a corrupted
  /// remote-config value) would make label layout arbitrarily expensive.
  private static let maxHintLength = 200

  /// Diagnostic logging of notifications and transform decisions (NSLog, "[OneHand]"
  /// prefix). Off by default; flip when investigating window-geometry issues.
  private static let debugLogging = false

  private static func dlog(_ message: String) {
    guard debugLogging else { return }
    NSLog("[OneHand] %@", message)
  }

  public func definition() -> ModuleDefinition {
    Name("OneHandWindows")

    Events("onDismissRequest")

    AsyncFunction("setScale") {
      (scale: Double, side: String, dismissHint: String, backdropColor: Int?,
        dismissHintColor: Int?) in
      DispatchQueue.main.async {
        Self.dlog("setScale scale=\(scale) side=\(side)")
        Self.scale = scale.isFinite
          ? min(max(CGFloat(scale), Self.minScale), Self.maxScale) : Self.defaultScale
        Self.side = side
        Self.dockScreenBounds = Self.currentScreenBounds()
        Self.active = true
        self.showBackdrop(
          hint: String(dismissHint.prefix(Self.maxHintLength)),
          fillColor: Self.color(fromARGB: backdropColor),
          hintColor: Self.color(fromARGB: dismissHintColor))
        // The only animated pass: entering the mode / switching sides is a user-visible
        // transition. Every notification-driven pass afterwards snaps (see apply(to:)).
        Self.applyToAllWindows(animated: true)
        Self.startObserving()
        Self.startEditMenuReconciler()
        Self.remotePolicyTriggered = false
        Self.lastPresentedIdentifier = nil
        Self.requestDismiss = { [weak self] in self?.sendEvent("onDismissRequest") }
      }
    }

    AsyncFunction("reset") {
      DispatchQueue.main.async { Self.performReset() }
    }

    OnDestroy {
      // JS reload: instances die but the static engine state does not — clear the
      // instance-bound closures and never leave windows transformed (Android parity).
      DispatchQueue.main.async {
        Self.requestDismiss = nil
        Self.backdropController?.onTap = nil
        Self.performReset()
      }
    }
  }

  /// Full exit — restore windows, stop observation, schedule the backdrop hide.
  /// Idempotent; runs from both `reset` and `OnDestroy`.
  private static func performReset() {
    Self.dlog("reset")
    Self.active = false
    Self.stopObserving()
    Self.stopEditMenuReconciler()
    Self.forEachWindow { window in
      guard window !== Self.backdropWindow else { return }
      // Restore ONLY windows the module actually recorded a frame for. Normalizing
      // anything else — and normalizing to anything OTHER than the window's own
      // recorded frame — would destroy deliberately non-fullscreen windows
      // (banners, floating widgets) exactly the way the repair path used to.
      guard let originalValue = Self.managedFrames.object(forKey: window) else { return }
      let originalFrame = originalValue.cgRectValue
      // Undo the edit-menu container translations together with the window transform,
      // so a menu that is still on screen travels coherently with the restoring app.
      let containers = window.subviews.filter {
        Self.isEditMenuContainer($0) && $0.transform != .identity
      }
      // Rotation-safe restore (see dockScreenBounds — recorded frames are in the
      // OLD orientation and restoring them corrupts geometry): snap to identity
      // un-animated, give a formerly full-screen window the CURRENT screen bounds,
      // and drop the stale record; a non-fullscreen window (banner, floating
      // widget) keeps its frame — its owner lays it out for the new orientation.
      if !window.screen.bounds.size.equalTo(Self.dockScreenBounds.size) {
        window.transform = .identity
        containers.forEach { $0.transform = .identity }
        if originalFrame.size.equalTo(Self.dockScreenBounds.size) {
          window.frame = window.screen.bounds
        }
        Self.managedFrames.removeObject(forKey: window)
        return
      }
      let boundsOK = window.bounds.size.equalTo(originalFrame.size)
      guard window.transform != .identity || !boundsOK || !containers.isEmpty else { return }
      UIView.animate(withDuration: Self.animationDuration, animations: {
        window.transform = .identity
        containers.forEach { $0.transform = .identity }
      }, completion: { _ in
        // Normalize geometry after removing the transform (see the class-level note).
        if !window.bounds.size.equalTo(originalFrame.size) {
          window.frame = originalFrame
        }
      })
    }
    // Keep the gray backdrop visible while the windows animate back to full size;
    // hiding it immediately would expose black behind the still-shrunk app. The hide
    // is a CANCELLABLE work item (mirroring the Android module's
    // pendingBackdropRestore): a rapid disable→enable→disable would otherwise let
    // the FIRST disable's timer fire mid-way through the second exit animation and
    // hide the backdrop under the still-shrunk app. The `active` guard stays as a
    // second line of defense for the disable→enable case.
    Self.pendingBackdropHide?.cancel()
    let hide = DispatchWorkItem {
      Self.pendingBackdropHide = nil
      if !Self.active { Self.backdropWindow?.isHidden = true }
    }
    Self.pendingBackdropHide = hide
    DispatchQueue.main.asyncAfter(deadline: .now() + Self.backdropHideDelay, execute: hide)
  }

  /// Converts a color processed by React Native's `processColor` (a 32-bit AARRGGBB
  /// integer, possibly sign-extended) into a UIColor. Nil in = nil out (use the default).
  private static func color(fromARGB value: Int?) -> UIColor? {
    guard let value else { return nil }
    return UIColor(
      red: CGFloat((value >> 16) & 0xFF) / 255.0,
      green: CGFloat((value >> 8) & 0xFF) / 255.0,
      blue: CGFloat(value & 0xFF) / 255.0,
      alpha: CGFloat((value >> 24) & 0xFF) / 255.0)
  }

  private func showBackdrop(hint: String, fillColor: UIColor?, hintColor: UIColor?) {
    // Re-entering the mode cancels any pending hide from a previous disable — a stale
    // timer from a rapid disable→enable→disable could otherwise remove the backdrop
    // mid-exit-animation (same rule as the Android module's installBackdrop).
    Self.pendingBackdropHide?.cancel()
    Self.pendingBackdropHide = nil

    // Reuse the existing backdrop only while its scene is still alive. A window whose
    // scene was disconnected (system reclaim in the background, an iPad multi-window
    // scene closed) can never render again, and unhiding it would leave the mode running
    // with a black surround and no tap-to-exit — so it is dropped and re-created below.
    if let window = Self.backdropWindow {
      if window.windowScene != nil {
        Self.backdropController?.hint = hint
        Self.backdropController?.fillColor = fillColor ?? OneHandBackdropController.defaultFillColor
        Self.backdropController?.hintColor = hintColor ?? OneHandBackdropController.defaultHintColor
        // Re-wire the tap handler to THIS module instance. The backdrop is a static
        // singleton that outlives module instances (a JS reload creates a new instance and
        // deallocates the old one) — without re-wiring, onTap would fire into a dead weak
        // reference and backdrop dismissal would silently stop working after any reload.
        Self.backdropController?.onTap = { [weak self] in self?.sendEvent("onDismissRequest") }
        window.isHidden = false
        return
      }
      Self.dlog("backdrop scene gone — re-creating")
      Self.backdropWindow = nil
      Self.backdropController = nil
    }

    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first
    else {
      Self.dlog("no window scene available — backdrop not created")
      return
    }

    let controller = OneHandBackdropController()
    controller.hint = hint
    if let fillColor { controller.fillColor = fillColor }
    if let hintColor { controller.hintColor = hintColor }
    controller.onTap = { [weak self] in self?.sendEvent("onDismissRequest") }

    let window = UIWindow(windowScene: scene)
    // Just below the application windows: visible (and tappable) only where the scaled
    // windows no longer cover the screen.
    window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.normal.rawValue - 1)
    window.rootViewController = controller
    window.isHidden = false

    Self.backdropWindow = window
    Self.backdropController = controller
  }

  /// Bounds of the screen hosting the app's scene (orientation-dependent — this is the
  /// value that flips on rotation and invalidates all recorded frames).
  private static func currentScreenBounds() -> CGRect {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    return scene?.screen.bounds ?? .zero
  }

  private static func forEachWindow(_ body: (UIWindow) -> Void) {
    // Iterated directly instead of `compactMap { … }.flatMap { $0.windows }`: that chain
    // built two throwaway arrays on EVERY call, and this runs once per frame from the
    // edit-menu reconciler (plus three times per window notification).
    for scene in UIApplication.shared.connectedScenes {
      guard let windowScene = scene as? UIWindowScene else { continue }
      for window in windowScene.windows { body(window) }
    }
  }

  /// Memoized results of the class-name predicates below, keyed by the class object.
  ///
  /// `NSStringFromClass` allocates a bridged `String` and `contains` then scans it — and
  /// these predicates run for every window (and, for the edit-menu one, every subview of
  /// every docked window) on EVERY frame the reconciler ticks, i.e. up to 120×/s while the
  /// mode is active. A class's name cannot change, so the answer is computed once per class
  /// and a dictionary lookup replaces the allocation + scan from then on. Main-thread only,
  /// like all state in this module.
  private static var keyboardWindowClasses: [ObjectIdentifier: Bool] = [:]
  private static var editMenuContainerClasses: [ObjectIdentifier: Bool] = [:]

  private static func className(
    of cls: AnyClass, contains needle: String, cache: inout [ObjectIdentifier: Bool]
  ) -> Bool {
    let key = ObjectIdentifier(cls)
    if let cached = cache[key] { return cached }
    let matches = NSStringFromClass(cls).contains(needle)
    cache[key] = matches
    return matches
  }

  // MARK: - Remote-content policy

  /// Out-of-process system UI — the photo picker, the Files/document picker, the calendar
  /// event editor — renders and hit-tests in ANOTHER process, anchored to untransformed
  /// screen geometry. Under the dock transform its content draws unscaled/misplaced and
  /// touches never connect (verified live on an iPhone 13 Pro Max: the photo grid painted
  /// full-size across the docked sheet, its AX frame was larger than the screen with a
  /// negative origin, and taps on it were dead; simulators do not reproduce this — their
  /// remote services render in-process). The transform cannot reach another process, so
  /// the mode EXITS automatically when remote content appears — the same shape as the
  /// keyboard policy: never combine a docked app with a surface the transform cannot cover.
  ///
  /// Detection is generic (any `_UIRemoteView` in the presented hierarchy) rather than a
  /// class blocklist, so in-process surfaces — the share sheet, the camera — are
  /// unaffected, and any OS version that hosts a given picker in-process keeps docking it.
  private static var remoteViewClasses: [ObjectIdentifier: Bool] = [:]
  private static var lastPresentedIdentifier: ObjectIdentifier?

  /// How long after a NEW presentation appears its subtree keeps being scanned for
  /// `_UIRemoteView` — the remote view attaches asynchronously, sometimes well after
  /// the presentation starts.
  private static let remoteCheckWindow: CFTimeInterval = 2.0
  private static var remoteCheckDeadline: CFTimeInterval = 0
  private static var remotePolicyTriggered = false

  /// Exit hook for the remote-content policy — re-wired on every `setScale` because
  /// `sendEvent` lives on the module instance (instances die on JS reload; statics do not).
  private static var requestDismiss: (() -> Void)?

  private static func isRemoteContentView(_ view: UIView) -> Bool {
    className(of: type(of: view), contains: "_UIRemoteView", cache: &remoteViewClasses)
  }

  private static func containsRemoteContent(_ root: UIView, depth: Int = 0) -> Bool {
    // Depth cap keeps the per-tick scan bounded; remote views attach within a few
    // levels of the presented root.
    if depth > 8 { return false }
    for sub in root.subviews {
      if isRemoteContentView(sub) { return true }
      if containsRemoteContent(sub, depth: depth + 1) { return true }
    }
    return false
  }

  /// Runs on the reconciler tick. Cheap by construction: the subtree scan happens only
  /// for a short window after a NEW presentation appears (the remote view attaches
  /// asynchronously, sometimes well after the presentation starts).
  private static func remotePolicyTick() {
    guard active, !remotePolicyTriggered else { return }
    var presented: UIViewController?
    forEachWindow { window in
      if presented == nil, window !== backdropWindow,
        let candidate = window.rootViewController?.presentedViewController {
        presented = candidate
      }
    }
    guard var presented else {
      lastPresentedIdentifier = nil
      return
    }
    // Walk to the TOPMOST presentation — a picker opened from inside a modal lives at
    // a deeper level, and scanning only the first would miss it.
    while let next = presented.presentedViewController { presented = next }
    let identifier = ObjectIdentifier(presented)
    if identifier != lastPresentedIdentifier {
      lastPresentedIdentifier = identifier
      remoteCheckDeadline = CACurrentMediaTime() + remoteCheckWindow
    }
    guard CACurrentMediaTime() < remoteCheckDeadline else { return }
    if let view = presented.viewIfLoaded, containsRemoteContent(view) {
      remotePolicyTriggered = true
      dlog("remote content detected in \(type(of: presented)) — requesting mode exit")
      requestDismiss?()
    }
  }

  // MARK: - Window docking

  private static func isKeyboardWindow(_ window: UIWindow) -> Bool {
    // Class-name matching only (no private API calls). Defensive: UIRemoteKeyboardWindow
    // is not published in UIWindowScene.windows, so in practice this never fires for the
    // real keyboard window (see KEYBOARD-FINDINGS.md).
    // UITextEffectsWindow is deliberately NOT excluded: it is an in-process window that
    // hosts the text-selection edit menu / callout bar — leaving it unscaled made the
    // edit menu render full-size instead of docking with the text. Scaling it is only
    // half of the story though: UIKit places the menu in screen coordinates, so the
    // position must additionally be corrected by reconcileEditMenus() (see the
    // class-level note on the edit-menu placement trap).
    className(of: type(of: window), contains: "KeyboardWindow", cache: &keyboardWindowClasses)
  }

  /// All geometry derived from the dock parameters for one screen, computed in ONE
  /// place. `apply(to:)`, the edit-menu reconciler and the selection mapping all consume
  /// this struct, so the transform, the equality guards and the on-screen dock rect can
  /// never drift apart (a drifted guard would silently disable the reconciler).
  ///
  /// The window transform is center-anchored, hence the `center` term in the point
  /// mappings: `screen = C + t + s·(p − C)`.
  private struct DockGeometry {
    let screenBounds: CGRect
    let scale: CGFloat
    let translation: CGPoint
    let center: CGPoint

    init(screenBounds: CGRect, scale: CGFloat, side: String) {
      self.screenBounds = screenBounds
      self.scale = scale
      self.translation = CGPoint(
        x: (side == "left" ? -1 : 1) * screenBounds.width * (1 - scale) / 2,
        y: screenBounds.height * (1 - scale) / 2)
      self.center = CGPoint(x: screenBounds.midX, y: screenBounds.midY)
    }

    /// The docking transform applied to every full-screen window.
    var target: CGAffineTransform {
      CGAffineTransform(translationX: translation.x, y: translation.y)
        .scaledBy(x: scale, y: scale)
    }

    /// Where the scaled window actually renders on screen.
    var dockRect: CGRect {
      CGRect(
        x: center.x + translation.x - scale * screenBounds.width / 2,
        y: center.y + translation.y - scale * screenBounds.height / 2,
        width: scale * screenBounds.width,
        height: scale * screenBounds.height)
    }

    /// Maps a window-local point to its rendered on-screen position.
    func screenPoint(fromWindowPoint point: CGPoint) -> CGPoint {
      CGPoint(
        x: center.x + translation.x + scale * (point.x - center.x),
        y: center.y + translation.y + scale * (point.y - center.y))
    }

    /// Maps a window-local rect to its rendered on-screen rect.
    func screenRect(fromWindowRect rect: CGRect) -> CGRect {
      let origin = screenPoint(fromWindowPoint: rect.origin)
      return CGRect(
        x: origin.x, y: origin.y, width: scale * rect.width, height: scale * rect.height)
    }

    /// Inverse of `screenPoint` — where a window-local point must sit so that it renders
    /// at the given on-screen position.
    func windowPoint(fromScreenPoint point: CGPoint) -> CGPoint {
      CGPoint(
        x: (point.x - center.x - translation.x) / scale + center.x,
        y: (point.y - center.y - translation.y) / scale + center.y)
    }
  }

  private static func dockGeometry(for screenBounds: CGRect) -> DockGeometry {
    DockGeometry(screenBounds: screenBounds, scale: scale, side: side)
  }

  /// Original (untransformed) frame of every managed window, recorded while the window
  /// was at IDENTITY — the only moment `frame` is defined. This is what makes deliberately
  /// non-fullscreen windows (in-app banner SDKs, floating widgets, toast libraries) safe
  /// to dock: the repair path and reset() restore each window to ITS OWN recorded frame
  /// instead of assuming every window is screen-sized (the old assumption blew a 300×64
  /// banner up to a permanent fullscreen touch-blocker — verified live with the example
  /// app's banner-window fixture). Weak keys: entries vanish with their window.
  private static let managedFrames = NSMapTable<UIWindow, NSValue>.weakToStrongObjects()

  /// Returns the window's untransformed frame, (re-)recording it whenever the window is
  /// currently at identity — that covers first sight, windows the owner repositioned
  /// while the mode was off, and freshly reset windows. Returns nil for a window first
  /// seen with a FOREIGN transform: its true geometry is unknowable, so it is left
  /// alone entirely (conservative).
  private static func originalFrame(of window: UIWindow) -> CGRect? {
    if window.transform == .identity {
      let frame = window.frame
      managedFrames.setObject(NSValue(cgRect: frame), forKey: window)
      return frame
    }
    return managedFrames.object(forKey: window)?.cgRectValue
  }

  private static func apply(to window: UIWindow, animated: Bool) {
    guard active else { return }
    if window === backdropWindow { return }
    if isKeyboardWindow(window) {
      dlog("skip keyboard window \(type(of: window))")
      return
    }
    // Rotation guard: once the screen no longer matches the geometry the mode was
    // entered with, every recorded frame is stale and the JS orientation policy is
    // already exiting the mode. Touching windows here — in particular the repair path
    // re-setting a pre-rotation frame — would fight UIKit's own re-layout and corrupt
    // window geometry (see dockScreenBounds).
    guard window.screen.bounds.size.equalTo(dockScreenBounds.size) else {
      dlog("skip apply — screen bounds changed (rotation), window=\(type(of: window))")
      return
    }
    // A window first seen with a foreign transform is left alone (see originalFrame).
    guard let originalFrame = originalFrame(of: window) else { return }

    let geometry = dockGeometry(for: window.screen.bounds)
    // Per-window docking transform: scale about the window's own center and move that
    // center to where the dock maps it. For a full-screen window the center IS the
    // screen center, so this reduces exactly to `geometry.target`; a non-fullscreen
    // window (banner, floating widget) scales in place and travels toward the corner
    // proportionally — it stays visually glued to the app content it belongs to.
    let windowCenter = CGPoint(x: originalFrame.midX, y: originalFrame.midY)
    let renderedCenter = geometry.screenPoint(fromWindowPoint: windowCenter)
    let target = CGAffineTransform(
      translationX: renderedCenter.x - windowCenter.x, y: renderedCenter.y - windowCenter.y)
      .scaledBy(x: geometry.scale, y: geometry.scale)

    let boundsOK = window.bounds.size.equalTo(originalFrame.size)
    if window.transform == target && boundsOK { return }

    dlog(
      "apply \(animated ? "ANIMATED" : "SNAP") window=\(type(of: window)) "
        + "boundsOK=\(boundsOK) bounds=\(window.bounds.size) frame=\(window.frame.size) "
        + "identity=\(window.transform == .identity)")

    if !boundsOK {
      // Recover from UIKit re-setting the frame while our transform was active
      // (observed during alert presentation and modal dismissal). The repair must be
      // instantaneous: all three property sets happen in one render transaction, so the
      // window never paints full-sized in between. Animating this path instead produces
      // a visible "expand then shrink" glitch.
      window.transform = .identity
      window.frame = originalFrame
      window.transform = target
      return
    }
    if animated {
      // Entering the mode / switching sides — a user-visible transition.
      UIView.animate(withDuration: animationDuration) { window.transform = target }
    } else {
      // Notification-driven pass: a window that appeared (or was reset) while the mode
      // is active must be docked before it ever paints — animating here made alerts
      // visibly "slide down" from full size.
      window.transform = target
    }
  }

  private static func applyToAllWindows(animated: Bool) {
    forEachWindow { apply(to: $0, animated: animated) }
  }

  // MARK: - Edit-menu reconciliation

  /// The text-selection edit menu lives in a full-window `_UIEditMenuContainerView`
  /// (inside `UITextEffectsWindow`; the app window holds an empty twin). Class-name
  /// substring matching only — no private API calls, same policy as keyboard windows.
  private static func isEditMenuContainer(_ view: UIView) -> Bool {
    className(of: type(of: view), contains: "EditMenuContainerView", cache: &editMenuContainerClasses)
  }

  /// The display link drives BOTH per-frame passes — `reconcileEditMenus()` AND the
  /// remote-content policy's `remotePolicyTick()` (see `EditMenuTicker.tick()`); stopping
  /// the reconciler stops both.
  private static func startEditMenuReconciler() {
    guard editMenuLink == nil else { return }
    let link = CADisplayLink(target: editMenuTicker, selector: #selector(EditMenuTicker.tick))
    link.add(to: .main, forMode: .common)
    editMenuLink = link
  }

  private static func stopEditMenuReconciler() {
    editMenuLink?.invalidate()
    editMenuLink = nil
  }

  /// Corrects the edit-menu placement trap (see the class-level note): UIKit writes the
  /// menu's intended SCREEN coordinates into the container's local frame, so under our
  /// window transform they map a second time. Translating the container by
  /// `d = (L' − C − t) / s − (L − C)` (L = the geometric center of the menu platters as
  /// UIKit laid them out, L' = that center clamped into the dock rect, C = the window
  /// center, t = the dock translation) makes the rendered center land exactly on L' —
  /// the menu stays scaled by the window transform but sits where UIKit aimed it.
  ///
  /// The clamp matters for tappability, not just looks: UIKit clamps the menu to SCREEN
  /// margins, so for a selection near the docked app's edge the intended position sticks
  /// out of the dock rect — and any part of the window rendered outside its transformed
  /// bounds is unreachable (`hitTest` bounds-checks the window first), so taps there
  /// would fall through to the backdrop and exit the mode (verified live).
  ///
  /// Runs every frame while active; the equality guard makes settled frames free.
  private static func reconcileEditMenus() {
    guard active else { return }
    forEachWindow { window in
      guard window !== backdropWindow, !isKeyboardWindow(window) else { return }

      let geometry = dockGeometry(for: window.screen.bounds)
      // Only windows that already carry the dock transform: a freshly appeared window is
      // still at identity until the next re-assert pass, and translating its container
      // then would misplace the menu for a frame.
      guard window.transform == geometry.target else { return }

      for container in window.subviews where isEditMenuContainer(container) {
        // Menu platters (the list, a submenu) are direct subviews. Their geometric rect
        // is computed from `bounds` via `convert` — NEVER from `frame` or `center`:
        // UIKit parks each platter's layer anchorPoint at the presentation-zoom origin
        // (observed live: anchorPoint (1,1) — `center` then returns the bottom-RIGHT
        // corner, and treating it as the middle shifted the menu ~150 pt off target).
        // Full-container-sized subviews are skipped: those are overlay helpers, not
        // platters, and would inflate the union to the whole screen.
        let platters = container.subviews.filter {
          !$0.isHidden && $0.bounds.width > 0 && $0.bounds.height > 0
            && $0.bounds.width < container.bounds.width - 1
        }
        guard !platters.isEmpty else {
          if container.transform != .identity { container.transform = .identity }
          continue
        }
        var union = CGRect.null
        for platter in platters {
          union = union.union(platter.convert(platter.bounds, to: container))
        }

        let dock = geometry.dockRect
        let inset: CGFloat = 4
        let halfWidth = union.width * geometry.scale / 2
        let halfHeight = union.height * geometry.scale / 2

        // While docked, UIKit always chooses the BELOW placement (its above/below
        // decision breaks on the transformed selection geometry — verified live: the
        // menu lands below even with an identity text-effects window, so the bias is
        // source-side and no window-transform arrangement can fix it). The full-size
        // system behavior is menu-above-selection, so the side is re-decided here from
        // the real selection rect: above when the dock has room, below otherwise.
        // Every candidate goes through the SAME final clamp into the dock rect — the
        // above-placement branch included. Skipping it there let a selection scrolled
        // below the dock push the menu past the dock's bottom edge, where a window
        // renders but cannot be tapped (see the note above).
        let minCenterX = dock.minX + inset + halfWidth
        let maxCenterX = dock.maxX - inset - halfWidth
        let minCenterY = dock.minY + inset + halfHeight
        let maxCenterY = dock.maxY - inset - halfHeight
        var targetX: CGFloat
        var targetY: CGFloat
        if let selection = selectionScreenRect() {
          // Clears the selection-handle knob rendered just outside the selection line.
          let gap: CGFloat = 12
          let aboveCenterY = selection.minY - gap - halfHeight
          let belowCenterY = selection.maxY + gap + halfHeight
          targetY = aboveCenterY >= minCenterY ? aboveCenterY : belowCenterY
          targetX = selection.midX
        } else {
          // No text selection to anchor to (e.g. a non-text edit menu): keep UIKit's
          // intended spot.
          targetX = union.midX
          targetY = union.midY
        }
        targetX = clamp(targetX, min: minCenterX, max: maxCenterX)
        targetY = clamp(targetY, min: minCenterY, max: maxCenterY)
        let local = geometry.windowPoint(fromScreenPoint: CGPoint(x: targetX, y: targetY))
        let dx = local.x - union.midX
        let dy = local.y - union.midY
        let target = CGAffineTransform(translationX: dx, y: dy)
        if container.transform != target {
          dlog(
            "edit menu reconcile window=\(type(of: window)) union=\(union) "
              + "target=(\(targetX), \(targetY)) d=(\(dx), \(dy))")
          container.transform = target
        }
      }
    }
  }

  /// Clamps into `[min, max]`; a menu larger than the dock collapses to the middle.
  private static func clamp(_ value: CGFloat, min lower: CGFloat, max upper: CGFloat) -> CGFloat {
    if lower > upper { return (lower + upper) / 2 }
    return Swift.min(Swift.max(value, lower), upper)
  }

  /// Last known first responder. The reconciler runs every frame while an edit menu is
  /// visible, and a full-hierarchy walk per frame is O(view count) on the main thread at
  /// up to 120 Hz; re-validating the cached view is a single property read, so the walk
  /// only happens when the responder actually changed.
  private static weak var cachedFirstResponder: UIView?

  private static func currentFirstResponder(in window: UIWindow) -> UIView? {
    if let cached = cachedFirstResponder, cached.isFirstResponder, cached.window === window {
      return cached
    }
    let found = findFirstResponder(in: window)
    if found != nil { cachedFirstResponder = found }
    return found
  }

  private static func findFirstResponder(in view: UIView) -> UIView? {
    if view.isFirstResponder { return view }
    for subview in view.subviews {
      if let found = findFirstResponder(in: subview) { return found }
    }
    return nil
  }

  /// A rect is usable only if every component is finite — `isNull`/`isInfinite` do NOT
  /// catch NaN, and a NaN slipping into the container transform silently blanks the menu.
  private static func isFinite(_ rect: CGRect) -> Bool {
    rect.origin.x.isFinite && rect.origin.y.isFinite && rect.size.width.isFinite
      && rect.size.height.isFinite
  }

  /// The current text selection's rect in SCREEN coordinates (i.e. where the selection is
  /// actually rendered in the docked app), or nil when no text input is being edited.
  /// Sources the rect from the first responder via the public `UITextInput` protocol and
  /// maps it through the dock transform. The hierarchy walk only runs while an edit menu
  /// is on screen, so its cost is irrelevant.
  private static func selectionScreenRect() -> CGRect? {
    var result: CGRect? = nil
    forEachWindow { window in
      guard result == nil, window !== backdropWindow, !isKeyboardWindow(window) else { return }
      let geometry = dockGeometry(for: window.screen.bounds)
      guard window.transform == geometry.target else { return }
      guard let responder = currentFirstResponder(in: window) as? UIView & UITextInput,
        let range = responder.selectedTextRange
      else { return }

      var union = CGRect.null
      for selection in responder.selectionRects(for: range)
      where !selection.rect.isEmpty && isFinite(selection.rect) {
        union = union.union(selection.rect)
      }
      if union.isNull {
        let caret = responder.caretRect(for: range.start)
        if !caret.isNull && isFinite(caret) { union = caret }
      }
      guard !union.isNull, isFinite(union) else { return }

      let mapped = geometry.screenRect(fromWindowRect: responder.convert(union, to: window))
      guard isFinite(mapped) else { return }
      result = mapped
    }
    return result
  }

  // MARK: - Window observation

  /// Re-asserts the transform on ALL windows: now, after the current runloop turn, and
  /// after the presentation animation settles. UIKit can reset the MAIN window's geometry
  /// while presenting a new one (observed with `Alert.alert`), so reacting to the new
  /// window alone is not enough. `apply(to:)` is idempotent, so extra passes are cheap.
  /// Always un-animated: these passes are corrections, not transitions.
  private static func reassertAllWindows() {
    applyToAllWindows(animated: false)
    DispatchQueue.main.async { applyToAllWindows(animated: false) }
    DispatchQueue.main.asyncAfter(deadline: .now() + reassertSettleDelay) {
      applyToAllWindows(animated: false)
    }
  }

  private static func startObserving() {
    guard observers.isEmpty else { return }
    let names: [Notification.Name] = [
      // A new window became visible (alert, action sheet, keyboard, library overlay).
      UIWindow.didBecomeVisibleNotification,
      // Key-window changes (e.g. an alert's makeKeyAndVisible) can re-layout windows.
      UIWindow.didBecomeKeyNotification,
      // Dismissals are another moment when UIKit touches window geometry.
      UIWindow.didBecomeHiddenNotification,
      // Keyboard presentation may also reset window geometry.
      UIResponder.keyboardDidShowNotification,
    ]
    observers = names.map { name in
      NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { note in
        guard active else { return }
        let objectDescription = note.object.map { String(describing: type(of: $0)) } ?? "nil"
        dlog("notification \(name.rawValue) object=\(objectDescription)")
        reassertAllWindows()
      }
    }
  }

  private static func stopObserving() {
    observers.forEach { NotificationCenter.default.removeObserver($0) }
    observers = []
  }
}
