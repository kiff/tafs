package bot.tafs.wear

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Row 30, call 6 (the Galaxy Watch 5 re-test: with the phone in Airplane mode, "Not sure it arrived" with a Retry button
 * stayed after the phone was back). A send with no receipt shows one calm linking screen and sends the same words, under the same
 * id, again by itself, until the phone answers.
 */
class CalmLinkingModelTest {
  private val t = 1_763_244_000_000L
  private val taken = AskLine("wa_1", "w_00000001", AskLine.ACCEPTED, words = "Sent · Coach has it", updated = "2026-11-25T17:21:00Z")

  private class Phone(var ask: () -> Asked) : PhoneLink {
    val sent = mutableListOf<WatchRequest.Ask>()
    override suspend fun ask(request: WatchRequest): Asked = when (request) {
      is WatchRequest.Ask -> ask().also { sent += request }
      is WatchRequest.NeedsYou -> Asked.Answered(WatchReply.NeedsYou(0, "Nothing needs you", emptyList()))
      else -> Asked.Unreachable()
    }
  }

  private suspend fun linking(phone: Phone, unlocked: () -> Boolean = { true }) =
    WatchModel(phone, now = { t }, unlocked = unlocked, newClientId = { "w_00000001" }, reviewFirst = true).also {
      it.review("Cannes")
      it.sendReviewed()
      assertEquals(WatchModel.Voice.Linking("Cannes", "w_00000001", null), it.voice.value)
    }

  @Test fun itSendsAgainByItselfUnderTheSameIdUntilThePhoneAnswers() = runTest {
    var back = false
    val phone = Phone { if (back) Asked.Answered(WatchReply.AskTaken(taken)) else Asked.Unreachable(LinkFailure.NotReachable) }
    val m = linking(phone)
    val waits = mutableListOf<Long>()
    m.keepTrying(wait = { waits += it; if (waits.size == 3) back = true })
    // Taken at the third try: back to the conversation, with the words as its newest turn.
    assertNull(m.voice.value)
    assertEquals(listOf(2_000L, 5_000L, 10_000L), waits)
    assertEquals(4, phone.sent.size)
    assertTrue(phone.sent.all { it.clientId == "w_00000001" && it.text == "Cannes" })
    assertEquals(taken, m.asks.value["wa_1"])
  }

  @Test fun afterTenMinutesItSaysNotSureItArrived() = runTest {
    val phone = Phone { Asked.Answered(WatchReply.Unsure) }
    val m = linking(phone)
    var waited = 0L
    m.keepTrying(wait = { waited += it })
    assertTrue(waited <= SEND_RETRY_FOR_MS)
    assertEquals(WatchModel.Voice.Unsure("Cannes", "w_00000001", null), m.voice.value)
  }

  @Test fun cancelStopsTheTries() = runTest {
    val phone = Phone { Asked.Unreachable(LinkFailure.NotReachable) }
    val m = linking(phone)
    m.keepTrying(wait = { m.closeVoice() })
    assertEquals(1, phone.sent.size)
    assertNull(m.voice.value)
  }

  @Test fun aLockedWatchSendsNothingWhileItWaits() = runTest {
    var unlocked = true
    val phone = Phone { Asked.Unreachable(LinkFailure.NotReachable) }
    val m = linking(phone) { unlocked }
    unlocked = false
    m.keepTrying(wait = {})
    assertEquals(1, phone.sent.size)
  }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class CalmLinkingScreenTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val did = mutableListOf<String>()
  private fun actions() = Actions(
    openOnPhone = {}, tryAgain = { did += "again" }, refuse = {}, halt = {}, askHaltAll = {}, confirmHaltAll = {}, cancelHaltAll = {},
    listen = {}, closeVoice = { did += "cancel" }, retry = { did += "retry" }, open = {}, hear = {}, reply = {}, closeResult = {}, dismiss = {},
    keep = { did += "keep" }, keepTrying = { did += "trying" },
  )

  @Test fun aSendWithNoReceiptShowsTheCalmLinkingScreenAndTriesByItself() {
    compose.setContent { MaterialTheme { VoiceScreen(WatchModel.Voice.Linking("Cannes", "w_1", null), actions()) } }
    compose.onNodeWithTag(SEND_LINKING).assertExists()
    compose.onNodeWithText(Words.Watch.linking).assertExists()
    compose.onAllNodesWithText(Words.Watch.notSure).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Watch.retry).assertCountEquals(0)
    compose.onNodeWithText(Words.Common.cancel).performClick()
    assertEquals(listOf("trying", "cancel"), did)
  }

  @Test fun aTafsMessageOffersNoKeepForLater() {
    compose.setContent { MaterialTheme { VoiceScreen(WatchModel.Voice.Linking("Cannes", "w_1", null, tafId = "taf_1"), actions()) } }
    compose.onAllNodesWithText(Words.Watch.keepForLater).assertCountEquals(0)
  }
}
