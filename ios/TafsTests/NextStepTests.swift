import XCTest
@testable import Tafs

/// A not-done call's next step on its Activity row (kiff/tafs#476, gap 10): tafs cloud says
/// which; the app finds what it opens. The same as Android's NextStepTest.
@MainActor
final class NextStepTests: XCTestCase {
    private let tool = "list_inboxes_k3f9"

    private func grant(_ id: String, status: String, agent: String = "chase-1", tools: [String]? = nil, requested: [String] = []) throws -> PlanqueClient.Grant {
        let toolsJSON = tools.map { #","tools":["# + $0.map { #""\#($0)""# }.joined(separator: ",") + "]" } ?? ""
        let requestedJSON = requested.map { #"{"tool":"\#($0)"}"# }.joined(separator: ",")
        let json = #"{"id":"\#(id)","status":"\#(status)","text":"","digest":"d","request":{"server":"https://mcp.example.com","agent":"\#(agent)","tools":[\#(requestedJSON)]}\#(toolsJSON),"events":[]}"#
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    func testANotDoneRowReadsItsNextStepAsTafsCloudSendsIt() throws {
        let call = { (id: String, at: String) in #"{"proposal_id":"\#(id)","action_name":"list_inboxes_k3f9","actor_id":"chase-1","outcome":"allowed","held":false,"created_at":"\#(at)"}"# }
        let json = #"""
        {"entries":[
          {"id":"e1","at":"2026-11-06T10:00:00Z","kind":"not_done","title":"List inboxes","detail":"The app refused it","time":"10:00","amount_text":null,
           "call":\#(call("p1", "2026-11-06T10:00:00Z")),"next":{"kind":"reconnect","words":"Connect it again","tool":"list_inboxes_k3f9","taf":"Chase invoices"}},
          {"id":"e2","at":"2026-11-06T10:01:00Z","kind":"done","title":"List inboxes","detail":"Done, within its Card","time":"10:01","amount_text":null,
           "call":\#(call("p2", "2026-11-06T10:01:00Z"))}],
         "groups":[],"filters":[],"filter":"all","revision":"r","next_cursor":null}
        """#
        let page = try KIFFClient.decoder.decode(TafsCloud.ActivityPage.self, from: Data(json.utf8))
        XCTAssertEqual(page.entries[0].next, TafsCloud.NextStep(kind: "reconnect", words: "Connect it again", tool: tool, taf: "Chase invoices"))
        XCTAssertNil(page.entries[1].next)
    }

    func testConnectItAgainSignsInAgainWhereItsVendorSignedItOutElseOpensConnectApps() throws {
        let next = TafsCloud.NextStep(kind: "reconnect", words: "Connect it again", tool: tool)
        let out = try grant("g_out", status: "needs_reconnect", tools: [tool])
        XCTAssertEqual(NextStepWords.action(next, agent: "chase-1", grants: [out], fallback: ""), .signInAgain(out))
        // Still connected, its key refused: Connect apps, where it's connected again.
        XCTAssertEqual(NextStepWords.action(next, agent: "chase-1", grants: [try grant("g_on", status: "connected", tools: [tool])], fallback: ""), .connectApps)
        XCTAssertEqual(NextStepWords.action(next, agent: "chase-1", grants: [], fallback: ""), .connectApps)
    }

    func testTheConnectionIsTheOneWhoseToolsIncludeTheCallsToolTheTafsOwnFirst() throws {
        let other = try grant("g_other", status: "connected", agent: "refunds-1", tools: [tool])
        let own = try grant("g_own", status: "needs_reconnect", requested: [tool])
        let gone = try grant("g_gone", status: "disconnected", tools: [tool])
        XCTAssertEqual(NextStepWords.connection(for: tool, agent: "chase-1", grants: [gone, other, own])?.id, "g_own")
        XCTAssertEqual(NextStepWords.connection(for: tool, agent: "chase-1", grants: [gone, other])?.id, "g_other")
        XCTAssertNil(NextStepWords.connection(for: tool, agent: "chase-1", grants: [gone]))
        XCTAssertNil(NextStepWords.connection(for: "", agent: "chase-1", grants: [other]))
    }

    func testAskCoachWritesTafsCloudsMessageInElseTheRowsOwnWords() {
        let words = "Change Chase invoices: List inboxes failed. Agent Mail refused it (it answered 404): Not found."
        XCTAssertEqual(NextStepWords.action(.init(kind: "ask_coach", words: "Ask Coach to change the taf", tool: tool, message: words), agent: "chase-1", grants: [], fallback: "row"),
                       .askCoach(words))
        XCTAssertEqual(NextStepWords.action(.init(kind: "ask_coach", words: "Ask Coach to change the taf", tool: tool, message: " "), agent: "chase-1", grants: [],
                                            fallback: "List inboxes: The app refused it"), .askCoach("List inboxes: The app refused it"))
    }

    func testTryLaterAndAKindTheAppDoesNotKnowAreALine() {
        XCTAssertEqual(NextStepWords.action(.init(kind: "try_later", words: "Try again later: the app was busy or down", tool: tool), agent: nil, grants: [], fallback: ""), .line)
        XCTAssertEqual(NextStepWords.action(.init(kind: "call_support", words: "Call the vendor", tool: tool), agent: nil, grants: [], fallback: ""), .line)
    }
}
