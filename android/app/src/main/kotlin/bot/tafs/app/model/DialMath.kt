package bot.tafs.app.model

import kotlin.math.roundToInt

/** The door dial's geometry, apart from drawing it, so it can be tested (as iOS's DialMath). */
object DialMath {
  fun step(stops: Int): Double = 360.0 / maxOf(stops, 1)

  /** Stop [index]'s angle on screen, in degrees (0 is 3 o'clock, clockwise): the top when the dial is turned to it. */
  fun angle(index: Int, stops: Int, rotation: Double): Double = -90 + index * step(stops) - rotation

  /** The stop at the top for a turn. */
  fun stop(rotation: Double, stops: Int): Int {
    val n = (rotation / step(stops)).roundToInt()
    return ((n % stops) + stops) % stops
  }

  /** The turn between two angles, the short way round, in -180..180. */
  fun delta(from: Double, to: Double): Double {
    var d = (to - from) % 360
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
  }

  /** Steps from stop [from] (any turn) to stop [to], the short way round. */
  fun shortest(from: Int, to: Int, stops: Int): Int {
    var d = ((to - from) % stops + stops) % stops
    if (d > stops / 2.0) d -= stops
    return d
  }

  /**
   * How far a ring's label turns at [fromTop] degrees clockwise from the top, so it always reads upright:
   * along the ring, and half a turn more in the lower half (past 90° and before 270°).
   */
  fun upright(fromTop: Double): Double {
    val at = ((fromTop % 360) + 360) % 360
    return if (at > 90 && at < 270) at + 180 else at
  }

  /** A ring shows at most this many stops at once; past it, it pages. */
  const val PAGE = 6

  /** The page of a ring that holds its [index]th stop (the first page when none is chosen). */
  fun page(index: Int): Int = if (index < 0) 0 else index / PAGE

  /** The ring's turn, in degrees, that puts the [at]th of its [shown] stops under the pointer at the top. */
  fun ringTurn(at: Int, shown: Int): Double = if (at <= 0 || shown <= 0) 0.0 else -at * 360.0 / shown
}
