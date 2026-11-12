package bot.tafs.app

import bot.tafs.app.kiff.TafsCloud
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a job run's result went: tafs cloud's one line on its report in the thread (docs/chat.md), kept as it's written. */
class DeliveredLineTest {
  private val transport = FakeTransport()

  @Test fun aReportsDeliveredLineIsReadAsTafsCloudWroteItAndNoneIsNone() = runTest {
    transport.answer(
      "GET /v1/tafs/taf_1/conversation", 200,
      """{"messages":[
        {"id":"run_1.4","at":"2026-11-05T09:00:00Z","from":"taf","kind":"message","text":"Three reminders sent.","session":"s1",
         "delivered":"Sent to Google Sheets · Refunds 2026. Not sent to Slack · #ops yet: trying again."},
        {"id":"run_2.4","at":"2026-11-05T10:00:00Z","from":"taf","kind":"message","text":"Nothing due today.","session":"s2","delivered":"  "},
        {"id":"run_3.4","at":"2026-11-05T11:00:00Z","from":"taf","kind":"message","text":"Done.","session":"s3"}]}""",
    )
    val messages = TafsCloud(transport, token = "synthetic-session").conversation("taf_1").messages
    assertEquals("Sent to Google Sheets · Refunds 2026. Not sent to Slack · #ops yet: trying again.", messages[0].delivered)
    assertNull("a blank line is no line", messages[1].delivered)
    assertNull(messages[2].delivered)
  }

  /** What a run did, counted from the record: tafs cloud's line on the run's last message, as written. The phone counts nothing. */
  @Test fun aRunsCountedLineIsReadAsTafsCloudWroteItAndNoneIsNone() = runTest {
    transport.answer(
      "GET /v1/tafs/taf_1/conversation", 200,
      """{"messages":[
        {"id":"run_1.4","at":"2026-11-05T09:00:00Z","from":"taf","kind":"message","text":"Three refunds made.","session":"s1",
         "delivered":"Sent to Slack · #ops.","counted":"From the record: 3 done · 1 refused"},
        {"id":"run_2.4","at":"2026-11-05T10:00:00Z","from":"taf","kind":"message","text":"Nothing due today.","session":"s2","counted":" "},
        {"id":"run_3.4","at":"2026-11-05T11:00:00Z","from":"taf","kind":"message","text":"Done.","session":"s3"}]}""",
    )
    val messages = TafsCloud(transport, token = "synthetic-session").conversation("taf_1").messages
    assertEquals("From the record: 3 done · 1 refused", messages[0].counted)
    assertEquals("both lines kept, each its own", "Sent to Slack · #ops.", messages[0].delivered)
    assertNull("a blank line is no line", messages[1].counted)
    assertNull("a tafs cloud that sends none: no line, and nothing counted here", messages[2].counted)
  }
}
