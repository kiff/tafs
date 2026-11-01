package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.ChatExport
import bot.tafs.app.model.ExportResult
import bot.tafs.app.model.ExportWords
import bot.tafs.app.views.ExportChatsRow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings → Export my chats (docs/data.md): the row, the wait, the two files offered, and Try again after a failure. */
class ExportChatsTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val offered = mutableListOf<ChatExport>()
  private val export = ChatExport("""{"chats":[]}""", "tafs: your chats\n")

  @Test fun theRowSaysItsPreparingThenOffersTheFiles() {
    val answer = CompletableDeferred<ExportResult>()
    rule.setShown { TafsTheme { ExportChatsRow({ answer.await() }, share = { offered += it }) } }
    rule.onNodeWithText(ExportWords.ROW).assertIsDisplayed().performClick()
    rule.onNodeWithText(ExportWords.PREPARING).assertIsDisplayed()
    answer.complete(ExportResult.Ready(export))
    rule.waitUntil(5_000) { offered.isNotEmpty() }
    assertEquals(listOf(export), offered)
    rule.onNodeWithText(ExportWords.ROW).assertIsDisplayed()
  }

  @Test fun theTwoFilesAreWrittenAndHandedToTheShareSheet() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    // Throws if the FileProvider doesn't share the exports folder.
    bot.tafs.app.views.shareExport(context, export)
    val dir = java.io.File(context.cacheDir, "exports")
    assertEquals(export.json, java.io.File(dir, ExportWords.JSON_FILE).readText())
    assertEquals(export.text, java.io.File(dir, ExportWords.TEXT_FILE).readText())
  }

  @Test fun aFailedExportSaysWhyAndTryAgainOffersTheFiles() {
    var tries = 0
    rule.setShown {
      TafsTheme {
        ExportChatsRow({ if (tries++ == 0) ExportResult.NotExported("You're offline.") else ExportResult.Ready(export) }, share = { offered += it })
      }
    }
    rule.onNodeWithText(ExportWords.ROW).performClick()
    rule.onNodeWithText(ExportWords.notExported("You're offline.")).assertIsDisplayed()
    rule.onNodeWithText(ExportWords.TRY_AGAIN).performClick()
    rule.waitUntil(5_000) { offered.isNotEmpty() }
    assertEquals(listOf(export), offered)
  }
}
