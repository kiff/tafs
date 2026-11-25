import XCTest
import UIKit
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
        let sheets = try app(#"{"entry":"sheets","title":"Google Sheets","connected":true,"method":"sign_in","line":"Sign in · Connected · Used by Invoices, Leads","whose":"Yours","used_by":[{"taf":"t1","name":"Invoices"},{"taf":"t2","name":"Leads"}]}"#)
        XCTAssertTrue(sheets.connected, "tafs cloud's connected")
        XCTAssertFalse(sheets.takesKey)
        XCTAssertEqual(sheets.line, "Sign in · Connected · Used by Invoices, Leads")
        XCTAssertEqual(sheets.whose, "Yours")
        XCTAssertEqual(sheets.usedBy, ["Invoices", "Leads"])
        let bare = try app(#"{"entry":"stripe","title":"Stripe","how":"oauth","method":"key","line":" ","whose":7,"used_by":"x","connected":"yes"}"#)
        XCTAssertTrue(bare.takesKey, "method wins over how")
        XCTAssertFalse(bare.connected)
        XCTAssertNil(bare.line)
        XCTAssertNil(bare.whose)
        XCTAssertNil(bare.usedBy)
        XCTAssertEqual(bare.state, "available", "the rest still reads")
    }

    /// A row draws tafs cloud's `name`, else its `title`, and its `subtitle` under it when sent.
    func testTheRowDrawsTheNameAndItsSubtitle() throws {
        let qonto = try app(#"{"entry":"qonto@1","vendor":"Qonto","title":"Qonto invoicing: reads, and invoice drafts from quotes","name":"Qonto","subtitle":"Invoicing: reads, and invoice drafts from quotes"}"#)
        XCTAssertEqual(qonto.displayName, "Qonto")
        XCTAssertEqual(qonto.subtitle, "Invoicing: reads, and invoice drafts from quotes")
        XCTAssertEqual(qonto.title, "Qonto invoicing: reads, and invoice drafts from quotes", "title kept as sent")

        let gmail = try app(#"{"entry":"connector:gmail","vendor":"gmail","title":"Gmail","name":"Gmail"}"#)
        XCTAssertEqual(gmail.displayName, "Gmail")
        XCTAssertNil(gmail.subtitle, "a managed app keeps its name, with nothing under it")

        let older = try app(#"{"entry":"stripe@3","vendor":"Stripe","title":"Stripe"}"#)
        XCTAssertEqual(older.displayName, "Stripe", "no name sent: the title")
        XCTAssertNil(older.name)
        XCTAssertNil(older.subtitle)

        let blank = try app(#"{"entry":"stripe@3","title":"Stripe payments","name":"  ","subtitle":" ","how":"key"}"#)
        XCTAssertEqual(blank.displayName, "Stripe payments", "a blank name falls back to the title")
        XCTAssertNil(blank.subtitle, "a blank subtitle reads as none")
        let odd = try app(#"{"entry":"stripe@3","title":"Stripe","name":7,"subtitle":["x"]}"#)
        XCTAssertEqual(odd.displayName, "Stripe", "a name that isn't words reads as none")
        XCTAssertNil(odd.subtitle)
        XCTAssertTrue(odd.takesKey == false && odd.state == "available", "the rest still reads")

        // The list and the iPad table share `name(_:_:)`: it draws the name, and the subtitle under it.
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let source = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        let name = String(source[try XCTUnwrap(source.range(of: "private func name(_ app: TafsCloud.ConnectApps.App")).upperBound...].prefix(1600))
        let drawn = try XCTUnwrap(name.range(of: "Text(app.displayName)"))
        let under = try XCTUnwrap(name.range(of: "if let subtitle = app.subtitle {\n                    Text(subtitle)"))
        XCTAssertTrue(drawn.upperBound < under.lowerBound, "the subtitle under the name")
        XCTAssertFalse(name.contains("Text(app.title)"), "never the title where a name is sent")
        XCTAssertEqual(source.components(separatedBy: "name(app, said)").count - 1, 2, "the table's App cell and the phone's row")
        XCTAssertTrue(source.contains("yourConnections(app: \"\\(app.displayName)\")"), "the app's own screen by its name")
    }

    /// While one of its connections is being removed, the row says so where "Connected" would be, in place of
    /// tafs cloud's line too, which still says connected (as Android 105). The model marks the grant from Face
    /// ID until planque answered and Connect apps was read again.
    func testARowBeingRemovedSaysSo() throws {
        let sheets = try app(#"{"entry":"sheets","title":"Google Sheets","how":"oauth","state":"connected","whose":"Yours","line":"Sign in · Connected"}"#)
        let said = ConnectRow(sheets, removing: true)
        XCTAssertTrue(said.removing)
        XCTAssertEqual(said.status, Words.Connect.disconnecting)
        XCTAssertEqual(said.second, [Words.Connect.How.signIn, "Yours", Words.Connect.disconnecting].joined(separator: " · "))
        XCTAssertEqual(Words.Connect.disconnecting, "Removing access…")
        XCTAssertEqual(ConnectRow(sheets).second, "Sign in · Connected", "otherwise tafs cloud's line")

        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let model = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let start = try XCTUnwrap(model.range(of: "func disconnect(grantID: String) async -> Bool {"))
        let body = String(model[start.upperBound...].prefix(2000))
        let marked = try XCTUnwrap(body.range(of: "removingGrants.insert(grantID)"))
        let call = try XCTUnwrap(body.range(of: "try await planque.disconnect(grantID: grantID)"))
        let reread = try XCTUnwrap(body.range(of: "if connectApps != nil { await loadConnectApps() }"))
        XCTAssertTrue(marked.upperBound < call.lowerBound && call.upperBound < reread.lowerBound, "marked after Face ID, until the list is read again")
        XCTAssertTrue(body.contains("defer { planque.signer = nil; removingGrants.remove(grantID) }"))
        let view = try String(contentsOf: root.appendingPathComponent("Views/ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("removing: model.connections(of: app).contains { model.removingGrants.contains($0.id) })"))
    }

    /// Connected: how it connects, whose, Connected, and who uses it. To connect: how it connects
    /// alone. With no `used_by` yet, this phone's own read names the tafs.
    func testWhatEachRowSays() throws {
        let sheets = try app(#"{"entry":"sheets","title":"Google Sheets","how":"oauth","state":"connected","whose":"Yours","used_by":[{"taf":"t1","name":"Invoices"}]}"#)
        let said = ConnectRow(sheets, usedHere: ["Ignored"])
        XCTAssertEqual(said.how, Words.Connect.How.signIn)
        XCTAssertEqual(said.status, Words.Connect.Status.connected)
        XCTAssertEqual(said.usedBy, Words.Connect.Apps.usedBy(tafs: "Invoices"), "tafs cloud's names win")
        XCTAssertEqual(said.second, [Words.Connect.How.signIn, "Yours", Words.Connect.Status.connected, Words.Connect.Apps.usedBy(tafs: "Invoices")].joined(separator: " · "),
                       "composed when tafs cloud sends no line")
        let worded = try app(#"{"entry":"sheets","title":"Google Sheets","state":"connected","line":"Anmeldung · Verbunden"}"#)
        XCTAssertEqual(ConnectRow(worded).second, "Anmeldung · Verbunden", "tafs cloud's line, as sent")

        let stripe = try app(#"{"entry":"stripe","title":"Stripe","how":"key"}"#)
        let fresh = ConnectRow(stripe, usedHere: ["Refunds"])
        XCTAssertEqual(fresh.second, Words.Connect.How.key, "how it connects alone, and no one uses what isn't connected")

        let gmail = try app(#"{"entry":"gmail","title":"Gmail","how":"oauth","state":"connected"}"#)
        XCTAssertEqual(ConnectRow(gmail, usedHere: ["Leads"]).usedBy, Words.Connect.Apps.usedBy(tafs: "Leads"), "this phone's read, until tafs cloud sends it")
        let out = try app(#"{"entry":"gmail","title":"Gmail","state":"needs_reconnect"}"#)
        XCTAssertEqual(ConnectRow(out).status, Words.Connect.signInAgain)

        // Not set up for anyone to connect yet: tafs cloud's words, and no Connect (as on Android).
        let unset = try app(#"{"entry":"crm","title":"CRM","state":"needs_setup","words":"Not available to connect yet.","line":"Sign in · Not connected"}"#)
        XCTAssertTrue(unset.needsSetup)
        XCTAssertFalse(unset.yours)
        XCTAssertEqual(ConnectRow(unset).second, "Not available to connect yet.", "its words, not the line")
    }

    /// The person's own first, connected or to sign in again, then the rest: each in tafs cloud's order.
    func testYoursComeFirstInTafsCloudsOrder() throws {
        let list = try [#"{"entry":"a","title":"A"}"#, #"{"entry":"b","title":"B","state":"connected"}"#,
                        #"{"entry":"c","title":"C","state":"needs_reconnect"}"#, #"{"entry":"d","title":"D"}"#].map(app)
        let parts = ConnectRow.split(list)
        XCTAssertEqual(parts.yours.map(\.entry), ["b", "c"])
        XCTAssertEqual(parts.discover.map(\.entry), ["a", "d"])
        XCTAssertTrue(try app(#"{"entry":"e","state":"signed_in"}"#).yours, "signed in is the person's own too")

        // A connected app opens its detail; the button stays as wide as its words; iPad is a table.
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let source = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertTrue(source.contains("NavigationLink { AppConnectionsView(app: app) } label: { cells(app, said) }"))
        XCTAssertTrue(source.contains("if AppModel.opensConnections(app, in: model.grants) {\n            NavigationLink { AppConnectionsView(app: app) }"),
                      "a connected app always opens, never a tap that does nothing, and so does one this device holds a connection to")
        XCTAssertTrue(source.contains("if !model.readsConnections {\n                Text(Words.Connect.startConnectedToolsFirst("),
                      "a device that reads no connections says so, not an empty list")
        XCTAssertTrue(source.contains("private var table: Bool { sizeClass == .regular }"))
        XCTAssertTrue(source.contains("} header: { Text(Words.Connect.Apps.yours) }"))
        XCTAssertTrue(source.contains("} header: { Text(Words.Connect.Apps.discover) }"))
        XCTAssertTrue(source.contains("if app.needsSetup {\n            // Not set up for anyone to connect yet"), "no Connect for an app to set up")
        // The whole connected row is the tap target: its ✓ is a picture inside the link, not a button of its own.
        XCTAssertTrue(source.contains("} else if app.connected {\n            Image(systemName: \"checkmark\")"),
                      "the ✓ is part of the row's link, not a tap of its own")
    }

    /// An app under Yours isn't under Discover again in another catalog form (the iPad walk on 2279):
    /// Stripe connected through the managed provider's entry leaves out its reviewed entry, matched by
    /// the entry's last part without its version, or by vendor, as tafs cloud folds them.
    func testAnAppUnderYoursIsntInDiscoverAgain() throws {
        let list = try [
            #"{"entry":"connector:composio:stripe","vendor":"stripe","title":"Stripe","state":"connected"}"#,
            #"{"entry":"stripe@3","vendor":"Stripe","title":"Stripe","how":"key"}"#,
            #"{"entry":"qonto-key@1","vendor":"qonto","title":"Qonto","how":"key","state":"needs_reconnect"}"#,
            #"{"entry":"connector:composio:qonto","vendor":"qonto","title":"Qonto"}"#,
            #"{"entry":"sheets@1","vendor":"Google Sheets","title":"Google Sheets","state":"connected"}"#,
            #"{"entry":"connector:composio:googlesheets","vendor":"googlesheets","title":"Google Sheets"}"#,
            #"{"entry":"connector:composio:gmail","vendor":"gmail","title":"Gmail"}"#,
            #"{"entry":"connector:composio:stripe_atlas","vendor":"stripe_atlas","title":"Stripe Atlas"}"#,
        ].map(app)
        let parts = ConnectRow.split(list)
        XCTAssertEqual(parts.yours.map(\.entry), ["connector:composio:stripe", "qonto-key@1", "sheets@1"])
        XCTAssertEqual(parts.discover.map(\.entry), ["connector:composio:gmail", "connector:composio:stripe_atlas"],
                       "each app once; another app whose name starts the same stays")
        XCTAssertEqual(ConnectRow.keys(list[0]), ["stripe"])
        XCTAssertEqual(ConnectRow.keys(list[2]), ["qontokey", "qonto"])
    }

    /// A Spanish row whose app needs signing in again (the owner's Windows note 3, checked on iPhone, iPad and Mac): the
    /// button "Volver a iniciar sesión" goes under the status wherever beside it would leave the status a sliver, and a short
    /// "Connect" stays beside. Widths measured as the button draws them (15 pt medium, 16 pt padding each side).
    func testALongSignInAgainNeverSqueezesTheStatus() {
        func button(_ label: String) -> CGFloat {
            let font = UIFont.systemFont(ofSize: 15, weight: .medium)
            return ceil((label as NSString).size(withAttributes: [.font: font]).width) + 32
        }
        let spanish = button("Volver a iniciar sesión"), english = button("Connect"), askCoach = button("Preguntar a Coach")
        // The Status column at its least and at its widest (an iPad beside a sidebar, a wide Mac window).
        for status in [ConnectColumns.least[2], ConnectColumns.wide[2]] {
            XCTAssertTrue(BesideOrUnder.under(total: status, button: spanish, spacing: 8, textLeast: BesideOrUnder.tableLeast),
                          "\(status) pt: Volver a iniciar sesión goes under the status")
            XCTAssertTrue(BesideOrUnder.under(total: status, button: askCoach, spacing: 8, textLeast: BesideOrUnder.tableLeast),
                          "\(status) pt: Preguntar a Coach goes under the status")
        }
        XCTAssertFalse(BesideOrUnder.under(total: ConnectColumns.wide[2], button: english, spacing: 8, textLeast: BesideOrUnder.tableLeast),
                       "Connect stays beside the status")
        // A phone's row (an iPhone SE's 375 pt, less the list's insets): the name keeps its room, or the button goes under.
        let row: CGFloat = 375 - 40
        XCTAssertFalse(BesideOrUnder.under(total: row, button: english, spacing: 12, textLeast: BesideOrUnder.rowLeast),
                       "Connect stays beside the name on a phone")
        // Wherever it's beside, the words keep at least their least.
        for total in stride(from: CGFloat(120), through: 600, by: 10) {
            for b in [english, spanish, askCoach] where !BesideOrUnder.under(total: total, button: b, spacing: 8, textLeast: 110) {
                XCTAssertGreaterThanOrEqual(total - b - 8, 110)
            }
        }
    }

    /// Overview's "who uses it" (the owner's Windows note 5a, checked on iPad and Mac): the count is held together, so a
    /// medium window moves "1 persona" to the next line whole instead of cutting it.
    func testTheOverviewsPeopleCountIsNeverSplit() {
        let line = OverviewView.peopleLine(names: ["Rae"], count: 1)
        XCTAssertTrue(line.hasPrefix("Rae · "))
        let count = String(line.dropFirst("Rae · ".count))
        XCTAssertEqual(count, Words.People.count(count: 1).replacingOccurrences(of: " ", with: "\u{00A0}"))
        XCTAssertFalse(count.contains(" "), "no breaking space inside the count")
        XCTAssertEqual(OverviewView.peopleLine(names: ["Support", "Ana"], count: 3).components(separatedBy: " · ").first, "Support, Ana")
    }

    /// iPad's table gives the App column its room first, so "Gmail", "Google Sheets" or "LinkedIn"
    /// read whole: the other columns as wide as they like while there's room, then giving way to
    /// their least; the header and each row lay out the same; a name is one line.
    func testTheAppColumnKeepsItsRoom() throws {
        func check(_ total: CGFloat) -> [CGFloat] {
            let w = ConnectColumns.widths(total)
            XCTAssertEqual(w.count, 4)
            XCTAssertEqual(w.reduce(0, +) + ConnectColumns.spacing * 3, total, accuracy: 0.5, "the columns fill the row at \(total)")
            return w
        }
        // Landscape, or a wide portrait: the three at their widths, the App column takes the rest.
        let wide = check(1000)
        XCTAssertEqual(Array(wide.dropFirst()), ConnectColumns.wide)
        XCTAssertEqual(wide[0], 1000 - 36 - 510)
        // An iPad mini in portrait, or beside a sidebar: the three give way, the App column keeps its least.
        for total in [700, 650, 620] as [CGFloat] {
            let w = check(total)
            XCTAssertEqual(w[0], ConnectColumns.appLeast, accuracy: 0.5, "at \(total)")
            for (i, width) in w.dropFirst().enumerated() {
                XCTAssertLessThanOrEqual(width, ConnectColumns.wide[i] + 0.5)
                XCTAssertGreaterThanOrEqual(width, ConnectColumns.least[i] - 0.5)
            }
        }
        // Narrower than any table should be: the three at their least, the App column what's left.
        XCTAssertEqual(Array(check(500).dropFirst()), ConnectColumns.least)

        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let source = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertEqual(source.components(separatedBy: "ConnectColumns {").count, 3, "the header and the rows, the same layout")
        XCTAssertTrue(source.contains(".lineLimit(1).truncationMode(.tail)\n                    .accessibilityIdentifier(\"connect.title.\" + app.entry)"),
                      "a name on one line, never broken inside a word")
    }

    /// A connection goes only to a taf the person named: the one the list was opened from, else their
    /// pick, asked even when one taf is all there is. Never a guess.
    func testConnectAsksWhichTafEvenWithOne() {
        let one = Taf(name: "Unanswered emails", job: "Emails", tools: [], colorIndex: 0, card: Card(rules: [], issuedBy: "You"))
        let two = Taf(name: "Refunds", job: "Refunds", tools: [], colorIndex: 1, card: Card(rules: [], issuedBy: "You"))
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: nil, connectable: [one]), .ask, "one taf is still asked, never taken")
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: nil, connectable: [one, two]), .ask)
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: two.id, connectable: [one, two]), .taf(two.id), "opened from a taf: that one")
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: nil, connectable: []), .noTaf)
    }

    /// With tafs cloud's `needed_by`, the list only narrows: one taf that needs the app is still asked, as two
    /// are; none is Ask Coach, and a taf opened from that doesn't need it is Ask Coach too, never a start.
    func testWithNeededByEvenOneTafIsAsked() {
        let one = Taf(name: "Refunds", job: "Refunds", tools: [], colorIndex: 0, card: Card(rules: [], issuedBy: "You"))
        let two = Taf(name: "Disputes", job: "Disputes", tools: [], colorIndex: 1, card: Card(rules: [], issuedBy: "You"))
        let other = Taf(name: "Unanswered emails", job: "Emails", tools: [], colorIndex: 2, card: Card(rules: [], issuedBy: "You"))
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: nil, connectable: [one], needed: true), .ask, "named, never taken")
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: nil, connectable: [one, two], needed: true), .ask)
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: nil, connectable: [], needed: true), .askCoach)
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: two.id, connectable: [one, two], needed: true), .taf(two.id))
        XCTAssertEqual(ConnectRow.connectFor(openedFrom: other.id, connectable: [one], needed: true), .askCoach, "Stripe under a Gmail taf")
    }

    /// "Make one first" sits over Discover, where connecting needs a taf, and never over Yours: on an
    /// account with no taf and only connected apps, it doesn't show at all.
    func testMakeATafShowsOnlyOverAppsToConnect() throws {
        let list = try [#"{"entry":"b","title":"B","state":"connected"}"#, #"{"entry":"a","title":"A"}"#].map(app)
        let parts = ConnectRow.split(list)
        let one = Taf(name: "Refunds", job: "Refunds", tools: [], colorIndex: 0, card: Card(rules: [], issuedBy: "You"))
        XCTAssertTrue(ConnectRow.saysMakeATaf(discover: parts.discover, forTaf: false, connectable: []), "apps to connect, and no taf to have them")
        XCTAssertFalse(ConnectRow.saysMakeATaf(discover: [], forTaf: false, connectable: []), "only Yours: nothing there needs a taf")
        XCTAssertFalse(ConnectRow.saysMakeATaf(discover: parts.discover, forTaf: false, connectable: [one]), "a taf can have them")
        XCTAssertFalse(ConnectRow.saysMakeATaf(discover: parts.discover, forTaf: true, connectable: []), "opened from a taf")

        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let source = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertEqual(source.components(separatedBy: "Text(Words.Connect.appsConnectTafMakeOne)").count, 2, "said in one place")
        let line = try XCTUnwrap(source.range(of: "Text(Words.Connect.appsConnectTafMakeOne)"))
        let discover = try XCTUnwrap(source.range(of: "} header: { Text(Words.Connect.Apps.discover) }"))
        let yours = try XCTUnwrap(source.range(of: "} header: { Text(Words.Connect.Apps.yours) }"))
        XCTAssertTrue(yours.upperBound < line.lowerBound && line.upperBound < discover.lowerBound, "inside Discover's section, not Yours'")
    }
}
