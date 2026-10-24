import XCTest
@testable import Tafs

/// "Halt all tafs" from Siri and Shortcuts (kiff/tafs#210): the request Halt all sends in the
/// app, and an answer that says what happened. Halted only once KIFF confirms; never silent.
@MainActor
final class HaltAllIntentTests: XCTestCase {
    private static let halting = #"{"halt":{"state":"halting","halted_at":"2026-10-24T12:00:00Z","runtime":true,"kiff":true,"planque":false,"account":false,"stopped":2}}"#
    private static let halted = #"{"halt":{"state":"halted","halted_at":"2026-10-24T12:00:00Z","runtime":true,"kiff":true,"planque":true,"account":false,"stopped":2}}"#
    private static let threeTafs = #"{"tafs":[{"id":"t1","name":"Rae","job":"refunds","kiff_agent":"a1","status":"active"},{"id":"t2","name":"Bo","job":"refunds","kiff_agent":"a2","status":"active"},{"id":"t3","name":"Ana","job":"refunds","kiff_agent":"a3","status":"active"}]}"#

    private func cloud() -> TafsCloud {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    func testSignedOutSaysSoAndOpensTafs() async {
        let outcome = await HaltAll.run(cloud: nil, wait: {})
        XCTAssertEqual(outcome, .needsApp("You're signed out of tafs. Sign in to halt your tafs."))
    }

    func testSaysHaltedOnceKIFFConfirms() async {
        let cloud = cloud()
        StubProtocol.responses["GET /v1/tafs"] = (200, Self.threeTafs)
        StubProtocol.responses["POST /v1/halt"] = (200, Self.halting)
        StubProtocol.responses["GET /v1/halt"] = (200, Self.halted)
        let outcome = await HaltAll.run(cloud: cloud, wait: {})
        XCTAssertEqual(outcome, .done("Halted 3 tafs. Nothing new is sent until you resume."))
        XCTAssertEqual(sent("POST", "/v1/halt").count, 1, "the app's own Halt all request, once")
        XCTAssertNil(sent("POST", "/v1/halt").first?.value(forHTTPHeaderField: "KIFF-Device-Signature"), "no signature: halting only takes authority away")
        XCTAssertEqual(sent("GET", "/v1/halt").count, 1)
    }

    func testStillHaltingSaysWhatIsLeftAfterAFewTries() async {
        let cloud = cloud()
        StubProtocol.responses["GET /v1/tafs"] = (200, Self.threeTafs)
        StubProtocol.responses["POST /v1/halt"] = (200, Self.halting)
        StubProtocol.responses["GET /v1/halt"] = (200, Self.halting)
        var waits = 0
        let outcome = await HaltAll.run(cloud: cloud, wait: { waits += 1 })
        XCTAssertEqual(outcome, .done("Halting 3 tafs: no run starts. KIFF hasn't confirmed every part yet; tafs shows when it has."))
        XCTAssertEqual(waits, HaltAll.confirmTries)
    }

    func testAFailedHaltIsNeverSilent() async {
        let cloud = cloud()
        StubProtocol.responses["POST /v1/halt"] = (502, #"{"error":"kiff/agents didn't answer"}"#)
        guard case .needsApp(let said) = await HaltAll.run(cloud: cloud, wait: {}) else { return XCTFail("a failed halt opens tafs") }
        XCTAssertTrue(said.hasPrefix("tafs couldn't halt your tafs:"), said)
        XCTAssertTrue(said.hasSuffix("Open tafs to halt them."), said)
    }

    func testOneTafInTheSingular() {
        let h = TafsCloud.Halt(state: .halted, haltedAt: nil, runtime: true, kiff: true, planque: true, account: true, stopped: 0)
        XCTAssertEqual(HaltAll.said(h, count: 1), .done("Halted 1 taf. Nothing new is sent until you resume."))
        XCTAssertEqual(HaltAll.said(h, count: nil), .done("Halted your tafs. Nothing new is sent until you resume."))
    }

    func testAHaltedAlertOrSiriOpensHaltAll() {
        let model = AppModel(signer: StubSigner())
        model.open("halt")
        XCTAssertTrue(model.haltRequested)
    }
}
