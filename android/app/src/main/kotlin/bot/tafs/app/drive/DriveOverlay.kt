package bot.tafs.app.drive

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What the person sees while Coach drives (docs/drive.md): the whole app's border glowing, a one-line caption above the tab bar
 * with Stop, and a dot where Coach is about to touch. Each is a window of its own, so the capture and Coach's touches leave them
 * out. The glow and the dot take no touch; the caption takes only its own, and its Stop ends the drive as `stopped`.
 */
class DriveOverlay(private val activity: Activity, private val accent: Int, private val stop: () -> Unit) {
  private val wm = activity.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private var glow: Glow? = null
  private var caption: LinearLayout? = null
  private var captionText: TextView? = null
  private var stopButton: TextView? = null

  fun show(state: DriveState) = main.post {
    when (state) {
      is DriveState.On -> {
        if (glow == null) addGlow()
        if (caption == null) addCaption()
        captionText?.text = state.caption ?: "Coach is driving: ${state.drive.task}".take(60)
        stopButton?.visibility = View.VISIBLE
        raise()
      }
      is DriveState.Over -> {
        // At once: the border and the dot go. The caption says why it's over, for a moment, then goes too.
        glow?.let(::remove)
        glow = null
        if (caption == null) addCaption()
        captionText?.text = state.words.take(80)
        stopButton?.visibility = View.GONE
        main.postDelayed({ if (glow == null) { caption?.let(::remove); caption = null } }, OVER_MS)
      }
      DriveState.Off -> {
        glow?.let(::remove)
        glow = null
        caption?.let(::remove)
        caption = null
      }
    }
  }.let { }

  /** The dot at a screen point, a moment before Coach touches there. */
  fun dot(x: Int, y: Int) = main.post { glow?.dot(x, y) }.let { }

  /**
   * A dialog or a sheet opened after the overlay: it's put back on top, so the border and the caption stay in sight. They take
   * no touch, so for the moment they're under a new window nothing is missed.
   */
  fun raise() = main.post {
    val roots = runCatching { android.view.inspector.WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList())
    val top = roots.lastOrNull { !isOverlay(it) }
    val ours = listOfNotNull(glow, caption)
    if (top != null && ours.isNotEmpty() && roots.indexOf(top) > ours.maxOf { roots.indexOf(it) }) {
      glow?.let { remove(it); wm.addView(it, it.params) }
      caption?.let { val p = it.layoutParams; remove(it); wm.addView(it, p) }
    }
  }.let { }

  private fun params(touchable: Boolean) = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    if (touchable) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.TYPE_APPLICATION,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      (if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
    PixelFormat.TRANSLUCENT,
  ).apply {
    token = activity.window.decorView.windowToken
    title = TITLE
    if (touchable) {
      gravity = Gravity.BOTTOM
      y = dp(88)
    }
  }

  private fun addGlow() {
    val g = Glow(activity, accent).apply { tag = TAG; params = params(touchable = false) }
    runCatching { wm.addView(g, g.params) }.onSuccess { glow = g; g.start() }
  }

  private fun addCaption() {
    val line = TextView(activity).apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      maxLines = 1
      ellipsize = android.text.TextUtils.TruncateAt.END
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val stopView = TextView(activity).apply {
      text = "Stop"
      setTextColor(accent)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setPadding(dp(14), dp(6), dp(4), dp(6))
      contentDescription = "Stop Coach"
      setOnClickListener { stop() }
    }
    val bar = LinearLayout(activity).apply {
      tag = TAG
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16), dp(8), dp(12), dp(8))
      background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(0xE6, 0x1F, 0x1E, 0x1B)) }
      addView(line)
      addView(stopView)
    }
    val p = params(touchable = true).apply {
      width = WindowManager.LayoutParams.MATCH_PARENT
      horizontalMargin = 0.04f
    }
    runCatching { wm.addView(bar, p) }.onSuccess { caption = bar; captionText = line; stopButton = stopView }
  }

  private fun remove(v: View) {
    runCatching { wm.removeViewImmediate(v) }
  }

  private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

  /** The border's slow pulse in the app's accent, and the dot. */
  private class Glow(context: Context, private val accent: Int) : View(context) {
    lateinit var params: WindowManager.LayoutParams
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private var pulse = 1f
    private var dotAt: Pair<Float, Float>? = null
    private val animator = ValueAnimator.ofFloat(0.45f, 1f).apply {
      duration = 1_600
      repeatMode = ValueAnimator.REVERSE
      repeatCount = ValueAnimator.INFINITE
      addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    fun start() = animator.start()

    fun dot(x: Int, y: Int) {
      val at = IntArray(2).also(::getLocationOnScreen)
      dotAt = (x - at[0]).toFloat() to (y - at[1]).toFloat()
      invalidate()
      postDelayed({ dotAt = null; invalidate() }, 700)
    }

    override fun onDetachedFromWindow() {
      animator.cancel()
      super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
      val w = 4f * resources.displayMetrics.density
      border.strokeWidth = w
      border.alpha = (pulse * 255).toInt()
      canvas.drawRect(w / 2, w / 2, width - w / 2, height - w / 2, border)
      dotAt?.let { (x, y) ->
        val r = 14f * resources.displayMetrics.density
        fill.alpha = 0x66
        canvas.drawCircle(x, y, r, fill)
        fill.alpha = 0xFF
        canvas.drawCircle(x, y, r / 3, fill)
      }
    }
  }

  companion object {
    private const val TAG = "tafs.drive.overlay"
    private const val TITLE = "tafs drive overlay"
    private const val OVER_MS = 3_000L

    /** One of the overlay's windows: never captured, never touched by Coach. */
    fun isOverlay(root: View): Boolean = root.tag == TAG || (root.layoutParams as? WindowManager.LayoutParams)?.title == TITLE
  }
}
