package bot.tafs.app

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The app's words follow the language the person picked for tafs (Settings › Apps › tafs › Language), not the phone's, a
 * plural included (docs/languages.md): once, with the app in German, Home's "0 tafs working." stayed English.
 */
class AppLanguageTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private var before: LocaleList? = null

  @Before fun german() {
    // An app's own language is Android 13's.
    assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    val locales = context.getSystemService(LocaleManager::class.java)
    before = locales.applicationLocales
    // Picked while the app's process already runs, as from Settings while tafs waits in the background.
    InstrumentationRegistry.getInstrumentation().runOnMainSync { locales.applicationLocales = LocaleList.forLanguageTags("de-DE") }
  }

  @After fun back() {
    before?.let { b -> InstrumentationRegistry.getInstrumentation().runOnMainSync { context.getSystemService(LocaleManager::class.java).applicationLocales = b } }
  }

  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

  @Test fun theWordsAndThePluralsAreInTheAppsOwnLanguage() {
    rule.waitForIdle()
    assertEquals("de", Res.language)
    assertEquals("3 tafs arbeiten.", Words.Home.working(count = 3))
    assertEquals("1 taf arbeitet.", Words.Home.working(count = 1))
  }
}
