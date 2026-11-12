package bot.tafs.app.drive

import bot.tafs.app.model.Words
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The drive on Android (docs/drive.md): the app's own windows, from `WindowInspector`, drawn with `PixelCopy` (or drawn in
 * software where a window has no `Window`, a menu's popup); the view tree from Compose's semantics, the tree TalkBack and the UI
 * tests read; and touches dispatched as `MotionEvent`s to the topmost window under the point, marked as Coach's ([DriveInput]).
 * No screen recording, accessibility service or input permission. The border, the caption and the dot are windows of their
 * own ([DriveOverlay]), left out of every capture and every touch.
 */
class AndroidDriveHost(
  private val activity: Activity,
  private val overlay: DriveOverlay,
  /** The taf whose screen is open now, for what a step touched; null on screens that aren't a taf's. */
  private val currentTaf: () -> String?,
) : DriveHost {
  private val main = Handler(Looper.getMainLooper())

  /** The ids of the last tree, each to its window and node: what an `element` names. */
  private var lastIds: Map<String, Pair<View, Int>> = emptyMap()

  override fun show(state: DriveState) = overlay.show(state)

  override suspend fun await(event: String, timeoutMs: Long): Boolean = DriveSignals.await(event, timeoutMs)

  // MARK: Looking

  override suspend fun look(tree: Boolean, diagnostics: Boolean): Look = withContext(Dispatchers.Main.immediate) {
    val windows = windows()
    val shot = shot()
    val seen = semantics(windows)
    val image = capture(windows, shot, seen.blank)
    Look(
      image = image,
      tree = if (tree) treeOf(seen, shot) else null,
      diagnostics = if (diagnostics) DriveDiagnostics.read(appInfo()) else null,
    )
  }

  /** The screen as the screenshot sizes it: the activity's whole window. */
  private fun shot(): Shot = activity.window.decorView.let { Shot(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }

  /** The app's windows that show now, lowest first, each with its box on screen: never the overlay's. */
  private fun windows(): List<Pair<View, Box>> {
    val roots = runCatching { android.view.inspector.WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
    return roots.filter { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 && !DriveOverlay.isOverlay(it) }
      .map { v ->
        val at = IntArray(2).also(v::getLocationOnScreen)
        v to Box(at[0], at[1], at[0] + v.width, at[1] + v.height)
      }
  }

  /** What the semantics of every window say: their nodes, lowest window first, and the boxes to blank. */
  private class Seen(val nodes: List<Node>, val blank: List<Box>)

  private class Node(val id: String, val root: View, val node: SemanticsNode, val box: Box, val words: String?, val role: String, val actions: List<String>, val confirm: Boolean)

  private fun semantics(windows: List<Pair<View, Box>>): Seen {
    val nodes = mutableListOf<Node>()
    val blank = mutableListOf<Box>()
    windows.forEachIndexed { w, (root, _) ->
      for (owner in composeRoots(root)) {
        fun visit(n: SemanticsNode, around: Boolean) {
          val c = n.config
          val box = boxOf(n)
          // A confirming control, or one inside a container that confirms (the approval gate): the mark holds for both.
          val confirm = around || c.getOrNull(DriveConfirms) == true
          val approval = c.getOrNull(DriveApproval)
          if (approval != null) {
            blank += box
            nodes += Node("$w.${n.id}", root, n, box, "An approval is open: $approval", "text", emptyList(), false)
            return
          }
          val secret = c.getOrNull(DriveSecret) == true || c.contains(SemanticsProperties.Password)
          if (secret) blank += box
          val actions = buildList {
            if (c.contains(SemanticsActions.OnClick)) add("tap")
            if (c.contains(SemanticsActions.OnLongClick)) add("long_press")
            if (c.contains(SemanticsActions.ScrollBy)) add("scroll")
            if (c.contains(SemanticsActions.SetText)) add("type")
          }
          val words = if (secret) "Secret field" else wordsOf(n)
          if (words != null || actions.isNotEmpty()) {
            nodes += Node("$w.${n.id}", root, n, box, words, roleOf(n), actions, confirm)
          }
          n.children.forEach { visit(it, confirm) }
        }
        runCatching { visit(owner.semanticsOwner.rootSemanticsNode, false) }
      }
    }
    return Seen(nodes, blank)
  }

  private fun boxOf(n: SemanticsNode): Box {
    val p = n.positionOnScreen
    return Box(p.x.toInt(), p.y.toInt(), p.x.toInt() + n.size.width, p.y.toInt() + n.size.height)
  }

  private fun wordsOf(n: SemanticsNode): String? {
    val c = n.config
    val parts = buildList {
      c.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
      c.getOrNull(SemanticsProperties.Text)?.let { t -> addAll(t.map(AnnotatedString::text)) }
      c.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf(String::isNotEmpty)?.let(::add)
    }
    return parts.map(String::trim).filter(String::isNotEmpty).distinct().joinToString(" ").take(200).ifEmpty { null }
  }

  /**
   * What a touched control is called, for the caption and the "What I did" card: its description, else its first words that
   * aren't a lone letter (a row's initial), never the whole row's text, which [wordsOf] keeps for Coach's tree.
   */
  private fun nameOf(n: SemanticsNode): String? {
    val c = n.config
    val described = c.getOrNull(SemanticsProperties.ContentDescription)?.map(String::trim)?.firstOrNull(String::isNotEmpty)
    val texts = c.getOrNull(SemanticsProperties.Text)?.map { it.text.trim() }.orEmpty().filter(String::isNotEmpty)
    return (described ?: texts.firstOrNull { it.length > 1 } ?: texts.firstOrNull() ?: wordsOf(n))?.take(60)
  }

  private fun roleOf(n: SemanticsNode): String {
    val c = n.config
    return when (c.getOrNull(SemanticsProperties.Role)) {
      Role.Button -> "button"
      Role.Tab -> "tab"
      Role.Switch -> "toggle"
      Role.Checkbox -> "checkbox"
      Role.RadioButton -> "radio"
      Role.Image -> "image"
      Role.DropdownList -> "menu"
      else -> when {
        c.contains(SemanticsActions.SetText) -> "text_field"
        c.contains(SemanticsActions.ScrollBy) -> "scroll"
        c.contains(SemanticsActions.OnClick) -> "button"
        else -> "text"
      }
    }
  }

  /** The Compose roots in a window: usually one. */
  private fun composeRoots(view: View): List<RootForTest> {
    val out = mutableListOf<RootForTest>()
    fun walk(v: View) {
      if (v is RootForTest) out += v
      if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(view)
    return out
  }

  private fun treeOf(seen: Seen, shot: Shot): JsonElement {
    lastIds = seen.nodes.associate { it.id to (it.root to it.node.id) }
    return buildJsonArray {
      seen.nodes.forEach { n ->
        val b = shot.toShot(n.box)
        add(buildJsonObject {
          put("id", n.id)
          n.words?.let { put("words", it) }
          put("role", n.role)
          put("bounds", buildJsonObject { put("x", b.left); put("y", b.top); put("w", b.width); put("h", b.height) })
          put("actions", buildJsonArray { n.actions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
          if (n.confirm) put("confirm", true)
        })
      }
    }
  }

  /**
   * The screenshot: each window drawn where it is, in its order, the dim a dialog casts under it, secret views and open
   * approvals blanked, scaled so the long side is at most 1,100 pixels, as a JPEG that fits 200,000 bytes.
   */
  private suspend fun capture(windows: List<Pair<View, Box>>, shot: Shot, blank: List<Box>): DriveImage {
    val full = Bitmap.createBitmap(shot.screenWidth, shot.screenHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(full)
    canvas.drawColor(android.graphics.Color.BLACK)
    val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
    for ((root, box) in windows) {
      val params = root.layoutParams as? WindowManager.LayoutParams
      if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
        canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
      }
      val bitmap = copy(root, windowOf(root)) ?: continue
      canvas.drawBitmap(bitmap, (box.left - origin[0]).toFloat(), (box.top - origin[1]).toFloat(), null)
      bitmap.recycle()
    }
    val fill = Paint().apply { color = android.graphics.Color.rgb(0x3A, 0x3A, 0x3C) }
    blank.forEach { b -> canvas.drawRect((b.left - origin[0]).toFloat(), (b.top - origin[1]).toFloat(), (b.right - origin[0]).toFloat(), (b.bottom - origin[1]).toFloat(), fill) }
    val scaled = if (shot.factor > 1.0) Bitmap.createScaledBitmap(full, shot.width, shot.height, true).also { full.recycle() } else full
    return withContext(Dispatchers.Default) {
      var quality = 82
      var bytes: ByteArray
      do {
        bytes = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
        quality -= 12
      } while (bytes.size > IMAGE_MAX && quality > 20)
      scaled.recycle()
      DriveImage("image/jpeg", Base64.getEncoder().encodeToString(bytes))
    }
  }

  private fun windowOf(root: View): Window? =
    if (root === activity.window.decorView) activity.window else DriveInput.windows.firstOrNull { it.root === root }?.window

  /** One window's pixels: `PixelCopy` from its surface where it has a `Window`, else drawn in software. */
  private suspend fun copy(root: View, window: Window?): Bitmap? {
    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val ok = suspendCancellableCoroutine { cont ->
        runCatching {
          PixelCopy.request(window, android.graphics.Rect(0, 0, root.width, root.height), bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, main)
        }.onFailure { cont.resume(false) }
      }
      if (ok) return bitmap
    }
    return runCatching { root.draw(Canvas(bitmap)); bitmap }.getOrElse { bitmap.recycle(); null }
  }

  private fun appInfo(): JsonObject {
    val config = activity.resources.configuration
    val info = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0) }.getOrNull()
    return buildJsonObject {
      put("version", info?.versionName ?: "")
      put("build", info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it).toString() } ?: "")
      put("platform", "android ${Build.VERSION.RELEASE}")
      put("orientation", if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
      put("size_class", if (config.screenWidthDp >= 600) "regular" else "compact")
      put("dark", config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
      put("font_scale", config.fontScale)
    }
  }

  // MARK: Acting

  override suspend fun act(action: Action): Acted = withContext(Dispatchers.Main.immediate) {
    if (DriveInput.isPaused()) return@withContext Acted.refused(DriveWords.PAUSED)
    val acted = when (action) {
      is Action.Tap -> touch(action.target, longPress = false)
      is Action.LongPress -> touch(action.target, longPress = true)
      is Action.Swipe -> swipe(action)
      is Action.Scroll -> scroll(action)
      is Action.Type -> type(action.text)
      is Action.Back -> if (DriveInput.back()) Acted(true, DriveWords.WENT_BACK) else Acted.refused(DriveWords.NOTHING_BACK)
      is Action.Done -> Acted(true, DriveWords.DONE)
    }
    // The app settles before the screen is looked at again; the keyboard stays down (Coach types through the field). Paused
    // meanwhile, the keyboard is the person's: a field they tapped keeps it.
    delay(SETTLE_MS)
    if (!DriveInput.isPaused()) hideKeyboard()
    acted
  }

  override fun putKeyboardAway() {
    main.post {
      // The field keeps its text and loses focus, so nothing it commits after Coach's step reads as the person's typing.
      windows().forEach { (root, _) -> root.findFocus()?.clearFocus() }
      hideKeyboard()
    }
  }

  /** Whether a screen point is on a control that acts: a button, a row, a toggle, a link, a tab. A field or empty space isn't. */
  fun controlAt(x: Int, y: Int): Boolean {
    val (root, _, _) = topmostAt(windows(), x, y) ?: return false
    var found = false
    for (owner in composeRoots(root)) {
      fun visit(n: SemanticsNode) {
        if (found) return
        if (!boxOf(n).contains(x, y)) { n.children.forEach(::visit); return }
        val c = n.config
        if (c.contains(SemanticsActions.OnClick) || c.contains(SemanticsActions.OnLongClick)) found = true
        n.children.forEach(::visit)
      }
      runCatching { visit(owner.semanticsOwner.rootSemanticsNode) }
    }
    return found
  }

  /** A point Coach named: the screen's, from the last screenshot's pixels or an element's middle; null when it's gone. */
  private fun screenPoint(target: Target): Pair<Int, Int>? = when (target) {
    is Target.At -> shot().toScreen(target.x, target.y)
    is Target.Element -> node(target.id)?.let { boxOf(it).let { b -> b.centerX to b.centerY } }
  }

  private fun node(id: String): SemanticsNode? {
    val (root, nodeId) = lastIds[id] ?: return null
    if (!root.isAttachedToWindow) return null
    for (owner in composeRoots(root)) {
      fun find(n: SemanticsNode): SemanticsNode? = if (n.id == nodeId) n else n.children.firstNotNullOfOrNull(::find)
      runCatching { find(owner.semanticsOwner.rootSemanticsNode) }.getOrNull()?.let { return it }
    }
    return null
  }

  /**
   * What's under a screen point, in the window a touch there goes to: the confirm mark on it or on anything around it, and the
   * words of the control it lands on.
   */
  private class Under(val root: View, val box: Box, val confirm: Boolean, val words: String?)

  private fun under(x: Int, y: Int): Under? {
    val (root, _, _) = topmostAt(windows(), x, y) ?: return null
    val box = windows().first { it.first === root }.second
    var confirm = false
    var words: String? = null
    var smallest = Int.MAX_VALUE
    for (owner in composeRoots(root)) {
      fun visit(n: SemanticsNode) {
        val b = boxOf(n)
        if (!b.contains(x, y)) { n.children.forEach(::visit); return }
        val c = n.config
        if (c.getOrNull(DriveConfirms) == true) confirm = true
        if (c.contains(SemanticsActions.OnClick) || c.contains(SemanticsActions.OnLongClick) || c.contains(SemanticsActions.SetText)) {
          val area = b.width * b.height
          if (area < smallest) { smallest = area; words = if (c.getOrNull(DriveSecret) == true) Words.Drive.aSecretField else nameOf(n) }
        }
        n.children.forEach(::visit)
      }
      runCatching { visit(owner.semanticsOwner.rootSemanticsNode) }
    }
    return Under(root, box, confirm, words)
  }

  private suspend fun touch(target: Target, longPress: Boolean): Acted {
    val (x, y) = screenPoint(target) ?: return Acted.refused(DriveWords.NOT_ON_SCREEN)
    val under = under(x, y) ?: return Acted.refused(DriveWords.NOTHING_THERE)
    // Hit-tested before anything is dispatched: a touch on a confirming control is refused, and nothing reaches it.
    if (under.confirm) return Acted.refused(DriveWords.CONFIRM)
    overlay.dot(x, y)
    delay(DOT_MS)
    // Paused while the dot showed: nothing was dispatched, so the step is refused.
    if (DriveInput.isPaused()) return Acted.refused(DriveWords.PAUSED)
    val hold = if (longPress) ViewConfiguration.getLongPressTimeout() + 150L else 60L
    gesture(under.root, under.box, listOf(x to y), hold)
    val touched = under.words?.let { Touched(it, currentTaf()) }
    return Acted(true, if (longPress) DriveWords.longPressed(under.words) else DriveWords.tapped(under.words), touched)
  }

  private suspend fun swipe(action: Action.Swipe): Acted {
    val (x, y) = screenPoint(action.from) ?: return Acted.refused(DriveWords.NOT_ON_SCREEN)
    val (tx, ty) = shot().toScreen(action.to.x, action.to.y)
    val under = under(x, y) ?: return Acted.refused(DriveWords.NOTHING_THERE)
    if (under.confirm) return Acted.refused(DriveWords.CONFIRM)
    overlay.dot(x, y)
    delay(DOT_MS)
    if (DriveInput.isPaused()) return Acted.refused(DriveWords.PAUSED)
    val steps = (action.ms / FRAME_MS).toInt().coerceAtLeast(2)
    val path = (0..steps).map { i -> (x + (tx - x) * i / steps) to (y + (ty - y) * i / steps) }
    gesture(under.root, under.box, path, FRAME_MS)
    return Acted(true, DriveWords.SWIPED)
  }

  /**
   * One gesture, down to up, dispatched to [root] alone: the window chosen at the down gets the rest of it, as a person's finger
   * would. Cut short (the drive ended, or the person paused it), the window is told the gesture was cancelled before its next
   * event.
   */
  private suspend fun gesture(root: View, box: Box, path: List<Pair<Int, Int>>, stepMs: Long) {
    val down = SystemClock.uptimeMillis()
    fun event(action: Int, p: Pair<Int, Int>): MotionEvent =
      MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.first.toFloat(), p.second.toFloat(), 0).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
        offsetLocation(-box.left.toFloat(), -box.top.toFloat())
      }
    fun send(e: MotionEvent) {
      DriveInput.dispatch(root, e)
      e.recycle()
    }
    var finished = false
    try {
      send(event(MotionEvent.ACTION_DOWN, path.first()))
      if (path.size == 1) delay(stepMs)
      for (p in path.drop(1)) {
        delay(stepMs)
        if (DriveInput.isPaused()) return
        send(event(MotionEvent.ACTION_MOVE, p))
      }
      if (DriveInput.isPaused()) return
      send(event(MotionEvent.ACTION_UP, path.last()))
      finished = true
    } finally {
      if (!finished) withContext(NonCancellable) { send(event(MotionEvent.ACTION_CANCEL, path.last())) }
    }
  }

  private fun scroll(action: Action.Scroll): Acted {
    val target = action.element?.let { id -> node(id) ?: return Acted.refused(DriveWords.NOT_ON_SCREEN) }
    // The element's own scroll, or the one around it; with none named, the largest scroll in the topmost window.
    val scroller = generateSequence(target) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
      ?: if (target == null) largestScroller() else null
    val node = scroller ?: return Acted.refused(DriveWords.cantScroll(action.direction))
    val box = boxOf(node)
    val (dx, dy) = when (action.direction) {
      "down" -> 0f to box.height * 0.8f
      "up" -> 0f to -box.height * 0.8f
      "right" -> box.width * 0.8f to 0f
      else -> -box.width * 0.8f to 0f
    }
    val vertical = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
    val horizontal = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
    val can = when (action.direction) {
      "down" -> vertical?.let { it.value() < it.maxValue() } ?: true
      "up" -> vertical?.let { it.value() > 0f } ?: true
      "right" -> horizontal?.let { it.value() < it.maxValue() } ?: true
      else -> horizontal?.let { it.value() > 0f } ?: true
    }
    if (!can) return Acted.refused(DriveWords.cantScroll(action.direction))
    val did = DriveInput.asCoach { node.config[SemanticsActions.ScrollBy].action?.invoke(dx, dy) == true }
    return if (did) Acted(true, DriveWords.scrolled(action.direction)) else Acted.refused(DriveWords.cantScroll(action.direction))
  }

  private fun largestScroller(): SemanticsNode? {
    val (root, _) = windows().lastOrNull() ?: return null
    var best: SemanticsNode? = null
    var area = 0
    for (owner in composeRoots(root)) {
      fun visit(n: SemanticsNode) {
        if (n.config.contains(SemanticsActions.ScrollBy)) {
          val b = boxOf(n)
          if (b.width * b.height > area) { area = b.width * b.height; best = n }
        }
        n.children.forEach(::visit)
      }
      runCatching { visit(owner.semanticsOwner.rootSemanticsNode) }
    }
    return best
  }

  /** Text into the focused field, through the field itself (the path its own semantics give), never the keyboard. */
  private fun type(text: String): Acted {
    var field: Pair<SemanticsNode, Boolean>? = null
    for ((root, _) in windows().asReversed()) {
      for (owner in composeRoots(root)) {
        // The mark may sit on the field's own node or on one around it: either way the field is secret.
        fun find(n: SemanticsNode, secretAround: Boolean): Pair<SemanticsNode, Boolean>? {
          val secret = secretAround || n.config.getOrNull(DriveSecret) == true || n.config.contains(SemanticsProperties.Password)
          return if (n.config.getOrNull(SemanticsProperties.Focused) == true && n.config.contains(SemanticsActions.SetText)) n to secret
          else n.children.firstNotNullOfOrNull { find(it, secret) }
        }
        field = runCatching { find(owner.semanticsOwner.unmergedRootSemanticsNode, false) }.getOrNull()
        if (field != null) break
      }
      if (field != null) break
    }
    val (f, secret) = field ?: return Acted.refused(DriveWords.NO_FIELD)
    // A secret field is the person's to fill (docs/drive.md, "Coach never confirms"): nothing is typed into it.
    if (secret) return Acted.refused(DriveWords.SECRET_FIELD)
    val c = f.config
    val did = DriveInput.asCoach {
      c.getOrNull(SemanticsActions.InsertTextAtCursor)?.action?.invoke(AnnotatedString(text))
        ?: c.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString((c.getOrNull(SemanticsProperties.EditableText)?.text ?: "") + text))
        ?: false
    }
    if (!did) return Acted.refused(DriveWords.NO_FIELD)
    val label = (c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: c.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text)
      ?.takeIf(String::isNotBlank)
    return Acted(true, DriveWords.typed(label), label?.let { Touched(it, currentTaf()) } ?: Touched(Words.Drive.aTextField, currentTaf()))
  }

  private fun hideKeyboard() {
    val imm = activity.getSystemService(InputMethodManager::class.java) ?: return
    windows().forEach { (root, _) -> imm.hideSoftInputFromWindow(root.windowToken, 0) }
  }

  companion object {
    const val IMAGE_MAX = 200_000
    /** How long the dot shows before the touch it marks. */
    const val DOT_MS = 350L
    /** How long the app gets to settle after a step before it's looked at. */
    const val SETTLE_MS = 400L
    const val FRAME_MS = 16L
  }
}
