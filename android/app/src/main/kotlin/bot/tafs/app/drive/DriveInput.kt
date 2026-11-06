package bot.tafs.app.drive

import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Whose input it is while Coach drives (docs/drive.md, "How each platform tells the person's input from Coach's"). Coach's
 * input is only what the app dispatches itself, right then, matched by the down time it created; anything else is the
 * person's. It isn't delivered, and it ends the drive: `owner_took_over`, or `stopped` for their back.
 *
 * Every window of the app is guarded: the activity's, and each dialog's and sheet's ([DriveWindow], through the wrappers in
 * DriveWindows.kt), whose `Window.Callback` is wrapped, so a touch on a sheet's scrim is caught before it's delivered too. A
 * menu's popup has no `Window`: [driveGuard] catches touches inside it, and the window under it catches those outside.
 *
 * Main thread only, as input is.
 */
object DriveInput {
  /** Whether a drive is on: Compose reads it, to hide the keyboard and guard. */
  var driving by mutableStateOf(false)
    private set

  /** Ends the drive for the person's input, with why: set by the app while a drive is on. */
  @Volatile
  var personsInput: (String) -> Unit = {}

  /** Whether one of Coach's steps is being done now: what a field's change and a dismissal are checked against. */
  @Volatile
  var coachStep: () -> Boolean = { false }

  private var acting = 0
  private var coachDown = Long.MIN_VALUE

  /** True while the app itself is dispatching Coach's input. */
  val coachIsActing: Boolean get() = acting > 0

  /** Runs [block] as Coach's own input: a back, a dismissal, a typed text. */
  fun <T> asCoach(block: () -> T): T {
    acting++
    try {
      return block()
    } finally {
      acting--
    }
  }

  /** Dispatches one of Coach's touch events to [root], marked as Coach's by its down time. */
  fun dispatch(root: View, event: MotionEvent): Boolean {
    coachDown = event.downTime
    return asCoach { root.dispatchTouchEvent(event) }
  }

  /** Whether [event] is one the app is dispatching for Coach right now. */
  fun isCoachs(event: MotionEvent): Boolean = acting > 0 && event.downTime == coachDown

  /** A touch a window got: true to deliver it; false when it's the person's during a drive, which ends it. */
  fun admit(event: MotionEvent): Boolean {
    if (!driving || isCoachs(event)) return true
    // On the Coach button, the person's touch is their Stop; anywhere else, they took over.
    val onStop = stopZones.values.any { it.contains(event.rawX.toInt(), event.rawY.toInt()) }
    personsInput(if (onStop) "stopped" else "owner_took_over")
    return false
  }

  /** Where the Coach button is on screen: a touch there during a drive is the person's Stop. */
  val stopZones = mutableMapOf<Any, Box>()

  /** A key a window got: the person's back stops the drive, any other key of theirs takes over. Volume keys stay theirs. */
  fun admit(event: KeyEvent): Boolean {
    if (!driving || acting > 0) return true
    if (KeyEvent.isMediaSessionKey(event.keyCode) || event.keyCode in VOLUME_KEYS) return true
    personsInput(if (event.keyCode == KeyEvent.KEYCODE_BACK) "stopped" else "owner_took_over")
    return false
  }

  private val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

  // MARK: The app's windows

  /** One of the app's windows that's open now: its root, its `Window` when it has one, and how Coach's back closes it. */
  class Entry(val root: View, val window: Window?, val back: () -> Boolean)

  /** The app's open windows, oldest first: the activity's, then each dialog, sheet and menu as it opened. */
  val windows = mutableListOf<Entry>()

  private val guarded = mutableMapOf<Window, Guarded>()

  /** Guards [window]: every touch and key goes through [admit] first. */
  fun guard(window: Window) {
    if (guarded.containsKey(window)) return
    val base = window.callback ?: return
    val g = Guarded(base)
    window.callback = g
    guarded[window] = g
    if (driving) g.backOn(window)
  }

  /** The window closed: its callback is put back as it was. */
  fun unguard(window: Window) {
    val g = guarded.remove(window) ?: return
    g.backOff(window)
    if (window.callback === g) window.callback = g.base
  }

  /** A drive started or ended: the person's back in each guarded window is caught first, or let go. */
  fun turn(on: Boolean) {
    driving = on
    guarded.forEach { (window, g) -> if (on) g.backOn(window) else g.backOff(window) }
  }

  /**
   * Coach's back: the topmost window's own (a sheet or a dialog closes, a menu folds, the screen goes back), never the
   * person's back gesture. False when there's nothing to go back to: at the app's first screen, back would leave it.
   */
  fun back(): Boolean = asCoach { windows.lastOrNull { it.root.isAttachedToWindow && it.root.isShown }?.back?.invoke() ?: false }

  /** A window's callback, wrapped: the person's input during a drive is caught here, before any view has it. */
  class Guarded(val base: Window.Callback) : Window.Callback by base {
    private var backCallback: Any? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (admit(event)) base.dispatchTouchEvent(event) else true

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = if (admit(event)) base.dispatchGenericMotionEvent(event) else true

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean = if (admit(event)) base.dispatchTrackballEvent(event) else true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (admit(event)) base.dispatchKeyEvent(event) else true

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = if (admit(event)) base.dispatchKeyShortcutEvent(event) else true

