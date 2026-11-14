import CryptoKit
import XCTest
@testable import Tafs

/// Settings, Devices: the devices signed in as this person, and signing
/// another one out behind Face ID. This device signs out with Sign out instead.
@MainActor
final class DevicesTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private let list = #"{"devices":[{"id":"dev_ipad01","name":"iPad","this_device":true,"last_used_at":"2026-10-28T09:00:00Z"},{"id":"dev_phone01","name":"iPhone","last_used_at":"2026-10-20T09:00:00Z"},{"id":"ses_old","name":"kiff/agents e2e","last_seen_at":"2026-10-27T09:00:00Z"}]}"#

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
        StubProtocol.responses["GET /v1/me/devices"] = (200, list)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return model
    }

    private func deletes() -> [String] {
        StubProtocol.sent.filter { $0.httpMethod == "DELETE" }.compactMap { $0.url?.path }
    }

    func testTheListShowsEachDeviceWithWhenItWasLastUsed() async {
        let model = await signedIn(signer: FaceIDSigner())
        await model.refreshDevices()
        XCTAssertEqual(model.devices.map(\.id), ["dev_ipad01", "dev_phone01", "ses_old"])
        XCTAssertEqual(model.devices.map(\.thisDevice), [true, false, false])
        // A session from before devices says when it was last seen.
        XCTAssertNotNil(model.devices.last?.lastUsed)
        XCTAssertEqual(DevicesView.lastUsed(nil), "Not used yet")
    }

    func testEachDeviceIsCalledWhatKIFFNamesItInTheAppsLanguage() throws {
        // KIFF's display_name, drawn from the device's kind in the app's language; the name it
        // gave at sign-in (in whatever language the app had then) only from a KIFF that sends none.
        let json = #"{"devices":[{"id":"dev_1","name":"tafs en iPad","display_name":"tafs on iPad"},{"id":"dev_2","name":"Ana's iPhone"}]}"#
        struct Response: Decodable { let devices: [KIFFClient.Device] }
        let r = try JSONDecoder().decode(Response.self, from: Data(json.utf8))
        XCTAssertEqual(r.devices.map(\.name), ["tafs on iPad", "Ana's iPhone"])
    }

    /// Removing a device that holds an approval key or a passkey is an approval (KIFF's pairing contract): the
    /// approval key signs the DELETE itself, `kiff.request.v1` over its path and the empty body, behind
    /// Face ID, and the request carries it.
    func testSigningAnotherDeviceOutIsSignedByTheApprovalKey() async throws {
        let signer = FaceIDSigner()
        let model = await signedIn(signer: signer)
        await model.refreshDevices()
        StubProtocol.responses["DELETE /v1/me/devices/dev_phone01"] = (204, "")
        let phone = try XCTUnwrap(model.devices.first { $0.id == "dev_phone01" })
        let done = await model.removeDevice(phone)
        XCTAssertTrue(done)
        XCTAssertEqual(signer.prompts.count, 1, "one Face ID")
        let empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        let lines = try XCTUnwrap(signer.prompts.first).components(separatedBy: "\n")
        XCTAssertEqual(Array(lines.prefix(5)), ["kiff.request.v1", "t_01", "DELETE", "/v1/me/devices/dev_phone01", empty])
        XCTAssertEqual(deletes(), ["/v1/me/devices/dev_phone01"])
        let header = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "DELETE" }?.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(header, "t=\(lines[5]),v1=signature", "v1, never a window's v2")
    }

    func testThisDeviceAndARefusedFaceIDRemoveNothing() async throws {
        let signer = FaceIDSigner()
        let model = await signedIn(signer: signer)
        await model.refreshDevices()
        let ipad = try XCTUnwrap(model.devices.first { $0.thisDevice })
        let removedSelf = await model.removeDevice(ipad)
        XCTAssertFalse(removedSelf, "this device signs out with Sign out")
        signer.refuse = true
        let phone = try XCTUnwrap(model.devices.first { $0.id == "dev_phone01" })
        let removed = await model.removeDevice(phone)
        XCTAssertFalse(removed)
        XCTAssertTrue(deletes().isEmpty)
    }

    // MARK: Pairing a computer (KIFF's pairing contract §5.3)

    private func pairingCode() throws -> PairingRequest {
        let exp = ISO8601DateFormatter().string(from: Date().addingTimeInterval(120))
        let fields: [String: Any] = ["v": "kiff.pair.v1", "pub": PairingTests.pub, "approval_pub": PairingTests.approvalPub, "name": "tafs on this Windows PC",
                                     "platform": "windows", "kind": "hello", "nonce": PairingTests.nonce, "exp": exp]
        return try PairingRequest.read(String(decoding: try JSONSerialization.data(withJSONObject: fields), as: UTF8.self)).get()
    }

    /// The approval key signs `POST /v1/me/pairings` over the QR's fields, behind one Face ID, never in a
    /// window; KIFF's code, the one checked, is said as done, and Devices is read again.
    func testPairingIsSignedByTheApprovalKeyOverTheCodesFields() async throws {
        let signer = FaceIDSigner()
        let model = await signedIn(signer: signer)
        let request = try pairingCode()
        StubProtocol.responses["POST /v1/me/pairings"] = (201, #"{"pairing":{"id":"pair_1","code":"XFI4MD","expires":"2026-11-14T21:00:00Z"}}"#)
        StubProtocol.sent = []
        let outcome = await model.pairComputer(request)
        XCTAssertEqual(outcome, .paired(Words.Settings.Devices.pairDone(device: "tafs on this Windows PC", code: "XFI4MD")))

        XCTAssertEqual(signer.prompts.count, 1, "one Face ID")
        let lines = try XCTUnwrap(signer.prompts.first).components(separatedBy: "\n")
        let bodyHash = SHA256.hash(data: request.body).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(Array(lines.prefix(5)), ["kiff.request.v1", "t_01", "POST", "/v1/me/pairings", bodyHash])
        let sent = try XCTUnwrap(StubProtocol.sent.first { $0.httpMethod == "POST" && $0.url?.path == "/v1/me/pairings" })
        XCTAssertEqual(sent.value(forHTTPHeaderField: "KIFF-Device-Signature"), "t=\(lines[5]),v1=signature", "v1, never a window's v2")
        XCTAssertTrue(StubProtocol.sent.contains { $0.httpMethod == "GET" && $0.url?.path == "/v1/me/devices" }, "Devices read again")
    }

    /// KIFF's code differs from the one checked: the person is told, and to sign it out if it isn't theirs.
    func testAKIFFCodeThatDiffersIsSaid() throws {
        let request = try pairingCode()
        XCTAssertEqual(AppModel.pairedWords(request, kiffCode: "ZZZZZZ"),
                       Words.Settings.Devices.pairCodeDiffers(device: "tafs on this Windows PC", kiff: "ZZZZZZ", shown: "XFI4MD"))
    }

    /// A refusal in KIFF's words when it sent them; the app's own for each known code where it sent none.
    func testARefusedPairingIsSaidInKIFFsWordsFirst() {
        XCTAssertEqual(AppModel.pairingRefusal(message: "Ce code a expiré.", reason: "pairing_expired"), "Ce code a expiré.")
        XCTAssertEqual(AppModel.pairingRefusal(message: "pairing_expired", reason: "pairing_expired"), Words.Settings.Devices.pairExpired)
        XCTAssertEqual(AppModel.pairingRefusal(message: "pairing_used", reason: "pairing_used"), Words.Settings.Devices.pairUsed)
        XCTAssertEqual(AppModel.pairingRefusal(message: "key_registered", reason: "key_registered"), Words.Settings.Devices.pairKeyRegistered)
        XCTAssertEqual(AppModel.pairingRefusal(message: "invalid_pairing", reason: "invalid_pairing"), Words.Settings.Devices.pairInvalid)
    }

    /// KIFF's refusal reaches the model with its words as the message and its code as the reason.
    func testKIFFsRefusalCarriesItsWords() async throws {
        let model = await signedIn(signer: FaceIDSigner())
        StubProtocol.responses["POST /v1/me/pairings"] = (409, #"{"error":"pairing_used","words":"Ce code a déjà servi."}"#)
        let used = await model.pairComputer(try pairingCode())
        XCTAssertEqual(used, .failed("Ce code a déjà servi."))
        StubProtocol.responses["POST /v1/me/pairings"] = (400, #"{"error":"pairing_expired"}"#)
        let expired = await model.pairComputer(try pairingCode())
        XCTAssertEqual(expired, .failed(Words.Settings.Devices.pairExpired))
    }

    /// A cancelled Face ID sends nothing.
    func testACancelledFaceIDSendsNoPairing() async throws {
        let signer = FaceIDSigner()
        signer.refuse = true
        let model = await signedIn(signer: signer)
        StubProtocol.sent = []
        let cancelled = await model.pairComputer(try pairingCode())
        XCTAssertEqual(cancelled, .cancelled)
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.path == "/v1/me/pairings" })
    }

    /// What the alert says before the code, by kind, and a code the phone won't pair in words.
    func testTheAlertSaysWhatPairingGives() throws {
        let lines = PairComputerView.lines(try pairingCode())
        XCTAssertEqual(lines, [Words.Settings.Devices.pairGivesHello(device: "tafs on this Windows PC"), Words.Settings.Devices.pairOnlyNow])
        XCTAssertEqual(PairComputerView.words(.notAPairingCode), Words.Settings.Devices.pairNotAPairing)
        XCTAssertEqual(PairComputerView.words(.expired), Words.Settings.Devices.pairExpired)
        XCTAssertEqual(PairComputerView.words(.badKeys), Words.Settings.Devices.pairInvalid)
        XCTAssertEqual(PairComputerView.check(try pairingCode()), Words.Settings.Devices.pairCheck(code: "XFI4MD"))
    }

    /// A phone or tablet (§5.8): what it gets whatever its biometric, only one in hand now, and the
    /// question names the new phone or tablet, not a computer.
    func testThePhoneAlertSaysWhatAPhoneGets() throws {
        let exp = ISO8601DateFormatter().string(from: Date().addingTimeInterval(120))
        let fields: [String: Any] = ["v": "kiff.pair.v1", "pub": PairingTests.pub, "approval_pub": PairingTests.approvalPub, "name": "tafs on iPad",
                                     "platform": "ipados", "kind": "enclave", "device_kind": "ipad", "nonce": PairingTests.nonce, "exp": exp]
        let ipad = try PairingRequest.read(String(decoding: try JSONSerialization.data(withJSONObject: fields), as: UTF8.self)).get()
        XCTAssertEqual(PairComputerView.lines(ipad), [Words.Settings.Devices.pairGivesPhone(device: "tafs on iPad"), Words.Settings.Devices.pairOnlyNowPhone])
        XCTAssertEqual(PairComputerView.check(ipad), Words.Settings.Devices.pairCheckPhone(code: "XFI4MD"))
        XCTAssertEqual(Words.Settings.Devices.pair, "Add a device")
    }
}
