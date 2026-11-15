import XCTest
@testable import Tafs

/// The platform's passkey, stubbed: it records what it was asked for and answers with the vector's creation.
@MainActor
final class StubPasskeyMaker: PasskeyMaking {
    var asked: [(challenge: Data, userName: String, userID: Data)] = []
    var answer: Result<MadePasskey, Error> = .success(MadePasskey(clientDataJSON: Data("{\"type\":\"webauthn.create\"}".utf8),
                                                                    attestationObject: Data([0xa3, 0x63, 0x66, 0x6d, 0x74])))
    func create(challenge: Data, userName: String, userID: Data) async throws -> MadePasskey {
        asked.append((challenge, userName, userID))
        return try answer.get()
    }
}

/// Adding and removing the account's passkeys at kiff.dev (KIFF's pairing contract, "Registering one"): the
/// platform makes it, the approval key signs its registration behind Face ID, never a window, by an owner or
/// admin only. The body's shape is the shared vector's (PasskeyRegistrationTests).
@MainActor
final class PasskeyTests: XCTestCase {
    private static let ours = Data([0x30, 0x59, 0x02])
    private static let now = Date(timeIntervalSince1970: 1_763_229_600) // 2025-11-15T18:00:00Z

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        Keychain.delete(AppModel.approvalKeyedAccount)
        super.tearDown()
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    /// Signed in with an approval key KIFF holds, as `role`, with the people read.
    private func signedIn(_ signer: TwoKeySigner, maker: StubPasskeyMaker, role: String = "owner") async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session),
                             clock: { Self.now }, passkeyMaker: maker)
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["POST /v1/me/device/approval-key"] = (200, #"{"fingerprint":""# + KIFFClient.fingerprint(Self.ours) + #""}"#)
        StubProtocol.responses["GET /v1/groups"] = (200, #"{"groups":[]}"#)
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{},"checked_at":"2026-10-26T01:00:00Z"}"#)
        StubProtocol.responses["GET /v1/tenant/members"] = (200, #"{"members":[{"subject":"usr_7Hq2","role":"\#(role)","status":"active","email":"ana@acme.test","you":true}]}"#)
        StubProtocol.responses["GET /v1/me/passkeys"] = (200, #"{"passkeys":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        await model.refreshPeople()
        StubProtocol.sent = []
        return model
    }

    /// The platform is asked for kiff.dev's challenge for this account, this person and this moment; then the
    /// approval key signs `POST /v1/me/passkeys` with the creation's bytes, and the list is read again.
    func testAddingAPasskeyMakesItThenTheApprovalKeySignsItsRegistration() async throws {
        let signer = TwoKeySigner(), maker = StubPasskeyMaker()
        let model = await signedIn(signer, maker: maker)
        XCTAssertTrue(model.canAddPasskey)
        StubProtocol.responses["POST /v1/me/passkeys"] = (201, #"{"passkey":{"id":"pk_1","name":"Ana's iPhone","synced":null,"created_at":"2025-11-15T18:00:01Z"}}"#)
        StubProtocol.responses["GET /v1/me/passkeys"] = (200, #"{"passkeys":[{"id":"pk_1","name":"Ana's iPhone","synced":null}]}"#)

        let outcome = await model.addPasskey(name: " Ana's iPhone ", reason: "Add a passkey")
        XCTAssertEqual(outcome, .added)
        let asked = try XCTUnwrap(maker.asked.first)
        XCTAssertEqual(asked.challenge, PasskeyRegistration.challenge(tenant: "t_01", subject: "usr_7Hq2", issuedAt: "2025-11-15T18:00:00Z"))
        XCTAssertEqual(asked.userName, "ana@acme.test")
        XCTAssertEqual(asked.userID, Data("usr_7Hq2".utf8))
        let post = try XCTUnwrap(sent("POST", "/v1/me/passkeys").first)
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: try XCTUnwrap(post.httpBody)) as? [String: String])
        XCTAssertEqual(body, ["name": "Ana's iPhone", "issued_at": "2025-11-15T18:00:00Z",
                              "client_data_json": PasskeyRegistration.base64url(Data("{\"type\":\"webauthn.create\"}".utf8)),
                              "attestation_object": PasskeyRegistration.base64url(Data([0xa3, 0x63, 0x66, 0x6d, 0x74]))])
        XCTAssertTrue(post.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=approval") == true, "the approval key, never a window")
        XCTAssertEqual(signer.approvalPrompted.count, 1)
        XCTAssertEqual(model.passkeys.map(\.passkeyID), ["pk_1"])
    }

    /// The same passkey registered already is done, not a failure.
    func testAPasskeyRegisteredAlreadyIsDone() async {
        let model = await signedIn(TwoKeySigner(), maker: StubPasskeyMaker())
        StubProtocol.responses["POST /v1/me/passkeys"] = (409, #"{"error":"passkey_registered","reason":"passkey_registered"}"#)
        let outcome = await model.addPasskey(name: "Ana's iPhone", reason: "Add a passkey")
        XCTAssertEqual(outcome, .alreadyThere)
    }

    /// Only an owner or admin adds one: anyone else is told so, and nothing is made or sent.
    func testOnlyAnOwnerOrAdminAddsOne() async {
        let maker = StubPasskeyMaker()
        let model = await signedIn(TwoKeySigner(), maker: maker, role: "editor")
        XCTAssertFalse(model.canAddPasskey)
        let outcome = await model.addPasskey(name: "Ana's iPhone", reason: "Add a passkey")
        XCTAssertEqual(outcome, .notAllowed)
        XCTAssertTrue(maker.asked.isEmpty)
        XCTAssertTrue(sent("POST", "/v1/me/passkeys").isEmpty)
    }

    /// A name KIFF wouldn't take makes nothing; closing the platform's sheet sends nothing.
    func testABadNameOrAClosedSheetSendsNothing() async {
        let maker = StubPasskeyMaker()
        let model = await signedIn(TwoKeySigner(), maker: maker)
        let tooLong = await model.addPasskey(name: String(repeating: "x", count: 65), reason: "Add a passkey")
        XCTAssertEqual(tooLong, .badName)
        XCTAssertTrue(maker.asked.isEmpty)
        maker.answer = .failure(PasskeyMaker.MakeError.cancelled)
        let closed = await model.addPasskey(name: "Ana's iPhone", reason: "Add a passkey")
        XCTAssertEqual(closed, .cancelled)
        XCTAssertTrue(sent("POST", "/v1/me/passkeys").isEmpty)
    }

    /// Removing one is the approval key's, behind Face ID; a computer's own passkey goes with its computer.
    func testRemovingOneIsSignedByTheApprovalKeyAndAComputersOwnIsntRemovable() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, maker: StubPasskeyMaker())
        StubProtocol.responses["GET /v1/me/passkeys"] = (200, #"{"passkeys":[{"id":"pk_1","name":"Ana's iPhone"},{"name":"Windows PC","device_id":"dev_pc"}]}"#)
        await model.refreshPasskeys()
        XCTAssertEqual(model.passkeys.map(\.removable), [true, false])
        StubProtocol.responses["DELETE /v1/me/passkeys/pk_1"] = (204, "")
        let removed = await model.removePasskey(model.passkeys[0], reason: "Remove the passkey")
        XCTAssertTrue(removed)
        let delete = try XCTUnwrap(sent("DELETE", "/v1/me/passkeys/pk_1").first)
        XCTAssertTrue(delete.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=approval") == true)
        let computers = await model.removePasskey(model.passkeys[1], reason: "Remove the passkey")
        XCTAssertFalse(computers, "a computer's own isn't removed here")
    }
}
