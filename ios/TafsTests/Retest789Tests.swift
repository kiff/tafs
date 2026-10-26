import XCTest
@testable import Tafs

/// What the owner's device test of TestFlight 789 (26 Oct, iPad) found, checked here where it
/// can be without a device.
@MainActor
final class Retest789Tests: XCTestCase {
    private func taf(_ name: String) -> Taf {
        Taf(name: name, job: "Refunds", tools: [], colorIndex: 0, card: Card(rules: [], issuedBy: "You"))
    }

    /// The list moved while the owner looked at it: Refunds 3 went 4th, 2nd, then 4th again.
    /// It keeps the order it was settled in; a new taf comes after, as it would be listed.
    func testTheListKeepsItsSettledOrder() {
        let a = taf("Refunds"), b = taf("Refunds 2"), c = taf("Refunds 3"), d = taf("Chargebacks")
        let settled = [c.id, a.id, b.id]
        XCTAssertEqual(AppModel.inOrder([a, b, c], settled, fallback: [b, a, c]).map(\.name), ["Refunds 3", "Refunds", "Refunds 2"],
                       "a reload that would list them otherwise doesn't move them")
        XCTAssertEqual(AppModel.inOrder([a, b, c, d], settled, fallback: [d, b, a, c]).map(\.name),
                       ["Refunds 3", "Refunds", "Refunds 2", "Chargebacks"])
        XCTAssertEqual(AppModel.inOrder([a, c], settled, fallback: [a, c]).map(\.name), ["Refunds 3", "Refunds"], "a taf that's gone drops out")
        XCTAssertEqual(AppModel.inOrder([a, b], [], fallback: [b, a]).map(\.name), ["Refunds 2", "Refunds"], "nothing settled yet")
    }

    /// The hold card sat right under the owner's message, above the taf's words asking for
    /// the approval. It comes after them.
    func testAHeldCardComesAfterTheWordsThatAskForIt() {
        let t = Date(timeIntervalSince1970: 1_000)
        let asked = TafsCloud.ChatMessage(at: t, from: .owner, text: "Refund $60 on T-3103", session: "s")
        let looked = TafsCloud.ChatMessage(at: t.addingTimeInterval(5), from: .taf, text: "It was paid $100.", session: "s")
        let waiting = TafsCloud.ChatMessage(at: t.addingTimeInterval(9), from: .taf, text: "Waiting for your approval.", session: "s2")
        let next = TafsCloud.ChatMessage(at: t.addingTimeInterval(60), from: .owner, text: "ok", session: "s")
        let call = HeldCall(tafID: UUID(), proposed: ProposedAction(action: "refund", summary: "Refund"), reason: "Over",
                            heldAt: t.addingTimeInterval(4), expiresAt: t.addingTimeInterval(600))
        let items: [AppModel.ChatItem] = [.message(asked), .held(call), .message(looked), .message(waiting), .message(next)]
        XCTAssertEqual(AppModel.heldAfterItsWords(items).map(\.id),
                       [asked, looked, waiting].map { AppModel.ChatItem.message($0).id } + [AppModel.ChatItem.held(call).id, AppModel.ChatItem.message(next).id],
                       "after the taf's words, before the owner's next message")

        // The words still streaming in go above it too.
        let streaming = AppModel.ChatItem.streaming(id: "live|0", waiting)
        XCTAssertEqual(AppModel.heldAfterItsWords([.message(asked), .held(call), streaming]).map(\.id),
                       [AppModel.ChatItem.message(asked).id, streaming.id, AppModel.ChatItem.held(call).id])

        // An answered one goes where it was answered; it isn't moved.
        var entry = RecordEntry(tafID: call.tafID, kind: .approved, title: "Refund", detail: "", at: t.addingTimeInterval(30))
        entry.answer = KIFFClient.ActivityCall.Answer(decision: "approved", at: t.addingTimeInterval(20))
        let answered: [AppModel.ChatItem] = [.message(asked), .outcome(entry), .message(looked)]
        XCTAssertEqual(AppModel.heldAfterItsWords(answered).map(\.id), answered.map(\.id))
    }

    /// Approving or refusing read "KIFF didn't take the answer: self_review". KIFF's reasons
    /// read as words, and only those that stay so take the card's buttons away.
    func testKIFFsRefusalsReadAsWords() {
        let selfReview = KIFFClient.KIFFError.server(status: 403, message: "self_review")
        XCTAssertEqual(AppModel.answerRefusedWords(selfReview), "You asked for this, so someone else on the account answers it.")
        XCTAssertEqual(AppModel.lastingRefusal(selfReview), "self_review")
        XCTAssertEqual(AppModel.lastingRefusal(KIFFClient.KIFFError.server(status: 403, message: "Forbidden", reason: "card_changer")), "card_changer")

        let later = KIFFClient.KIFFError.server(status: 503, message: "hold_check_unavailable")
        XCTAssertEqual(AppModel.answerRefusedWords(later), "KIFF couldn't check who may answer just now. Try again.")
        XCTAssertNil(AppModel.lastingRefusal(later), "a moment later it may take the answer")

        let other = KIFFClient.KIFFError.server(status: 500, message: "boom")
        XCTAssertEqual(AppModel.answerRefusedWords(other), "KIFF didn't take the answer: boom")
        XCTAssertNil(AppModel.lastingRefusal(other))
        XCTAssertNil(AppModel.lastingRefusal(URLError(.notConnectedToInternet)))
    }

    /// Settings and People & groups named the account "user_3GzdltyJNNvxxy8nNQJudOwgzl9".
    /// A person's own account without a name reads as their email.
    func testAnAccountWithoutANameReadsAsTheEmail() throws {
        func account(_ json: String) throws -> KIFFClient.Account {
            try KIFFClient.decoder.decode(KIFFClient.Account.self, from: Data(json.utf8))
        }
        let own = try account(#"{"tenant_id":"t1","tenant_slug":"user_3GzdltyJNNvxxy8nNQJudOwgzl9","email":"owner@example.com"}"#)
        XCTAssertEqual(own.name, "owner@example.com")
        let named = try account(#"{"tenant_id":"t1","tenant_slug":"user_3Gz","tenant_display_name":"Acme","email":"owner@example.com"}"#)
        XCTAssertEqual(named.name, "Acme")
        let slug = try account(#"{"tenant_id":"t2","tenant_slug":"acme","email":"owner@example.com"}"#)
        XCTAssertEqual(slug.name, "acme")
        let noEmail = try account(#"{"tenant_id":"t1","tenant_slug":"user_3Gz"}"#)
        XCTAssertEqual(noEmail.name, "user_3Gz")
    }
}
