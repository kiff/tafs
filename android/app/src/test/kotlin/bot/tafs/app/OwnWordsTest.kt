package bot.tafs.app

import bot.tafs.app.kiff.CloudError
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.model.Chat
import bot.tafs.app.model.ErrorWords
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.OwnWords
import bot.tafs.app.model.Work
import bot.tafs.app.model.WorkStep
import bot.tafs.app.signing.ApprovalKeyRenewed
import bot.tafs.app.voice.SpeechLanguage
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the owner reads is the app's own words or a server's words for them: never a tool's id or a server's error text. */
class OwnWordsTest {
  // MARK: Steps

  @Test fun aStepTafsCloudGivesNoWordsForHasNoLineAndNoToolIdShows() {
    val live = LiveAnswer(started = Instant.EPOCH)
      .began("show", "", "k1")
      .began("list_charges_ijkp", "Reading payments…", "k2")
    assertEquals("Reading payments…", live.activity)
    assertEquals(listOf("", "Reading payments…"), live.steps.map { it.words })
    // A step with no words leaves "Thinking…" as it is, rather than show its tool.
    assertNull(LiveAnswer(started = Instant.EPOCH).began("show", "", "k1").activity)
  }

  @Test fun aRunsWorkSaysHowLongAndHowManyStepsNeverWhichTools() {
    val steps = listOf(WorkStep("list_charges_ijkp"), WorkStep("refund_charge", error = true), WorkStep("get_charge_ijkp"))
    assertEquals("Worked 4.2 s · 3 steps, 1 failed", Chat.workLine(Work(4200, steps)))
    assertEquals("Worked 12 s · 1 step", Chat.workLine(Work(12_000, steps.take(1))))
    assertEquals("Worked 0.8 s", Chat.workLine(Work(800, emptyList())))
    assertFalse(Chat.workLine(Work(4200, steps)).contains("charge"))
  }

  @Test fun aRunsStepsAreListedInTafsCloudsWordsAndOneWithoutWordsIsLeftOut() {
    val steps = listOf(
      WorkStep("list_charges_ijkp", words = "Listed your charges"),
      WorkStep("refund_charge", error = true, words = "Refunded a charge"),
      WorkStep("kiff_pending"),
      WorkStep("send_reply", notMade = true, words = "Sent the reply"),
    )
    assertEquals(listOf("Listed your charges", "Refunded a charge · failed", "Sent the reply · not made"), Chat.steps(Work(4200, steps)))
    assertEquals("Worked 4.2 s · 4 steps, 1 failed", Chat.workLine(Work(4200, steps)))
    assertTrue(Chat.steps(Work(4200, steps)).none { it.contains("_") })
  }

  @Test fun theThreadsStepWordsAreRead() {
    val wire = bot.tafs.app.kiff.KiffClient.json.decodeFromString<bot.tafs.app.kiff.WireWork>(
      """{"ms":1200,"steps":[{"tool":"list_charges_ijkp","words":"Listed your charges"},{"tool":"show"}]}""",
    )
    assertEquals(listOf("Listed your charges", null), wire.steps.map { it.words })
  }

  // MARK: The home list's order

  @Test fun aRefreshNeverMovesARowWhileTheListIsShown() {
    val shown = bot.tafs.app.model.ListOrder.Shown(listOf("refunds", "refunds-5", "bills"), waiting = emptySet())
    // Refunds 5 was used just now, so the list's own order would put it first: it stays where it was.
    assertEquals(listOf("refunds", "refunds-5", "bills"), bot.tafs.app.model.ListOrder.hold(shown, listOf("refunds-5", "refunds", "bills"), emptySet()))
    // A taf newly waiting on the owner goes to the top at once.
    assertEquals(listOf("bills", "refunds", "refunds-5"), bot.tafs.app.model.ListOrder.hold(shown, listOf("bills", "refunds-5", "refunds"), setOf("bills")))
    // A new taf goes last; one removed leaves.
    assertEquals(listOf("refunds", "bills", "access"), bot.tafs.app.model.ListOrder.hold(shown, listOf("access", "refunds", "bills"), emptySet()))
    // Nothing shown yet (first open, back in the app, a pull): the list's own order.
    assertEquals(listOf("refunds-5", "refunds"), bot.tafs.app.model.ListOrder.hold(null, listOf("refunds-5", "refunds"), emptySet()))
  }

