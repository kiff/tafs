import XCTest
@testable import Tafs

/// The "Taking shape" panel beside Setup's chat on a wide screen (kiff/tafs#85): the
/// plan, the tools and the Card, read from what the app already holds.
@MainActor
final class SetupShapeTests: XCTestCase {
    private func at(_ s: Int) -> Date { Date(timeIntervalSince1970: 1_800_000_000 + Double(s)) }

    private func shown(_ json: String, _ t: Int) throws -> ShownComponent {
        ShownComponent(firstAt: at(t), session: "s1", run: "r1", component: try SetupTests.component(json))
    }

    private func grant(_ status: String, signedIn: Bool = false) throws -> PlanqueClient.Grant {
        try JSONDecoder().decode(PlanqueClient.Grant.self, from: Data(PlanqueTests.grantJSONFor(status: status, signedIn: signedIn).utf8))
    }

    private func draft(_ status: String, id: String = "drf_1", noTools: Bool = false) throws -> KIFFClient.AgentDraft {
        let json = #"{"id":"\#(id)","agent_id":"refunds-x","status":"\#(status)","version":"v1","no_tools":\#(noTools),"cards":[{"grants":[{"action":"refund_payment","per_action_max":[{"quantity":"sum(amount)","max":15000}]}],"aggregates":[{"quantity":"sum(amount)","limit":200000,"window":"calendar_day"}]}]}"#
        return try JSONDecoder().decode(KIFFClient.AgentDraft.self, from: Data(json.utf8))
    }

    /// An empty conversation has nothing to show.
    func testNothingYet() {
        let shape = SetupShape(thread: SetupThread(), grants: [], drafts: [:], waiting: nil, states: [:])
        XCTAssertTrue(shape.isEmpty)
    }

