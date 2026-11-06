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
        XCTAssertEqual(ConnectionFixWords.waiting("Google Calendar"), "Waiting for the sign-in at Google Calendar")
        XCTAssertEqual(ConnectionFixWords.cancelTitle("Google Calendar"), "Cancel the sign-in to Google Calendar?")
    }
}
