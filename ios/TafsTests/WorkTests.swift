import XCTest
@testable import Tafs

/// A taf's work as tafs cloud says it (docs/reliability.md), and the home list with a taf
/// whose connection its vendor signed out.
@MainActor
final class WorkTests: XCTestCase {
    private static let workJSON = #"""
    {"summary":{"status":"needs_you","line":"1 request needs you","needs_you":1},"next_run":null,"counts":{},
     "requests":[{"id":"run_2","at":"2026-10-30T10:00:00.000Z","state":"needs_you","line":"Refund $300 waits for you",
       "deliveries":[{"place":"door_reply","state":"not_sent","line":"The reply wasn't sent","send_again":true}]},
      {"id":"run_1","at":"2026-10-30T08:00:00.000Z","state":"done","line":"Read 3 charges","deliveries":[]}],
     "skipped":[{"due":"2026-10-30T09:00:00.000Z","why":"busy","words":"Skipped the 10:00 run: the last one was still working"}]}
    """#

    func testTheWorkReadsAsTafsCloudSendsIt() throws {
        let work = try KIFFClient.decoder.decode(TafsCloud.TafWork.self, from: Data(Self.workJSON.utf8))
        XCTAssertEqual(work.summary?.line, "1 request needs you")
        XCTAssertEqual(work.summary?.needsYou, 1)
        XCTAssertEqual(work.requests.first?.deliveries.first?.sendAgain, true)
        // Requests and skipped firings, each at its own time, newest first.
        XCTAssertEqual(work.rows.map(\.id).map { $0.prefix(2) }, ["r|", "s|", "r|"])
        let older = try KIFFClient.decoder.decode(TafsCloud.TafWork.self, from: Data(#"{"summary":{"status":"idle","line":""},"requests":[]}"#.utf8))
        XCTAssertTrue(older.skipped.isEmpty, "an older tafs cloud sends no skipped firings")
    }

    func testSendAgainPostsToTheDelivery() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        StubProtocol.responses["POST /v1/tafs/taf-1/requests/run_2/deliveries/door_reply/send"] = (200, #"{"state":"sent"}"#)
        let state = try await cloud.sendAgain("taf-1", run: "run_2")
        XCTAssertEqual(state, "sent")
    }

    /// A place of the taf's own (an action at a connected app, or another taf) is sent again by its own key,
    /// never as the door's reply (docs/api.md).
    func testAPlaceOfTheTafsOwnIsSentAgainByItsKey() async throws {
        let json = #"""
        {"summary":{"status":"needs_you","line":"","needs_you":1},"requests":[{"id":"run_2","at":"2026-10-30T10:00:00.000Z","state":"needs_you","line":"",
          "deliveries":[{"place":"door_reply","state":"not_sent","line":"The reply wasn't sent","send_again":true},
                        {"place":"taf","key":"taf:taf-2","name":"Invoices","state":"not_sent","line":"Invoices didn't finish what Rae handed it","send_again":true},
                        {"place":"action","key":"grt_g:sheets_append","name":"Sheets","state":"not_sent","line":"Not delivered to Sheets","send_again":true},
                        {"place":"taf","name":"Old","state":"handed","line":"Handed to Old"}]}]}
        """#
        let deliveries = try XCTUnwrap(KIFFClient.decoder.decode(TafsCloud.TafWork.self, from: Data(json.utf8)).requests.first?.deliveries)
        XCTAssertEqual(deliveries.map(\.sendPlace), ["door_reply", "taf:taf-2", "grt_g:sheets_append", "door_reply"])

        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        StubProtocol.responses["POST /v1/tafs/taf-1/requests/run_2/deliveries/taf:taf-2/send"] = (200, #"{"state":"handed"}"#)
        let state = try await cloud.sendAgain("taf-1", run: "run_2", place: deliveries[1].sendPlace)
        XCTAssertEqual(state, "handed")
        let sent = try XCTUnwrap(StubProtocol.sent.last?.url)
        XCTAssertEqual(sent.path, "/v1/tafs/taf-1/requests/run_2/deliveries/taf:taf-2/send")
    }

    func testRequestDetailKeepsTheServicesStepOrderAndReadsOnly() async throws {
        StubProtocol.responses = [:]; StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        StubProtocol.responses["GET /v1/tafs/taf-1/requests/run_2"] = (200, #"{"id":"run_2","from":"Its schedule","line":"Reply needs you","steps":[{"label":"Received","state":"done"},{"label":"Refund","line":"Not made","state":"not_made"},{"label":"Needs you","state":"waiting"}],"deliveries":[]}"#)
        let detail = try await cloud.request("taf-1", run: "run_2")
        XCTAssertEqual(detail.steps.map(\.label), ["Received", "Refund", "Needs you"])
        XCTAssertEqual(detail.steps[1].state, "not_made")
        XCTAssertEqual(detail.from, "Its schedule")
        XCTAssertTrue(StubProtocol.sent.allSatisfy { $0.httpMethod == "GET" })
    }

    /// A run's credits are tafs cloud's words from KIFF's usage record, drawn as sent: a counted
    /// run's figure, why a run used none, and no line for a run not counted yet. The phone counts nothing.
    func testARunsCreditsAreTafsCloudsLineAndNoneIsNone() throws {
        let json = #"""
        {"summary":{"status":"idle","line":"Idle"},"requests":[
          {"id":"r1","at":"2026-11-12T19:43:00Z","state":"done","line":"Done at 20:43","credits":{"credits":34,"counted":true,"words":"34 credits"}},
          {"id":"r2","at":"2026-11-12T18:48:00Z","state":"failed","line":"It couldn't start","credits":{"credits":0,"counted":false,"words":"No credits: it failed"}},
          {"id":"r3","at":"2026-11-12T18:00:00Z","state":"done","line":"Done at 19:00"},
          {"id":"r4","at":"2026-11-12T17:00:00Z","state":"done","line":"Done at 18:00","credits":{"credits":12,"counted":true,"words":"  "}},
          {"id":"r5","at":"2026-11-12T16:00:00Z","state":"done","line":"Done at 17:00","credits":"soon"}],"skipped":[]}
        """#
        let work = try KIFFClient.decoder.decode(TafsCloud.TafWork.self, from: Data(json.utf8))
        XCTAssertEqual(work.requests.map(\.creditsLine), ["34 credits", "No credits: it failed", nil, nil, nil])
        XCTAssertEqual(work.requests[0].credits?.credits, 34)
        XCTAssertNil(work.requests[2].credits, "not counted yet: no line")
        XCTAssertNil(work.requests[3].creditsLine, "blank words: no line, and no figure made up from the number")
        XCTAssertEqual(work.requests[4].line, "Done at 18:00".replacingOccurrences(of: "18", with: "17"), "credits the phone can't read leave the run as it is")
        // Both places a run's line is drawn draw this one under it.
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        for file in ["TafDetailView.swift", "TafPresentationViews.swift"] {
            XCTAssertTrue(try String(contentsOf: views.appendingPathComponent(file), encoding: .utf8).contains("creditsLine"), file)
        }
    }

    func testTheRimOpensUnfinishedWorkThenTheNewest() {
        let done = TafsCloud.TafWork.Request(id: "done", at: Date(), state: "done")
        let waiting = TafsCloud.TafWork.Request(id: "waiting", at: Date(), state: "waiting")
        XCTAssertEqual(TafsCloud.TafWork(requests: [done, waiting]).focusedRequest?.id, "waiting")
        XCTAssertEqual(TafsCloud.TafWork(requests: [done]).focusedRequest?.id, "done")
        XCTAssertNil(TafsCloud.TafWork().focusedRequest)
    }

    func testTheFaceRemovesTheServersFollowUpByItsOwnID() async throws {
        StubProtocol.responses = [:]; StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        StubProtocol.responses["DELETE /v1/tafs/taf-1/follow-ups/fu-2"] = (200, #"{"removed":"fu-2"}"#)
        try await cloud.removeFollowUp("taf-1", followUp: "fu-2")
        XCTAssertEqual(StubProtocol.sent.last?.httpMethod, "DELETE")
        XCTAssertEqual(StubProtocol.sent.last?.url?.path, "/v1/tafs/taf-1/follow-ups/fu-2")
    }

    private func taf(_ name: String) -> Taf {
        Taf(name: name, job: "Refunds", tools: [], colorIndex: 0, card: Card(rules: [], issuedBy: "You"))
    }

    /// A taf whose connection its vendor signed out waits on the owner: at the top, with the
    /// held calls, each part keeping the order it had.
    func testASignedOutTafWaitsOnTheOwner() {
        let a = taf("Refunds"), b = taf("Chargebacks"), c = taf("Payouts")
        let now = Date()
        let record = [RecordEntry(tafID: a.id, kind: .done, title: "Refund", detail: "", at: now.addingTimeInterval(-60))]
        XCTAssertEqual(AppModel.byRecent([a, b, c], record: record, held: [], chats: [:], signedOut: [c.id]).map(\.name),
                       ["Payouts", "Refunds", "Chargebacks"])
        XCTAssertEqual(AppModel.waitingFirst([a, b, c], waiting: [c.id, b.id]).map(\.name), ["Chargebacks", "Payouts", "Refunds"],
                       "newly waiting goes to the top at once")
        XCTAssertEqual(AppModel.waitingFirst([a, b, c], waiting: []).map(\.name), ["Refunds", "Chargebacks", "Payouts"])
    }
}
