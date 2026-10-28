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

    func testSigningAnotherDeviceOutAsksFaceIDThenRemovesIt() async throws {
        let signer = FaceIDSigner()
        let model = await signedIn(signer: signer)
        await model.refreshDevices()
        StubProtocol.responses["DELETE /v1/me/devices/dev_phone01"] = (204, "")
        let phone = try XCTUnwrap(model.devices.first { $0.id == "dev_phone01" })
        let done = await model.removeDevice(phone)
        XCTAssertTrue(done)
        XCTAssertEqual(signer.prompts, ["tafs.devices.v1|t_01|dev_phone01"])
        XCTAssertEqual(deletes(), ["/v1/me/devices/dev_phone01"])
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
