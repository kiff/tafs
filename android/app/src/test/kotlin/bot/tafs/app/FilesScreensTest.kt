package bot.tafs.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.files.FileDetail
import bot.tafs.app.files.FilePreview
import bot.tafs.app.files.FilesFeature
import bot.tafs.app.files.FilesModel
import bot.tafs.app.files.FilesState
import bot.tafs.app.files.FilesView
import bot.tafs.app.files.KeptFile
import bot.tafs.app.files.StubFilesStore
import bot.tafs.app.model.Words
import bot.tafs.app.model.dayTitle
import bot.tafs.app.model.localDate
import bot.tafs.app.model.shortTime
import bot.tafs.app.model.shownDate
import bot.tafs.app.views.ChatFiles
import bot.tafs.app.views.FilesAccountRow
import bot.tafs.app.views.FilesActions
import bot.tafs.app.views.FilesPane
import bot.tafs.app.views.FilesScreen
import bot.tafs.app.views.LocalFiles
import bot.tafs.app.views.TafFilesRow
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Files' screens (docs/android-app.md, Files): the Account row, the list's three ways, a file, Delete asked first, the tablet's two panes, and nothing at all with the switch off. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w411dp-h2000dp")
class FilesScreensTest {
  @get:Rule val compose = createComposeRule()

  private val now = Instant.now()
  private val today = localDate(now)
  private val did = mutableListOf<String>()

  private val act = FilesActions(
    close = { did += "close" }, view = { did += "view $it" }, pickTaf = { did += "taf $it" }, open = { did += "open ${it.id}" },
    closeFile = { did += "close file" }, download = { did += "download ${it.id}" }, share = { did += "share ${it.id}" },
    askDelete = { did += "ask ${it.id}" }, cancelDelete = { did += "cancel" }, confirmDelete = { did += "delete" },
  )

  // Made today in the account's zone, whatever the time the test runs: one goes in 30 days, the other tomorrow.
  private fun at(day: LocalDate, hour: Int): Instant = day.atTime(hour, 0).atZone(bot.tafs.app.model.zone).toInstant()
  private val tickets = KeptFile("f_1", "Tickets answered, 17 Nov.csv", "text/csv", 14_000, at(today, 9), at(today, 9).plusSeconds(30L * 86_400), "taf_support", "Support replies", "run_s1", "chat_s1")
  private val parcels = KeptFile("f_2", "Parcels without a scan.csv", "text/csv", 6_000, at(today, 8), at(today.plusDays(1), 12), "taf_parcels", "Late parcels", "run_p1", "chat_p1")

  private fun show(state: FilesState, twoPane: Boolean = false) =
    compose.setContent { TafsTheme { FilesScreen(state, act, twoPane = twoPane, today = today) } }

  private fun withFiles(model: FilesModel?, content: @androidx.compose.runtime.Composable () -> Unit) =
    compose.setContent { TafsTheme { CompositionLocalProvider(LocalFiles provides model) { content() } } }

  // MARK: The switch

  @Test fun filesShipsOn() {
    assertTrue("Files is on since Android 125 (the owner's 19 Nov yes, its privacy lines live)", FilesFeature.on)
  }

