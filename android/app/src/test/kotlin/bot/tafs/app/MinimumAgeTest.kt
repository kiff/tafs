package bot.tafs.app

import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.model.MinimumAge
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.model.ageLine
import bot.tafs.app.model.raisedAgeLine
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The welcome's age line says the person's country's age (docs/onboarding.md), from the one table (ages.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MinimumAgeTest {
  @Before fun words() = Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))

  @Test fun theTableIsLegalI18ns() {
    assertEquals(16, MinimumAge.STANDARD)
    assertEquals(18, MinimumAge.of("IN"))
    assertEquals(18, MinimumAge.of("br"))
    assertEquals(16, MinimumAge.of("FR"))
    assertEquals("none known is the default", 16, MinimumAge.of(null))
  }

  @Test fun aCountryThatAsksMoreSaysItsAge() {
    assertEquals(Words.Onboarding.ageN(age = "18"), ageLine("IN"))
    assertEquals(Words.Onboarding.ageN(age = "18"), ageLine("ID"))
  }

  @Test fun everyOtherCountryReadsTheLineItAlwaysHas() {
    assertEquals(Words.Onboarding.age, ageLine("DE"))
    assertEquals(Words.Onboarding.age, ageLine("KR"))
    assertEquals(Words.Onboarding.age, ageLine(null))
  }

  /** Today's sign-in says a raised age only: India and Brazil before the switch; every other country sees the sheet as it was. */
  @Test fun todaysSignInSaysARaisedAgeOnly() {
    assertEquals(Words.Onboarding.ageN(age = "18"), raisedAgeLine("IN"))
    assertEquals(Words.Onboarding.ageN(age = "18"), raisedAgeLine("BR"))
    assertNull(raisedAgeLine("FR"))
    assertNull(raisedAgeLine(null))
  }
}
