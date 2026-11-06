package bot.tafs.app.drive

/** A rectangle in whole pixels: left and top included, right and bottom not. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top

  fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

  val centerX: Int get() = left + width / 2
  val centerY: Int get() = top + height / 2
}

/**
 * The screenshot's size against the screen's (docs/drive.md, "Coordinates"): the screenshot is scaled down so its long side is at
 * most [LONG_SIDE], and a point in it becomes the screen's by multiplying by `screen pixels / screenshot pixels`.
 */
data class Shot(val screenWidth: Int, val screenHeight: Int) {
  /** Screen pixels per screenshot pixel: 1 when the screen already fits. */
  val factor: Double = maxOf(1.0, maxOf(screenWidth, screenHeight).toDouble() / LONG_SIDE)

  val width: Int = Math.round(screenWidth / factor).toInt()
  val height: Int = Math.round(screenHeight / factor).toInt()

  /** A point in the screenshot's pixels, on the screen. */
  fun toScreen(x: Int, y: Int): Pair<Int, Int> = Math.round(x * factor).toInt() to Math.round(y * factor).toInt()

  /** A box on the screen, in the screenshot's pixels: what the tree's bounds say. */
  fun toShot(b: Box): Box = Box(
    Math.round(b.left / factor).toInt(), Math.round(b.top / factor).toInt(),
    Math.round(b.right / factor).toInt(), Math.round(b.bottom / factor).toInt(),
  )

  companion object {
    const val LONG_SIDE = 1_100
  }
}

/**
 * Which of the app's windows a touch at a screen point goes to: the topmost that contains it (a sheet or a dialog over the
 * screen), with the point made relative to that window. [windows] are on screen, oldest (lowest) first; null when none
 * contains the point.
 */
fun <W> topmostAt(windows: List<Pair<W, Box>>, x: Int, y: Int): Triple<W, Int, Int>? =
  windows.lastOrNull { (_, b) -> b.contains(x, y) }?.let { (w, b) -> Triple(w, x - b.left, y - b.top) }
