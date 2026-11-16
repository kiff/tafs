package bot.tafs.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import bot.tafs.app.drive.KeepUpwardLeftovers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A sheet's list keeps what it doesn't use going up past its end, so a fast fling to the end of Account doesn't set the sheet
 * bouncing (Android 111). What it doesn't use going down still reaches the sheet, so pulling down at the top closes it.
 */
class KeepUpwardLeftoversTest {
  private val keep = KeepUpwardLeftovers()

  @Test fun aFlingThatReachesTheEndKeepsItsLeftoverSpeed() = runTest {
    // Fast: the list ran out with speed to spare. The sheet would have taken it and settled with an overshoot.
    assertEquals(Velocity(0f, -4200f), keep.onPostFling(Velocity(0f, -1800f), Velocity(0f, -4200f)))
  }

  @Test fun aSlowScrollHasNothingLeftAndNothingIsKept() = runTest {
    assertEquals(Velocity.Zero, keep.onPostFling(Velocity(0f, -300f), Velocity.Zero))
  }

  @Test fun aDragUpPastTheEndKeepsItsLeftover() {
    assertEquals(Offset(0f, -40f), keep.onPostScroll(Offset(0f, -10f), Offset(0f, -40f), NestedScrollSource.UserInput))
  }

  @Test fun aPullDownAtTheTopStillReachesTheSheetToCloseIt() = runTest {
    assertEquals(Offset.Zero, keep.onPostScroll(Offset.Zero, Offset(0f, 60f), NestedScrollSource.UserInput))
    assertEquals(Velocity.Zero, keep.onPostFling(Velocity.Zero, Velocity(0f, 2500f)))
  }

  @Test fun sidewaysIsNeverKept() = runTest {
    assertEquals(Offset.Zero, keep.onPostScroll(Offset.Zero, Offset(-30f, 0f), NestedScrollSource.UserInput))
    assertEquals(Velocity.Zero, keep.onPostFling(Velocity.Zero, Velocity(-900f, 0f)))
  }
}
