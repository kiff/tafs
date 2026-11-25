import XCTest
@testable import Tafs

/// The Card step's shown = signed (docs/ios-app.md, "The Card step"): the plain list the step shows against the Cards' draft
/// the one approval signs, compared on structured fields on an allowlist, failing closed.
final class CardStepTests: XCTestCase {
    private let step = """
    {"title":"t","groups":[
      {"name":"Watch","line":"l","tools":[
        {"name":"See your tafs","does":"d","why":"w","tool":"tafs_state","card":"coach-reads","limit":500,"asks":false},
        {"name":"Read a run","does":"d","why":"w","tool":"taf_run","card":"coach-reads","limit":500,"asks":false}]},
      {"name":"Run","line":"l","tools":[
        {"name":"Give a task","does":"d","why":"w","tool":"taf_task","card":"coach-taf-task","limit":50,"asks":true}]},
      {"name":"Build","line":"l","tools":[
        {"name":"Install","does":"d","why":"w","tool":"version_install","card":"coach-version-install","limit":0,"asks":true}]}]}
    """

    private func cards(_ edit: (inout [[String: Any]]) -> Void = { _ in }) -> Data {
        var cards: [[String: Any]] = [
            ["id": "coach-reads", "subject": "setup", "domain": "gateway", "on_exceed": "refuse",
             "grants": [["action": "tafs_state"], ["action": "taf_run"]],
             "aggregates": [["quantity": "count", "limit": 500, "window": "calendar_day"]]],
            ["id": "coach-taf-task", "subject": "setup", "domain": "gateway", "on_exceed": "owner", "hold_expiry": "10m",
             "grants": [["action": "taf_task"]],
             "aggregates": [["quantity": "count", "limit": 50, "window": "calendar_day"]]],
            ["id": "coach-version-install", "subject": "setup", "domain": "gateway", "on_exceed": "owner",
             "grants": [["action": "version_install"]],
             "aggregates": [["quantity": "count", "limit": 0, "window": "calendar_day"]]],
        ]
        edit(&cards)
        return try! JSONSerialization.data(withJSONObject: ["agent_id": "setup", "label": "Coach", "cards": cards, "contract_digest": "x"])
    }

    private func decoded(_ json: String) -> TafsCloud.CoachTools.Step {
        try! JSONDecoder().decode(TafsCloud.CoachTools.Step.self, from: Data(json.utf8))
    }

    private func matches(_ draft: Data, step: String? = nil) -> Bool {
        TafsCloud.CardStepCheck.matches(step: decoded(step ?? self.step), draft: draft, agent: "setup")
    }

    func testTheListShownIsWhatTheDraftSigns() {
        XCTAssertTrue(matches(cards()))
    }

    /// Anything on a signed Card beyond what the step shows, or not as it shows it, and nothing is signed.
    func testAnythingElseOnTheSignedCardsFailsClosed() {
        let changes: [(String, (inout [[String: Any]]) -> Void)] = [
            ("a higher limit", { $0[1]["aggregates"] = [["quantity": "count", "limit": 51, "window": "calendar_day"]] }),
            ("extra room on the total", { $0[0]["aggregates"] = [["quantity": "count", "limit": 500, "window": "calendar_day", "extra_room": 100]] }),
            ("a measure", { $0[0]["aggregates"] = [["quantity": "count", "limit": 500, "window": "calendar_day", "measure": "effects"]] }),
            ("another window", { $0[0]["aggregates"] = [["quantity": "count", "limit": 500, "window": "rolling_week"]] }),
            ("a sum, not a count", { $0[0]["aggregates"] = [["quantity": "sum(amount)", "limit": 500, "window": "calendar_day"]] }),
            ("a second aggregate", { $0[0]["aggregates"] = [["quantity": "count", "limit": 500, "window": "calendar_day"], ["quantity": "count", "limit": 9, "window": "calendar_day"]] }),
            ("no aggregate", { $0[0]["aggregates"] = [] as [Any] }),
            ("a per-call cap on a grant", { $0[1]["grants"] = [["action": "taf_task", "per_action_max": ["quantity": "amount", "max": 1]]] }),
            ("an action the step doesn't show", { $0[1]["grants"] = [["action": "taf_task"], ["action": "taf_delete"]] }),
            ("an action on two Cards", { $0[2]["grants"] = [["action": "version_install"], ["action": "taf_task"]] }),
            ("another subject", { $0[0]["subject"] = "someone" }),
            ("no domain", { $0[0].removeValue(forKey: "domain") }),
            ("another domain", { $0[0]["domain"] = "api" }),
            ("refuse where the step says it asks", { $0[1]["on_exceed"] = "refuse"; $0[1].removeValue(forKey: "hold_expiry") }),
            ("asks where the step says refused", { $0[0]["on_exceed"] = "owner" }),
            ("an on_exceed KIFF has beside these", { $0[0]["on_exceed"] = "keep" }),
            ("a hold's expiry on a refusing Card", { $0[0]["hold_expiry"] = "10m" }),
            ("an environment", { $0[0]["environment"] = "live" }),
            ("resources", { $0[0]["resources"] = ["*"] }),
            ("a Card the step doesn't show", { $0.append(["id": "coach-more", "subject": "setup", "domain": "gateway", "on_exceed": "refuse", "grants": [["action": "web_read"]], "aggregates": [["quantity": "count", "limit": 1, "window": "calendar_day"]]]) }),
            ("a Card the step shows, missing", { $0.removeLast() }),
            ("another Card id", { $0[2]["id"] = "coach-install" }),
            ("a limit that isn't a whole number", { $0[1]["aggregates"] = [["quantity": "count", "limit": 50.5, "window": "calendar_day"]] }),
            ("a limit that's true", { $0[1]["aggregates"] = [["quantity": "count", "limit": true, "window": "calendar_day"]] }),
        ]
        for (why, change) in changes {
            XCTAssertFalse(matches(cards(change)), why)
        }
        XCTAssertFalse(TafsCloud.CardStepCheck.matches(step: decoded(step), draft: cards(), agent: "another"), "the draft for another agent")
        XCTAssertFalse(matches(Data("not json".utf8)), "a draft that doesn't read")
    }

