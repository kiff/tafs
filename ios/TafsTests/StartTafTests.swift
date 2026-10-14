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
        let result = try await model.startOnKIFF(name: " Rae ", job: "refunds",
                                                  limits: [ToolLimit(tool: refund, on: true, perCall: "150", perDay: "2000")],
                                                  agentID: "rae-k3f9") { steps.append($0) }
        XCTAssertEqual(result.session, "sesn_1")
        XCTAssertEqual(steps, [.confirm, .agent, .cards, .key, .run])
        XCTAssertNil(model.startAttempt, "a finished start keeps nothing, the key least of all")

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

    // MARK: tafs cloud's answer

    private func stubKIFFSteps() {
        StubProtocol.responses["POST /v1/me/agents"] = (201, #"{"id":"rae-k3f9"}"#)
        StubProtocol.responses["POST /v1/me/mandates"] = (201, #"{"id":"c","subject":"rae-k3f9","grants":[],"aggregates":[],"active":true}"#)
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_1","plaintext":"kiff_live_secret","agent_id":"rae-k3f9"}"#)
    }
    private let raeInCloud = #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active","next_run":null,"last_run":null}]}"#
    private func count(_ method: String, _ path: String) -> Int {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }.count
    }

    func testALostCloudAnswerIsResumedNotStartedAgain() async throws {
        let model = model()
        stubKIFFSteps()
        StubProtocol.responses["POST /v1/tafs"] = (504, #"{"error":"timed out"}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        let limits = [ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        do {
            _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-k3f9")
            XCTFail("expected a failure")
        } catch let f as AppModel.StartFailure {
            XCTAssertEqual(f.step, .run)
            XCTAssertTrue(f.message.contains("may have the taf"), f.message)
            XCTAssertFalse(f.message.contains("didn't start"), "an unknown outcome isn't a failure to start")
        }
        XCTAssertEqual(model.startAttempt?.cloudUnknown, true)

        // The lost start lands; trying again finds it instead of starting another.
        StubProtocol.responses["GET /v1/tafs"] = (200, raeInCloud)
        let result = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits)
        XCTAssertEqual(result.taf.id, "depl_1")
        XCTAssertNil(result.session)
        XCTAssertEqual(count("POST", "/v1/me/agents"), 1)
        XCTAssertEqual(count("POST", "/v1/me/mandates"), 1)
        XCTAssertEqual(count("POST", "/v1/keys"), 1, "the key isn't minted twice")
        XCTAssertEqual(count("POST", "/v1/tafs"), 1, "no second start once the taf is there")
    }

    func testRetryingAfterAnUnknownStartReusesTheKey() async throws {
        let model = model()
        stubKIFFSteps()
        StubProtocol.responses["POST /v1/tafs"] = (503, #"{"error":"busy"}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        let limits = [ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        _ = try? await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-k3f9")

        StubProtocol.responses["POST /v1/tafs"] = (200, #"{"taf":{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"},"session":"sesn_2"}"#)
        let result = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits)
        XCTAssertEqual(result.session, "sesn_2")
        XCTAssertEqual(count("POST", "/v1/keys"), 1)
        XCTAssertEqual(count("POST", "/v1/tafs"), 2)
        let last = StubProtocol.sent.last { $0.url?.path == "/v1/tafs" && $0.httpMethod == "POST" }
        XCTAssertEqual(body(last)["agent_key"] as? String, "kiff_live_secret")
    }

    func testACreatedTafWhoseFirstRunFailedIsStarted() async throws {
        let model = model()
        stubKIFFSteps()
        StubProtocol.responses["POST /v1/tafs"] = (502, #"{"error":"taf "Rae" is created (deployment depl_1), but its first run did not start (session_rate_limited_error: busy)."}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, raeInCloud)
        let result = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")], agentID: "rae-k3f9")
        XCTAssertEqual(result.taf.id, "depl_1")
        XCTAssertNil(result.session, "no first run, and the app says so")
        XCTAssertNil(model.startAttempt)
    }

    func testARetryWithFewerToolsRevokesTheDroppedToolsCard() async throws {
        let model = model()
        stubKIFFSteps()
        StubProtocol.responses["POST /v1/tafs"] = (503, #"{"error":"busy"}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        let both = [ToolLimit(tool: refund, on: true, perDay: "100"), ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        _ = try? await model.startOnKIFF(name: "Rae", job: "refunds", limits: both, agentID: "rae-k3f9")
        XCTAssertEqual(count("POST", "/v1/me/mandates"), 2)

        // The owner turns the refund tool off and tries again.
        StubProtocol.responses["POST /v1/me/mandates/rae-k3f9-stripe_refund/revoke"] = (200, #"{}"#)
        StubProtocol.responses["POST /v1/tafs"] = (200, #"{"taf":{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"},"session":"sesn_2"}"#)
        let result = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")])
        XCTAssertEqual(result.session, "sesn_2")
        let paths = StubProtocol.sent.map { "\($0.httpMethod ?? "") \($0.url?.path ?? "")" }
        let revoked = try XCTUnwrap(paths.firstIndex(of: "POST /v1/me/mandates/rae-k3f9-stripe_refund/revoke"), "the dropped tool's Card is revoked")
        let started = try XCTUnwrap(paths.lastIndex(of: "POST /v1/tafs"))
        XCTAssertLessThan(revoked, started, "before tafs cloud gets the key")
        XCTAssertEqual(count("POST", "/v1/keys"), 1, "the key is still reused")
    }

    func testAFailedStartDoesNotOutliveItsAccount() async throws {
        let model = model()
        stubKIFFSteps()
        StubProtocol.responses["POST /v1/tafs"] = (503, #"{"error":"busy"}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        let limits = [ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        _ = try? await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-k3f9")
        XCTAssertNotNil(model.startAttempt)

        await model.signOut()
        XCTAssertNil(model.startAttempt, "signing out forgets the attempt and its key")

        // Another account signs in and starts a taf with the same name and job.
        model.kiff.token = "kiff_dev_b"
        model.cloud.token = "kiff_dev_b"
        StubProtocol.responses["POST /v1/me/agents"] = (201, #"{"id":"rae-b222"}"#)
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_b","plaintext":"kiff_live_b","agent_id":"rae-b222"}"#)
        StubProtocol.responses["POST /v1/tafs"] = (200, #"{"taf":{"id":"depl_b","name":"Rae","job":"refunds","kiff_agent":"rae-b222","status":"active"},"session":"sesn_b"}"#)
        _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-b222")
        XCTAssertEqual(count("POST", "/v1/me/agents"), 2, "provisioned afresh")
        XCTAssertEqual(count("POST", "/v1/keys"), 2)
        let last = StubProtocol.sent.last { $0.url?.path == "/v1/tafs" && $0.httpMethod == "POST" }
        XCTAssertEqual(body(last)["agent_key"] as? String, "kiff_live_b", "never the first account's key")
        XCTAssertEqual(last?.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_b")
    }

    /// Waits, off the main actor, until the held request has reached the stub.
    private func untilHeldRequestArrives() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
    }

    func testAnAccountChangeMidCardsStopsBeforeTheNextRequest() async {
        let model = model()
        stubKIFFSteps()
        StubProtocol.responses["POST /v1/tafs"] = (200, #"{"taf":{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"},"session":"sesn_1"}"#)
        StubProtocol.holdOnce = "POST /v1/me/mandates"
        let both = [ToolLimit(tool: refund, on: true, perDay: "100"), ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        let start = Task { try await model.startOnKIFF(name: "Rae", job: "refunds", limits: both, agentID: "rae-k3f9") }

        // The first Card is on its way; the owner signs out and another account signs in.
        await untilHeldRequestArrives()
        await model.signOut()
        model.kiff.token = "kiff_dev_b"
        model.cloud.token = "kiff_dev_b"
        StubProtocol.release.signal()

        do {
            _ = try await start.value
            XCTFail("expected the start to stop")
        } catch let f as AppModel.StartFailure {
            XCTAssertEqual(f.step, .cards)
            XCTAssertTrue(f.message.contains("signed out"), f.message)
        } catch { XCTFail("\(error)") }
        XCTAssertEqual(count("POST", "/v1/me/mandates"), 1, "no second Card under the new account")
        XCTAssertEqual(count("POST", "/v1/keys"), 0)
        XCTAssertEqual(count("POST", "/v1/tafs"), 0)
        XCTAssertFalse(StubProtocol.sent.contains { $0.value(forHTTPHeaderField: "Authorization") == "Bearer kiff_dev_b" && $0.httpMethod == "POST" },
                       "nothing of the old start is sent with the new account's session")
        XCTAssertNil(model.startAttempt)
    }

    // MARK: Runs

    private let rae = TafsCloud.CloudTaf(id: "depl_1", name: "Rae", job: "refunds", kiffAgent: "rae-k3f9", status: "active", nextRun: nil, lastRun: nil)

    func testRunNowSendsOneRequestHoweverOftenItIsTapped() async {
        let model = model()
        StubProtocol.responses["POST /v1/tafs/depl_1/run"] = (200, #"{"session":"sesn_9"}"#)
        let taf = rae
        // Two taps: two tasks on the main actor, the second before the first's answer.
        let first = Task { await model.runNow(taf) }
        let second = Task { await model.runNow(taf) }
        let (a, b) = (await first.value, await second.value)
        XCTAssertEqual([a, b].compactMap { $0 }, ["sesn_9"])
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/run"), 1)
        XCTAssertTrue(model.runsInFlight.isEmpty)
        _ = await model.runNow(rae)
        XCTAssertEqual(count("POST", "/v1/tafs/depl_1/run"), 2, "once the first is done, another run can start")
    }

    func testLoadsTheLatestRunsReport() async {
        let model = model()
        StubProtocol.responses["GET /v1/tafs/depl_1/runs"] = (200, #"{"runs":[{"session":"sesn_9","at":"2026-10-14T09:00:00Z","error":null},{"session":"sesn_1","at":"2026-10-14T08:00:00Z","error":null}]}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/runs/sesn_9"] = (200, #"{"session":"sesn_9","report":{"status":"idle","text":"Refunded 3 orders; 1 waits on you."}}"#)
        await model.loadReport(rae)
        XCTAssertEqual(model.reports["depl_1"]?.session, "sesn_9")
        XCTAssertEqual(model.reports["depl_1"]?.text, "Refunded 3 orders; 1 waits on you.")
        XCTAssertEqual(model.reports["depl_1"]?.finished, true)

        StubProtocol.responses["GET /v1/tafs/depl_1/runs"] = (500, #"{"error":"down"}"#)
        await model.loadReport(rae)
        XCTAssertEqual(model.reports["depl_1"]?.text, "Refunded 3 orders; 1 waits on you.", "an error keeps the last report")
        XCTAssertNotNil(model.reports["depl_1"]?.error)
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
