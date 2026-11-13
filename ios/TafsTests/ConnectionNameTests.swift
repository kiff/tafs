import XCTest
@testable import Tafs

/// A connection's row is called by planque's `title`, never by a managed app's provider id: that
/// stays in `account`, only to be sent back (a door's trigger).
@MainActor
final class ConnectionNameTests: XCTestCase {
    private func grant(account: String? = nil, title: String? = nil, managed: Bool) throws -> PlanqueClient.Grant {
        var fields = [#""id":"g_1","status":"connected","text":"t","digest":"d","events":[]"#]
        if let account { fields.append(#""account":"\#(account)""#) }
        if let title { fields.append(#""title":"\#(title)""#) }
        let connector = managed ? #","connector":{"provider":"composio","toolkit":"gmail"}"# : ""
        fields.append(#""request":{"server":"https://mail.test","agent":"inbox","tools":[]\#(connector)}"#)
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(("{" + fields.joined(separator: ",") + "}").utf8))
    }

    func testTheRowIsNamedByTitleNeverAProvidersID() throws {
        let titled = try grant(account: "ca_CM-AeYdvMUR8", title: "Gmail · Demo Shop", managed: true)
        XCTAssertEqual(titled.name, "Gmail · Demo Shop")
        XCTAssertEqual(titled.account, "ca_CM-AeYdvMUR8", "kept as sent, for a trigger")
        XCTAssertNil(try grant(account: "ca_CM-AeYdvMUR8", managed: true).name, "a managed app's account is never drawn: the row says the app")
        XCTAssertEqual(try grant(account: "acct_123 · Demo Shop", managed: false).name, "acct_123 · Demo Shop", "a direct sign-in's or a key's account, as before")
        XCTAssertNil(try grant(account: " ", title: " ", managed: false).name)
        XCTAssertNil(try grant(managed: false).name)
    }

    /// The rows draw the name, and a door's trigger still sends `account` back.
    func testTheViewsDrawTheNameAndTriggersSendTheAccount() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let connect = try String(contentsOf: root.appendingPathComponent("Views/ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertEqual(connect.components(separatedBy: "Text(g.name ?? app.title)").count - 1, 2)
        XCTAssertFalse(connect.contains("g.account"), "no row draws the account")
        let doors = try String(contentsOf: root.appendingPathComponent("Views/DoorsView.swift"), encoding: .utf8)
        XCTAssertTrue(doors.contains("Text(bot.name ?? bot.vendor)"))
        let model = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        XCTAssertTrue(model.contains("let account = grant.account"), "a trigger is sent with the account planque checks")
    }
}
