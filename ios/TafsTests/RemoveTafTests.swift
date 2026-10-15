import XCTest
@testable import Tafs

/// Removing a taf: KIFF revokes its Cards first, then tafs cloud stops it and deletes its
/// memory, its key's vault and its name.
@MainActor
final class RemoveTafTests: XCTestCase {
    private static let raeCard = #"{"id":"rae-k3f9-stripe_refund","subject":"rae-k3f9","grants":[],"aggregates":[],"active":true}"#

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private func signedIn(signer: ApprovalSigning = StubSigner()) async throws -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        let list = #"{"mandates":["# + Self.raeCard + "]}"
        for path in ["GET /v1/me/mandates", "GET /v1/me/mandates?include=use"] { StubProtocol.responses[path] = (200, list) }
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x")
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id)
        return (model, rae)
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    func testRevokesTheCardsThenRemovesItOnTafsCloud() async throws {
        let (model, rae) = try await signedIn()
        StubProtocol.responses["POST /v1/me/mandates/rae-k3f9-stripe_refund/revoke"] = (200, "{}")
        StubProtocol.responses["DELETE /v1/tafs/depl_1"] = (200, #"{"done":true,"removed":["schedule","memory","key","name"],"failed":[]}"#)

        let removed = await model.removeTaf(rae)
        let message = try XCTUnwrap(removed)
        XCTAssertTrue(message.hasPrefix("Its Cards are revoked in KIFF."), message)
        XCTAssertTrue(message.contains("the name Rae is free"), message)
        let order = StubProtocol.sent.compactMap { r -> String? in
            guard let p = r.url?.path, p.hasSuffix("/revoke") || p == "/v1/tafs/depl_1" else { return nil }
            return "\(r.httpMethod ?? "") \(p)"
        }
        XCTAssertEqual(order, ["POST /v1/me/mandates/rae-k3f9-stripe_refund/revoke", "DELETE /v1/tafs/depl_1"], "authority goes first")
        XCTAssertEqual(model.taf(rae)?.state, .revoked)
        XCTAssertNil(model.cloudTaf(for: rae))
    }

    func testSaysWhatIsLeftWhenItIsntFinished() async throws {
        let (model, rae) = try await signedIn()
        StubProtocol.responses["POST /v1/me/mandates/rae-k3f9-stripe_refund/revoke"] = (503, #"{"error":"down"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1"] = (200, #"{"done":false,"removed":["schedule","memory"],"failed":["key"]}"#)
        let removed = await model.removeTaf(rae)
        let message = try XCTUnwrap(removed)
        XCTAssertTrue(message.contains("KIFF didn't revoke 1 of its Cards: revoke them in KIFF."), message)
        XCTAssertTrue(message.contains("its key (its vault) couldn't be removed. Tap Remove again"), message)
        XCTAssertNotNil(model.cloudTaf(for: rae), "still there to finish")
    }

    func testNothingHappensWithoutFaceID() async throws {
        let (model, rae) = try await signedIn(signer: RefusingSigner())
        StubProtocol.sent = []
        let message = await model.removeTaf(rae)
        XCTAssertNil(message)
        XCTAssertTrue(StubProtocol.sent.isEmpty)
    }
}
