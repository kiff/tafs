package bot.tafs.app

import bot.tafs.app.kiff.ActivityCalls
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.ReceiptWords
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kiff/tafs#2225: a held call that went through is "approved once by you" only when KIFF says the owner answered it with an
 * approval. One that went through some other way (its Card changed, room given) is the Card's, as KIFF's own trail shows.
 */
class HeldThenAllowedTest {
  private fun entry(answer: String?): RecordEntry {
    val json = """{"calls":[{"proposal_id":"p1","action_name":"book_meeting","title":"Book a meeting","actor_id":"a1","outcome":"allowed","held":true,""" +
      (answer?.let { "\"answer\":$it," } ?: "") + """"tool_outcome":{"status":"done"},"created_at":"2026-11-27T00:44:05Z"}]}"""
    return KiffMapping.record(KiffClient.json.decodeFromString<ActivityCalls>(json).calls, setOf("a1")).single()
  }

  @Test fun heldThenAllowedWithTheOwnersApprovalIsApprovedOnce() {
    val e = entry("""{"decision":"approved_once","you":true}""")
    assertEquals(RecordEntry.Kind.approved, e.kind)
    assertEquals(true, e.ownerApproved)
  }

  @Test fun heldThenAllowedWithNoAnswerIsTheCardsNeverAnApprovalOnce() {
    val e = entry(null)
    assertEquals(RecordEntry.Kind.done, e.kind)
    assertNull(e.ownerApproved)
    assertEquals(Words.Record.doneWithinCard, e.detail)
    assertTrue(ReceiptWords.decidedBy(e, signedIn = true) != Words.Receipt.DecidedBy.youApprovingOnce)
    assertTrue(ReceiptWords.label(e.kind) != Words.Receipt.Label.approved)
  }

  @Test fun heldAndRefusedByTheOwnerThenAllowedIsNeverApprovedOnce() {
    // An allowed call never carries a refusal, but if KIFF ever sent one the app wouldn't call it an approval.
    val e = entry("""{"decision":"refused","you":true}""")
    assertEquals(RecordEntry.Kind.done, e.kind)
    assertNull(e.ownerApproved)
  }
}