  // MARK: Dictation's language

  @Test fun englishInFranceDictatesInBritishEnglishThenAmerican() {
    assertEquals(SpeechLanguage.Choice.Use("en-GB"), SpeechLanguage.choose(Locale.forLanguageTag("en-FR"), listOf("en-US", "en-GB"), listOf("en-US", "en-GB")))
    assertEquals(SpeechLanguage.Choice.Use("en-US"), SpeechLanguage.choose(Locale.forLanguageTag("en-FR"), listOf("en-US"), listOf("en-US", "en-GB")))
  }

  @Test fun thePhonesOwnLocaleComesFirstAndALanguagesMainVariantNext() {
    assertEquals(SpeechLanguage.Choice.Use("en-US"), SpeechLanguage.choose(Locale.US, listOf("en-GB", "en-US"), emptyList()))
    assertEquals(SpeechLanguage.Choice.Use("fr-FR"), SpeechLanguage.choose(Locale.forLanguageTag("fr-BE"), listOf("fr-CA", "fr-FR"), emptyList()))
  }

  @Test fun aLanguageNotOnThePhoneYetIsDownloadedAndOneNotOfferedIsNone() {
    assertEquals(SpeechLanguage.Choice.Download("en-GB"), SpeechLanguage.choose(Locale.forLanguageTag("en-FR"), listOf("fr-FR"), listOf("en-GB", "fr-FR")))
    assertEquals(SpeechLanguage.Choice.None, SpeechLanguage.choose(Locale.FRANCE, listOf("en-US"), listOf("en-US")))
    // Ready now beats downloading.
    assertEquals(SpeechLanguage.Choice.Use("en-US"), SpeechLanguage.choose(Locale.forLanguageTag("en-FR"), listOf("en-US"), listOf("en-GB", "en-US")))
  }

  @Test fun dictationSaysWhatTheOwnerCanDoNeverTapAgainWhenItCantHelp() {
    assertTrue(SpeechLanguage.cannot("English").endsWith("You can type instead."))
    assertFalse(SpeechLanguage.cannot("English").contains("again"))
    assertTrue(SpeechLanguage.downloading("English").contains("once it's ready"))
  }

  // MARK: Errors

  @Test fun aServersRefusalIsSaidInTheAppsWordsWithWhatTheOwnerCanDo() {
    assertEquals(ErrorWords.OURS, ErrorWords.of(CloudError(400, "send the phone's push token, in hex")))
    assertEquals(ErrorWords.DOWN, ErrorWords.of(CloudError(503, "alerts aren't set up")))
    assertEquals(ErrorWords.NOT_ALLOWED, ErrorWords.of(KiffError.Server(403, "forbidden")))
    assertEquals(ErrorWords.CHANGED, ErrorWords.of(CloudError(409, "card_not_waiting")))
    assertEquals(ErrorWords.BUSY, ErrorWords.of(CloudError(429, "slow down")))
    assertEquals(ErrorWords.SIGNED_OUT, ErrorWords.of(CloudError(401, "unauthorized")))
    assertEquals(ErrorWords.OFFLINE, ErrorWords.of(java.net.SocketTimeoutException("timeout")))
    assertEquals(ErrorWords.OURS, ErrorWords.of(IllegalStateException("Unexpected JSON token at offset 3")))
    // None says to tap again where tapping again can't help.
    for (w in listOf(ErrorWords.OURS, ErrorWords.NOT_ALLOWED, ErrorWords.CHANGED)) assertFalse(w, w.contains("again"))
  }

  @Test fun theAppsOwnErrorsKeepTheirWords() {
    assertEquals(KiffError.Unauthorized.message, ErrorWords.of(KiffError.Unauthorized))
    assertEquals(ApprovalKeyRenewed().message, ErrorWords.of(ApprovalKeyRenewed()))
    assertEquals("The file didn't upload. Try attaching it again.", ErrorWords.of(OwnWords("The file didn't upload. Try attaching it again.")))
  }
}
