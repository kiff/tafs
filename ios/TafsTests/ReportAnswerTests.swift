import XCTest
@testable import Tafs

/// Report this answer (kiff/tafs#421): an answer of Coach's or a taf's goes to tafs cloud by
/// its id, with its chat's session, and the app says how it went.
@MainActor
final class ReportAnswerTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    /// A model signed in with KIFF whose one taf is Rae, run by tafs cloud as depl_1.
    private func kiffModel() async -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(), kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x")
        XCTAssertEqual(model.mode, .kiff)
        return (model, try! XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id))
    }

    private let answer = TafsCloud.ChatMessage(at: Date(), from: .taf, text: "Refunded order 4410.", session: "sesn_9", serverID: "run_1.4")
    private let route = "POST /v1/tafs/depl_1/messages/run_1.4/report"

    private func body() throws -> [String: String] {
        let post = try XCTUnwrap(StubProtocol.sent.first { $0.httpMethod == "POST" && $0.url?.path == "/v1/tafs/depl_1/messages/run_1.4/report" })
        return try XCTUnwrap(JSONSerialization.jsonObject(with: post.httpBody ?? Data()) as? [String: String])
    }

    func testAnAnswerIsReportedByItsIdWithItsSessionAndNote() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses[route] = (200, #"{"report":{"id":"r1","at":"2026-11-01T15:00:00Z","new":true}}"#)
        let outcome = await model.reportAnswer(answer, in: id, reason: .wrong, note: "  It refunded the wrong order. ")
        XCTAssertEqual(outcome, .sent)
        XCTAssertEqual(try body(), ["reason": "wrong", "note": "It refunded the wrong order.", "session": "sesn_9"])
    }

    func testABlankNoteIsLeftOut() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses[route] = (200, #"{"report":{"id":"r1","at":"2026-11-01T15:00:00Z","new":true}}"#)
        _ = await model.reportAnswer(answer, in: id, reason: .offensive, note: "   ")
        XCTAssertEqual(try body(), ["reason": "offensive", "session": "sesn_9"])
    }

    func testReportedAgainSaysSo() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses[route] = (200, #"{"report":{"id":"r1","at":"2026-11-01T15:00:00Z","new":false}}"#)
        let outcome = await model.reportAnswer(answer, in: id, reason: .harmful, note: "")
        XCTAssertEqual(outcome, .already)
    }

    func testNotSentSaysWhy() async throws {
        let (model, id) = await kiffModel()
        StubProtocol.responses[route] = (404, #"{"error":"no answer of the taf's by that id"}"#)
        let outcome = await model.reportAnswer(answer, in: id, reason: .other, note: "")
        guard case .failed(let why) = outcome else { return XCTFail("not sent: \(outcome)") }
        XCTAssertEqual(why, "This answer isn't on tafs cloud any more.", "in the app's words, not tafs cloud's")
    }

    /// Only an answer tafs cloud has an id for: never the person's own words, a run's kickoff,
    /// or an answer still streaming.
    func testOnlyAnAnswerWithAnIdIsReportable() async throws {
        let (model, _) = await kiffModel()
        XCTAssertTrue(model.canReport(answer))
        XCTAssertFalse(model.canReport(TafsCloud.ChatMessage(at: Date(), from: .owner, text: "Skip 4410", session: "sesn_9", serverID: "run_1.in")))
        XCTAssertFalse(model.canReport(TafsCloud.ChatMessage(at: Date(), from: .taf, kind: .kickoff, text: "Work the queue", session: "sesn_9", serverID: "run_1.0")))
        XCTAssertFalse(model.canReport(TafsCloud.ChatMessage(at: Date(), from: .taf, text: "Refunding…", session: "sesn_9")))
    }
}
