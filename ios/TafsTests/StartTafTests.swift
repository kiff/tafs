import XCTest
@testable import Tafs

/// A signer that refuses, as when Face ID is cancelled.
struct RefusingSigner: ApprovalSigning {
    struct Cancelled: Error {}
    var publicKeyDER: Data { Data([0x30, 0x59]) }
    func sign(_ message: Data, reason: String) async throws -> String { throw Cancelled() }
    func signConfirmed(_ message: Data) throws -> String { throw Cancelled() }
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

    private let confirmedDraft = #"{"id":"drf_1","agent_id":"rae-k3f9","status":"confirmed","version":"v1","claim_secret":"dcs_secret","card_ids":["rae-k3f9-stripe_refund"]}"#
    private let startedRae = #"{"taf":{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active","next_run":"2026-10-14T10:30:00Z","last_run":null},"session":"sesn_1"}"#

    /// From a phone session bound to its key, the start's one setup call, the draft, carries
    /// the phone's signature over KIFF's kiff.request.v1 message (kiff-cloud #1225). One Face
    /// ID, at confirm, covers it. Nothing else is signed this way.
    func testAKeyedPhoneSignsTheDraft() async throws {
        let signer = RecordingSigner()
        let model = model(signer: signer)
        model.kiffTenant = "t1"
        model.sessionHasKey = true
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, confirmedDraft)
        StubProtocol.responses["POST /v1/tafs"] = (200, startedRae)

        _ = try await model.startOnKIFF(name: "Rae", job: "refunds",
                                        limits: [ToolLimit(tool: refund, on: true, perCall: "150", perDay: "2000")], agentID: "rae-k3f9")

        let setup = StubProtocol.sent.filter { $0.url?.host == "api.test" && $0.httpMethod == "POST" }
        XCTAssertEqual(setup.map { $0.url?.path ?? "" }, ["/v1/me/agent-drafts"])
        let request = try XCTUnwrap(setup.first)
        let header = try XCTUnwrap(request.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertTrue(header.hasPrefix("t=") && header.hasSuffix(",v1=signature"), header)
        let signedAt = String(header.dropFirst(2).prefix { $0 != "," })
        let expected = KIFFClient.requestMessage(tenant: "t1", method: "POST", uri: "/v1/me/agent-drafts",
                                                 body: request.httpBody ?? Data(), signedAt: signedAt)
        XCTAssertTrue(signer.signed.contains(String(decoding: expected, as: UTF8.self)), "signed what KIFF checks")
        XCTAssertNil(StubProtocol.sent.first { $0.url?.host == "cloud.test" }?.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(signer.signed.filter { $0.hasPrefix("kiff.request.v1") }.count, 1)
        XCTAssertNil(model.kiff.setupSigner, "nothing signs after the start")
    }

    func testStartsWithOneDraftThenTafsCloudClaimsTheKey() async throws {
        let model = model()
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, confirmedDraft)
        StubProtocol.responses["POST /v1/tafs"] = (200, startedRae)

        var steps: [AppModel.StartStep] = []
        let result = try await model.startOnKIFF(name: " Rae ", job: "refunds",
                                                  limits: [ToolLimit(tool: refund, on: true, perCall: "150", perDay: "2000")],
                                                  agentID: "rae-k3f9") { steps.append($0) }
        XCTAssertEqual(result.session, "sesn_1")
        XCTAssertEqual(steps, [.confirm, .cards, .run])
        XCTAssertNil(model.startAttempt, "a finished start keeps nothing, the claim secret least of all")

        let sent = StubProtocol.sent.map { "\($0.httpMethod ?? "") \($0.url?.host ?? "")\($0.url?.path ?? "")" }
        XCTAssertEqual(Array(sent.prefix(2)), ["POST api.test/v1/me/agent-drafts", "POST cloud.test/v1/tafs"])
        let draft = body(StubProtocol.sent[0])
        XCTAssertEqual(draft["agent_id"] as? String, "rae-k3f9")
        XCTAssertEqual(draft["label"] as? String, "tafs · Rae")
        let card = try XCTUnwrap((draft["cards"] as? [[String: Any]])?.first)
        XCTAssertEqual(card["subject"] as? String, "rae-k3f9")
        XCTAssertEqual(card["domain"] as? String, "gateway")
        XCTAssertEqual(card["hold_expiry"] as? String, "10m")
        let start = body(StubProtocol.sent[1])
        XCTAssertEqual(start["draft_id"] as? String, "drf_1")
        XCTAssertEqual(start["claim_secret"] as? String, "dcs_secret")
        XCTAssertNil(start["agent_key"], "the phone never holds the agent's key")
        XCTAssertEqual(start["name"] as? String, "Rae")
        XCTAssertEqual(StubProtocol.sent[1].value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_x", "tafs cloud gets the person's session")
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.path == "/v1/keys" || $0.url?.path == "/v1/me/agents" })
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

