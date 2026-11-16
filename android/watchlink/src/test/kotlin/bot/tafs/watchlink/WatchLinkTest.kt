package bot.tafs.watchlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone acts only on the watch's six requests, each exactly as the watch writes it, and only while it is fresh. */
class WatchLinkTest {
  private val now = 1_763_244_000_000L

  private fun accept(path: String, body: String, at: Long = now) = WatchLink.accept(path, body.encodeToByteArray(), at)

  private fun refused(reason: NotAccepted, path: String, body: String, at: Long = now) =
    assertEquals(WatchLink.Accepted.No(reason), accept(path, body, at))

  @Test fun everyRequestComesBackAsItWasMade() {
    val requests = listOf(
      WatchRequest.NeedsYou(now),
      WatchRequest.Tafs(now),
      WatchRequest.Refuse(now, "hold_1"),
      WatchRequest.Halt(now, "taf_1"),
      WatchRequest.Halt(now, null),
      WatchRequest.Coach(now, "Pause refunds until Monday"),
      WatchRequest.Brief(now),
    )
    for (request in requests) {
      val (path, data) = WatchLink.encode(request)
      assertEquals(WatchLink.Accepted.Yes(request), WatchLink.accept(path, data, now))
    }
  }

  @Test fun anApprovalIsNeverAWatchRequest() {
    refused(NotAccepted.UNKNOWN_PATH, WatchLink.PREFIX + "approve", """{"made_at":$now,"hold_id":"hold_1"}""")
    refused(NotAccepted.UNKNOWN_PATH, WatchLink.PREFIX + "extra-room", """{"made_at":$now,"hold_id":"hold_1"}""")
    // An approval dressed as a refusal is refused too: a field Refuse doesn't take.
    refused(NotAccepted.MALFORMED, WatchLink.REFUSE, """{"made_at":$now,"hold_id":"hold_1","answer":"approve_once"}""")
  }

  @Test fun anUnknownPathIsRefused() {
    refused(NotAccepted.UNKNOWN_PATH, "/tafs/watch/v2/refuse", """{"made_at":$now,"hold_id":"hold_1"}""")
    refused(NotAccepted.UNKNOWN_PATH, "/somewhere/else", "{}")
  }

  @Test fun aRefusalNamesItsHold() {
    refused(NotAccepted.MALFORMED, WatchLink.REFUSE, """{"made_at":$now}""")
    refused(NotAccepted.MALFORMED, WatchLink.REFUSE, """{"made_at":$now,"hold_id":" "}""")
    refused(NotAccepted.MALFORMED, WatchLink.REFUSE, """{"made_at":$now,"hold_id":null}""")
  }

  @Test fun haltAllIsSaidInSoManyWords() {
    // A body missing its taf is never every taf.
    refused(NotAccepted.MALFORMED, WatchLink.HALT, """{"made_at":$now}""")
    refused(NotAccepted.MALFORMED, WatchLink.HALT, """{"made_at":$now,"all":false}""")
    refused(NotAccepted.MALFORMED, WatchLink.HALT, """{"made_at":$now,"taf_id":"taf_1","all":true}""")
    refused(NotAccepted.MALFORMED, WatchLink.HALT, """{"made_at":$now,"taf_id":""}""")
    assertEquals(WatchLink.Accepted.Yes(WatchRequest.Halt(now, null)), accept(WatchLink.HALT, """{"made_at":$now,"all":true}"""))
  }

  @Test fun aLateRequestIsDropped() {
    refused(NotAccepted.TOO_OLD, WatchLink.HALT, """{"made_at":${now - 31_000},"all":true}""")
    refused(NotAccepted.TOO_OLD, WatchLink.REFUSE, """{"made_at":${now - 31_000},"hold_id":"hold_1"}""")
    assertEquals(
      WatchLink.Accepted.Yes(WatchRequest.Halt(now - 30_000, "taf_1")),
      accept(WatchLink.HALT, """{"made_at":${now - 30_000},"taf_id":"taf_1"}"""),
    )
    refused(NotAccepted.FROM_THE_FUTURE, WatchLink.TAFS, """{"made_at":${now + 6_000}}""")
  }

