package bot.tafs.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The watch's Play store screenshots (docs/watch-wear.md, Versions): the real screen, drawn with the watch's own words in each of
 * the 8 languages and the phone app's sample tafs (app/strings/<lang>/sample.json), never a mock-up and nothing drawn over it.
 * Round, 384 × 384 px (192 dp at xhdpi), Play's smallest Wear OS size. Drawn into android/play/wear-screenshots/<locale>/ only
 * when asked (`./gradlew :wear:testDebugUnitTest -PwearScreenshots`); otherwise each screen is still drawn, so one that no longer
 * draws fails here.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = WatchApplication::class)
class WearStoreScreenshots(private val language: String, private val playLocale: String) {
  @get:Rule val compose = createComposeRule()

  private val repo = File(System.getProperty("tafs.repo") ?: "../..")

  /** The phone's words for [language], from app/strings, where the sample tafs and their states live. */
  private fun phoneWords(file: String): JSONObject = JSONObject(File(repo, "app/strings/$language/$file.json").readText())

  private val sample by lazy { phoneWords("sample") }
  private val taf by lazy { phoneWords("taf") }

  private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
    RuntimeEnvironment.setQualifiers("$language-w192dp-h192dp-round-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
    // The watch's window is black (Theme.DeviceDefault on Wear OS); the JVM has no window, so it's drawn here as the watch shows it.
    compose.setContent { WatchTheme { Box(Modifier.fillMaxSize().background(Color.Black)) { content() } } }
    val out = File(repo, "android/play/wear-screenshots/$playLocale/$name.png")
    compose.onRoot().captureRoboImage(out.path)
    opaqueForPlay(out)
    // Drawn in the watch's own words: the screen's language is the one asked for.
    assertTrue(Res.language, Res.language == language)
  }

  private val actions = Actions.none

  /** An ask a sample taf finished: its taf and a line of the phone's sample words, as tafs cloud would send one. */
  private fun finished() = bot.tafs.watchlink.AskLine(
    "wa_1", "w_00000001", bot.tafs.watchlink.AskLine.DONE, sample.getString("sample.bill_pay.name"),
    sample.getString("sample.brief.case.bills_line"), Res.s(R.string.common_done), "2026-11-16T19:01:00Z",
  )

  /** An ask at work, with its taf and tafs cloud's word for it. */
  private fun working() = bot.tafs.watchlink.AskLine(
    "wa_2", "w_00000002", bot.tafs.watchlink.AskLine.WORKING, sample.getString("sample.refunds.name"), null, taf.getString("taf.state.working"),
    "2026-11-16T19:02:00Z",
  )

  /**
   * A whole amount in euros, written as the watch shows it: the phone sends `Money.formatted`, which writes a whole amount with no
   * cents in the app's language ("€184", "184 €"), and the watch draws that string as it comes.
   */
  private fun euros(amount: Int): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag(language)).apply {
    currency = Currency.getInstance("EUR")
    minimumFractionDigits = 0
    maximumFractionDigits = 0
  }.format(amount)

  private fun read(): WatchModel.Shown.Read {
    val refund = HeldLine(
      holdId = "h1",
      taf = sample.getString("sample.refunds.name"),
      wants = sample.getString("sample.refunds.refund_4471"),
      amount = euros(184),
      reason = sample.getString("sample.refunds.refund_4471_why"),
      refusable = true,
    )
    val tafs = listOf(
      TafLine("t1", sample.getString("sample.refunds.name"), taf.getString("taf.state.waiting"), halted = false),
      TafLine("t2", sample.getString("sample.bill_pay.name"), taf.getString("taf.state.working"), halted = false),
    )
    return WatchModel.Shown.Read(
      WatchModel.NeedsYouRead(count = 1, words = Res.p(R.plurals.watch_held_calls, 1, Res.number(1)), calls = listOf(refund)),
      WatchModel.TafsRead(tafs, allHalted = false),
    )
  }

  /** The microphone first: Ask tafs, an ask at work and one ready under it, and Needs you's count. */
  @Test fun askTafs() = shot("1-ask-tafs") {
    WatchScreen(read(), null, false, voice = null, result = null, working = listOf(working()), ready = listOf(finished()), act = actions)
  }

  /** A result: its taf, its line, Hear and Reply. */
  @Test fun result() = shot("2-result") {
    WatchScreen(read(), null, false, voice = null, result = finished(), working = emptyList(), ready = emptyList(), act = actions)
  }

  /** Needs you, opened on the held refund: Refuse and Approve on your phone. The list is the microphone 0, Needs you 1, its line 2, the refund 3. */
  @Test fun needsYou() = shot("3-needs-you") {
    WatchScreen(read(), null, false, null, null, emptyList(), emptyList(), actions, list = rememberScalingLazyListState(initialCenterItemIndex = 3))
  }

  /** Nothing needs you: the owner's all-clear (board 12) above Needs you's words. The list is the microphone 0, Needs you 1, the all-clear 2. */
  @Test fun allClear() = shot("4-all-clear") {
    WatchScreen(
      read().copy(needsYou = WatchModel.NeedsYouRead(0, Res.s(R.string.needs_you_nothing), emptyList())), null, false, null, null,
      emptyList(), emptyList(), actions,
      // A little further on, so the microphone above Needs you is off the screen rather than cut at its edge.
      list = rememberScalingLazyListState(initialCenterItemIndex = 2, initialCenterItemScrollOffset = 28),
    )
  }

  companion object {
    /** The 8 languages, each with the Play listing's own locale for its folder. */
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun languages(): List<Array<String>> = listOf(
      arrayOf("en", "en-US"), arrayOf("de", "de-DE"), arrayOf("fr", "fr-FR"), arrayOf("es", "es-ES"),
      arrayOf("it", "it-IT"), arrayOf("pt", "pt-PT"), arrayOf("nl", "nl-NL"), arrayOf("pl", "pl-PL"),
    )
  }
}

/**
 * Play takes a phone or Wear OS screenshot only as a JPEG or a 24-bit PNG with no alpha: the recorded image is written again as
 * opaque RGB. Only when recording, so a normal run leaves the committed images as they are.
 */
internal fun opaqueForPlay(file: java.io.File) {
  if (System.getProperty("roborazzi.test.record") != "true" || !file.exists()) return
  val drawn = javax.imageio.ImageIO.read(file)
  val rgb = java.awt.image.BufferedImage(drawn.width, drawn.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
  rgb.createGraphics().apply { drawImage(drawn, 0, 0, null); dispose() }
  javax.imageio.ImageIO.write(rgb, "png", file)
}
