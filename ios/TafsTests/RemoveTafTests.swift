import XCTest
@testable import Tafs

/// Removing a taf: KIFF revokes its Cards first, then tafs cloud stops it and deletes its
/// runs, chats and memory, its key and its name.
@MainActor
final class RemoveTafTests: XCTestCase {
    private static let raeCard = "rae-k3f9-stripe_refund"

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private static let raeLookupCard = "rae-k3f9-stripe_get_charge"

    private func signedIn(signer: ApprovalSigning = StubSigner(), cards: [String] = [raeCard]) async throws -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.entry(agent: "rae-k3f9", name: "Rae", cards: cards)]))
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
        XCTAssertTrue(message.contains("its key couldn't be removed. tafs tries again each time it refreshes."), message)
        XCTAssertNotNil(model.cloudTaf(for: rae), "still there to finish")
    }

    func testNothingHappensWithoutFaceID() async throws {
        let (model, rae) = try await signedIn(signer: RefusingSigner())
        StubProtocol.sent = []
        let message = await model.removeTaf(rae)
        XCTAssertNil(message)
        XCTAssertTrue(StubProtocol.sent.isEmpty)
    }

    private func untilHeldRequestArrives() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
    }

    /// Archived but not finished: the app no longer lists the taf, so the removal must come
    /// back from tafs cloud's list and be finished on refresh.
    func testAnUnfinishedRemovalIsFinishedOnTheNextRefresh() async throws {
        let (model, rae) = try await signedIn()
        StubProtocol.responses["POST /v1/me/mandates/rae-k3f9-stripe_refund/revoke"] = (200, "{}")
        StubProtocol.responses["DELETE /v1/tafs/depl_1"] = (200, #"{"done":false,"removed":["schedule","memory"],"failed":["key"]}"#)
        _ = await model.removeTaf(rae)

        // Refresh: tafs cloud no longer lists it as a taf, only as a removal to finish.
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list(removing: #"[{"id":"depl_1","name":"Rae"}]"#))
        StubProtocol.responses["DELETE /v1/tafs/depl_1"] = (502, #"{"error":"kiff/agents is down"}"#)
        await model.refresh()
        XCTAssertEqual(model.unfinishedRemovals.map(\.name), ["Rae"], "shown, and tried again")
        XCTAssertEqual(sent("DELETE", "/v1/tafs/depl_1").count, 2)

        StubProtocol.responses["DELETE /v1/tafs/depl_1"] = (200, #"{"done":true,"removed":["schedule","memory","key","name"],"failed":[]}"#)
        await model.refresh()
        XCTAssertEqual(sent("DELETE", "/v1/tafs/depl_1").count, 3)
        XCTAssertTrue(model.unfinishedRemovals.isEmpty)
    }

    /// The account changes while Face ID is up: nothing of the removal goes out.
    func testAnAccountChangeDuringFaceIDStopsTheRemoval() async throws {
        let signer = SuspendingSigner()
        let (model, rae) = try await signedIn(signer: signer)
        signer.whileSigning = {
            await model.signOut()
            model.kiff.token = "kiff_dev_b"
            model.cloud.token = "kiff_dev_b"
        }
        StubProtocol.sent = []
        let message = await model.removeTaf(rae)
        XCTAssertNil(message)
        XCTAssertTrue(sent("POST", "/v1/me/mandates/rae-k3f9-stripe_refund/revoke").isEmpty)
        XCTAssertTrue(sent("DELETE", "/v1/tafs/depl_1").isEmpty)
    }

    /// The account changes while the first of two Cards is being revoked: the second isn't
    /// revoked, and tafs cloud isn't asked, under the new sign-in.
    func testAnAccountChangeBetweenRevocationsStopsTheRest() async throws {
        let (model, rae) = try await signedIn(cards: [Self.raeCard, Self.raeLookupCard])
        let cards = try XCTUnwrap(model.taf(rae)?.kiffCardIDs)
        XCTAssertEqual(cards.count, 2)
        for id in cards { StubProtocol.responses["POST /v1/me/mandates/\(id)/revoke"] = (200, "{}") }
        StubProtocol.holdOnce = "POST /v1/me/mandates/\(cards[0])/revoke"
        let removal = Task { await model.removeTaf(rae) }

        await untilHeldRequestArrives()
        // Signed out without a request (the stub holds its one loading thread), so the
        // account has changed by the time the held revocation answers.
        model.kiff.token = nil
        await model.signOut()
        model.kiff.token = "kiff_dev_b"
        model.cloud.token = "kiff_dev_b"
        StubProtocol.release.signal()

        let message = await removal.value
        XCTAssertNil(message)
        XCTAssertTrue(sent("POST", "/v1/me/mandates/\(cards[1])/revoke").isEmpty, "no revocation under the new sign-in")
        XCTAssertTrue(sent("DELETE", "/v1/tafs/depl_1").isEmpty)
    }

    /// Finishing an old account's removal waited while the owner signed out: what that
    /// refresh read is dropped, not shown after the switch.
    func testARefreshFinishingARemovalDoesntOutliveItsAccount() async throws {
        let (model, _) = try await signedIn()
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae], removing: #"[{"id":"depl_9","name":"Old"}]"#))
        StubProtocol.responses["DELETE /v1/tafs/depl_9"] = (200, #"{"done":true,"removed":["schedule","memory","key","name"],"failed":[]}"#)
        StubProtocol.holdOnce = "DELETE /v1/tafs/depl_9"
        let refresh = Task { await model.refresh() }

        await untilHeldRequestArrives()
        model.kiff.token = nil   // sign out without a request: the stub holds its loading thread
        await model.signOut()
        StubProtocol.release.signal()
        await refresh.value

        XCTAssertTrue(model.cloudTafs.isEmpty, "account A's tafs aren't put back after it signed out")
        XCTAssertTrue(model.unfinishedRemovals.isEmpty)
    }
}

/// A signer whose Face ID waits on `whileSigning`, so a test can change the account then.
@MainActor
final class SuspendingSigner: ApprovalSigning {
    var whileSigning: (@MainActor () async -> Void)?
    var publicKeyDER: Data { Data([0x30, 0x59]) }
    func sign(_ message: Data, reason: String) async throws -> String {
        await whileSigning?()
        return "signature"
    }
    func signConfirmed(_ message: Data) throws -> String { "signature" }
}
