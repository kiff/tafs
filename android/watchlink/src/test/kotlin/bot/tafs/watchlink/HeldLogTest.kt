package bot.tafs.watchlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The log line both apps write about held calls: counts and seconds, never a taf, what it wants, an amount, a reason or an id. */
class HeldLogTest {
  private val now = 1_763_244_000_000L
  private val call = HeldLine("hold_9f2", "Stripe refunds", "Refund a charge in EUR", amount = "€40.00", reason = "Over the limit", refusable = true, endsAt = now + 287_400)

  @Test fun aLineCarriesCountsAndSecondsLeftOnly() {
    val line = HeldLog.line("phone put kept/needs-you", 2, listOf(call, call.copy(endsAt = null), call.copy(endsAt = now - 5_000)), now, "mark" to true)
    assertEquals("phone put kept/needs-you: count=2 calls=3 ends_in_s=[287,?,-5] mark=true", line)
    for (never in listOf("Stripe", "Refund", "40", "limit", "hold_9f2")) assertFalse(never, line.contains(never))
  }

  @Test fun noCallsAndAMissingNote() {
    assertEquals("watch kept (app closed): count=0 calls=0 ends_in_s=[] new=none", HeldLog.line("watch kept (app closed)", 0, emptyList(), now, "new" to null))
    assertEquals("phone answered brief: a brief held=1 held_in_brief=0", HeldLog.skipped("phone answered brief", "a brief", "held" to 1, "held_in_brief" to 0))
  }

  @Test fun theMarkIsComparedNeverWritten() {
    val a = "AAAAAAAAAAAAAAAAAAAAAA"
    assertEquals("same", HeldLog.mark(a, a))
    assertEquals("other", HeldLog.mark(a, "BBBBBBBBBBBBBBBBBBBBBB"))
    assertEquals("phone-none", HeldLog.mark(a, null))
    assertEquals("watch-none", HeldLog.mark(null, a))
  }
}
