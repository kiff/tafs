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

    /// planque can't sign the connection in again on its old account (`reconnect: fresh`): tafs cloud says `fresh`, and so
    /// does planque's grant. Either way the first step is connect-fresh, never an approve planque would refuse.
    func testAFreshOneStartsANewSignInNeverAReconnectFirst() throws {
        XCTAssertTrue(try again(#"{"grant":"grt_1","title":"Connect again","words":"w","fresh":true}"#).fresh)
        XCTAssertFalse(try again(#"{"grant":"grt_1","title":"Connect again","words":"w"}"#).fresh)
        XCTAssertFalse(try again(#"{"grant":"grt_1","title":"Connect again","words":"w","fresh":"yes"}"#).fresh, "another shape: not fresh")
        let page = #"{"connections":[],"workflow":{"title":"t","summary":"s","steps":[]},"memory":{"count":0,"title":"m"},"versions":{"title":"v","summary":"s","earlier":0},"connections_error":null"#
        let named = try KIFFClient.decoder.decode(TafsCloud.SetupView.self, from: Data((page + #","connected_again":{"grant":"grt_1","words":"Gmail is connected again as ana@example.com."}}"#).utf8))
        XCTAssertEqual(named.connected_again, TafsCloud.ConnectedAgain(grant: "grt_1", words: "Gmail is connected again as ana@example.com."))
        XCTAssertEqual(try KIFFClient.decoder.decode(TafsCloud.SetupView.self, from: Data((page + #","connected_again":7}"#).utf8)).connected_again?.words ?? "", "", "another shape: no words")
        let grant = #"{"id":"g1","status":"needs_reconnect","text":"","digest":"d","request":{"agent":"rae","server":"https://gmail.example/mcp","tools":[]},"events":[]"#
        XCTAssertTrue(try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data((grant + #","reconnect":"fresh"}"#).utf8)).reconnectsFresh)
        XCTAssertFalse(try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data((grant + #","reconnect":"same"}"#).utf8)).reconnectsFresh)
        XCTAssertFalse(try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data((grant + "}").utf8)).reconnectsFresh)
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let client = try String(contentsOf: root.appendingPathComponent("KIFF/PlanqueClient.swift"), encoding: .utf8)
        XCTAssertTrue(client.contains(#"try await send("POST", "grants/\(escaped(grant.id))/connect-fresh", body: try Self.approveBody(grant, words: words))"#))
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        XCTAssertTrue(app.contains("if fresh || grant.reconnectsFresh { return try await planque.connectFresh(grant, words: words) }"))
        XCTAssertTrue(app.contains("return TafsCloud.ConnectStart(grant: grant.id, again: true, fresh: fresh || grant.reconnectsFresh)"))
        XCTAssertTrue(app.contains("let done = await connect(grantID: grantID, shown: shown, again: true, fresh: fresh)"))
    }

    /// On the taf's Setup, first, for whoever manages it: that connection signed in again here, and the
    /// taf read again; tafs cloud's done words once planque says connected.
    func testTheTafSignsThatConnectionInAgainItself() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/TafDetailView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("if let again = model.tafSetups[taf.id]?.connect_again, again.usable, manages {"))
        // Its sheet first (docs/connect-apps.md), on that same connection; the taf is read again once it's signed in again.
        XCTAssertTrue(view.contains("Task { reapproving = await model.reconnectStart(id: again.grant, fresh: again.fresh) }"))
        XCTAssertTrue(view.contains(".reapprovalSheet($reapproving) { await model.afterConnectAgain(tafID, again) }"))
        let panel = String(view[try XCTUnwrap(view.range(of: "struct ConnectAgainPanel: View {")).upperBound...])
        XCTAssertFalse(panel.contains("ConnectAppsView"), "never a trip through every app")
        let app = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let start = try XCTUnwrap(app.range(of: "func afterConnectAgain(_ tafID: UUID, _ again: TafsCloud.ConnectAgain) async {"))
        let body = String(app[start.upperBound...].prefix(1000))
        XCTAssertTrue(body.contains("if connected, let words = named ?? (again.doneWords.isEmpty ? nil : again.doneWords) { connectedAgain[tafID] = words }"), "done words only once planque says connected")
        // After a fresh sign-in, tafs cloud's words naming the account it went to, when it sent them for that grant.
        XCTAssertTrue(body.contains("await loadTafSetup(tafID, connectedAgain: connected && again.fresh ? again.grant : nil)"))
    }
}
