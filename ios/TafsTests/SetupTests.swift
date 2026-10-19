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
        // A stream the test drives: it says its chat, and stays open until the test ends it.
        var continuation: AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation?
        let events = AsyncThrowingStream<TafsCloud.StreamEvent, Error> { continuation = $0 }
        let stream = try XCTUnwrap(continuation)
        model.streamOpener = { _, _ in events }
        StubProtocol.responses["POST /v1/tafs/taf-setup/stop"] = (200, #"{"stopped":true}"#)
        let delivered = await model.sendSetup("start over")
        XCTAssertTrue(delivered)
        stream.yield(.session("s_new", newSession: true))
        for _ in 0..<2000 where model.setupLive?.session == nil { try await Task.sleep(nanoseconds: 1_000_000) }
        XCTAssertEqual(model.setupLive?.session, "s_new")
        await model.stopSetup()
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/stop").last) as? [String: String], ["session": "s_new"],
                       "the new chat, not s_old")
        stream.yield(.end(status: "CANCELLED", stop: "cancelled"))
        stream.finish()
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
        XCTAssertEqual(model.setupUnsent.map(\.text), ["again"], "kept, marked not sent")
        XCTAssertEqual(model.setupUnsent.first?.why, "that's 10 messages in 10 minutes")
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

    /// The hand-over starts its taf when it arrives, wherever the owner is (kiff/tafs#96):
    /// no result card on screen needed, so the new taf reaches the list.
    func testAHandOverStartsItsTafWithoutTheCardOnScreen() async throws {
        let model = await model(signer: FaceIDSigner())
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["POST /v1/tafs"] = (200, #"{"taf":{"id":"taf-new","name":"Refunds","job":"refunds","kiff_agent":"refunds-x","status":"active","next_run":null,"last_run":null},"session":"s2"}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[],"components":[
          {"at":"2026-10-17T10:00:06.000Z","session":"s1","run":"r1","seq":6,"component":{"type":"result","id":"done","taf":{"agent":"refunds-x","name":"Refunds","job":"refunds","draft":"drf_s1"},"text":"Done."}}]}
        """)
        StubProtocol.sent = []
        await model.loadSetup()
        for _ in 0..<50 where sent("POST", "cloud.test", "/v1/tafs").isEmpty { await Task.yield(); try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/tafs").count, 1, "started once, by the model")
        XCTAssertEqual(body(try XCTUnwrap(sent("POST", "cloud.test", "/v1/tafs").first))["draft_id"] as? String, "drf_s1")
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

    /// A message that isn't sent stays in the thread, marked not sent with why, and Retry
    /// sends it (#58 B1). An answer's card can be answered again until it's sent.
    func testAMessageThatIsntSentStaysWithRetry() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        await model.loadSetup()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = (429, #"{"error":"that's 10 messages in 10 minutes"}"#)
        let which = try SetupTests.component(#"{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]}"#)
        await model.answer(which, "live")
        XCTAssertEqual(model.setupUnsent.map(\.text), ["[confirm which] live"])
        XCTAssertEqual(model.setupUnsent.first?.why, "that's 10 messages in 10 minutes")
        XCTAssertFalse(model.answered.contains("which"), "the card can be answered again")
        XCTAssertFalse(model.setupThread.messages.contains { $0.session == "pending" })
        XCTAssertNil(model.setupError, "the message says it, not a line under the thread")

        // Retry, with tafs cloud taking it: sent once, and the card goes quiet.
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = nil
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false}"#)
        StubProtocol.sent = []
        await model.retrySetup(try XCTUnwrap(model.setupUnsent.first))
        XCTAssertTrue(model.setupUnsent.isEmpty)
        XCTAssertTrue(model.answered.contains("which"))
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").map { body($0)["text"] as? String }, ["[confirm which] live"])

        // Deleting one that isn't sent drops it.
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (503, #"{"error":"down"}"#)
        await model.sendSetup("later")
        model.discardUnsent(try XCTUnwrap(model.setupUnsent.first))
        XCTAssertTrue(model.setupUnsent.isEmpty)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Retry never loses the message: one that can't join the queue (too long with it)
    /// stays where it was. An answer sent from its card drops the earlier one that
    /// wasn't sent, and a Retry of an answer the card has since had sends nothing.
    func testRetryKeepsTheMessageAndAnswersOnce() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        await model.loadSetup()
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = (429, #"{"error":"that's 10 messages in 10 minutes"}"#)
        let short = String(repeating: "x", count: 100)
        await model.sendSetup(short)
        XCTAssertEqual(model.setupUnsent.map(\.text), [short])

        // Setup at work, with 1,950 characters queued: the retry can't join them.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":{"session":"s1","run":"r1"}}"#)
        await model.loadSetupThread()
        let queued = await model.submitSetup(String(repeating: "y", count: 1950))
        XCTAssertTrue(queued)
        await model.retrySetup(try XCTUnwrap(model.setupUnsent.first))
        XCTAssertEqual(model.setupUnsent.map(\.text), [short], "still there, not lost")
        _ = model.unqueueSetup()
        model.discardUnsent(try XCTUnwrap(model.setupUnsent.first))

        // An answer that isn't sent, then the card answered again and sent.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":null}"#)
        await model.loadSetupThread()
        let which = try SetupTests.component(#"{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]}"#)
        await model.answer(which, "live")
        XCTAssertEqual(model.setupUnsent.map(\.text), ["[confirm which] live"])
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = nil
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false}"#)
        StubProtocol.sent = []
        await model.answer(which, "test")
        XCTAssertTrue(model.setupUnsent.isEmpty, "the earlier answer goes")
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").map { body($0)["text"] as? String }, ["[confirm which] test"])

        // A Retry of an answer the thread already has sends nothing.
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (503, #"{"error":"down"}"#)
        let signIn = try SetupTests.component(#"{"type":"sign_in","id":"in","service":"mcp.stripe.com","grant":"g_01"}"#)
        await model.answer(signIn, "not now")
        let unsent = try XCTUnwrap(model.setupUnsent.first)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[{"at":"2026-10-17T10:00:00.000Z","from":"owner","kind":"message","text":"[sign_in in] not now","session":"s1"}],"components":[],"working":null}
        """)
        await model.loadSetupThread()
        StubProtocol.sent = []
        await model.retrySetup(unsent)
        XCTAssertTrue(model.setupUnsent.isEmpty)
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty, "not answered twice")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// What the owner writes while Setup works waits, and is sent once Setup is done, as
    /// one message (#58 B1).
    func testWhatsWrittenWhileSetupWorksIsSentWhenItsDone() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":{"session":"s1","run":"r1"}}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false}"#)
        await model.loadSetup()
        XCTAssertTrue(model.setupThinking)
        StubProtocol.sent = []
        let first = await model.submitSetup("Use the test account")
        let second = await model.submitSetup("and refunds under 50")
        XCTAssertTrue(first)
        XCTAssertTrue(second)
        XCTAssertEqual(model.setupQueued, "Use the test account\n\nand refunds under 50")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty, "nothing sent while Setup works")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages/stream").isEmpty)

        // Setup is done: what waited is sent, once.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":null}"#)
        await model.loadSetupThread()
        XCTAssertNil(model.setupQueued)
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").map { body($0)["text"] as? String },
                       ["Use the test account\n\nand refunds under 50"])

        // Taken back, it isn't sent.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":{"session":"s1","run":"r2"}}"#)
        await model.loadSetupThread()
        await model.submitSetup("never mind")
        XCTAssertEqual(model.unqueueSetup(), "never mind")
        XCTAssertNil(model.setupQueued)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A run that fails part-way says so and offers Retry, which sends its message again; a
    /// stopped run is marked stopped (#58 B1, B8).
    func testARunThatFailsPartWayOffersRetry() async throws {
        final class Sent { var texts: [String] = [] }
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        await model.loadSetup()
        let sent = Sent()
        var ending = "ERROR"
        model.streamOpener = { _, text in
            sent.texts.append(text)
            let status = ending
            return AsyncThrowingStream { c in
                c.yield(.session("s1", newSession: true))
                c.yield(.text("Connecting "))
                c.yield(.end(status: status, stop: status.lowercased()))
                c.finish()
            }
        }
        let delivered = await model.sendSetup("Connect my Stripe")
        XCTAssertTrue(delivered)
        await model.setupStream?.value
        XCTAssertEqual(model.setupEnded, .failed(retry: "Connect my Stripe"))
        XCTAssertFalse(model.setupThinking, "the run is over")

        await model.retrySetupRun()
        await model.setupStream?.value
        XCTAssertEqual(sent.texts, ["Connect my Stripe", "Connect my Stripe"])

        ending = "CANCELLED"
        await model.sendSetup("again")
        await model.setupStream?.value
        XCTAssertEqual(model.setupEnded, .stopped)

        ending = "COMPLETED"
        await model.sendSetup("thanks")
        await model.setupStream?.value
        XCTAssertNil(model.setupEnded)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// The owner's answers read as what they chose, and each card knows where it stands:
    /// the one waiting for the owner, then declined, failed with why, or done (#58 B4, B7).
    func testCardsKnowWhereTheyStand() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "pending"))]}"#)
        func conversation(_ answers: [String]) -> String {
            let owner = answers.enumerated().map { i, text in
                #",{"at":"2026-10-17T10:00:1\#(i).000Z","from":"owner","kind":"message","text":"\#(text)","session":"s1"}"#
            }.joined()
            return """
            {"messages":[{"at":"2026-10-17T10:00:00.000Z","from":"owner","kind":"message","text":"Refunds, please","session":"s1"},
                         {"at":"2026-10-17T10:00:03.000Z","from":"taf","kind":"message","text":"Pick one, then approve.","session":"s1"}\(owner)],
             "components":[
              {"at":"2026-10-17T10:00:02.000Z","session":"s1","run":"r1","seq":2,"component":{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]}},
              {"at":"2026-10-17T10:00:02.000Z","session":"s1","run":"r1","seq":3,"component":{"type":"approval","id":"connect","grant":"g_01"}}]}
            """
        }
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, conversation([]))
        await model.loadSetup()
        let connect = try SetupTests.component(#"{"type":"approval","id":"connect","grant":"g_01"}"#)
        XCTAssertEqual(model.setupThread.asks.map(\.id), ["which", "connect"])
        XCTAssertEqual(model.setupWaiting?.id, "connect", "the latest card waiting")
        XCTAssertEqual(model.setupCardState(connect), .needsYou)

        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, conversation(["[confirm which] live"]))
        await model.loadSetupThread()
        XCTAssertEqual(model.setupWaiting?.id, "connect", "an approval waits though the owner wrote since")
        XCTAssertEqual(model.setupThread.ownerWords("[confirm which] live"), "Live")
        XCTAssertEqual(model.setupThread.ownerWords("[approval connect] declined"), "Decline")
        XCTAssertEqual(model.setupThread.ownerWords("[approval connect] approved, signed in"), "Approve")
        XCTAssertEqual(model.setupThread.ownerWords("[sign_in in] not now"), "Not now")
        XCTAssertEqual(model.setupThread.ownerWords("[hologram x] y"), "[hologram x] y", "not an answer to a known kind")
        XCTAssertEqual(model.setupThread.ownerWords("Refunds, please"), "Refunds, please")

        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, conversation(["[confirm which] live", "[approval connect] declined"]))
        await model.loadSetupThread()
        XCTAssertEqual(model.setupCardState(connect), .declined)
        XCTAssertNil(model.setupWaiting)

        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, conversation(["[confirm which] live", "[approval connect] failed: planque is down"]))
        await model.loadSetupThread()
        XCTAssertEqual(model.setupCardState(connect), .failed("planque is down"))
        XCTAssertEqual(model.setupThread.ownerWords("[approval connect] failed: planque is down"), "Approve · it didn't go through")

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "connected"))]}"#)
        await model.refreshConnections()
        XCTAssertEqual(model.setupCardState(connect), .done, "connected is done, whatever was answered")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A new conversation (#73): the thread is empty and nothing is read until the
    /// first message, which asks for a new chat; that chat is the one shown from then on.
    func testANewConversationStartsItsOwnChat() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[{"at":"2026-10-18T09:00:00.000Z","from":"owner","kind":"message","text":"An older topic","session":"s_old"}],"components":[]}
        """)
        await model.loadSetup()
        XCTAssertEqual(model.setupThread.messages.count, 1)

        model.newSetupChat()
        XCTAssertTrue(model.setupThread.messages.isEmpty)
        StubProtocol.sent = []
        await model.loadSetupThread()
        XCTAssertTrue(sent("GET", "cloud.test", "/v1/tafs/taf-setup/conversation").isEmpty, "nothing to read before the first message")

        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s_new","new_session":true}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s_new"] = (200, #"{"messages":[],"components":[]}"#)
        let delivered = await model.sendSetup("Connect my Shopify")
        XCTAssertTrue(delivered)
        let post = try XCTUnwrap(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").last)
        XCTAssertEqual(body(post)["new_chat"] as? Bool, true)
        XCTAssertEqual(body(post)["text"] as? String, "Connect my Shopify")
        XCTAssertEqual(model.setupChat, "s_new")
        XCTAssertFalse(model.setupFresh)
        XCTAssertFalse(sent("GET", "cloud.test", "/v1/tafs/taf-setup/conversation/s_new").isEmpty, "then the new chat alone is read")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A past conversation, reopened (#73): it's read alone, and messages go on in it.
    func testAPastConversationReopens() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/chats"] = (200, """
        {"chats":[{"session":"s_2","started":"2026-10-18T10:00:00.000Z","updated":"2026-10-18T10:05:00.000Z","title":"Answer customer questions"},
                  {"session":"s_1","started":"2026-10-17T09:00:00.000Z","updated":"2026-10-17T09:30:00.000Z","title":"Connect my Stripe"}]}
        """)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s_1"] = (200, """
        {"messages":[{"at":"2026-10-17T09:00:00.000Z","from":"owner","kind":"message","text":"Connect my Stripe","session":"s_1"}],"components":[]}
        """)
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s_1","new_session":false}"#)
        await model.loadSetup()
        await model.loadSetupChats()
        XCTAssertEqual(model.setupChats.map(\.title), ["Answer customer questions", "Connect my Stripe"])

        await model.openSetupChat("s_1")
        XCTAssertEqual(model.setupThread.messages.map(\.text), ["Connect my Stripe"])
        StubProtocol.sent = []
        await model.sendSetup("Now with test mode")
        let post = try XCTUnwrap(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").last)
        XCTAssertEqual(body(post)["session"] as? String, "s_1")
        XCTAssertNil(body(post)["new_chat"], "a reopened chat isn't a new one")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A tapped choice shows at once as the owner's message (#82), before tafs cloud
    /// answers; a reload that doesn't have it yet keeps it, and tafs cloud's copy takes
    /// its place without showing it twice.
    func testATappedChoiceShowsAtOnceAndOnce() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        await model.loadSetup()
        // A stream the test holds open: Setup hasn't answered, nor recorded the message.
        var continuation: AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation?
        let events = AsyncThrowingStream<TafsCloud.StreamEvent, Error> { continuation = $0 }
        let stream = try XCTUnwrap(continuation)
        model.streamOpener = { _, _ in events }
        let which = try SetupTests.component(#"{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]}"#)
        await model.answer(which, "live")
        let mine = { model.setupThread.messages.filter { $0.from == .owner && $0.text == "[confirm which] live" } }
        XCTAssertEqual(mine().count, 1, "shown as soon as it's tapped")
        XCTAssertEqual(model.setupThread.answer(to: "which")?.text, "live", "the card shows the choice")

        // A reload while it's on its way: tafs cloud doesn't have it yet, and it stays.
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":null}"#)
        await model.loadSetupThread()
        XCTAssertEqual(mine().count, 1, "a reload doesn't drop it")

        // tafs cloud has it: its copy, once.
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let at = iso.string(from: Date())
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[{"at":"\(at)","from":"owner","kind":"message","text":"[confirm which] live","session":"s1"}],"components":[],"working":null}
        """)
        await model.loadSetupThread()
        XCTAssertEqual(mine().map(\.session), ["s1"], "tafs cloud's copy, not twice")
        await model.loadSetupThread()
        XCTAssertEqual(mine().count, 1)
        stream.yield(.end(status: "COMPLETED", stop: "answered"))
        stream.finish()
        await model.setupStream?.value
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Streams the test holds open, one per send, so Setup never answers or records.
    private final class OpenStreams {
        var continuations: [AsyncThrowingStream<TafsCloud.StreamEvent, Error>.Continuation] = []
        func open() -> AsyncThrowingStream<TafsCloud.StreamEvent, Error> {
            AsyncThrowingStream { self.continuations.append($0) }
        }
        func close() {
            for c in continuations { c.yield(.end(status: "COMPLETED", stop: "answered")); c.finish() }
        }
    }

    /// A sent message shows last, even when the phone's clock is behind tafs cloud's (the
    /// #84 review): it's stamped no earlier than the thread's latest item.
    func testASentMessageShowsLastWhenThePhoneClockIsBehind() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        // tafs cloud's time is a minute ahead of the phone's.
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let ahead = iso.string(from: Date().addingTimeInterval(60))
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[{"at":"\(ahead)","from":"taf","kind":"message","text":"¿Prueba o real?","session":"s1"}],"components":[],"working":null}
        """)
        await model.loadSetup()
        await model.loadSetupThread()
        let streams = OpenStreams()
        model.streamOpener = { _, _ in streams.open() }
        await model.sendSetup("Prueba")
        guard case .message(let last)? = model.setupThread.items.last else { return XCTFail("no message last") }
        XCTAssertEqual(last.text, "Prueba", "the owner's message is last, under Setup's question")
        streams.close()
        await model.setupStream?.value
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Two identical messages sent close together stay two until tafs cloud has both (the
    /// #84 review): one copy takes the place of one sent message.
    func testTwoIdenticalMessagesStayTwo() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        await model.loadSetup()
        let streams = OpenStreams()
        model.streamOpener = { _, _ in streams.open() }
        await model.sendSetup("Sí")
        await model.sendSetup("Sí")
        let mine = { model.setupThread.messages.filter { $0.from == .owner && $0.text == "Sí" } }
        XCTAssertEqual(mine().count, 2)

        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let at = iso.string(from: Date())
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[{"at":"\(at)","from":"owner","kind":"message","text":"Sí","session":"s1"}],"components":[],"working":null}
        """)
        await model.loadSetupThread()
        XCTAssertEqual(mine().map(\.session).sorted(), ["pending", "s1"], "tafs cloud has one: the other still shows")

        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[{"at":"\(at)","from":"owner","kind":"message","text":"Sí","session":"s1"},
                     {"at":"\(at)","from":"owner","kind":"message","text":"Sí","session":"s1"}],"components":[],"working":null}
        """)
        await model.loadSetupThread()
        XCTAssertEqual(mine().map(\.session), ["s1", "s1"], "both copies, none twice")
        streams.close()
        await model.setupStream?.value
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A message on its way to tafs cloud isn't the thread's session: Stop and the like
    /// name the session tafs cloud gave (#82).
    func testAMessageOnItsWayHasNoSession() {
        var thread = SetupThread()
        thread.messages = [
            TafsCloud.ChatMessage(at: Date(timeIntervalSince1970: 1_800_000_000), from: .owner, text: "hi", session: "s9"),
            TafsCloud.ChatMessage(at: Date(timeIntervalSince1970: 1_800_000_060), from: .owner, text: "again", session: "pending"),
        ]
        XCTAssertEqual(thread.session, "s9")
    }

    /// Archiving a conversation (#82) asks tafs cloud, so it stays archived on every
    /// phone: it leaves the list, and the one shown gives way to a new conversation.
    /// One tafs cloud didn't archive stays, with why.
    func testArchivingAConversation() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/chats"] = (200, """
        {"chats":[{"session":"s_2","started":"2026-10-18T10:00:00.000Z","updated":"2026-10-18T10:05:00.000Z","title":"Answer customer questions"},
                  {"session":"s_1","started":"2026-10-17T09:00:00.000Z","updated":"2026-10-17T09:30:00.000Z","title":"Connect my Stripe"}]}
        """)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s_2"] = (200, """
        {"messages":[{"at":"2026-10-18T10:00:00.000Z","from":"owner","kind":"message","text":"Answer customer questions","session":"s_2"}],"components":[]}
        """)
        await model.loadSetup()
        await model.loadSetupChats()
        await model.openSetupChat("s_2")

        StubProtocol.responses["POST /v1/tafs/taf-setup/chats/s_2/archive"] = (200, #"{"session":"s_2","archived":true}"#)
        StubProtocol.sent = []
        let archived = await model.archiveSetupChat("s_2")
        XCTAssertTrue(archived)
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/tafs/taf-setup/chats/s_2/archive").count, 1, "kept on tafs cloud")
        XCTAssertEqual(model.setupChats.map(\.session), ["s_1"])
        XCTAssertNil(model.setupChat, "the archived one isn't shown")
        XCTAssertTrue(model.setupFresh, "a new conversation instead")
        XCTAssertTrue(model.setupThread.messages.isEmpty)

        StubProtocol.responses["POST /v1/tafs/taf-setup/chats/s_1/archive"] = (503, #"{"error":"tafs cloud is down"}"#)
        let refused = await model.archiveSetupChat("s_1")
        XCTAssertFalse(refused)
        XCTAssertEqual(model.setupChats.map(\.session), ["s_1"], "still listed")
        XCTAssertEqual(model.lastError, "Not archived: tafs cloud is down")
        Keychain.delete(AppModel.planqueAccount)
    }

    static func component(_ json: String) throws -> SetupComponent {
        try JSONDecoder().decode(SetupComponent.self, from: Data(json.utf8))
    }
}
