import XCTest
@testable import Tafs

/// Connect apps' rows, from the same tafs cloud fields as Android's (docs/ios-app.md): the person's
/// own first, then the rest; each says how it connects, whose it is, where it stands and which
/// tafs use it; a connected app opens its detail, and the rest have a compact Connect.
@MainActor
final class ConnectRowTests: XCTestCase {
    private func app(_ json: String) throws -> TafsCloud.ConnectApps.App {
        try JSONDecoder().decode(TafsCloud.ConnectApps.App.self, from: Data(json.utf8))
    }

    /// tafs cloud's new fields read as sent, and a field that isn't words reads as none.
    func testTheRowReadsTafsCloudsFields() throws {
        let sheets = try app(#"{"entry":"sheets","title":"Google Sheets","how":"oauth","state":"connected","line":"Spreadsheets in your Google account.","whose":"Yours","used_by":[{"taf":"t1","name":"Invoices"},{"taf":"t2","name":"Leads"}]}"#)
        XCTAssertEqual(sheets.line, "Spreadsheets in your Google account.")
        XCTAssertEqual(sheets.whose, "Yours")
        XCTAssertEqual(sheets.usedBy, ["Invoices", "Leads"])
        let bare = try app(#"{"entry":"stripe","title":"Stripe","how":"key","line":" ","whose":7,"used_by":"x"}"#)
        XCTAssertNil(bare.line)
        XCTAssertNil(bare.whose)
        XCTAssertNil(bare.usedBy)
        XCTAssertEqual(bare.state, "available", "the rest still reads")
    }

    /// Connected: how it connects, whose, Connected, and who uses it. To connect: how it connects
    /// alone. With no `used_by` yet, this phone's own read names the tafs.
    func testWhatEachRowSays() throws {
        let sheets = try app(#"{"entry":"sheets","title":"Google Sheets","how":"oauth","state":"connected","whose":"Yours","used_by":[{"taf":"t1","name":"Invoices"}]}"#)
        let said = ConnectRow(sheets, usedHere: ["Ignored"])
        XCTAssertEqual(said.how, Words.Connect.How.signIn)
        XCTAssertEqual(said.status, Words.Connect.Status.connected)
        XCTAssertEqual(said.usedBy, Words.Connect.Apps.usedBy(tafs: "Invoices"), "tafs cloud's names win")
        XCTAssertEqual(said.second, [Words.Connect.How.signIn, "Yours", Words.Connect.Status.connected, Words.Connect.Apps.usedBy(tafs: "Invoices")].joined(separator: " · "))

        let stripe = try app(#"{"entry":"stripe","title":"Stripe","how":"key"}"#)
        let fresh = ConnectRow(stripe, usedHere: ["Refunds"])
        XCTAssertEqual(fresh.second, Words.Connect.How.key, "how it connects alone, and no one uses what isn't connected")

        let gmail = try app(#"{"entry":"gmail","title":"Gmail","how":"oauth","state":"connected"}"#)
        XCTAssertEqual(ConnectRow(gmail, usedHere: ["Leads"]).usedBy, Words.Connect.Apps.usedBy(tafs: "Leads"), "this phone's read, until tafs cloud sends it")
        let out = try app(#"{"entry":"gmail","title":"Gmail","state":"needs_reconnect"}"#)
        XCTAssertEqual(ConnectRow(out).status, Words.Connect.signInAgain)
    }

    /// The person's own first, connected or to sign in again, then the rest: each in tafs cloud's order.
    func testYoursComeFirstInTafsCloudsOrder() throws {
        let list = try [#"{"entry":"a","title":"A"}"#, #"{"entry":"b","title":"B","state":"connected"}"#,
                        #"{"entry":"c","title":"C","state":"needs_reconnect"}"#, #"{"entry":"d","title":"D"}"#].map(app)
        let parts = ConnectRow.split(list)
        XCTAssertEqual(parts.yours.map(\.entry), ["b", "c"])
        XCTAssertEqual(parts.discover.map(\.entry), ["a", "d"])

        // A connected app opens its detail; the button stays as wide as its words; iPad is a table.
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let source = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertTrue(source.contains("NavigationLink { AppConnectionsView(app: app) } label: { cells(app, said) }"))
        XCTAssertTrue(source.contains("private var table: Bool { sizeClass == .regular }"))
        XCTAssertTrue(source.contains("} header: { Text(Words.Connect.Apps.yours) }"))
        XCTAssertTrue(source.contains("} header: { Text(Words.Connect.Apps.discover) }"))
    }
}
