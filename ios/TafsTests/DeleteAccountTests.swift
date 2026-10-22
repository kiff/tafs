import XCTest
@testable import Tafs

/// Deleting the account (kiff/tafs#185): one Face ID, then tafs cloud erases the tafs and what
/// it and kiff/agents keep, then planque the connections, then KIFF the account, each signed
/// by the phone, KIFF last, and the app signs out. A step that fails stops the rest.
@MainActor
final class DeleteAccountTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private func signedIn(signer: FaceIDSigner) async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session),
                             planque: PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return model
    }

    /// The writes sent, in order, as "METHOD host path".
    private func writes() -> [String] {
        StubProtocol.sent.compactMap { r in
            guard let m = r.httpMethod, m != "GET" else { return nil }
            return "\(m) \(r.url?.host ?? "") \(r.url?.path ?? "")"
        }
    }

    func testKIFFIsSignedForADeletion() {
        XCTAssertTrue(KIFFClient.signsSetup("/v1/me/delete"))
    }

    func testDeletesInTafsCloudThenPlanqueThenKIFFSignedAndSignsOut() async throws {
        let signer = FaceIDSigner()
        let model = await signedIn(signer: signer)
        StubProtocol.responses["POST /v1/account/delete"] = (200, #"{"done":true,"removed":2}"#)
        StubProtocol.responses["DELETE /v1/accounts/t_01"] = (200, #"{"erased":true}"#)
        StubProtocol.responses["POST /v1/me/delete"] = (202, #"{"status":"deleted_pending"}"#)

        let done = await model.deleteAccount()
        XCTAssertTrue(done, model.deleteAccountError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.delete-account.v1|t_01"], "one Face ID")
        let order = writes().filter { !$0.hasSuffix("/v1/push") && !$0.contains("/v1/auth") }
        XCTAssertEqual(Array(order.prefix(3)), [
            "POST cloud.test /v1/account/delete",
            "DELETE planque.test /v1/accounts/t_01",
            "POST api.test /v1/me/delete",
        ])
        let kiff = try XCTUnwrap(StubProtocol.sent.first { $0.url?.path == "/v1/me/delete" })
        XCTAssertNotNil(kiff.value(forHTTPHeaderField: "KIFF-Device-Signature"), "KIFF takes a deletion only signed by the phone")
        let planque = try XCTUnwrap(StubProtocol.sent.first { $0.url?.host == "planque.test" })
        XCTAssertNotNil(planque.value(forHTTPHeaderField: "Planque-Device-Signature"))
        XCTAssertNotEqual(model.mode, .kiff, "signed out")
        XCTAssertNil(model.kiff.setupSigner)
    }

    func testOnlyTheOwnerCanAndNothingElseIsAsked() async {
        let model = await signedIn(signer: FaceIDSigner())
        StubProtocol.responses["POST /v1/account/delete"] = (403, #"{"error":"your role on this KIFF account cannot delete it"}"#)

        let done = await model.deleteAccount()
        XCTAssertFalse(done)
        XCTAssertEqual(model.deleteAccountError, "Only the account's owner can delete it.")
        XCTAssertFalse(writes().contains { $0.contains("planque.test") || $0.hasSuffix("/v1/me/delete") })
        XCTAssertEqual(model.mode, .kiff)
    }

    func testAStepThatFailsStopsTheRestAndAskingAgainGoesOn() async {
        let model = await signedIn(signer: FaceIDSigner())
        StubProtocol.responses["POST /v1/account/delete"] = (200, #"{"done":true,"removed":0}"#)
        StubProtocol.responses["DELETE /v1/accounts/t_01"] = (502, #"{"error":"planque couldn't reach the vendor"}"#)

        let first = await model.deleteAccount()
        XCTAssertFalse(first)
        XCTAssertEqual(model.deleteAccountError?.hasPrefix("Your tafs are erased, but your connections aren't yet"), true)
        XCTAssertFalse(writes().contains { $0.hasSuffix("/v1/me/delete") }, "KIFF comes last, only once the rest is done")
        XCTAssertEqual(model.mode, .kiff)

        // planque doesn't know the account any more: that counts as done.
        StubProtocol.responses["DELETE /v1/accounts/t_01"] = (404, #"{"error":"no such account"}"#)
        StubProtocol.responses["POST /v1/me/delete"] = (202, #"{"status":"deleted_pending"}"#)
        let second = await model.deleteAccount()
        XCTAssertTrue(second, model.deleteAccountError ?? "")
    }
}
