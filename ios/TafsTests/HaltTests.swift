import XCTest
@testable import Tafs

/// A halt: one tap and no Face ID halts a taf, or all of them, through
/// tafs cloud; it reads Halted only once kiff/agents, KIFF and planque all hold it. Resuming
/// asks for Face ID, is signed to KIFF first, and lets tafs cloud run it again only after.
@MainActor
final class HaltTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private static let halting = #"{"halt":{"state":"halting","halted_at":"2026-10-21T23:00:00Z","runtime":true,"kiff":true,"planque":false,"account":false,"stopped":1}}"#
    private static let halted = #"{"halt":{"state":"halted","halted_at":"2026-10-21T23:00:00Z","runtime":true,"kiff":true,"planque":true,"account":false,"stopped":0}}"#
    private static let running = #"{"halt":{"state":"running","halted_at":"2026-10-21T23:00:00Z","runtime":false,"kiff":false,"planque":false,"account":false,"stopped":0}}"#

    private func signedIn(signer: FaceIDSigner) async throws -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id)
        return (model, rae)
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    func testHaltsInOneTapAndReadsHaltedOnlyOnceAllHoldIt() async throws {
        let signer = FaceIDSigner()
        let (model, rae) = try await signedIn(signer: signer)
        XCTAssertEqual(model.haltState(rae).state, .running)

        StubProtocol.responses["POST /v1/tafs/depl_1/halt"] = (200, Self.halting)
        await model.halt(rae)
        XCTAssertEqual(model.haltState(rae).state, .halting, "planque hasn't confirmed: not halted yet")
        XCTAssertTrue(signer.prompts.isEmpty, "halting asks for no Face ID")
        XCTAssertEqual(sent("POST", "/v1/tafs/depl_1/halt").count, 1)
        XCTAssertNil(sent("POST", "/v1/tafs/depl_1/halt").first?.value(forHTTPHeaderField: "KIFF-Device-Signature"))

        StubProtocol.responses["GET /v1/tafs/depl_1/halt"] = (200, Self.halted)
        await model.refreshHalt(rae)
        let h = model.haltState(rae)
        XCTAssertEqual(h.state, .halted)
        XCTAssertTrue(h.runtime && h.kiff && h.planque)
    }

    func testResumesInKIFFSignedThenInTafsCloud() async throws {
        let signer = FaceIDSigner()
        let (model, rae) = try await signedIn(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/halt"] = (200, Self.halted)
        await model.halt(rae)
        StubProtocol.responses["POST /v1/agents/rae-k3f9/resume"] = (200, #"{"state":"running"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/halt"] = (200, Self.running)

        let resumed = await model.resumeHalt(rae)
        XCTAssertTrue(resumed, model.lastError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.resume.v1|t_01|rae-k3f9"], "one Face ID")
        let kiff = try XCTUnwrap(sent("POST", "/v1/agents/rae-k3f9/resume").first)
        XCTAssertNotNil(kiff.value(forHTTPHeaderField: "KIFF-Device-Signature"), "KIFF takes a resume only signed by the phone")
        let order = StubProtocol.sent.compactMap { r -> String? in
            guard let m = r.httpMethod, m != "GET" else { return nil }
            return "\(m) \(r.url?.path ?? "")"
        }
        XCTAssertEqual(order, ["POST /v1/tafs/depl_1/halt", "POST /v1/agents/rae-k3f9/resume", "DELETE /v1/tafs/depl_1/halt"])
        XCTAssertEqual(model.haltState(rae).state, .running)
        XCTAssertNil(model.kiff.setupSigner)
    }

    func testAResumeKIFFRefusesNeverReachesTafsCloud() async throws {
        let signer = FaceIDSigner()
        let (model, rae) = try await signedIn(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/halt"] = (200, Self.halted)
        await model.halt(rae)
        StubProtocol.responses["POST /v1/agents/rae-k3f9/resume"] = (403, #"{"error":"the device signature doesn't check"}"#)

        let resumed = await model.resumeHalt(rae)
        XCTAssertFalse(resumed)
        XCTAssertTrue(sent("DELETE", "/v1/tafs/depl_1/halt").isEmpty)
        XCTAssertEqual(model.haltState(rae).state, .halted)
        XCTAssertTrue(model.lastError?.hasPrefix("KIFF didn't resume Rae") == true, model.lastError ?? "")
    }

    func testNoFaceIDNoResume() async throws {
        let signer = FaceIDSigner()
        signer.refuse = true
        let (model, rae) = try await signedIn(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/halt"] = (200, Self.halted)
        await model.halt(rae)
        let resumed = await model.resumeHalt(rae)
        XCTAssertFalse(resumed)
        XCTAssertTrue(sent("POST", "/v1/agents/rae-k3f9/resume").isEmpty)
    }

    func testHaltsAndResumesEveryTaf() async throws {
        let signer = FaceIDSigner()
        let (model, _) = try await signedIn(signer: signer)
        StubProtocol.responses["POST /v1/halt"] = (200, Self.halted)
        await model.halt(nil)
        XCTAssertEqual(model.haltState(nil).state, .halted)
        StubProtocol.responses["POST /v1/resume"] = (200, #"{"state":"running"}"#)
        StubProtocol.responses["DELETE /v1/halt"] = (200, Self.running)
        let resumed = await model.resumeHalt(nil)
        XCTAssertTrue(resumed, model.lastError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.resume.v1|t_01|*"])
        XCTAssertNotNil(sent("POST", "/v1/resume").first?.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(model.haltState(nil).state, .running)
    }

    func testKIFFTakesAResumeOnlySigned() {
        XCTAssertTrue(KIFFClient.signsSetup("/v1/agents/rae-k3f9/resume"))
        XCTAssertTrue(KIFFClient.signsSetup("/v1/resume"))
        XCTAssertFalse(KIFFClient.signsSetup("/v1/agents/rae-k3f9/halt"), "a halt only takes authority away: no signature")
        XCTAssertFalse(KIFFClient.signsSetup("/v1/agents//resume"))
    }
}
