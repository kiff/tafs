@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.BriefRoutes
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Brief
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.MissionLog
import bot.tafs.app.model.SampleBrief
import bot.tafs.app.model.Words
import bot.tafs.app.push.Alert
import bot.tafs.app.push.AlertPayload
import bot.tafs.app.signing.ApprovalSigning
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Your brief" (Missions design v2.1 §7, docs/android-app.md, Your brief): `GET /v1/brief` read leniently in v2.1's field names,
 * drawn as sent; a recommendation is shown, never acted on; an answer opens where its own signed answer is given; Needs you stays
 * until there's a brief, and stays reachable under it. A fake tafs cloud and KIFF stand in.
 */
class BriefTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer: ApprovalSigning = softwareSigner(auth)
  private val t0 = "2026-10-28T09:00:00Z"

  private val liveBrief = """{"decisions":[{"id":"d1","question":"Pay 3 supplier bills due Friday, €1,240?","why":"All three match their orders.",
    "meanwhile":"these three wait.","answers":[{"words":"Pay the 3","answer":"approve","recommended":true},{"words":"Not now","answer":"refuse","decline":true}],
    "later":{"words":"Later"},"ref":{"kind":"hold","id":"ce_1","taf":"id_a-1"}}],"words":{"tab":"Your brief","title":"Your brief"}}"""

  private val held = """{"id":"ce_1","subject":"a-1","action":"create_refund","title":"Refund a payment","entity_id":"tc-ce_1","proposal_id":"prop_ce_1",
    "accounting_version":1,"breaches":[{"outcome":"per_action_exceeded","reason":"over","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
    "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},"created_at":"$t0","expires_at":"2099-01-01T00:00:00Z"}"""

  private fun reads(brief: String? = liveBrief, needsYou: String = "") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"id_a-1","name":"Bill pay","kiff_agent":"a-1","color":1,"state":"waiting","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]$needsYou}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    if (brief != null) transport.answer("GET /v1/brief", 200, brief)
  }

  private suspend fun kotlinx.coroutines.test.TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false, scope = backgroundScope).also { it.restore() }
  }

  private fun brief(json: String) = Brief.parse(json)

  // MARK: The live route: the switch is tafs cloud answering it

  @Test fun noRouteYetMeansNoBriefAndNeedsYouAsToday() = runTest {
    reads(brief = null)
    val m = model()
    assertEquals("asked once, with the refresh", 1, transport.requestsTo("GET /v1/brief").size)
    assertNull(m.state.value.brief)
    assertEquals(Words.NeedsYou.title, m.state.value.needsYouTabTitle)
    assertEquals(Words.NeedsYou.title, tabTitle(Tab.NeedsYou, m.state.value))
  }

  @Test fun onceTheRouteAnswersTheBriefShowsInNeedsYousPlace() = runTest {
    reads()
    val m = model()
    assertEquals(listOf("d1"), m.state.value.briefDecisions.map { it.id })
    assertEquals("Your brief", tabTitle(Tab.NeedsYou, m.state.value))
    assertEquals("the other tabs keep their names", Words.Record.title, tabTitle(Tab.Record, m.state.value))
  }

  @Test fun aBriefWithNothingToDrawOrThatFailsIsNoBrief() = runTest {
    reads(brief = """{"decisions":[],"done":[],"words":{"tab":"Your brief"}}""")
    val m = model()
    assertNull("nothing to draw: Needs you, as today", m.state.value.brief)
    transport.reset(); reads(brief = "[1,2]")
    m.loadBrief()
    assertNull("another shape: no brief, never Needs you hidden", m.state.value.brief)
    transport.reset(); reads(brief = null); transport.answer("GET /v1/brief", 500, """{"error":"down"}""")
    m.loadBrief()
    assertNull(m.state.value.brief)
  }

  @Test fun aFilterReadsTheBriefForThatLabel() = runTest {
    reads()
    val m = model()
    m.filterBrief("l money")
    assertEquals("l money", m.state.value.briefLabel)
    assertTrue(transport.requestsTo("GET /v1/brief").last().url.endsWith("/v1/brief?label=l%20money"))
    m.filterBrief(null)
    assertTrue(transport.requestsTo("GET /v1/brief").last().url.endsWith("/v1/brief"))
  }

  // MARK: Answering: opened where its own signed answer is given, never answered here

  @Test fun anAnswerOpensTheHeldCallOnItsOwnCardWithNothingChosenAndSignsNothing() = runTest {
    reads()
    val m = model()
    val decision = m.state.value.briefDecisions.single()
    val before = transport.requests.size
    m.answerBrief(decision, decision.answers[0])
    assertEquals("the held call opens on its own card, with no answer chosen", "hold:ce_1", m.state.value.briefOpen)
    assertEquals("the card it opens is KIFF's held call", "ce_1", m.state.value.held.single { it.kiffId == BriefRoutes.holdOf(m.state.value.briefOpen) }.kiffId)
    m.closeBriefOpen()
    m.answerBrief(decision, decision.answers[1])
    assertEquals("a decline too", "hold:ce_1", m.state.value.briefOpen)
    assertEquals("nothing is sent from the brief", before, transport.requests.size)
    assertTrue("no fingerprint is asked, nothing is signed", auth.asked.isEmpty() && auth.fingerprints.isEmpty())
    assertTrue(transport.requests.none { it.method != "GET" })
    assertEquals("the held call is still there, to answer on its card", 1, m.state.value.held.size)
    assertEquals("it stays until it's answered there", listOf("d1"), m.state.value.briefDecisions.map { it.id })
    m.closeBriefOpen()
    assertNull(m.state.value.briefOpen)
  }

  @Test fun theBriefsAnswerRoutingNeverSignsAnythingAndNeverPreselectsAnAnswer() {
    val decision = brief(liveBrief).decisions.single()
    // Whichever answer is tapped, the same destination: the answer is never carried to where it's given.
    val destinations = decision.answers.map { BriefRoutes.destination(decision, it) }.toSet()
    assertEquals(setOf("hold:ce_1"), destinations)
    decision.answers.forEach { a ->
      destinations.forEach { d -> assertFalse("$d carries no answer", d.contains(a.answer) || d.contains(a.words)) }
    }
    assertEquals("step:ho_1", BriefRoutes.destination(decision.copy(ref = Brief.Ref("step", "ho_1")), decision.answers[0]))
    assertEquals("ho_1", BriefRoutes.stepOf("step:ho_1"))
    assertEquals(BriefRoutes.NEEDS_YOU, BriefRoutes.destination(decision.copy(ref = Brief.Ref("proposal", "p1")), decision.answers[0]))
    assertEquals(BriefRoutes.NEEDS_YOU, BriefRoutes.destination(decision.copy(ref = Brief.Ref("connection", "g1")), decision.answers[0]))
    assertEquals(BriefRoutes.NEEDS_YOU, BriefRoutes.destination(decision.copy(ref = Brief.Ref("hold", "")), decision.answers[0]))

    // The brief's own code signs nothing, answers nothing and picks no answer: it only opens where the answer is given.
    val forbidden = listOf("signer", "sign(", "presigned", "answerNow(", "answer(call", "Answer.ApproveOnce", "Answer.Refuse", "approvalWindow", "kiff.answer")
    fun code(text: String) = text.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")
    val app = File("src/main/kotlin/bot/tafs/app/app/AppModel.kt").readText()
    val section = app.substring(app.indexOf("// MARK: Your brief"), app.indexOf("suspend fun missionLog("))
    val routes = File("src/main/kotlin/bot/tafs/app/app/BriefRoutes.kt").readText()
    val view = File("src/main/kotlin/bot/tafs/app/views/BriefView.kt").readText()
    for ((name, text) in listOf("AppModel's brief" to section, "BriefRoutes" to routes, "BriefView" to view)) {
      forbidden.forEach { assertFalse("$name has $it", code(text).contains(it)) }
    }
    // The only answer drawn from the brief's screen is the held call's own card, as Needs you draws it.
    assertTrue(view.contains("HeldCallCard(call, state, onAnswerHold)"))
  }

  // MARK: Needs you stays reachable

  @Test fun needsYouStaysReachableWheneverItHoldsMoreThanTheBrief() = runTest {
    assertEquals(Words.NeedsYou.count(count = 3), BriefRoutes.needsYouBeyondBrief(count = 3, decisions = 1))
    assertNull("the brief covers it all", BriefRoutes.needsYouBeyondBrief(count = 1, decisions = 1))
    assertNull(BriefRoutes.needsYouBeyondBrief(count = 0, decisions = 0))

    // tafs cloud counts 3 in Needs you, the brief names 1 and sends no rest line: Needs you's own count shows, with its link.
    reads(needsYou = ""","needs_you":{"count":3,"words":"3 need you."}""")
    val m = model()
    assertNull(m.state.value.brief!!.rest)
    assertEquals("3 need you.", m.state.value.briefNeedsYouLine)
    m.openNeedsYouFromBrief()
    assertEquals(BriefRoutes.NEEDS_YOU, m.state.value.briefOpen)

  }

  @Test fun theBriefsDecisionsCoveringNeedsYouShowNoExtraLine() = runTest {
    reads()
    val m = model()
    // One held call, one decision about it: the brief covers it all.
    assertEquals(1, m.state.value.needsYouCount)
    assertNull(m.state.value.briefNeedsYouLine)
  }

  // MARK: Later

  @Test fun laterMovesTheCardOnlyOnceTafsCloudTookIt() = runTest {
    reads()
    transport.answer("POST /v1/brief/decisions/d1/later", 500, """{"error":"later_failed","words":"Not now."}""")
    transport.answer("POST /v1/brief/decisions/d1/later", 200, "{}")
    val m = model()
    val decision = m.state.value.briefDecisions.single()
    assertFalse(m.laterBrief(decision))
    assertEquals("refused: it stays, to answer or try again", listOf("d1"), m.state.value.briefDecisions.map { it.id })
    assertEquals("Not now.", m.state.value.lastError)
    assertTrue(m.laterBrief(decision))
    assertTrue("taken: it moves to the next brief", m.state.value.briefDecisions.isEmpty())
    assertEquals(listOf("/v1/brief/decisions/d1/later", "/v1/brief/decisions/d1/later"), transport.requestsTo("POST /v1/brief/decisions/d1/later").map { java.net.URI(it.url).path })
    assertTrue("Later signs nothing", auth.asked.isEmpty())
  }

  @Test fun laterIsNeverOfferedOnAnUrgentDecision() = runTest {
    val urgent = brief(
      """{"decisions":[{"id":"u1","question":"Pay Mercier €2,100 before 12:00?","answers":[{"words":"Pay","answer":"approve"}],"urgent":true,
        "later":{"words":"Later"},"cant_wait":"This can't wait.","ref":{"kind":"hold","id":"ce_1"}}]}""",
    ).decisions.single()
    assertEquals("sent, but never drawn", "Later", urgent.later)
    assertNull(urgent.laterShown)

    reads()
    val m = model()
    assertFalse("the model refuses it too", m.laterBrief(urgent))
    assertTrue(transport.requestsTo("POST /v1/brief/decisions/u1/later").isEmpty())

    val sample = SampleBrief.brief(urgent = true)
    val first = sample.decisions.first()
    assertTrue("the urgent one leads", first.urgent)
    assertNull(first.later)
    assertNotNull(first.cantWait)
    assertFalse("only with the urgent extra", SampleBrief.brief().decisions.any { it.urgent })
    // The view draws Later from the decision's own rule, never from what was sent.
    assertTrue(File("src/main/kotlin/bot/tafs/app/views/BriefView.kt").readText().contains("val later = decision.laterShown"))
  }

  @Test fun theSamplesBriefShowsOnlyForTheScreenshotsAndPutsOffHere() = runTest {
    val m = testModel()
    assertEquals(Mode.sample, m.state.value.mode)
    assertNull("the sample walk keeps Needs you", m.state.value.brief)
    m.showBriefForScreenshots(label = SampleBrief.MONEY)
    assertEquals(listOf("Money"), m.state.value.brief!!.done.mapNotNull { it.mission })
    val pay = m.state.value.briefDecisions.first()
    assertTrue(m.laterBrief(pay))
    assertFalse(m.state.value.briefDecisions.any { it.id == pay.id })
    // An answer on the sample sets its card aside: there is nothing to open.
    val remind = m.state.value.briefDecisions.first()
    m.answerBrief(remind, remind.answers[0])
    assertTrue(m.state.value.briefDecisions.isEmpty())
    assertNull(m.state.value.briefOpen)
  }

  // MARK: Reading v2.1

  @Test fun theBriefReadsV21sFieldsAsSent() {
    val b = brief(
      """{"at":"2026-11-14T08:00:00+01:00","next_at":"2026-11-14T18:00:00+01:00",
       "decisions":[{"id":"d1","question":"Pay 3 supplier bills due Friday, €1,240?",
                     "answers":[{"words":"Pay the 3","answer":"approve","recommended":true},{"words":"Not now","answer":"reject","decline":true}],
                     "why":"All three match their orders.","meanwhile":"these three wait.","deadline":"by Fri 12:00","urgent":false,
                     "later":{"words":"Later"},"ref":{"kind":"hold","id":"h1","taf":"t1"}},
                    {"id":"d2","question":"Pay Mercier €2,100 before 12:00?","answers":[{"words":"Pay €2,100","answer":"approve","recommended":true}],
                     "why":"It matches order 0388.","meanwhile":"held.","urgent":true,"cant_wait":"This can't wait: it expires at 12:00.","ref":{"kind":"hold","id":"h2","taf":"t1"}},
                    {"id":"d3","question":"","answers":[]}, 7],
       "more":{"count":2,"words":"2 more decisions"},
       "done":[{"mission":"Money","cases":[{"name":"Brio Bikes, invoice 0139","words":"paid Thursday · closed","state":"closed"},{"words":"x","state":"waiting","next_check":"Friday"}],"value":"€3,400 due back","log":"m1"},{"cases":[]}],
       "next_move":{"words":"Prepare an escalation summary?","cases":["0142"],"start":"Start","dismiss":"Dismiss"},
       "rest":{"count":14,"words":"14 things wait that need nothing from you now"},
       "credits":{"words":"Today: 38 credits."},
       "labels":[{"id":"money","name":"Money","color":2,"count":5,"waiting":true},{"id":"","name":"x"}],
       "words":{"tab":"Your brief","title":"Your brief","decisions":"Decisions · 2","next_move":"Next move","recommends":"Coach recommends"},
       "unknown":{"a":1}}""",
    )
    assertEquals("an empty question, no answers, or another shape: left out", listOf("d1", "d2"), b.decisions.map { it.id })
    val d1 = b.decisions.first()
    assertEquals("Pay the 3", d1.recommended?.words)
    assertTrue(d1.answers.last().decline)
    assertEquals("Later", d1.laterShown)
    assertEquals(Brief.Ref("hold", "h1", "t1"), d1.ref)
    val d2 = b.decisions[1]
    assertTrue(d2.urgent)
    assertNull("it can't be put off", d2.laterShown)
    assertEquals("This can't wait: it expires at 12:00.", d2.cantWait)
    assertEquals("2 more decisions", b.more?.words)
    assertEquals("a group with no cases: left out", 1, b.done.size)
    assertTrue(b.done[0].cases[0].closed)
    assertTrue(b.done[0].cases[1].waiting)
    assertEquals("Friday", b.done[0].cases[1].nextCheck)
    assertEquals("Start", b.nextMove?.start)
    assertEquals(listOf("0142"), b.nextMove?.cases)
    assertEquals(14, b.rest?.count)
    assertEquals("Today: 38 credits.", b.credits)
    assertEquals(listOf("money"), b.labels.map { it.id })
    assertEquals("a yes from an earlier draft reads as one waiting", 1, b.labels[0].waiting)
    assertNull("a colour of another shape is none", b.labels[0].color)
    assertEquals("Next move", b.words.nextMove)
    assertEquals("2026-11-14T18:00:00+01:00", b.nextAt)
  }

  @Test fun everyHeadingIsTafsCloudsAndTheAppsWordsOnlyStandIn() {
    val sent = brief("""{"words":{"urgent":"Dringend","all":"Alle","decisions":"Entscheidungen · 2"}}""").words
    assertEquals("Dringend", sent.urgentShown)
    assertEquals("Entscheidungen · 2", sent.decisionsShown)
    assertEquals("Alle", sent.all)
    val none = brief("{}").words
    assertEquals(Words.Brief.urgent, none.urgentShown)
    assertEquals(Words.Brief.title, none.titleShown)
    assertEquals("the tab's word stands in for the title", "Your brief", brief("""{"words":{"tab":"Your brief"}}""").words.titleShown)
  }

  /** With no why, no answer is drawn as recommended: they show as equals. */
  @Test fun aRecommendationNeedsItsWhy() {
    val b = brief("""{"decisions":[{"id":"d","question":"q","answers":[{"words":"Yes","answer":"approve","recommended":true},{"words":"No","answer":"reject"}],"meanwhile":"m","ref":{}}]}""")
    assertNull(b.decisions.single().recommended)
  }

  /** The sample's brief filters as tafs cloud does: Money keeps its decisions and cases, drops Support's, and its rest line counts only its own. */
  @Test fun theSampleFiltersByLabel() {
    val all = SampleBrief.brief()
    val money = SampleBrief.brief(label = SampleBrief.MONEY)
    assertEquals(listOf("Money", "Support"), all.done.mapNotNull { it.mission })
    assertEquals(listOf("Money"), money.done.mapNotNull { it.mission })
    assertEquals(14, all.rest?.count)
    assertEquals(9, money.rest?.count)
    assertEquals(2, all.decisions.size)
    assertTrue("each with Coach's pick and its why", all.decisions.all { it.recommended != null })
  }

  // MARK: A mission's log

  /** A mission's log reads `{at, by, kind, words, evidence, value?}`; a removed taf's entry stays, marked. */
  @Test fun theLogReadsItsItemsAndKeepsARemovedTafsEntries() {
    val log = MissionLog.parse(
      """{"items":[{"at":"2026-11-14T07:02:00+01:00","by":"taf","kind":"delivered","words":"12 bills sorted.","evidence":[{"kind":"run","id":"r1","words":"run"},{"kind":"x"}],"value":"€4,120 sorted"},
                {"at":"2026-11-13T09:12:00+01:00","by":"taf","kind":"delivered","words":"3 quotes drafted.","removed":true},{"words":""}],
       "before":"c1"}""",
    )
    assertEquals(2, log.items.size)
    assertEquals(listOf("run"), log.items[0].evidence.map { it.words })
    assertEquals("€4,120 sorted", log.items[0].value)
    assertTrue(log.items[1].removed)
    assertEquals("c1", log.before)
    assertTrue(SampleBrief.log().items.any { it.removed })
  }

  /** The log as tafs cloud serves it: `{items, before?}`, each item `{id, at, by: {kind, name?}, kind, words, evidence: [{kind, id}], removed?}`. */
  @Test fun theLogReadsTafsCloudsShape() {
    val log = MissionLog.parse(
      """{"items":[
        {"id":"log_r2","at":"2026-11-16T07:10:00Z","by":{"kind":"taf","name":"Bill pay"},"kind":"delivered","words":"6 done · 1 held for you","evidence":[{"kind":"run","id":"r2"},{"kind":"item","id":"inv_0412"}]},
        {"id":"log_r1","at":"2026-11-16T06:10:00Z","by":{"kind":"taf"},"kind":"delivered","words":"3 done","evidence":[{"kind":"run","id":"r1"}],"removed":true},
        {"id":"log_n1","at":"2026-11-16T06:00:00Z","by":{"kind":"lead"},"kind":"note","words":"Started the month's close.","evidence":[]}
      ],"before":"log_n1"}""",
    )
    assertEquals(listOf("log_r2", "log_r1", "log_n1"), log.items.map { it.key })
    assertEquals("a removed taf has no name; the lead is Coach", listOf("Bill pay", Words.NeedsYou.aTaf, Words.Coach.title), log.items.map { it.byWords })
    assertEquals(listOf(false, true, false), log.items.map { it.removed })
    assertTrue("refs only, never shown as data", log.items[0].evidence.isEmpty())
    assertEquals("log_n1", log.before)
  }

  @Test fun theLogIsReadFromTafsCloudAndItsDaysKeepItsOrder() = runTest {
    reads()
    transport.answer("GET /v1/missions/m 1/log", 200, """{"items":[{"at":"2026-11-14T07:10:00+01:00","by":"taf","words":"a"},{"at":"2026-11-14T07:00:00+01:00","by":"taf","words":"b"}]}""")
    val m = model()
    val log = m.missionLog("m 1")!!
    assertTrue(transport.requestsTo("GET /v1/missions/m 1/log").single().url.endsWith("/v1/missions/m%201/log"))
    assertEquals(listOf(listOf("a", "b")), bot.tafs.app.views.logDays(log.items).map { (_, items) -> items.map { it.words } })
    assertNull("a log that can't be read is none", m.missionLog("gone"))
  }

  // MARK: Alerts

  /** A brief's or an urgent push opens the brief, and only those; one that names where it opens goes there. */
  @Test fun theBriefsAndTheUrgentAlertOpenTheBrief() {
    val urgent = AlertPayload.parse(mapOf("kind" to "urgent", "title" to "Pay Mercier before 12:00", "body" to "It expires before the next brief."))
    assertTrue(urgent is Alert.Show)
    urgent as Alert.Show
    assertEquals(AlertPayload.BRIEF, urgent.open)
    assertTrue(urgent.brief)
    assertTrue(urgent.loud)
    assertEquals(AlertPayload.BRIEF, (AlertPayload.parse(mapOf("kind" to "brief", "level" to "quiet")) as Alert.Show).open)
    assertFalse((AlertPayload.parse(mapOf("kind" to "brief", "level" to "quiet")) as Alert.Show).loud)
    assertEquals("an alert's own `open` still wins", AlertPayload.USAGE, (AlertPayload.parse(mapOf("kind" to "brief", "open" to "usage")) as Alert.Show).open)
    val hold = AlertPayload.parse(mapOf("kind" to "alert", "hold_id" to "ce_1")) as Alert.Show
    assertEquals("a held call's alert opens Needs you, as it says", AlertPayload.NEEDS_YOU, hold.open)
    assertFalse(hold.brief)
    // Locked, a brief's alert says only that something needs the person, as every alert does.
    assertEquals(Words.Alert.needsYou, urgent.locked().title)
    assertEquals("", urgent.locked().body)
  }

  @Test fun aHeldCallsAlertOpensNeedsYouOverTheBriefOnlyWhenThereIsOne() = runTest {
    reads(brief = null)
    val m = model()
    m.openNeedsYouFromBrief()
    assertNull("no brief: nothing to open over", m.state.value.briefOpen)
    transport.answer("GET /v1/brief", 200, liveBrief)
    m.loadBrief()
    m.openNeedsYouFromBrief()
    assertEquals(BriefRoutes.NEEDS_YOU, m.state.value.briefOpen)
    // The brief gone with a read: nothing stays open over it.
    transport.reset(); reads(brief = null)
    m.loadBrief()
    assertNull(m.state.value.brief)
    assertNull(m.state.value.briefOpen)
  }

  /** Needs you stays, unchanged, until there's a brief: the tab draws it whenever there's none. */
  @Test fun needsYouStaysUntilThereIsABrief() {
    val state = AppState()
    assertNull(state.brief)
    assertEquals(Words.NeedsYou.title, state.needsYouTabTitle)
    val view = File("src/main/kotlin/bot/tafs/app/views/BriefView.kt").readText()
    assertTrue("no brief: Needs you, as today", view.contains("if (brief == null) {\n    needsYou()\n    return\n  }"))
    val main = File("src/main/kotlin/bot/tafs/app/MainActivity.kt").readText()
    assertTrue(main.contains("Tab.NeedsYou -> BriefTab(state, briefActions"))
  }
}
