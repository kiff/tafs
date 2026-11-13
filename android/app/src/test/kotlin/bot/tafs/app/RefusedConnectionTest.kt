package bot.tafs.app

import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.model.Refused
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A connection the server refused: tafs cloud's lines on the message (docs/chat.md), the step's words, why and what to do
 * next, kept as written. The phone adds and decides nothing: no lines from tafs cloud, nothing shown.
 */
class RefusedConnectionTest {
  private val transport = FakeTransport()

  @Test fun aRefusedConnectionsLinesAreReadAsTafsCloudWroteThem() = runTest {
    transport.answer(
      "GET /v1/tafs/coach/conversation", 200,
      """{"messages":[
        {"id":"run_1.2","at":"2026-11-13T03:00:00Z","from":"taf","kind":"message","text":"Shippo's sign-in isn't set up here.","session":"s1",
         "work":{"ms":4000,"steps":[{"tool":"planque","words":"Reading what your provider offers"}]},
         "refused":{"words":"Coach couldn't connect Shippo yet","why":"Why: managed sign-in isn't set up for this app",
           "next":"Next: ask Coach to connect Shippo with its key instead, or to use another app."}},
        {"id":"run_2.2","at":"2026-11-13T03:05:00Z","from":"taf","kind":"message","text":"Tried again.","session":"s1",
         "refused":{"words":"Coach couldn't connect the app yet","why":" ","next":"Next: ask Coach to connect it with its key instead, or to use another app."}},
        {"id":"run_3.2","at":"2026-11-13T03:10:00Z","from":"taf","kind":"message","text":"Connected.","session":"s1"}]}""",
    )
    val messages = TafsCloud(transport, token = "synthetic-session").conversation("coach").messages
    assertEquals(
      Refused(
        "Coach couldn't connect Shippo yet", "Why: managed sign-in isn't set up for this app",
        "Next: ask Coach to connect Shippo with its key instead, or to use another app.",
      ),
      messages[0].refused,
    )
    assertEquals(3, messages[0].refused!!.lines.size)
    // A blank reason is no line: the step's words and what to do next still show.
    assertNull(messages[1].refused!!.why)
    assertEquals(listOf("Coach couldn't connect the app yet", "Next: ask Coach to connect it with its key instead, or to use another app."), messages[1].refused!!.lines)
    assertNull("none from tafs cloud: nothing shown", messages[2].refused)
  }

  @Test fun anEmptyRefusalIsNone() = runTest {
    transport.answer(
      "GET /v1/tafs/coach/conversation", 200,
      """{"messages":[{"id":"run_1.2","at":"2026-11-13T03:00:00Z","from":"taf","kind":"message","text":"Hm.","session":"s1","refused":{}}]}""",
    )
    assertNull(TafsCloud(transport, token = "synthetic-session").conversation("coach").messages[0].refused)
  }
}
