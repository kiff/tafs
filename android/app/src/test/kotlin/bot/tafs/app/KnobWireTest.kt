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
}
