import XCTest
@testable import Tafs

/// The account's time zone, which ends a Card's day, shown in Settings (kiff/tafs#96),
/// as KIFF reads it on GET /v1/me.
final class TimeZoneTests: XCTestCase {
    func testTheAccountSaysItsTimeZone() throws {
        let json = #"{"tenant_id":"t1","tenant_slug":"acme","email":"o@acme.test","time_zone":"Europe/Paris"}"#
        let account = try JSONDecoder().decode(KIFFClient.Account.self, from: Data(json.utf8))
        XCTAssertEqual(account.timeZone, "Europe/Paris")
        let older = try JSONDecoder().decode(KIFFClient.Account.self, from: Data(#"{"tenant_id":"t1","tenant_slug":"acme"}"#.utf8))
        XCTAssertNil(older.timeZone, "a KIFF that doesn't say: Settings shows UTC, its default")
    }
}
