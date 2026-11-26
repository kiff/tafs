package bot.tafs.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Words
import bot.tafs.app.views.packsExplainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Plan & usage's line above the packs (the owner's item): what packs are for, only while some can be bought here. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class PacksExplainerTest {
  @Before fun words() = bot.tafs.app.model.Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))

  private val store = KiffClient.json.decodeFromString(
    bot.tafs.app.kiff.UsageStore.serializer(),
    """{"packs":[{"id":"credits-500","name":"500 credits","google_product":"bot.tafs.credits.500"},{"id":"credits-firstmonth","name":"First month pack","once":true,"google_product":"bot.tafs.credits.firstmonth"}]}""",
  )

  @Test fun thePacksSayWhatTheyreForWhileOneCanBeBought() {
    assertEquals(Words.Billing.packAddsCreditsOnceNever, packsExplainer(true, store, setOf("bot.tafs.credits.500")))
  }

  @Test fun noLineOverAnEmptyList() {
    assertNull("packs not offered (not on Solo)", packsExplainer(false, store, setOf("bot.tafs.credits.500")))
    assertNull("Play lists none of the packs", packsExplainer(true, store, emptySet()))
    // The First month pack has its own row: alone, it isn't "the packs".
    assertNull(packsExplainer(true, store, setOf("bot.tafs.credits.firstmonth")))
  }
}
