import XCTest
@testable import Tafs

/// The account's time zone, which ends a Card's day (kiff/tafs#96): shown from GET /v1/me
/// and set from Settings as a signed setup call (kiff-cloud #1278).
@MainActor
final class TimeZoneTests: XCTestCase {
    override func tearDown() {
        AccountTime.use(nil)
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
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme","time_zone":"UTC"}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")

        let here = TimeZone.current.identifier
        StubProtocol.responses["PUT /v1/me/time-zone"] = (200, #"{"time_zone":"\#(here)"}"#)
        StubProtocol.thenRespond["PUT /v1/me/time-zone"] = ["GET /v1/me": (200, #"{"tenant_id":"t1","tenant_slug":"acme","time_zone":"\#(here)"}"#)]
        let unlocksBefore = signer.signed.count // signing in signs too
        await model.useThisDevicesTimeZone()

        let put = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PUT" })
        XCTAssertEqual(try JSONDecoder().decode([String: String].self, from: try XCTUnwrap(put.httpBody)), ["time_zone": here])
        let header = try XCTUnwrap(put.value(forHTTPHeaderField: "KIFF-Device-Signature"), "a signed setup call, as KIFF asks of a phone")
        XCTAssertTrue(header.hasPrefix("t="), header)
        // One unlock (sign), whose confirmation then signs KIFF's request (signConfirmed): the
        // signer records both, in that order.
        let during = Array(signer.signed.dropFirst(unlocksBefore))
        XCTAssertEqual(during.count, 2, "\(during)")
        XCTAssertTrue(during.first?.hasPrefix("tafs.time-zone.v1|t1|") ?? false, "the unlock: \(during)")
        XCTAssertTrue(during.last?.hasPrefix("kiff.request.v1") ?? false, "KIFF's request message: \(during)")
        XCTAssertEqual(model.kiffAccount?.timeZone, here, "read again from KIFF")
        XCTAssertNil(model.timeZoneError)

        StubProtocol.responses["PUT /v1/me/time-zone"] = (403, #"{"error":"only the account's owner or an admin can change its time zone"}"#)
        await model.useThisDevicesTimeZone()
        XCTAssertNotNil(model.timeZoneError, "KIFF decides who may")
    }

    /// Every time the app shows is in the account's zone, whatever this device's is
    /// (kiff/tafs#211, 6.11): a hold's deadline, a chat's times, the Record's days.
    func testTimesShowInTheAccountsZone() {
        let at = Date(timeIntervalSince1970: 1_792_812_600) // 2026-10-24T03:30:00Z
        AccountTime.use("America/New_York")
        XCTAssertEqual(at.shortTime, "23:30")
        XCTAssertEqual(AccountTime.calendar.component(.day, from: at), 23, "the day before, in New York")
        XCTAssertTrue(RecordView.dayTitle(at).hasPrefix("Friday"), RecordView.dayTitle(at))
        AccountTime.use("Asia/Tokyo")
        XCTAssertEqual(at.shortTime, "12:30")
        XCTAssertEqual(AccountTime.calendar.component(.day, from: at), 24)
        XCTAssertTrue(RecordView.dayTitle(at).hasPrefix("Saturday"), RecordView.dayTitle(at))
        AccountTime.use("Not/AZone")
        XCTAssertEqual(at.shortTime, "03:30", "a zone this device doesn't know is UTC, KIFF's default")
        AccountTime.use(nil)
        XCTAssertEqual(AccountTime.zone, TimeZone.current, "no account: this device's")
    }

    /// The account's zone is the one in use once KIFF says it, and the device's again
    /// once signed out.
    func testSigningInUsesTheAccountsZone() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: RecordingSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme","time_zone":"Asia/Tokyo"}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")
        XCTAssertEqual(AccountTime.zone.identifier, "Asia/Tokyo")
        await model.signOut()
        XCTAssertEqual(AccountTime.zone, TimeZone.current)
    }

}
