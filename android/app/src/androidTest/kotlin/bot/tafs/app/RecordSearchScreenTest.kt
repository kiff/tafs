package bot.tafs.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.app.ReceiptsResult
import bot.tafs.app.app.RecordSearch
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.ActivityCall
import bot.tafs.app.kiff.CaseFailed
import bot.tafs.app.kiff.ReceiptsExport
import bot.tafs.app.kiff.RecordCase
import bot.tafs.app.kiff.RecordHit
import bot.tafs.app.kiff.RecordHitGroup
import bot.tafs.app.kiff.RecordSearchPage
import bot.tafs.app.kiff.RecordTafRef
import bot.tafs.app.views.ExportReceiptsRow
import bot.tafs.app.views.RECORD_SEARCH_PAUSE_MS
import bot.tafs.app.views.RecordCaseSheet
import bot.tafs.app.views.RecordSearchBox
import bot.tafs.app.views.RecordResults
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Record searched across tafs, one case, and receipts for an accountant (docs/android-app.md › The Record): every word found is
 * tafs cloud's; a call about something opens its case; the export hands tafs cloud's files to the share sheet as they came.
 */
class RecordSearchScreenTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val call = ActivityCall(proposalId = "p_1", actionName = "create_refund", outcome = "allowed", createdAt = Instant.parse("2026-11-11T09:00:00Z"))

  private fun hit(id: String, title: String, about: String?) =
    RecordHit(id = id, kind = "done", title = title, detail = "€150 to a customer", time = "10:00", amountText = "€150.00", call = call, taf = RecordTafRef("taf_9", "Refunds"), about = about)

  @Test fun aFoundCallShowsUnderItsTafsNameAndOpensItsCase() {
    val opened = mutableListOf<String>()
    val search = RecordSearch(
      q = "1042",
      pages = listOf(RecordSearchPage(q = "1042", groups = listOf(RecordHitGroup("today", "Today", listOf(hit("e1", "Refund a payment", "Order 1042")))))),
    )
    rule.setShown {
      TafsTheme { RecordResults(search, AppState(mode = Mode.kiff, ready = true), actions = null, onCase = { opened += it }, onMore = {}, onConnectApps = {}) }
    }
    rule.onNodeWithText("TODAY").assertExists()
    rule.onNodeWithText("REFUNDS").assertExists()
    rule.onNodeWithText("Refund a payment").assertExists()
    rule.onNodeWithText("Order 1042 ›").performClick()
    rule.waitForIdle()
    assertEquals(listOf("Order 1042"), opened)
  }

  @Test fun theBoxSearchesAsYouTypeAfterAPauseAndEnterStillSearchesAtOnce() {
    val asked = mutableListOf<String>()
    rule.setShown { TafsTheme { RecordSearchBox(hint = null) { asked += it } } }
    val box = rule.onNode(hasSetTextAction())
    rule.mainClock.autoAdvance = false
    box.performTextInput("80")
    rule.mainClock.advanceTimeBy(RECORD_SEARCH_PAUSE_MS / 2)
    box.performTextInput("0")
    rule.mainClock.advanceTimeBy(RECORD_SEARCH_PAUSE_MS / 2)
    // Still typing: nothing sent yet.
    assertEquals(emptyList<String>(), asked)
    rule.mainClock.advanceTimeBy(RECORD_SEARCH_PAUSE_MS)
    assertEquals(listOf("800"), asked)
    // The keyboard's Search sends at once.
    box.performImeAction()
    rule.mainClock.advanceTimeBy(16)
    assertEquals(listOf("800", "800"), asked)
    // Clearing the box shows the Record at once.
    box.performTextClearance()
    rule.mainClock.advanceTimeBy(16)
    assertEquals(listOf("800", "800", ""), asked)
    rule.mainClock.autoAdvance = true
  }

  @Test fun nothingFoundSaysSoInTafsCloudsWords() {
    val search = RecordSearch(q = "zzz", pages = listOf(RecordSearchPage(q = "zzz", noneWords = "Nothing in your Record matches “zzz”.")))
    rule.setShown { TafsTheme { RecordResults(search, AppState(mode = Mode.kiff, ready = true), null, {}, {}, {}) } }
    rule.onNodeWithText("Nothing in your Record matches “zzz”.").assertExists()
  }

  @Test fun aCaseNamesTheStepThatFailedAndListsItsStepsOldestFirst() {
    val case = RecordCase(
      about = "Order 1042",
      steps = listOf(hit("s1", "Read the ticket", "Order 1042"), hit("s2", "Reply to the customer", "Order 1042")),
      failed = CaseFailed("s2", "The reply didn't go."),
    )
    rule.setShown { TafsTheme { RecordCaseSheet(case, null, AppState(mode = Mode.kiff, ready = true), null, onClose = {}, onConnectApps = {}) } }
    rule.onNodeWithText("Order 1042").assertExists()
    rule.onNodeWithText("The reply didn't go.").assertExists()
    val first = rule.onNodeWithText("Read the ticket").fetchSemanticsNode().boundsInRoot.top
    val second = rule.onNodeWithText("Reply to the customer").performScrollTo().fetchSemanticsNode().boundsInRoot.top
    assertEquals(true, first < second)
  }

  @Test fun exportingTheLastSevenDaysHandsTafsCloudsFilesToTheShareSheet() {
    val asked = mutableListOf<Pair<String, String>>()
    val shared = mutableListOf<ReceiptsExport>()
    val export = ReceiptsExport("2026-11-05", "2026-11-11", """{"receipts":[]}""", "day,taf\n", missing = 2)
    rule.setShown {
      TafsTheme {
        ExportReceiptsRow(
          onExport = { from, to -> asked += from to to; ReceiptsResult.Ready(export) },
          today = { LocalDate.parse("2026-11-11") },
          share = { shared += it },
        )
      }
    }
    rule.onNodeWithText("Export receipts").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Last 7 days").performClick()
    rule.waitForIdle()
    assertEquals(listOf("2026-11-05" to "2026-11-11"), asked)
    assertEquals(listOf(export), shared)
    rule.onNodeWithText("2 calls have no receipt yet.").assertExists()
  }

  @Test fun anExportTafsCloudRefusesSaysWhy() {
    rule.setShown {
      TafsTheme { ExportReceiptsRow(onExport = { _, _ -> ReceiptsResult.NotExported("Export fewer days.") }, share = {}) }
    }
    rule.onNodeWithText("Export receipts").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Last 90 days").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("They couldn't be exported. Export fewer days.").assertExists()
  }
}
