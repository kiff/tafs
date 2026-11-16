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
    }

    private func send(_ request: WatchRequest, reading: Reading) async -> WatchReply {
        await WatchRelay.answer(WatchMessage.encode(request), now: now, reading: reading)
    }

    /// Bytes as a watch, or anything else, could send them.
    private func raw(_ json: String, reading: Reading) async -> WatchReply {
        await WatchRelay.answer(Data(json.utf8), now: now, reading: reading)
    }

    private var made: String { "\(now.timeIntervalSinceReferenceDate)" }

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
        XCTAssertEqual(reading.reads, 0)
    }

    func testAnUnknownKindOrMalformedBytesAreRefused() async {
        let reading = Reading(at: now)
        for bytes in [#"{"kind":"pay","made":\#(made)}"#, #"{"kind":"needs_you"}"#, #"{"made":\#(made)}"#, "not json", "", "[]",
                      #"{"kind":"needs_you","made":"yesterday"}"#] {
            let reply = await raw(bytes, reading: reading)
            XCTAssertEqual(reply, WatchReply(.refused), bytes)
        }
        XCTAssertEqual(reading.reads, 0)
    }

    func testARequestMissingWhatItNamesIsRefused() async {
        let reading = Reading(at: now)
        for request in [WatchRequest(.refuse, made: now), WatchRequest(.refuse, made: now, hold: "  "),
                        WatchRequest(.halt, made: now), WatchRequest(.coach, made: now, text: "")] {
            let reply = await send(request, reading: reading)
            XCTAssertEqual(reply, WatchReply(.refused), "\(request.kind)")
        }
        XCTAssertEqual(reading.reads, 0)
    }

    func testOnlyALiveRequestIsAnswered() async {
        let reading = Reading(at: now)
        let late = await send(WatchRequest(.haltAll, made: now.addingTimeInterval(-31)), reading: reading)
        XCTAssertEqual(late, WatchReply(.stale), "a Halt 31 s old is dropped")
        let lateRead = await send(WatchRequest(.needsYou, made: now.addingTimeInterval(-31)), reading: reading)
        XCTAssertEqual(lateRead, WatchReply(.stale))
        let ahead = await send(WatchRequest(.needsYou, made: now.addingTimeInterval(31)), reading: reading)
        XCTAssertEqual(ahead, WatchReply(.stale), "nor one from ahead of the phone's clock")
        XCTAssertEqual(reading.reads, 0)
        let justInTime = await send(WatchRequest(.needsYou, made: now.addingTimeInterval(-29)), reading: reading)
        XCTAssertEqual(justInTime.outcome, .ok)
    }

    func testSignedOutAnswersNothingOfTheAccount() async {
        let reading = Reading(at: now)
        reading.signedIn = false
        let reply = await send(WatchRequest(.needsYou, made: now), reading: reading)
        XCTAssertEqual(reply, WatchReply(.signedOut))
        XCTAssertNil(reply.needsYou)
        XCTAssertEqual(reading.reads, 0)
    }

    func testAReadThatFailsSaysSoInThePhonesWords() async {
        let reading = Reading(at: now)
        reading.fails = true
        let reply = await send(WatchRequest(.needsYou, made: now), reading: reading)
        XCTAssertEqual(reply, WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff))
    }

    func testRefuseHaltAndCoachAreNotAnsweredFromTheWatchYet() async {
        let reading = Reading(at: now)
        for request in [WatchRequest(.refuse, made: now, hold: "ce_1"), WatchRequest(.halt, made: now, taf: "depl_1"),
                        WatchRequest(.haltAll, made: now), WatchRequest(.tafs, made: now),
                        WatchRequest(.coach, made: now, text: "Pause Rae")] {
            let reply = await send(request, reading: reading)
            XCTAssertEqual(reply, WatchReply(.notYet), "\(request.kind)")
        }
        XCTAssertEqual(reading.reads, 0)
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

    func testTheReadsUseTheSessionTheAppKeeps() {
        XCTAssertEqual(HoldNotification.sessionAccount, AppModel.tokenAccount)
    }

    func testEachHeldCallInThePhonesWordsTheSoonestFirst() async throws {
        let (kiff, cloud) = clients()
        let page = try await KeptWatchReading(kiff: kiff, cloud: cloud).needsYou(now: now)
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
        XCTAssertFalse(KeptWatchReading(kiff: KIFFClient(), cloud: cloud).signedIn)
    }
}
