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

    /// Removing a device that holds an approval key or a passkey is an approval (desktop-client.md): the
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
}
