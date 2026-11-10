package bot.tafs.app

import bot.tafs.app.model.ThisDevice
import bot.tafs.app.model.Words
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The lines that name this device say what it is: a phone, or a tablet on a large screen (docs/android-app.md). */
class ThisDeviceTest {
  @After fun phoneAgain() {
    ThisDevice.tablet = false
  }

  @Test fun aPhoneIsCalledAPhone() {
    assertEquals("Alerts on this phone", Words.Alert.onThisDevice(ThisDevice.word))
    assertEquals("Use this phone's: Europe/Paris", Words.Settings.useDeviceZone(ThisDevice.word, "Europe/Paris"))
  }

  @Test fun aTabletIsCalledATablet() {
    ThisDevice.tablet = true
    assertEquals("Alerts on this tablet", Words.Alert.onThisDevice(ThisDevice.word))
    assertEquals("Signed on this tablet", Words.Record.signedOnDevice(ThisDevice.word))
    assertEquals("Use this tablet's: Europe/Paris", Words.Settings.useDeviceZone(ThisDevice.word, "Europe/Paris"))
  }
}
