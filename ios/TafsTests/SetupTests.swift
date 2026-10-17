import XCTest
@testable import Tafs

/// The setup chat (kiff/tafs#50, contract 1 on kiff/agents#91): starting Setup once, its
/// thread of messages and components, and the owner's answers.
@MainActor
final class SetupTests: XCTestCase {
    private static let setupJSON = #"{"id":"taf-setup","name":"Setup","job":"setup","kiff_agent":"setup","status":"active","next_run":null,"last_run":null}"#

    private func model(signer: FaceIDSigner, vendor: StubVendor = StubVendor()) async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        Keychain.delete(AppModel.planqueAccount)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: vendor)
        for path in ["GET /v1/me/mandates", "GET /v1/me/mandates?include=use"] { StubProtocol.responses[path] = (200, #"{"mandates":[]}"#) }
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":null}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return model
    }

    private func body(_ request: URLRequest?) -> [String: Any] {
        guard let data = request?.httpBody, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return json
    }

    private func sent(_ method: String, _ host: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.host == host && $0.url?.path == path }
    }

    /// One Face ID starts it all, once: planque gets the phone's key and a connector key,
    /// and Setup starts from a no-tools draft whose key tafs cloud claims.
    func testStartSetupOnce() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer)
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"ab12"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_s","agent_id":"setup","status":"confirmed","version":"v1","claim_secret":"kiff_claim_x","no_tools":true,"cards":[]}"#)
        StubProtocol.responses["POST /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)

        let started = await model.startSetup()
        XCTAssertTrue(started, model.setupError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.setup.v1|t_01"])
        XCTAssertTrue(model.planqueReady)
        XCTAssertEqual(model.setupTaf?.id, "taf-setup")
        let draft = try XCTUnwrap(sent("POST", "api.test", "/v1/me/agent-drafts").first)
        XCTAssertEqual(body(draft)["no_tools"] as? Bool, true)
        XCTAssertEqual(body(draft)["agent_id"] as? String, "setup")
        XCTAssertNil(body(draft)["cards"], "a no-tools draft sends no Cards")
        XCTAssertNotNil(draft.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertNotNil(sent("POST", "api.test", "/v1/keys").first?.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        let start = try XCTUnwrap(sent("POST", "cloud.test", "/v1/setup").first)
        XCTAssertEqual(body(start) as? [String: String], ["draft_id": "drf_s", "claim_secret": "kiff_claim_x"])
        XCTAssertNil(model.kiff.setupSigner)
        XCTAssertNil(model.planque.signer)

        StubProtocol.sent = []
        let again = await model.startSetup()
        XCTAssertTrue(again)
        XCTAssertEqual(signer.prompts.count, 1, "no second Face ID")
        XCTAssertTrue(StubProtocol.sent.isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// The thread: each component at its first place, at its latest version; the plan is
    /// pinned, not in the thread; a component the app doesn't know is skipped.
    func testThreadFoldsComponents() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[{"at":"2026-10-17T10:00:00.000Z","from":"owner","kind":"message","text":"Handle refunds from my Stripe","session":"s1"},
                     {"at":"2026-10-17T10:00:03.000Z","from":"taf","kind":"message","text":"Here's the plan.","session":"s1"}],
         "components":[
          {"at":"2026-10-17T10:00:02.000Z","session":"s1","run":"r1","seq":2,"component":{"type":"steps","id":"plan","title":"Connect Stripe","steps":[{"label":"Sign in","status":"active"}]}},
          {"at":"2026-10-17T10:00:04.000Z","session":"s1","run":"r1","seq":4,"component":{"type":"confirm","id":"which","text":"Which account?","options":[{"value":"live","label":"Live"},{"value":"test","label":"Test"}]}},
          {"at":"2026-10-17T10:00:05.000Z","session":"s1","run":"r1","seq":5,"component":{"type":"hologram","id":"future"}},
          {"at":"2026-10-17T10:00:06.000Z","session":"s1","run":"r1","seq":6,"component":{"type":"steps","id":"plan","title":"Connect Stripe","steps":[{"label":"Sign in","status":"done"}]}}]}
        """)
        await model.loadSetup()
        XCTAssertEqual(model.setupThread.plan?.component.steps?.first?.status, "done", "the latest version")
        XCTAssertEqual(model.setupThread.items.map(\.id), [
            "m|" + model.setupThread.messages[0].id, "m|" + model.setupThread.messages[1].id, "c|which",
        ])
        XCTAssertEqual(model.setupThread.session, "s1")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A card that asks the owner comes after what Setup wrote with it (#58 B3), even when
    /// Setup showed it first; a card from an earlier turn stays where it was.
    func testACardComesAfterSetupsWords() throws {
        func at(_ s: Int) -> Date { Date(timeIntervalSince1970: 1_800_000_000 + Double(s)) }
        func shown(_ json: String, _ t: Int) throws -> ShownComponent {
            ShownComponent(firstAt: at(t), session: "s1", run: "r1", component: try SetupTests.component(json))
        }
        var thread = SetupThread()
        thread.messages = [
            TafsCloud.ChatMessage(at: at(0), from: .owner, text: "Refunds, please", session: "s1"),
            TafsCloud.ChatMessage(at: at(3), from: .taf, text: "I'll connect Stripe. Approve below.", session: "s1"),
            TafsCloud.ChatMessage(at: at(10), from: .owner, text: "[approval connect] approved, signed in", session: "s1"),
            TafsCloud.ChatMessage(at: at(12), from: .taf, text: "Connected.", session: "s1"),
        ]
        thread.components = [try shown(#"{"type":"approval","id":"connect","grant":"g_01"}"#, 2),
                             try shown(#"{"type":"notice","id":"note","tone":"info","text":"Reading Stripe's tools"}"#, 11)]
        let order = thread.items.map(\.id)
        let connect = try XCTUnwrap(order.firstIndex(of: "c|connect"))
        let words = try XCTUnwrap(order.firstIndex(of: "m|" + thread.messages[1].id))
        let answer = try XCTUnwrap(order.firstIndex(of: "m|" + thread.messages[2].id))
        XCTAssertEqual(connect, words + 1, "the card right after Setup's words")
        XCTAssertLessThan(connect, answer, "and before the owner's answer")
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: "c|note")), try XCTUnwrap(order.firstIndex(of: "m|" + thread.messages[3].id)),
                          "a notice stays where it happened")
    }

    /// While Setup works, the chat shows it thinking and Send is Stop (#58 B1): from the
    /// owner's message until tafs cloud says the run ended; Stop cancels that run.
    func testThinkingUntilSetupAnswersAndStopCancels() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":true}"#)
        await model.loadSetup()
        XCTAssertFalse(model.setupThinking)

        // Sent, and tafs cloud doesn't see the run yet: thinking all the same.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":null}"#)
        let delivered = await model.sendSetup("Make sure customers can get their refunds")
        XCTAssertTrue(delivered)
        XCTAssertTrue(model.setupThinking)

        // At work: thinking, and Stop names its session.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":{"session":"s1","run":"r1"}}"#)
        await model.loadSetupThread()
        XCTAssertTrue(model.setupThinking)
        StubProtocol.responses["POST /v1/tafs/taf-setup/stop"] = (200, #"{"stopped":true}"#)
        await model.stopSetup()
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/stop").last) as? [String: String], ["session": "s1"])

        // The run ended: no longer thinking.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":null}"#)
        await model.loadSetupThread()
        XCTAssertFalse(model.setupThinking)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A message is streamed (#60): tafs cloud takes it on the stream route, Setup's words
    /// arrive as written, each tool call reloads the thread (the turn's words are recorded
    /// by then), and the end reloads it once more. Nothing goes through the plain route.
    func testSetupsAnswerStreams() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        await model.loadSetup()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = (200, """
        event: session
        data: {"session":"s1","new_session":true}

        event: text
        data: {"text":"I'll connect "}

        event: text
        data: {"text":"**Stripe**."}

        event: activity
        data: {"tool":"planque"}

        event: end
        data: {"status":"COMPLETED","stop":"answered"}


        """)
        StubProtocol.sent = []
        let delivered = await model.sendSetup("Make sure customers can get their refunds")
        XCTAssertTrue(delivered)
        XCTAssertTrue(model.setupStreamWorks)
        await model.setupStream?.value
        XCTAssertNil(model.setupLive, "the stream ended")
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages/stream").first)["text"] as? String,
                       "Make sure customers can get their refunds")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty, "not sent twice")
        XCTAssertGreaterThanOrEqual(sent("GET", "cloud.test", "/v1/tafs/taf-setup/conversation").count, 2, "at the tool call and at the end")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Stop while a message streams stops that message's chat, a new one included, not
    /// the thread's previous chat.
    func testStopWhileStreamingStopsItsOwnChat() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[{"at":"2026-10-17T10:00:00.000Z","from":"owner","kind":"message","text":"earlier","session":"s_old"}],"components":[],"working":null}
        """)
        await model.loadSetup()
        // The stream says its chat, then holds (no end yet).
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = (200, "event: session\ndata: {\"session\":\"s_new\",\"new_session\":true}\n\n")
        StubProtocol.responses["POST /v1/tafs/taf-setup/stop"] = (200, #"{"stopped":true}"#)
        let delivered = await model.sendSetup("start over")
        XCTAssertTrue(delivered)
        // Read the session event before the stream ends.
        for _ in 0..<200 where model.setupLive?.session == nil { try await Task.sleep(nanoseconds: 1_000_000) }
        // The stream's chat is kept until the thread is reloaded at its end.
        XCTAssertEqual(model.setupLive?.session, "s_new")
        await model.stopSetup()
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/stop").last) as? [String: String], ["session": "s_new"],
                       "the new chat, not s_old")
        await model.setupStream?.value
        XCTAssertNil(model.setupLive)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// No stream route (an older tafs cloud) or no way to it: the plain send takes the
    /// message. A refusal on the stream route is the owner's to see, and isn't sent again.
    func testWithoutAStreamThePlainSendTakesIt() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":true}"#)
        await model.loadSetup()
        StubProtocol.sent = []
        let plain = await model.sendSetup("hello")
        XCTAssertTrue(plain)
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").count, 1)
        XCTAssertFalse(model.setupStreamWorks)

        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = (429, #"{"error":"that's 10 messages in 10 minutes"}"#)
        StubProtocol.sent = []
        let refused = await model.sendSetup("again")
        XCTAssertFalse(refused)
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty)
        XCTAssertEqual(model.setupError, "It wasn't sent: that's 10 messages in 10 minutes")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// tafs cloud's stream events, read: unknown ones skipped.
    func testStreamEventsAreRead() {
        func e(_ name: String, _ json: String) -> TafsCloud.StreamEvent? { TafsCloud.streamEvent(name, data: Data(json.utf8)) }
        XCTAssertEqual(e("session", #"{"session":"s1","new_session":true}"#), .session("s1", newSession: true))
        XCTAssertEqual(e("text", #"{"text":"a\nb"}"#), .text("a\nb"))
        XCTAssertEqual(e("activity", #"{"tool":"planque"}"#), .activity("planque"))
        XCTAssertEqual(e("component", #"{"component":{}}"#), .component)
        XCTAssertEqual(e("end", #"{"status":"COMPLETED","stop":"answered"}"#), .end(status: "COMPLETED", stop: "answered"))
        XCTAssertEqual(e("error", #"{"error":"x"}"#), .broken)
        XCTAssertNil(e("delta", #"{"kind":"tool_result"}"#))
        XCTAssertEqual(AppModel.activityWords("kiff_draft"), "Preparing the Card…")
        XCTAssertNil(AppModel.activityWords("show"))
    }

    /// Setup's Markdown is drawn, not shown as symbols (#58 B5).
    func testMarkdownIsDrawn() {
        let blocks = MarkdownBlock.parse("## The plan\nI will **connect** Stripe,\nthen:\n\n- read its `tools`\n2. propose rules\n\n```\nPostRefunds\n```")
        XCTAssertEqual(blocks.map(\.kind), [.heading, .paragraph, .bullet, .numbered(2), .code])
        let text = blocks.map { String($0.text.characters) }
        XCTAssertEqual(text, ["The plan", "I will connect Stripe,\nthen:", "read its tools", "propose rules", "PostRefunds"])
        XCTAssertFalse(text.joined().contains("*"), "no symbol is left")
        XCTAssertEqual(MarkdownText("**ab** cd").length, 5)
    }

    /// An approval answers what happened: approved and signed in for a connection, approved
    /// for the Cards of a connected one; a confirm answers with its value.
    func testAnswersFollowContractOne() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false}"#)
        await model.loadSetup()

        let pending = PlanqueTests.grantJSONFor(status: "pending")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(pending)]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/a","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, PlanqueTests.grantJSONFor(status: "connected"))
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        await model.refreshConnections()
        let connect = try SetupTests.component(#"{"type":"approval","id":"connect-stripe","grant":"g_01"}"#)
        await model.approve(connect)
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").last)["text"] as? String,
                       "[approval connect-stripe] approved, signed in")

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "connected"))]}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_01"] = (200, #"{"id":"d_01","agent_id":"refunds","status":"draft","version":"v3"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_01/confirm"] = (200, #"{"id":"d_01","agent_id":"refunds","status":"confirmed","version":"v3"}"#)
        await model.refreshConnections()
        let cards = try SetupTests.component(#"{"type":"approval","id":"refunds-card","grant":"g_01","draft":"d_01"}"#)
        await model.approve(cards)
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").last)["text"] as? String,
                       "[approval refunds-card] approved")
        XCTAssertEqual(signer.prompts.count, 2, "one Face ID per approval")

        let which = try SetupTests.component(#"{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]}"#)
        await model.answer(which, "test")
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").last)["text"] as? String, "[confirm which] test")
        XCTAssertTrue(model.answered.contains("which"))
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Connect first (contract 5 on kiff/agents#91): the first approval signs in and planque
    /// only lists the vendor's tools; the second approves the tools Setup chose, with no
    /// second sign-in. Each answers what happened, and a held sign-in shows on the Card tab.
    func testConnectFirstThenApproveTheTools() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false}"#)
        await model.loadSetup()

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "pending"))]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/a","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, PlanqueTests.grantJSONFor(status: "listed", signedIn: true))
        StubProtocol.thenRespond["POST /v1/accounts/t_01/grants/g_01/confirm"] = [
            "GET /v1/accounts/t_01/grants": (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "listed", signedIn: true))]}"#)]
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        await model.refreshConnections()
        await model.approve(try SetupTests.component(#"{"type":"approval","id":"connect-stripe","grant":"g_01"}"#))
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").last)["text"] as? String,
                       "[approval connect-stripe] approved, signed in", "a listed grant is an answer too")
        XCTAssertTrue(model.grants.first?.listed ?? false)
        XCTAssertTrue(model.grants.first?.holdsSignIn ?? false)

        // Setup set the tools: pending again, the sign-in held. Approving connects at once.
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "pending", signedIn: true))]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, PlanqueTests.grantJSONFor(status: "connected", signedIn: true))
        StubProtocol.thenRespond["POST /v1/accounts/t_01/grants/g_01/approve"] = [
            "GET /v1/accounts/t_01/grants": (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "connected", signedIn: true))]}"#)]
        await model.refreshConnections()
        XCTAssertTrue(model.grants.first?.holdsSignIn ?? false, "a pending grant with a held sign-in is shown")
        StubProtocol.sent = []
        await model.approve(try SetupTests.component(#"{"type":"approval","id":"stripe-tools","grant":"g_01"}"#))
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").last)["text"] as? String,
                       "[approval stripe-tools] approved", "no sign-in, so the answer doesn't claim one")
        XCTAssertEqual(vendor.opened.count, 1, "no second sign-in")
        XCTAssertTrue(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/confirm").isEmpty)
        XCTAssertEqual(signer.prompts.count, 2, "one Face ID per approval")
        XCTAssertTrue(model.grants.first?.connected ?? false)
        XCTAssertNil(model.lastError)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// The hand-over starts the taf Setup made, once: tafs cloud stores it from Setup's
    /// draft (kiff/agents claims the key), and the phone refreshes to show it.
    func testResultStartsTheTafOnce() async throws {
        let model = await model(signer: FaceIDSigner())
        StubProtocol.responses["POST /v1/tafs"] = (200, #"{"taf":{"id":"taf-new","name":"Refunds","job":"refunds","kiff_agent":"refunds-x","status":"active","next_run":null,"last_run":null},"session":"s2"}"#)
        let result = try SetupTests.component(#"{"type":"result","id":"done","taf":{"agent":"refunds-x","name":"Refunds","job":"refunds","draft":"drf_s1"},"text":"Refunds can refund within its Card.","tools":["refund_payment"]}"#)
        StubProtocol.sent = []
        await model.startSetupTaf(result)
        let start = try XCTUnwrap(sent("POST", "cloud.test", "/v1/tafs").first)
        XCTAssertEqual(body(start)["from_setup"] as? Bool, true)
        XCTAssertEqual(body(start)["draft_id"] as? String, "drf_s1")
        XCTAssertEqual(body(start)["job"] as? String, "refunds")
        XCTAssertEqual(body(start)["name"] as? String, "Refunds")
        XCTAssertNil(body(start)["claim_secret"], "the phone holds no claim secret for Setup's draft")
        XCTAssertFalse(sent("GET", "cloud.test", "/v1/tafs").isEmpty, "then it refreshes")
        XCTAssertNil(model.tafStartErrors["done"])

        // A result without a job or a draft starts nothing.
        StubProtocol.sent = []
        let bare = try SetupTests.component(#"{"type":"result","id":"bare","taf":{"agent":"refunds-x","name":"Refunds"},"text":"Done."}"#)
        await model.startSetupTaf(bare)
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs").isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Stop cancels the run of the thread's session.
    func testStopNamesTheSession() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[{"at":"2026-10-17T10:00:00.000Z","from":"owner","kind":"message","text":"hi","session":"s9"}],"components":[]}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/stop"] = (200, #"{"stopped":true,"run":"r9","status":"CANCELLED"}"#)
        await model.loadSetup()
        await model.stopSetup()
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/stop").first) as? [String: String], ["session": "s9"])
        Keychain.delete(AppModel.planqueAccount)
    }

    static func component(_ json: String) throws -> SetupComponent {
        try JSONDecoder().decode(SetupComponent.self, from: Data(json.utf8))
    }
}
