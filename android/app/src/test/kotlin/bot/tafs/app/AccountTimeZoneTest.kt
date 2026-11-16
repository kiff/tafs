package bot.tafs.app

import bot.tafs.app.model.Words
import bot.tafs.app.views.offersDeviceZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Account's time zone, as on the iPad: the phone's zone is offered only when it would change something, and the footer says why. */
class AccountTimeZoneTest {
  @Test fun thePhonesZoneIsOfferedOnlyWhenTheAccountIsInAnother() {
    assertTrue(offersDeviceZone("America/New_York", "Europe/Paris"))
    assertFalse(offersDeviceZone("Europe/Paris", "Europe/Paris"))
  }

  @Test fun anAccountWithNoZoneIsUtcAsTheRowShowsIt() {
    assertFalse(offersDeviceZone(null, "UTC"))
    assertTrue(offersDeviceZone(null, "Europe/Paris"))
  }

  @Test fun theFooterSaysWhatTheZoneIsFor() {
    assertEquals("Holds' deadlines, Activity, the Record and what your tafs write use this time zone.", Words.Settings.TimeZone.usesThis)
  }
}
