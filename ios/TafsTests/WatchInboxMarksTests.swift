import XCTest
@testable import Tafs

/// Row 21: the watch Inbox's lines read and dismissed, kept in step across the person's watches by tafs cloud
/// (`/v1/watch/inbox`, docs/api.md), by id alone. The phone reads and sends them for the watch, and nothing else.
@MainActor
final class WatchInboxMarksTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-11-24T00:30:00Z")!

    /// A reading that answers the marks as told and keeps what it was asked to mark.
    private final class Reading: WatchReading {
        var signedIn = true
        var marks = WatchInboxMarks(read: ["ce_1"], dismissed: ["ce_2"])
        var marked: [WatchInboxMarks] = []
        func needsYou(now: Date) async throws -> WatchNeedsYou { WatchNeedsYou(count: 0, calls: [], at: now) }
        func tafs() async throws -> [WatchTaf] { [] }
        func refuse(hold: String, now: Date) async -> NotificationRefuse.Outcome { .gone }
        func halt(taf: String?) async -> HaltAll.Outcome { .needsApp("") }
        func coach(text: String, messageID: UUID) async -> WatchReply { WatchReply(.notYet) }
        func tafPage(_ id: String) async throws -> WatchTafPage? { nil }
        func calendar(day: String?) async throws -> WatchCalendarDay { WatchCalendarDay(day: "", words: "", events: [], halted: false) }
        func account() async throws -> WatchAccount { WatchAccount(email: nil, account: "", plan: nil) }
        func inboxMarks() async -> WatchReply { WatchReply(.ok, marks: marks) }
        func inboxMark(_ marks: WatchInboxMarks) async -> WatchReply {
            marked.append(marks)
            return WatchReply(.ok, marks: self.marks)
        }
    }

    private func send(_ request: WatchRequest, reading: WatchReading) async -> WatchReply {
        await WatchRelay.answer(WatchMessage.encode(request), now: now, reading: reading)
    }

    func testTheMarksAreReadForTheWatch() async {
        let reading = Reading()
        let reply = await send(WatchRequest(.inboxMarks, made: now), reading: reading)
        XCTAssertEqual(reply, WatchReply(.ok, marks: WatchInboxMarks(read: ["ce_1"], dismissed: ["ce_2"])))
        XCTAssertTrue(WatchRequest.Kind.inboxMarks.isRead)
        XCTAssertFalse(WatchRequest.Kind.inboxMark.isRead, "a mark is a write: never sent twice by the route")
    }

    /// A mark names 1 to 100 ids tafs cloud takes; anything else is refused before tafs cloud is asked.
    func testAMarkIsSentOnlyWithIdsTafsCloudTakes() async {
        let reading = Reading()
        let ok = await send(WatchRequest(.inboxMark, made: now, marks: WatchInboxMarks(read: ["ce_3"], dismissed: ["6f1c2a4e-0b8d"])),
                            reading: reading)
        XCTAssertEqual(ok.outcome, .ok)
        XCTAssertEqual(reading.marked, [WatchInboxMarks(read: ["ce_3"], dismissed: ["6f1c2a4e-0b8d"])])
        for bad in [nil, WatchInboxMarks(), WatchInboxMarks(read: ["has space"]), WatchInboxMarks(dismissed: [String(repeating: "a", count: 101)]),
                    WatchInboxMarks(read: (0...100).map { "h_\($0)" })] {
            let refused = await send(WatchRequest(.inboxMark, made: now, marks: bad), reading: reading)
            XCTAssertEqual(refused, WatchReply(.refused), "\(String(describing: bad))")
        }
        XCTAssertEqual(reading.marked.count, 1)
    }

    /// Signed out, nothing of the account is read or marked.
    func testSignedOutMarksNothing() async {
        let reading = Reading()
        reading.signedIn = false
        let read = await send(WatchRequest(.inboxMarks, made: now), reading: reading)
        let mark = await send(WatchRequest(.inboxMark, made: now, marks: WatchInboxMarks(read: ["ce_3"])), reading: reading)
        XCTAssertEqual(read, WatchReply(.signedOut))
        XCTAssertEqual(mark, WatchReply(.signedOut))
        XCTAssertTrue(reading.marked.isEmpty)
    }

    /// A phone reader from before the marks: the watch keeps its own.
    func testAnOlderReaderSaysNotYet() async {
        struct Older: WatchReading {
            var signedIn: Bool { true }
            func needsYou(now: Date) async throws -> WatchNeedsYou { WatchNeedsYou(count: 0, calls: [], at: now) }
            func tafs() async throws -> [WatchTaf] { [] }
            func refuse(hold: String, now: Date) async -> NotificationRefuse.Outcome { .gone }
            func halt(taf: String?) async -> HaltAll.Outcome { .needsApp("") }
            func coach(text: String, messageID: UUID) async -> WatchReply { WatchReply(.notYet) }
            func tafPage(_ id: String) async throws -> WatchTafPage? { nil }
            func calendar(day: String?) async throws -> WatchCalendarDay { WatchCalendarDay(day: "", words: "", events: [], halted: false) }
            func account() async throws -> WatchAccount { WatchAccount(email: nil, account: "", plan: nil) }
        }
        let reply = await send(WatchRequest(.inboxMarks, made: now), reading: Older())
        XCTAssertEqual(reply, WatchReply(.notYet))
    }

    /// View on phone: an older reader opens nothing and says so; a request naming no taf opens Coach's chat.
    func testViewOnPhoneOpensCoachOrTheTaf() async {
        struct Opener: WatchReading {
            var signedIn: Bool { true }
            func needsYou(now: Date) async throws -> WatchNeedsYou { WatchNeedsYou(count: 0, calls: [], at: now) }
            func tafs() async throws -> [WatchTaf] { [] }
            func refuse(hold: String, now: Date) async -> NotificationRefuse.Outcome { .gone }
            func halt(taf: String?) async -> HaltAll.Outcome { .needsApp("") }
            func coach(text: String, messageID: UUID) async -> WatchReply { WatchReply(.notYet) }
            func tafPage(_ id: String) async throws -> WatchTafPage? { nil }
            func calendar(day: String?) async throws -> WatchCalendarDay { WatchCalendarDay(day: "", words: "", events: [], halted: false) }
            func account() async throws -> WatchAccount { WatchAccount(email: nil, account: "", plan: nil) }
            func openOnPhone(taf: String?) async -> WatchReply { WatchReply(.ok, line: WatchOpen.destination(taf: taf)) }
        }
        let coach = await send(WatchRequest(.openOnPhone, made: now), reading: Opener())
        let taf = await send(WatchRequest(.openOnPhone, made: now, taf: "taf-1"), reading: Opener())
        XCTAssertEqual(coach, WatchReply(.ok, line: "coach"))
        XCTAssertEqual(taf, WatchReply(.ok, line: "taf:taf-1"))
        let blank = await send(WatchRequest(.openOnPhone, made: now, taf: "  "), reading: Opener())
        XCTAssertEqual(blank, WatchReply(.refused))
        struct Older: WatchReading {
            var signedIn: Bool { true }
            func needsYou(now: Date) async throws -> WatchNeedsYou { WatchNeedsYou(count: 0, calls: [], at: now) }
            func tafs() async throws -> [WatchTaf] { [] }
            func refuse(hold: String, now: Date) async -> NotificationRefuse.Outcome { .gone }
            func halt(taf: String?) async -> HaltAll.Outcome { .needsApp("") }
            func coach(text: String, messageID: UUID) async -> WatchReply { WatchReply(.notYet) }
            func tafPage(_ id: String) async throws -> WatchTafPage? { nil }
            func calendar(day: String?) async throws -> WatchCalendarDay { WatchCalendarDay(day: "", words: "", events: [], halted: false) }
            func account() async throws -> WatchAccount { WatchAccount(email: nil, account: "", plan: nil) }
        }
        let older = await send(WatchRequest(.openOnPhone, made: now), reading: Older())
        XCTAssertEqual(older, WatchReply(.notYet))
    }

    /// The notification View on phone posts with tafs not in front: "From your watch", whose tap opens the chat.
    func testViewOnPhonesNotificationOpensTheChat() {
        let coach = WatchOpen.content("coach")
        XCTAssertEqual(coach.body, Words.Coach.Chats.fromYourWatch)
        XCTAssertEqual(coach.userInfo["open"] as? String, "coach")
        XCTAssertEqual(WatchOpen.content("taf:taf-1").userInfo["open"] as? String, "taf:taf-1")
        XCTAssertEqual(WatchOpen.destination(taf: ""), "coach")
    }

    /// tafs cloud's answer as the API gives it: ids it can't use are left out, each once, and a dismissed id is never read.
    func testTheMarksAreTafsCloudsJSON() {
        let json = #"{"read":["ce_1","ce_1","wa_9",7,"bad id","ce_2"],"dismissed":["ce_2","6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90"]}"#
        XCTAssertEqual(TafsCloud.watchInboxMarks(decoding: Data(json.utf8)),
                       WatchInboxMarks(read: ["ce_1", "wa_9"], dismissed: ["ce_2", "6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90"]))
        XCTAssertEqual(TafsCloud.watchInboxMarks(decoding: Data("{}".utf8)), WatchInboxMarks())
        XCTAssertEqual(TafsCloud.watchInboxMarks(decoding: Data("not json".utf8)), WatchInboxMarks())
    }
}