  @Test fun withTheSwitchOffNothingOfFilesIsDrawn() {
    // No model where it's off: the Account row, a taf's row and a chat's files draw nothing.
    withFiles(null) {
      Column {
        FilesAccountRow {}
        TafFilesRow("taf_parcels")
        ChatFiles("chat_p1")
      }
    }
    compose.onAllNodesWithText(Words.Files.title, substring = true).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Files.noneYet).assertCountEquals(0)
  }

  // MARK: The entries

  @Test fun theAccountRowSaysHowManyFilesAndThatTheyreKept30Days() {
    val model = FilesModel(StubFilesStore())
    var opened = 0
    withFiles(model) { FilesAccountRow { opened++ } }
    compose.onNodeWithText(Words.Files.accountRow(4)).assertExists()
    compose.onNodeWithText("${Words.Files.title} ›").performClick()
    assertEquals(1, opened)
  }

  @Test fun withNoFilesTheAccountRowSaysNoneYet() {
    val store = StubFilesStore()
    runBlocking { for (id in listOf("f_1", "f_2", "f_3", "f_4")) store.delete(id) }
    withFiles(FilesModel(store)) { FilesAccountRow {} }
    compose.onNodeWithText(Words.Files.noneYet).assertExists()
  }

  @Test fun aTafsRowCountsItsFilesAndOpensThemByRun() {
    val model = FilesModel(StubFilesStore())
    withFiles(model) { TafFilesRow("taf_parcels") }
    compose.onNodeWithText(Words.Files.tafRow(2)).assertExists()
    val until = model.state.value.files!!.filter { it.tafId == "taf_parcels" }.maxOf { it.expires }
    compose.onNodeWithText(Words.Files.keptUntil(shownDate(until))).assertExists()
    compose.onNodeWithText("${Words.Files.title} ›").performClick()
    assertTrue(model.state.value.showing)
    assertEquals("taf_parcels", model.state.value.taf)
    assertEquals(FilesView.ByRun, model.state.value.view)
  }

  @Test fun aChatsFilesAreChipsThatOpenTheFile() {
    val model = FilesModel(StubFilesStore())
    runBlocking { model.refresh() }
    withFiles(model) { ChatFiles("chat_p1") }
    compose.onNodeWithText("Parcels without a scan.csv").assertExists()
    compose.onNodeWithText("Late parcels, 17 Nov.md").assertExists()
    // Another chat's file isn't here.
    compose.onAllNodesWithText("Tickets answered, 17 Nov.csv").assertCountEquals(0)
    compose.onNodeWithText("Parcels without a scan.csv").performClick()
    compose.waitForIdle()
    assertEquals("f_2", model.state.value.open?.file?.id)
    assertTrue("opened on its own: closing it goes back to the chat", model.state.value.alone)
  }

  // MARK: The list

  @Test fun theListIsAllByTafAndByRunWithWhenEachGoes() {
    show(FilesState(files = listOf(tickets, parcels)))
    compose.onNodeWithText(dayTitle(today, today).uppercase()).assertExists()
    compose.onNodeWithText(tickets.name).assertExists()
    compose.onNodeWithText(Words.Files.goesInDays(30)).assertExists()
    compose.onNodeWithText(Words.Files.goesTomorrow).assertExists()
    compose.onNodeWithText(Words.Files.keptLine).performScrollTo().assertExists()
    // The chips: All tafs, then each taf with files.
    compose.onNodeWithText(Words.Files.allTafs).assertExists()
    compose.onNodeWithText("Late parcels").performClick()
    compose.onNodeWithText(Words.Files.byTaf).performClick()
    compose.onNodeWithText(Words.Files.byRun).performClick()
    assertEquals(listOf("taf taf_parcels", "view ByTaf", "view ByRun"), did)
  }

  @Test fun byTafHeadsEachTafWithItsCount() {
    show(FilesState(files = listOf(tickets, parcels), view = FilesView.ByTaf))
    compose.onNodeWithText("Support replies · ${Words.Files.tafRow(1)}".uppercase()).assertExists()
  }

  @Test fun byRunHeadsEachRunWithItsTafAndTime() {
    show(FilesState(files = listOf(tickets, parcels), view = FilesView.ByRun))
    compose.onNodeWithText("Late parcels · ${dayTitle(localDate(parcels.created), today)} · ${shortTime(parcels.created)}".uppercase()).assertExists()
  }

  @Test fun aTafChosenNarrowsTheList() {
    show(FilesState(files = listOf(tickets, parcels), taf = "taf_parcels"))
    compose.onNodeWithText(parcels.name).assertExists()
    compose.onAllNodesWithText(tickets.name).assertCountEquals(0)
  }

  @Test fun withNoFilesTheListSaysWhatLandsThere() {
    show(FilesState(files = emptyList()))
    compose.onNodeWithText(Words.Files.empty).assertExists()
  }

  // MARK: A file

  private val detail = FileDetail(tickets.copy(name = "reports/‮Tickets.csv"), FilePreview.Text("# Item,Done‮\n#7781,09:04\u0007"))

  @Test fun aFileShowsItsPreviewAsPlainTextAndItsDetails() {
    show(FilesState(files = listOf(tickets), open = detail))
    // Its name cleaned: no path, no override.
    compose.onNodeWithText("Tickets.csv").assertExists()
    // Plain text, cleaned: the Markdown heading's # is there as written.
    compose.onNodeWithText("# Item,Done\n#7781,09:04").assertExists()
    // Its type in its tile and in its details.
    compose.onAllNodesWithText("CSV").assertCountEquals(2)
    for (words in listOf(Words.Files.madeBy, "Support replies", Words.Files.inRun, Words.Files.type, Words.Files.size, Words.Files.whoSees, Words.Files.everyone, Words.Files.goesAway, Words.Files.goesOn(shownDate(tickets.expires)))) {
      compose.onNodeWithText(words).assertExists()
    }
    compose.onNodeWithText(Words.Files.download).performClick()
    compose.onNodeWithText(Words.Files.share).performClick()
    compose.onNodeWithText(Words.Files.deleteNow).performScrollTo().performClick()
    assertEquals(listOf("download f_1", "share f_1", "ask f_1"), did)
  }

  @Test fun aWholePreviewSaysNothingMore() {
    show(FilesState(files = listOf(tickets), open = detail))
    compose.onAllNodesWithText(Words.Files.previewStart).assertCountEquals(0)
  }

  @Test fun aPreviewCutAt4KbSaysOnlyTheStartIsShown() {
    show(FilesState(files = listOf(tickets), open = detail.copy(preview = FilePreview.Text("# Item,Done", truncated = true))))
    compose.onNodeWithText(Words.Files.previewStart).assertExists()
  }

  @Test fun onAPhoneAFileTakesTheListsPlaceWithBack() {
    show(FilesState(files = listOf(tickets, parcels), open = detail))
    compose.onAllNodesWithText(Words.Files.byRun).assertCountEquals(0)
    compose.onNodeWithText(Words.Common.back).performClick()
    assertEquals(listOf("close file"), did)
  }

  @Test fun deleteIsAskedFirstWithItsName() {
    show(FilesState(files = listOf(tickets), open = detail, asking = tickets))
    compose.onNodeWithText(Words.Files.deleteQuestion(tickets.name)).assertExists()
    compose.onNodeWithText(Words.Files.deleteBody).assertExists()
    compose.onNode(hasText(Words.Common.cancel) and hasAnyAncestor(isDialog())).performClick()
    compose.onNode(hasText(Words.Files.deleteNow) and hasAnyAncestor(isDialog())).performClick()
    assertEquals(listOf("cancel", "delete"), did)
  }

  @Test fun deleteNowConfirmedTakesItAwayForEveryone() {
    val store = StubFilesStore()
    val model = FilesModel(store)
    compose.setContent { TafsTheme { FilesPane(model, onClose = {}) } }
    compose.onNodeWithText("Nord Atelier call notes.txt").performScrollTo().performClick()
    compose.waitForIdle()
    compose.onNodeWithText(Words.Files.deleteNow).performScrollTo().performClick()
    compose.onNode(hasText(Words.Files.deleteNow) and hasAnyAncestor(isDialog())).performClick()
    compose.waitForIdle()
    compose.onNodeWithText(Words.Files.deleted).assertExists()
    compose.onAllNodesWithText("Nord Atelier call notes.txt").assertCountEquals(0)
    assertNull(runBlocking { store.file("f_4") })
  }

  // MARK: A tablet

  @Test fun onATabletBesideTheListItSaysToPickAFile() {
    show(FilesState(files = listOf(tickets, parcels)), twoPane = true)
    compose.onNodeWithText(Words.Files.pickOne).assertExists()
    compose.onNodeWithText(parcels.name).performClick()
    assertEquals(listOf("open f_2"), did)
  }

  @Test fun onATabletTheListIsBesideTheFile() {
    show(FilesState(files = listOf(tickets, parcels), open = detail), twoPane = true)
    // Both at once: the list's segments and rows, and the file's details.
    compose.onNodeWithText(Words.Files.byRun).assertExists()
    compose.onNodeWithText(parcels.name).assertExists()
    compose.onNodeWithText(Words.Files.whoSees).assertExists()
    compose.onAllNodesWithText(Words.Files.pickOne).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Common.back).assertCountEquals(0)
  }

  @Test fun theScreenIsTheFilesScreen() {
    show(FilesState(files = listOf(tickets)))
    compose.onNodeWithTag(bot.tafs.app.views.FILES_SCREEN).assertExists()
    compose.onAllNodesWithTag(bot.tafs.app.views.FILE_SCREEN).assertCountEquals(0)
  }

  @Test fun theDaysLeftFollowTheAccountsCalendar() {
    assertEquals(Words.Files.goesToday, bot.tafs.app.views.goesWords(tickets.copy(expires = now), today))
    assertEquals(Words.Files.goesInDays(3), bot.tafs.app.views.goesWords(tickets.copy(expires = today.plusDays(3).atStartOfDay(bot.tafs.app.model.zone).toInstant()), today))
    assertEquals(LocalDate.now(bot.tafs.app.model.zone), today)
  }
}
