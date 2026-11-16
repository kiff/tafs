package bot.tafs.wear

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.DecisionLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Your brief on the watch's screen (docs/watch-wear.md, Your brief): in Needs you's place when the phone sent one, each decision
 * answered on the phone (Open on phone), Refuse only where Needs you offers it; with none, Needs you as before.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class WatchBriefScreenTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    // Tall, so the whole list is drawn at once and every line can be found.
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val call = HeldLine("hold_1", "Bill pay", "Pay bill 0412", "€184", "Over €100 each", refusable = true)
  private val pay = DecisionLine("d-pay", "Pay bill 0412?", "Bill pay", "by Fri 12:00", refKind = "hold", refId = "ce_1", holdId = "hold_1")
  private val bank = DecisionLine("d-bank", "Sign in to the bank again?", urgent = true, cantWait = "Bill pay can't run until then.", refKind = "connection", refId = "bank")
  private val brief = WatchReply.Brief(
    title = "Your brief",
    whenLine = "Morning brief · 08:00 · next at 18:00",
    decisions = listOf(bank, pay),
    urgent = "Urgent",
    done = "Done since last evening",
    doneCases = listOf("Atelier Dubois", "Brio"),
    rest = "14 more wait and need nothing now",
  )
  private val tafs = WatchModel.TafsRead(listOf(TafLine("taf_1", "Bill pay", "Waiting for you", halted = false)), allHalted = false)
  private val needsYou = WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call))

  private val opened = mutableListOf<String>()
  private val refused = mutableListOf<String>()
  private val actions = Actions(
    openOnPhone = { opened += it }, tryAgain = {}, refuse = { refused += it }, halt = {}, askHaltAll = {}, confirmHaltAll = {},
    cancelHaltAll = {}, askCoach = {}, sendToCoach = {}, closeTalk = {}, dismiss = {},
  )

  private fun show(read: WatchModel.Shown.Read) =
    compose.setContent { MaterialTheme { WatchScreen(read, outcome = null, confirmingHaltAll = false, talk = null, act = actions) } }

  @Test fun theBriefShowsInNeedsYousPlaceInItsOwnWords() {
    show(WatchModel.Shown.Read(needsYou, tafs, brief))
    for (words in listOf("Your brief", "Morning brief · 08:00 · next at 18:00", "Sign in to the bank again?", "Urgent", "Bill pay can't run until then.",
        "Pay bill 0412?", "by Fri 12:00", "Done since last evening", "Atelier Dubois", "Brio", "14 more wait and need nothing now")) {
      compose.onNodeWithText(words).assertExists()
    }
    // Needs you isn't drawn beside it: no Needs you heading, its words or its held call's card.
    compose.onAllNodesWithText(Words.NeedsYou.title).assertCountEquals(0)
    compose.onAllNodesWithText("1 held call needs you").assertCountEquals(0)
    compose.onAllNodesWithText(Words.Watch.approveOnPhone).assertCountEquals(0)
    // Your tafs and Halt stay below.
    compose.onNodeWithText(Words.Home.yourTafs).assertExists()
    compose.onNodeWithText(Words.Halt.allNow).assertExists()
  }

  @Test fun eachDecisionIsOpenOnPhoneAndRefuseOnlyWhereNeedsYouOffersIt() {
    show(WatchModel.Shown.Read(needsYou, tafs, brief))
    // One Open on phone per decision; one Refuse, on the held call Needs you would let the wrist refuse.
    compose.onAllNodesWithText(Words.Watch.openOnPhone).assertCountEquals(2)
    compose.onAllNodesWithText(Words.NeedsYou.refuse).assertCountEquals(1)
    compose.onNodeWithText(Words.NeedsYou.refuse).performClick()
    assertEquals(listOf("hold_1"), refused)
    compose.onAllNodesWithText(Words.Watch.openOnPhone)[0].performClick()
    assertEquals(listOf("needs-you"), opened)
  }

  @Test fun aCallNeedsYouWontRefuseFromTheWristHasNoRefuse() {
    show(WatchModel.Shown.Read(needsYou.copy(calls = listOf(call.copy(refusable = false))), tafs, brief))
    compose.onAllNodesWithText(Words.NeedsYou.refuse).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Watch.openOnPhone).assertCountEquals(2)
  }

  /** No brief (the switch off, or none on the account): Needs you, as before. */
  @Test fun withNoBriefNeedsYouIsAsBefore() {
    show(WatchModel.Shown.Read(needsYou, tafs))
    compose.onNodeWithText(Words.NeedsYou.title).assertExists()
    compose.onNodeWithText("1 held call needs you").assertExists()
    compose.onNodeWithText(Words.Watch.approveOnPhone).assertExists()
    compose.onAllNodesWithText("Your brief").assertCountEquals(0)
  }
}
