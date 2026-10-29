package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.CountedTask
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Usage
import bot.tafs.app.kiff.UsagePack
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.UsageWords
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account's usage this period, and a taf's own part of it. A fake KIFF stands in. */
class UsageTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val home =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""

  private val usage =
    """{"plan":"team","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
       "tasks":{"counted":14,"included":25,"packs":10,"left":21,
         "by_agent":[{"agent":"refunds-1","counted":9},{"agent":"billing-x","counted":5},{"agent":"quiet","counted":0}],
         "free":{"refused":3,"held_refused":1,"read":42,"setup_read":2,"setup":1}},
       "actions":{"counted":3,"included":500},
       "billing":{"source":"stripe"},
       "pack_list":[{"id":"rayo","name":"Rayo","tasks":70,"tasks_left":60,"expires_at":"2027-10-22T00:00:00Z"}],
       "can_add_tasks":true,"store":{"solo_product":"x","packs":[]},"requests":[]}"""

  private fun tasks(vararg items: String) = """{"items":[${items.joinToString(",")}]}"""
  private fun task(record: String, agent: String = "refunds-1", title: String? = "Refund an order") =
    """{"record":"$record","agent":"$agent","tool":"refund_order",${title?.let { """"tool_title":"$it",""" } ?: ""}"at":"2026-10-28T08:00:00Z"}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  // MARK: Reading

  @Test fun theAccountsUsageIsReadFromKiffWithItsCountedTasksAndAiUse() = runTest {
    reads()
    transport.answer("GET /v1/me/usage", 200, usage)
    transport.answer("GET /v1/me/usage/tasks", 200, tasks(task("tc-1"), task("tc-2", "billing-x", title = null)))
    transport.answer("GET /v1/usage/ai", 200, """{"used_pct":42,"asks_at_pct":100,"paused":false}""")
    val m = model()
    m.refreshUsage()
    val s = m.state.value
    assertEquals("team", s.usage!!.plan)
    assertEquals(14, s.usage!!.tasks.counted)
    assertEquals(35, s.usage!!.tasks.total)
    assertEquals(listOf("tc-1", "tc-2"), s.countedTasks.map { it.record })
    assertEquals(42, s.aiUse!!.usedPct)
    assertNull(s.usageError)
    assertEquals("Bearer at_1", transport.requestsTo("GET /v1/me/usage").single().headers["Authorization"])
    assertEquals("https://cloud.tafs.kiff.dev/v1/usage/ai", transport.requestsTo("GET /v1/usage/ai").single().url)
  }

  @Test fun aUsageKiffCannotGiveSaysSoAndNeverShowsAnEarlierReadAsNow() = runTest {
    reads()
    transport.answer("GET /v1/me/usage", 500, """{"error":"later"}""")
    val m = model()
    m.refreshUsage()
    assertNull(m.state.value.usage)
    assertEquals("Usage couldn't be read: later", m.state.value.usageError)
  }

  @Test fun theCountedTasksOrAiUseMissingDoNotHideTheRest() = runTest {
    reads()
    transport.answer("GET /v1/me/usage", 200, usage)
    transport.answer("GET /v1/me/usage/tasks", 500, """{"error":"x"}""")
    transport.answer("GET /v1/usage/ai", 404)
    val m = model()
    m.refreshUsage()
    assertNotNull(m.state.value.usage)
    assertTrue(m.state.value.countedTasks.isEmpty())
    assertNull(m.state.value.aiUse)
    assertNull(m.state.value.usageError)
  }

  @Test fun aTafsPartIsItsKiffAgentsAndATafKiffDoesNotListCountedNothing() = runTest {
    reads()
    transport.answer("GET /v1/me/usage", 200, usage)
    transport.answer("GET /v1/me/usage/tasks", 200, tasks())
    transport.answer("GET /v1/usage/ai", 404)
    val m = model()
    assertNull("nothing until read", m.state.value.tafUsage("refunds-1"))
    m.refreshUsage()
    assertEquals(9, m.state.value.tafUsage("refunds-1")!!.counted)
    assertEquals(0, m.state.value.tafUsage("never-listed")!!.counted)
  }

  @Test fun aTafsOwnCountedTasksAreAskedForByItsAgent() = runTest {
    reads()
    transport.answer("GET /v1/me/usage", 200, usage)
    transport.answer("GET /v1/me/usage/tasks", 200, tasks(task("tc-1"), task("tc-2")))
    transport.answer("GET /v1/usage/ai", 404)
    val m = model()
    m.refreshTafUsage("refunds-1")
    assertNotNull("usage is read first when it hasn't been", m.state.value.usage)
    assertEquals(listOf("tc-1", "tc-2"), m.state.value.tafTasks["refunds-1"]!!.map { it.record })
    val asked = transport.requestsTo("GET /v1/me/usage/tasks").map { it.url }
    assertTrue(asked.toString(), asked.contains("https://api.kiff.dev/v1/me/usage/tasks?agent=refunds-1"))
  }

  @Test fun theSamplesMonthIsATrialTwoDaysIn() = runTest {
    val m = testModel(transport = transport, clock = { t0 })
    assertEquals(Mode.sample, m.state.value.mode)
    m.refreshUsage()
    val u = m.state.value.usage!!
    assertEquals("trial", u.plan)
    assertEquals(14, u.tasks.counted)
    assertEquals(11, u.tasks.left)
    assertEquals(30, m.state.value.aiUse!!.usedPct)
    assertTrue("the sample touches no network", transport.requests.isEmpty())
  }

  @Test fun leavingTheAccountForgetsItsUsage() = runTest {
    reads()
    transport.answer("GET /v1/me/usage", 200, usage)
    transport.answer("GET /v1/me/usage/tasks", 200, tasks())
    transport.answer("GET /v1/usage/ai", 404)
    transport.answer("POST /v1/auth/device/logout", 200, "{}")
    val m = model()
    m.refreshUsage()
    m.signOut()
    assertNull(m.state.value.usage)
    assertNull(m.state.value.aiUse)
  }

  @Test fun aCountedTasksRecordLineIsFoundByKiffsCallKey() = runTest {
    reads()
    val m = model()
    assertNull(m.state.value.recordEntry("tc-9"))
    val entry = RecordEntry(tafId = "refunds-1", kind = RecordEntry.Kind.done, title = "Refund", detail = "", at = t0, entityId = "tc-9")
    val s = m.state.value.copy(record = listOf(entry))
    assertEquals(entry, s.recordEntry("tc-9"))
    assertEquals("Refunds", s.copy(tafs = m.state.value.tafs).tafName("refunds-1"))
    assertEquals("someone-else", s.tafName("someone-else"))  }

  // MARK: What KIFF sends

  @Test fun whatKiffDoesNotCountIsInItsOwnWordsAndSetupIsBothNames() {
    val u = KiffClient.json.decodeFromString<Usage>(usage)
    val lines = u.tasks.free!!.lines
    assertEquals(
      listOf("Refused by KIFF" to 3, "Held, then refused" to 1, "Reads" to 42, "Coach and Setup" to 3),
      lines,
    )
  }

  @Test fun aReasonKiffDoesNotNameCountsNothingAndAnUnknownFieldIsIgnored() {
    val u = KiffClient.json.decodeFromString<Usage>(
      """{"plan":"trial","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
          "tasks":{"counted":1,"included":0,"free":{"brand_new_reason":9}},"actions":{"counted":0,"included":0},"future":true}""",
    )
    assertTrue(u.tasks.free!!.lines.isEmpty())
    assertNull(u.tasks.left)
    assertFalse(u.tasks.usedUp)
  }

  // MARK: Words

  private fun usageOf(json: String): Usage = KiffClient.json.decodeFromString(json)
  private val period = """"period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"}"""

  @Test fun countsReadAsAnOfOrAsAPlainTotalWhereThereIsNoLimit() {
    assertEquals("14 of 25 tasks", UsageWords.count(14, 25, "tasks"))
    assertEquals("472 tasks", UsageWords.count(472, 0, "tasks"))
    assertEquals("1 task", UsageWords.tasks(1))
    assertEquals("8 tasks", UsageWords.tasks(8))
    assertEquals("0 tasks this period", UsageWords.tafCount(0))
  }

  @Test fun whatIsLeftSaysWhereItCameFromAndNothingWhereThereIsNoLimit() {
    assertEquals("21 tasks left · 25 included, 10 from packs", UsageWords.leftLine(usageOf(usage)))
    assertEquals("1 task left", UsageWords.leftLine(usageOf("""{"plan":"solo",$period,"tasks":{"counted":9,"included":10,"left":1}}""")))
    assertEquals("11 tasks left", UsageWords.leftLine(usageOf("""{"plan":"solo",$period,"tasks":{"counted":9,"included":20,"packs":0,"left":11}}""")))
    assertNull(UsageWords.leftLine(usageOf("""{"plan":"company",$period,"tasks":{"counted":472,"included":0}}""")))
  }

  @Test fun theCountsOwnerIsNamedWithThePlanWhereThePlanHasAName() {
    assertEquals("All tafs · Team", UsageWords.whose(usageOf(usage)))
    assertEquals("All tafs", UsageWords.whose(usageOf("""{"plan":"internal",$period}""")))
    assertEquals("Trial", UsageWords.planName("trial"))
    assertNull(UsageWords.planName(""))
  }

  @Test fun theNoticeSaysTasksUsedUpOrWhatIsLeftOfATrial() {
    assertEquals(
      "Your trial's tasks are used. Your tafs are paused until tasks are added.",
      UsageWords.notice(usageOf("""{"plan":"trial",$period,"tasks":{"counted":25,"included":25,"left":0}}""")),
    )
    assertEquals(
      "This period's tasks are used. Your tafs are paused until tasks are added or the next period starts.",
      UsageWords.notice(usageOf("""{"plan":"team",$period,"tasks":{"counted":25,"included":25,"left":0}}""")),
    )
    fun trial(json: String) = UsageWords.notice(usageOf("""{"plan":"trial",$period,"tasks":{"counted":14,"included":25,"left":11},"trial":$json}"""))
    assertEquals("Your trial: 5 days and 11 tasks left.", trial("""{"ends_at":"2026-11-02T00:00:00Z","days_left":5,"tasks_left":11}"""))
    assertEquals("Your trial: 1 day and 11 tasks left.", trial("""{"ends_at":"2026-11-02T00:00:00Z","days_left":1,"tasks_left":11}"""))
    assertEquals("Your trial has ended.", trial("""{"ends_at":"2026-10-20T00:00:00Z","days_left":0,"tasks_left":11}"""))
    assertEquals("Your trial hasn't started.", trial("""{"days_left":14,"tasks_left":25}"""))
    assertNull(UsageWords.notice(usageOf(usage)))
  }

  @Test fun whoBillsThePlanAndWhatOtherAgentsDid() {
    assertEquals("Billed by KIFF", UsageWords.billingLine(usageOf(usage)))
    assertEquals("Billed by Apple", UsageWords.billingLine(usageOf("""{"plan":"solo",$period,"billing":{"source":"apple"}}""")))
    assertEquals("Managed by your company's agreement", UsageWords.billingLine(usageOf("""{"plan":"x",$period,"billing":{"source":"contract"}}""")))
    assertNull(UsageWords.billingLine(usageOf("""{"plan":"trial",$period,"billing":{"source":""}}""")))
    assertNull(UsageWords.billingLine(usageOf("""{"plan":"trial",$period}""")))
    assertEquals("3 of 500 actions", UsageWords.othersLine(usageOf(usage)))
    assertNull("nothing counted: no line asking what 0 means", UsageWords.othersLine(usageOf("""{"plan":"trial",$period,"actions":{"counted":0,"included":500}}""")))
  }

  @Test fun aPacksLeftAndATasksToolReadInWords() {
    assertTrue(UsageWords.packLeft(UsagePack("rayo", "Rayo", 70, 60, Instant.parse("2027-10-22T00:00:00Z"))).startsWith("60 of 70 left, until "))
    assertEquals("5 of 5 left", UsageWords.packLeft(UsagePack("p", "P", 5, 5)))
    fun counted(tool: String, title: String?) = CountedTask("r", "a", tool, title, t0)
    assertEquals("Refund an order", UsageWords.toolName(counted("refund_order", "Refund an order")))
    assertEquals("Refund order", UsageWords.toolName(counted("refund_order", null)))
    assertEquals("Refund order", UsageWords.toolName(counted("refund-order", "")))
    assertTrue(UsageWords.periodEnds(Instant.parse("2026-11-22T12:00:00Z")).startsWith("This period ends "))
  }

  @Test fun aiUseSaysWhereTheTafsAskNext() {
    assertTrue(UsageWords.aiExplain(100).contains("At 100%, your tafs ask you before going on."))
    assertTrue(UsageWords.aiExplain(null).contains("At 100%"))
    assertTrue(UsageWords.aiExplain(150).contains("You said go on; your tafs ask you again at 150%."))
    assertTrue(UsageWords.aiExplain(100).endsWith("It's never billed."))
  }
}
