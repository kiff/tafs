import XCTest
@testable import Tafs

/// What the owner's device test of TestFlight 793 (26 Oct, iPad) found, checked here where it
/// can be without a device.
@MainActor
final class Retest793Tests: XCTestCase {
    /// The hold card showed under the owner's message with no words, and the taf's words came
    /// about 30 s later, above it. It waits for those words, at most `heldWaitsForWords`.
    func testAHeldCardWaitsForTheTafsWords() {
        let t = Date(timeIntervalSince1970: 1_000)
        let asked = TafsCloud.ChatMessage(at: t, from: .owner, text: "Refund $70 of T-3004", session: "s")
        let call = HeldCall(tafID: UUID(), proposed: ProposedAction(action: "refund", summary: "Refund"), reason: "Over",
                            heldAt: t.addingTimeInterval(4), expiresAt: t.addingTimeInterval(600))
        let card = AppModel.ChatItem.held(call)
        let alone: [AppModel.ChatItem] = [.message(asked), card]

        XCTAssertEqual(AppModel.withheldUntilWords(alone, now: t.addingTimeInterval(10)).map(\.id), [AppModel.ChatItem.message(asked).id],
                       "no words yet: the card waits")
        XCTAssertEqual(AppModel.withheldUntilWords(alone, now: t.addingTimeInterval(4 + AppModel.heldWaitsForWords)).map(\.id),
                       alone.map(\.id), "the wait is over: it shows, words or not")

        let words = TafsCloud.ChatMessage(at: t.addingTimeInterval(30), from: .taf, text: "It's over my $50 limit.", session: "s")
        let said: [AppModel.ChatItem] = [.message(asked), .message(words), card]
        XCTAssertEqual(AppModel.withheldUntilWords(said, now: t.addingTimeInterval(31)).map(\.id), said.map(\.id), "the words came: it shows")

        let streaming: [AppModel.ChatItem] = [.message(asked), .streaming(id: "live|0", words), card]
        XCTAssertEqual(AppModel.withheldUntilWords(streaming, now: t.addingTimeInterval(10)).map(\.id), streaming.map(\.id),
                       "words streaming in count too")

        // A call held before the owner's last message isn't this turn's: it stays.
        let earlier = HeldCall(tafID: call.tafID, proposed: ProposedAction(action: "refund", summary: "Refund"), reason: "Over",
                               heldAt: t.addingTimeInterval(-60), expiresAt: t.addingTimeInterval(600))
        let before: [AppModel.ChatItem] = [.held(earlier), .message(asked)]
        XCTAssertEqual(AppModel.withheldUntilWords(before, now: t.addingTimeInterval(10)).map(\.id), before.map(\.id))
    }
}
