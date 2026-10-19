import XCTest
@testable import Tafs

/// Pausing a real taf: tafs cloud stops starting its runs, and the app shows it paused.
/// Resuming puts it back on its schedule. Its Cards aren't touched.
@MainActor
final class PauseTafTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private func signedIn() async throws -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x")
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id)
        return (model, rae)
    }

    func testPausesAndResumesOnTafsCloud() async throws {
        let (model, rae) = try await signedIn()
        StubProtocol.responses["POST /v1/tafs/depl_1/pause"] = (200, #"{"taf":{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"paused"}}"#)
        await model.setPaused(rae, true)
        XCTAssertEqual(model.taf(rae)?.state, .paused)
        XCTAssertEqual(model.cloudTaf(for: rae)?.paused, true)

        StubProtocol.responses["POST /v1/tafs/depl_1/resume"] = (200, #"{"taf":{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}}"#)
        await model.setPaused(rae, false)
        XCTAssertEqual(model.taf(rae)?.state, .working)
        XCTAssertEqual(model.cloudTaf(for: rae)?.paused, false)

        let calls = StubProtocol.sent.filter { $0.httpMethod == "POST" }.compactMap { $0.url?.path }
        XCTAssertEqual(calls, ["/v1/tafs/depl_1/pause", "/v1/tafs/depl_1/resume"], "only tafs cloud: no Card is revoked")
    }

    func testSaysSoWhenTafsCloudRefuses() async throws {
        let (model, rae) = try await signedIn()
        StubProtocol.responses["POST /v1/tafs/depl_1/pause"] = (403, #"{"error":"needs cards.manage"}"#)
        await model.setPaused(rae, true)
        XCTAssertNotEqual(model.taf(rae)?.state, .paused)
        XCTAssertTrue(model.lastError?.hasPrefix("tafs cloud didn't pause it") == true, model.lastError ?? "")
    }
}
