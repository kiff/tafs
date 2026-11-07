import XCTest
@testable import Tafs

/// A connection's sheet in Connect apps (gap 13), and a sign-in that never came back: the same
/// cases as Android's ConnectionSheetTest.
@MainActor
final class ConnectionSheetTests: XCTestCase {
    private func grant(_ id: String, agent: String, status: String = "connected", kind: String? = nil,
                       tools: String = #"[{"tool":"list_inboxes","name":"list_inboxes_k3f9"}]"#) throws -> PlanqueClient.Grant {
        let k = kind.map { #","kind":"\#($0)""# } ?? ""
        let json = #"{"id":"\#(id)","status":"\#(status)","text":"","digest":"d","vendor":"Google Calendar","request":{"server":"https://mcp.example.com","agent":"\#(agent)","tools":\#(tools)\#(k)},"events":[]}"#
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    private func call(_ id: String, actor: String, action: String, at: String, status: String, message: String = "") -> String {
        #"{"proposal_id":"\#(id)","action_name":"\#(action)","title":"List inboxes","actor_id":"\#(actor)","outcome":"allowed","held":false,"created_at":"\#(at)","tool_outcome":{"status":"\#(status)","message":"\#(message)"}}"#
    }

    private func g(_ id: String, _ request: String, entry: String? = nil, status: String = "connected") throws -> PlanqueClient.Grant {
        let e = entry.map { #","entry":"\#($0)""# } ?? ""
        let json = #"{"id":"\#(id)","status":"\#(status)","text":"","digest":"d","vendor":"Stripe"\#(e),"request":\#(request),"events":[]}"#
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    private func app(_ entry: String) throws -> TafsCloud.ConnectApps.App {
        try JSONDecoder().decode(TafsCloud.ConnectApps.App.self, from: Data(#"{"entry":"\#(entry)","vendor":"Stripe","title":"Stripe","how":"oauth","state":"connected"}"#.utf8))
    }

    /// Connect apps lists every connection to an app (Android 36's phone round), as Android's
    /// ConnectionSheetTest: by the app planque names for it, its `entry`, which planque gives every
    /// grant now, a vendor's own sign-in included. Values as planque lists them.
    func testEveryConnectionToAnAppIsListedUnderItWhateverStartedIt() throws {
        let stripe = "connector:composio:stripe"
        let grants = [
            try g("g1", #"{"server":"https://mcp.stripe.com","agent":"refunds-1","tools":[]}"#, entry: stripe),
            try g("g2", #"{"server":"https://backend.composio.dev/api/v3.1","agent":"refunds-0","connector":{"provider":"composio","toolkit":"stripe"},"tools":[]}"#, entry: stripe),
            try g("g3", #"{"server":"https://backend.composio.dev/api/v3.1","agent":"x-2","connector":{"provider":"composio","toolkit":"gmail"},"tools":[]}"#, entry: "connector:composio:gmail"),
            try g("g4", #"{"server":"https://mcp.stripe.com","agent":"refunds-9","tools":[]}"#, entry: stripe, status: "disconnected"),
            try g("g5", #"{"server":"https://mcp.stripe.com","agent":"refunds-2","tools":[]}"#, entry: stripe, status: "approved"),
            // planque left it unnamed (its app unclear): the phone never guesses one from its server.
            try g("g6", #"{"server":"https://mcp.stripe.com","agent":"x-3","tools":[]}"#),
        ]
        XCTAssertEqual(AppModel.connections(of: try app(stripe), in: grants).map(\.id), ["g1", "g2", "g5"])
    }

    /// An app's connections grouped by taf (the owner's Connect apps ask): a taf with one
    /// needing attention first, the rest by name, each taf's connections together in planque's
    /// order; those with no taf the app can name apart, to show last as "Other connections".
    func testAnAppsConnectionsAreGroupedByTafOthersLast() throws {
        let req = { (agent: String) in #"{"server":"https://mcp.stripe.com","agent":"\#(agent)","tools":[]}"# }
        let grants = [
            try g("g1", req("save-1")),
            try g("g2", req("gone-1")),
            try g("g3", req("refunds-1")),
            try g("g4", req("charge-1")),
            try g("g5", req("save-1")),
            try g("g6", req("gone-2"), status: "approved"),
            try g("g7", req("refunds-1"), status: "needs_reconnect"),
        ]
        let names = ["save-1": "Save Cancels", "refunds-1": "Refunds", "charge-1": "chargebacks"]
        let split = AppModel.connectionGroups(grants, taf: { names[$0.request.agent] }, attention: { $0.waitingSignIn || $0.needsReconnect })
        XCTAssertEqual(split.tafs.map(\.taf), ["Refunds", "chargebacks", "Save Cancels"], "the one needing attention first, then by name, in any case")
        XCTAssertEqual(split.tafs.map { $0.grants.map(\.id) }, [["g3", "g7"], ["g4"], ["g1", "g5"]], "a taf's two connections together")
        XCTAssertEqual(split.others.map(\.id), ["g2", "g6"], "those with no taf it can name, a waiting one too, apart")

        let none = AppModel.connectionGroups(grants, taf: { names[$0.request.agent] }, attention: { _ in false })
        XCTAssertEqual(none.tafs.map(\.taf), ["chargebacks", "Refunds", "Save Cancels"], "by name alone when none needs attention")
        XCTAssertTrue(AppModel.connectionGroups([], taf: { _ in nil }, attention: { _ in false }).tafs.isEmpty)
    }

    /// The last group's name, the same on Android: never "removed", which the app can't tell
    /// from a taf it doesn't see.
    func testTheLastGroupClaimsNothing() {
        XCTAssertEqual(ConnectionGroupWords.others, "Other connections")
        XCTAssertFalse(ConnectionGroupWords.others.lowercased().contains("removed"))
    }

    /// A managed provider's connected grant isn't signed in again in place: planque refuses it.
    func testAProvidersConnectionSaysToDisconnectAndConnectAgain() throws {
        let managed = try g("g9", #"{"server":"https://backend.composio.dev/api/v3.1","agent":"refunds-1","connector":{"provider":"composio","toolkit":"stripe"},"tools":[]}"#)
        XCTAssertTrue(managed.signsInThroughProvider)
        XCTAssertFalse(try g("g9", #"{"server":"https://backend.composio.dev/api/v3.1","agent":"refunds-1","connector":{"provider":"composio","toolkit":"stripe"},"tools":[]}"#, status: "needs_reconnect").signsInThroughProvider)
        XCTAssertFalse(try g("g1", #"{"server":"https://mcp.stripe.com","agent":"refunds-1","tools":[]}"#).signsInThroughProvider)
        XCTAssertEqual(ConnectionFixWords.throughProvider("Stripe"),
                       "Stripe signs in through its connection provider. To sign in again, disconnect it and connect it again.")
    }

    func testAKeyConnectionIsFixedWithANewKeyAndASignInOneBySigningInAgain() throws {
        XCTAssertTrue(try grant("g1", agent: "chase-1", kind: "api_key").takesKey)
        XCTAssertFalse(try grant("g2", agent: "chase-1").takesKey)
    }

    func testItsLastFailedCallIsTheNewestThroughItsToolsItsTafsOwnFirst() throws {
        let json = "[" + [
            call("p1", actor: "chase-1", action: "list_inboxes_k3f9", at: "2026-11-06T10:00:00Z", status: "refused_by_tool", message: "Agent Mail refused it (it answered 403): Forbidden."),
            call("p2", actor: "chase-1", action: "list_inboxes_k3f9", at: "2026-11-06T11:00:00Z", status: "done"),
            call("p3", actor: "other-9", action: "list_inboxes_k3f9", at: "2026-11-06T12:00:00Z", status: "refused_by_tool", message: "Agent Mail refused it (it answered 401): Unauthorized."),
            call("p4", actor: "chase-1", action: "send_email_x1", at: "2026-11-06T13:00:00Z", status: "refused_by_tool", message: "Not this connection's tool."),
        ].joined(separator: ",") + "]"
        let calls = try KIFFClient.decoder.decode([KIFFClient.ActivityCall].self, from: Data(json.utf8))
        let chase = UUID(), other = UUID()
        let record = KIFFMapping.record(calls, ids: ["chase-1": chase, "other-9": other])
        XCTAssertTrue(record.contains { $0.action == "list_inboxes_k3f9" }, "a record keeps its tool as KIFF names it")
        let own = try XCTUnwrap(record.filter { $0.tafID == chase && $0.kind == .notDone && $0.action == "list_inboxes_k3f9" }.first)
        let line = try XCTUnwrap(AppModel.lastFailure(try grant("g1", agent: "chase-1"), record: record, ownTaf: chase))
        XCTAssertTrue(line.hasPrefix("Last failed: List inboxes · "), line)
        XCTAssertTrue(line.contains(own.detail), "its own taf's, though another taf's is newer: \(line)")
        let theirs = try XCTUnwrap(record.filter { $0.tafID == other && $0.kind == .notDone }.first)
        XCTAssertTrue(try XCTUnwrap(AppModel.lastFailure(try grant("g9", agent: "other-9"), record: record, ownTaf: other)).contains(theirs.detail))
        XCTAssertNil(AppModel.lastFailure(try grant("g5", agent: "chase-1", tools: #"[{"tool":"get_contact"}]"#), record: record, ownTaf: chase))
    }

    func testASignInThatNeverCameBackIsWaitingAndCanBeCancelled() throws {
        XCTAssertTrue(try grant("g7", agent: "cal-1", status: "approved").waitingSignIn)
        XCTAssertFalse(try grant("g8", agent: "cal-1", status: "connected").waitingSignIn)
        // Marked connected by this phone: not waiting, as on Android.
        XCTAssertFalse(try grant("g7", agent: "cal-1", status: "approved").marked(connected: true).waitingSignIn)
        XCTAssertEqual(ConnectionFixWords.waiting("Google Calendar"), "Waiting for the sign-in at Google Calendar")
        XCTAssertEqual(ConnectionFixWords.cancelTitle("Google Calendar"), "Cancel the sign-in to Google Calendar?")
    }
}
