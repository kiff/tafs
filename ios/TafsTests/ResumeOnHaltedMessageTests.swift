import XCTest
@testable import Tafs

/// A message refused because of a halt offers Resume on its bubble: tafs cloud says whether the
/// account or the taf is halted, and the bubble opens that Halt screen, where resuming is the
/// person's own act behind Face ID.
@MainActor
final class ResumeOnHaltedMessageTests: XCTestCase {
    private func refusal(_ body: String, status: Int = 409) -> TafsCloud.CloudError {
        TafsCloud.refusal(status, Data(body.utf8))
    }

    /// tafs cloud's refusal says what is halted; anything else it might say there is not a halt.
    func testTheRefusalSaysWhatIsHalted() {
        let account = refusal(#"{"error":"halted","code":"halted","words":"Votre compte est en pause. Reprenez-le depuis Halte.","halted":"account"}"#)
        XCTAssertEqual(account.halted, "account")
        XCTAssertEqual(account.errorDescription, "Votre compte est en pause. Reprenez-le depuis Halte.", "its words, in the person's language")
        XCTAssertEqual(refusal(#"{"error":"halted","code":"halted","words":"Refunds is halted.","halted":"taf"}"#).halted, "taf")
        XCTAssertNil(refusal(#"{"error":"halted","code":"halted","words":"Halted."}"#).halted, "an older answer names neither")
        XCTAssertNil(refusal(#"{"error":"no","code":"halted","halted":"something_new"}"#).halted, "a value this app doesn't know")
        XCTAssertNil(refusal(#"{"error":"no credits","code":"credits_used_up"}"#).halted)
        XCTAssertNil(refusal("not json").halted)
    }

    /// Only tafs cloud's refusal carries a halt: any other failure of a send is not one.
    func testOnlyTheServicesRefusalIsAHalt() {
        XCTAssertEqual(AppModel.haltOf(refusal(#"{"error":"halted","halted":"account"}"#)), "account")
        XCTAssertNil(AppModel.haltOf(URLError(.notConnectedToInternet)))
        XCTAssertNil(AppModel.haltOf(refusal(#"{"error":"busy"}"#, status: 429)))
    }

    /// Which Halt screen Resume opens: the account's for a halted account, in Coach's chat too;
    /// the taf's own for a halted taf; none when it isn't a halt, or names a taf the chat doesn't have.
    func testResumeOpensTheHaltThatRefusedIt() {
        let taf = UUID()
        func unsent(_ halted: String?, why: String = "words") -> AppModel.UnsentMessage {
            AppModel.UnsentMessage(text: "Refund order 42", why: why, at: Date(), halted: halted)
        }
        let account = UnsentBubble.halt(unsent("account"), taf: taf)
        XCTAssertEqual(account?.account, true)
        XCTAssertNil(account?.screen, "the account's Halt, even from a taf's chat")
        XCTAssertEqual(UnsentBubble.halt(unsent("account"), taf: nil)?.account, true, "Coach's chat has no taf, and the account's halt refuses it")
        let own = UnsentBubble.halt(unsent("taf"), taf: taf)
        XCTAssertEqual(own?.account, false)
        XCTAssertEqual(own?.screen, taf)
        XCTAssertNil(UnsentBubble.halt(unsent("taf"), taf: nil), "a taf's halt with no taf to open")
        XCTAssertNil(UnsentBubble.halt(unsent(nil), taf: taf), "not refused for a halt")
        XCTAssertNil(UnsentBubble.halt(unsent(nil, why: "Votre compte est bloqué"), taf: taf), "never guessed from the words, in any language")
    }

    /// Every send that keeps a message as not sent carries the halt its failure names, and the
    /// bubble never resumes by itself: Resume only opens Halt.
    func testEveryKeptMessageCarriesItsHaltAndTheBubbleOnlyOpensHalt() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        XCTAssertFalse(app.contains("keepUnsent(text, tafID, why: error.localizedDescription)"), "a send's failure kept without what it says of a halt")
        XCTAssertFalse(app.contains("why: error.localizedDescription, at: clock()))"), "a Coach send's failure kept without what it says of a halt")
        XCTAssertEqual(app.components(separatedBy: "halted: Self.haltOf(error)").count - 1, 4, "the taf's chat (twice) and Coach's (twice)")
        let chat = try String(contentsOf: root.appendingPathComponent("Views/SetupChatView.swift"), encoding: .utf8)
        let start = try XCTUnwrap(chat.range(of: "if let halt = Self.halt(message, taf: taf?.id) {"))
        let end = try XCTUnwrap(chat.range(of: "} else if let taf, Self.halted(message.why) {", range: start.upperBound..<chat.endIndex))
        let branch = String(chat[start.upperBound..<end.lowerBound])
        XCTAssertTrue(branch.contains("Button(Words.Taf.resume) { resuming = true }"), "Resume opens Halt")
        XCTAssertTrue(branch.contains("HaltScreen(tafID: halt.screen)"))
        XCTAssertFalse(branch.contains("model.resume") || branch.contains("resumeAll") || branch.contains("signer"), "nothing here resumes or signs")
        XCTAssertTrue(branch.contains("message.why"), "tafs cloud's words say which is halted")
    }
}
