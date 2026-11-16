import LocalAuthentication
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
    private static let now = Date(timeIntervalSince1970: 1_794_765_600) // 2026-11-15T18:00:00Z

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

    /// Signed in as `role`, with the people read. KIFF answers the approval key's registration with
    /// `registration`: by default, it holds this phone's.
    private func signedIn(_ signer: TwoKeySigner, maker: StubPasskeyMaker, role: String = "owner",
                          registration: (Int, String)? = nil) async -> AppModel {
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
        StubProtocol.responses["POST /v1/me/device/approval-key"] = registration ?? (200, #"{"fingerprint":""# + KIFFClient.fingerprint(Self.ours) + #""}"#)
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
        StubProtocol.responses["POST /v1/me/passkeys"] = (201, #"{"passkey":{"id":"pk_1","name":"Ana's iPhone","synced":null,"created_at":"2026-11-15T18:00:01Z"}}"#)
        StubProtocol.responses["GET /v1/me/passkeys"] = (200, #"{"passkeys":[{"id":"pk_1","name":"Ana's iPhone","synced":null}]}"#)

        let outcome = await model.addPasskey(name: " Ana's iPhone ", reason: "Add a passkey")
        XCTAssertEqual(outcome, .added)
        let asked = try XCTUnwrap(maker.asked.first)
        XCTAssertEqual(asked.challenge, PasskeyRegistration.challenge(tenant: "t_01", subject: "usr_7Hq2", issuedAt: "2026-11-15T18:00:00Z"))
        XCTAssertEqual(asked.userName, "ana@acme.test")
        XCTAssertEqual(asked.userID, Data("usr_7Hq2".utf8))
        let post = try XCTUnwrap(sent("POST", "/v1/me/passkeys").first)
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: try XCTUnwrap(post.httpBody)) as? [String: String])
        XCTAssertEqual(body, ["name": "Ana's iPhone", "issued_at": "2026-11-15T18:00:00Z",
                              "client_data_json": PasskeyRegistration.base64url(Data("{\"type\":\"webauthn.create\"}".utf8)),
                              "attestation_object": PasskeyRegistration.base64url(Data([0xa3, 0x63, 0x66, 0x6d, 0x74]))])
        XCTAssertTrue(post.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=approval") == true, "the approval key, never a window")
        XCTAssertEqual(signer.approvalPrompted.count, 1)
        XCTAssertEqual(model.passkeys.map(\.passkeyID), ["pk_1"])
    }

    /// The same passkey registered already is done, not a failure. KIFF's answer, as it writes it: the code
    /// is `error`, with no `reason`.
    func testAPasskeyRegisteredAlreadyIsDone() async {
        let model = await signedIn(TwoKeySigner(), maker: StubPasskeyMaker())
        StubProtocol.responses["POST /v1/me/passkeys"] = (409, #"{"error":"passkey_registered","message":"this passkey is already registered on the account"}"#)
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

    /// A session that can't approve adds none: view-only, or without an approval key KIFF holds. The
    /// platform is asked for nothing, and nothing is sent.
    func testASessionThatCantApproveAddsNone() async {
        for registration in [(403, #"{"error":"view_only","message":"this device can't approve"}"#), (404, #"{"error":"not found"}"#)] {
            let maker = StubPasskeyMaker()
            let model = await signedIn(TwoKeySigner(), maker: maker, registration: registration)
            XCTAssertFalse(model.canAddPasskey, "\(registration.0)")
            let outcome = await model.addPasskey(name: "Ana's iPhone", reason: "Add a passkey")
            XCTAssertEqual(outcome, .notAllowed, "\(registration.0)")
            XCTAssertTrue(maker.asked.isEmpty, "\(registration.0)")
            XCTAssertTrue(sent("POST", "/v1/me/passkeys").isEmpty, "\(registration.0)")
        }
    }

    /// After the platform made one, cancelling the approval's Face ID is the person's cancel: nothing sent,
    /// and the view says the passkey made is in Passwords. A key that couldn't sign says why instead.
    func testCancellingTheApprovalAfterTheSheetIsNotAFailure() async {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, maker: StubPasskeyMaker())
        signer.approvalThrows = LAError(.userCancel)
        let cancelled = await model.addPasskey(name: "Ana's iPhone", reason: "Add a passkey")
        XCTAssertEqual(cancelled, .notRegistered(nil))
        signer.approvalThrows = ApprovalSigner.SignerError.notAuthenticated
        let failed = await model.addPasskey(name: "Ana's iPhone", reason: "Add a passkey")
        XCTAssertEqual(failed, .notRegistered("\(ApprovalSigner.SignerError.notAuthenticated.localizedDescription)"))
        XCTAssertTrue(sent("POST", "/v1/me/passkeys").isEmpty)
    }

    /// A sheet left open past KIFF's ten minutes from `issued_at` is refused for that: the view says try again.
    func testASheetLeftOpenTooLongSaysTryAgain() async {
        let model = await signedIn(TwoKeySigner(), maker: StubPasskeyMaker())
        StubProtocol.responses["POST /v1/me/passkeys"] = (400, #"{"error":"registration_invalid","reason":"issued_at","message":"issued_at is too far from now"}"#)
        let outcome = await model.addPasskey(name: "Ana's iPhone", reason: "Add a passkey")
        XCTAssertEqual(outcome, .tooSlow)
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

    /// Cancelling Face ID to remove one says it wasn't confirmed; a key that couldn't sign says why.
    func testACancelledRemovalSaysItWasntConfirmedAndAFailedOneSaysWhy() async {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, maker: StubPasskeyMaker())
        StubProtocol.responses["GET /v1/me/passkeys"] = (200, #"{"passkeys":[{"id":"pk_1","name":"Ana's iPhone"}]}"#)
        await model.refreshPasskeys()
        signer.approvalThrows = LAError(.userCancel)
        let cancelled = await model.removePasskey(model.passkeys[0], reason: "Remove the passkey")
        XCTAssertFalse(cancelled)
        XCTAssertEqual(model.passkeysError, "Remove the passkey didn't go through: it wasn't confirmed.")
        signer.approvalThrows = ApprovalSigner.SignerError.notAuthenticated
        let failed = await model.removePasskey(model.passkeys[0], reason: "Remove the passkey")
        XCTAssertFalse(failed)
        XCTAssertNotNil(model.passkeysError)
        XCTAssertTrue(sent("DELETE", "/v1/me/passkeys/pk_1").isEmpty)
    }

    /// What Devices says once Add is over: added (also when it was there already), nothing for a cancel, try
    /// again for a sheet left open, and a passkey made but not added says it's left in this phone's passwords.
    func testDevicesSaysHowAddingEnded() {
        XCTAssertEqual(DevicesView.words(for: .added, role: "owner"), Words.Settings.Devices.passkeyAdded)
        XCTAssertEqual(DevicesView.words(for: .alreadyThere, role: "owner"), Words.Settings.Devices.passkeyAdded)
        XCTAssertNil(DevicesView.words(for: .cancelled, role: "owner"))
        XCTAssertEqual(DevicesView.words(for: .tooSlow, role: "owner"), Words.Settings.Devices.passkeyTooSlow)
        XCTAssertEqual(DevicesView.words(for: .notRegistered(nil), role: "owner"), Words.Settings.Devices.passkeyNotRegistered)
        XCTAssertEqual(DevicesView.words(for: .notRegistered("KIFF said no"), role: "owner"), Words.Settings.Devices.passkeyNotRegistered + "\nKIFF said no")
        XCTAssertEqual(DevicesView.words(for: .failed("no Face ID"), role: "owner"), Words.Settings.Devices.passkeyNotMade(why: "no Face ID"))
        XCTAssertEqual(DevicesView.words(for: .notAllowed, role: "editor"), Words.Settings.Devices.passkeyAdminsOnly, "only owners and admins")
        XCTAssertEqual(DevicesView.words(for: .notAllowed, role: "admin"), Words.Settings.Devices.passkeyNeedsApprover, "an admin on a session that doesn't approve")
    }

    /// Removing one that didn't go through says what and why; a failed list says it couldn't be loaded.
    func testAFailedListSaysItCouldntBeLoaded() async {
        let model = await signedIn(TwoKeySigner(), maker: StubPasskeyMaker())
        StubProtocol.responses["GET /v1/me/passkeys"] = (500, #"{"error":"down"}"#)
        await model.refreshPasskeys()
        XCTAssertTrue(model.passkeysError?.hasPrefix("Couldn't load your passkeys.") == true, model.passkeysError ?? "nil")
    }
}
