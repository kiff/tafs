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

/** The knob API as tafs cloud publishes it: the dial and Connect apps, decoded and laid out as sent. */
class KnobWireTest {
  // The knob API's own example for the dial, with the rest of Refunds' rings.
  private val b1 = """{
    "version": 7,
    "does": {"label": "Refund", "line": "Refunds a payment in Stripe, within its Card."},
    "wakes": [
      {"id": "trg_1", "kind": "door", "door": "door_1", "label": "Email", "words": "Wakes on email to refunds@yourshop.com", "on": true},
      {"id": "trg_2", "kind": "door", "label": "Slack", "words": "Messages in #refunds start a request.", "on": true, "state": "Open", "tap": "Tap to close"},
      {"id": "trg_3", "kind": "follow_up", "label": "Tomorrow 9:00", "words": "Check refund 4402 again", "expires": "2026-11-03T09:00:00Z"}
    ],
    "when": [{"id": "when_arrivals", "kind": "arrivals", "label": "Arrivals", "words": "Works each one as it arrives"}],
    "results": [{"id": "res_1", "place": "door_reply", "label": "Sender", "words": "Replies to whoever wrote"},
                {"id": "res_2", "place": "notion", "label": "Notion", "words": "A page in Refunds log", "on": false}],
    "rim": {"done": 12, "working": 1, "retrying": 1, "needs_you": 1, "line": "12 done today · 1 working · 1 retrying its reply"},
    "draft": {"version": 8, "words": ["Wakes on Slack in #refunds too"]}
  }"""

  @Test fun theDialIsLaidOutAsTafsCloudSendsIt() {
    val wire = KiffClient.json.decodeFromString<KnobWire>(b1)
    val knob = wire.knob()
    assertEquals("Refund", knob.does)
    assertEquals("Refunds a payment in Stripe, within its Card.", knob.overview)
    assertEquals(listOf(KnobRing.Kind.WakesIt, KnobRing.Kind.When, KnobRing.Kind.ResultsGo), knob.rings.map { it.kind })
    assertEquals(listOf("Email", "Slack", "Tomorrow 9:00"), knob.rings[0].stops.map { it.label })
    val slack = knob.rings[0].stops[1]
    assertEquals("Open", slack.state)
    assertEquals("Tap to close", slack.tap)
    assertEquals("Messages in #refunds start a request.", slack.about)
    // A stop that sends no face words: the face shows its label, and a tap there does nothing.
    assertNull(knob.rings[0].stops[0].state)
    assertNull(knob.rings[0].stops[0].tap)
    assertTrue("on unless it says", knob.rings[1].stops.single().on)
    assertFalse(knob.rings[2].stops[1].on)
    assertEquals(15, knob.rim.total)
    assertEquals(1, knob.rim.needsYou)
    assertEquals("12 done today · 1 working · 1 retrying its reply", knob.summary)
    assertEquals("a door stop names its door, for the face's open and close", "door_1", wire.wakes[0].door)
    assertEquals(8, wire.draft!!.version)
    assertEquals(listOf("Wakes on Slack in #refunds too"), wire.draft!!.words)
  }

  @Test fun anEmptyDialStillReads() {
    val knob = KiffClient.json.decodeFromString<KnobWire>("""{"does":{"label":"Refund","line":""},"rim":{}}""").knob()
    assertEquals(3, knob.rings.size)
    assertTrue(knob.rings.all { it.stops.isEmpty() })
    assertEquals(0, knob.rim.total)
    assertNull(knob.summary)
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

  // MARK: A tap changes one thing in the live setting

  private val dial = KiffClient.json.decodeFromString<KnobWire>(
    """{"does":{"label":"Refund","line":""},
        "wakes":[{"id":"trg_door","kind":"door","door":"door_1","label":"Email","on":true},
                 {"id":"trg_run","kind":"run_now","label":"Run now"},
                 {"id":"trg_9","kind":"schedule","label":"Weekdays","on":true,"trigger":{"kind":"schedule","form":"weekdays","at":"09:00"}},
                 {"id":"trg_15","kind":"schedule","label":"15:00","on":false,"trigger":{"kind":"schedule","form":"daily","at":"15:00"}}],
        "when":[{"id":"when_busy","kind":"busy","label":"If busy"},{"id":"when_arrivals","kind":"arrivals","label":"Arrivals"}],
        "results":[{"id":"res_reply","place":"door_reply","label":"Sender","on":true},
                   {"id":"res_app","place":"app","label":"You","on":false},
                   {"id":"res_slack","place":"slack","label":"Slack","on":false}],
        "rim":{},
        "setting":{"wakes":[{"kind":"door"},{"kind":"schedule","form":"weekdays","at":"09:00"}],"busy":"skip","results":[{"place":"door_reply"}]}}""",
  )

  private fun json(s: String) = KiffClient.json.parseToJsonElement(s)

  @Test fun aTriggerIsntChangedByATapItsCoachsDraft() {
    assertNull(dial.changed("trg_9"))
    assertNull(dial.changed("trg_15"))
  }

  @Test fun aPlaceIsTakenOutOrAPlainOnePutBack() {
    assertEquals(json("""{"wakes":[{"kind":"door"},{"kind":"schedule","form":"weekdays","at":"09:00"}],"when":{"busy":"skip"},"results":[]}"""), dial.changed("res_reply"))
    assertEquals(
      json("""{"wakes":[{"kind":"door"},{"kind":"schedule","form":"weekdays","at":"09:00"}],"when":{"busy":"skip"},"results":[{"place":"door_reply"},{"place":"app"}]}"""),
      dial.changed("res_app"),
    )
    assertNull("a place that names a channel is set where it's chosen, not by a tap", dial.changed("res_slack"))
  }

  @Test fun ifBusyGoesBetweenSkipAndLatest() {
    val busy = (dial.changed("when_busy")!!["when"] as kotlinx.serialization.json.JsonObject)["busy"] as kotlinx.serialization.json.JsonPrimitive
    assertEquals("latest", busy.content)
    assertTrue(busy.isString)
  }

  @Test fun aDoorRunNowAndAStopTheDialCantSetChangeNoVersion() {
    assertNull(dial.changed("trg_door"))
    assertNull(dial.changed("trg_run"))
    assertNull(dial.changed("when_arrivals"))
    assertNull("no setting from an older tafs cloud", KiffClient.json.decodeFromString<KnobWire>("""{"wakes":[{"id":"t","trigger":{"kind":"schedule"}}]}""").changed("t"))
  }
}
