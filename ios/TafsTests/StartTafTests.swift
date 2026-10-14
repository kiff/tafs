import XCTest
@testable import Tafs

/// A signer that refuses, as when Face ID is cancelled.
struct RefusingSigner: ApprovalSigning {
    struct Cancelled: Error {}
    func sign(answer: Answer, heldID: String, reason: String) async throws -> String { throw Cancelled() }
}

@MainActor
final class StartTafTests: XCTestCase {
    private let refund = KIFFClient.Connection(name: "stripe_refund", description: "Refund a charge", amountArgument: "amount",
                                               amountUnit: "EUR", amountScale: 2, readOnly: false)
    private let lookup = KIFFClient.Connection(name: "stripe_get_charge", description: nil, amountArgument: nil,
                                               amountUnit: nil, amountScale: nil, readOnly: true)

    private func stubbedSession() -> URLSession {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: config)
    }

    private func model(signer: ApprovalSigning = StubSigner()) -> AppModel {
        let session = stubbedSession()
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        return AppModel(signer: signer, kiff: kiff, cloud: cloud)
    }

    private func body(_ request: URLRequest?) -> [String: Any] {
        guard let data = request?.httpBody, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return json
    }

    // MARK: The plan

    func testAgentIDIsASlugWithASuffix() {
        XCTAssertEqual(TafPlan.agentID(for: "Rae", suffix: "k3f9"), "rae-k3f9")
        XCTAssertEqual(TafPlan.agentID(for: "  Bill Pay #2 ", suffix: "ab12"), "bill-pay-2-ab12")
        XCTAssertEqual(TafPlan.agentID(for: "Zoé", suffix: "ab12"), "zoe-ab12")
        XCTAssertEqual(TafPlan.agentID(for: "!!!", suffix: "ab12"), "taf-ab12")
    }

    func testAmountsUseTheToolsDecimalPlaces() throws {
        XCTAssertEqual(try TafPlan.amount("150", scale: 2, tool: "t", field: "f"), 15000)
        XCTAssertEqual(try TafPlan.amount("25,50", scale: 2, tool: "t", field: "f"), 2550)
        XCTAssertEqual(try TafPlan.amount("7", scale: 0, tool: "t", field: "f"), 7)
        XCTAssertNil(try TafPlan.amount(" ", scale: 2, tool: "t", field: "f"))
        for bad in ["0", "-5", "abc", "1.234"] {
            XCTAssertThrowsError(try TafPlan.amount(bad, scale: 2, tool: "t", field: "f"), bad)
        }
        XCTAssertThrowsError(try TafPlan.amount("1.5", scale: 0, tool: "t", field: "f"), "a count has no decimals")
    }

    func testOneCardPerToolOnTheGatewayDomain() throws {
        let limits = [
            ToolLimit(tool: refund, on: true, perCall: "150", perDay: "2000", callsPerDay: ""),
            ToolLimit(tool: lookup, on: true, callsPerDay: "200"),
            ToolLimit(tool: KIFFClient.Connection(name: "stripe_payout", description: nil, amountArgument: "amount", amountUnit: "EUR", amountScale: 2, readOnly: false), on: false),
        ]
        let cards = try TafPlan.cards(agentID: "rae-k3f9", limits: limits)
        XCTAssertEqual(cards.map(\.id), ["rae-k3f9-stripe_refund", "rae-k3f9-stripe_get_charge"])
        XCTAssertEqual(cards[0].subject, "rae-k3f9")
        XCTAssertEqual(cards[0].domain, "gateway")
        XCTAssertEqual(cards[0].grants, [.init(action: "stripe_refund", perActionMax: [.init(quantity: "sum(amount)", max: 15000)])])
        XCTAssertEqual(cards[0].aggregates, [.init(quantity: "sum(amount)", limit: 200000, window: "calendar_day")])
        XCTAssertEqual(cards[1].aggregates, [.init(quantity: "count", limit: 200, window: "calendar_day")])
        XCTAssertEqual(cards[0].onExceed, "owner")
    }

    func testACardMustLimitSomethingOverTime() {
        XCTAssertThrowsError(try TafPlan.cards(agentID: "a", limits: [ToolLimit(tool: refund, on: true, perCall: "150")])) {
            XCTAssertEqual($0 as? TafPlan.PlanError, .nothingOverTime(tool: "stripe_refund"))
        }
        XCTAssertThrowsError(try TafPlan.cards(agentID: "a", limits: [ToolLimit(tool: refund, on: false, perDay: "1")])) {
            XCTAssertEqual($0 as? TafPlan.PlanError, .noTools)
        }
    }

    func testSuggestsTheJobsToolsWithItsSampleLimits() {
        let usd = KIFFClient.Connection(name: "stripe_refund", description: nil, amountArgument: "amount", amountUnit: "USD", amountScale: 2, readOnly: false)
        let suggested = TafPlan.suggested(job: "refunds", tools: [usd, lookup])
        XCTAssertTrue(suggested[0].on)
        XCTAssertEqual(suggested[0].perCall, "150")
        XCTAssertEqual(suggested[0].perDay, "2000")
        XCTAssertFalse(suggested[1].on, "a tool the job doesn't name starts off")
        // In another currency, the sample's dollar limits are not carried over.
        XCTAssertEqual(TafPlan.suggested(job: "refunds", tools: [refund])[0].perCall, "")
    }

    // MARK: Starting on KIFF

    func testStartsTheAgentItsCardsItsKeyThenTheRun() async throws {
        let model = model()
        StubProtocol.responses["POST /v1/me/agents"] = (201, #"{"id":"rae-k3f9","recorded":true,"sources":["record"]}"#)
        StubProtocol.responses["POST /v1/me/mandates"] = (201, #"{"id":"rae-k3f9-stripe_refund","subject":"rae-k3f9","grants":[],"aggregates":[],"active":true}"#)
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_1","plaintext":"kiff_live_secret","agent_id":"rae-k3f9","created_at":"2026-10-14T10:00:00Z"}"#)
        StubProtocol.responses["POST /v1/tafs"] = (200, #"{"taf":{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active","next_run":"2026-10-14T10:30:00Z","last_run":null},"session":"sesn_1"}"#)

        var steps: [AppModel.StartStep] = []
        let session = try await model.startOnKIFF(name: " Rae ", job: "refunds",
                                                  limits: [ToolLimit(tool: refund, on: true, perCall: "150", perDay: "2000")],
                                                  agentID: "rae-k3f9") { steps.append($0) }
        XCTAssertEqual(session, "sesn_1")
        XCTAssertEqual(steps, [.confirm, .agent, .cards, .key, .run])

        let sent = StubProtocol.sent.map { "\($0.httpMethod ?? "") \($0.url?.host ?? "")\($0.url?.path ?? "")" }
        XCTAssertEqual(Array(sent.prefix(4)), ["POST api.test/v1/me/agents", "POST api.test/v1/me/mandates", "POST api.test/v1/keys", "POST cloud.test/v1/tafs"])
        XCTAssertEqual(body(StubProtocol.sent[0])["id"] as? String, "rae-k3f9")
        let card = body(StubProtocol.sent[1])
        XCTAssertEqual(card["domain"] as? String, "gateway")
        XCTAssertEqual(card["hold_expiry"] as? String, "10m")
        let key = body(StubProtocol.sent[2])
        XCTAssertEqual(key["roles"] as? [String], ["gateway_agent"])
        XCTAssertEqual(key["agent_id"] as? String, "rae-k3f9")
        let start = body(StubProtocol.sent[3])
        XCTAssertEqual(start["agent_key"] as? String, "kiff_live_secret")
        XCTAssertEqual(start["name"] as? String, "Rae")
        XCTAssertEqual(StubProtocol.sent[3].value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_x", "tafs cloud gets the person's session")
        XCTAssertEqual(model.cloudTafs["rae-k3f9"]?.id, "depl_1")
    }

    func testNothingIsSentWithoutFaceID() async {
        let model = model(signer: RefusingSigner())
        do {
            _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")])
            XCTFail("expected a failure")
        } catch let f as AppModel.StartFailure {
            XCTAssertEqual(f.step, .confirm)
        } catch { XCTFail("\(error)") }
        XCTAssertTrue(StubProtocol.sent.isEmpty)
    }

    func testAnEditorIsToldAKeyNeedsAnAdmin() async {
        let model = model()
        StubProtocol.responses["POST /v1/me/agents"] = (201, #"{"id":"a"}"#)
        StubProtocol.responses["POST /v1/me/mandates"] = (201, #"{"id":"c","subject":"a","grants":[],"aggregates":[],"active":true}"#)
        StubProtocol.responses["POST /v1/keys"] = (403, #"{"error":"role_forbidden"}"#)
        do {
            _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")], agentID: "a")
            XCTFail("expected a failure")
        } catch let f as AppModel.StartFailure {
            XCTAssertEqual(f.step, .key)
            XCTAssertTrue(f.message.contains("only an admin"), f.message)
        } catch { XCTFail("\(error)") }
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.host == "cloud.test" }, "no run without a key")
    }

    func testListsConnectedToolsFromTheGateway() async throws {
        let model = model()
        StubProtocol.responses["GET /v1/connections"] = (200, #"{"connections":[{"name":"stripe_refund","description":"Refund","url":"https://x","tool":"refund","amount_argument":"amount","amount_unit":"EUR","amount_scale":2,"idempotency_argument":"","read_only":false,"has_credential":true,"created_at":"2026-10-01T00:00:00Z"}]}"#)
        let tools = try await model.connectedTools()
        XCTAssertEqual(tools, [refund.withDescription("Refund")])
        XCTAssertEqual(StubProtocol.sent.last?.url?.host, "mcp.test")
        XCTAssertEqual(StubProtocol.sent.last?.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_x")
    }
}

private extension KIFFClient.Connection {
    func withDescription(_ d: String) -> KIFFClient.Connection {
        .init(name: name, description: d, amountArgument: amountArgument, amountUnit: amountUnit, amountScale: amountScale, readOnly: readOnly)
    }
}
