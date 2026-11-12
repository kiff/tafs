package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.design.LogoStore
import bot.tafs.app.kiff.CategoryColor
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.RecipesGallery
import bot.tafs.app.kiff.TafsCloud
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every job a taf can take, also once a taf runs (`GET /v1/recipes/gallery`, docs/android-app.md): the jobs with their apps' logos and
 * their category's colour, and the strip under the tafs list, all as tafs cloud sends them. Logos come from tafs cloud only.
 */
class GalleryTest {
  private val t0 = Instant.parse("2026-11-11T07:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val key = "3f9c0a1b2c3d4e5f60718293a4b5c6d7"

  private val gallery =
    """{"categories":[{"id":"money","title":"Money","color":{"light":"#107758","dark":"#4CC38A"}},{"id":"customers","title":"Customers","color":{"light":"#D0104D","dark":"#FF7FA3"}}],""" +
      """"recipes":[""" +
      """{"id":"refunds","title":"Refunds","category":"money","headline":"No refund waits overnight.","say":"Refund orders under 50 euros.","apps":[{"name":"Stripe","logo":"/v1/logos/$key"}],"connected":true,"running":true},""" +
      """{"id":"chase-overdue-invoices","title":"Chase invoices","category":"money","apps":[{"name":"Stripe","logo":"/v1/logos/$key"},{"name":"Gmail"}],"connected":false,"running":false},""" +
      """{"id":"follow-up-new-leads","title":"Follow up leads","category":"customers","apps":[{"name":"HubSpot"}]}],""" +
      """"strip":{"shown":true,"title":"More jobs to hand off","ids":["follow-up-new-leads","gone-since","chase-overdue-invoices"]},""" +
      """"words":{"title":"New taf","own_words":"Say it in your own words","own_words_line":"Tell Coach the job, and it builds the taf with you."},""" +
      """"labels":{"allows":"Does on its own","holds":"Asks you first","never":"Never does","hand":"Hand it to Coach"}}"""

  private fun read(json: String) = KiffClient.json.decodeFromString<RecipesGallery>(json)

  @Test fun theGalleryReadsAsTafsCloudSendsIt() {
    val g = read(gallery)
    assertEquals(listOf("Money", "Customers"), g.byCategory.map { it.first.title })
    val refunds = g.recipes.first()
    assertEquals("Stripe", refunds.apps.single().name)
    assertEquals("/v1/logos/$key", refunds.apps.single().logo)
    assertTrue(refunds.connected && refunds.running)
    assertNull(g.recipes[1].apps[1].logo)
    assertEquals("Say it in your own words", g.words?.ownWords)
    assertEquals("Hand it to Coach", g.labels?.hand)
  }

  @Test fun theStripIsTafsCloudsJobsInItsOrderAndNothingElse() {
    val g = read(gallery)
    // An id the gallery doesn't have is left out, never guessed at; the order is tafs cloud's.
    assertEquals(listOf("follow-up-new-leads", "chase-overdue-invoices"), g.stripRecipes.map { it.id })
    assertEquals(emptyList<String>(), read(gallery.replace("\"shown\":true,\"title\":\"More", "\"shown\":false,\"title\":\"More")).stripRecipes.map { it.id })
    assertEquals(emptyList<String>(), read("""{"categories":[],"recipes":[]}""").stripRecipes.map { it.id })
  }

  @Test fun browsingReadsTafsCloudsWordsAndEachJobsProofAndSample() {
    val sent = gallery
      .replace(""""own_words_line":"Tell Coach the job, and it builds the taf with you."}""",
        """"own_words_line":"Tell Coach the job, and it builds the taf with you.","explore":"Explore jobs","see_all":"See all jobs","try":"Try it with Coach","sample":"What it reported after its test run"}""")
      .replace(""""id":"follow-up-new-leads","title":"Follow up leads","category":"customers",""",
        """"id":"follow-up-new-leads","title":"Follow up leads","category":"customers","proved":"Proved on a test help desk.","sample":"3 tickets answered.",""")
    val g = read(sent)
    assertEquals("Explore jobs", g.words?.explore)
    assertEquals("See all jobs", g.words?.seeAll)
    assertEquals("Try it with Coach", g.words?.tryIt)
    assertEquals("What it reported after its test run", g.words?.sample)
    val leads = g.recipes.first { it.id == "follow-up-new-leads" }
    assertEquals("Proved on a test help desk.", leads.proved)
    assertEquals("3 tickets answered.", leads.sample)
    // A job with no proof run has neither line, and nothing is drawn for it.
    assertEquals("", g.recipes.first().proved)
    assertEquals("", g.recipes.first().sample)
    assertEquals("Explore jobs", bot.tafs.app.model.HomeWords.explore(g))
    assertEquals("Try it with Coach", bot.tafs.app.model.HomeWords.tryIt(g))
  }

  @Test fun whileTafsCloudSendsNoWordsTheAppsOwnStandInAndNewIsAlwaysNamed() {
    val g = read("""{"categories":[],"recipes":[]}""")
    val home = bot.tafs.app.model.HomeWords
    val words = bot.tafs.app.model.Words.Home
    // + New is never left without words: tafs cloud's title, else the app's "New taf".
    assertEquals(words.newTaf, home.newTaf(g))
    assertEquals(words.newTaf, home.newTaf(null))
    assertEquals(words.newTaf, home.newTaf(read("""{"categories":[],"recipes":[],"words":{"title":"  "}}""")))
    assertEquals("New taf", home.newTaf(read(gallery)))
    assertEquals(words.exploreJobs, home.explore(g))
    assertEquals(words.seeAllJobs, home.seeAll(g))
    assertEquals(words.tryWithCoach, home.tryIt(g))
    assertEquals(words.sampleLabel, home.sampleLabel(g))
  }

  @Test fun theRowUnderTheStripShowsOnlyWhenTheGalleryHoldsMore() {
    val g = read(gallery)
    assertTrue("three jobs, the strip shows two", g.holdsMoreThanStrip)
    val whole = g.copy(strip = g.strip!!.copy(ids = g.recipes.map { it.id }))
    assertFalse("the strip already shows every job", whole.holdsMoreThanStrip)
    val hidden = g.copy(strip = g.strip!!.copy(shown = false))
    assertFalse("no strip, so no row under it", hidden.holdsMoreThanStrip)
  }

  @Test fun anOlderTafsCloudsRecipesStillRead() {
    val g = read("""{"categories":[{"id":"money","title":"Money"}],"recipes":[{"id":"r","title":"R","category":"money"}]}""")
    assertNull(g.categories.single().color)
    assertTrue(g.recipes.single().apps.isEmpty())
    assertFalse(g.recipes.single().running)
  }

  @Test fun aCategorysColourIsTafsCloudsTokenForTheThemeOnScreen() {
    val c = CategoryColor(light = "#107758", dark = "#4CC38A")
    assertEquals(0xFF107758L, c.argb(dark = false))
    assertEquals(0xFF4CC38AL, c.argb(dark = true))
    // Anything that isn't #RRGGBB is no colour, and the app draws its own.
    assertNull(CategoryColor(light = "green", dark = "#4CC38A").argb(dark = false))
    assertNull(CategoryColor(light = "#107758FF").argb(dark = false))
  }

  @Test fun onlyTafsCloudsOwnLogoPathsAreLogos() {
    assertTrue(TafsCloud.isLogoPath("/v1/logos/$key"))
    for (not in listOf(
      "https://logos.composio.dev/api/stripe", "//logos.composio.dev/stripe", "/v1/logos/../me", "/v1/logos/$key?x=1",
      "/v1/logos/${key.uppercase()}", "/v1/logos/abc", "v1/logos/$key", "",
    )) assertFalse(not, TafsCloud.isLogoPath(not))
    assertFalse(TafsCloud.isLogoPath(null))
  }

  @Test fun aLogoIsReadFromTafsCloudWithNoSessionAndNothingElseIsFetched() = runTest {
    transport.answer("GET /v1/logos/$key", 200, "PNG")
    val cloud = TafsCloud(transport, token = "at_1")
    assertTrue(cloud.logo("/v1/logos/$key")!!.isNotEmpty())
    val sent = transport.requestsTo("GET /v1/logos/$key").single()
    assertEquals("https://cloud.tafs.kiff.dev/v1/logos/$key", sent.url)
    assertNull("no session with a public logo", sent.headers["Authorization"])
    // A third party's address is never asked for.
    assertNull(cloud.logo("https://logos.composio.dev/api/stripe"))
    assertEquals(1, transport.requests.size)
    // tafs cloud without one: none, and the name shows alone.
    transport.answer("GET /v1/logos/0000000000000000000000000000000f", 404)
    assertNull(cloud.logo("/v1/logos/0000000000000000000000000000000f"))
  }

  @Test fun theLogoStoreKeepsALogoForAWeekAndFetchesOnlyTafsCloudPaths() = runTest {
    val dir = kotlin.io.path.createTempDirectory("logos").toFile()
    var now = 1_000_000L
    val fetched = mutableListOf<String>()
    val bytes = byteArrayOf(1, 2, 3)
    fun store() = LogoStore(dir, { p -> fetched += p; bytes }, now = { now })
    val path = "/v1/logos/$key"
    assertArrayEquals(bytes, store().bytes(path))
    // Another start of the app: from disk, not fetched again, within the week.
    now += LogoStore.MAX_AGE_MS - 1
    assertArrayEquals(bytes, store().bytes(path))
    assertEquals(listOf(path), fetched)
    // Past the week: fetched again.
    now += 2
    assertArrayEquals(bytes, store().bytes(path))
    assertEquals(listOf(path, path), fetched)
    // Anything else is never fetched.
    assertNull(store().bytes("https://logos.composio.dev/api/stripe"))
    assertNull(store().bytes(null))
    assertEquals(2, fetched.size)
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage","holds.answer"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","state":"idle"}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
  }

  @Test fun eachRefreshReadsTheGalleryAlsoOnceATafRuns() = runTest {
    reads()
    transport.answer("GET /v1/recipes/gallery", 200, gallery)
    val m = model()
    m.refresh()
    assertEquals(listOf("follow-up-new-leads", "chase-overdue-invoices"), m.state.value.gallery?.stripRecipes?.map { it.id })
  }

  @Test fun aTafsCloudWithoutTheGalleryShowsNoneAndAFailedReadKeepsWhatWasRead() = runTest {
    reads()
    val m = model()
    m.refresh()
    // No route there yet (404): nothing to show, so no + New and no strip.
    assertNull(m.state.value.gallery)
    transport.answer("GET /v1/recipes/gallery", 200, gallery)
    transport.answer("GET /v1/recipes/gallery", 503, """{"error":"try again"}""")
    m.loadGallery()
    m.loadGallery()
    assertEquals(3, m.state.value.gallery?.recipes?.size)
  }
}