    /// The step's own list must say all of it, once per tool.
    func testAStepThatDoesntSayItAllFailsClosed() {
        let without = step.replacingOccurrences(of: #","limit":50,"asks":true"#, with: "")
        XCTAssertFalse(matches(cards(), step: without), "a tool without its limit")
        let twice = step.replacingOccurrences(of: #""tool":"taf_run","card":"coach-reads""#, with: #""tool":"tafs_state","card":"coach-reads""#)
        XCTAssertFalse(matches(cards(), step: twice), "a tool listed twice")
        let otherLimit = step.replacingOccurrences(of: #""limit":500"#, with: #""limit":5000"#)
        XCTAssertFalse(matches(cards(), step: otherLimit), "the list says another limit")
        XCTAssertFalse(matches(cards(), step: #"{"title":"t","groups":[]}"#), "nothing shown")
    }

    /// A later approval (Coach exists, some Cards held): the step lists every tool and marks those its draft carries
    /// (`in_draft`); only those are compared with the draft, both ways.
    func testALaterApprovalComparesTheToolsInItsDraft() {
        let marked = step
            .replacingOccurrences(of: #""asks":false}"#, with: #""asks":false,"in_draft":false}"#)
            .replacingOccurrences(of: #""asks":true}"#, with: #""asks":true,"in_draft":true}"#)
        let partial = cards { $0.removeFirst() }
        XCTAssertTrue(matches(partial, step: marked), "the two drafted Cards, as marked")
        XCTAssertFalse(matches(cards(), step: marked), "a Card in the draft the step doesn't mark")
        XCTAssertFalse(matches(cards { $0.removeFirst(); $0.removeLast() }, step: marked), "a marked tool whose Card isn't in the draft")
        XCTAssertFalse(matches(partial, step: step), "unmarked, every tool must be in the draft")

        var tools = TafsCloud.CoachTools()
        tools.state = "to_give"
        tools.step = decoded(marked)
        tools.card = .init(body: partial)
        XCTAssertTrue(tools.draftSigns)
        tools.card = .init(body: cards())
        XCTAssertFalse(tools.draftSigns, "the read's draft signs more than the step marks: nothing is signed")
        tools.step = decoded(step.replacingOccurrences(of: #","card":"coach-reads""#, with: #","card":"coach-reads""#))
        XCTAssertTrue(tools.draftSigns, "a tafs cloud that doesn't mark them: KIFF's own words are what's shown as signed")
        tools.card = nil
        XCTAssertTrue(tools.draftSigns, "no draft to sign")
    }

    /// Start's words carry the Card step; without every line of it, Start's screen is as before.
    func testStartsWordsMakeTheCardStepOnlyWhenComplete() {
        func start(_ words: String) -> TafsCloud.CoachStart? {
            TafsCloud.CoachStart.parse(Data("""
            {"state":"to_give","scheme":"kiff.request.v1","agent_id":"setup","requests":[{"id":"draft","method":"POST","path":"/v1/me/agent-drafts","body":\(String(decoding: cards(), as: UTF8.self)),"cards":[{"title":"Coach's reads","lines":["500 a day"]}]}],"coach_tools_step":\(step),"words":\(words)}
            """.utf8))
        }
        let full = #"{"lead":"l","start":"Start","title":"Let Coach build tafs with you","lines":{"allows":"a","asks":"b","sign":"c","stop":"d"},"exact":"Exactly what you sign","buttons":{"fingerprint":"Approve with fingerprint","face":"Approve with face","passkey":"p"},"sign_passkey":"s","locked":"Approve this first, then you can write to Coach."}"#
        let s = start(full)
        XCTAssertEqual(s?.cardStep(face: true), true)
        XCTAssertEqual(s?.cardStep(face: false), true)
        XCTAssertEqual(s?.words?.give(face: true), "Approve with face")
        XCTAssertEqual(s?.words?.give(face: false), "Approve with fingerprint")
        XCTAssertEqual(s?.words?.locked, "Approve this first, then you can write to Coach.")
        XCTAssertEqual(s?.cardStepSigns, true)
        let noFace = full.replacingOccurrences(of: #""face":"Approve with face","#, with: "")
        XCTAssertEqual(start(noFace)?.cardStep(face: true), false, "no button for this phone's way: Start's screen as before")
        XCTAssertEqual(start(noFace)?.cardStep(face: false), true)
        // No `locked` from tafs cloud: still the Card step, and the box stays locked under the app's own line.
        let noLocked = full.replacingOccurrences(of: #","locked":"Approve this first, then you can write to Coach.""#, with: "")
        XCTAssertEqual(start(noLocked)?.cardStep(face: true), true, "no locked line: still the step, still locked")
        XCTAssertEqual(SetupChatView.cardStepLockedLine(start(noLocked)?.words), Words.Coach.Tools.writeAfter)
        XCTAssertEqual(SetupChatView.cardStepLockedLine(s?.words), "Approve this first, then you can write to Coach.")
        XCTAssertEqual(SetupChatView.cardStepLockedLine(nil), Words.Coach.Tools.writeAfter)
        let older = #"{"lead":"l","start":"Start"}"#
        XCTAssertEqual(start(older)?.cardStep(face: true), false, "a tafs cloud from before the Card step")
        XCTAssertEqual(start(older)?.oneScreen, true)
    }

    /// The tools batch the same approval signs, held to the step: each connection one of its tools, as Coach's own control
    /// server at this app's tafs cloud, with only the keys tafs cloud writes.
    func testTheToolsBatchConnectsOnlyTheStepsToolsAtThisAppsTafsCloud() {
        let mcp = "https://cloud.test/v1/mcp"
        func batch(_ edit: (inout [[String: Any]]) -> Void = { _ in }) -> Data {
            var connections: [[String: Any]] = [
                ["name": "tafs_state", "url": mcp, "tool": "tafs_state", "mode": "verify", "audience": mcp, "control": true,
                 "idempotency_argument": "tafs_op", "read_only": true],
                ["name": "taf_task", "url": mcp, "tool": "taf_task", "mode": "verify", "audience": mcp, "control": true,
                 "idempotency_argument": "tafs_op"],
            ]
            edit(&connections)
            return try! JSONSerialization.data(withJSONObject: ["connections": connections])
        }
        let shown = decoded(step)
        XCTAssertTrue(TafsCloud.CardStepCheck.batchMatches(step: shown, batch: batch(), mcp: mcp))
        let changes: [(String, (inout [[String: Any]]) -> Void)] = [
            ("a tool the step doesn't show", { $0[1]["tool"] = "taf_delete"; $0[1]["name"] = "taf_delete" }),
            ("a name that isn't its tool", { $0[1]["name"] = "something_else" }),
            ("another server", { $0[1]["url"] = "https://elsewhere.example/v1/mcp" }),
            ("another audience", { $0[1]["audience"] = "https://elsewhere.example/v1/mcp" }),
            ("not the control server", { $0[1]["control"] = false }),
            ("control as a number", { $0[1]["control"] = 1 }),
            ("another mode", { $0[1]["mode"] = "trust" }),
            ("no idempotency argument", { $0[1].removeValue(forKey: "idempotency_argument") }),
            ("read_only as false", { $0[0]["read_only"] = false }),
            ("a key tafs cloud doesn't write", { $0[1]["headers"] = ["Authorization": "x"] }),
            ("a tool twice", { $0.append($0[0]) }),
            ("nothing to connect", { $0.removeAll() }),
        ]
        for (why, change) in changes {
            XCTAssertFalse(TafsCloud.CardStepCheck.batchMatches(step: shown, batch: batch(change), mcp: mcp), why)
        }
        XCTAssertFalse(TafsCloud.CardStepCheck.batchMatches(step: shown, batch: batch(), mcp: "https://tafs.other/v1/mcp"),
                       "the batch names another tafs cloud than this app's")
        XCTAssertTrue(TafsCloud.CardStepCheck.batchMatches(step: shown, batch: batch { $0[1]["replace"] = true }, mcp: mcp),
                      "replacing an earlier connection, as tafs cloud marks it")
    }
}
