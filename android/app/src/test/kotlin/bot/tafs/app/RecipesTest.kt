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

  private fun reads(home: String = """{"tafs":[],"others":[],"removing":[]}""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage","holds.answer"]}""")
    transport.answer("GET /v1/home", 200, home)
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

  // MARK: Home with no taf (Android 43)

  private val forHome =
    """{"categories":[{"id":"money","title":"Dinero"}],""" +
      """"recipes":[{"id":"refunds-within-a-limit","title":"Reembolsos dentro de un límite","category":"money","line":"Reembolsa lo que cabe en el límite.",""" +
      """"say":"Reembolsa los pedidos de menos de 50 €.","headline":"Ningún reembolso espera a mañana.","today":"Un reembolso que espera puede acabar en un contracargo.",""" +
      """"card":{"allows":["Reembolsa hasta 50 €"],"holds":["Más de 50 €"],"never":["Reembolsar dos veces"]}}],""" +
      """"filtered":true,"shown":true,"home":{"shown":true,"words":"Elige un trabajo para tu primer taf."},""" +
      """"labels":{"allows":"Lo hace solo","holds":"Te pregunta antes","never":"Nunca lo hace","hand":"Pásalo a Coach"}}"""

  @Test fun homeReadsTheRecipesWhenTheListHasNoTafAndKeepsEveryWordAsTafsCloudSaysIt() = runTest {
    reads()
    transport.answer("GET /v1/recipes", 200, forHome)
    val m = model()
    m.refresh()
    val r = m.state.value.recipes!!
    assertEquals(true, r.home?.shown)
    assertEquals("Elige un trabajo para tu primer taf.", r.home?.words)
    assertEquals("Pásalo a Coach", r.labels?.hand)
    val recipe = r.recipes.single()
    assertEquals("Ningún reembolso espera a mañana.", recipe.headline)
    assertEquals("Un reembolso que espera puede acabar en un contracargo.", recipe.today)
    assertEquals(listOf("Reembolsa hasta 50 €"), recipe.card.allows)
    assertEquals(listOf("Más de 50 €"), recipe.card.holds)
    assertEquals(listOf("Reembolsar dos veces"), recipe.card.never)
  }

  @Test fun anAccountWithATafDoesntReadTheRecipesForHome() = runTest {
    reads(home = """{"tafs":[{"id":"t1","name":"Refunds","job":"refunds","kiff_agent":"rae","status":"active","color":0,"state":"running","waiting":0}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/recipes", 200, forHome)
    val m = model()
    m.refresh()
    assertEquals("the list has a taf", 1, m.state.value.tafs.size)
    assertTrue(transport.requestsTo("GET /v1/recipes").isEmpty())
  }

  @Test fun aTafsCloudFromBeforeHomesLineLeavesHomeAsItWas() = runTest {
    reads()
    transport.answer("GET /v1/recipes", 200, recipes)
    val m = model()
    m.refresh()
    val r = m.state.value.recipes!!
    assertNull("no home: Home draws its own empty line, as before", r.home)
    assertNull(r.labels)
    assertEquals("", r.recipes.first().headline)
  }

  @Test fun handingARecipeToCoachOpensANewConversationWithItsWordsAndSendsNothing() = runTest {
    reads()
    transport.answer("GET /v1/recipes", 200, forHome)
    val m = model()
    m.refresh()
    val recipe = m.state.value.recipes!!.recipes.single()
    m.handRecipeToCoach(recipe)
    val s = m.state.value
    assertEquals("Reembolsa los pedidos de menos de 50 €.", s.drafts[COACH_CHAT])
    assertTrue(s.coachRequested)
    assertTrue("a new conversation", s.coachFresh)
    assertNull(s.coachImproving)
    assertTrue("nothing sent", transport.requestsTo("POST /v1/setup/messages").isEmpty())
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
    // What was sent: a write. A read refresh() or loadCoach() began in the model's own background may still land after this,
    // off the test's clock, so reads aren't counted.
    fun sent() = transport.requests.filterNot { it.method == "GET" }
    val before = sent()
    // CoachStarts' fill is the chat's draft setter.
    m.setDraft(COACH_CHAT, m.state.value.recipes!!.recipes.first().say)
    assertEquals("Chase my overdue invoices: remind once a week, at most 3 times.", m.state.value.drafts[COACH_CHAT])
    assertEquals("nothing was sent", before, sent())
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
