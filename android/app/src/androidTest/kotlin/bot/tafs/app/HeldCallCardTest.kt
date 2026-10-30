package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.model.Answer
import bot.tafs.app.model.Card
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.ProposedAction
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import bot.tafs.app.model.Money
import bot.tafs.app.views.HeldCallCard
import bot.tafs.app.views.LocalAnswerPage
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

/**
 * The card a held call is answered on, drawn in each state it can be in: the sample's, a call KIFF holds that
 * this phone can answer, one it can't, a viewer's, and the ones KIFF refuses part or all of. No answer here is
 * real: the card only reports what the owner tapped.
 */
class HeldCallCardTest {
  @get:Rule val rule = createComposeRule()

  private val taf = Taf(id = "a-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"))

  private fun call(
    kiff: Boolean = true,
    approvableOnce: Boolean = true,
    version: Int? = 1,
    nextSteps: List<String> = emptyList(),
    details: List<String>? = null,
    expiresAt: Instant = Instant.now().plusSeconds(600),
  ) = HeldCall(
    id = "held-1", tafId = "a-1", proposed = ProposedAction("create_refund", "Refund a payment", Money.of(15000, "EUR", 2)),
    reason = "Over the limit", heldAt = Instant.now(), expiresAt = expiresAt,
    kiffId = if (kiff) "ce_1" else null, accountingVersion = version, approvableOnce = approvableOnce, nextSteps = nextSteps, details = details,
  )

  private fun state(
    kiff: Boolean = true,
    sessionHasKey: Boolean = true,
    capabilities: List<String>? = null,
    unanswerable: Map<String, String> = emptyMap(),
    entries: List<RecordEntry> = emptyList(),
  ) = AppState(
    mode = if (kiff) Mode.kiff else Mode.sample, ready = true, tafs = listOf(taf), sessionHasKey = sessionHasKey,
    kiffAccount = Account("t", "acme", capabilities = capabilities), unanswerable = unanswerable, record = entries,
  )

  private fun show(call: HeldCall, state: AppState, onAnswer: suspend (HeldCall, Answer) -> Unit = { _, _ -> }) {
    rule.setContent {
      TafsTheme {
        CompositionLocalProvider(
          LocalBiometry provides "your fingerprint",
          LocalAnswerPage provides { c -> c.kiffId?.let { "https://app.kiff.dev/needs-you/$it" } },
        ) { HeldCallCard(call, state, onAnswer) }
      }
    }
  }

  private fun text(t: String) = rule.onNodeWithText(t, substring = true)

  private fun gone(t: String) = rule.onNodeWithText(t, substring = true).assertDoesNotExistCompat()

  private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

  @Test fun theSamplesCallIsAnsweredOnThePhoneWithApproveAndRefuse() {
    show(call(kiff = false), state(kiff = false))
    text("Refund a payment").assertExists()
    text("Nothing is sent until you approve.").assertExists()
    text("Approve once with your fingerprint").assertExists()
    text("Refuse").assertExists()
    gone("Answer in KIFF")
  }

  @Test fun aCallKiffHoldsThatThePhoneCanAnswerGetsTheSameButtonsAndItsNextSteps() {
    show(call(nextSteps = listOf("Return first")), state())
    text("Approve once with your fingerprint").assertExists()
    text("Return first").assertExists()
    text("Refuse").assertExists()
    gone("Answer in KIFF")
  }

  @Test fun tappingApproveReportsTheAnswerAndTheButtonsWaitUntilItIsDone() {
    val gate = CompletableDeferred<Unit>()
    val answers = mutableListOf<Answer>()
    show(call(), state()) { _, a -> answers += a; gate.await() }
    text("Approve once with your fingerprint").performClick()
    rule.waitForIdle()
    check(answers == listOf<Answer>(Answer.ApproveOnce)) { answers.toString() }
    // While the owner is at the prompt a second tap sends nothing, and nothing looks tappable.
    text("Refuse").assertIsNotEnabled()
    text("Refuse").performClick()
    rule.waitForIdle()
    check(answers.size == 1) { "a second tap sent: $answers" }
    gate.complete(Unit)
    rule.waitForIdle()
    text("Refuse").assertIsEnabled()
  }

  @Test fun refusingAndANextStepAreAnswersToo() {
    val answers = mutableListOf<Answer>()
    show(call(nextSteps = listOf("Return first")), state()) { _, a -> answers += a }
    text("Return first").performClick()
    rule.waitForIdle()
    text("Refuse").performClick()
    rule.waitForIdle()
    check(answers == listOf(Answer.NextStep("Return first"), Answer.Refuse)) { answers.toString() }
  }

  @Test fun aCallThePhoneCannotAnswerSendsTheOwnerToKiffAndOffersNoButtonsThatWouldDoNothing() {
    show(call(), state(sessionHasKey = false))
    text("Answering from the phone arrives once KIFF checks the phone's signature").assertExists()
    text("Answer in KIFF").assertExists()
    gone("Approve once")
    gone("Refuse")
  }

  @Test fun aHoldWithNoAccountingVersionIsAnsweredInKiffToo() {
    show(call(version = null), state())
    text("Answer in KIFF").assertExists()
    gone("Approve once")
  }

  @Test fun aViewerIsToldWhoAnswersInsteadOfBeingOfferedButtonsKiffRefuses() {
    show(call(), state(capabilities = listOf("tafs.use")))
    text("Someone who approves on this account answers this.").assertExists()
    gone("Approve once")
    gone("Refuse")
  }

  @Test fun aReasonThePersonMayNeverAnswerLeavesWordsAndNoButtons() {
    show(call(), state(unanswerable = mapOf("held-1" to "role_forbidden")))
    text("Your role on this account doesn't answer held calls.").assertExists()
    gone("Approve once")
    gone("Refuse")
  }

  @Test fun aCallThePersonAskedForLeavesRefusingOpenButNotApproving() {
    show(call(nextSteps = listOf("Return first")), state(unanswerable = mapOf("held-1" to "self_review")))
    text("You asked for this, so someone else on the account approves it.").assertExists()
    text("Refuse").assertExists()
    text("Return first").assertExists()
    gone("Approve once")
  }

  @Test fun aCallOverATotalSaysApprovingOnceCannotPassItAndOffersExtraRoomToWhoManagesCards() {
    show(call(approvableOnce = false), state(capabilities = listOf("holds.answer", "cards.manage")))
    text("Approving it once can't pass this").assertExists()
    text("Extra room adds just what this call needs").assertExists()
    text("Extra room with your fingerprint").assertExists()
    text("Refuse").assertExists()
    gone("Approve once")
  }

  @Test fun aCallOverATotalOffersExtraRoomToNoOneWhoDoesNotManageCards() {
    show(call(approvableOnce = false), state(capabilities = listOf("holds.answer")))
    text("Someone who manages the account's Cards can give it extra room").assertExists()
    gone("Extra room with")
    text("Refuse").assertExists()
  }

  @Test fun extraRoomIsAnAnswerToo() {
    val answers = mutableListOf<Answer>()
    show(call(approvableOnce = false), state()) { _, a -> answers += a }
    text("Extra room with your fingerprint").performClick()
    rule.waitForIdle()
    check(answers == listOf<Answer>(Answer.ExtraRoom)) { answers.toString() }
  }

  @Test fun callsTheVendorRefusedTodayAreSaidWhereExtraRoomIsOffered() {
    val refused = RecordEntry(tafId = "a-1", kind = RecordEntry.Kind.notDone, title = "x", detail = "", at = Instant.now(), refusedByTool = true, refusedBy = "Stripe")
    show(call(approvableOnce = false), state(entries = listOf(refused)))
    text("1 call Stripe refused today still counted toward it").assertExists()
  }

  @Test fun anExpiredCallSaysSoAndOffersNothing() {
    show(call(expiresAt = Instant.now().minusSeconds(5)), state())
    text("Expired. Nobody answered in time").assertExists()
    gone("Approve once")
    gone("Refuse")
  }

  @Test fun whatTheCallIsOnIsFoldedUnderDetails() {
    show(call(details = listOf("payment_intent: pi_123")), state())
    gone("payment_intent: pi_123")
    text("Details").performClick()
    text("payment_intent: pi_123").assertExists()
  }

  @Test fun theCardSaysWhoAskedWhatForHowMuchAndWhy() {
    show(call(), state())
    text("Refunds").assertExists()
    text("Refund a payment").assertExists()
    text("Over the limit").assertExists()
    text(Money.of(15000, "EUR", 2).formatted).assertExists()
  }
}
