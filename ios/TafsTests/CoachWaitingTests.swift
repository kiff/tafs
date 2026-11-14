import XCTest
@testable import Tafs

/// Coach waiting (docs/api.md › Coach waiting): a Coach chat paused on a card its person answers is
/// listed in Needs you in tafs cloud's words, and its way in opens that chat.
@MainActor
final class CoachWaitingTests: XCTestCase {
    private func home(_ waiting: String) throws -> TafsCloud.Home {
        let json = #"{"tafs":[],"others":[],"removing":[],"coach_waiting":\#(waiting),"needs_you":{"count":1,"words":"1 thing needs you"}}"#
        return try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(json.utf8))
    }

    /// Read leniently: one the app can't read, or without its chat or words, is left out, never Home with it.
    func testHomeListsEachWaitingChatAsSent() throws {
        let read = try home(#"""
        [{"taf":"taf-setup","session":"s1","run":"r1","since":"2026-11-13T23:34:46Z","words":{"line":"Coach is waiting for your approval.","open":"Open Coach"}},
         {"taf":"taf-setup","session":"s2","words":{"line":" ","open":"Open Coach"}},
         {"taf":"taf-setup","words":{"line":"Coach is waiting for your answer.","open":"Open Coach"}},
         7, {"session":"s3","words":"no"}]
        """#)
        let model = AppModel()
        model.show(read, [])
        XCTAssertEqual(model.coachWaiting.map(\.session), ["s1"])
        XCTAssertEqual(model.coachWaiting.first?.words.line, "Coach is waiting for your approval.")
        XCTAssertEqual(model.coachWaiting.first?.words.open, "Open Coach")
        XCTAssertEqual(model.needsYouCount, 1, "tafs cloud's count, which has it once")

        model.show(try home("[]"), [])
        XCTAssertTrue(model.coachWaiting.isEmpty, "answered: gone at the next read")
        XCTAssertNil(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(#"{"tafs":[],"others":[],"removing":[]}"#.utf8)).coachWaiting,
                     "a tafs cloud before it: none")
    }

    /// Needs you draws each with its line and way in, which opens Coach on that chat; with one waiting,
    /// it never says nothing is waiting.
    func testNeedsYouDrawsItAndOpensThatChat() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/NeedsYouView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("ForEach(model.coachWaiting) { waiting in CoachWaitingCard(waiting: waiting).id(waiting.id) }"))
        XCTAssertTrue(view.contains("&& model.coachWaiting.isEmpty {"), "not 'nothing waiting' while Coach waits")
        XCTAssertTrue(view.contains("Button(waiting.words.open) { Task { await model.openCoachWaiting(waiting) } }"))
        XCTAssertTrue(view.contains("if model.mayUseCoach {\n                Button(waiting.words.open)"), "no way in for someone who may not use Coach: the line alone")
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let start = try XCTUnwrap(app.range(of: "func openCoachWaiting(_ waiting: TafsCloud.CoachWaiting) async {"))
        let body = String(app[start.upperBound...].prefix(250))
        XCTAssertTrue(body.contains("guard mode == .kiff, mayUseCoach, !waiting.session.isEmpty else { return }"))
        XCTAssertTrue(body.contains("coachOpen = true"))
        XCTAssertTrue(body.contains("await openSetupChat(waiting.session)"))
    }
}
