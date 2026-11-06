import XCTest
@testable import Tafs

/// The late Stop after a hand-over (kiff/tafs#476): the same cases as Android's.
@MainActor
final class HandOverTests: XCTestCase {
    private func at(_ s: Int) -> Date { Date(timeIntervalSince1970: 1_800_000_000 + Double(s)) }

    func testAHandOverIsDoneOnceCoachSpeaksAfterTheAnswerAndTheOwnerHasNotWrittenSince() throws {
        var result = ShownComponent(firstAt: at(10), session: "s1", run: "r1",
                                    component: try SetupTests.component(#"{"type":"result","id":"res"}"#))
        result.answer = TafsCloud.CardAnswer(words: "Handed over", at: at(20))
        let asked = TafsCloud.ChatMessage(at: at(0), from: .owner, text: "Set up Refunds", session: "s1")
        let closing = TafsCloud.ChatMessage(at: at(25), from: .taf, text: "Refunds is set up.", session: "s1")
        func thread(_ messages: [TafsCloud.ChatMessage], _ shown: ShownComponent) -> SetupThread {
            var t = SetupThread(); t.messages = messages; t.components = [shown]; return t
        }
        XCTAssertTrue(thread([asked, closing], result).handedOver(session: "s1"))
        XCTAssertTrue(thread([asked, closing], result).handedOver(session: nil), "before the stream named its session")
        // Coach hasn't spoken since the answer: still at work.
        XCTAssertFalse(thread([asked], result).handedOver(session: "s1"))
        // The owner wrote again: that's a new run.
        XCTAssertFalse(thread([asked, closing, TafsCloud.ChatMessage(at: at(30), from: .owner, text: "Now Chargebacks", session: "s1")], result).handedOver(session: "s1"))
        // Not answered yet, or another conversation's result.
        var open = result; open.answer = nil
        XCTAssertFalse(thread([asked, closing], open).handedOver(session: "s1"))
        XCTAssertFalse(thread([asked, closing], result).handedOver(session: "s2"))
        // Coach's words in another session don't count.
        let elsewhere = TafsCloud.ChatMessage(at: at(25), from: .taf, text: "Refunds is set up.", session: "s9")
        XCTAssertFalse(thread([asked, elsewhere], result).handedOver(session: nil))
    }
}
