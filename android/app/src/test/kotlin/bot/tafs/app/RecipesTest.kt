package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A new Coach conversation opens to tafs cloud's recipes (kiff/tafs#476): drawn by category in tafs cloud's order, as it words
 * them; a tap puts a recipe's words in the message box and sends nothing.
 */
class RecipesTest {
  private val t0 = Instant.parse("2026-11-05T00:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()

  private val recipes =
    """{"categories":[{"id":"money","title":"Money"},{"id":"customers","title":"Customers"},{"id":"errands","title":"Errands"}],""" +
      """"recipes":[{"id":"chase-overdue-invoices","title":"Chase overdue invoices","category":"money","line":"Reminds customers whose invoices are late.",""" +
      """"say":"Chase my overdue invoices: remind once a week, at most 3 times.","connections":[["stripe"]],"card":{"allows":[],"holds":[],"never":[]}},""" +
      """{"id":"follow-up-new-leads","title":"Follow up new leads","category":"customers","line":"Writes back to new leads the same day.",""" +
      """"say":"Follow up every new lead within a day.","connections":[["hubspot"]],"card":{"allows":[],"holds":[],"never":[]}}],"filtered":true}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage","holds.answer"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"},"tools":{"state":"given"}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  @Test fun coachReadsTheRecipesByCategoryInTafsCloudsOrder() = runTest {
    reads()
    transport.answer("GET /v1/recipes", 200, recipes)
    val m = model()
    m.refresh()
    m.loadCoach()
    val shown = m.state.value.recipes!!.byCategory
    assertEquals("a category with no recipe isn't shown", listOf("Money", "Customers"), shown.map { it.first.title })
    assertEquals(listOf("Chase overdue invoices"), shown.first().second.map { it.title })
  }

  @Test fun aTapPutsTheWordsInTheMessageBoxAndSendsNothing() = runTest {
    reads()
    transport.answer("GET /v1/recipes", 200, recipes)
    val m = model()
    m.refresh()
    m.loadCoach()
    val before = transport.requests.size
    // CoachStarts' fill is the chat's draft setter.
    m.setDraft(COACH_CHAT, m.state.value.recipes!!.recipes.first().say)
    assertEquals("Chase my overdue invoices: remind once a week, at most 3 times.", m.state.value.drafts[COACH_CHAT])
    assertEquals("nothing was sent", before, transport.requests.size)
  }

  @Test fun recipesNotReadLeaveCoachUsable() = runTest {
    reads()
    transport.answer("GET /v1/recipes", 503, """{"error":"down"}""")
    val m = model()
    m.refresh()
    m.loadCoach()
    assertNull(m.state.value.recipes)
    assertEquals("setup_1", m.state.value.coachCloudId)
    assertTrue("no error for missing recipes", m.state.value.chatErrors[COACH_CHAT] == null)
  }
  // MARK: "Show suggestions", kept in tafs cloud

  @Test fun noRecipesComeWhenTafsCloudDoesntShowThemAndNoneAreDrawn() = runTest {
    val m = model()
    transport.answer("GET /v1/recipes", 200, """{"categories":[{"id":"money","title":"Money"}],"recipes":[],"filtered":true,"shown":false,
      "suggestions":{"show":true,"words":"The account has a live taf, so a new conversation with Coach opens blank."}}""")
    m.loadRecipes()
    val read = m.state.value.recipes!!
    assertFalse(read.shown)
    assertTrue(read.byCategory.isEmpty())
  }

  @Test fun theSettingIsReadAndChangedInTafsCloudAndTheRecipesReadAgain() = runTest {
    val m = model()
    transport.answer("GET /v1/me/suggestions", 200, """{"suggestions":{"show":true,"words":"Suggestions show in a new conversation with Coach until the account's first taf is live."}}""")
    m.loadSuggestions()
    assertTrue(m.state.value.suggestions!!.show)
    transport.answer("PUT /v1/me/suggestions", 200, """{"suggestions":{"show":false,"words":"Suggestions are off for this account."}}""")
    transport.answer("GET /v1/recipes", 200, """{"categories":[],"recipes":[],"shown":false,"suggestions":{"show":false,"words":"Suggestions are off for this account."}}""")
    m.setSuggestions(false)
    assertEquals("""{"show":false}""", String(transport.requestsTo("PUT /v1/me/suggestions").single().body!!))
    assertEquals("Suggestions are off for this account.", m.state.value.suggestions!!.words)
    assertFalse(m.state.value.suggestions!!.show)
    assertFalse(m.state.value.recipes!!.shown)
    assertFalse(m.state.value.suggestionsBusy)
  }

  @Test fun aRefusedChangeIsSaidInTafsCloudsWordsAndNothingChanges() = runTest {
    val m = model()
    transport.answer("GET /v1/me/suggestions", 200, """{"suggestions":{"show":true,"words":"On"}}""")
    m.loadSuggestions()
    transport.answer("PUT /v1/me/suggestions", 403, """{"error":"the suggestions setting is for the people who manage the account's tafs"}""")
    m.setSuggestions(false)
    assertTrue(m.state.value.suggestions!!.show)
    assertEquals("It didn't change. the suggestions setting is for the people who manage the account's tafs", m.state.value.suggestionsError)
    assertFalse(m.state.value.suggestionsBusy)
  }
}