  @Test fun anythingElseIsMalformed() {
    refused(NotAccepted.MALFORMED, WatchLink.NEEDS_YOU, "")
    refused(NotAccepted.MALFORMED, WatchLink.NEEDS_YOU, "not json")
    refused(NotAccepted.MALFORMED, WatchLink.NEEDS_YOU, """{"made_at":"now"}""")
    refused(NotAccepted.MALFORMED, WatchLink.TAFS, """{"made_at":$now,"cached":true}""")
    assertEquals(WatchLink.Accepted.No(NotAccepted.MALFORMED), WatchLink.accept(WatchLink.TAFS, byteArrayOf(0xC3.toByte(), 0x28), now))
    refused(NotAccepted.MALFORMED, WatchLink.COACH, """{"made_at":$now,"text":"  "}""")
    refused(NotAccepted.MALFORMED, WatchLink.COACH, """{"made_at":$now,"text":"${"a".repeat(WatchLink.MAX_COACH_TEXT + 1)}"}""")
    refused(NotAccepted.MALFORMED, WatchLink.COACH, """{"made_at":$now,"text":"hi","audio":"AAAA"}""")
  }

  /** The brief is a read, checked as the others: its path, exactly its body, and only while it's fresh. */
  @Test fun theBriefIsAReadCheckedAsTheOthers() {
    assertEquals("/tafs/watch/v1/brief", WatchLink.BRIEF)
    assertEquals(WatchLink.Accepted.Yes(WatchRequest.Brief(now)), accept(WatchLink.BRIEF, """{"made_at":$now}"""))
    refused(NotAccepted.MALFORMED, WatchLink.BRIEF, """{"made_at":$now,"answer":"approve"}""")
    refused(NotAccepted.MALFORMED, WatchLink.BRIEF, """{}""")
    refused(NotAccepted.TOO_OLD, WatchLink.BRIEF, """{"made_at":${now - 31_000}}""")
    refused(NotAccepted.FROM_THE_FUTURE, WatchLink.BRIEF, """{"made_at":${now + 6_000}}""")
    // An answer to a decision is never a watch request.
    refused(NotAccepted.UNKNOWN_PATH, WatchLink.PREFIX + "brief/answer", """{"made_at":$now}""")
  }

  @Test fun repliesRoundTripAndANewerKindIsNotGuessed() {
    val replies = listOf(
      WatchReply.NeedsYou(1, "1 held call needs you", listOf(HeldLine("hold_1", "Refunds", "Refund order 1042", "€64.00", "Over its limit", refusable = true))),
      WatchReply.Tafs(listOf(TafLine("taf_1", "Refunds", "Running", halted = false)), allHalted = false),
      WatchReply.Done("Refused"),
      WatchReply.AlreadyAnswered,
      WatchReply.Failed("Not refused: KIFF didn't answer."),
      WatchReply.SignedOut,
      WatchReply.Coach("Done. Refunds pause until Monday.", long = false),
      WatchReply.Refused(NotAccepted.TOO_OLD),
      WatchReply.Brief(
        title = "Your brief",
        whenLine = "Morning brief · 08:00 · next at 18:00",
        decisions = listOf(
          DecisionLine("d1", "Pay bill 0412?", "Bill pay", "by Fri 12:00", urgent = true, cantWait = "It expires before the next brief.", refKind = "hold", refId = "ce_1", holdId = "hold_1"),
          DecisionLine("d2", "Sign in to the bank again?", refKind = "connection", refId = "bank"),
        ),
        more = "2 more decisions",
        urgent = "Urgent",
        done = "Done since last evening",
        doneCases = listOf("Atelier Dubois", "Brio"),
        rest = "14 more wait and need nothing now",
        needsYou = "1 needs you",
      ),
      WatchReply.Brief(title = "Your brief"),
      WatchReply.NoBrief,
    )
    for (reply in replies) assertEquals(reply, WatchLink.decodeReply(WatchLink.encodeReply(reply)))
    assertNull(WatchLink.decodeReply("""{"kind":"approve_here"}""".encodeToByteArray()))
    assertEquals(WatchReply.Done("Halted"), WatchLink.decodeReply("""{"kind":"done","words":"Halted","newer":1}""".encodeToByteArray()))
  }
}
