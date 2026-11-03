package bot.tafs.app

import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.ConnectStart
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KnobWire
import bot.tafs.app.model.KnobRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The knob API as tafs cloud publishes it: the settled dial (When · What · Where around its Why) and Connect apps, as sent. */
class KnobWireTest {
  // The dial's data as tafs cloud sends it: Refunds, with a Slack door and an app's action from one connection.
  private val dial = """{
    "version": 7,
    "rings": [{"key": "wakes", "name": "When"}, {"key": "what", "name": "What"}, {"key": "results", "name": "Where"}],
    "why": "Refund double charges",
    "does": {"label": "Refund", "line": "Refunds a payment in Stripe, within its Card."},
    "wakes": [
      {"id": "trg_1", "kind": "door", "door": "door_1", "label": "Email", "words": "Wakes on email to refunds@yourshop.com", "on": true},
      {"id": "trg_2", "kind": "door", "door": "door_s", "grant": "g_slack", "label": "Slack", "words": "Messages in #refunds start a request.", "state": "Open", "tap": "Tap to close"},
      {"id": "trg_3", "kind": "schedule", "label": "Weekdays", "words": "Weekdays at 09:00",
       "size": {"label": "Time", "index": 1, "choices": [{"label": "08:00", "setting": {"n": 8}}, {"label": "09:00", "setting": {"n": 9}}, {"label": "12:00", "setting": {"n": 12}}]}}
    ],
    "what": [{"id": "s1", "step": 1, "label": "Check", "words": "Looks up the charge"}, {"id": "s2", "step": 2, "label": "Refund", "words": "Refunds it, once"}],
    "results": [{"id": "res_1", "place": "door_reply", "label": "Sender", "words": "Replies to whoever wrote"},
                {"id": "res_2", "place": "action", "grant": "g_slack", "label": "#sales", "words": "Posts in #sales", "on": false}],
    "connections": [{"grant": "g_slack", "name": "Slack"}],
    "rim": {"done": 12, "working": 1, "retrying": 1, "needs_you": 1, "line": "12 done today · 1 working · 1 retrying its reply"},
    "draft": {"version": 8, "wakes": ["Wakes on Slack in #refunds too"], "what": ["1 · Check: Looks up the charge"],
              "groups": [{"grant": "g_slack", "name": "Slack", "wakes": ["Messages in #refunds"], "results": ["Posts in #sales"]}, {"name": "This taf", "what": ["1 · Check"]}]}
  }"""

  @Test fun theDialIsWhenWhatWhereAroundItsWhy() {
    val wire = KiffClient.json.decodeFromString<KnobWire>(dial)
    val knob = wire.knob()
    assertEquals("the face is the Why", "Refund double charges", knob.does)
    assertEquals(listOf(KnobRing.Kind.WakesIt, KnobRing.Kind.What, KnobRing.Kind.ResultsGo), knob.rings.map { it.kind })
    assertEquals("named as tafs cloud names them", listOf("When", "What", "Where"), knob.rings.map { it.name })
    assertEquals("the recipe's steps read with their number", listOf("1 · Check", "2 · Refund"), knob.rings[1].stops.map { it.label })
    val slack = knob.rings[0].stops[1]
    assertEquals("Open", slack.state)
    assertEquals("Tap to close", slack.tap)
    assertEquals("Messages in #refunds start a request.", slack.about)
    assertEquals("a size's value now", "09:00", knob.rings[0].stops[2].size)
    assertNull("no size, no value", knob.rings[0].stops[0].size)
    assertFalse(knob.rings[2].stops[1].on)
    assertEquals(15, knob.rim.total)
    assertEquals("12 done today · 1 working · 1 retrying its reply", knob.summary)
    assertEquals("◀ and ▶ go ring by ring, from the inside", listOf("trg_1", "trg_2", "trg_3", "s1", "s2", "res_1", "res_2"), knob.stops.map { it.second.id })
  }

  @Test fun showingOneInputKeepsOnlyItsStops() {
    val wire = KiffClient.json.decodeFromString<KnobWire>(dial)
    val slack = wire.knob("g_slack")
    assertEquals(listOf("trg_2", "res_2"), slack.stops.map { it.second.id })
    val own = wire.knob(KnobWire.THIS_TAF)
    assertEquals("the taf's own stops have no connection", listOf("trg_1", "trg_3", "s1", "s2", "res_1"), own.stops.map { it.second.id })
    assertEquals(listOf("Slack"), wire.connections.map { it.name })
  }

  @Test fun theRulesBothAppsShareComeFromTafsCloud() {
    val wire = KiffClient.json.decodeFromString<KnobWire>(
      """{"version":7,"wakes":[{"id":"door_e","kind":"door","door":"e","label":"Email","ok":"door","look":"knock"},
          {"id":"run_now","kind":"run_now","label":"Run now","ok":"run"},{"id":"add_door","kind":"add_door","label":"Add a door","ok":"coach"}],
          "what":[{"id":"what_ask","kind":"step","step":1,"label":"Ask Coach","ok":"coach"}],
          "pending":{"version":8,"changes":2},"rim":{}}""",
    )
    assertEquals(listOf("door", "run", "coach"), wire.wakes.map { it.ok })
    assertEquals("a door's look, as sent", "knock", wire.knob().rings[0].stops[0].look)
    assertEquals("Add a door is When's last stop, as sent", "add_door", wire.knob().rings[0].stops.last().id)
    assertEquals("1 · Ask Coach", wire.knob().rings[1].stops.single().label)
    assertEquals(KnobWire.Pending(8, 2), wire.pending)
    assertNull("nothing waits", KiffClient.json.decodeFromString<KnobWire>("""{"rim":{}}""").pending)
  }

