import XCTest
@testable import Tafs

/// The watch's requests, answered on the phone (docs/watch-app.md, "The relay"): only the
/// contract's kinds, only complete and live, only signed in. Anything else is refused before
/// any read, and nothing on this path signs.
@MainActor
final class WatchRelayTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-10-14T10:45:00Z")!

    /// A reading that counts what it's asked, and has nothing to sign with.
    private final class Reading: WatchReading {
        var signedIn = true
        var reads = 0
        var fails = false
        var refused: [String] = []
        var halted: [String?] = []
        var refuseOutcome = NotificationRefuse.Outcome.refused(left: 0)
        var haltOutcome = HaltAll.Outcome.done("Halted. Resuming starts only new work.")
        var coached: [String] = []
        var coachedIDs: [UUID] = []
        let page: WatchNeedsYou
        init(at: Date) {
            page = WatchNeedsYou(count: 2, calls: [.init(id: "ce_1", taf: "Rae", what: "Refund order 4502, €480.00",
                                                          reason: "Over €150.00 each", answerBy: "10:51")], at: at)
        }
        func needsYou(now: Date) async throws -> WatchNeedsYou {
            reads += 1
            if fails { throw URLError(.notConnectedToInternet) }
            return page
        }
        func tafs() async throws -> [WatchTaf] {
            reads += 1
            if fails { throw URLError(.notConnectedToInternet) }
            return [WatchTaf(id: "depl_1", name: "Rae", line: "Paused", halted: false)]
        }
        func refuse(hold: String, now: Date) async -> NotificationRefuse.Outcome {
            refused.append(hold)
            return refuseOutcome
        }
        func halt(taf: String?) async -> HaltAll.Outcome {
            halted.append(taf)
            return haltOutcome
        }
        func coach(text: String, messageID: UUID) async -> WatchReply {
            coached.append(text)
            coachedIDs.append(messageID)
            return WatchReply(.ok, coach: WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false))
        }
        var briefRead: WatchBrief?
        func brief() async throws -> WatchBrief? {
            reads += 1
            if fails { throw URLError(.notConnectedToInternet) }
            return briefRead
        }
        var pages: [String: WatchTafPage] = [:]
        var askedTaf: [String] = []
        func tafPage(_ id: String) async throws -> WatchTafPage? {
            reads += 1
            askedTaf.append(id)
            if fails { throw URLError(.notConnectedToInternet) }
            return pages[id]
        }
        var askedDay: [String?] = []
        func calendar(day: String?) async throws -> WatchCalendarDay {
            reads += 1
            askedDay.append(day)
            if fails { throw URLError(.notConnectedToInternet) }
            return WatchCalendarDay(day: day ?? "2026-10-14", words: "2 to come", events: [], halted: false)
        }
        func account() async throws -> WatchAccount {
            reads += 1
            if fails { throw URLError(.notConnectedToInternet) }
            return WatchAccount(email: "owner@example.com", account: "Shop", plan: "Starter")
        }
        /// Nothing was read, refused, halted or sent to Coach.
        var untouched: Bool { reads == 0 && refused.isEmpty && halted.isEmpty && coached.isEmpty }
    }

    private func send(_ request: WatchRequest, reading: Reading) async -> WatchReply {
        await WatchRelay.answer(WatchMessage.encode(request), now: now, reading: reading)
    }

    /// Bytes as a watch, or anything else, could send them.
    private func raw(_ json: String, reading: Reading) async -> WatchReply {
        await WatchRelay.answer(Data(json.utf8), now: now, reading: reading)
    }

    private var made: String { "\(now.timeIntervalSinceReferenceDate)" }

    /// Your brief (S2, behind the watch's switch): a read, answered from what tafs cloud sends; none is no brief,
    /// and nothing is answered or signed. Signed out answers nothing of the account.
    func testTheBriefIsReadOnly() async {
        let reading = Reading(at: now)
        let decision = WatchBrief.Decision(id: "hold:ce_1", question: "Refund order 4502, €480.00?", taf: "Rae", deadline: "by 10:51",
                                           urgent: true, cantWait: nil, refKind: "hold", refID: "ce_1", holdID: "ce_1")
        reading.briefRead = WatchBrief(title: "Your brief", when: "Morning brief · 08:00", urgent: "Urgent", decisions: [decision],
                                       more: nil, done: nil, doneCases: [], rest: nil)
        let reply = await send(WatchRequest(.brief, made: now), reading: reading)
        XCTAssertEqual(reply, WatchReply(.ok, brief: reading.briefRead))
        XCTAssertEqual(WatchMessage.decode(WatchReply.self, from: WatchMessage.encode(reply)), reply, "it survives the wire")
        XCTAssertTrue(reading.refused.isEmpty && reading.halted.isEmpty && reading.coached.isEmpty, "a read: nothing answered")
        reading.briefRead = nil
        let none = await send(WatchRequest(.brief, made: now), reading: reading)
        XCTAssertEqual(none, WatchReply(.ok), "no brief: the watch shows Needs you")
        reading.signedIn = false
        let out = await send(WatchRequest(.brief, made: now), reading: reading)
        XCTAssertEqual(out, WatchReply(.signedOut))
        XCTAssertEqual(reading.reads, 2)
    }

    /// The brief as the wrist gets it: tafs cloud's words, each decision without its answers, a held call's id for
    /// Refuse, the taf by name, every decision tafs cloud sends and two of Done's cases; an empty brief is none.
    func testTheWristsBriefIsTafsCloudsWords() throws {
        let json = #"""
        {"words":{"title":"Your brief","when":"Morning brief · 08:00","urgent":"Urgent","done":"Done since last evening"},
         "decisions":[{"id":"hold:ce_1","question":"Refund order 4502, €480.00?","deadline":"by 10:51","urgent":true,
                       "answers":[{"words":"Approve","answer":"approve"},{"words":"Refuse","answer":"refuse"}],
                       "ref":{"kind":"hold","id":"ce_1","taf":"depl_1"}},
                      {"id":"step:h1","question":"Sign in to Stripe again?","answers":[{"words":"Open","answer":"open"}],
                       "now_why":"It stops at noon.","ref":{"kind":"step","id":"h1","taf":"depl_9"}},
                      {"id":"d3","question":"No answers, so not drawn","answers":[]}],
         "more":{"count":2,"words":"2 more decisions"},
         "done":[{"mission":"Refunds","cases":[{"name":"Order 4501","words":"Refunded"},{"name":"Order 4499","words":"Refunded"},{"name":"Order 4498","words":"Refunded"}]}],
         "rest":{"count":4,"words":"4 tafs need nothing now"}}
        """#
        let brief = try JSONDecoder().decode(TafsCloud.Brief.self, from: Data(json.utf8))
        let wrist = try XCTUnwrap(KeptWatchReading.watchBrief(brief, tafNames: ["depl_1": "Rae"]))
        XCTAssertEqual(wrist.title, "Your brief")
        XCTAssertEqual(wrist.when, "Morning brief · 08:00")
        XCTAssertEqual(wrist.decisions.map(\.id), ["hold:ce_1", "step:h1"], "only decisions the phone draws")
        XCTAssertEqual(wrist.decisions[0].holdID, "ce_1")
        XCTAssertEqual(wrist.decisions[0].taf, "Rae")
        XCTAssertEqual(wrist.decisions[0].deadline, "by 10:51")
        XCTAssertTrue(wrist.decisions[0].urgent)
        XCTAssertNil(wrist.decisions[1].holdID, "a step names no held call")
        XCTAssertNil(wrist.decisions[1].taf, "a taf the phone can't name stays unnamed")
        XCTAssertEqual(wrist.decisions[1].cantWait, "It stops at noon.")
        XCTAssertEqual(wrist.more, "2 more decisions")
        XCTAssertEqual(wrist.done, "Done since last evening")
        XCTAssertEqual(wrist.doneCases, ["Order 4501", "Order 4499"])
        XCTAssertEqual(wrist.rest, "4 tafs need nothing now")
        // Never cut: tafs cloud keeps every urgent decision past its own cap, and `more` counts only the rest.
        let many = (1...7).map { #"{"id":"d\#($0)","question":"Q\#($0)?","urgent":true,"answers":[{"words":"Open","answer":"open"}]}"# }
        let urgentSeven = try JSONDecoder().decode(TafsCloud.Brief.self, from: Data(#"{"decisions":[\#(many.joined(separator: ","))]}"#.utf8))
        XCTAssertEqual(KeptWatchReading.watchBrief(urgentSeven, tafNames: [:])?.decisions.count, 7)
        let empty = try JSONDecoder().decode(TafsCloud.Brief.self, from: Data(#"{"decisions":[],"done":[]}"#.utf8))
        XCTAssertNil(KeptWatchReading.watchBrief(empty, tafNames: [:]), "nothing in it: Needs you")
    }

    func testNeedsYouIsReadAndAnswered() async {
        let reading = Reading(at: now)
        let reply = await send(WatchRequest(.needsYou, made: now), reading: reading)
        XCTAssertEqual(reply, WatchReply(.ok, needsYou: reading.page))
        XCTAssertEqual(reading.reads, 1)
        // The reply survives the wire as it was.
        XCTAssertEqual(WatchMessage.decode(WatchReply.self, from: WatchMessage.encode(reply)), reply)
    }

    func testAnApprovalIsNeverAnswered() async {
        let reading = Reading(at: now)
        for kind in ["approve", "approve_once", "extra_room", "install", "pair", "allow"] {
            let reply = await raw(#"{"kind":"\#(kind)","made":\#(made),"hold":"ce_1"}"#, reading: reading)
            XCTAssertEqual(reply, WatchReply(.refused), kind)
        }
        XCTAssertTrue(reading.untouched)
    }

    func testAnUnknownKindOrMalformedBytesAreRefused() async {
        let reading = Reading(at: now)
        for bytes in [#"{"kind":"pay","made":\#(made)}"#, #"{"kind":"needs_you"}"#, #"{"made":\#(made)}"#, "not json", "", "[]",
                      #"{"kind":"needs_you","made":"yesterday"}"#] {
            let reply = await raw(bytes, reading: reading)
            XCTAssertEqual(reply, WatchReply(.refused), bytes)
        }
        XCTAssertTrue(reading.untouched)
    }

    func testARequestMissingWhatItNamesIsRefused() async {
        let reading = Reading(at: now)
        for request in [WatchRequest(.refuse, made: now), WatchRequest(.refuse, made: now, hold: "  "),
                        WatchRequest(.halt, made: now), WatchRequest(.coach, made: now, text: "")] {
            let reply = await send(request, reading: reading)
            XCTAssertEqual(reply, WatchReply(.refused), "\(request.kind)")
        }
        XCTAssertTrue(reading.untouched)
    }

    func testOnlyALiveRequestIsAnswered() async {
        let reading = Reading(at: now)
        let late = await send(WatchRequest(.haltAll, made: now.addingTimeInterval(-31)), reading: reading)
        XCTAssertEqual(late, WatchReply(.stale), "a Halt 31 s old is dropped")
        let lateRead = await send(WatchRequest(.needsYou, made: now.addingTimeInterval(-31)), reading: reading)
        XCTAssertEqual(lateRead, WatchReply(.stale))
        let ahead = await send(WatchRequest(.needsYou, made: now.addingTimeInterval(31)), reading: reading)
        XCTAssertEqual(ahead, WatchReply(.stale), "nor one from ahead of the phone's clock")
        XCTAssertTrue(reading.untouched)
        let justInTime = await send(WatchRequest(.needsYou, made: now.addingTimeInterval(-29)), reading: reading)
        XCTAssertEqual(justInTime.outcome, .ok)
    }

    func testSignedOutAnswersNothingOfTheAccount() async {
        let reading = Reading(at: now)
        reading.signedIn = false
        let reply = await send(WatchRequest(.needsYou, made: now), reading: reading)
        XCTAssertEqual(reply, WatchReply(.signedOut))
        XCTAssertNil(reply.needsYou)
        XCTAssertTrue(reading.untouched)
    }

    func testAReadThatFailsSaysSoInThePhonesWords() async {
        let reading = Reading(at: now)
        reading.fails = true
        let reply = await send(WatchRequest(.needsYou, made: now), reading: reading)
        XCTAssertEqual(reply, WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff))
    }

    func testCoachGetsTheWordsAsTheWatchWroteThem() async {
        let reading = Reading(at: now)
        let reply = await send(WatchRequest(.coach, made: now, text: "Pause Rae"), reading: reading)
        XCTAssertEqual(reply.coach?.text, "Rae is paused.")
        XCTAssertEqual(reading.coached, ["Pause Rae"])
        XCTAssertTrue(reading.refused.isEmpty && reading.halted.isEmpty)
    }

    /// The watch's id for the words goes on as the message's id, the same when the watch sends them
    /// again; a watch from before it sends none, or a malformed one, and the phone gives it one of its own.
    func testCoachsWordsGoUnderTheWatchsIdForThem() async {
        let reading = Reading(at: now)
        let watchID = UUID(uuidString: "6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90")!
        _ = await send(WatchRequest(.coach, made: now, text: "Pause Rae", message: watchID.uuidString.lowercased()), reading: reading)
        _ = await send(WatchRequest(.coach, made: now, text: "Pause Rae", message: watchID.uuidString.lowercased()), reading: reading)
        XCTAssertEqual(reading.coachedIDs, [watchID, watchID], "sent again, the same message")
        _ = await send(WatchRequest(.coach, made: now, text: "Pause Rae"), reading: reading)
        _ = await send(WatchRequest(.coach, made: now, text: "Pause Rae", message: "not-a-uuid"), reading: reading)
        XCTAssertEqual(reading.coachedIDs.count, 4)
        XCTAssertFalse(reading.coachedIDs[2...].contains(watchID), "none, or malformed: the phone's own")
        XCTAssertNotEqual(reading.coachedIDs[2], reading.coachedIDs[3])
        let wire = WatchMessage.decode(WatchRequest.self, from: WatchMessage.encode(WatchRequest(.coach, made: now, text: "x", message: "m")))
        XCTAssertEqual(wire?.message, "m", "it survives the wire")
    }

    func testRefuseNamesItsHoldAndSaysWhatKIFFDid() async {
        let reading = Reading(at: now)
        let refused = await send(WatchRequest(.refuse, made: now, hold: "ce_1"), reading: reading)
        XCTAssertEqual(refused, WatchReply(.ok, line: Words.Watch.refused))
        XCTAssertEqual(reading.refused, ["ce_1"])
        reading.refuseOutcome = .gone
        let gone = await send(WatchRequest(.refuse, made: now, hold: "ce_1"), reading: reading)
        XCTAssertEqual(gone, WatchReply(.answered, line: Words.Watch.alreadyAnswered))
        reading.refuseOutcome = .needsApp(Words.Alert.kiffDidNotTakeRefusal)
        let failed = await send(WatchRequest(.refuse, made: now, hold: "ce_1"), reading: reading)
        XCTAssertEqual(failed, WatchReply(.failed, line: Words.Alert.kiffDidNotTakeRefusal))
        XCTAssertTrue(reading.halted.isEmpty)
    }

    func testHaltNamesItsTafAndHaltAllNone() async {
        let reading = Reading(at: now)
        let one = await send(WatchRequest(.halt, made: now, taf: "depl_1"), reading: reading)
        XCTAssertEqual(one, WatchReply(.ok, line: "Halted. Resuming starts only new work."))
        // A Halt all naming a taf still halts everything it says: never a taf in its place.
        _ = await send(WatchRequest(.haltAll, made: now, taf: "depl_1"), reading: reading)
        XCTAssertEqual(reading.halted, ["depl_1", nil])
        reading.haltOutcome = .needsApp(Words.HaltAll.running)
        let failed = await send(WatchRequest(.haltAll, made: now), reading: reading)
        XCTAssertEqual(failed, WatchReply(.failed, line: Words.HaltAll.running))
        XCTAssertTrue(reading.refused.isEmpty)
    }

    func testYourTafsAreRead() async {
        let reading = Reading(at: now)
        let reply = await send(WatchRequest(.tafs, made: now), reading: reading)
        XCTAssertEqual(reply.outcome, .ok)
        XCTAssertEqual(reply.tafs?.map(\.id), ["depl_1"])
    }

    // MARK: Watch v2: a taf's page, a calendar day, the account (reads only)

    /// A taf's page names its taf; one the phone doesn't list says to open the phone. A read: nothing is answered,
    /// halted or signed. With no taf, or an id longer than 128, nothing is read.
    func testATafsPageIsReadByItsTaf() async {
        let reading = Reading(at: now)
        let page = WatchTafPage(id: "depl_1", name: "Rae", state: "Ready", halted: false, job: "Answers refunds",
                                cards: [.init(title: "Create refund", figure: "8", words: "calls left today", rules: ["Create refund: up to 20 a day"])],
                                waiting: 1)
        reading.pages["depl_1"] = page
        let reply = await send(WatchRequest(.taf, made: now, taf: "depl_1"), reading: reading)
        XCTAssertEqual(reply, WatchReply(.ok, tafPage: page))
        XCTAssertEqual(WatchMessage.decode(WatchReply.self, from: WatchMessage.encode(reply)), reply, "it survives the wire")
        let unknown = await send(WatchRequest(.taf, made: now, taf: "depl_9"), reading: reading)
        XCTAssertEqual(unknown, WatchReply(.failed, line: Words.Watch.openOnPhone))
        XCTAssertTrue(reading.refused.isEmpty && reading.halted.isEmpty && reading.coached.isEmpty, "a read: nothing answered")
        let before = reading.reads
        for bad in [WatchRequest(.taf, made: now), WatchRequest(.taf, made: now, taf: "  "),
                    WatchRequest(.taf, made: now, taf: String(repeating: "a", count: 129))] {
            let refused = await send(bad, reading: reading)
            XCTAssertEqual(refused, WatchReply(.refused))
        }
        XCTAssertEqual(reading.reads, before, "nothing read for an incomplete page")
        XCTAssertEqual(reading.askedTaf, ["depl_1", "depl_9"])
    }

    /// A calendar day is `YYYY-MM-DD` or none (today); anything else is refused before any read.
    func testACalendarDayIsADayOrToday() async {
        let reading = Reading(at: now)
        let day = await send(WatchRequest(.calendar, made: now, day: "2026-10-15"), reading: reading)
        XCTAssertEqual(day.outcome, .ok)
        XCTAssertEqual(day.calendar?.day, "2026-10-15")
        let today = await send(WatchRequest(.calendar, made: now), reading: reading)
        XCTAssertEqual(today.outcome, .ok)
        XCTAssertEqual(reading.askedDay, ["2026-10-15", nil])
        for bad in ["15/10/2026", "2026-10-15T00:00", "tomorrow", ""] {
            let refused = await send(WatchRequest(.calendar, made: now, day: bad), reading: reading)
            XCTAssertEqual(refused, WatchReply(.refused), bad)
        }
        XCTAssertEqual(reading.askedDay.count, 2)
    }

    /// The account page: read, and failed in the phone's words when it can't be; signed out answers nothing.
    func testTheAccountIsRead() async {
        let reading = Reading(at: now)
        let reply = await send(WatchRequest(.account, made: now), reading: reading)
        XCTAssertEqual(reply, WatchReply(.ok, account: WatchAccount(email: "owner@example.com", account: "Shop", plan: "Starter")))
        reading.fails = true
        let failed = await send(WatchRequest(.account, made: now), reading: reading)
        XCTAssertEqual(failed, WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff))
        reading.signedIn = false
        let out = await send(WatchRequest(.account, made: now), reading: reading)
        XCTAssertEqual(out, WatchReply(.signedOut))
    }

    /// A watch v2 kind, as bytes any watch could send: a kind the contract has decodes, one it lacks is refused.
    func testTheNewKindsDecodeAndNothingElse() async {
        let reading = Reading(at: now)
        let ok = await raw(#"{"kind":"calendar","made":\#(made),"day":"2026-10-15"}"#, reading: reading)
        XCTAssertEqual(ok.outcome, .ok)
        let unknown = await raw(#"{"kind":"calendar_write","made":\#(made)}"#, reading: reading)
        XCTAssertEqual(unknown, WatchReply(.refused))
    }

    /// A taf's page as the phone's Card tab draws it, from tafs cloud's own home answer (contracts/home.json): its job,
    /// each Card's title and rules, how many of its calls wait, and Halted over its state when it's halted.
    func testATafsPageIsTheCardTabsWords() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("contracts/home.json")
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: try JSONSerialization.data(withJSONObject: try XCTUnwrap(root["answer"])))
        let listed = try XCTUnwrap(home.tafs.first)
        let page = try XCTUnwrap(KeptWatchReading.tafPage(listed, halted: false, waiting: 2))
        let taf = KIFFMapping.taf(listed, id: UUID(), owner: "")
        XCTAssertEqual(page.id, listed.id)
        XCTAssertEqual(page.name, "Rae")
        XCTAssertEqual(page.job, taf.job)
        XCTAssertEqual(page.state, taf.now)
        XCTAssertEqual(page.cards.map(\.title), ["Create refund"])
        XCTAssertEqual(page.cards.first?.rules.count, 1)
        XCTAssertEqual(page.cards.first?.figure, taf.shownCards.first?.hero?.figure)
        XCTAssertEqual(page.waiting, 2)
        XCTAssertFalse(page.halted)
        let halted = try XCTUnwrap(KeptWatchReading.tafPage(listed, halted: true, waiting: 0))
        XCTAssertTrue(halted.halted)
        XCTAssertEqual(halted.state, Words.Taf.State.halted)
    }

    /// A calendar day is that day's events alone, in time order, each with its taf's name, in tafs cloud's words.
    func testACalendarDayIsThatDayInTimeOrder() throws {
        let json = #"""
        {"time_zone":"Europe/Paris","from":"2026-10-15","to":"2026-10-15","words":"2 to come","halted":false,
         "events":[{"taf":"depl_1","name":"Rae","at":"2026-10-15T15:00:00Z","day":"2026-10-15","time":"17:00","minute":1020,"words":"Refund check"},
                   {"taf":"depl_2","name":"Ivo","at":"2026-10-15T07:00:00Z","day":"2026-10-15","time":"09:00","minute":540,"words":"Morning sweep","count":3},
                   {"taf":"depl_1","name":"Rae","at":"2026-10-16T07:00:00Z","day":"2026-10-16","time":"09:00","minute":540,"words":"Next day"}]}
        """#
        let read = try JSONDecoder().decode(TafsCloud.CalendarRead.self, from: Data(json.utf8))
        let day = KeptWatchReading.calendarDay("2026-10-15", read)
        XCTAssertEqual(day.words, "2 to come")
        XCTAssertEqual(day.events, [WatchCalendarDay.Event(time: "09:00", taf: "Ivo", words: "Morning sweep", count: 3),
                                    WatchCalendarDay.Event(time: "17:00", taf: "Rae", words: "Refund check", count: 1)])
        XCTAssertFalse(day.halted)
    }

    /// Today is the account's day, not the phone's: late evening UTC is already tomorrow in Tokyo.
    func testTodayIsTheAccountsDay() throws {
        let late = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-10-14T22:30:00Z"))
        XCTAssertEqual(KeptWatchReading.day(late, in: try XCTUnwrap(TimeZone(identifier: "UTC"))), "2026-10-14")
        XCTAssertEqual(KeptWatchReading.day(late, in: try XCTUnwrap(TimeZone(identifier: "Asia/Tokyo"))), "2026-10-15")
        XCTAssertTrue(WatchRequest.isDay(KeptWatchReading.day(late, in: try XCTUnwrap(TimeZone(identifier: "UTC")))))
    }

    /// The account as the wrist names it: its name, else its slug; a blank line is none.
    func testTheAccountIsNamedByItsNameElseItsSlug() {
        XCTAssertEqual(KeptWatchReading.watchAccount(email: "a@example.com", displayName: "Shop", slug: "shop-1", plan: "Starter"),
                       WatchAccount(email: "a@example.com", account: "Shop", plan: "Starter"))
        XCTAssertEqual(KeptWatchReading.watchAccount(email: nil, displayName: " ", slug: "shop-1", plan: ""),
                       WatchAccount(email: nil, account: "shop-1", plan: nil))
    }

    // MARK: The reads, with the session the app keeps

    private let heldJSON = """
    {"exceptions":[{"id":"ce_2","proposal_id":"p2","subject":"rae-k3f9","action":"refund_order","entity_id":"ord_4503",
      "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000}],
      "parameters":{"amount_cents":20000},"created_at":"2026-10-14T10:42:00Z","expires_at":"2026-10-14T10:58:00Z",
      "accounting_version":1},
     {"id":"ce_1","proposal_id":"p1","subject":"setup","action":"refund_order","entity_id":"ord_4502",
      "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000}],
      "parameters":{"amount_cents":48000},"created_at":"2026-10-14T10:41:00Z","expires_at":"2026-10-14T10:51:00Z",
      "accounting_version":2}]}
    """

    private func clients() -> (KIFFClient, TafsCloud) {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, heldJSON)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme","time_zone":"Asia/Tokyo"}"#)
        return (KIFFClient(baseURL: URL(string: "https://api.test")!, webURL: URL(string: "https://app.test")!, token: "dev_token", session: session),
                TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "dev_token", session: session))
    }

    /// Signs with the current key only. Anything that would use the approval key, or ask for
    /// Face ID, fails the test: nothing from the wrist grants authority.
    private final class CurrentKeyOnly: ApprovalSigning {
        var signed: [String] = []
        var publicKeyDER: Data { Data([0x30, 0x59, 0x01]) }
        var approvalPublicKeyDER: Data { Data([0x30, 0x59, 0x02]) }
        func signConfirmed(_ message: Data) throws -> String {
            signed.append(String(decoding: message, as: UTF8.self))
            return "signature"
        }
        func sign(_ message: Data, reason: String) async throws -> String {
            XCTFail("the watch's path asked for Face ID"); throw ApprovalSigner.SignerError.notAuthenticated
        }
        func sign(_ message: Data, reason: String, grants: Bool) async throws -> String {
            XCTFail("the watch's path asked to grant"); throw ApprovalSigner.SignerError.notAuthenticated
        }
        func signApproval(_ message: Data, reason: String) async throws -> String {
            XCTFail("the watch's path used the approval key"); throw ApprovalSigner.SignerError.notAuthenticated
        }
        func signApprovalConfirmed(_ message: Data) throws -> String {
            XCTFail("the watch's path used the approval key"); throw ApprovalSigner.SignerError.notAuthenticated
        }
        func allowUnlockReuse() { XCTFail("the watch's path reused an unlock") }
    }

    private func kept(_ signer: ApprovalSigning) -> KeptWatchReading {
        let (kiff, cloud) = clients()
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/reject"] = (200, #"{"id":"ce_1"}"#)
        return KeptWatchReading(kiff: kiff, cloud: cloud, signer: signer, tenant: "t1", keyed: true, wait: {})
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    /// Row 21, the phone's own reader: `GET /v1/watch/inbox` for the marks, and `POST` with only the lists that have ids, with
    /// the person's session and nothing signed. A tafs cloud that can't answer is the phone's words, never a mark.
    func testTheInboxMarksAreReadAndPostedWithTheSession() async throws {
        let reading = kept(CurrentKeyOnly())
        StubProtocol.responses["GET /v1/watch/inbox"] = (200, #"{"read":["ce_1"],"dismissed":[]}"#)
        StubProtocol.responses["POST /v1/watch/inbox"] = (200, #"{"read":["ce_1"],"dismissed":["ce_2"]}"#)
        let read = await reading.inboxMarks()
        XCTAssertEqual(read, WatchReply(.ok, marks: WatchInboxMarks(read: ["ce_1"])))
        let marked = await reading.inboxMark(WatchInboxMarks(dismissed: ["ce_2"]))
        XCTAssertEqual(marked, WatchReply(.ok, marks: WatchInboxMarks(read: ["ce_1"], dismissed: ["ce_2"])))
        let post = try XCTUnwrap(sent("POST", "/v1/watch/inbox").first)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: post.httpBody ?? Data()) as? [String: [String]])
        XCTAssertEqual(body, ["dismissed": ["ce_2"]], "only the list with ids; no words, no taf, no amount")
        XCTAssertEqual(post.value(forHTTPHeaderField: "Authorization"), "Bearer dev_token")
        StubProtocol.responses["GET /v1/watch/inbox"] = (503, #"{"error":"down"}"#)
        let down = await reading.inboxMarks()
        XCTAssertEqual(down, WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff))
    }

    func testRefuseFromTheWristIsTheNotificationsSignedRefusalWithTheCurrentKey() async {
        let signer = CurrentKeyOnly()
        let outcome = await kept(signer).refuse(hold: "ce_1", now: now)
        XCTAssertEqual(outcome, .refused(left: 1))
        XCTAssertEqual(sent("POST", "/v1/me/card-exceptions/ce_1/reject").count, 1)
        XCTAssertEqual(signer.signed.count, 1)
        XCTAssertTrue(signer.signed[0].contains("ce_1"), "it signs that hold's refusal")
    }

    func testAHoldAlreadyAnsweredIsNotRefusedAgain() async {
        let signer = CurrentKeyOnly()
        let outcome = await kept(signer).refuse(hold: "ce_9", now: now)
        XCTAssertEqual(outcome, .gone)
        XCTAssertTrue(StubProtocol.sent.allSatisfy { $0.httpMethod == "GET" }, "no second call to KIFF")
        XCTAssertTrue(signer.signed.isEmpty, "nothing signed")
    }

    func testHaltFromTheWristIsTheAppsHaltWithNoSignature() async {
        let reading = kept(CurrentKeyOnly())
        let halted = #"{"halt":{"state":"halted","halted_at":"2026-10-24T12:00:00Z","runtime":true,"kiff":true,"planque":true,"account":false,"stopped":1}}"#
        StubProtocol.responses["POST /v1/tafs/depl_1/halt"] = (200, halted)
        StubProtocol.responses["POST /v1/halt"] = (200, halted)
        let one = await reading.halt(taf: "depl_1")
        XCTAssertEqual(one, .done(Words.Halt.Explain.halted))
        XCTAssertEqual(sent("POST", "/v1/tafs/depl_1/halt").count, 1)
        XCTAssertTrue(sent("POST", "/v1/halt").isEmpty, "one taf, not all")
        _ = await reading.halt(taf: nil)
        XCTAssertEqual(sent("POST", "/v1/halt").count, 1)
        XCTAssertTrue(StubProtocol.sent.allSatisfy { $0.value(forHTTPHeaderField: "KIFF-Device-Signature") == nil },
                      "halting only takes authority away: no signature")
    }

    func testEachTafWithItsHalt() async throws {
        let reading = kept(CurrentKeyOnly())
        StubProtocol.responses["GET /v1/halt"] = (200, #"{"halt":{"state":"running","runtime":false,"kiff":false,"planque":false,"account":false}}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/halt"] = (200, #"{"halt":{"state":"halted","halted_at":"2026-10-24T12:00:00Z","runtime":true,"kiff":true,"planque":true,"account":false,"stopped":1}}"#)
        let tafs = try await reading.tafs()
        XCTAssertEqual(tafs, [WatchTaf(id: "depl_1", name: "Rae", line: Words.Taf.State.halted, halted: true, paused: false)],
                       "halted, so not paused: its chat takes nothing")
    }

    func testATafsOwnHaltIsReadWhenTheAccountsCantBe() async throws {
        let reading = kept(CurrentKeyOnly())
        StubProtocol.responses["GET /v1/halt"] = (500, #"{"error":"unavailable"}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/halt"] = (200, #"{"halt":{"state":"halted","halted_at":"2026-10-24T12:00:00Z","runtime":true,"kiff":true,"planque":true,"account":false,"stopped":1}}"#)
        let tafs = try await reading.tafs()
        XCTAssertEqual(tafs.first?.halted, true, "a taf halted on its own isn't offered Halt again")
    }

    // MARK: Coach

    private static let setupJSON = #"{"id":"taf-setup","name":"Setup","job":"setup","kiff_agent":"setup","status":"active","next_run":null,"last_run":null}"#

    private func coachReading() -> KeptWatchReading {
        let reading = kept(CurrentKeyOnly())
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        return reading
    }

    func testCoachsAnswerIsReadAfterThePersonsWords() async {
        let reading = coachReading()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false,"message":{"id":"m1"}}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s1"] = (200, #"{"messages":[{"at":"2026-10-14T10:44:00Z","from":"taf","kind":"message","text":"An older answer","session":"s1"},{"id":"m1","at":"2026-10-14T10:45:00Z","from":"owner","kind":"message","text":"Pause Rae","session":"s1"},{"at":"2026-10-14T10:45:05Z","from":"taf","kind":"message","text":"Rae is paused.","session":"s1"}],"components":[]}"#)
        let messageID = UUID()
        let reply = await reading.coach(text: "Pause Rae", messageID: messageID)
        XCTAssertEqual(reply, WatchReply(.ok, coach: WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false,
                                                                      at: ISO8601DateFormatter().date(from: "2026-10-14T10:45:05Z"))))
        XCTAssertEqual(StubProtocol.sent.filter { $0.httpMethod == "POST" }.map { $0.url?.path }, ["/v1/tafs/taf-setup/messages"],
                       "the words, once, and nothing signed or approved")
        let body = sent("POST", "/v1/tafs/taf-setup/messages").last?.httpBody
            .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        XCTAssertEqual(body?["client_message_id"] as? String, messageID.uuidString.lowercased(), "under the message's id")
    }

    /// W2: v2's Send is answered as soon as tafs cloud has the words, with where Coach's answer will be; nothing is
    /// read yet, so the watch can say "Sent" at once.
    func testCoachSendAnswersOnceTafsCloudHasTheWords() async {
        let reading = coachReading()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false,"message":{"id":"m1"}}"#)
        let messageID = UUID()
        let reply = await reading.coachSend(text: "Pause Rae", messageID: messageID)
        XCTAssertEqual(reply, WatchReply(.ok, ticket: WatchCoachTicket(chat: "taf-setup", session: "s1", sent: "m1")))
        XCTAssertTrue(sent("GET", "/v1/tafs/taf-setup/conversation/s1").isEmpty, "answered before Coach's thread is read")
        let body = sent("POST", "/v1/tafs/taf-setup/messages").last?.httpBody
            .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        XCTAssertEqual(body?["client_message_id"] as? String, messageID.uuidString.lowercased(), "under the message's id, as v1")
    }

    // MARK: Asking from the watch (docs/watch-ask.md)

    /// v2's Send goes as a watch request of its own: the exact `{client_id, text}` body, signed with the device key (no
    /// prompt, never the approval key), under the watch's id for the words. The ticket names the request; Coach's chat
    /// isn't written to.
    func testASendIsAWatchRequestSignedWithTheDeviceKey() async throws {
        let signer = CurrentKeyOnly()
        let reading = kept(signer)
        StubProtocol.responses["POST /v1/watch/asks"] = (200, #"{"ask":{"id":"wa_1","client_id":"x","state":"accepted","words":"Sent · Coach has it"}}"#)
        let messageID = UUID()
        let reply = await reading.coachSend(text: "Pause Rae", messageID: messageID, after: nil)
        XCTAssertEqual(reply, WatchReply(.ok, ticket: WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_1", sent: nil)))
        let request = try XCTUnwrap(sent("POST", "/v1/watch/asks").last)
        let body = try XCTUnwrap(request.httpBody)
        XCTAssertEqual(String(decoding: body, as: UTF8.self), #"{"client_id":"\#(messageID.uuidString.lowercased())","text":"Pause Rae"}"#)
        XCTAssertTrue(request.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasPrefix("t=") ?? false)
        XCTAssertEqual(signer.signed.count, 1, "signed once, with the device key")
        XCTAssertTrue(signer.signed[0].contains("/v1/watch/asks"))
        XCTAssertTrue(sent("POST", "/v1/tafs/taf-setup/messages").isEmpty, "Coach's chat isn't written to")
    }

    /// The next Send of the watch's conversation replies to the last request (`reply_to`); one that's still at work, or gone,
    /// makes the words a request of their own.
    func testASendAfterARequestRepliesToIt() async throws {
        let reading = kept(CurrentKeyOnly())
        let last = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_1", sent: nil)
        StubProtocol.responses["POST /v1/watch/asks"] = (200, #"{"ask":{"id":"wa_2","client_id":"x","state":"accepted","words":"Sent"}}"#)
        _ = await reading.coachSend(text: "Until Monday", messageID: UUID(), after: last)
        let body = try XCTUnwrap(sent("POST", "/v1/watch/asks").last?.httpBody)
        XCTAssertTrue(String(decoding: body, as: UTF8.self).hasSuffix(#","reply_to":"wa_1"}"#))
        // A ticket of Coach's chat names no request: nothing to reply to.
        _ = await reading.coachSend(text: "Hi", messageID: UUID(), after: WatchCoachTicket(chat: "taf-setup", session: "s1", sent: "m1"))
        let plain = try XCTUnwrap(sent("POST", "/v1/watch/asks").last?.httpBody)
        XCTAssertFalse(String(decoding: plain, as: UTF8.self).contains("reply_to"))
        // Still at work: the words go on their own.
        StubProtocol.responses["POST /v1/watch/asks"] = (409, #"{"error":"wait for its result before replying","code":"reply_too_soon"}"#)
        StubProtocol.thenRespond["POST /v1/watch/asks"] = ["POST /v1/watch/asks": (200, #"{"ask":{"id":"wa_3","client_id":"x","state":"accepted","words":"Sent"}}"#)]
        let alone = await reading.coachSend(text: "And Lea", messageID: UUID(), after: last)
        XCTAssertEqual(alone.ticket?.session, "wa_3")
        XCTAssertFalse(String(decoding: sent("POST", "/v1/watch/asks").last?.httpBody ?? Data(), as: UTF8.self).contains("reply_to"))
    }

    /// A phone whose session can only view (KIFF's 403 `view_only`, the simulator's on 19 Nov): the watch says so in its own
    /// words, never "a reply wasn't sent", and Coach's chat isn't written to.
    func testAViewOnlyPhoneSaysSoOnTheWatch() async {
        let reading = kept(CurrentKeyOnly())
        StubProtocol.responses["POST /v1/watch/asks"] = (403, #"{"error":"this device can only view","code":"view_only"}"#)
        let reply = await reading.coachSend(text: "What needs me today?", messageID: UUID(), after: nil)
        XCTAssertEqual(reply, WatchReply(.failed, line: Words.Watch.viewOnlyPhone))
        XCTAssertTrue(sent("POST", "/v1/tafs/taf-setup/messages").isEmpty)
    }

    /// Where tafs cloud's switch for watch requests is off (404 `watch_off`), the words go to Coach's chat, as before.
    func testASendWithTheSwitchOffGoesToCoachsChat() async {
        let reading = coachReading()
        StubProtocol.responses["POST /v1/watch/asks"] = (404, #"{"error":"asking from the watch isn't on yet","code":"watch_off"}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false,"message":{"id":"m1"}}"#)
        let reply = await reading.coachSend(text: "Pause Rae", messageID: UUID(), after: nil)
        XCTAssertEqual(reply, WatchReply(.ok, ticket: WatchCoachTicket(chat: "taf-setup", session: "s1", sent: "m1")))
    }

    /// A request is read by its id: at work is "still answering"; ended, its line (else tafs cloud's words); a call held for
    /// the owner, its line with "Approve on your phone". Coach's setup is never read for it, and nothing is sent.
    func testARequestIsReadByItsId() async {
        let reading = kept(CurrentKeyOnly())
        let ticket = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_1", sent: nil)
        StubProtocol.responses["GET /v1/watch/asks/wa_1"] = (200, #"{"ask":{"id":"wa_1","client_id":"x","state":"working","taf":{"id":"t","name":"Rae"},"words":"Rae is answering"}}"#)
        let working = await reading.coachRead(ticket)
        XCTAssertEqual(working, WatchReply(.ok, answering: true))
        StubProtocol.responses["GET /v1/watch/asks/wa_1"] = (200, #"{"ask":{"id":"wa_1","client_id":"x","state":"working","line":"Rae's refund waits for you on your phone.","words":"Rae is answering"}}"#)
        let held = await reading.coachRead(ticket)
        XCTAssertEqual(held.coach?.approveOnPhone, true)
        XCTAssertEqual(held.answering, false)
        StubProtocol.responses["GET /v1/watch/asks/wa_1"] = (200, #"{"ask":{"id":"wa_1","client_id":"x","state":"done","line":"Rae is paused until Monday.","words":"Done"}}"#)
        let done = await reading.coachRead(ticket)
        XCTAssertEqual(done, WatchReply(.ok, coach: WatchCoachAnswer(text: "Rae is paused until Monday.", more: false, approveOnPhone: false), answering: false))
        StubProtocol.responses["GET /v1/watch/asks/wa_1"] = (200, #"{"ask":{"id":"wa_1","client_id":"x","state":"no_taf","words":"No taf took it"}}"#)
        let none = await reading.coachRead(ticket)
        XCTAssertEqual(none.coach?.text, "No taf took it", "tafs cloud's words, with no line")
        XCTAssertTrue(sent("GET", "/v1/setup").isEmpty)
        XCTAssertTrue(StubProtocol.sent.filter { $0.httpMethod == "POST" }.isEmpty)
    }

    // MARK: Chatting with a taf (docs/watch-ask.md)

    /// The words go to the taf the watch names: `taf_id` is in the exact body the device key signs, so it can't be changed
    /// after, and Coach's chat isn't written to.
    func testATafChatSendNamesTheTafInTheSignedBody() async throws {
        let signer = CurrentKeyOnly()
        let reading = kept(signer)
        StubProtocol.responses["POST /v1/watch/asks"] = (200, #"{"ask":{"id":"wa_9","client_id":"x","state":"accepted","words":"Sent · Refunds has it"}}"#)
        let messageID = UUID()
        let reply = await reading.tafSend(taf: "taf-0a1b2c3d4e5f60718293", text: "Refund A-1052 too", messageID: messageID, after: nil)
        XCTAssertEqual(reply, WatchReply(.ok, ticket: WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_9", sent: nil)))
        let body = try XCTUnwrap(sent("POST", "/v1/watch/asks").last?.httpBody)
        XCTAssertEqual(String(decoding: body, as: UTF8.self),
                       #"{"client_id":"\#(messageID.uuidString.lowercased())","text":"Refund A-1052 too","taf_id":"taf-0a1b2c3d4e5f60718293"}"#)
        XCTAssertEqual(signer.signed.count, 1, "signed once, with the device key")
        XCTAssertTrue(sent("POST", "/v1/tafs/taf-setup/messages").isEmpty, "Coach's chat isn't written to")
        // A follow-up replies to the taf's last request.
        _ = await reading.tafSend(taf: "taf-0a1b2c3d4e5f60718293", text: "And A-1053?", messageID: UUID(),
                                  after: WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_9", sent: nil))
        let next = String(decoding: try XCTUnwrap(sent("POST", "/v1/watch/asks").last?.httpBody), as: UTF8.self)
        XCTAssertTrue(next.hasSuffix(#","reply_to":"wa_9","taf_id":"taf-0a1b2c3d4e5f60718293"}"#))
    }

    /// Whatever tafs cloud refuses, a taf's words never go to Coach: with the switch off (`watch_taf_chat_off`), a taf the
    /// person may not use (`no_taf`) or Coach's own taf (`coach_taf`), the watch says to open the phone.
    func testATafChatNeverFallsBackToCoach() async {
        let reading = coachReading()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false,"message":{"id":"m1"}}"#)
        for (status, code) in [(404, "watch_taf_chat_off"), (404, "no_taf"), (404, "watch_off"), (400, "coach_taf")] {
            StubProtocol.responses["POST /v1/watch/asks"] = (status, #"{"error":"no","code":"\#(code)"}"#)
            let reply = await reading.tafSend(taf: "taf-0a1b2c3d4e5f60718293", text: "Hi", messageID: UUID(), after: nil)
            XCTAssertEqual(reply, WatchReply(.failed, line: Words.Watch.openOnPhone), code)
        }
        XCTAssertTrue(sent("POST", "/v1/tafs/taf-setup/messages").isEmpty, "never Coach's chat")
        StubProtocol.responses["POST /v1/watch/asks"] = (403, #"{"error":"this device can only view","code":"view_only"}"#)
        let viewOnly = await reading.tafSend(taf: "taf-0a1b2c3d4e5f60718293", text: "Hi", messageID: UUID(), after: nil)
        XCTAssertEqual(viewOnly, WatchReply(.failed, line: Words.Watch.viewOnlyPhone))
    }

    /// A taf chat's request that ends waiting on the person (`held: true`) says "Approve on your phone"; one whose answer
    /// went on past 400 characters (`more: true`) says the rest is on the phone.
    func testATafChatsAnswerSaysHeldAndMore() async {
        let reading = kept(CurrentKeyOnly())
        let ticket = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_9", sent: nil)
        StubProtocol.responses["GET /v1/watch/asks/wa_9"] = (200, #"{"ask":{"id":"wa_9","client_id":"x","state":"done","held":true,"line":"A-1039 is €84, over its Card. It waits for you.","words":"Done"}}"#)
        let held = await reading.coachRead(ticket)
        XCTAssertEqual(held.coach?.approveOnPhone, true)
        XCTAssertEqual(held.answering, false)
        StubProtocol.responses["GET /v1/watch/asks/wa_9"] = (200, #"{"ask":{"id":"wa_9","client_id":"x","state":"done","more":true,"line":"This week: 31 refunds paid…","words":"Done"}}"#)
        let long = await reading.coachRead(ticket)
        XCTAssertEqual(long.coach, WatchCoachAnswer(text: "This week: 31 refunds paid…", more: true, approveOnPhone: false))
    }

    /// A taf chat's Send names its taf, or it's refused before anything is read or signed; a reader from before it sends
    /// nothing.
    func testATafChatSendNamesItsTaf() async {
        let reading = Reading(at: now)
        for taf in [" ", String(repeating: "a", count: WatchRequest.tafIDLimit + 1)] {
            let refused = await send(WatchRequest(.coachSend, made: now, taf: taf, text: "Hi"), reading: reading)
            XCTAssertEqual(refused, WatchReply(.refused))
        }
        XCTAssertTrue(reading.untouched)
        let old = await send(WatchRequest(.coachSend, made: now, taf: "taf-0a1b2c3d4e5f60718293", text: "Hi"), reading: reading)
        XCTAssertEqual(old, WatchReply(.notYet), "the fake reader is one from before chatting with a taf")
    }

    /// Read more reads a watch request's whole answer (`?full=1`), never cut at the wrist's 300; a ticket of Coach's chat
    /// names no request and is refused unread.
    func testReadMoreReadsTheWholeAnswerByTheRequestsId() async {
        let reading = kept(CurrentKeyOnly())
        let long = String(repeating: "Refunded A-1052. ", count: 40)
        StubProtocol.responses["GET /v1/watch/asks/wa_9?full=1"] = (200, #"{"ask":{"id":"wa_9","client_id":"x","state":"done","more":true,"line":"Refunded A-1052.","words":"Done"},"text":"\#(long)"}"#)
        let whole = await reading.askFull(WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_9", sent: nil))
        XCTAssertEqual(whole.coach?.text, long.trimmingCharacters(in: .whitespaces), "the whole `text`, not the line")
        XCTAssertEqual(whole.coach?.more, false)
        XCTAssertEqual(StubProtocol.sent.last?.url?.query, "full=1")
        // tafs cloud's shape (#1505): `text` beside `ask`. One inside `ask` is not the whole answer.
        StubProtocol.responses["GET /v1/watch/asks/wa_9?full=1"] = (200, #"{"ask":{"id":"wa_9","client_id":"x","state":"done","more":true,"line":"Refunded A-1052.","text":"inside","words":"Done"}}"#)
        let inside = await reading.askFull(WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_9", sent: nil))
        XCTAssertEqual(inside, WatchReply(.failed, line: Words.Watch.moreOnPhone))
        // No `text` (the run hasn't finished, or the taf is no longer the person's to use): the rest is on the phone.
        StubProtocol.responses["GET /v1/watch/asks/wa_9?full=1"] = (200, #"{"ask":{"id":"wa_9","client_id":"x","state":"done","more":true,"line":"Refunded A-1052.","words":"Done"}}"#)
        let none = await reading.askFull(WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_9", sent: nil))
        XCTAssertEqual(none, WatchReply(.failed, line: Words.Watch.moreOnPhone))
        let coachs = await reading.askFull(WatchCoachTicket(chat: "taf-setup", session: "s1", sent: "m1"))
        XCTAssertEqual(coachs, WatchReply(.refused))
        StubProtocol.responses["GET /v1/watch/asks/wa_9?full=1"] = (503, #"{"error":"unavailable"}"#)
        let unread = await reading.askFull(WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_9", sent: nil))
        XCTAssertEqual(unread, WatchReply(.failed, line: Words.Watch.moreOnPhone))
    }

    /// A taf's chat's last turns: messages only, with words, the last six, each cut as an answer is; a taf's chat with no
    /// taf named is refused before anything is read.
    func testATafsChatsLastTurnsAreItsMessagesCut() async {
        let at = Date(timeIntervalSince1970: 1_763_600_000)
        func m(_ i: Int, _ from: TafsCloud.ChatMessage.From, _ text: String, kind: TafsCloud.ChatMessage.Kind = .message) -> TafsCloud.ChatMessage {
            TafsCloud.ChatMessage(at: at.addingTimeInterval(Double(i)), from: from, kind: kind, text: text, session: "s1", serverID: "m\(i)")
        }
        var messages = [m(0, .taf, "Kickoff", kind: .kickoff), m(1, .owner, "  ")]
        for i in 2..<10 { messages.append(m(i, i % 2 == 0 ? .owner : .taf, "Turn \(i)")) }
        messages.append(m(10, .taf, String(repeating: "word ", count: 100)))
        let lines = KeptWatchReading.chatLines(messages)
        XCTAssertEqual(lines.count, WatchChatLine.last)
        XCTAssertEqual(lines.first?.text, "Turn 5")
        XCTAssertEqual(lines.map(\.mine), [false, true, false, true, false, false])
        XCTAssertEqual(lines.last?.more, true)
        XCTAssertLessThanOrEqual(lines.last?.text.count ?? 0, WatchCoachAnswer.wristLength + 1)
        let reading = Reading(at: now)
        let none = await send(WatchRequest(.tafChat, made: now, taf: " "), reading: reading)
        XCTAssertEqual(none, WatchReply(.refused))
        XCTAssertTrue(reading.untouched)
    }

    /// Read more names a watch request's ticket, or it's refused before anything is read.
    func testReadMoreNamesARequest() async {
        let reading = Reading(at: now)
        let none = await send(WatchRequest(.askFull, made: now), reading: reading)
        XCTAssertEqual(none, WatchReply(.refused))
        let coachs = await send(WatchRequest(.askFull, made: now, ticket: WatchCoachTicket(chat: "taf-setup", session: "s1")), reading: reading)
        XCTAssertEqual(coachs, WatchReply(.refused))
        XCTAssertTrue(reading.untouched)
    }

    /// W2: a refusal at Send is v1's, in the phone's words.
    func testCoachSendRefusedForTheAIConsentSaysToAllowItOnThePhone() async {
        let reading = coachReading()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (403, #"{"error":"Allow AI use first.","code":"ai_consent_required","consent":{"given":false}}"#)
        let reply = await reading.coachSend(text: "Pause Rae", messageID: UUID())
        XCTAssertEqual(reply, WatchReply(.failed, line: Words.Watch.coachNeedsConsent))
    }

    /// W2: each read is Coach's answer so far, and whether its chat says it's still working.
    func testCoachReadSaysWhetherCoachIsStillAtIt() async {
        KeptWatchReading.coachChats = []
        let reading = coachReading()
        let ticket = WatchCoachTicket(chat: "taf-setup", session: "s1", sent: "m1")
        let mine = #"{"id":"m1","at":"2026-10-14T10:45:00Z","from":"owner","kind":"message","text":"Pause Rae","session":"s1"}"#
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s1"] = (200, #"{"messages":[\#(mine)],"components":[],"working":{"session":"s1"}}"#)
        let working = await reading.coachRead(ticket)
        XCTAssertEqual(working, WatchReply(.ok, answering: true))
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s1"] = (200, #"{"messages":[\#(mine),{"at":"2026-10-14T10:45:05Z","from":"taf","kind":"message","text":"Rae is paused.","session":"s1"}],"components":[]}"#)
        let done = await reading.coachRead(ticket)
        XCTAssertEqual(done, WatchReply(.ok, coach: WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false,
                                                                     at: ISO8601DateFormatter().date(from: "2026-10-14T10:45:05Z")), answering: false))
        XCTAssertTrue(StubProtocol.sent.filter { $0.httpMethod == "POST" }.isEmpty, "a read sends nothing")
    }

    /// W2: a read names Coach's chat, or nothing is read: never another taf's conversation.
    func testCoachReadReadsOnlyCoachsChat() async {
        KeptWatchReading.coachChats = []
        let reading = coachReading()
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation/s1"] = (200, #"{"messages":[],"components":[]}"#)
        let refused = await reading.coachRead(WatchCoachTicket(chat: "depl_1", session: "s1", sent: nil))
        XCTAssertEqual(refused, WatchReply(.refused))
        XCTAssertTrue(sent("GET", "/v1/tafs/depl_1/conversation/s1").isEmpty, "Rae's conversation is never read")
        // tafs cloud out of reach is said as such, not refused, and nothing is read.
        StubProtocol.responses["GET /v1/setup"] = (503, #"{"error":"unavailable"}"#)
        let unreached = await reading.coachRead(WatchCoachTicket(chat: "taf-setup", session: "s1", sent: nil))
        XCTAssertEqual(unreached, WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff))
        XCTAssertTrue(sent("GET", "/v1/tafs/taf-setup/conversation/s1").isEmpty)
    }

    /// W2's kinds decode, and a read with no ticket, or an empty one, is refused before anything is read.
    func testACoachReadNamesItsTicket() async {
        let reading = Reading(at: now)
        let none = await send(WatchRequest(.coachRead, made: now), reading: reading)
        XCTAssertEqual(none, WatchReply(.refused))
        let empty = await send(WatchRequest(.coachRead, made: now, ticket: WatchCoachTicket(chat: " ", session: "s1")), reading: reading)
        XCTAssertEqual(empty, WatchReply(.refused))
        let long = await send(WatchRequest(.coachSend, made: now, text: String(repeating: "a", count: WatchRequest.coachLimit + 1)),
                              reading: reading)
        XCTAssertEqual(long, WatchReply(.refused))
        XCTAssertTrue(reading.untouched)
        let read = await send(WatchRequest(.coachRead, made: now, ticket: WatchCoachTicket(chat: "taf-setup", session: "s1")),
                              reading: reading)
        XCTAssertEqual(read, WatchReply(.notYet), "the fake reader is one from before W2")
    }

    func testCoachRefusedForTheAIConsentSaysToAllowItOnThePhone() async {
        let reading = coachReading()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (403, #"{"error":"Allow AI use first.","code":"ai_consent_required","consent":{"given":false}}"#)
        let reply = await reading.coach(text: "Pause Rae", messageID: UUID())
        XCTAssertEqual(reply, WatchReply(.failed, line: Words.Watch.coachNeedsConsent))
    }

    func testACardInCoachsAnswerIsOnlyApproveOnYourPhone() throws {
        let at = { (s: TimeInterval) in self.now.addingTimeInterval(s) }
        var thread = SetupThread()
        thread.messages = [TafsCloud.ChatMessage(at: at(0), from: .owner, text: "Connect Stripe", session: "s1", serverID: "m1"),
                           TafsCloud.ChatMessage(at: at(3), from: .taf, text: "Approve the connection.", session: "s1")]
        thread.components = [ShownComponent(firstAt: at(4), session: "s1", run: "r1",
                                            component: try SetupTests.component(#"{"type":"approval","id":"connect","waits":"owner","grant":"g_01"}"#))]
        let answer = try XCTUnwrap(KeptWatchReading.answer(in: thread, after: "m1", session: "s1"))
        XCTAssertTrue(answer.approveOnPhone)
        XCTAssertEqual(answer.text, "Approve the connection.", "Coach's words only: nothing of the card")
        XCTAssertNil(KeptWatchReading.answer(in: SetupThread(), after: "m1", session: "s1"), "nothing written yet")
    }

    func testAnOlderAnswerIsNeverTakenForThisOne() throws {
        let at = { (s: TimeInterval) in self.now.addingTimeInterval(s) }
        var thread = SetupThread()
        // Coach's earlier answer and card, in the same chat; the person's new message isn't in yet.
        thread.messages = [TafsCloud.ChatMessage(at: at(-60), from: .owner, text: "Connect Stripe", session: "s1", serverID: "m0"),
                           TafsCloud.ChatMessage(at: at(-55), from: .taf, text: "Approve the connection.", session: "s1")]
        thread.components = [ShownComponent(firstAt: at(-54), session: "s1", run: "r1",
                                            component: try SetupTests.component(#"{"type":"approval","id":"connect","waits":"owner","grant":"g_01"}"#))]
        XCTAssertNil(KeptWatchReading.answer(in: thread, after: "m1", session: "s1"), "not yet: keep waiting")
        // Then the person's message and Coach's reply to it.
        thread.messages += [TafsCloud.ChatMessage(at: at(0), from: .owner, text: "Pause Rae", session: "s1", serverID: "m1"),
                            TafsCloud.ChatMessage(at: at(4), from: .taf, text: "Rae is paused.", session: "s1")]
        let answer = try XCTUnwrap(KeptWatchReading.answer(in: thread, after: "m1", session: "s1"))
        XCTAssertEqual(answer, WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false, at: at(4)),
                       "the older card isn't this answer's; it carries when Coach's reply came")
    }

    func testCoachsWordsOverTwoThousandCharactersAreRefused() async {
        let reading = Reading(at: now)
        let long = String(repeating: "a", count: WatchRequest.coachLimit + 1)
        let refused = await send(WatchRequest(.coach, made: now, text: long), reading: reading)
        XCTAssertEqual(refused, WatchReply(.refused))
        XCTAssertTrue(reading.untouched, "nothing sent to Coach")
        let atLimit = await send(WatchRequest(.coach, made: now, text: String(repeating: "a", count: WatchRequest.coachLimit)), reading: reading)
        XCTAssertEqual(atLimit.outcome, .ok)
    }

    func testThePhoneAnswersWellWithinTheWatchsWait() {
        XCTAssertLessThanOrEqual(KeptWatchReading.coachWait, 15)
    }

    func testALongAnswerIsCutAtAWord() {
        let long = Array(repeating: "refund", count: 80).joined(separator: " ")
        let cut = WatchCoachAnswer.cut(long, more: false, approveOnPhone: false)
        XCTAssertTrue(cut.more)
        XCTAssertLessThanOrEqual(cut.text.count, WatchCoachAnswer.wristLength + 1)
        XCTAssertTrue(cut.text.hasSuffix("refund…"))
    }

    func testTheReadsUseTheSessionTheAppKeeps() {
        XCTAssertEqual(HoldNotification.sessionAccount, AppModel.tokenAccount)
    }

    func testEachHeldCallInThePhonesWordsTheSoonestFirst() async throws {
        let (kiff, cloud) = clients()
        let page = try await KeptWatchReading(kiff: kiff, cloud: cloud, signer: CurrentKeyOnly(), tenant: "t1", keyed: true).needsYou(now: now)
        XCTAssertEqual(page.calls.map(\.id), ["ce_1", "ce_2"], "the soonest to lapse first")
        XCTAssertEqual(page.calls.map(\.taf), [Words.Coach.title, "Rae"], "Coach's own agent is named Coach")
        XCTAssertEqual(page.calls.map { String($0.answerBy.prefix(5)) }, ["19:51", "19:58"], "in the account's zone, Tokyo")
        let tokyo = try XCTUnwrap(TimeZone(identifier: "Asia/Tokyo"))
        XCTAssertEqual(page.calls[0].answerBy, HoldNotification.time(now.addingTimeInterval(6 * 60), in: tokyo),
                       "as the phone's notification writes it, with the zone when the phone's clock differs")
        let hold = try XCTUnwrap(try KIFFClient.decoder.decode([String: [KIFFClient.CardException]].self, from: Data(heldJSON.utf8))["exceptions"]?.last)
        let call = KIFFMapping.held(hold, tafID: UUID())
        XCTAssertEqual(page.calls[0].what, "\(call.proposed.summary), \(try XCTUnwrap(call.proposed.amount).formatted)")
        XCTAssertEqual(page.calls[0].reason, call.reason)
        XCTAssertEqual(page.count, 2, "with no count from tafs cloud, the held calls")
        XCTAssertEqual(page.at, now)
        XCTAssertTrue(StubProtocol.sent.allSatisfy { $0.httpMethod == "GET" }, "a read sends nothing")
    }

    func testNoSessionKeptIsSignedOut() {
        let (_, cloud) = clients()
        XCTAssertFalse(KeptWatchReading(kiff: KIFFClient(), cloud: cloud, signer: CurrentKeyOnly(), tenant: nil, keyed: false).signedIn)
    }

    /// The summary the watch may keep holds no amount, even where KIFF's words write one in, in the ways tafs' languages
    /// write money (qa-kiff's table); an order's own number stays.
    func testTheSummaryForTheWatchHoldsNoAmount() {
        let cases: [(String, Money?, String)] = [
            ("Refund order 4502", nil, "Refund order 4502"),
            ("Refund €150.00 to order 4502", nil, "Refund to order 4502"),
            ("Refund order 4502, €150.00", nil, "Refund order 4502"),
            ("Pay 150.00 EUR to the supplier", nil, "Pay to the supplier"),
            ("Refund 150,00 € on order 4502", nil, "Refund on order 4502"),
            ("Refund 1 250,50 € now", nil, "Refund now"),
            ("Ship 3 items to Lyon", nil, "Ship 3 items to Lyon"),
            ("Refund order 1042 EUR 150.00", nil, "Refund order 1042"),
            ("Zwrot zamówienia 1042, 150 zł", nil, "Zwrot zamówienia 1042"),
            ("Zwrot 150 PLN", nil, "Zwrot"),
            ("Refund 150 euros to Ana", nil, "Refund to Ana"),
            ("Rembourser 150 euros", nil, "Rembourser"),
            ("Refund 20 dollars", nil, "Refund"),
            ("Återbetala 200 kr", nil, "Återbetala"),
            ("Refund order 4502 150 €", nil, "Refund order 4502"),
            ("Rückerstattung Fr. 80.50", nil, "Rückerstattung"),
            // The call's own amount goes whatever sits beside it, or nothing does.
            ("Refund 150 to Ana", Money(minor: 15_000, currency: "EUR", scale: 2), "Refund to Ana"),
            ("Refund 1,234.56 for order 77", Money(minor: 123_456, currency: "USD", scale: 2), "Refund for order 77"),
            ("Refund 1 234,56 now", Money(minor: 123_456, currency: "EUR", scale: 2), "Refund now"),
            ("Refund 300 XYZ to Ana", Money(minor: 300, currency: "XYZ", scale: 0), "Refund to Ana"),
            ("Order 4150 refund", Money(minor: 15_000, currency: "EUR", scale: 2), "Order 4150 refund"),
            ("Order 4150 refund", .bare(150), "Order 4150 refund"),
            // The Swiss apostrophe, a bare amount, and a fraction written without its trailing zeros (dev-android's read).
            ("Rückerstattung CHF 1'234.50", nil, "Rückerstattung"),
            ("Rückerstattung CHF 1’234.50", nil, "Rückerstattung"),
            ("Rückerstattung 1'234.50", Money(minor: 123_450, currency: "CHF", scale: 2), "Rückerstattung"),
            ("Refund 15000 to Ana", .bare(15_000), "Refund to Ana"),
            ("Refund 1234.5 now", Money(minor: 123_450, currency: "EUR", scale: 2), "Refund now"),
            ("Refund 150.5 to Ana", Money(minor: 15_000, currency: "EUR", scale: 2), "Refund 150.5 to Ana"),
            ("Refund 150, then more", .bare(150), "Refund, then more"),
            // A unit right after its figure, and more words (qa-kiff's 07:36); a code inside a word stays.
            ("Zwrot 150zł", nil, "Zwrot"),
            ("Refund 150EUR", nil, "Refund"),
            ("Rückerstattung 80 Franken", nil, "Rückerstattung"),
            ("Zwrot 50 dolarów", nil, "Zwrot"),
            ("Zwrot 150 złotych", nil, "Zwrot"),
            ("Order EUROPE 4 items", nil, "Order EUROPE 4 items"),
            ("Skrzynka 3 krzesła", nil, "Skrzynka 3 krzesła"),
        ]
        for (summary, amount, kept) in cases {
            XCTAssertEqual(WatchRelay.withoutAmount(summary, amount: amount), kept, summary)
        }
    }

    /// A taf with no next run, not paused or halted, shows Home's state on the watch as the iPhone does (mac-air's
    /// walk-through, 19 Nov: two rows had no line), in the same mapping as the iPhone's.
    func testATafsWatchLineFallsBackToHomesState() {
        XCTAssertEqual(TafState(cloudState: "idle").label, Words.Taf.State.ready)
        XCTAssertEqual(TafState(cloudState: "stopped").label, Words.Taf.State.stopped)
        XCTAssertEqual(TafState(cloudState: "working").label, Words.Taf.State.working)
        XCTAssertEqual(TafState(cloudState: "working", unscheduled: true).label, Words.Taf.State.ready, "no schedule: ready, not working")
        XCTAssertEqual(TafState(cloudState: "waiting").label, Words.Taf.State.waiting)
        XCTAssertEqual(TafState(cloudState: "paused").label, Words.Taf.State.paused)
        XCTAssertEqual(TafState(cloudState: "halted").label, Words.Taf.State.halted)
        XCTAssertEqual(TafState(cloudState: "something new").label, Words.Taf.State.stopped)
    }

}
