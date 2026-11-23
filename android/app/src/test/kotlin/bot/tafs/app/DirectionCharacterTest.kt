package bot.tafs.app

import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.ActivityCall
import bot.tafs.app.kiff.CardExceptions
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.push.Alert
import bot.tafs.app.push.AlertPayload
import bot.tafs.app.watch.WatchRelay
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A payee name, an IBAN or a reason from KIFF, a vendor, a taf or tafs cloud never carries a direction character onto an
 * approval, the record, a notification or the watch: U+202E or an isolate could make what sits beside an
 * amount look reordered. They're taken out; any other control character is a space; right-to-left letters stay.
 */
class DirectionCharacterTest {
  private fun hasDirection(s: String?) = s != null && s.codePoints().anyMatch(KiffMapping::isDirection)

  @Test fun visibleTakesOutDirectionAndSpacesControls() {
    assertEquals("AcmeDTL", KiffMapping.visible("Acme‮DTL"))
    assertEquals("DE89 3704 0044 0532 0130 00", KiffMapping.visible("⁦DE89 3704 0044 0532 0130 00⁩"))
    assertEquals("abcd", KiffMapping.visible("a‎b‏c؜d"))
    assertEquals("one two ", KiffMapping.visible("one\ttwo\u0007"))
    assertEquals("شركة أكمي", KiffMapping.visible("شركة أكمي"))
    assertEquals("€150.00", KiffMapping.visible("€150.00"))
  }

  @Test fun aHeldCallsSummaryReasonAndDetailsCarryNone() {
    val e = KiffClient.json.decodeFromString<CardExceptions>("""
      {"exceptions":[{"id":"ce_1","subject":"pay-k5","action":"send_payment","title":"Pay‮ a supplier","entity_id":"inv⁧-42",
        "status":"held","reason_text":"Over the ‮limit","created_at":"2026-11-20T10:00:00Z",
        "arguments":{"payee":"Acme‮LTD","iban⁦":"⁦DE89 3704 0044 0532 0130 00⁩"}}]}
    """.trimIndent()).exceptions.single()
    val held = KiffMapping.held(e)
    assertFalse(held.proposed.summary, hasDirection(held.proposed.summary))
    assertFalse(held.reason, hasDirection(held.reason))
    held.details.orEmpty().forEach { assertFalse(it, hasDirection(it)) }
    assertEquals(listOf("iban: DE89 3704 0044 0532 0130 00", "payee: AcmeLTD"), held.details)
  }

  @Test fun aRecordEntryCarriesNone() {
    val call = ActivityCall("p1", "send_payment", actorId = "a1", outcome = "refused", createdAt = Instant.EPOCH,
      title = "Pay‮ Mercier", entityId = "inv⁦-42", reason = "Over‮ the limit", reasons = listOf("payee‮ blocked"))
    val entry = KiffMapping.record(listOf(call), setOf("a1")).single()
    listOf(entry.title, entry.detail, entry.entityId, entry.reason).forEach { assertFalse("$it", hasDirection(it)) }
    entry.why.orEmpty().forEach { assertFalse(it, hasDirection(it)) }
  }

  @Test fun aNotificationCarriesNone() {
    val shown = AlertPayload.parse(mapOf("kind" to "alert", "title" to "Pay‮ Mercier", "body" to "€150.00 to ⁦Acme⁩")) as Alert.Show
    assertEquals("Pay Mercier", shown.title)
    assertEquals("€150.00 to Acme", shown.body)
    val urgent = AlertPayload.parse(mapOf("kind" to "urgent", "title" to "Acme‮LTD", "body" to "x")) as Alert.Show
    assertEquals("AcmeLTD", urgent.title)
  }

  @Test fun theWatchsNeedsYouCarriesNone() {
    val state = testModel().state.value.copy(mode = Mode.kiff)
    val call = state.held.first().let { it.copy(proposed = it.proposed.copy(summary = "Refund ‮order 1042"), reason = "Over⁦ the limit") }
    val line = WatchRelay.needsYou(state.copy(held = listOf(call))).calls.single()
    assertEquals("Refund order 1042", line.wants)
    assertEquals("Over the limit", line.reason)
    assertFalse(hasDirection(line.taf))
  }
}
