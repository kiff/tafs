import XCTest
@testable import Tafs

/// "Your connections" as planque draws them (planque's rows): each row its name, the tafs on it and its
/// health; a tap opens its detail with only the actions planque offers; Remove there, asking once more.
@MainActor
final class ConnectionRowsTests: XCTestCase {
    private func grant(_ extra: String, managed: Bool = true) throws -> PlanqueClient.Grant {
        let connector = managed ? #","connector":{"provider":"composio","toolkit":"gmail"}"# : ""
        let json = #"{"id":"g_1","status":"connected","text":"t","digest":"d","events":[],"title":"Gmail · Demo Shop","request":{"server":"https://mail.test","agent":"inbox","tools":[]\#(connector)}\#(extra)}"#
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    func testTheRowReadsAsPlanqueSendsIt() throws {
        let working = try grant(#","used_by":["Unanswered customer emails"],"health":{"state":"working","ok":true,"words":"Working · last used 00:32","at":"2026-11-14T00:32:00Z"},"actions":[{"id":"remove","words":"Remove access","how":"delete","stops":"Unanswered customer emails will stop using Gmail."}]"#)
        XCTAssertTrue(working.rowFromPlanque)
        XCTAssertEqual(working.usedBy, ["Unanswered customer emails"])
        XCTAssertEqual(working.health, .init(state: "working", ok: true, words: "Working · last used 00:32"))
        XCTAssertEqual(working.actions?.map(\.id), ["remove"])
        XCTAssertEqual(working.actions?.first?.stops, "Unanswered customer emails will stop using Gmail.")
        XCTAssertTrue(working.actions?.first?.removes ?? false)

        // A leftover: only its own removal; never Sign in again.
        let leftover = try grant(#","used_by":[],"health":{"state":"unfinished","ok":false,"words":"Unfinished · signed in on 13 Nov, no tools chosen, no taf uses it"},"actions":[{"id":"remove_unfinished","words":"Remove unfinished connection","how":"delete"}]"#)
        XCTAssertEqual(leftover.actions?.filter(\.known).map(\.id), ["remove_unfinished"])
        XCTAssertFalse(leftover.health?.ok ?? true)

        // Only what this app knows how to take, with its words: another id, a mismatched how, or no words is left out.
        let odd = try grant(#","health":{"state":"signed_out","ok":false,"words":"Gmail's sign-in expired on 14 Nov"},"actions":[{"id":"sign_in_again","words":"Sign in again","how":"approve"},{"id":"sign_in_again","words":"Sign in again","how":"delete"},{"id":"explode","words":"Boom","how":"delete"},{"id":"remove","words":" ","how":"delete"},7]"#)
        XCTAssertEqual(odd.actions?.filter(\.known).map(\.id), ["sign_in_again"])

        // A planque before them: the app's own row, as before.
        let before = try grant("")
        XCTAssertFalse(before.rowFromPlanque)
        XCTAssertNil(before.health)
        XCTAssertFalse(try grant(#","health":{"words":" "},"actions":[]"#).rowFromPlanque, "no health words: the app's own row")
        XCTAssertFalse(try grant(#","health":{"state":"working","ok":true,"words":"Working"}"#).rowFromPlanque, "no actions said: the app's own row")
    }

    /// Every request to planque names this phone's zone, for the times in its words.
    func testPlanqueIsToldThePhonesZone() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: URLSession(configuration: config))
        planque.tenant = "t_01"
        planque.deviceID = "dev_1"
        planque.signer = { _ in "sig" }
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        _ = try await planque.grants()
        XCTAssertEqual(StubProtocol.sent.last?.value(forHTTPHeaderField: "X-Time-Zone"), TimeZone.current.identifier)
    }

    /// The list draws planque's row and opens its detail; the detail draws only known actions, and Remove
    /// asks once more with what stops, before any Face ID. The row itself carries no Remove.
    func testTheListAndTheDetailDrawWhatPlanqueSends() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let file = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertEqual(file.components(separatedBy: "ForEach(group.grants) { g in entry(g) }").count - 1, 1)
        XCTAssertEqual(file.components(separatedBy: "ForEach(groups.others) { g in entry(g) }").count - 1, 1)
        let entry = String(file[try XCTUnwrap(file.range(of: "@ViewBuilder private func entry(")).upperBound...].prefix(1400))
        XCTAssertTrue(entry.contains("if g.rowFromPlanque {"))
        XCTAssertTrue(entry.contains("NavigationLink { ConnectionDetailView(grantID: g.id, appTitle: app.displayName)"))
        let planqueRow = String(entry[..<(try XCTUnwrap(entry.range(of: "} else {")).lowerBound)])
        XCTAssertFalse(planqueRow.contains("Button("), "no action beside the row: they're in its detail")
        let detail = String(file[try XCTUnwrap(file.range(of: "struct ConnectionDetailView: View {")).upperBound...])
        XCTAssertTrue(detail.contains("let actions = (g.actions ?? []).filter(\\.known)"))
        XCTAssertTrue(detail.contains("if action.removes { asking = action; return }"), "Remove asks first")
        XCTAssertTrue(detail.contains("if let stops = action.stops { Text(stops) }"), "naming what stops")
        XCTAssertTrue(detail.contains("if await model.disconnect(grantID: grantID) {"), "then the signed removal, behind Face ID")
    }
}
