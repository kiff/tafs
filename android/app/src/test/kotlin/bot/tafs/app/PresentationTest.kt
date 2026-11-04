package bot.tafs.app

import bot.tafs.app.kiff.*
import bot.tafs.app.model.ThemePreference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class PresentationTest {
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
