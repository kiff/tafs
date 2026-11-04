package bot.tafs.app

import bot.tafs.app.kiff.*
import bot.tafs.app.app.AppModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import bot.tafs.app.model.ThemePreference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class PresentationTest {
  private suspend fun model(transport: FakeTransport): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"account-example","tenant_slug":"example"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf-example","name":"Example","kiff_agent":"agent-example","state":"idle","card":{"ids":[],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    val secrets = InMemorySecretStore().also { it.save("synthetic-session", AppModel.TOKEN); it.save("account-example", AppModel.TENANT) }
    return testModel(transport = transport, secrets = secrets, start = false).also { it.restore() }
  }
  @Test fun cancelledPageReadCanBeOpenedAgain() = runTest {
    val transport = FakeTransport(); val m = model(transport)
    val id = m.state.value.tafs.single().id
    val route = "GET /v1/tafs/taf-example/activity"
    transport.answer(route, 200, """{"revision":"r"}""")
    transport.hold(route)
    val job = launch { m.loadActivityPage(id, "all") }
    runCurrent()
    assertTrue(m.state.value.activityPages["$id:all:"]!!.loading)
    job.cancel(); job.join(); transport.release(route)
    m.loadActivityPage(id, "all")
    assertFalse(m.state.value.activityPages["$id:all:"]!!.loading)
    assertEquals(1, m.state.value.activityPages["$id:all:"]!!.pages.size)
  }
  @Test fun aConditionalRefreshKeepsAllCachedPagesAndAccountExitClearsThem() = runTest {
    val transport = FakeTransport(); val m = model(transport)
    val id = m.state.value.tafs.single().id
    val route = "GET /v1/tafs/taf-example/activity"
    transport.answer(route, 200, """{"revision":"r","next_cursor":"page-two"}""", headers = mapOf("etag" to "\"view-1\""))
    transport.answer(route, 200, """{"revision":"r"}""")
    transport.answer(route, 304, "")
    m.loadActivityPage(id, "all"); m.loadActivityPage(id, "all", more = true); m.loadActivityPage(id, "all")
    assertEquals(2, m.state.value.activityPages["$id:all:"]!!.pages.size)
    assertEquals("\"view-1\"", transport.requestsTo(route).last().headers["If-None-Match"])
    m.signOut(tellKiff = false)
    assertTrue(m.state.value.activityPages.isEmpty())
  }
  @Test fun conditionalActivityReadKeepsTheValidatorAndPassesTheServerCursorUnchanged() = runTest {
    val requests = mutableListOf<HttpRequest>()
    val transport = object : Transport {
      override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return if (requests.size == 1) HttpResponse(200,
          """{"entries":[],"groups":[],"filters":[{"id":"held","title":"Held"}],"filter":"held","revision":"r","next_cursor":"opaque+/="}""".toByteArray(), mapOf("etag" to "\"view-1\""))
        else HttpResponse(304, ByteArray(0))
      }
    }
    val cloud = TafsCloud(transport, token = "synthetic-session")
    val first = cloud.activityPage("taf-example", "held")
    val same = cloud.activityPage("taf-example", "held", etag = first.etag)
    assertNull(same.value); assertEquals(first.etag, same.etag)
    assertEquals("\"view-1\"", requests.last().headers["If-None-Match"])
    cloud.activityPage("taf-example", "held", cursor = first.value!!.nextCursor)
    assertTrue(URI.create(requests.last().url).rawQuery.contains("cursor=opaque%2B%2F%3D"))
    assertTrue(requests.all { URI.create(it.url).path == "/v1/tafs/taf-example/activity" })
  }
  @Test fun systemIsTheDefaultAndTheExplicitThemesOverrideEitherSystemAppearance() {
    assertEquals(ThemePreference.System, ThemePreference.from(null))
    assertEquals(ThemePreference.System, ThemePreference.from("unknown"))
    for (system in listOf(false, true)) {
      assertFalse(ThemePreference.Light.dark(system)); assertTrue(ThemePreference.Dark.dark(system))
      assertEquals(system, ThemePreference.System.dark(system))
    }
  }
}
