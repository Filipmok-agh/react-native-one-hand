package expo.modules.onehand

import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Native "all windows" one-hand mode (Android).
 *
 * Mirrors the iOS module: instead of transforming a single React subtree, every window of
 * the process is visually scaled towards a bottom corner. Windows are enumerated with the
 * public `WindowInspector.getGlobalWindowViews()` API (API 29+), which returns the root
 * (decor) views of every window this process attached — the activity, RN `<Modal>`'s
 * fullscreen dialog, `Alert.alert`'s floating dialog, popups. Windows of other processes
 * (notably the IME/keyboard) are unreachable by design — on Android the keyboard can
 * never be scaled, which is why the JS keyboard policy is cross-platform.
 *
 * Implementation notes:
 *
 * - Android touch trap (the analog of the iOS frame/bounds trap): the transform must be
 *   applied to a CHILD of the decor view, never to the decor itself. Touch coordinates
 *   are mapped through a view's inverse matrix by its PARENT ViewGroup during dispatch;
 *   the decor has no parent view, so scaling it would leave taps landing at unscaled
 *   coordinates. Scaling `decor.getChildAt(0)` keeps hit-testing correct for free.
 *
 * - Fullscreen windows (activity, RN Modal's `Theme.FullScreenDialog`) get a top-left
 *   pivot scale plus an explicit translation on their content child — geometrically a
 *   bottom-corner dock, but the translation animates on a side switch (pivots do not).
 *   Centered floating windows (alerts, pickers) are scaled about their center and their
 *   WINDOW is offset via `WindowManager.updateViewLayout` so they land where the docking
 *   transform would put them; anchored/edge-gravity windows are scale-only (they position
 *   themselves from their anchor's already-transformed coordinates).
 *
 * - New windows appearing while the mode is active are caught by a per-frame
 *   `Choreographer` pass. A fullscreen window (RN Modal) gets its transform from an
 *   `OnPreDrawListener` before its first draw; a floating window (an alert, a picker) is
 *   held at alpha 0 until its reposition is observed to have settled. Either way the
 *   window never paints full-sized (parity with the iOS "snap before first paint"
 *   behavior).
 *
 * - The gray backdrop is NOT an extra window: the exposed area of a scaled window shows
 *   that window's background, so the activity window's background drawable is swapped for
 *   a gray drawable with the hint text. Taps that no child claims (i.e. taps on the
 *   exposed backdrop) are caught by an `OnTouchListener` on the decor and emit
 *   `onDismissRequest` — the native side never mutates mode state itself.
 *
 * - Requires API 29 (Android 10). On older devices the module is inert (logs a warning).
 */
class OneHandWindowsModule : Module() {
  private var active = false
  private var scale = DEFAULT_SCALE
  private var dockRight = true
  private var dismissHint = ""

  /**
   * Backdrop colors from JS (React Native `processColor` — AARRGGBB ints, which is
   * exactly Android's color-int format). Null = the built-in defaults.
   */
  private var backdropColor: Int? = null
  private var backdropHintColor: Int? = null

  /**
   * Roots we already processed (decor -> the content child carrying the transform).
   * Values are weak: the child strongly references its parent (the KEY) via mParent, so a
   * strong value would defeat WeakHashMap expunging and retain every dismissed dialog's
   * view tree for the whole active session.
   */
  private val scaledRoots = WeakHashMap<View, WeakReference<View>>()

  /** Floating windows we offset (decor -> original LayoutParams x/y). */
  private val floatingOrigins = WeakHashMap<View, IntArray>()

  /** Floating windows still hidden while their reposition commits (decor -> frames). */
  private val floatingHiddenFrames = WeakHashMap<View, Int>()

  /**
   * Where a floating window sat when we first saw it (may be (0,0) if it was not laid out
   * yet) and where it sat on the previous pass — together they tell us whether our
   * reposition has actually landed. See the reveal logic in [reconcileFloating].
   */
  private val floatingPreMoveLocation = WeakHashMap<View, IntArray>()
  private val floatingLastLocation = WeakHashMap<View, IntArray>()

  /** Scratch array for `getLocationOnScreen` — this runs every frame; do not allocate. */
  private val locationScratch = IntArray(2)

  /** Scratch for [selectionVisualAnchor]: visual (center x, selection top y) on screen. */
  private val selectionAnchorScratch = FloatArray(2)

  /** Floating windows whose reposition already failed once (warn-once bookkeeping). */
  private val floatingUpdateFailures = WeakHashMap<View, Boolean>()

  /**
   * Floating windows whose card background we moved from the decor onto the scaled child
   * (decor -> weak ref of the moved drawable; null value = checked, nothing to move).
   * Some dialog themes — dark Material in particular — draw the rounded card as the
   * WINDOW/decor background, which is not affected by scaling the decor's child, so the
   * card would render full-size while the content shrinks. The drawable is only moved
   * when the child has no background of its own — never silently dropped. Weak value:
   * the drawable's callback chain leads back to the key.
   */
  private val movedDecorBackground = WeakHashMap<View, WeakReference<Drawable>?>()

  /** Fullscreen roots we installed a backdrop-tap listener on. */
  private val touchListenerRoots = WeakHashMap<View, Boolean>()

  /** The exact Window the backdrop drawable was installed on (the restore target). */
  private var backdropTarget: WeakReference<Window>? = null
  private var previousWindowBackground: Drawable? = null
  private var pendingBackdropRestore: Runnable? = null

  private val mainHandler = Handler(Looper.getMainLooper())

  private val frameCallback = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
      if (!active) return
      // Resolve the activity ONCE per frame and hand it down, so applyToAllWindows does
      // not repeat the lookup.
      val activity = currentActivitySafe()
      val decor = activity?.window?.peekDecorView()
      if (decor != null && decor.windowVisibility == View.VISIBLE) {
        // Measured around the transform pass — the cost the app actually pays per vsync.
        val startNanos = if (PROFILE_FRAME_PASS) System.nanoTime() else 0L
        applyToAllWindows(activity, animated = false)
        if (PROFILE_FRAME_PASS) recordPassDuration(System.nanoTime() - startNanos)
        Choreographer.getInstance().postFrameCallback(this)
      } else {
        // App window not visible (backgrounded, or between activities during recreation):
        // drop to a slow heartbeat instead of waking the main thread at vsync rate. The
        // loop re-arms at full rate as soon as the window is visible again.
        Choreographer.getInstance().postFrameCallbackDelayed(this, BACKGROUND_POLL_MS)
      }
    }
  }

  override fun definition() = ModuleDefinition {
    Name("OneHandWindows")

    Events("onDismissRequest")

    // runOnQueue(MAIN): the body runs on the UI thread (required to touch views/windows)
    // AND returns Unit — never a coroutine Job, which the bridge cannot serialize.
    AsyncFunction("setScale") {
      scaleValue: Double, side: String, dismissHint: String, backdropColor: Int?,
      dismissHintColor: Int? ->
      if (isSupported()) {
        // Mirror of the JS useSafeScale clamp — the bridge is callable by any
        // in-process code, and NaN would crash roundToInt() in reconcileFloating.
        scale = scaleValue.toFloat().takeIf { it.isFinite() }?.coerceIn(MIN_SCALE, MAX_SCALE)
          ?: DEFAULT_SCALE
        dockRight = side != "left"
        // Cap the hint — StaticLayout lays it out on the UI thread inside draw().
        this@OneHandWindowsModule.dismissHint = dismissHint.take(MAX_HINT_LENGTH)
        this@OneHandWindowsModule.backdropColor = backdropColor
        this@OneHandWindowsModule.backdropHintColor = dismissHintColor
        val wasActive = active
        active = true
        // Force re-application on ALL roots (side/scale may have changed while active).
        // Floating origins are kept — the offset math is idempotent against the origin.
        scaledRoots.clear()
        installBackdrop()
        // The only animated pass: entering the mode / switching sides is a user-visible
        // transition. Windows discovered later by the frame loop snap into place.
        applyToAllWindows(animated = true)
        if (!wasActive) {
          Choreographer.getInstance().postFrameCallback(frameCallback)
        }
      } else {
        Log.w(TAG, "One-hand mode requires Android 10 (API 29) or newer — ignoring.")
      }
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("reset") {
      deactivate()
    }.runOnQueue(Queues.MAIN)

    OnDestroy {
      // JS reload / module teardown: never leave windows transformed. The handler post
      // is deliberate — mainQueue may already be cancelled during destroy. runCatching:
      // by the time the post runs, the AppContext may already be gone.
      mainHandler.post { runCatching { deactivate() } }
    }
  }

  private fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

  /**
   * `Module.appContext` throws once the module is destroyed (weak holder cleared). Work
   * that can run around teardown — the frame callback, the posted OnDestroy deactivate —
   * must not crash on that window.
   */
  private fun currentActivitySafe(): android.app.Activity? =
    // Plain try/catch rather than `runCatching`: this is on the per-frame path, and
    // runCatching wraps the outcome in a Result that boxes rather than staying a value
    // class here. Same semantics, no allocation.
    try {
      appContext.currentActivity
    } catch (_: Throwable) {
      null
    }

  // region Core transform pass

  private fun applyToAllWindows(animated: Boolean) {
    applyToAllWindows(currentActivitySafe() ?: return, animated)
  }

  /**
   * Whether [root] is a window of a DIFFERENT activity of this process than the tracked
   * one. Primary signal: an activity's MAIN window is the only one typed
   * `TYPE_BASE_APPLICATION`, stamped before the window is added — already correct on the
   * pre-layout frame where the (one-shot) fullscreen decision is made
   * (`applicationWindowToken` is still null that early — measured). Secondary: a dialog
   * shown BY a foreign activity keeps `TYPE_APPLICATION`, but its context unwraps to its
   * owner. The tracked decor is matched by identity first, so a stale `currentActivity`
   * can never classify our own window as foreign. A window resolving to no activity
   * (application context — banners, floating widgets) counts as ours and docks.
   */
  private fun isForeignWindow(
    activity: android.app.Activity,
    activityDecor: View,
    root: View,
    params: WindowManager.LayoutParams,
  ): Boolean {
    if (root === activityDecor) return false
    if (params.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) return true
    val owner = root.ownerActivity()
    return owner != null && owner !== activity
  }

  /**
   * The activity a DIALOG/POPUP window belongs to (unwrapping dialog theme wrappers).
   * Always null for an activity's MAIN window — its decor's `DecorContext` chain is based
   * on the APPLICATION context (measured), hence the type check in [isForeignWindow].
   */
  private fun View.ownerActivity(): android.app.Activity? {
    var context = this.context
    while (context is ContextWrapper) {
      if (context is android.app.Activity) return context
      context = context.baseContext
    }
    return null
  }

  /**
   * Undo anything done to a root that BECAME foreign (the tracked activity changed after
   * we scaled it). Normally a no-op; without it the next `setScale` would drop the root
   * from [scaledRoots] and that screen would stay shrunk forever. Mirrors deactivate()'s
   * per-root restore — cleanup is deliberately NOT gated on a live [scaledRoots] entry.
   */
  private fun releaseForeignRoot(root: View) {
    scaledRoots.remove(root)?.get()?.let { child ->
      child.animate().cancel()
      child.scaleX = 1f
      child.scaleY = 1f
      child.translationX = 0f
      child.translationY = 0f
    }
    root.alpha = 1f
    floatingHiddenFrames.remove(root)
    floatingPreMoveLocation.remove(root)
    floatingLastLocation.remove(root)
    floatingUpdateFailures.remove(root)
    if (touchListenerRoots.remove(root) != null) root.setOnTouchListener(null)
    movedDecorBackground.remove(root)?.get()?.let { moved ->
      (root as? ViewGroup)?.getChildAt(0)?.let {
        if (it.background === moved) it.background = null
      }
      root.background = moved
    }
    floatingOrigins.remove(root)?.let { origin ->
      val params = root.layoutParams as? WindowManager.LayoutParams ?: return@let
      params.x = origin[0]
      params.y = origin[1]
      params.flags = params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
      // Any WindowManager routes updateViewLayout by view identity; the owner is null
      // for a dialog-themed activity's main window, hence the fallback.
      val windowManager = root.ownerActivity()?.windowManager
        ?: currentActivitySafe()?.windowManager
      runCatching { windowManager?.updateViewLayout(root, params) }
    }
  }

  private fun applyToAllWindows(activity: android.app.Activity, animated: Boolean) {
    if (!isSupported()) return
    val activityDecor = activity.window?.decorView ?: return
    val screenW = activityDecor.width
    val screenH = activityDecor.height
    if (screenW == 0 || screenH == 0) return

    // Discovery MUST run on every frame — do not throttle it back.
    //
    // This pass runs in the Choreographer animation stage, which precedes the traversal
    // stage of the same frame. A window discovered here is hidden and repositioned before
    // it can lay out and paint. Miss that one frame and the dialog paints once at its
    // undocked position — the flash this loop exists to prevent.
    //
    // An earlier version scanned only every 4th idle frame and relied on the activity's
    // window-focus change to force an immediate pass. Measured on a Pixel 9 emulator
    // (API 36), that event arrives too late: with the throttle on, a date picker was first
    // seen already laid out (`decor.width != 0`, i.e. after its first draw); with per-frame
    // discovery the same picker is first seen at (0,0) with no size — before layout, in
    // time to hide it. The full pass costs ~43 us per idle frame; a glitch-free first frame
    // is worth that.

    // Heal the backdrop: the activity may have been recreated while active (theme change,
    // don't-keep-activities), or the initial install may have raced a null activity.
    // Cheap identity check when nothing changed.
    if (active && backdropTarget?.get() !== activity.window) {
      installBackdrop()
    }
    for (root in WindowInspector.getGlobalWindowViews()) {
      val params = root.layoutParams as? WindowManager.LayoutParams ?: continue
      // TYPE_TOAST: system-positioned and short-lived — never scaled or moved.
      if (params.type == WindowManager.LayoutParams.TYPE_TOAST) continue
      // A window of ANOTHER activity of this process (expo-video's fullscreen player, a
      // second native screen) is a different screen, not part of the docked app — leave
      // it alone and the mode survives underneath, like the separate-activity pickers.
      if (isForeignWindow(activity, activityDecor, root, params)) {
        releaseForeignRoot(root)
        continue
      }
      val group = root as? ViewGroup ?: continue
      if (group.childCount == 0) continue

      // Both dimensions: a full-WIDTH but wrap-height window (full-width dialog, bottom
      // banner) is still a floating window and must take the floating path (offset or
      // scale-only, by gravity), not the fullscreen corner transform.
      val isFullscreen = params.width == WindowManager.LayoutParams.MATCH_PARENT &&
        params.height == WindowManager.LayoutParams.MATCH_PARENT
      if (DEBUG && !isFullscreen && !scaledRoots.containsKey(group)) {
        Log.d(
          TAG,
          "floating window ${root.javaClass.simpleName} type=${params.type} " +
            "w=${params.width} h=${params.height} gravity=${params.gravity} " +
            "pos=(${params.x},${params.y}) child=${group.getChildAt(0)?.javaClass?.simpleName} " +
            "childSize=(${group.getChildAt(0)?.width},${group.getChildAt(0)?.height})",
        )
      }
      if (isFullscreen) {
        // Fullscreen windows are stable: a child scale, applied once, persists.
        if (!scaledRoots.containsKey(group)) {
          applyFullscreen(group, animated && root === activityDecor)
          installDismissListener(group)
        }
      } else {
        // Floating windows (dialogs, popups) re-assert their own layout after we touch
        // them, so scale + position are reconciled EVERY frame (both idempotent — no-ops
        // once they match, so no jitter after the dialog settles).
        reconcileFloating(activity, group, params, screenW, screenH)
      }
    }
  }

  private var passCount = 0
  private var passTotalNanos = 0L
  private var passMaxNanos = 0L

  /**
   * Aggregates how long one frame pass actually costs and logs mean/max every
   * [PROFILE_REPORT_FRAMES] frames. Off by default ([PROFILE_FRAME_PASS]) — flip it when
   * deciding whether the per-frame window enumeration is worth throttling on a given
   * device. Reported as an aggregate rather than per frame because a per-frame log would
   * itself dominate the measurement.
   */
  private fun recordPassDuration(nanos: Long) {
    passCount++
    passTotalNanos += nanos
    if (nanos > passMaxNanos) passMaxNanos = nanos
    if (passCount < PROFILE_REPORT_FRAMES) return
    Log.i(
      TAG,
      "frame pass over $passCount frames: mean=${passTotalNanos / passCount}ns " +
        "max=${passMaxNanos}ns",
    )
    passCount = 0
    passTotalNanos = 0L
    passMaxNanos = 0L
  }

  /**
   * Fullscreen window (activity / RN Modal): scale the content child about the docking
   * corner. The pivot needs the child's laid-out size — for a freshly created window the
   * transform is installed from OnPreDraw (after layout, before the first draw), so the
   * window never paints full-sized.
   */
  private fun applyFullscreen(root: ViewGroup, animated: Boolean) {
    val child = root.getChildAt(0) ?: return
    scaledRoots[root] = WeakReference(child)

    val install = {
      // Top-left pivot + explicit translation instead of a corner pivot: geometrically
      // identical (with scale and translation interpolating together the docking corner
      // stays pinned, so the enable animation still reads as "shrink into the corner"),
      // but the translation ANIMATES on a side switch — pivots are not animatable, and a
      // pivot change teleports the content to the other corner.
      child.pivotX = 0f
      child.pivotY = 0f
      val targetX = if (dockRight) child.width * (1 - scale) else 0f
      val targetY = child.height * (1 - scale)
      if (animated) {
        child.animate()
          .scaleX(scale).scaleY(scale)
          .translationX(targetX).translationY(targetY)
          .setDuration(ANIMATION_MS)
          .start()
      } else {
        child.scaleX = scale
        child.scaleY = scale
        child.translationX = targetX
        child.translationY = targetY
      }
    }

    if (child.width > 0 && child.height > 0) {
      install()
    } else {
      child.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
          child.viewTreeObserver.removeOnPreDrawListener(this)
          // Re-check the root is still managed: it may have been released as a foreign
          // window while awaiting layout — installing then would re-scale it for good.
          if (active && scaledRoots[root]?.get() === child) install()
          return true
        }
      })
    }
  }

  /**
   * Floating window (alert, popup): scale the content about its center and move the whole
   * WINDOW by the docking translation, so it lands where the corner transform would put
   * it. Offset math matches iOS: t = (±W·(1−s)/2, H·(1−s)/2) in the window's gravity space.
   * This offset path applies ONLY to gravity-CENTER windows (alerts, pickers) — anchored /
   * edge-gravity windows are handled scale-only by reconcileAnchored, see the split below.
   *
   * Called every frame while active. A dialog re-applies its OWN window layout after we
   * move it (which is why a one-shot approach silently reverted), so we reconcile each
   * frame. Both steps are guarded by an equality check, so once the dialog settles at our
   * target they become no-ops — no per-frame `updateViewLayout` churn, no jitter.
   */
  private fun reconcileFloating(
    activity: android.app.Activity,
    root: ViewGroup,
    params: WindowManager.LayoutParams,
    screenW: Int,
    screenH: Int,
  ) {
    val child = root.getChildAt(0) ?: return

    // Two floating flavors, split by gravity:
    // - CENTER windows (alerts, date pickers) are positioned by gravity alone, so they
    //   must be OFFSET to land where the docking transform would put them (below).
    // - Non-CENTER windows (anchored popups via showAsDropDown — dropdowns, the text
    //   selection toolbar — and edge-gravity banners) position themselves from
    //   `getLocationInWindow()` of their anchor, which ALREADY includes our child scale
    //   in the matrix. Offsetting them would double-transform; they only need a scale
    //   (plus a dock-bounds nudge for the selection toolbar — see reconcileAnchored).
    val absoluteGravity = Gravity.getAbsoluteGravity(params.gravity, root.layoutDirection)
    val isCentered =
      absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL &&
        absoluteGravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.CENTER_VERTICAL
    if (!isCentered) {
      reconcileAnchored(activity, root, child, params, absoluteGravity, screenW)
      return
    }

    // Capture the untouched position ONCE, before we ever offset it; the target is always
    // computed from this origin, so re-application never accumulates.
    val isNewlyManaged = !floatingOrigins.containsKey(root)
    val origin = floatingOrigins.getOrPut(root) { intArrayOf(params.x, params.y) }
    scaledRoots[root] = WeakReference(child)

    if (isNewlyManaged) {
      // Hide the window until it settles at the docked position. Unlike the child scale
      // (synchronous), `updateViewLayout` is ASYNC — the move lands a frame later — so
      // without this the dialog paints one frame at its original position before snapping
      // into the dock. Revealed below once settled, with a hard frame cap so a misbehaving
      // window can never stay invisible.
      root.alpha = 0f
      floatingHiddenFrames[root] = 0
      root.getLocationOnScreen(locationScratch)
      floatingPreMoveLocation[root] = intArrayOf(locationScratch[0], locationScratch[1])
    }

    // Move the decor's card background onto the scaled child once, so a dialog whose card
    // is drawn as the window background (dark Material) scales with its content instead of
    // staying full-size. Only when the child has no background of its own — replacing an
    // existing child background (or dropping the decor layer) would visibly change the
    // dialog. No-op for themes that already draw the card on the child.
    if (!movedDecorBackground.containsKey(root)) {
      val decorBg = root.background
      if (decorBg != null && child.background == null) {
        child.background = decorBg
        root.background = null
        movedDecorBackground[root] = WeakReference(decorBg)
      } else {
        movedDecorBackground[root] = null
      }
    }

    val direction = if (dockRight) 1 else -1
    val targetX = origin[0] + (direction * screenW * (1 - scale) / 2f).roundToInt()
    val targetY = origin[1] + (screenH * (1 - scale) / 2f).roundToInt()
    // A dialog window is often ~0.87 screen-wide — wider than the 0.75 dock region — so
    // WindowManager clamps a horizontal offset back on-screen (vertical has room, hence Y
    // docks but X doesn't). FLAG_LAYOUT_NO_LIMITS lets the window extend past the screen
    // edge; the content is only render-scaled (0.75) and centered, so the overflow is the
    // window's transparent margin — invisible — while the visible content lands in the
    // corner. The flag is cleared on reset.
    val noLimits = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    val needsFlag = params.flags and noLimits == 0
    val needsMove = params.x != targetX || params.y != targetY || needsFlag
    var updateError: String? = null
    if (needsMove) {
      params.x = targetX
      params.y = targetY
      params.flags = params.flags or noLimits
      try {
        activity.windowManager.updateViewLayout(root, params)
      } catch (e: Throwable) {
        updateError = e.javaClass.simpleName + ": " + e.message
        // Warn ONCE per window, in release builds too: a persistently failing reposition
        // (a detached-window race, an owner fighting the layout) leaves that dialog
        // undocked at its original position, and with DEBUG off that used to be
        // completely silent. The flag is cleared when the window is forgotten.
        if (floatingUpdateFailures.put(root, true) == null) {
          Log.w(TAG, "updateViewLayout failed for ${root.javaClass.simpleName}: $updateError")
        }
      }
    }

    val scaleReady = child.width > 0 && child.height > 0
    if (scaleReady && child.scaleX != scale) {
      child.scaleX = scale
      child.scaleY = scale
    }

    // Reveal once the window has ACTUALLY settled at the docked position.
    //
    // `needsMove` alone is not proof: `params` is the window's live LayoutParams object,
    // mutated in place above, so it reports "already at target" on the very next frame —
    // while WindowManager applies the move asynchronously (through WMS). Trusting it
    // revealed the dialog at its OLD position for a frame or two: correctly scaled but
    // centered on the full screen, then snapping into the dock. So verify against ground
    // truth instead: observe the decor's real on-screen position and reveal only once it
    // has settled (see the reveal condition below).
    // The frame cap still guarantees a window fighting our reposition becomes visible.
    if (root.alpha != 1f) {
      val hiddenFrames = (floatingHiddenFrames[root] ?: 0) + 1
      floatingHiddenFrames[root] = hiddenFrames

      // The reveal condition CANNOT be "the window sits where we predict": a CENTER-gravity
      // window is centered by WindowManager inside the area left by the system bars, not
      // inside the raw screen, so a predicted target is off by half the navigation-bar
      // height (measured: 40 px here) and the gate would never open — every window would
      // fall through to the frame cap. Observe the real position instead and reveal when it
      // has settled: a valid (laid-out) location, unchanged since the previous pass, and —
      // when we saw the window before our move — different from where it started.
      root.getLocationOnScreen(locationScratch)
      val laidOut = locationScratch[0] != 0 || locationScratch[1] != 0
      val previous = floatingLastLocation[root]
      val stable = previous != null &&
        previous[0] == locationScratch[0] &&
        previous[1] == locationScratch[1]
      val startedAt = floatingPreMoveLocation[root]
      val movedFromStart = startedAt == null ||
        (startedAt[0] == 0 && startedAt[1] == 0) ||
        startedAt[0] != locationScratch[0] ||
        startedAt[1] != locationScratch[1]

      if (previous == null) {
        floatingLastLocation[root] = intArrayOf(locationScratch[0], locationScratch[1])
      } else {
        previous[0] = locationScratch[0]
        previous[1] = locationScratch[1]
      }

      val settled = scaleReady && !needsMove && laidOut && stable && movedFromStart
      if (settled || hiddenFrames > MAX_HIDDEN_FRAMES) {
        root.alpha = 1f
        floatingHiddenFrames.remove(root)
        floatingLastLocation.remove(root)
        floatingPreMoveLocation.remove(root)
      }
    }

    if (DEBUG && (floatingLogCounter++ % 20 == 0)) {
      Log.d(
        TAG,
        "reconcile children=${root.childCount} " +
          "child0=${child.javaClass.simpleName}(${child.width}x${child.height}) " +
          "scaleX=${child.scaleX} origin=(${origin[0]},${origin[1]}) " +
          "target=($targetX,$targetY) nowPos=(${params.x},${params.y}) " +
          "updateError=$updateError",
      )
    }
  }

  private var floatingLogCounter = 0

  /**
   * Anchored / edge-gravity floating window (dropdown, text-selection toolbar, banner):
   * scale the content in place. The owner positioned the window from its anchor's
   * on-screen location, which already reflects the docked app's transform. The pivot sits
   * at the gravity-resolved corner (TOP|START → top-left), so the content stays glued to
   * the anchor point instead of shrinking away from it.
   *
   * One correction on top, for TOP|LEFT windows only (the gravity whose x/y are absolute
   * screen coordinates; edge-gravity banners read x/y as offsets and are left alone):
   * the text-selection toolbar is horizontally RE-CENTERED over the real selection and
   * every popup is clamped into the docked region — see the inline comment for why the
   * toolbar's own position cannot be trusted.
   */
  private fun reconcileAnchored(
    activity: android.app.Activity,
    root: ViewGroup,
    child: View,
    params: WindowManager.LayoutParams,
    absoluteGravity: Int,
    screenW: Int,
  ) {
    if (child.width == 0 || child.height == 0) return
    scaledRoots[root] = WeakReference(child)
    if (child.scaleX != scale) {
      child.pivotX = when (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
        Gravity.RIGHT -> child.width.toFloat()
        Gravity.CENTER_HORIZONTAL -> child.width / 2f
        else -> 0f
      }
      child.pivotY = when (absoluteGravity and Gravity.VERTICAL_GRAVITY_MASK) {
        Gravity.BOTTOM -> child.height.toFloat()
        Gravity.CENTER_VERTICAL -> child.height / 2f
        else -> 0f
      }
      child.scaleX = scale
      child.scaleY = scale
    }

    val isTopLeft =
      absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT &&
        absoluteGravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.TOP
    if (!isTopLeft) return
    // Visible content spans [x, x + width·s] (top-left pivot). Reconciled every frame,
    // like the centered path — the toolbar re-asserts its own position on layout changes.
    val dockLeft = if (dockRight) (screenW * (1 - scale)).roundToInt() else 0
    val dockRightEdge = if (dockRight) screenW else (screenW * scale).roundToInt()
    val visibleWidth = (child.width * scale).roundToInt()
    val maxX = (dockRightEdge - visibleWidth).coerceAtLeast(dockLeft)
    // The selection toolbar's own position cannot be trusted: its owner mixes the SCALED
    // anchor location with UNSCALED text offsets and toolbar dimensions, so while docked
    // it drifts sideways by up to (1−s)·toolbarWidth and floats (1−s)·toolbarHeight too
    // high above the text. Re-anchor it to the real selection read from the focused
    // TextView — centered horizontally, pill seated just above the selected line. Other
    // popups keep their owner position, only clamped into the dock.
    // TYPE_APPLICATION_ABOVE_SUB_PANEL — the floating toolbar's window type; the constant
    // itself is not public SDK.
    val isSelectionToolbar =
      params.type == WindowManager.LayoutParams.FIRST_SUB_WINDOW + 5
    val anchor = if (isSelectionToolbar) selectionVisualAnchor(activity) else null
    val targetX = (anchor?.let { (it[0] - visibleWidth / 2f).roundToInt() } ?: params.x)
      .coerceIn(dockLeft, maxX)
    var targetY = params.y
    if (anchor != null) {
      // 8 dp mirrors the system's own content-rect margin. The pill does not fill the
      // window (the container reserves room for the overflow panel), so its real bottom
      // edge is seated, not the window's.
      val pillBottom = visiblePillBottom(child)
      val gap = 8f * child.resources.displayMetrics.density * scale
      // Seat the window with the UNSCALED pill bottom: the toolbar's touchable region is
      // owner-computed in unscaled coordinates (hidden API, not correctable from app
      // code) and would otherwise reach below the visible pill and swallow the next
      // long-press on the selected line. The child is then translated down by the scale
      // discrepancy, so the VISIBLE pill still sits just above the line — pill and
      // touchable region end on the same edge, like the unscaled original.
      targetY = (anchor[1] - gap - pillBottom).roundToInt().coerceAtLeast(0)
      val translation = (1 - scale) * pillBottom
      if (child.translationY != translation) child.translationY = translation
    } else if (child.translationY != 0f) {
      // The selection became unreadable while the toolbar window is still up — drop the
      // seating translation instead of leaving the content offset against an owner y.
      child.translationY = 0f
    }
    if (targetX == params.x && targetY == params.y) return
    floatingOrigins.getOrPut(root) { intArrayOf(params.x, params.y) }
    params.x = targetX
    params.y = targetY
    try {
      activity.windowManager.updateViewLayout(root, params)
    } catch (e: Throwable) {
      if (floatingUpdateFailures.put(root, true) == null) {
        Log.w(
          TAG,
          "updateViewLayout failed for ${root.javaClass.simpleName}: " +
            "${e.javaClass.simpleName}: ${e.message}",
        )
      }
    }
  }

  /**
   * On-screen (scale-applied) anchor of the focused TextView's selection — [center x,
   * top y of the first selected line] — or null when there is no readable selection.
   * `getLocationOnScreen` already includes the docked transform; the text-local offsets
   * must be scaled manually.
   */
  private fun selectionVisualAnchor(activity: android.app.Activity): FloatArray? = runCatching {
    val textView = activity.currentFocus as? TextView ?: return null
    val layout = textView.layout ?: return null
    val start = textView.selectionStart
    val end = textView.selectionEnd
    if (start < 0 || end < 0) return null
    val from = minOf(start, end)
    val to = maxOf(start, end)
    val localX: Float
    if (layout.getLineForOffset(from) == layout.getLineForOffset(to)) {
      val a = layout.getPrimaryHorizontal(from)
      val b = layout.getPrimaryHorizontal(to)
      localX = (a + b) / 2f
    } else {
      // Multi-line selection: its bounding box spans the full layout width.
      localX = layout.width / 2f
    }
    val localTop = layout.getLineTop(layout.getLineForOffset(from)).toFloat()
    textView.getLocationOnScreen(locationScratch)
    selectionAnchorScratch[0] =
      locationScratch[0] + (localX + textView.totalPaddingLeft - textView.scrollX) * scale
    selectionAnchorScratch[1] =
      locationScratch[1] + (localTop + textView.totalPaddingTop - textView.scrollY) * scale
    selectionAnchorScratch
  }.getOrNull()

  /**
   * Bottom edge (window-local, unscaled) of the toolbar content actually visible — the
   * union of the container's visible children, falling back to the container itself.
   */
  private fun visiblePillBottom(child: View): Int {
    val group = child as? ViewGroup ?: return child.height
    var bottom = 0
    for (i in 0 until group.childCount) {
      val v = group.getChildAt(i)
      if (v.visibility == View.VISIBLE && v.width > 0) bottom = maxOf(bottom, v.bottom)
    }
    return if (bottom > 0) bottom else child.height
  }

  // endregion

  // region Dismissal (tap on the exposed backdrop)

  /**
   * A tap that no child of a fullscreen window claims lands on the decor itself — that is
   * exactly a tap on the exposed backdrop area around the docked content. Listeners are
   * installed on every fullscreen root (activity AND fullscreen dialogs), because with an
   * RN Modal open it is the dialog's window that receives the touches.
   */
  private fun installDismissListener(root: ViewGroup) {
    if (touchListenerRoots.containsKey(root)) return
    touchListenerRoots[root] = true
    root.setOnTouchListener { _, event ->
      if (active && event.actionMasked == MotionEvent.ACTION_DOWN) {
        // runCatching: a tap can race module teardown (registry already cleared).
        runCatching { sendEvent("onDismissRequest") }
        true
      } else {
        false
      }
    }
  }

  // endregion

  // region Backdrop

  private fun installBackdrop() {
    val window = currentActivitySafe()?.window ?: return
    // Re-entering the mode cancels any pending restore from a previous disable — a stale
    // runnable from a rapid disable→enable→disable could otherwise remove the backdrop
    // mid-exit-animation.
    pendingBackdropRestore?.let(mainHandler::removeCallbacks)
    pendingBackdropRestore = null

    if (backdropTarget?.get() !== window) {
      // New target window (first enable, or the activity was recreated while active).
      // Capture its own background as the restore value — but never our backdrop drawable
      // (possible if a previous restore was skipped), which would make the gray permanent.
      val current = window.decorView.background
      previousWindowBackground = if (current is BackdropDrawable) null else current
      backdropTarget = WeakReference(window)
    }
    val density = window.decorView.resources.displayMetrics.density
    // Offset the hint below the status bar / display cutout, mirroring the iOS label's
    // safe-area constraint. The window is edge-to-edge (RN 0.85 / targetSdk 35 default),
    // so the drawable's bounds start at the physical screen top and a fixed offset would
    // put the hint under the status bar on notched devices. Insets are unavailable before
    // the first layout pass, in which case the fixed offset is the sane fallback.
    val topInset = window.decorView.rootWindowInsets?.let { insets ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).top
      } else {
        // API 29 (our minimum): WindowInsets.Type and getInsets() only exist from API 30 —
        // calling them here is a runtime NoSuchMethodError. The deprecated accessor is the
        // correct source on that single API level.
        @Suppress("DEPRECATION")
        insets.systemWindowInsetTop
      }
    } ?: 0
    window.setBackgroundDrawable(
      BackdropDrawable(dismissHint, density, topInset, backdropColor, backdropHintColor),
    )
  }

  private fun removeBackdropAfterRestore() {
    // Keep the gray backdrop visible while the content animates back to full size. The
    // restore targets EXACTLY the window the backdrop was installed on — not whatever
    // activity is current 300 ms later (it may be a different, recreated one) — and state
    // is cleared only here or on re-enable, never after a skipped write.
    pendingBackdropRestore?.let(mainHandler::removeCallbacks)
    val restore = Runnable {
      pendingBackdropRestore = null
      if (!active) {
        backdropTarget?.get()?.setBackgroundDrawable(previousWindowBackground)
        backdropTarget = null
        previousWindowBackground = null
      }
    }
    pendingBackdropRestore = restore
    mainHandler.postDelayed(restore, ANIMATION_MS + 50)
  }

  // endregion

  // region Reset

  private fun deactivate() {
    if (!active && scaledRoots.isEmpty() && backdropTarget == null) return
    active = false
    Choreographer.getInstance().removeFrameCallback(frameCallback)

    val activity = currentActivitySafe()
    val activityDecor = activity?.window?.decorView

    for ((root, childRef) in scaledRoots) {
      val child = childRef.get() ?: continue
      child.animate().cancel()
      if (root === activityDecor) {
        child.animate()
          .scaleX(1f).scaleY(1f)
          .translationX(0f).translationY(0f)
          .setDuration(ANIMATION_MS)
          .start()
      } else {
        child.scaleX = 1f
        child.scaleY = 1f
        child.translationX = 0f
        child.translationY = 0f
      }
    }
    for ((root, origin) in floatingOrigins) {
      // Never leave a window stuck invisible if the mode exits during the short
      // hidden-until-settled window.
      root.alpha = 1f
      val params = root.layoutParams as? WindowManager.LayoutParams ?: continue
      params.x = origin[0]
      params.y = origin[1]
      params.flags = params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
      runCatching { activity?.windowManager?.updateViewLayout(root, params) }
    }
    for (root in touchListenerRoots.keys) {
      root.setOnTouchListener(null)
    }
    for ((root, movedRef) in movedDecorBackground) {
      val moved = movedRef?.get() ?: continue
      (root as? ViewGroup)?.getChildAt(0)?.let {
        if (it.background === moved) it.background = null
      }
      root.background = moved
    }
    scaledRoots.clear()
    floatingOrigins.clear()
    floatingHiddenFrames.clear()
    floatingPreMoveLocation.clear()
    floatingLastLocation.clear()
    floatingUpdateFailures.clear()
    touchListenerRoots.clear()
    movedDecorBackground.clear()
    removeBackdropAfterRestore()
  }

  // endregion

  // region Backdrop drawable

  /**
   * Gray fill with the dismiss hint near the top — drawn as the activity WINDOW
   * background, so it shows exactly in the area the scaled content no longer covers.
   * `topInset` (status bar / display cutout, in px) keeps the hint clear of system UI,
   * the Android counterpart of the iOS label's safe-area constraint.
   */
  private class BackdropDrawable(
    private val hint: String,
    private val density: Float,
    private val topInset: Int,
    fillColor: Int?,
    hintColor: Int?,
  ) : Drawable() {
    private val backgroundPaint = Paint().apply { color = fillColor ?: Color.rgb(184, 184, 184) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = hintColor ?: Color.rgb(64, 64, 64)
      // this@BackdropDrawable is load-bearing: inside `apply` a bare `density` resolves
      // to the Paint.density FIELD (1.0), which silently rendered the hint at 15 px
      // instead of 15 dp.
      textSize = 15f * this@BackdropDrawable.density
      typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var textLayout: StaticLayout? = null

    override fun draw(canvas: Canvas) {
      canvas.drawRect(bounds, backgroundPaint)
      if (hint.isEmpty()) return
      val textWidth = bounds.width() - (48 * density).roundToInt()
      if (textWidth <= 0) return
      val layout = textLayout?.takeIf { abs(it.width - textWidth) < 2 }
        ?: StaticLayout.Builder.obtain(hint, 0, hint.length, textPaint, textWidth)
          .setAlignment(Layout.Alignment.ALIGN_CENTER)
          .build()
          .also { textLayout = it }
      canvas.save()
      canvas.translate(bounds.left + 24 * density, bounds.top + topInset + 32 * density)
      layout.draw(canvas)
      canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in android.graphics.drawable.Drawable")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
  }

  // endregion

  private companion object {
    const val TAG = "OneHandWindows"
    const val ANIMATION_MS = 250L

    /** Scale bounds — the native mirror of the JS `useSafeScale` validation. */
    const val DEFAULT_SCALE = 0.75f
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 0.85f

    /** Max `dismissHint` length rendered into the backdrop (see setScale). */
    const val MAX_HINT_LENGTH = 200

    /** Heartbeat interval for the frame pass while the app window is not visible. */
    const val BACKGROUND_POLL_MS = 500L

    /** Max frames a freshly repositioned floating window may stay hidden (safety valve). */
    const val MAX_HIDDEN_FRAMES = 10

    /** Diagnostic logging of enumerated windows + floating reconcile. Off by default. */
    const val DEBUG = false

    /**
     * Measure the cost of one frame pass and log mean/max periodically. Off by default;
     * turn on to decide, on real hardware, whether the per-frame window enumeration is
     * expensive enough to be worth throttling.
     */
    const val PROFILE_FRAME_PASS = false

    /** How many frames each PROFILE_FRAME_PASS report aggregates over. */
    const val PROFILE_REPORT_FRAMES = 300
  }
}