    func testAnEditorsDraftWaitsForAnAdminThenStarts() async throws {
        let model = model()
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_1","agent_id":"rae-k3f9","status":"draft","version":"v1","claim_secret":"dcs_secret"}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/drf_1"] = (200, #"{"id":"drf_1","agent_id":"rae-k3f9","status":"draft","version":"v1"}"#)
        let limits = [ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        do {
            _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-k3f9")
            XCTFail("expected a failure")
        } catch let f as AppModel.StartFailure {
            XCTAssertEqual(f.step, .cards)
            XCTAssertTrue(f.message.contains("admin"), f.message)
        }
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.host == "cloud.test" }, "nothing runs before the Cards are confirmed")

        // An admin confirms it in KIFF; tapping Start again goes on with the same draft.
        StubProtocol.responses["GET /v1/me/agent-drafts/drf_1"] = (200, #"{"id":"drf_1","agent_id":"rae-k3f9","status":"confirmed","version":"v1","card_ids":["c1"]}"#)
        StubProtocol.responses["POST /v1/tafs"] = (200, startedRae)
        let result = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits)
        XCTAssertEqual(result.taf.id, "depl_1")
        XCTAssertEqual(count("POST", "/v1/me/agent-drafts"), 1, "the draft isn't submitted twice")
        let last = StubProtocol.sent.last { $0.url?.path == "/v1/tafs" && $0.httpMethod == "POST" }
        XCTAssertEqual(body(last)["claim_secret"] as? String, "dcs_secret", "the secret from the submit, kept in memory")
    }

    func testATakenAgentNameStartsOverWithANewOne() async {
        let model = model()
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (409, #"{"error":"this agent already holds Cards; a draft starts a new agent"}"#)
        do {
            _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")], agentID: "rae-k3f9")
            XCTFail("expected a failure")
        } catch let f as AppModel.StartFailure {
            XCTAssertEqual(f.step, .cards)
            XCTAssertTrue(f.message.contains("new name"), f.message)
        } catch { XCTFail("\(error)") }
        XCTAssertNil(model.startAttempt)
    }

    // MARK: tafs cloud's answer

    private func stubDraft() {
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, confirmedDraft)
    }
    private let raeInCloud = #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active","next_run":null,"last_run":null}]}"#
    private func count(_ method: String, _ path: String) -> Int {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }.count
    }

    func testALostCloudAnswerIsResumedNotStartedAgain() async throws {
        let model = model()
        stubDraft()
        StubProtocol.responses["POST /v1/tafs"] = (504, #"{"error":"timed out"}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        let limits = [ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        do {
            _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-k3f9")
            XCTFail("expected a failure")
        } catch let f as AppModel.StartFailure {
            XCTAssertEqual(f.step, .run)
            XCTAssertTrue(f.message.contains("may have the taf"), f.message)
        }
        XCTAssertEqual(model.startAttempt?.cloudUnknown, true)

        // The lost start lands; trying again finds it instead of starting another.
        StubProtocol.responses["GET /v1/tafs"] = (200, raeInCloud)
        let result = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits)
        XCTAssertEqual(result.taf.id, "depl_1")
        XCTAssertNil(result.session)
        XCTAssertEqual(count("POST", "/v1/me/agent-drafts"), 1)
        XCTAssertEqual(count("POST", "/v1/tafs"), 1, "no second start once the taf is there")
    }

    func testRetryingAfterAnUnknownStartReusesTheDraft() async throws {
        let model = model()
        stubDraft()
        StubProtocol.responses["POST /v1/tafs"] = (503, #"{"error":"busy"}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        let limits = [ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        _ = try? await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-k3f9")

        StubProtocol.responses["POST /v1/tafs"] = (200, startedRae)
        let result = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits)
        XCTAssertEqual(result.session, "sesn_1")
        XCTAssertEqual(count("POST", "/v1/me/agent-drafts"), 1)
        XCTAssertEqual(count("POST", "/v1/tafs"), 2)
        let last = StubProtocol.sent.last { $0.url?.path == "/v1/tafs" && $0.httpMethod == "POST" }
        XCTAssertEqual(body(last)["claim_secret"] as? String, "dcs_secret")
    }

    func testACreatedTafWhoseFirstRunFailedIsStarted() async throws {
        let model = model()
        stubDraft()
        StubProtocol.responses["POST /v1/tafs"] = (502, #"{"error":"taf "Rae" is created (deployment depl_1), but its first run did not start (session_rate_limited_error: busy)."}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, raeInCloud)
        let result = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")], agentID: "rae-k3f9")
        XCTAssertEqual(result.taf.id, "depl_1")
        XCTAssertNil(result.session, "no first run, and the app says so")
        XCTAssertNil(model.startAttempt)
    }

    func testARetryWithOtherToolsDiscardsTheOldDraftFirst() async throws {
        let model = model()
        stubDraft()
        StubProtocol.responses["POST /v1/tafs"] = (503, #"{"error":"busy"}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        let both = [ToolLimit(tool: refund, on: true, perDay: "100"), ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        _ = try? await model.startOnKIFF(name: "Rae", job: "refunds", limits: both, agentID: "rae-k3f9")

        // The owner turns the refund tool off and tries again.
        StubProtocol.responses["DELETE /v1/me/agent-drafts/drf_1"] = (200, #"{}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_2","agent_id":"rae-new","status":"confirmed","version":"v2","claim_secret":"dcs_2"}"#)
        StubProtocol.responses["POST /v1/tafs"] = (200, startedRae)
        _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")])
        let paths = StubProtocol.sent.map { "\($0.httpMethod ?? "") \($0.url?.path ?? "")" }
        let discarded = try XCTUnwrap(paths.firstIndex(of: "DELETE /v1/me/agent-drafts/drf_1"), "the old draft and its Cards go")
        let resubmitted = try XCTUnwrap(paths.lastIndex(of: "POST /v1/me/agent-drafts"))
        XCTAssertLessThan(discarded, resubmitted)
        let second = StubProtocol.sent.last { $0.url?.path == "/v1/me/agent-drafts" && $0.httpMethod == "POST" }
        XCTAssertNotEqual(body(second)["agent_id"] as? String, "rae-k3f9", "a new agent: the old one may still be named on revoked Cards")
        XCTAssertEqual((body(second)["cards"] as? [Any])?.count, 1)
        let last = StubProtocol.sent.last { $0.url?.path == "/v1/tafs" && $0.httpMethod == "POST" }
        XCTAssertEqual(body(last)["draft_id"] as? String, "drf_2")
    }

    func testARefusedStartTakesItsCardsBack() async {
        let model = model()
        stubDraft()
        StubProtocol.responses["POST /v1/tafs"] = (400, #"{"error":"no job \"payroll\""}"#)
        StubProtocol.responses["DELETE /v1/me/agent-drafts/drf_1"] = (200, #"{}"#)
        do {
            _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")], agentID: "rae-k3f9")
            XCTFail("expected a failure")
        } catch let f as AppModel.StartFailure {
            XCTAssertEqual(f.step, .run)
            XCTAssertTrue(f.message.contains("taken back"), f.message)
        } catch { XCTFail("\(error)") }
        XCTAssertEqual(count("DELETE", "/v1/me/agent-drafts/drf_1"), 1)
        XCTAssertNil(model.startAttempt)
    }

    func testARefusedStartAfterTheClaimRevokesTheCards() async {
        let model = model()
        stubDraft()
        StubProtocol.responses["POST /v1/tafs"] = (400, #"{"error":"the agent key belongs to another KIFF account"}"#)
        StubProtocol.responses["DELETE /v1/me/agent-drafts/drf_1"] = (409, #"{"error":"claimed"}"#)
        StubProtocol.responses["POST /v1/me/mandates/rae-k3f9-stripe_refund/revoke"] = (200, #"{}"#)
        _ = try? await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")], agentID: "rae-k3f9")
        XCTAssertEqual(count("POST", "/v1/me/mandates/rae-k3f9-stripe_refund/revoke"), 1)
    }

    func testAFailedStartDoesNotOutliveItsAccount() async throws {
        let model = model()
        stubDraft()
        StubProtocol.responses["POST /v1/tafs"] = (503, #"{"error":"busy"}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        let limits = [ToolLimit(tool: lookup, on: true, callsPerDay: "5")]
        _ = try? await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-k3f9")
        XCTAssertNotNil(model.startAttempt)

        await model.signOut()
        XCTAssertNil(model.startAttempt, "signing out forgets the attempt and its claim secret")

        // Another account signs in and starts a taf with the same name and job.
        model.kiff.token = "kiff_dev_b"
        model.cloud.token = "kiff_dev_b"
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_b","agent_id":"rae-b222","status":"confirmed","version":"v1","claim_secret":"dcs_b"}"#)
        StubProtocol.responses["POST /v1/tafs"] = (200, #"{"taf":{"id":"depl_b","name":"Rae","job":"refunds","kiff_agent":"rae-b222","status":"active"},"session":"sesn_b"}"#)
        _ = try await model.startOnKIFF(name: "Rae", job: "refunds", limits: limits, agentID: "rae-b222")
        XCTAssertEqual(count("POST", "/v1/me/agent-drafts"), 2, "provisioned afresh")
        let last = StubProtocol.sent.last { $0.url?.path == "/v1/tafs" && $0.httpMethod == "POST" }
        XCTAssertEqual(body(last)["claim_secret"] as? String, "dcs_b", "never the first account's secret")
        XCTAssertEqual(last?.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_b")
    }

    /// Waits, off the main actor, until the held request has reached the stub.
    private func untilHeldRequestArrives() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
    }

    func testAnAccountChangeMidDraftStopsBeforeTafsCloud() async {
        let model = model()
        stubDraft()
        StubProtocol.responses["POST /v1/tafs"] = (200, startedRae)
        StubProtocol.holdOnce = "POST /v1/me/agent-drafts"
        let start = Task { try await model.startOnKIFF(name: "Rae", job: "refunds", limits: [ToolLimit(tool: lookup, on: true, callsPerDay: "5")], agentID: "rae-k3f9") }

        // The draft is on its way; the owner signs out and another account signs in.
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
        XCTAssertEqual(count("POST", "/v1/tafs"), 0, "the claim secret never goes out under the new account")
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
