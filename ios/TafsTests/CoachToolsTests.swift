import XCTest
@testable import Tafs

/// Coach's tools (kiff/tafs#206): tafs cloud's MCP server connected in KIFF as the account's
/// control server, Coach's Cards over it, its held installs, and how its tasks went.
@MainActor
final class CoachToolsTests: XCTestCase {
    private func held(action: String, arguments: String) throws -> KIFFClient.CardException {
        let json = """
        {"id":"ce_9","proposal_id":"p9","subject":"setup-acme","action":"\(action)","entity_id":"call_9","title":"Install a version",
          "status":"held","breaches":[{"mandate_id":"coach-version-install","outcome":"limit_reached","quantity":"count","limit":0}],
          "arguments":\(arguments),"created_at":"2026-10-23T18:00:00Z","expires_at":"2099-10-23T18:10:00Z","accounting_version":1}
        """
        return try KIFFClient.decoder.decode(KIFFClient.CardException.self, from: Data(json.utf8))
    }

    func testAHeldInstallOfCoachsNamesItsTafAndVersion() throws {
        let call = KIFFMapping.held(try held(action: "version_install", arguments: #"{"taf":"taf-aaaa","n":2,"tafs_op":"op_1"}"#), tafID: UUID())
        XCTAssertEqual(call.install, VersionInstall(taf: "taf-aaaa", n: 2))
        XCTAssertNil(KIFFMapping.held(try held(action: "taf_task", arguments: #"{"taf":"taf-aaaa","text":"go"}"#), tafID: UUID()).install)
        XCTAssertNil(KIFFMapping.held(try held(action: "version_install", arguments: #"{"taf":"taf-aaaa","n":"two"}"#), tafID: UUID()).install,
                     "no version to install: an ordinary held call")
    }

    func testCoachsToolsAreConnectedSignedOnTheGatewayAndItsCardsDrafted() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!,
                              token: "kiff_dev_x", session: URLSession(configuration: config))
        var signed: [String] = []
        kiff.tenant = "t1"
        kiff.setupSigner = { message in signed.append(String(decoding: message, as: UTF8.self)); return "sig" }
        StubProtocol.responses["POST /v1/connections"] = (201, "{}")
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_c","agent_id":"setup-acme","status":"confirmed","version":"v1"}"#)

        let mcp = URL(string: "https://cloud.test/v1/mcp")!
        try await kiff.connectCoachTool("tafs_state", readOnly: true, mcp: mcp)
        try await kiff.connectCoachTool("taf_task", readOnly: false, mcp: mcp)
        try await kiff.submitCoachCards(agentID: "setup-acme")

        let connects = StubProtocol.sent.filter { $0.url?.path == "/v1/connections" }
        XCTAssertEqual(connects.count, 2)
        for request in connects {
            XCTAssertEqual(request.url?.host, "mcp.test", "the gateway takes connections")
            XCTAssertNotNil(request.value(forHTTPHeaderField: "KIFF-Device-Signature"), "signed, as KIFF asks of a phone")
        }
        let first = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(connects[0].httpBody)) as? [String: Any])
        XCTAssertEqual(first["name"] as? String, "tafs_state")
        XCTAssertEqual(first["tool"] as? String, "tafs_state")
        XCTAssertEqual(first["mode"] as? String, "verify")
        XCTAssertEqual(first["control"] as? Bool, true)
        XCTAssertEqual(first["url"] as? String, mcp.absoluteString)
        XCTAssertEqual(first["audience"] as? String, mcp.absoluteString)
        XCTAssertEqual(first["idempotency_argument"] as? String, "tafs_op")
        XCTAssertEqual(first["read_only"] as? Bool, true)
        let second = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(connects[1].httpBody)) as? [String: Any])
        XCTAssertNil(second["read_only"], "a write isn't declared read-only")
        // Each request signs exactly its own bytes, under KIFF's request message.
        XCTAssertTrue(signed.allSatisfy { $0.hasPrefix("kiff.request.v1\nt1\nPOST\n") }, "\(signed)")
        XCTAssertTrue(signed[0].contains("\n/v1/connections\n"))

        let draft = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/me/agent-drafts" })
        XCTAssertEqual(draft.url?.host, "api.test")
        XCTAssertNotNil(draft.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(draft.httpBody)) as? [String: Any])
        XCTAssertEqual(body["agent_id"] as? String, "setup-acme")
        let cards = try XCTUnwrap(body["cards"] as? [[String: Any]])
        XCTAssertEqual(cards.compactMap { $0["id"] as? String },
                       ["coach-reads", "coach-taf-task", "coach-version-try", "coach-run-stop", "coach-taf-pause", "coach-version-install"])
        let install = try XCTUnwrap(cards.last)
        XCTAssertEqual(install["on_exceed"] as? String, "owner", "every install is held for the owner")
        XCTAssertEqual(((install["aggregates"] as? [[String: Any]])?.first?["limit"] as? Int), 0)
        XCTAssertEqual(((cards[0]["grants"] as? [[String: Any]])?.compactMap { $0["action"] as? String }), ["tafs_state", "taf_run"],
                       "the reads share one Card")
        XCTAssertEqual(cards[3]["on_exceed"] as? String, "refuse")
    }

    func testCoachsChatShowsHowItsTasksWentAndLeavesOutOneItCantRead() async throws {
        StubProtocol.responses = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        XCTAssertEqual(cloud.mcpURL.absoluteString, "https://cloud.test/v1/mcp")
        StubProtocol.responses["GET /v1/tafs/setup_1/conversation"] = (200, """
        {"messages":[],"coach_lines":[{"at":"2026-10-23T18:00:00Z","taf":"Refunds","said":"Refunded order 42."},{"at":"never","taf":"Refunds","said":"x"}]}
        """)
        let thread = try await cloud.setupThread("setup_1")
        XCTAssertEqual(thread.coachLines.map(\.said), ["Refunded order 42."])
        XCTAssertEqual(thread.coachLines.first?.taf, "Refunds")
    }
}
