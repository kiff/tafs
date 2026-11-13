import XCTest
@testable import Tafs

/// A taf's own Connect again (docs/api.md): tafs cloud's `connect_again` on its Setup page signs that
/// same connection in again from the taf, never a trip through every app, and says `done_words` after.
@MainActor
final class ConnectAgainTests: XCTestCase {
    private func again(_ json: String) throws -> TafsCloud.ConnectAgain {
        try KIFFClient.decoder.decode(TafsCloud.ConnectAgain.self, from: Data(json.utf8))
    }

    func testItReadsAsSentAndLenientlyElse() throws {
        let read = try again(#"{"grant":"grt_1","app":"Gmail","title":"Connect again","words":"Gmail's sign-in has expired, so Rae can't use it. Connect again to sign in.","done_words":"Gmail is connected again. Rae can use it."}"#)
        XCTAssertTrue(read.usable)
        XCTAssertEqual(read.grant, "grt_1")
        XCTAssertEqual(read.doneWords, "Gmail is connected again. Rae can use it.")
        XCTAssertFalse(try again(#"{"app":"Gmail","title":"Connect again","words":"w"}"#).usable, "no connection named")
        XCTAssertFalse(try again(#"{"grant":"grt_1","title":" ","words":"w"}"#).usable, "no button words")
        XCTAssertFalse(try again(#"{"grant":7,"title":"t","words":"w"}"#).usable, "another shape: none")
        // The page reads with or without it, and one of another shape doesn't stop it.
        let page = #"{"connections":[],"workflow":{"title":"t","summary":"s","steps":[]},"memory":{"count":0,"title":"m"},"versions":{"title":"v","summary":"s","earlier":0},"connections_error":null"#
        XCTAssertNil(try KIFFClient.decoder.decode(TafsCloud.SetupView.self, from: Data((page + "}").utf8)).connect_again)
        XCTAssertEqual(try KIFFClient.decoder.decode(TafsCloud.SetupView.self, from: Data((page + #","connect_again":{"grant":"grt_1","title":"Connect again","words":"w"}}"#).utf8)).connect_again?.grant, "grt_1")
        XCTAssertFalse(try KIFFClient.decoder.decode(TafsCloud.SetupView.self, from: Data((page + #","connect_again":"x"}"#).utf8)).connect_again?.usable ?? false)
    }

    /// On the taf's Setup, first, for whoever manages it: that connection signed in again here, and the
    /// taf read again; tafs cloud's done words once planque says connected.
    func testTheTafSignsThatConnectionInAgainItself() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/TafDetailView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("if let again = model.tafSetups[taf.id]?.connect_again, again.usable, manages {"))
        XCTAssertTrue(view.contains("Task { await model.connectAgain(tafID, again) }"))
        XCTAssertFalse(view.contains("ConnectAppsView()") && view.contains("connect_again") && view.contains("NavigationLink { ConnectAppsView() } label: { Text(again"), "never a trip through every app")
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let start = try XCTUnwrap(app.range(of: "func connectAgain(_ tafID: UUID, _ again: TafsCloud.ConnectAgain) async {"))
        let body = String(app[start.upperBound...].prefix(420))
        func at(_ s: String) throws -> String.Index { try XCTUnwrap(body.range(of: s), s).lowerBound }
        XCTAssertTrue(try at("await reconnectApp(again.grant)") < (try at("await loadTafSetup(tafID)")), "that same connection, then the taf read again")
        XCTAssertTrue(body.contains("if connected, !again.doneWords.isEmpty { connectedAgain[tafID] = again.doneWords }"), "done words only once planque says connected")
    }
}
