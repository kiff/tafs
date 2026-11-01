package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.ChatExport
import bot.tafs.app.model.ExportResult
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exporting the person's own chats (docs/data.md, Your own copy): tafs cloud's answer as two files, and a failure said. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportChatsTest {
  private val t0 = Instant.parse("2026-11-01T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val route = "GET /v1/me/chats/export"
  private val answer = """{"exported_at":"2026-11-01T09:00:00Z","chats":[{"taf":"Refunds","messages":[{"from":"you","text":"Refund Ana","amount":40.1}]}],"notes":[],"readable":"tafs: your chats\n\nRefunds\nYou: Refund Ana\n"}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0.plusMillis(currentTime) }, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  @Test fun theExportIsTafsCloudsDataWithoutReadableAndReadableAsText() {
    val export = ChatExport.of(answer.toByteArray())
    assertEquals("tafs: your chats\n\nRefunds\nYou: Refund Ana\n", export.text)
    val data = KiffClient.json.parseToJsonElement(export.json).jsonObject
    assertFalse("readable" in data)
    assertEquals(setOf("exported_at", "chats", "notes"), data.keys)
    // The data as tafs cloud sent it, only laid out to read.
    assertEquals(
      KiffClient.json.parseToJsonElement(answer).jsonObject.filterKeys { it != "readable" },
      data.toMap(),
    )
  }

  @Test fun exportingReadsTafsCloudsRouteOnce() = runTest {
    reads()
    transport.answer(route, 200, answer)
    val m = model()
    val result = m.exportChats()
    assertTrue(result is ExportResult.Ready)
    assertEquals(1, transport.requestsTo(route).size)
  }

  @Test fun anExportThatFailedSaysWhyInTheAppsWords() = runTest {
    reads()
    transport.answer(route, 500, """{"error":"export broke"}""")
    val m = model()
    val result = m.exportChats()
    assertTrue(result is ExportResult.NotExported && !result.why.contains("export broke"))
  }
}
