package bot.tafs.watchlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone acts only on the watch's requests, each exactly as the watch writes it, and only while it is fresh. */
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
      WatchRequest.Ask(now, "w_7f3a9c21", "Move my four o'clock call to six"),
      WatchRequest.Ask(now, "w_7f3a9c22", "Make it seven", replyTo = "wa_01"),
      WatchRequest.Ask(now, "w_7f3a9c23", "Refund order 1182", tafId = "refunds-1"),
      WatchRequest.Asks(now),
      WatchRequest.Asks(now, since = "2026-11-16T19:00:00.000Z"),
      WatchRequest.Brief(now),
      WatchRequest.Taf(now, "taf_1"),
      WatchRequest.Calendar(now),
      WatchRequest.Calendar(now, day = "2026-11-19"),
      WatchRequest.Account(now),
    )
    for (request in requests) {
      val (path, data) = WatchLink.encode(request)
      assertEquals(WatchLink.Accepted.Yes(request), WatchLink.accept(path, data, now))
    }
  }

  /** Wear v2's reads (a taf's page, a calendar day, the account) are reads, checked as the others: no field they don't take. */
  @Test fun theV2ReadsAreCheckedAsTheOthers() {
    assertEquals(WatchLink.Accepted.No(NotAccepted.MALFORMED), WatchLink.accept(WatchLink.TAF, """{"made_at":$now}""".encodeToByteArray(), now))
    assertEquals(WatchLink.Accepted.No(NotAccepted.MALFORMED), WatchLink.accept(WatchLink.TAF, """{"made_at":$now,"taf_id":" "}""".encodeToByteArray(), now))
    assertEquals(WatchLink.Accepted.No(NotAccepted.MALFORMED), WatchLink.accept(WatchLink.CALENDAR, """{"made_at":$now,"day":"tomorrow"}""".encodeToByteArray(), now))
    assertEquals(WatchLink.Accepted.No(NotAccepted.MALFORMED), WatchLink.accept(WatchLink.ACCOUNT, """{"made_at":$now,"sign_out":true}""".encodeToByteArray(), now))
    assertEquals(WatchLink.Accepted.No(NotAccepted.TOO_OLD), WatchLink.accept(WatchLink.ACCOUNT, """{"made_at":${now - 31_000}}""".encodeToByteArray(), now))
    val replies = listOf(
      WatchReply.TafPage("taf_1", "Support replies", "Working", false, "Answer tickets", listOf(CardLine("Replies", "28", "calls left today of 40", listOf("Replies: up to 40 a day"))), waiting = 1),
      WatchReply.CalendarDay("2026-11-19", "2 to come", listOf(EventLine("09:00", "Morning inbox", "Every day at 09:00"))),
      WatchReply.AccountPage("ana@acme.com", "Acme Pay", "Solo · 260 credits left"),
    )
    for (reply in replies) assertEquals(reply, WatchLink.decodeReply(WatchLink.encodeReply(reply)))
  }

  /** Row 21: the Inbox's marks are ids only, 1 to 100 that tafs cloud takes, checked before the phone reads anything. */
  @Test fun theInboxMarksAreIdsOnlyCheckedOnBothEnds() {
    val requests = listOf(
      WatchRequest.InboxMarks(now),
      WatchRequest.InboxMark(now, read = listOf("hold_1")),
      WatchRequest.InboxMark(now, dismissed = listOf("hold_2", "wa-01")),
      WatchRequest.InboxMark(now, read = listOf("hold_1"), dismissed = listOf("hold_2")),
      WatchRequest.InboxMark(now, dismissed = List(100) { "h$it" }),
    )
    for (request in requests) {
      val (path, data) = WatchLink.encode(request)
      assertEquals(WatchLink.Accepted.Yes(request), WatchLink.accept(path, data, now))
    }
    assertEquals("/tafs/watch/v1/inbox-mark", WatchLink.INBOX_MARK)
    // An empty list isn't written at all.
    assertEquals("""{"made_at":$now,"read":["hold_1"]}""", WatchLink.encode(WatchRequest.InboxMark(now, read = listOf("hold_1"))).second.decodeToString())
    refused(NotAccepted.MALFORMED, WatchLink.INBOX_MARK, """{"made_at":$now}""")
    refused(NotAccepted.MALFORMED, WatchLink.INBOX_MARK, """{"made_at":$now,"read":[]}""")
    refused(NotAccepted.MALFORMED, WatchLink.INBOX_MARK, """{"made_at":$now,"read":["held:hold_1"]}""")
    refused(NotAccepted.MALFORMED, WatchLink.INBOX_MARK, """{"made_at":$now,"read":["${"a".repeat(101)}"]}""")
    refused(NotAccepted.MALFORMED, WatchLink.INBOX_MARK, """{"made_at":$now,"dismissed":[${List(101) { "\"h$it\"" }.joinToString(",")}]}""")
    refused(NotAccepted.MALFORMED, WatchLink.INBOX_MARK, """{"made_at":$now,"read":["a"],"dismissed":[${List(100) { "\"h$it\"" }.joinToString(",")}]}""")
    refused(NotAccepted.MALFORMED, WatchLink.INBOX_MARK, """{"made_at":$now,"read":["a"],"title":"Refund"}""")
    refused(NotAccepted.MALFORMED, WatchLink.INBOX_MARKS, """{"made_at":$now,"read":["a"]}""")
    refused(NotAccepted.TOO_OLD, WatchLink.INBOX_MARK, """{"made_at":${now - 31_000},"read":["a"]}""")
    val reply = WatchReply.InboxMarks(read = listOf("hold_1"), dismissed = listOf("wa_01"))
    assertEquals(reply, WatchLink.decodeReply(WatchLink.encodeReply(reply)))
    assertEquals(WatchReply.InboxMarks(), WatchLink.decodeReply("""{"kind":"inbox_marks"}""".encodeToByteArray()))
    assertEquals(listOf("a", "b"), InboxMarkIds.checked(listOf("a", "held:x", "b", "a", "")))
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

  // MARK: Ask tafs

  @Test fun anAskCarriesTheWatchsOwnIdAndPlainText() {
    // An id too short, or with a character tafs cloud wouldn't take, is refused before anything is signed.
    refused(NotAccepted.MALFORMED, WatchLink.ASK, """{"made_at":$now,"client_id":"w_1","text":"hi"}""")
    refused(NotAccepted.MALFORMED, WatchLink.ASK, """{"made_at":$now,"client_id":"w_7f3a9c21/../x","text":"hi"}""")
    refused(NotAccepted.MALFORMED, WatchLink.ASK, """{"made_at":$now,"client_id":"w_7f3a9c21","text":"  "}""")
    refused(NotAccepted.MALFORMED, WatchLink.ASK, """{"made_at":$now,"client_id":"w_7f3a9c21","text":"${"a".repeat(WatchLink.MAX_ASK_TEXT + 1)}"}""")
    refused(NotAccepted.MALFORMED, WatchLink.ASK, """{"made_at":$now,"client_id":"w_7f3a9c21","text":"hi","reply_to":""}""")
  }

  @Test fun anAskCantNameItsTafOrMarkItselfAnything() {
    // The watch says what it wants; tafs cloud picks the taf. A field the ask doesn't take is refused, not passed on.
    refused(NotAccepted.MALFORMED, WatchLink.ASK, """{"made_at":$now,"client_id":"w_7f3a9c21","text":"hi","taf":"taf_1"}""")
    refused(NotAccepted.MALFORMED, WatchLink.ASK, """{"made_at":$now,"client_id":"w_7f3a9c21","text":"hi","entry":"watch"}""")
    refused(NotAccepted.MALFORMED, WatchLink.ASKS, """{"made_at":$now,"since":"yesterday at noon"}""")
  }

  @Test fun anAsksLateMessageIsDroppedAsAnyOther() {
    refused(NotAccepted.TOO_OLD, WatchLink.ASK, """{"made_at":${now - WatchLink.MAX_AGE_MS - 1},"client_id":"w_7f3a9c21","text":"hi"}""")
  }

  @Test fun theResultIsTheWatchsToReadNeverARequestThePhoneAnswers() {
    refused(NotAccepted.UNKNOWN_PATH, WatchLink.ASK_RESULT, """{"made_at":$now}""")
    val line = AskLine("wa_01", "w_7f3a9c21", AskLine.DONE, "Calendar", "Moved your call to 18:00.", "Done", "2026-11-16T19:02:00Z")
    assertEquals(line, WatchLink.decodeResult(WatchLink.encodeResult(line)))
    assertEquals(true, line.ended)
    assertEquals(false, line.copy(state = AskLine.WORKING).ended)
    // A newer phone's extra field is read past; something that isn't an ask is nothing.
    assertEquals(line, WatchLink.decodeResult(String(WatchLink.encodeResult(line)).replace("}", ",\"newer\":1}").encodeToByteArray()))
    assertNull(WatchLink.decodeResult("{}".encodeToByteArray()))
  }

  @Test fun theAskRepliesComeBackAsTheyWereMade() {
    val line = AskLine("wa_01", "w_7f3a9c21", AskLine.ACCEPTED, words = "Sent", updated = "2026-11-16T19:00:00Z")
    for (reply in listOf(WatchReply.AskTaken(line), WatchReply.AskList(listOf(line, line.copy(id = "wa_02", state = AskLine.WORKING, tafName = "Inbox"))))) {
      assertEquals(reply, WatchLink.decodeReply(WatchLink.encodeReply(reply)))
    }
  }

  /** Coach's ask carries no `taf_id`, so a phone from before chatting with a taf still takes it; a taf's message names its taf. */
  @org.junit.Test fun onlyAMessageToATafCarriesItsTaf() {
    val coach = String(WatchLink.encode(WatchRequest.Ask(1L, "w_7f3a9c21", "Hello")).second)
    org.junit.Assert.assertFalse(coach, coach.contains("taf_id"))
    val taf = String(WatchLink.encode(WatchRequest.Ask(1L, "w_7f3a9c21", "Hello", tafId = "refunds-1")).second)
    org.junit.Assert.assertTrue(taf, taf.contains("\"taf_id\":\"refunds-1\""))
  }
}
