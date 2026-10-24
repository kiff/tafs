import UserNotifications
import XCTest
@testable import Tafs

/// A hold's notification, filled in on the phone (kiff/tafs#210): the taf, what it asks and
/// when it lapses, in the account's zone and in the app's own words.
@MainActor
final class HoldNotificationTests: XCTestCase {
    private let heldJSON = """
    {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"rae-k3f9","action":"refund_order","entity_id":"ord_4502",
      "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000}],
      "parameters":{"amount_cents":48000},"created_at":"2026-10-14T10:41:00Z","expires_at":"2026-10-14T10:51:00Z",
      "accounting_version":2}]}
    """

    private func clients() -> (KIFFClient, TafsCloud) {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, heldJSON)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme","time_zone":"Asia/Tokyo"}"#)
        return (KIFFClient(baseURL: URL(string: "https://api.test")!, webURL: URL(string: "https://app.test")!, token: "dev_token", session: session),
                TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "dev_token", session: session))
    }

    func testTheExtensionReadsTheSessionTheAppKeeps() {
        XCTAssertEqual(HoldNotification.sessionAccount, AppModel.tokenAccount)
    }

    func testItSaysWhatTheAppSays() throws {
        let hold = try XCTUnwrap(try KIFFClient.decoder.decode([String: [KIFFClient.CardException]].self, from: Data(heldJSON.utf8))["exceptions"]?.first)
        let call = KIFFMapping.held(hold, tafID: UUID())
        let utc = TimeZone(identifier: "UTC")!
        let words = HoldNotification.words(hold, taf: "Rae", zone: utc)
        XCTAssertEqual(words.title, "Rae needs you")
        XCTAssertTrue(words.body.hasPrefix("\(call.proposed.summary), \(try XCTUnwrap(call.proposed.amount).formatted) · \(call.reason). Answer by 10:51"), words.body)
        XCTAssertEqual(HoldNotification.words(hold, taf: nil, zone: utc).title, "A taf needs you")
    }

    func testTheDeadlineNamesTheZoneOnlyWhenItsClockIsntTheDevices() {
        let at = ISO8601DateFormatter().date(from: "2026-10-14T10:51:00Z")!
        XCTAssertFalse(HoldNotification.time(at, in: .current).contains(" "), "the device's own clock: no zone")
        let other = TimeZone.current.secondsFromGMT(for: at) == 9 * 3600 ? TimeZone(identifier: "America/New_York")! : TimeZone(identifier: "Asia/Tokyo")!
        let shown = HoldNotification.time(at, in: other)
        XCTAssertTrue(shown.hasSuffix(" " + other.abbreviation(for: at)!), shown)
    }

    func testFillsInTheHoldItNames() async {
        let (kiff, cloud) = clients()
        let content = UNMutableNotificationContent()
        content.title = "A taf needs you"
        content.body = "Refund order waits for your answer."
        content.userInfo = ["hold_id": "ce_1", "open": "needs-you"]
        await HoldNotification.fill(content, kiff: kiff, cloud: cloud)
        XCTAssertEqual(content.title, "Rae needs you")
        XCTAssertTrue(content.body.contains("Answer by 19:51"), "in the account's zone, Tokyo: \(content.body)")
    }

    func testAHoldItCantReadLeavesThePushAsItCame() async {
        let (kiff, cloud) = clients()
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        let content = UNMutableNotificationContent()
        content.title = "A taf needs you"
        content.body = "Refund order waits for your answer."
        content.userInfo = ["hold_id": "ce_1"]
        await HoldNotification.fill(content, kiff: kiff, cloud: cloud)
        XCTAssertEqual(content.title, "A taf needs you")
        XCTAssertEqual(content.body, "Refund order waits for your answer.")
    }
}
