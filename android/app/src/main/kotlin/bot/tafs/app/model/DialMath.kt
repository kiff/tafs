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
}
