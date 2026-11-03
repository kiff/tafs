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

  @Test fun aRingPastSixStopsShowsTheSixThatHoldTheChosenOne() {
    assertEquals("nothing chosen: the first page", 0, DialMath.page(-1))
    assertEquals(0, DialMath.page(5))
    assertEquals(1, DialMath.page(6))
    assertEquals(1, DialMath.page(11))
  }

  @Test fun theChosenStopTurnsUnderThePointer() {
    assertEquals("the first stop is already there", 0.0, DialMath.ringTurn(at = 0, shown = 4), 1e-9)
    assertEquals(-90.0, DialMath.ringTurn(at = 1, shown = 4), 1e-9)
    assertEquals(-300.0, DialMath.ringTurn(at = 5, shown = 6), 1e-9)
    assertEquals("and the short way round from where it stands", 60.0, DialMath.delta(from = 0.0, to = -300.0), 1e-9)
  }

  @Test fun aRingsLabelAlwaysReadsUpright() {
    assertEquals("the top reads as it is", 0.0, DialMath.upright(0.0), 1e-9)
    assertEquals("the right edge stays along the ring", 90.0, DialMath.upright(90.0), 1e-9)
    assertEquals("the bottom turns right way up", 360.0, DialMath.upright(180.0), 1e-9)
    assertEquals(300.0, DialMath.upright(120.0), 1e-9)
    assertEquals("the left edge stays along the ring", 270.0, DialMath.upright(270.0), 1e-9)
    assertEquals(315.0, DialMath.upright(315.0), 1e-9)
  }
}
