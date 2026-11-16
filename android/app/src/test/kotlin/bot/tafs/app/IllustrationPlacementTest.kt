package bot.tafs.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's boards 1–10 on the screens they belong to (docs/android-app.md, Illustrations), as iOS places them. The drawings
 * carry no words (rule 14): every word on those screens stays the screen's own, from app/strings.
 */
class IllustrationPlacementTest {
  private fun source(path: String) = File("src/main/kotlin/bot/tafs/app/$path").readText()

  @Test fun eachScreenDrawsItsBoard() {
    val boards = mapOf(
      "views/OnboardingView.kt" to "FirstRunArt",
      "views/HomeView.kt" to "NoTafsArt",
      "views/BriefView.kt" to "BriefClearArt",
      "views/HaltViews.kt" to "AllHaltedArt",
      "views/SecureKeySheet.kt" to "AppConnectedArt",
      "views/NeedsYouView.kt" to "NeedsYouClearArt",
      "views/CoachViews.kt" to "CoachStartArt",
      "views/HomeRecipes.kt" to "NewTafArt",
      "views/OverviewView.kt" to "OverviewArt",
    )
    for ((file, art) in boards) assertTrue("$file draws $art", source(file).contains("$art("))
    // Board 9 is Coach's start screen on a wide screen only, as iOS's iPad and Mac.
    assertTrue(Regex("""wide -> [^\n]*CoachLoadingArt\(""").containsMatchIn(source("views/CoachViews.kt")))
  }

  @Test fun theDrawingsHaveNoWords() {
    val drawings = source("design/Illustrations.kt")
    assertFalse("no Text in a drawing", Regex("""\bText\(""").containsMatchIn(drawings))
    assertFalse("no drawn text", drawings.contains("drawText") || drawings.contains("TextMeasurer"))
    assertFalse("no image file", Regex("""painterResource|ImageBitmap|R\.drawable""").containsMatchIn(drawings))
    // No string in the code, its comments set aside: nothing a person could read.
    val code = drawings.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")
    val strings = Regex(""""([^"\\]|\\.)*"""").findAll(code).map { it.value }.toList()
    assertTrue("no string literal in the drawings: $strings", strings.isEmpty())
  }
}
