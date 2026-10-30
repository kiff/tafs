import XCTest
@testable import Tafs

/// The one rule both chats keep what the owner sent by (kiff/tafs#138, item 48).
@MainActor
final class SentMessagesTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    private func owner(_ text: String, _ at: Date, _ session: String = "pending") -> TafsCloud.ChatMessage {
        TafsCloud.ChatMessage(at: at, from: .owner, text: text, session: session)
    }

    func testASentMessageWaitsUntilTheThreadHasIt() {
        let sent = [owner("Refund pi_1", t0)]
        let before = SentMessages.merge([], sent: sent, now: t0.addingTimeInterval(5))
        XCTAssertEqual(before.messages.map(\.text), ["Refund pi_1"])
        XCTAssertEqual(before.waiting.count, 1)

        let after = SentMessages.merge([owner("Refund pi_1", t0.addingTimeInterval(1), "s1")], sent: sent, now: t0.addingTimeInterval(6))
        XCTAssertEqual(after.messages.map(\.session), ["s1"], "the thread's copy, once")
        XCTAssertTrue(after.waiting.isEmpty)
    }

    func testTwoIdenticalStayTwoUntilTheThreadHasBoth() {
        let sent = [owner("Sí", t0), owner("Sí", t0.addingTimeInterval(1))]
        let one = SentMessages.merge([owner("Sí", t0.addingTimeInterval(2), "s1")], sent: sent, now: t0.addingTimeInterval(3))
        XCTAssertEqual(one.messages.filter { $0.text == "Sí" }.count, 2)
        XCTAssertEqual(one.waiting.count, 1)
    }

    func testAMessageTheThreadNeverGetsGoesAfterAWhile() {
        let gone = SentMessages.merge([], sent: [owner("Hola", t0)], now: t0.addingTimeInterval(SentMessages.kept + 1))
        XCTAssertTrue(gone.messages.isEmpty)
        XCTAssertTrue(gone.waiting.isEmpty)
    }

    func testASentMessageIsStampedAfterTheThreadsLatest() {
        let ahead = t0.addingTimeInterval(60)
        XCTAssertGreaterThan(SentMessages.stamp(after: ahead, now: t0), ahead)
        XCTAssertEqual(SentMessages.stamp(after: nil, now: t0), t0)
        XCTAssertEqual(SentMessages.stamp(after: t0.addingTimeInterval(-5), now: t0), t0)
    }

    /// A taf's message keeps its id while its words grow, so it isn't shown as a new one
    /// and revealed again; an owner's two identical messages keep two ids.
    func testATafsMessageKeepsItsIdAsItGrows() {
        let first = TafsCloud.ChatMessage(at: t0, from: .taf, text: "I'd lower the limit to 40 €.", session: "s1")
        let grown = TafsCloud.ChatMessage(at: t0, from: .taf, text: "I'd lower the limit to 40 €. I'll set the new version up as soon as you say yes.", session: "s1")
        XCTAssertEqual(first.id, grown.id)
        XCTAssertNotEqual(owner("Sí", t0, "s1").id, owner("No", t0, "s1").id)
    }

    /// With tafs cloud's id for it, a sent message is found by that id alone: an earlier "Sí"
    /// in the thread, under another id, isn't it; its own copy is, whatever words came back.
    func testASentMessageWithTafsCloudsIdIsFoundByItsIdNeverByItsWords() {
        var sent = owner("Sí", t0)
        sent.serverID = "run_2.in"
        var earlier = owner("Sí", t0.addingTimeInterval(1), "s1")
        earlier.serverID = "run_1.in"
        let waits = SentMessages.merge([earlier], sent: [sent], now: t0.addingTimeInterval(5))
        XCTAssertEqual(waits.waiting, [sent], "the other Sí isn't it")
        XCTAssertEqual(waits.messages.count, 2)
        var own = owner("Sí, gracias", t0.addingTimeInterval(2), "s1")
        own.serverID = "run_2.in"
        XCTAssertTrue(SentMessages.merge([earlier, own], sent: [sent], now: t0.addingTimeInterval(5)).waiting.isEmpty)
    }

    /// The thread's messages, the send's answer and the stream's first event carry tafs cloud's id.
    func testTheIdsReadAsTafsCloudSendsThem() throws {
        let m = try KIFFClient.decoder.decode(TafsCloud.ChatMessage.self, from: Data(#"{"id":"run_2.in","at":"2026-10-28T09:00:00Z","from":"owner","text":"Use live","session":"s1","answers":"limits"}"#.utf8))
        XCTAssertEqual(m.serverID, "run_2.in")
        XCTAssertEqual(m.answers, "limits")
        let sent = try KIFFClient.decoder.decode(TafsCloud.Sent.self, from: Data(#"{"session":"s1","new_session":false,"run":"run_2","message":{"id":"run_2.in","at":"2026-10-28T09:00:00Z"}}"#.utf8))
        XCTAssertEqual(sent.message?.id, "run_2.in")
        let older = try KIFFClient.decoder.decode(TafsCloud.Sent.self, from: Data(#"{"session":"s1","new_session":false}"#.utf8))
        XCTAssertNil(older.message)
    }
}
