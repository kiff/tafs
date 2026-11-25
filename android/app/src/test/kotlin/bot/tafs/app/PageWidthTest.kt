package bot.tafs.app

import androidx.compose.ui.unit.dp
import bot.tafs.app.design.PageWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One layout on every app (docs/parity.md): on a wide window a page is one centred column, capped by its kind. The owner's
 * finding on a big screen was a page drawn full width, or capped at the left with the right half empty.
 */
class PageWidthTest {
  private fun source(path: String) = File("src/main/kotlin/bot/tafs/app/$path").readText()

  @Test fun theCapsAreTheIPadsAndTheMacsInOnePlace() {
    assertEquals(720.dp, PageWidth.Reading)
    assertEquals(980.dp, PageWidth.List)
    assertEquals(640.dp, PageWidth.Gallery)
    assertEquals(1180.dp, PageWidth.Calendar)
  }

  @Test fun aPageColumnIsCentredThenCapped() {
    val page = source("design/PageWidth.kt")
    assertTrue("centred in the window, then capped", page.contains("fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()"))
  }

  @Test fun everySheetIsCappedAsASheetAndBesideTheSidebar() {
    val drive = source("drive/DriveWindows.kt")
    assertTrue("a reading page unless it says otherwise", drive.contains("maxWidth: androidx.compose.ui.unit.Dp = bot.tafs.app.design.PageWidth.Reading"))
    assertTrue("a sheet of its own", drive.contains("sheetMaxWidth = maxWidth"))
    assertTrue("beside a tablet's sidebar", drive.contains("Column(Modifier.weight(1f).pageColumn(maxWidth))"))
    assertTrue(source("views/ShareViews.kt").contains("maxWidth = bot.tafs.app.design.PageWidth.List"))
    assertTrue(source("views/HomeRecipes.kt").contains("maxWidth = bot.tafs.app.design.PageWidth.Gallery"))
  }

  @Test fun thePagesThatArentSheetsAreCentredColumnsToo() {
    assertTrue(source("views/OverviewView.kt").contains(".pageColumn(PageWidth.List)"))
    assertTrue(source("views/HomeView.kt").contains("Modifier.pageColumn(PageWidth.List)"))
    assertTrue(source("views/NeedsYouView.kt").contains("Modifier.pageColumn(PageWidth.Reading)"))
    assertTrue(source("views/CalendarView.kt").contains(".pageColumn(PageWidth.Calendar)"))
    assertTrue(source("views/TafChatView.kt").contains("private val Readable = bot.tafs.app.design.PageWidth.Reading"))
  }

  @Test fun noPageSetsItsOwnCap() {
    // A cap written as a number after the column fills the window was ignored, and left it uncentred (Overview, Calendar).
    val raw = Regex("""widthIn\(max = (640|720|980|1100|1180)\.dp\)""")
    File("src/main/kotlin/bot/tafs/app").walkTopDown().filter { it.extension == "kt" && it.name != "PageWidth.kt" }.forEach {
      assertFalse("${it.name} sets a page's cap itself", raw.containsMatchIn(it.readText()))
    }
  }
}
