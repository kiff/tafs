package bot.tafs.app

import bot.tafs.app.design.Haptics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The app's taps (docs/android-app.md, "Haptics"): each kind goes to the phone, and Haptics off in Settings turns all of them off. */
class HapticsTest {
  private val felt = mutableListOf<Haptics.Kind>()

  @Before fun listen() {
    Haptics.performer = { felt += it }
    Haptics.setEnabledForTest(true)
  }

  @After fun reset() {
    Haptics.performer = null
    Haptics.setEnabledForTest(true)
  }

  @Test fun eachKindIsFeltOnceWhileHapticsIsOn() {
    Haptics.tap(); Haptics.success(); Haptics.warning(); Haptics.step()
    assertEquals(listOf(Haptics.Kind.Tap, Haptics.Kind.Success, Haptics.Kind.Warning, Haptics.Kind.Step), felt)
  }

  @Test fun hapticsOffTurnsEverythingOff() {
    Haptics.setEnabledForTest(false)
    Haptics.tap(); Haptics.success(); Haptics.warning(); Haptics.step()
    assertEquals(emptyList<Haptics.Kind>(), felt)
  }

  @Test fun aPhoneThatCantTapNeverStopsTheApp() {
    Haptics.performer = { error("no vibrator") }
    Haptics.tap()
  }
}
