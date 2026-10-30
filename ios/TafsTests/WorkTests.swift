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
