package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A request step by step (K5), from the dial's rim or Activity, as tafs cloud writes it. */
class RequestDetailTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()

  private suspend fun model(): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { Instant.parse("2026-11-03T01:00:00Z") }, start = false).also { it.restore(); it.refresh() }
  }

  private val detail = """{"id":"run_2","at":"2026-11-02T22:41:00Z","state":"retrying","line":"Refund $20 · order 4402","from":"By email",
    "deliveries":[{"place":"door_reply","state":"failed","line":"Reply not delivered","send_again":true}],
    "steps":[{"label":"Received","line":"Email from anna@example.com","at":"22:41","state":"done"},
             {"label":"Refunded $20, once","line":"Within its Card","at":"22:42","state":"done"},
             {"label":"Reply not delivered","line":"The email provider was down · try 2 of 3","state":"failed"},
             {"label":"Next try 23:15","line":"If it fails again, this needs you","state":"next"}]}"""

  @Test fun aRequestIsReadStepByStepAsTafsCloudWroteIt() = runTest {
    val m = model()
    transport.answer("GET /v1/tafs/taf_1/requests/run_2", 200, detail)
    m.openRequest("refunds-1", "run_2")
    val (taf, d) = m.state.value.openRequest!!
    assertEquals("refunds-1", taf)
    assertEquals("By email", d.from)
    assertEquals(listOf("done", "done", "failed", "next"), d.steps.map { it.state })
    assertEquals("Next try 23:15", d.steps.last().label)
    assertTrue(d.deliveries.single().sendAgain)
    m.closeRequest()
    assertNull(m.state.value.openRequest)
  }

  @Test fun theRimOpensTheFirstRequestNotDone() = runTest {
    val m = model()
    transport.answer("GET /v1/tafs/taf_1/work", 200, """{"summary":{"status":"retrying","line":"1 retrying"},"requests":[
      {"id":"run_2","at":"2026-11-02T22:41:00Z","state":"retrying","line":"Refund $20 · order 4402"},
      {"id":"run_1","at":"2026-11-02T21:00:00Z","state":"done","line":"Refund $10 · order 4401"}]}""")
    transport.answer("GET /v1/tafs/taf_1/requests/run_2", 200, detail)
    m.openRimRequest("refunds-1")
    assertEquals("run_2", m.state.value.openRequest!!.second.id)
  }

  @Test fun aRequestThatCantBeReadIsSaidInWords() = runTest {
    val m = model()
    transport.answer("GET /v1/tafs/taf_1/requests/run_9", 404, """{"error":"not found"}""")
    m.openRequest("refunds-1", "run_9")
    assertNull(m.state.value.openRequest)
    assertTrue(m.state.value.requestError!!.startsWith("That request couldn't be read."))
  }

  @Test fun aRunWaitingForAnAppsEventSaysItInTheConnectionsWordsAndIsntTheOwners() = runTest {
    val m = model()
    transport.answer(
      "GET /v1/tafs/taf_1/requests/run_3", 200,
      """{"id":"run_3","at":"2026-11-02T22:41:00Z","state":"waiting","line":"Waiting for the customer's reply","waiting_for":"Waiting for the customer's reply","from":"By email",
        "steps":[{"label":"Held for your approval","line":"Over its Card · 22:41","state":"waiting"},
                 {"label":"Sent the email","at":"22:42","state":"done"},
                 {"label":"Waiting for the customer's reply","state":"waiting"}]}""",
    )
    m.openRequest("refunds-1", "run_3")
    val d = m.state.value.openRequest!!.second
    assertEquals("Waiting for the customer's reply", d.waitingFor)
    // The wait tafs cloud names is the app's, drawn as not needing the owner; a hold is still the owner's.
    assertTrue(bot.tafs.app.views.RequestWords.waitsOnApp(d.steps[2], d))
    assertTrue(!bot.tafs.app.views.RequestWords.waitsOnApp(d.steps[0], d))
    // A request waiting on the owner has no waiting_for: none of its steps is the app's.
    val held = d.copy(waitingFor = null)
    assertTrue(!bot.tafs.app.views.RequestWords.waitsOnApp(held.steps[2], held))
  }
}