    /**
     * From Android 13, a back gesture isn't a key: it goes to the window's back dispatcher. Ours sits above every other there
     * while a drive is on, so the person's back stops the drive before any screen goes back. Coach's back goes to the app's
     * own dispatcher directly, never through this one.
     */
    fun backOn(window: Window) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backCallback != null) return
      val cb = OnBackInvokedCallback { personsInput("stopped") }
      window.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
      backCallback = cb
    }

    fun backOff(window: Window) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
      (backCallback as? OnBackInvokedCallback)?.let { window.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
      backCallback = null
    }
  }

  /** The `Window` of the dialog or sheet [view] is in, if it's in one. */
  fun dialogWindow(view: View): Window? {
    var v: Any? = view
    while (v != null) {
      if (v is DialogWindowProvider) return v.window
      v = (v as? View)?.parent
    }
    return null
  }
}

/**
 * Inside each dialog's and sheet's content (the wrappers in DriveWindows.kt put it there): guards its window and lists it, so
 * Coach's back closes the topmost one and the capture knows it. [dismiss] folds a menu, which has no back of its own.
 */
@Composable
fun DriveWindow(dismiss: (() -> Unit)? = null) {
  val view = LocalView.current
  val owner = if (dismiss == null) LocalOnBackPressedDispatcherOwner.current else null
  DisposableEffect(view, owner) {
    val window = DriveInput.dialogWindow(view)
    window?.let(DriveInput::guard)
    val entry = DriveInput.Entry(view.rootView, window) {
      val dispatcher = owner?.onBackPressedDispatcher
      when {
        dismiss != null -> { dismiss(); true }
        dispatcher != null && dispatcher.hasEnabledCallbacks() -> { dispatcher.onBackPressed(); true }
        else -> false
      }
    }
    DriveInput.windows += entry
    DriveSignals.emit("sheet_opened")
    onDispose {
      DriveInput.windows -= entry
      window?.let(DriveInput::unguard)
      DriveSignals.emit("sheet_closed")
    }
  }
}

/**
 * A dialog's or sheet's dismissal (a scrim tap, a swipe down, back) that Coach didn't make, during a drive, is the person's: it
 * ends the drive. The dismissal itself still happens: what the person did is theirs.
 */
fun driveDismissal(onDismissRequest: () -> Unit): () -> Unit = {
  if (DriveInput.driving && !DriveInput.coachIsActing && !DriveInput.coachStep()) DriveInput.personsInput("owner_took_over")
  onDismissRequest()
}

/**
 * A menu's dismissal. Its popup has no `Window` to guard, so the person's tap outside it reaches only this, and this runs inside
 * the touch's own dispatch: Coach's outside tap is [DriveInput.coachIsActing] then. So no step window is needed here, and a tap
 * outside that isn't Coach's ends the drive even while one of Coach's steps is under way.
 */
fun driveMenuDismissal(onDismissRequest: () -> Unit): () -> Unit = {
  if (DriveInput.driving && !DriveInput.coachIsActing) DriveInput.personsInput("owner_took_over")
  onDismissRequest()
}

/**
 * The person's input inside a window with no `Window` of its own (a menu's popup): caught on its way in, before any control
 * has it, and not delivered.
 */
fun Modifier.driveGuard(): Modifier = pointerInput(Unit) {
  awaitPointerEventScope {
    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      if (!DriveInput.driving) continue
      val motion = event.motionEvent
      if (motion != null && DriveInput.isCoachs(motion)) continue
      event.changes.forEach { it.consume() }
      DriveInput.personsInput("owner_took_over")
    }
  }
}

/**
 * Every text field's change goes through this (docs/drive.md, "Typing"): during a drive, a change while none of Coach's steps
 * is under way is the person's. It isn't taken, and it ends the drive.
 */
fun <T> driveTyped(onValueChange: (T) -> Unit): (T) -> Unit = { value ->
  if (!DriveInput.driving || DriveInput.coachIsActing || DriveInput.coachStep()) onValueChange(value)
  else DriveInput.personsInput("owner_took_over")
}

// MARK: The marks

/** A control that confirms (docs/drive.md, "Coach never confirms"): a driven touch on it is refused before it's dispatched. */
val DriveConfirms = SemanticsPropertyKey<Boolean>("DriveConfirms")

/** A field that's never captured: blanked in the screenshot, its words left out of the tree. */
val DriveSecret = SemanticsPropertyKey<Boolean>("DriveSecret")

/** An open approval's terms: blanked in the screenshot, and named in the tree only by [title]. */
val DriveApproval = SemanticsPropertyKey<String>("DriveApproval")

private var SemanticsPropertyReceiver.confirms by DriveConfirms
private var SemanticsPropertyReceiver.secret by DriveSecret
private var SemanticsPropertyReceiver.approval by DriveApproval

/** The confirm mark: on every control listed in docs/drive.md. */
fun Modifier.confirms(): Modifier = semantics { confirms = true }

/** The secret mark: a vendor's key, a password, a recovery code. */
fun Modifier.secret(): Modifier = semantics { secret = true }

/** An open approval's terms, named [title] in the tree. */
fun Modifier.approvalTerms(title: String): Modifier = semantics { approval = title }

/** On the Coach button: the person's touch there during a drive ends it as `stopped`, not as taking over. */
@Composable
fun Modifier.driveStopZone(): Modifier {
  val key = androidx.compose.runtime.remember { Any() }
  DisposableEffect(key) { onDispose { DriveInput.stopZones.remove(key) } }
  return this.then(Modifier.onGloballyPositioned { c ->
    val p = c.positionOnScreen()
    DriveInput.stopZones[key] = Box(p.x.toInt(), p.y.toInt(), p.x.toInt() + c.size.width, p.y.toInt() + c.size.height)
  })
}
