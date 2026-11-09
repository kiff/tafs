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

    // MARK: From removed tafs (tafs cloud's `removed_tafs`, as Android's ConnectAppsByTafTest)

    private func page(_ removed: String) throws -> TafsCloud.ConnectApps {
        try KIFFClient.decoder.decode(TafsCloud.ConnectApps.self, from: Data(#"{"apps":[],"removed_tafs":\#(removed)}"#.utf8))
    }

    /// tafs cloud names the removed tafs, and with the signed read each one's live connections
    /// with the tafs whose Cards still use them. No `grants` key means it couldn't say.
    func testRemovedTafsAreReadAsTafsCloudListsThem() throws {
        let read = try page(#"[{"agent":"gone-1","removed_at":"2026-11-08T10:00:00Z","grants":[{"grant":"g2","used_by":[]},{"grant":"g6","used_by":[{"taf":"taf_9","name":"Chargebacks"}]}]},{"agent":"gone-2"}]"#)
        XCTAssertEqual(read.removedTafs.map(\.agent), ["gone-1", "gone-2"])
        XCTAssertEqual(read.removedTafs[0].grants?.map(\.grant), ["g2", "g6"])
        XCTAssertEqual(read.removedTafs[0].grants?[1].usedBy, [.init(taf: "taf_9", name: "Chargebacks")])
        XCTAssertNil(read.removedTafs[1].grants, "not listed: never an empty list")
        let old = try KIFFClient.decoder.decode(TafsCloud.ConnectApps.self, from: Data(#"{"apps":[]}"#.utf8))
        XCTAssertTrue(old.removedTafs.isEmpty, "a tafs cloud without it")
        let listed = AppModel.removedListed(read.removedTafs)
        XCTAssertEqual(Set(listed.keys), ["g2", "g6"])
    }

    /// Only the server's word puts a connection under removed: a taf merely missing from this
    /// person's list stays under Other connections.
    func testOnlyTafsCloudsFlagPutsAConnectionUnderRemoved() throws {
        let req = { (agent: String) in #"{"server":"https://mcp.stripe.com","agent":"\#(agent)","tools":[]}"# }
        let grants = [try g("g1", req("refunds-1")), try g("g2", req("gone-1")), try g("g3", req("unseen-1")), try g("g4", req("gone-1"))]
        let names = ["refunds-1": "Refunds"]
        let split = AppModel.connectionGroups(grants, taf: { names[$0.request.agent] }, removed: ["gone-1"], attention: { _ in false })
        XCTAssertEqual(split.tafs.map(\.taf), ["Refunds"])
        XCTAssertEqual(split.removed.map(\.id), ["g2", "g4"])
        XCTAssertEqual(split.others.map(\.id), ["g3"], "missing, but not named as removed")
        // Without the flag, nothing is under removed.
        let unflagged = AppModel.connectionGroups(grants, taf: { names[$0.request.agent] }, attention: { _ in false })
        XCTAssertTrue(unflagged.removed.isEmpty)
        XCTAssertEqual(unflagged.others.map(\.id), ["g2", "g3", "g4"])
        // A live taf is never under removed, whatever the list says: ids aren't reused.
        let live = AppModel.connectionGroups(grants, taf: { names[$0.request.agent] }, removed: ["refunds-1"], attention: { _ in false })
        XCTAssertTrue(live.removed.isEmpty)
    }

    /// Clear ends only the connections tafs cloud lists with nobody using them. One a live
    /// taf's Card uses is kept for that taf, and one it doesn't list is never touched.
    func testClearEndsOnlyListedConnectionsNobodyUses() throws {
        let req = { (agent: String) in #"{"server":"https://mcp.stripe.com","agent":"\#(agent)","tools":[]}"# }
        let grants = [try g("g2", req("gone-1")), try g("g6", req("gone-1")), try g("g8", req("gone-2")), try g("g3", req("unseen-1"))]
        let listed = AppModel.removedListed(try page(#"[{"agent":"gone-1","grants":[{"grant":"g2","used_by":[]},{"grant":"g6","used_by":[{"taf":"taf_9","name":"Chargebacks"}]}]},{"agent":"gone-2"}]"#).removedTafs)
        let plan = AppModel.clearPlan(["g2", "g6", "g8", "g3"], grants: grants, listed: listed)
        XCTAssertEqual(plan.ending.map(\.id), ["g2"])
        XCTAssertEqual(plan.keptFor.map { $0.map(\.name) }, [["Chargebacks"]])
        // Asked for less, it ends less.
        XCTAssertTrue(AppModel.clearPlan(["g6"], grants: grants, listed: listed).ending.isEmpty)
        // With no list at all (no signed read, or tafs cloud's reads failed), nothing is cleared.
        let none = AppModel.clearPlan(["g2", "g6"], grants: grants, listed: [:])
        XCTAssertTrue(none.ending.isEmpty)
        XCTAssertTrue(none.keptFor.isEmpty)
    }

    /// The words of the section, the same as Android's.
    func testTheRemovedSectionsWords() {
        XCTAssertEqual(ConnectionGroupWords.fromRemoved, "From removed tafs")
        XCTAssertEqual(ConnectionGroupWords.clearRemoved, "Clear these connections")
        XCTAssertEqual(ConnectionGroupWords.clearTitle(1), "Clear 1 connection from removed tafs?")
        XCTAssertEqual(ConnectionGroupWords.clearTitle(2), "Clear 2 connections from removed tafs?")
        XCTAssertEqual(ConnectionGroupWords.clearBody, "Each is signed out at its vendor. One another taf's Card uses is kept.")
        XCTAssertEqual(ConnectionGroupWords.cleared(done: 1, kept: 1, keptFor: ["Chargebacks"], left: 0), "Cleared 1. Kept 1 for Chargebacks, whose Card uses it.")
        XCTAssertEqual(ConnectionGroupWords.cleared(done: 2, kept: 0, keptFor: [], left: 1), "Cleared 2. 1 couldn't be cleared. Try again.")
        XCTAssertEqual(ConnectionGroupWords.cleared(done: 0, kept: 2, keptFor: ["Refunds", "Bills", "Refunds", " "], left: 0), "Kept 2 for Refunds and Bills, whose Cards use them.")
        XCTAssertEqual(ConnectionGroupWords.keptLine(1, []), "Kept 1 for another taf, whose Card uses it.")
        XCTAssertEqual(ConnectionGroupWords.usedBy(["Chargebacks"]), "Kept: Chargebacks's Card uses it")
        XCTAssertEqual(ConnectionGroupWords.usedBy(["Refunds", "Bills", "Save"]), "Kept: Refunds, Bills and Save's Cards use it")
        XCTAssertNil(ConnectionGroupWords.usedBy([]))
        XCTAssertNil(ConnectionGroupWords.usedBy([""]))
    }

    /// planque keeps the name of the taf a connection was made for: what a removed taf's row is called.
    func testAGrantKeepsItsTafsName() throws {
        let json = #"{"id":"g2","status":"connected","text":"","digest":"d","vendor":"Stripe","agent_name":"Old refunds","request":{"server":"https://mcp.stripe.com","agent":"gone-1","tools":[]},"events":[]}"#
        XCTAssertEqual(try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8)).agentName, "Old refunds")
        XCTAssertNil(try g("g1", #"{"server":"https://mcp.stripe.com","agent":"refunds-1","tools":[]}"#).agentName)
    }
}
