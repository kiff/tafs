@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.BriefRoutes
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Brief
import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.MissionLogPage
import bot.tafs.app.kiff.MissionLogs
import bot.tafs.app.model.SampleBrief
import bot.tafs.app.model.Words
import bot.tafs.app.push.Alert
import bot.tafs.app.push.AlertChannel
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
    "later":{"words":"Later"},"ref":{"kind":"hold","id":"ce_1","taf":"id_a-1"}}],"words":{"title":"Your brief"}}"""

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

  @Test fun openTheTafOpensTheTafsPageAndSendsNothing() = runTest {
    reads()
    val m = model()
    val check = m.state.value.briefDecisions.single().copy(
      id = "check:id_a-1", ref = Brief.Ref("check", "id_a-1", "id_a-1"), answers = listOf(Brief.Answer(words = "Open the taf", answer = "open")),
    )
    val before = transport.requests.size
    m.answerBrief(check, check.answers[0])
    assertEquals("the taf's page opens, on the tafs tab", bot.tafs.app.push.AlertPayload.taf("id_a-1"), m.state.value.requestedOpen)
    assertNull("nothing is drawn over the brief", m.state.value.briefOpen)
    assertEquals("nothing is sent from the brief", before, transport.requests.size)
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
    // A taf whose scheduled check couldn't read: "Open the taf" opens its page, by tafs cloud's id; with no taf, Needs you.
    assertEquals("refunds-1", BriefRoutes.tafOf(BriefRoutes.destination(decision.copy(ref = Brief.Ref("check", "refunds-1", "refunds-1")), decision.answers[0])))
    assertEquals(BriefRoutes.NEEDS_YOU, BriefRoutes.destination(decision.copy(ref = Brief.Ref("check", "refunds-1", "")), decision.answers[0]))
    assertNull(BriefRoutes.tafOf("step:ho_1"))
    assertNull(BriefRoutes.tafOf(BriefRoutes.NEEDS_YOU))

    // The brief's own code signs nothing, answers nothing and picks no answer: it only opens where the answer is given.
    val forbidden = listOf("signer", "sign(", "presigned", "answerNow(", "answer(call", "Answer.ApproveOnce", "Answer.Refuse", "approvalWindow", "kiff.answer")
    fun code(text: String) = text.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")
    val app = File("src/main/kotlin/bot/tafs/app/app/AppModel.kt").readText()
    val start = app.indexOf("// MARK: Your brief")
    val section = app.substring(start, app.indexOf("fun askCoachAboutMission(", start))
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
    fun d(kind: String, id: String) = Brief.Decision(id = "$kind:$id", ref = Brief.Ref(kind, id))
    val two = listOf(d("hold", "h1"), d("step", "s1"))
    assertNull("the brief shows it all", BriefRoutes.needsYouBeyondBrief(2, held = listOf("h1"), steps = listOf("s1"), others = 0, decisions = two))
    assertNull(BriefRoutes.needsYouBeyondBrief(0, held = emptyList(), steps = emptyList(), others = 0, decisions = emptyList()))
    // As many decisions as Needs you holds, but not the same ones: Later moved h2 out, the brief shows a step instead.
    assertEquals(
      "equal counts, different items",
      Words.NeedsYou.count(count = 1),
      BriefRoutes.needsYouBeyondBrief(2, held = listOf("h1", "h2"), steps = emptyList(), others = 0, decisions = listOf(d("hold", "h1"), d("step", "s9"))),
    )
    assertEquals(
      "the same id as another kind is not the same item",
      Words.NeedsYou.count(count = 1),
      BriefRoutes.needsYouBeyondBrief(1, held = listOf("x1"), steps = emptyList(), others = 0, decisions = listOf(d("step", "x1"))),
    )
    assertEquals(
      "a Coach chat waiting is never in the brief",
      Words.NeedsYou.count(count = 1),
      BriefRoutes.needsYouBeyondBrief(3, held = listOf("h1"), steps = listOf("s1"), others = 1, decisions = two),
    )

    // tafs cloud counts 3 in Needs you, the brief shows the 1 the app lists and sends no rest line: the other 2 show, with the link.
    reads(needsYou = ""","needs_you":{"count":3,"words":"3 need you."}""")
    val m = model()
    assertNull(m.state.value.brief!!.rest)
    assertEquals(Words.NeedsYou.count(count = 2), m.state.value.briefNeedsYouLine)
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

  /** The route's ids are `hold:<id>` and `step:<handoff>`, read raw: the `:` goes as is, anything else is escaped. */
  /** `deadline` is a time for machines; `deadline_words` is the line the card draws (kiff/tafs#1192). */
  @Test fun theDeadlineDrawnIsTafsCloudsWordsNeverTheTime() {
    fun decision(extra: String) = brief(
      """{"decisions":[{"id":"step:s1","question":"Send the signed form?","answers":[{"words":"Done","answer":"done"}],""" +
        """"meanwhile":"The run waits.","urgent":false,$extra"ref":{"kind":"step","id":"s1","taf":"t1"}}]}""",
    ).decisions.single()
    assertEquals("by Fri 12:00", decision(""""deadline":"2026-10-29T13:00:00.000Z","deadline_words":"by Fri 12:00",""").deadline)
    assertEquals("bis 14:00", decision(""""deadline_words":"bis 14:00",""").deadline)
    assertNull("a time alone draws nothing", decision(""""deadline":"2026-10-29T13:00:00.000Z",""").deadline)
  }

  @Test fun laterSendsTheDecisionsColonAsIs() = runTest {
    transport.answer("POST /v1/brief/decisions/hold:ce_1/later", 200, "{}")
    transport.answer("POST /v1/brief/decisions/step:a b/later", 200, "{}")
    val cloud = TafsCloud(transport, token = "at_1")
    cloud.briefLater("hold:ce_1")
    cloud.briefLater("step:a b")
    assertEquals(
      listOf("/v1/brief/decisions/hold:ce_1/later", "/v1/brief/decisions/step:a%20b/later"),
      transport.requests.filter { it.method == "POST" }.map { java.net.URI(it.url).rawPath },
    )
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
    assertEquals(listOf("Parcels"), m.state.value.brief!!.done.mapNotNull { it.mission })
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
                     "why":"All three match their orders.","meanwhile":"these three wait.","deadline":"2026-11-14T11:00:00.000Z","deadline_words":"by Fri 12:00","urgent":false,
                     "later":{"words":"Later"},"ref":{"kind":"hold","id":"h1","taf":"t1"}},
                    {"id":"d2","question":"Pay Mercier €2,100 before 12:00?","answers":[{"words":"Pay €2,100","answer":"approve","recommended":true}],
                     "why":"It matches order 0388.","meanwhile":"held.","urgent":true,"now_why":"This can't wait: it expires at 12:00.","ref":{"kind":"hold","id":"h2","taf":"t1"}},
                    {"id":"d3","question":"","answers":[]}, 7],
       "more":{"count":2,"words":"2 more decisions"},
       "done":[{"mission":"Money","cases":[{"name":"Brio Bikes, invoice 0139","words":"paid Thursday · closed","state":"closed"},{"words":"x","state":"waiting","next_check":"Friday"}],"value":"€3,400 due back","log":"m1"},{"cases":[]}],
       "next_move":{"words":"Prepare an escalation summary?","cases":["0142"],"start":"Start","dismiss":"Dismiss"},
       "rest":{"count":14,"words":"14 things wait that need nothing from you now"},
       "credits":{"words":"Today: 38 credits."},
       "labels":[{"id":"money","name":"Money","color":2,"count":5,"waiting":true},{"id":"","name":"x"}],
       "words":{"title":"Your brief","decisions":"Decisions · 2","next_move":"Next move","recommends":"Coach recommends"},
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

  /** The body as tafs cloud's `GET /v1/brief` serves it (`docs/api.md`): `words.title` names the tab, `now_why` the urgent card's reason. */
  @Test fun theRoutesOwnShapeReadsWithNothingLost() {
    val b = brief(
      """{"at":"2026-11-16T08:00:00+01:00","next_at":"2026-11-16T18:00:00+01:00",""" +
        """"decisions":[{"id":"hold:h1","question":"Refund order 0388?","answers":[{"words":"Approve","answer":"approve","recommended":false},""" +
        """{"words":"Refuse","answer":"refuse","recommended":false}],"meanwhile":"KIFF holds it.","deadline":"2026-11-16T12:00:00+01:00",""" +
        """"urgent":true,"now_why":"It expires at 12:00, before the next brief.","ref":{"kind":"hold","id":"h1","taf":"t1"}},""" +
        """{"id":"step:s1","question":"Send the signed form?","answers":[{"words":"Done","answer":"done","recommended":false}],""" +
        """"meanwhile":"The run waits.","urgent":false,"later":{"words":"Later"},"ref":{"kind":"step","id":"s1","taf":"t1"}}],""" +
        """"more":{"count":2,"words":"2 more decisions."},"done":[],"rest":{"count":4,"words":"4 in Needs you."},""" +
        """"labels":[{"id":"money","name":"Money","color":"green","count":3,"waiting":1}],""" +
        """"words":{"title":"Your brief","decisions":"Decisions · 2","done":"Done since last evening","rest":"Everything else"}}""",
    )
    assertEquals("Your brief", b.words.titleShown)
    assertEquals(listOf("hold:h1", "step:s1"), b.decisions.map { it.id })
    assertEquals("It expires at 12:00, before the next brief.", b.decisions[0].cantWait)
    assertNull("a time with no words to draw it is no deadline line, never the raw time", b.decisions[0].deadline)
    assertNull("an urgent decision offers no Later", b.decisions[0].later)
    assertEquals("Later", b.decisions[1].later)
    assertEquals(Brief.Ref("step", "s1", "t1"), b.decisions[1].ref)
    assertEquals(2, b.more?.count)
    assertEquals(4, b.rest?.count)
    assertEquals(1, b.labels.single().waiting)
    assertEquals("Decisions · 2", b.words.decisionsShown)
  }

  /** Coach's chats that wait on the person, in tafs cloud's words: read when there are some, and never at 0 or with no words. */
  @Test fun theCoachChatsThatWaitAreReadOnlyWhenThereAreSome() {
    val b = brief("""{"decisions":[],"coach_waiting":{"count":17,"words":"17 Coach chats wait for your answer."}}""")
    assertEquals(Brief.Counted(17, "17 Coach chats wait for your answer."), b.coachWaiting)
    assertNull(brief("""{"coach_waiting":{"count":0,"words":"0 Coach chats wait for your answer."}}""").coachWaiting)
    assertNull(brief("""{"coach_waiting":{"count":3}}""").coachWaiting)
    assertNull(brief("""{"coach_waiting":"17"}""").coachWaiting)
    assertNull(brief("""{}""").coachWaiting)
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
    assertEquals(listOf("Parcels", "Leads", "Support"), all.done.mapNotNull { it.mission })
    assertEquals(listOf("Parcels"), money.done.mapNotNull { it.mission })
    assertEquals(14, all.rest?.count)
    assertEquals(9, money.rest?.count)
    assertEquals(2, all.decisions.size)
    assertTrue("each with Coach's pick and its why", all.decisions.all { it.recommended != null })
  }

  // MARK: A mission's log: the one the tafs list opens (MissionLogView), from Done's Evidence

  /** A page reads `{items, before}` leniently; what says nothing is left out, and a removed taf's entry stays, marked. */
  @Test fun theLogReadsItsItemsAndKeepsARemovedTafsEntries() {
    val page = KiffClient.json.decodeFromString(
      MissionLogPage.serializer(),
      """{"items":[{"id":"l2","at":"2026-11-14T06:02:00Z","by":{"kind":"taf","name":"Bill pay"},"kind":"delivered","words":"12 bills sorted.",
                   "evidence":[{"kind":"run","id":"r1","words":"run"},{"kind":"hold","id":"h1"}],"value":"€4,120 sorted"},
                  {"id":"l1","at":"2026-11-13T08:12:00Z","by":{"kind":"taf"},"kind":"delivered","words":"3 quotes drafted.","removed":true},{"id":"l0","words":""}],
       "before":"c1"}""",
    )
    val drawn = MissionLogs.drawn(listOf(page))
    assertEquals(listOf("l2", "l1"), drawn.map { it.id })
    assertEquals("tafs cloud's words, else the evidence's kind", listOf("run", "hold"), drawn[0].evidence.map { MissionLogs.evidenceWords(it) })
    assertEquals("€4,120 sorted", drawn[0].value)
    assertTrue(drawn[1].removed)
    assertNull("a removed taf has no name", MissionLogs.byName(drawn[1]))
    assertEquals("c1", page.before)
  }

  /** The sample's log, for the screenshots' Evidence: one page of the same shape, newest first, a removed taf's entry kept. */
  @Test fun theSamplesLogIsAPageOfTheMissionsLog() = runTest {
    val log = SampleBrief.log()
    assertEquals(5, MissionLogs.drawn(listOf(log)).size)
    assertTrue(log.items.any { it.removed })
    assertEquals(listOf("Coach", "Late parcels → Support replies", "Late parcels", "You", "Quotes"), log.items.map { MissionLogs.byName(it) })
    assertNull("one page, no more", log.before)
    val m = testModel()
    assertNull("the sample walk has no log", m.missionLog(SampleBrief.MONEY))
    m.showBriefForScreenshots()
    assertEquals(log, m.missionLog(SampleBrief.MONEY))
    assertNull(m.missionLog(SampleBrief.MONEY, before = "s1"))
  }

  @Test fun doneOpensTheMissionsOwnLogByItsId() = runTest {
    reads()
    transport.answer("GET /v1/missions/m 1/log", 200, """{"items":[{"id":"a","at":"2026-11-14T06:10:00Z","by":{"kind":"taf","name":"Bill pay"},"words":"a"}]}""")
    val m = model()
    assertEquals(listOf("a"), m.missionLog("m 1")!!.items.map { it.id })
    assertTrue(transport.requestsTo("GET /v1/missions/m 1/log").single().url.endsWith("/v1/missions/m%201/log"))
    assertNull("a log that can't be read is none: the view offers Try again", m.missionLog("gone"))
    val view = File("src/main/kotlin/bot/tafs/app/views/BriefView.kt").readText()
    assertTrue("one log view, the tafs list's", view.contains("MissionLogView(HomeLabelMission(id = shownLog[0], name = shownLog[1])"))
    assertFalse(view.contains("fun MissionLogView"))
  }

  @Test fun theBriefsLabelsCarryTheirMission() {
    val label = brief("""{"labels":[{"id":"money","name":"Money","mission":{"id":"m_1","name":"Money","goal":"Run the money side"}},{"id":"ops","name":"Ops","mission":{"name":"x"}}]}""").labels
    assertEquals("m_1", label[0].mission?.id)
    assertNull("a mission with no id is none", label[1].mission)
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

  /**
   * As tafs cloud sends them (kiff/tafs#1189): `kind: alert` with `brief_kind`. "Your brief is ready" opens the brief, quietly,
   * one at a time; a held call's alert is `urgent` with its decision, opens Needs you as it says, and reads the brief again.
   */
  @Test fun theAlertsBriefKindIsReadAsTafsCloudSendsIt() {
    val ready = AlertPayload.parse(mapOf("kind" to "alert", "open" to "brief", "brief_kind" to "brief", "title" to "Your brief", "body" to "Your 08:00 brief is ready.", "level" to "quiet", "at" to "2026-11-16T07:00:00Z")) as Alert.Show
    assertEquals(AlertPayload.BRIEF, ready.open)
    assertTrue(ready.brief)
    assertFalse(ready.loud)
    assertEquals("one brief's alert replaces the last", AlertPayload.BRIEF, ready.tag)
    val held = AlertPayload.parse(mapOf("kind" to "alert", "open" to "needs-you", "hold_id" to "ce_1", "brief_kind" to "urgent", "decision" to "hold:ce_1", "level" to "interrupt")) as Alert.Show
    assertEquals(AlertPayload.NEEDS_YOU, held.open)
    assertTrue("the brief is read again", held.brief)
    assertTrue(held.loud)
    assertEquals("ce_1", held.tag)
    val other = AlertPayload.parse(mapOf("kind" to "alert", "open" to "usage", "brief_kind" to "something-newer")) as Alert.Show
    assertFalse("a brief_kind it doesn't know is no brief's", other.brief)
  }

  /**
   * Each kind on its own channel, so the person can set them apart: a held call stays on Needs you's (its settings kept, though
   * tafs cloud marks it urgent), an urgent one that isn't a held call goes on Urgent, Your brief's on Briefs, the rest by sound.
   */
  @Test fun eachAlertGoesOnItsOwnChannel() {
    fun channel(vararg pairs: Pair<String, String>) = (AlertPayload.parse(mapOf(*pairs)) as Alert.Show).channel
    assertEquals(AlertChannel.NEEDS_YOU, channel("kind" to "alert", "hold_id" to "ce_1", "brief_kind" to "urgent", "decision" to "hold:ce_1", "level" to "interrupt"))
    assertEquals(AlertChannel.NEEDS_YOU, channel("kind" to "alert", "hold_id" to "ce_1", "level" to "interrupt"))
    assertEquals(AlertChannel.URGENT, channel("kind" to "alert", "open" to "brief", "brief_kind" to "urgent", "level" to "interrupt"))
    assertEquals(AlertChannel.BRIEFS, channel("kind" to "alert", "open" to "brief", "brief_kind" to "brief", "level" to "quiet"))
    assertEquals(AlertChannel.OTHER, channel("kind" to "alert", "open" to "usage", "level" to "quiet"))
    assertEquals(AlertChannel.NEEDS_YOU, channel("kind" to "alert", "open" to "needs-you"))
    assertEquals("an earlier draft's kinds read the same", AlertChannel.BRIEFS, channel("kind" to "brief", "level" to "quiet"))
    assertEquals(AlertChannel.URGENT, channel("kind" to "urgent"))
    // Locked, the public version goes on the same channel, so it sounds (or doesn't) as the alert would.
    val urgent = AlertPayload.parse(mapOf("kind" to "alert", "brief_kind" to "urgent", "title" to "Pay before 12:00")) as Alert.Show
    assertEquals(AlertChannel.URGENT, urgent.locked().channel)
    // The ids Needs you's and the quiet channel always had stay, so the settings people gave them hold.
    assertEquals("holds", bot.tafs.app.push.AlertNotifier.idOf(AlertChannel.NEEDS_YOU))
    assertEquals("quiet", bot.tafs.app.push.AlertNotifier.idOf(AlertChannel.OTHER))
    // Your brief's channel is a new one, which sounds: the first, quiet one can't be raised, so it's replaced.
    assertEquals("brief", bot.tafs.app.push.AlertNotifier.idOf(AlertChannel.BRIEFS))
    assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, bot.tafs.app.push.AlertNotifier.BRIEFS_IMPORTANCE)
    assertEquals(listOf(true, true, false, false), AlertChannel.entries.map { bot.tafs.app.push.AlertNotifier.interrupts(it) })
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
    val view = File("src/main/kotlin/bot/tafs/app/views/BriefView.kt").readText().replace("
", "
")
    assertTrue("no brief: Needs you, as today", view.contains("if (brief == null) {\n    needsYou()\n    return\n  }"))
    val main = File("src/main/kotlin/bot/tafs/app/MainActivity.kt").readText()
    assertTrue(main.contains("Tab.NeedsYou -> BriefTab(state, briefActions"))
  }
}