    /// Setup's plan, as it keeps it: its step at work needs the owner while a card waits.
    func testThePlanAsSetupKeepsIt() throws {
        var thread = SetupThread()
        thread.components = [try shown(#"{"type":"steps","id":"plan","title":"Connect Stripe","steps":[{"label":"Read what you want","status":"done"},{"label":"Sign in at Stripe","status":"active"},{"label":"Choose the tools","status":"pending"},{"label":"Test mode","status":"skipped"}]}"#, 1)]
        let waiting = SetupShape(thread: thread, grants: [], drafts: [:], waiting: "connect", states: [:])
        XCTAssertEqual(waiting.title, "Connect Stripe")
        XCTAssertNil(waiting.name, "no taf handed over yet")
        XCTAssertEqual(waiting.steps.map(\.state), [.done, .needsYou, .toDo, .skipped])
        XCTAssertEqual(waiting.steps.map(\.label), ["Read what you want", "Sign in at Stripe", "Choose the tools", "Test mode"])

        let working = SetupShape(thread: thread, grants: [], drafts: [:], waiting: nil, states: [:])
        XCTAssertEqual(working.steps[1].state, .now, "nothing waits for the owner: Setup is at it")
    }

    /// No plan shown: what Setup asked of the owner, and where each stands.
    func testWithoutAPlanTheAsks() throws {
        var thread = SetupThread()
        thread.messages = [
            TafsCloud.ChatMessage(at: at(0), from: .owner, text: "Refunds, please", session: "s1"),
            TafsCloud.ChatMessage(at: at(5), from: .owner, text: "[confirm which] live", session: "s1"),
        ]
        thread.components = [
            try shown(#"{"type":"confirm","id":"which","text":"Which account?","options":[{"value":"live","label":"Live"}]}"#, 2),
            try shown(#"{"type":"notice","id":"note","tone":"info","text":"Reading Stripe's tools"}"#, 6),
            try shown(#"{"type":"approval","id":"connect","grant":"g_01"}"#, 7),
        ]
        let grants = [try grant("pending")]
        let shape = SetupShape(thread: thread, grants: grants, drafts: [:], waiting: "connect", states: ["connect": .needsYou])
        XCTAssertEqual(shape.steps, [
            SetupShape.Step(label: "Which account?", state: .done),
            SetupShape.Step(label: "Approve Stripe", state: .needsYou),
        ], "a notice asks nothing")

        let declined = SetupShape(thread: thread, grants: grants, drafts: [:], waiting: nil, states: ["connect": .declined])
        XCTAssertEqual(declined.steps.last?.state, .skipped)
        let failed = SetupShape(thread: thread, grants: grants, drafts: [:], waiting: nil, states: ["connect": .failed("planque is down")])
        XCTAssertEqual(failed.steps.last?.state, .failed)
    }

    /// The tools: each grant the conversation names, once, with where it stands; a
    /// connected one lists what the taf calls through it.
    func testTheToolsWhereTheyStand() throws {
        var thread = SetupThread()
        thread.components = [
            try shown(#"{"type":"sign_in","id":"in","grant":"g_01","service":"mcp.stripe.com"}"#, 1),
            try shown(#"{"type":"approval","id":"connect","grant":"g_01"}"#, 2),
            try shown(#"{"type":"approval","id":"other","grant":"g_unknown"}"#, 3),
        ]
        func tools(_ g: PlanqueClient.Grant) -> [SetupShape.Tool] {
            SetupShape(thread: thread, grants: [g], drafts: [:], waiting: nil, states: [:]).tools
        }
        XCTAssertEqual(tools(try grant("pending")), [SetupShape.Tool(id: "g_01", vendor: "Stripe", state: .waiting, actions: [])],
                       "once, and a grant planque hasn't shown is left out")
        XCTAssertEqual(tools(try grant("listed", signedIn: true)).first?.state, .signedIn)
        XCTAssertEqual(tools(try grant("pending", signedIn: true)).first?.state, .signedIn)
        XCTAssertEqual(tools(try grant("failed")).first?.state, .failed(nil))
        let connected = try XCTUnwrap(tools(try grant("connected")).first)
        XCTAssertEqual(connected.state, .connected)
        XCTAssertEqual(connected.actions, ["refund_payment", "list_payments"])
    }

    /// The Card: the latest draft an approval names, a draft until confirmed; Setup's own
    /// no-tools draft isn't the taf's Card. The name comes with the hand-over.
    func testTheCardAndTheName() throws {
        var thread = SetupThread()
        thread.components = [
            try shown(#"{"type":"approval","id":"connect","grant":"g_01","draft":"drf_1"}"#, 1),
            try shown(#"{"type":"approval","id":"setup","grant":"g_02","draft":"drf_s"}"#, 2),
        ]
        let drafts = ["drf_1": try draft("draft"), "drf_s": try draft("confirmed", id: "drf_s", noTools: true)]
        let drafted = SetupShape(thread: thread, grants: [], drafts: drafts, waiting: nil, states: [:])
        let card = try XCTUnwrap(drafted.card)
        XCTAssertFalse(card.confirmed)
        XCTAssertEqual(card.agent, "refunds-x")
        // No unit declared: bare numbers, in words (kiff/tafs#94).
        // No title or unit from KIFF: the action and a bare number as KIFF sends them.
        XCTAssertEqual(card.lines, ["refund_payment: up to \(Money.bare(15000).formatted) each", "\(Money.bare(200000).formatted) in total a day"])

        let confirmed = SetupShape(thread: thread, grants: [], drafts: ["drf_1": try draft("confirmed")], waiting: nil, states: [:])
        XCTAssertEqual(confirmed.card?.confirmed, true)
        XCTAssertNil(SetupShape(thread: thread, grants: [], drafts: [:], waiting: nil, states: [:]).card, "a draft KIFF hasn't shown")

        thread.components.append(try shown(#"{"type":"result","id":"done","taf":{"agent":"refunds-x","name":"Refunds","job":"refunds","draft":"drf_1"},"text":"Done."}"#, 3))
        XCTAssertEqual(SetupShape(thread: thread, grants: [], drafts: [:], waiting: nil, states: [:]).name, "Refunds")
    }
}
