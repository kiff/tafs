import XCTest
@testable import Tafs

/// "Report this payment" (docs/record.md): tafs cloud offers it on a payment this reader may report, and the receipt shows the
/// button only then. The same rule as Android's.
@MainActor
final class ReportPaymentTests: XCTestCase {
    private func page(_ extra: String) throws -> TafsCloud.ActivityPage {
        let call = #"{"proposal_id":"p1","action_name":"browse_pay","actor_id":"chase-1","outcome":"allowed","held":false,"created_at":"2026-11-06T10:00:00Z"}"#
        let json = #"""
        {"entries":[{"id":"e1","at":"2026-11-06T10:00:00Z","kind":"done","title":"Pay","detail":"Done, within its Card","time":"10:00","amount_text":"€12.00",
          "call":\#(call)\#(extra)}],"groups":[],"filters":[],"filter":"all","revision":"r","next_cursor":null}
        """#
        return try KIFFClient.decoder.decode(TafsCloud.ActivityPage.self, from: Data(json.utf8))
    }

    func testAnOfferIsReadAsTafsCloudSendsItAndNoOfferIsNoButton() throws {
        let offered = try page(#","report":{"trace_id":"tr_1","at":"2026-11-06T09:59:30.250Z"}"#).entries[0]
        XCTAssertEqual(offered.report, TafsCloud.ReportOffer(trace_id: "tr_1", at: "2026-11-06T09:59:30.250Z"))
        XCTAssertNil(offered.reported_at)
        let reported = try page(#","reported_at":"2026-11-06T11:00:00Z""#).entries[0]
        XCTAssertNil(reported.report)
        XCTAssertEqual(reported.reported_at, "2026-11-06T11:00:00Z")
        let plain = try page("").entries[0]
        XCTAssertNil(plain.report)
        XCTAssertNil(plain.reported_at)
    }

    func testTheTimeIsReadWithOrWithoutFractionsOfASecond() {
        XCTAssertNotNil(TafsCloud.isoDate("2026-11-06T09:59:30.250Z"))
        XCTAssertNotNil(TafsCloud.isoDate("2026-11-06T09:59:30Z"))
        XCTAssertNil(TafsCloud.isoDate("yesterday"))
    }

    func testTheButtonShowsOnlyWhereOfferedAndNotReported() {
        var entry = RecordEntry(tafID: UUID(), kind: .done, title: "Pay", detail: "", at: Date())
        XCTAssertFalse(ReceiptView.offersReport(entry), "no offer from tafs cloud: no button")
        entry.reportTraceID = "tr_1"
        XCTAssertTrue(ReceiptView.offersReport(entry))
        XCTAssertFalse(ReceiptView.offersReport(entry, reportedNow: true), "reported just now: the button goes")
        entry.reportedAt = Date()
        XCTAssertFalse(ReceiptView.offersReport(entry), "already reported: no button")
    }
}
