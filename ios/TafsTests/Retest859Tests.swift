import XCTest
@testable import Tafs

/// What the owner's device test of TestFlight 859 found (27 Oct), checked here where it can
/// be without a device. The blank chat at a run's end is ChatPinUITests'.
@MainActor
final class Retest859Tests: XCTestCase {
    private func taf(_ name: String) -> Taf {
        Taf(name: name, job: "Refunds", tools: [], colorIndex: 0, card: Card(rules: [], issuedBy: "You"))
    }

    /// The iPad opened Refunds, the first taf made, while Refunds 4 led the list. The app
    /// opens on the list's first taf: the one a call waits on, else the one used last.
    func testTheAppOpensOnTheListsFirstTaf() {
        let a = taf("Refunds"), b = taf("Refunds 2"), c = taf("Refunds 4")
        let now = Date()
        let record = [RecordEntry(tafID: c.id, kind: .done, title: "Refund", detail: "", at: now.addingTimeInterval(-60)),
                      RecordEntry(tafID: a.id, kind: .done, title: "Refund", detail: "", at: now.addingTimeInterval(-3600))]
        let listed = AppModel.byRecent([a, b, c], record: record, held: [], chats: [:])
        XCTAssertEqual(HomeSplitView.opening(listed, screenshot: false), .taf(c.id), "the one used last, not the first made")

        let waiting = HeldCall(tafID: b.id, proposed: ProposedAction(action: "refund", summary: "Refund"), reason: "Over",
                               heldAt: now, expiresAt: now.addingTimeInterval(600))
        let held = AppModel.byRecent([a, b, c], record: record, held: [waiting], chats: [:])
        XCTAssertEqual(HomeSplitView.opening(held, screenshot: false), .taf(b.id), "the one a call waits on")

        XCTAssertEqual(HomeSplitView.opening(listed, screenshot: true), .taf(a.id), "CI's screenshots open Refunds")
        XCTAssertNil(HomeSplitView.opening([], screenshot: false))
    }
}