  @Test fun aSizeStepsThroughItsChoicesAndStopsAtTheEnds() {
    val size = KiffClient.json.decodeFromString<KnobWire>(dial).item("trg_3")!!.size!!
    assertEquals("09:00", size.now!!.label)
    assertEquals("08:00", size.step(-1)!!.label)
    assertEquals("12:00", size.step(1)!!.label)
    assertNull(size.step(2))
    assertEquals("the whole PUT body, sent as it is", KiffClient.json.parseToJsonElement("""{"n":12}"""), size.step(1)!!.setting)
  }

  @Test fun anOlderDialWithNoRingNamesStillReadsWhenWhatWhere() {
    val knob = KiffClient.json.decodeFromString<KnobWire>("""{"does":{"label":"Refund","line":""},"rim":{}}""").knob()
    assertEquals(listOf("When", "What", "Where"), knob.rings.map { it.name })
    assertEquals("no Why yet: the job", "Refund", knob.does)
    assertTrue(knob.rings.all { it.stops.isEmpty() })
    assertNull(knob.summary)
  }

  @Test fun theDraftComesByConnectionForCoachsCard() {
    val draft = KiffClient.json.decodeFromString<KnobWire>(dial).draft!!
    assertEquals(listOf("Slack", "This taf"), draft.groups.map { it.name })
    assertEquals("g_slack", draft.groups[0].grant)
    assertNull(draft.groups[1].grant)
    assertEquals(listOf("Wakes on Slack in #refunds too", "1 · Check: Looks up the charge"), draft.lines)
  }

  @Test fun connectAppsListsTheCatalogAsSent() {
    val apps = KiffClient.json.decodeFromString<ConnectApps>(
      """{"apps":[{"entry":"notion@1","vendor":"Notion","title":"Notion","how":"oauth","state":"available"},
                  {"entry":"stripe@1","vendor":"Stripe","title":"Stripe","how":"key","state":"connected","grant":"g1"},
                  {"entry":"google","vendor":"Google","title":"Google","how":"oauth","state":"needs_reconnect","grant":"g2"}],
          "by_key":true}""",
    )
    assertEquals(listOf("notion@1", "stripe@1", "google"), apps.apps.map { it.entry })
    assertTrue(apps.apps[1].connected)
    assertTrue(apps.apps[2].needsReconnect)
    assertTrue(apps.byKey)
    // The start gives only the grant: the sign-in page comes from planque after the owner approves.
    val oauth = KiffClient.json.decodeFromString<ConnectStart>("""{"grant":"g3"}""")
    assertEquals("g3", oauth.grant)
    assertFalse(oauth.needsKey)
    assertTrue(KiffClient.json.decodeFromString<ConnectStart>("""{"grant":"g4","needs_key":true}""").needsKey)
  }

  // MARK: Coach's dial card

  @Test fun coachsDialCardNamesItsTafAndVersionWhicheverWayItsSent() {
    val byId = KiffClient.json.decodeFromString<bot.tafs.app.kiff.SetupComponent>("""{"type":"knob","id":"k1","taf_id":"taf_1","version":9}""")
    assertEquals("taf_1", byId.knobTaf)
    assertEquals(9, byId.version)
    val byTaf = KiffClient.json.decodeFromString<bot.tafs.app.kiff.SetupComponent>("""{"type":"knob","id":"k2","taf":"taf_1","version":9}""")
    assertEquals("taf_1", byTaf.knobTaf)
    assertNull("a string taf isn't a hand-over", byTaf.taf)
    // The hand-over's taf is still an object, read as before.
    val handOver = KiffClient.json.decodeFromString<bot.tafs.app.kiff.SetupComponent>("""{"type":"result","id":"r1","taf":{"agent":"refunds-1","name":"Refunds"}}""")
    assertEquals("Refunds", handOver.taf!!.name)
    assertNull(handOver.knobTaf)
    assertTrue("knob" in bot.tafs.app.kiff.SetupComponent.KINDS)
  }

  @Test fun theDraftsWordsComeByRingOrAsOneList() {
    val byRing = KiffClient.json.decodeFromString<KnobWire.Draft>("""{"version":9,"wakes":["An email to refunds@"],"what":["As they arrive"],"results":["A reply to the sender"]}""")
    assertEquals(listOf("An email to refunds@", "As they arrive", "A reply to the sender"), byRing.lines)
    assertEquals(listOf("Older words"), KiffClient.json.decodeFromString<KnobWire.Draft>("""{"version":8,"words":["Older words"]}""").lines)
  }
}
