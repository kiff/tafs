package bot.tafs.app

import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.RecipesGallery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A job's guide, "How it goes", as tafs cloud sends it with the gallery (docs/coach.md "Recipes"): drawn as sent, in its words. */
class RecipeGuideTest {
  private val labels = ""","labels":{"allows":"On its own","holds":"Asks you first","never":"Never","hand":"Try it","guide":"How it goes","guide_do":"Do","guide_see":"You see","guide_if":"If"}"""

  private fun gallery(guide: String, withLabels: String = labels) = KiffClient.json.decodeFromString<RecipesGallery>(
    """{"categories":[],"recipes":[{"id":"chase","title":"Chase invoices","proved":"Proved on a test billing account.","guide":$guide}]$withLabels}""")

  @Test fun theStepsComeInOrderWithTheirLabelledLines() {
    val g = gallery("""[{"title":"Connect Stripe","do":"Approve it.","see":"Stripe listed as connected."},{"title":"Run it","do":"Ask it to run.","if":"No invoices: nothing is overdue."}]""")
    val steps = g.labels!!.guideOf(g.recipes.single())
    assertEquals(listOf("Connect Stripe", "Run it"), steps.map { it.title })
    assertEquals(listOf("Do" to "Approve it.", "You see" to "Stripe listed as connected."), steps[0].lines(g.labels!!))
    // A line that didn't come has no label either.
    assertEquals(listOf("Do" to "Ask it to run.", "If" to "No invoices: nothing is overdue."), steps[1].lines(g.labels!!))
  }

  @Test fun anOlderTafsCloudWithoutTheHeadingShowsNoGuide() {
    val g = gallery("""[{"title":"Connect Stripe","do":"Approve it."}]""", withLabels = ""","labels":{"allows":"On its own"}""")
    assertTrue(g.labels!!.guideOf(g.recipes.single()).isEmpty())
  }

  @Test fun aStepThatIsntOneIsLeftOutAndAGuideThatIsntAListIsNone() {
    val g = gallery("""[{"title":"Connect Stripe","do":"Approve it."},{"title":""},42,{"do":"no title"}]""")
    assertEquals(listOf("Connect Stripe"), g.labels!!.guideOf(g.recipes.single()).map { it.title })
    val odd = gallery("\"not a list\"")
    assertTrue(odd.labels!!.guideOf(odd.recipes.single()).isEmpty())
    // The rest of the gallery still reads.
    assertEquals("Proved on a test billing account.", odd.recipes.single().proved)
  }
}
