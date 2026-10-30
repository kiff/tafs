package bot.tafs.app

import bot.tafs.app.model.DialMath
import org.junit.Assert.assertEquals
import org.junit.Test

/** The door dial's geometry, as iOS's DialMath tests it. */
class DialMathTest {
  @Test fun aStopIsAtTheTopWhenTheDialIsTurnedToIt() {
    assertEquals(-90.0, DialMath.angle(index = 2, stops = 4, rotation = 180.0), 1e-9)
    assertEquals(0.0, DialMath.angle(index = 1, stops = 4, rotation = 0.0), 1e-9)
  }

  @Test fun theTurnPicksTheNearestStopAndWrapsBothWays() {
    assertEquals(1, DialMath.stop(rotation = 100.0, stops = 4))
    assertEquals(0, DialMath.stop(rotation = 360.0, stops = 4))
    assertEquals(3, DialMath.stop(rotation = -90.0, stops = 4))
  }

  @Test fun aDragTurnsTheShortWayRound() {
    assertEquals(20.0, DialMath.delta(from = 170.0, to = -170.0), 1e-9)
    assertEquals(-20.0, DialMath.delta(from = -170.0, to = 170.0), 1e-9)
  }

  @Test fun aTapTurnsTheShortWayToItsStop() {
    assertEquals(-1, DialMath.shortest(from = 0, to = 3, stops = 4))
    assertEquals(1, DialMath.shortest(from = 3, to = 0, stops = 4))
    assertEquals(2, DialMath.shortest(from = 5, to = 3, stops = 4))
  }
}
