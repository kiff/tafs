package bot.tafs.app

import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.RecipesGallery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A job's guide, "How it goes", as tafs cloud sends it with the gallery (docs/coach.md "Recipes"): drawn as sent, in its words. */
class RecipeGuideTest {
  // As tafs cloud sends them (cloud/recipes.ts): the guide's heading and labels in the gallery's `words`, beside `sample`.
  private val words = ""","labels":{"allows":"On its own","holds":"Asks you first","never":"Never","hand":"Try it"},"words":{"sample":"What it reported","guide":"How it goes","guide_do":"Do","guide_see":"You see","guide_if":"If"}"""

  private fun gallery(guide: String, withWords: String = words) = KiffClient.json.decodeFromString<RecipesGallery>(
    """{"categories":[],"recipes":[{"id":"chase","title":"Chase invoices","proved":"Proved on a test billing account.","guide":$guide}]$withWords}""")

  @Test fun theStepsComeInOrderWithTheirLabelledLines() {
    val g = gallery("""[{"title":"Connect Stripe","do":"Approve it.","see":"Stripe listed as connected."},{"title":"Run it","do":"Ask it to run.","if":"No invoices: nothing is overdue."}]""")
    val steps = g.words!!.guideOf(g.recipes.single())
    assertEquals(listOf("Connect Stripe", "Run it"), steps.map { it.title })
    assertEquals(listOf("Do" to "Approve it.", "You see" to "Stripe listed as connected."), steps[0].lines(g.words!!))
    // A line that didn't come has no label either.
    assertEquals(listOf("Do" to "Ask it to run.", "If" to "No invoices: nothing is overdue."), steps[1].lines(g.words!!))
  }

  // The labels alone don't carry it: they never hold the guide's words.
  @Test fun theGuidesWordsAreReadFromTheGallerysWordsNotItsLabels() {
    val g = gallery("""[{"title":"Connect Stripe","do":"Approve it."}]""", withWords = ""","labels":{"guide":"How it goes"}""")
    assertTrue((g.words ?: bot.tafs.app.kiff.GalleryWords()).guideOf(g.recipes.single()).isEmpty())
  }

  @Test fun anOlderTafsCloudWithoutTheHeadingShowsNoGuide() {
    val g = gallery("""[{"title":"Connect Stripe","do":"Approve it."}]""", withWords = ""","labels":{"allows":"On its own"},"words":{"sample":"What it reported"}""")
    assertTrue(g.words!!.guideOf(g.recipes.single()).isEmpty())
  }

  @Test fun aStepThatIsntOneIsLeftOutAndAGuideThatIsntAListIsNone() {
    val g = gallery("""[{"title":"Connect Stripe","do":"Approve it."},{"title":""},42,{"do":"no title"}]""")
    assertEquals(listOf("Connect Stripe"), g.words!!.guideOf(g.recipes.single()).map { it.title })
    val odd = gallery("\"not a list\"")
    assertTrue(odd.words!!.guideOf(odd.recipes.single()).isEmpty())
    // The rest of the gallery still reads.
    assertEquals("Proved on a test billing account.", odd.recipes.single().proved)
  }
}
