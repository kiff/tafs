package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.ReceiptsResult
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Record searched across the person's tafs, one case, and receipts for an accountant (docs/android-app.md › The Record): what
 * tafs cloud finds and words, read as it sends it; the app keeps no copy of KIFF's receipts and rebuilds none.
 */
class RecordSearchTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-11-11T11:00:00Z")

  private fun hit(id: String, about: String?) =
    """{"id":"$id","at":"2026-11-11T09:00:00Z","kind":"done","title":"Refund a payment","detail":"€150 to Mara","time":"10:00","amount_text":"€150.00",""" +
      """"call":{"proposal_id":"p_$id","action_name":"create_refund","outcome":"allowed","created_at":"2026-11-11T09:00:00Z"},""" +
      """"taf":{"id":"taf_1","name":"Refunds"}""" + (about?.let { ""","about":"$it"""" } ?: "") + "}"

  private val page1 =
    """{"q":"1042","hint":"Search by order, invoice, amount or action","entries":[${hit("e1", "Order 1042")}],""" +
      """"groups":[{"id":"2026-11-11","title":"Today","entries":[${hit("e1", "Order 1042")}]}],"next_cursor":"c2"}"""
  private val page2 =
    """{"q":"1042","hint":"Search by order, invoice, amount or action","entries":[${hit("e2", null)}],""" +
      """"groups":[{"id":"2026-11-10","title":"Yesterday","entries":[${hit("e2", null)}]}],"end_words":"Nothing older"}"""

  private suspend fun model(access: String = """{"refunds-1":{"use":true,"oversee":true,"manage":true}}"""): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage","holds.answer"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":$access}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  @Test fun aSearchReadsTafsCloudsGroupsAndHintThenItsNextPage() = runTest {
    val m = model()
    transport.answer("GET /v1/record", 200, page1)
    transport.answer("GET /v1/record", 200, page2)
    m.searchRecord(" 1042 ")
    val first = m.state.value.recordSearch!!
    assertEquals("1042", first.q)
    assertEquals("Order 1042", first.pages.single().groups.single().entries.single().about)
    assertEquals("Refunds", first.pages.single().entries.single().taf.name)
    assertEquals("Search by order, invoice, amount or action", m.state.value.recordHint)
    assertTrue(transport.requestsTo("GET /v1/record").single().url.endsWith("/v1/record?q=1042"))
    m.searchRecord("1042", more = true)
    assertEquals(2, m.state.value.recordSearch!!.pages.size)
    assertTrue(transport.requestsTo("GET /v1/record")[1].url.contains("cursor=c2"))
    assertNull(m.state.value.recordSearch!!.nextCursor)
    // A blank box clears the search: the Record shows as before.
    m.searchRecord("  ")
    assertNull(m.state.value.recordSearch)
  }

  @Test fun aCursorTafsCloudRefusesSearchesAgainFromTheTop() = runTest {
    val m = model()
    transport.answer("GET /v1/record", 200, page1)
    transport.answer("GET /v1/record", 409, """{"error":"search again"}""")
    transport.answer("GET /v1/record", 200, page1)
    m.searchRecord("1042")
    m.searchRecord("1042", more = true)
    val asked = transport.requestsTo("GET /v1/record").map { it.url.substringAfter("/v1/record") }
    assertEquals(listOf("?q=1042", "?q=1042&cursor=c2", "?q=1042"), asked)
    assertEquals(1, m.state.value.recordSearch!!.pages.size)
    assertNull(m.state.value.recordSearch!!.error)
  }

  @Test fun aFailedSearchSaysWhyAndKeepsTheQuestion() = runTest {
    val m = model()
    transport.answer("GET /v1/record", 503, """{"error":"KIFF's activity couldn't be read"}""")
    m.searchRecord("1042")
    assertEquals("1042", m.state.value.recordSearch?.q)
    assertNotNull(m.state.value.recordSearch?.error)
  }

  @Test fun aCaseReadsItsStepsOldestFirstAndTheStepThatFailed() = runTest {
    val m = model()
    transport.answer(
      "GET /v1/record/cases/Order 1042", 200,
      """{"about":"Order 1042","steps":[${hit("s1", "Order 1042")},${hit("s2", "Order 1042")}],"failed":{"id":"s2","words":"The reply to Mara didn't go.","next":{"kind":"try_later","words":"Try later"}}}""",
    )
    m.openRecordCase("Order 1042")
    // The case's key is sent URL-encoded.
    assertTrue(transport.requests.last().url.endsWith("/v1/record/cases/Order%201042"))
    val case = m.state.value.recordCase!!
    assertEquals(listOf("s1", "s2"), case.steps.map { it.id })
    assertEquals("The reply to Mara didn't go.", case.failed?.words)
    assertEquals("try_later", case.failed?.next?.kind)
    m.closeRecordCase()
    assertNull(m.state.value.recordCase)
    // None of theirs is about it: why, in words.
    transport.answer("GET /v1/record/cases/Order 9999", 404, """{"error":"Nothing of yours is about that."}""")
    m.openRecordCase("Order 9999")
    assertNotNull(m.state.value.recordCaseError)
  }

  @Test fun theExportKeepsTafsCloudsAnswerByteForByteAndItsCsv() = runTest {
    val m = model()
    val body = """{"from":"2026-11-05","to":"2026-11-11","time_zone":"Europe/Paris","receipts":[{"trace_id":"t1","sig":"0xab","amount":15000}],"csv":"day,taf\n2026-11-05,Refunds\n","missing":2}"""
    transport.answer("GET /v1/record/receipts", 200, body)
    val r = m.exportReceipts("2026-11-05", "2026-11-11") as ReceiptsResult.Ready
    assertEquals(body, r.export.receiptsJson)
    assertEquals("day,taf\n2026-11-05,Refunds\n", r.export.csv)
    assertEquals(2, r.export.missing)
    assertTrue(transport.requestsTo("GET /v1/record/receipts").single().url.endsWith("?from=2026-11-05&to=2026-11-11"))
    // Someone who doesn't see every taf: tafs cloud's 403, in words.
    transport.answer("GET /v1/record/receipts", 403, """{"error":"Only those who see every taf's Record can export it."}""")
    assertTrue(m.exportReceipts("2026-11-05", "2026-11-11") is ReceiptsResult.NotExported)
  }

  @Test fun theExportIsOfferedOnlyToWhoeverSeesEveryTafsRecord() = runTest {
    assertTrue(model().state.value.seesEveryTaf)
    val viewer = RecordSearchTest().run { model("""{"refunds-1":{"use":true,"oversee":false,"manage":false}}""") }
    assertFalse(viewer.state.value.seesEveryTaf)
  }
}
