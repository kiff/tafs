import XCTest
@testable import Tafs

/// The account's time zone, which ends a Card's day, shown and set from Settings
/// (kiff/tafs#96): KIFF keeps it and decides who may change it.
@MainActor
final class TimeZoneTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    func testSettingsSetsTheAccountToThisDevicesTimeZone() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        for path in ["GET /v1/me/mandates", "GET /v1/me/mandates?include=use"] { StubProtocol.responses[path] = (200, #"{"mandates":[]}"#) }
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme","email":"o@acme.test","time_zone":"UTC"}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")
        XCTAssertEqual(model.kiffAccount?.timeZone, "UTC")

        let here = TimeZone.current.identifier
        StubProtocol.responses["PUT /v1/me/time-zone"] = (200, #"{"time_zone":"\#(here)"}"#)
        StubProtocol.thenRespond["PUT /v1/me/time-zone"] = ["GET /v1/me": (200, #"{"tenant_id":"t1","tenant_slug":"acme","time_zone":"\#(here)"}"#)]
        await model.useThisDevicesTimeZone()
        let put = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PUT" })
        let body = try JSONDecoder().decode([String: String].self, from: try XCTUnwrap(put.httpBody))
        XCTAssertEqual(body, ["time_zone": here])
        XCTAssertEqual(model.kiffAccount?.timeZone, here, "read again from KIFF")
        XCTAssertNil(model.timeZoneError)

        StubProtocol.responses["PUT /v1/me/time-zone"] = (403, #"{"error":"only the account's owner or an admin can change its time zone"}"#)
        await model.useThisDevicesTimeZone()
        XCTAssertNotNil(model.timeZoneError, "KIFF decides who may")
    }
}
