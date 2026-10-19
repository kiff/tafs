import XCTest
@testable import Tafs

/// The account's time zone, which ends a Card's day (kiff/tafs#96): shown from GET /v1/me
/// and set from Settings as a signed setup call (kiff-cloud #1278).
@MainActor
final class TimeZoneTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    func testTheAccountSaysItsTimeZone() throws {
        let json = #"{"tenant_id":"t1","tenant_slug":"acme","email":"o@acme.test","time_zone":"Europe/Paris"}"#
        let account = try JSONDecoder().decode(KIFFClient.Account.self, from: Data(json.utf8))
        XCTAssertEqual(account.timeZone, "Europe/Paris")
        let older = try JSONDecoder().decode(KIFFClient.Account.self, from: Data(#"{"tenant_id":"t1","tenant_slug":"acme"}"#.utf8))
        XCTAssertNil(older.timeZone, "a KIFF that doesn't say: Settings shows UTC, its default")
    }

    func testSettingsSetsThisDevicesTimeZoneSigned() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let signer = RecordingSigner()
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        for path in ["GET /v1/me/mandates", "GET /v1/me/mandates?include=use"] { StubProtocol.responses[path] = (200, #"{"mandates":[]}"#) }
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme","time_zone":"UTC"}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")

        let here = TimeZone.current.identifier
        StubProtocol.responses["PUT /v1/me/time-zone"] = (200, #"{"time_zone":"\#(here)"}"#)
        StubProtocol.thenRespond["PUT /v1/me/time-zone"] = ["GET /v1/me": (200, #"{"tenant_id":"t1","tenant_slug":"acme","time_zone":"\#(here)"}"#)]
        let unlocksBefore = signer.signed.count // signing in may unlock too (alerts)
        await model.useThisDevicesTimeZone()

        let put = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PUT" })
        XCTAssertEqual(try JSONDecoder().decode([String: String].self, from: try XCTUnwrap(put.httpBody)), ["time_zone": here])
        let header = try XCTUnwrap(put.value(forHTTPHeaderField: "KIFF-Device-Signature"), "a signed setup call, as KIFF asks of a phone")
        XCTAssertTrue(header.hasPrefix("t="), header)
        XCTAssertEqual(signer.signed.count - unlocksBefore, 1, "one unlock for it")
        XCTAssertTrue(signer.signed.last?.hasPrefix("tafs.time-zone.v1|t1|") ?? false, signer.signed.last ?? "")
        XCTAssertEqual(model.kiffAccount?.timeZone, here, "read again from KIFF")
        XCTAssertNil(model.timeZoneError)

        StubProtocol.responses["PUT /v1/me/time-zone"] = (403, #"{"error":"only the account's owner or an admin can change its time zone"}"#)
        await model.useThisDevicesTimeZone()
        XCTAssertNotNil(model.timeZoneError, "KIFF decides who may")
    }
}
