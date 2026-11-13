import XCTest
@testable import Tafs

/// tafs cloud's mark of what an approval's fingerprint covers (`signs`, docs/secure-connection.md):
/// a Card approved next is drawn under its words and dimmed, never as if signed now.
@MainActor
final class SignsMarkTests: XCTestCase {
    func testTheMarkReadsAsSent() throws {
        let now = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","draft":"dr_1","signs":{"card":true}}"#)
        XCTAssertEqual(now.signs?.card, true)
        XCTAssertFalse(now.signs?.cardNext ?? true)
        let next = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","draft":"dr_1","signs":{"card":false,"words":"Its Card: you approve it next"}}"#)
        XCTAssertTrue(next.signs?.cardNext ?? false)
        XCTAssertEqual(next.signs?.words, "Its Card: you approve it next")
        XCTAssertNil(try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1"}"#).signs, "a tafs cloud before it: as before")
        // Another shape is read as nothing said, never the card unread.
        let odd = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","signs":{"card":"no","words":" "}}"#)
        XCTAssertNil(odd.signs?.card)
        XCTAssertNil(odd.signs?.words)
        XCTAssertFalse(odd.signs?.cardNext ?? false)
    }

    /// Setup's thread is read with this phone's signed read of its connections and the steps it signs, so tafs
    /// cloud can mark its cards; without the proof, it's read as before.
    func testTheThreadIsReadWithTheProofAndWhatThePhoneSigns() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        StubProtocol.responses["GET /v1/tafs/setup_1/conversation?can=tools_and_cards,key_and_cards"] = (200, """
        {"messages":[],"components":[{"at":"2026-11-13T23:00:00.000Z","session":"s1","run":"r1","seq":1,"key":"k1","component":{"type":"approval","id":"a1","grant":"g_1","draft":"dr_1","signs":{"card":false,"words":"Its Card: you approve it next"}}}]}
        """)
        let thread = try await cloud.setupThread("setup_1", proof: "t=T,v1=sig,d=dev", can: AppModel.stepsSignedHere)
        let sent = try XCTUnwrap(StubProtocol.sent.last)
        XCTAssertEqual(sent.value(forHTTPHeaderField: "Planque-Device-Signature"), "t=T,v1=sig,d=dev")
        XCTAssertEqual(sent.url?.query, "can=tools_and_cards,key_and_cards")
        XCTAssertEqual(thread.components.first?.component.signs?.cardNext, true)

        StubProtocol.responses["GET /v1/tafs/setup_1/conversation"] = (200, #"{"messages":[]}"#)
        _ = try await cloud.setupThread("setup_1", can: AppModel.stepsSignedHere)
        let plain = try XCTUnwrap(StubProtocol.sent.last)
        XCTAssertNil(plain.url?.query, "no proof, no steps: read as before")
        XCTAssertNil(plain.value(forHTTPHeaderField: "Planque-Device-Signature"))
    }

    /// Next: its words and dimmed lines, never the Card's own title as if signed. Next with no words: not drawn.
    func testTheCardIsDrawnByTheMark() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let chat = try String(contentsOf: views.appendingPathComponent("SetupChatView.swift"), encoding: .utf8)
        XCTAssertTrue(chat.contains("if let draft, draft.cards != nil, !(component.signs?.cardNext == true && component.signs?.words == nil) {"))
        XCTAssertTrue(chat.contains("if next, let words = component.signs?.words {"))
        XCTAssertTrue(chat.contains(".foregroundColor(next ? Theme.dim : Theme.ink)"))
    }
}
