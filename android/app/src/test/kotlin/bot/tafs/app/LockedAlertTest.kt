package bot.tafs.app

import bot.tafs.app.push.Alert
import bot.tafs.app.push.AlertPayload
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an alert shows on a locked phone, or a locked watch the phone bridges it to: only "A taf needs you". tafs cloud's title
 * and body can name an amount, a merchant or a person, so they wait for the unlock, on every channel.
 */
class LockedAlertTest {
  private val now = Instant.parse("2026-11-15T22:00:00Z")

  private fun shown(data: Map<String, String>): Alert.Show = AlertPayload.parse(data, now) as Alert.Show

  @Test fun aHeldCallLockedSaysOnlyThatATafNeedsYou() {
    val hold = shown(
      mapOf(
        "kind" to "alert", "open" to "needs-you", "hold_id" to "h_1", "level" to "interrupt",
        "title" to "Chargebacks needs you", "body" to "Dispute #4825 · €640 at Example Shop is over its limit",
      ),
    )
    val locked = hold.locked()
    assertEquals("A taf needs you", locked.title)
    assertEquals("", locked.body)
    for (detail in listOf("Chargebacks", "€640", "Example Shop", "4825")) {
      assertFalse(detail, locked.title.contains(detail) || locked.body.contains(detail))
    }
    // The rest is the alert's own: the same call, the same channel.
    assertEquals(hold.tag, locked.tag)
    assertEquals(hold.loud, locked.loud)
    assertEquals(hold.open, locked.open)
  }

  @Test fun aQuietAlertIsHiddenToo() {
    val usage = shown(mapOf("kind" to "alert", "open" to "usage", "level" to "quiet", "title" to "Usage", "body" to "€38 of €50 used"))
    val locked = usage.locked()
    assertEquals("A taf needs you", locked.title)
    assertEquals("", locked.body)
    assertFalse(locked.loud)
  }

  @Test fun everyNotificationIsPrivateWithItsLockedVersion() {
    // The notification itself is Android's, so its two calls are checked where they're made: one builder, used for every alert.
    val notifier = File("src/main/kotlin/bot/tafs/app/push/AndroidPush.kt").readText().replace("\r\n", "\n")
    val show = notifier.substringAfter("fun show(alert: Alert.Show)").substringBefore("\n  }\n")
    assertTrue(show.contains(".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
    assertTrue(show.contains(".setPublicVersion(lockedVersion(alert.locked()))"))
    assertEquals("one notification built in show", 1, Regex("""NotificationCompat\.Builder\(""").findAll(show).count())
    assertFalse("no alert may be public", notifier.contains("VISIBILITY_PUBLIC"))
  }
}
