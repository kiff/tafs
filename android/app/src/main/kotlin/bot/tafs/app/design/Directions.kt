package bot.tafs.app.design

/**
 * Where something drawn by hand, or moved by a drag, goes in either direction (docs/android-app.md, "Right to left"). Compose
 * mirrors a layout itself, but not a `drawBehind`'s coordinates, a slide's pixels or a drag's delta: those are always measured
 * from the left. These turn "the start" and "the end" into pixels, so a right-to-left screen gets the mirror of a left-to-right one.
 */
object Directions {
  /** Where a bar [bar] wide starts, to sit on the start edge of something [width] wide: the left, or the right in right to left. */
  fun startEdgeX(width: Float, bar: Float, rtl: Boolean): Float = if (rtl) width - bar else 0f

  /** A point [inset] in from the end edge of something [width] wide: from the right, or from the left in right to left. */
  fun fromEndX(width: Float, inset: Float, rtl: Boolean): Float = if (rtl) inset else width - inset

  /** A horizontal drag's pixels towards the end edge: rightwards left to right, leftwards right to left. */
  fun towardsEnd(dx: Float, rtl: Boolean): Float = if (rtl) -dx else dx

  /** Where something [width] wide starts off screen to slide in from the end edge, or ends to slide out by it. */
  fun offEndX(width: Int, rtl: Boolean): Int = if (rtl) -width else width
}
