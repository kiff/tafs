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
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":null}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
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

    /// Coach mode's Start also points KIFF's alerts at tafs cloud, with the same one Face ID
    /// (kiff/tafs#206): a paused run hears the owner's answer with no phone's alerts on.
    /// Kept already, nothing is registered again.
    func testStartSetupKeepsKIFFsAlertsWithTheSameFaceID() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer)
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"ab12"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_s","agent_id":"setup","status":"confirmed","version":"v1","claim_secret":"kiff_claim_x","no_tools":true,"cards":[]}"#)
        StubProtocol.responses["POST /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/alerts"] = (200, #"{"alerts":false}"#)
        StubProtocol.responses["POST /v1/me/alert-targets"] = (201, #"{"id":"at_1","signing_secret":"whsec_abcdefghijklmnopqrstuv"}"#)
        StubProtocol.responses["POST /v1/alerts"] = (200, #"{"alerts":true}"#)

        let started = await model.startSetup()
        XCTAssertTrue(started, model.setupError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.setup.v1|t_01"], "no Face ID of its own")
        let target = try XCTUnwrap(sent("POST", "api.test", "/v1/me/alert-targets").first)
        XCTAssertEqual(body(target)["url"] as? String, "https://cloud.test/v1/kiff/alerts")
        XCTAssertNotNil(target.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        let kept = try XCTUnwrap(sent("POST", "cloud.test", "/v1/alerts").first)
        XCTAssertEqual(body(kept)["alert_target"] as? [String: String], ["id": "at_1", "signing_secret": "whsec_abcdefghijklmnopqrstuv"])

        // An account whose alerts are kept already registers nothing.
        let other = await self.model(signer: FaceIDSigner())
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"ab12"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_s","agent_id":"setup","status":"confirmed","version":"v1","claim_secret":"kiff_claim_x","no_tools":true,"cards":[]}"#)
        StubProtocol.responses["POST /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/alerts"] = (200, #"{"alerts":true}"#)
        _ = await other.startSetup()
        XCTAssertTrue(sent("POST", "api.test", "/v1/me/alert-targets").isEmpty)
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

    /// Improving a taf (kiff/tafs#107): opened from the taf's page, the conversation's first
    /// message goes to tafs cloud's improve route for that taf, which adds its context; the
    /// conversation then goes on as any other.
    func testImprovingATafSendsItsFirstMessageThroughImprove() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        await model.refresh()
        await model.loadSetup()
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" })
        StubProtocol.responses["POST /v1/tafs/depl_1/improve"] = (200, #"{"setup":"taf-setup","session":"s_imp","new_session":true}"#)
        model.improveTaf(rae.id)
        XCTAssertEqual(model.setupImproving?.id, "depl_1")
        let delivered = await model.sendSetup("How is it going?")
        XCTAssertTrue(delivered)
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/depl_1/improve").last) as? [String: String], ["text": "How is it going?"])
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty, "not as a plain Setup message")
        XCTAssertNil(model.setupImproving, "only the first message carries the taf")
        model.newSetupChat()
        XCTAssertNil(model.setupImproving)
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
        XCTAssertEqual(e("session", #"{"session":"s1","new_session":false,"run":"r2","message":{"id":"r2.in","at":"2026-10-28T09:00:00Z"}}"#),
                       .session("s1", newSession: false, run: "r2", message: "r2.in"))
        XCTAssertEqual(e("text", #"{"text":"a\nb"}"#), .text("a\nb"))
        XCTAssertEqual(e("activity", #"{"tool":"planque"}"#), .activity("planque"))
        // What Coach is doing, in tafs cloud's words (kiff/tafs#214).
        XCTAssertEqual(e("activity", #"{"tool":"planque","step":"Checking your Stripe connection…"}"#),
                       .activity("planque", step: "Checking your Stripe connection…"))
        XCTAssertEqual(e("activity", #"{"tool":"get_order","call_id":"c1"}"#), .activity("get_order", call: "c1"))
        // How a step ended, what the taf thinks, a message a resumed run wrote (kiff/tafs#285).
        XCTAssertEqual(e("step", #"{"call_id":"c1","status":"error"}"#), .step(call: "c1", status: "error"))
        XCTAssertEqual(e("thinking", #"{"text":"The order is paid."}"#), .thinking("The order is paid."))
        XCTAssertEqual(e("said", "{}"), .said)
        XCTAssertNil(e("step", "{}"))
        XCTAssertEqual(e("component", #"{"component":{}}"#), .component)
        XCTAssertEqual(e("end", #"{"status":"COMPLETED","stop":"answered"}"#), .end(status: "COMPLETED", stop: "answered"))
        // A failed run says what went through before it stopped (kiff/agents#249).
        XCTAssertEqual(e("end", #"{"status":"ERROR","stop":"","done":[{"tool":"create_refund","resource":"ch_1","invocation":"tc-1"},{"tool":"send_email"},{}]}"#),
                       .end(status: "ERROR", stop: "", done: [.init(tool: "create_refund", resource: "ch_1", invocation: "tc-1"), .init(tool: "send_email", resource: nil)]))
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

    /// An approval answers what happened, as data on the card's own route: approved for a
    /// connection and for the Cards of a connected one; a confirm answers with its value.
    func testAnswersGoAsDataOnTheCardsRoute() async throws {
        let signer = FaceIDSigner()
        let vendor = StubVendor()
        let model = await model(signer: signer, vendor: vendor)
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        for id in ["connect-stripe", "refunds-card", "which"] {
            StubProtocol.responses["POST /v1/tafs/taf-setup/components/\(id)/answer"] = (200, #"{"session":"s1","run":"r1","status":"RUNNING"}"#)
        }
        await model.loadSetup()

        let pending = PlanqueTests.grantJSONFor(status: "pending")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(pending)]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/a","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, PlanqueTests.grantJSONFor(status: "connected"))
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        await model.refreshConnections()
        let connect = try SetupTests.component(#"{"type":"approval","id":"connect-stripe","grant":"g_01"}"#)
        await model.approve(connect, shown: model.grants.first?.digest, shownDraft: nil)
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/components/connect-stripe/answer").last)["answer"] as? String, "approved")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty, "never a message in the old form")

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "connected"))]}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_01"] = (200, #"{"id":"d_01","agent_id":"refunds","status":"draft","version":"v3"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_01/confirm"] = (200, #"{"id":"d_01","agent_id":"refunds","status":"confirmed","version":"v3"}"#)
        await model.refreshConnections()
        let cards = try SetupTests.component(#"{"type":"approval","id":"refunds-card","grant":"g_01","draft":"d_01"}"#)
        await model.approve(cards, shown: model.grants.first?.digest, shownDraft: "v3")
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/components/refunds-card/answer").last)["answer"] as? String, "approved")
        XCTAssertEqual(signer.prompts.count, 2, "one Face ID per approval")

        let which = try SetupTests.component(#"{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]}"#)
        await model.answer(which, "test")
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/components/which/answer").last)["answer"] as? String, "test")
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
        for id in ["connect-stripe", "stripe-tools"] {
            StubProtocol.responses["POST /v1/tafs/taf-setup/components/\(id)/answer"] = (200, #"{"session":"s1","run":"r1","status":"RUNNING"}"#)
        }
        await model.loadSetup()

        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "pending"))]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/a","expires_at":"2026-10-17T10:15:00Z"}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_01/confirm"] = (200, PlanqueTests.grantJSONFor(status: "listed", signedIn: true))
        StubProtocol.thenRespond["POST /v1/accounts/t_01/grants/g_01/confirm"] = [
            "GET /v1/accounts/t_01/grants": (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "listed", signedIn: true))]}"#)]
        vendor.callback = URL(string: "tafs://planque/signed-in?tenant=t_01&grant=g_01&confirm=c0de")
        await model.refreshConnections()
        await model.approve(try SetupTests.component(#"{"type":"approval","id":"connect-stripe","grant":"g_01"}"#), shown: model.grants.first?.digest, shownDraft: nil)
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/components/connect-stripe/answer").last)["answer"] as? String,
                       "approved", "a listed grant is an answer too")
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
        await model.approve(try SetupTests.component(#"{"type":"approval","id":"stripe-tools","grant":"g_01"}"#), shown: model.grants.first?.digest, shownDraft: nil)
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/components/stripe-tools/answer").last)["answer"] as? String, "approved")
        XCTAssertEqual(vendor.opened.count, 1, "no second sign-in")
        XCTAssertTrue(sent("POST", "planque.test", "/v1/accounts/t_01/grants/g_01/confirm").isEmpty)
        XCTAssertEqual(signer.prompts.count, 2, "one Face ID per approval")
        XCTAssertTrue(model.grants.first?.connected ?? false)
        XCTAssertNil(model.lastError)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A new taf is tafs cloud's to set up: the phone never starts
    /// one from Coach's result, and its card says it's setting up until tafs cloud answers.
    func testTheHandOverIsTafsCloudsToMake() async throws {
        let model = await model(signer: FaceIDSigner())
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[],"components":[
          {"at":"2026-10-17T10:00:06.000Z","session":"s1","run":"r1","seq":6,"component":{"type":"result","id":"done","taf":{"agent":"refunds-x","name":"Refunds","job":"refunds","draft":"drf_s1"},"text":"Done."}}]}
        """)
        StubProtocol.sent = []
        await model.loadSetup()
        for _ in 0..<10 { await Task.yield(); try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs").isEmpty, "the phone starts no taf")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty, "nor tells Coach anything")
        XCTAssertEqual(model.setupThread.handOver("done", listed: false), .settingUp)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// The card draws tafs cloud's answer to the result: set up (the list is read again once
    /// for a taf it set up that isn't listed yet), or why it wasn't, with no Try again: Coach
    /// heard it from tafs cloud and hands it over again.
    func testTheHandOverCardShowsTafsCloudsAnswer() async throws {
        let model = await model(signer: FaceIDSigner())
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[],"components":[
          {"at":"2026-10-17T10:00:06.000Z","session":"s1","run":"r1","seq":6,"component":{"type":"result","id":"done","taf":{"agent":"refunds-x","name":"Refunds","job":"refunds","draft":"drf_s1"},"text":"Done."},
           "answer":{"state":"answered","words":"Set up","at":"2026-10-17T10:00:09.000Z","by":"tafs cloud","outcome":{"taf_id":"taf-new"}}},
          {"at":"2026-10-17T10:01:00.000Z","session":"s1","run":"r2","seq":9,"component":{"type":"result","id":"again","taf":{"agent":"orders-x","name":"Orders","job":"orders","draft":"drf_s2"},"text":"Done."},
           "answer":{"state":"answered","words":"It didn't go through","at":"2026-10-17T10:01:02.000Z","by":"tafs cloud","outcome":{"failed":"the account already has a taf named Orders."}}}]}
        """)
        StubProtocol.sent = []
        await model.loadSetup()
        let shown = try XCTUnwrap(model.setupThread.components.first { $0.id == "done" })
        XCTAssertEqual(shown.answer?.by, "tafs cloud")
        XCTAssertEqual(shown.answer?.outcome?.tafID, "taf-new")
        XCTAssertEqual(model.setupThread.handOver("done", listed: false), .setUp)
        XCTAssertEqual(model.setupThread.handOver("again", listed: false), .failed("the account already has a taf named Orders."))
        for _ in 0..<50 where sent("GET", "cloud.test", "/v1/home").isEmpty { await Task.yield(); try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertFalse(sent("GET", "cloud.test", "/v1/home").isEmpty, "the list is read again for the taf tafs cloud set up")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs").isEmpty)
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty, "Coach hears a failure from tafs cloud, not the phone")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// An answer the app can't read leaves its card unanswered, never the card out.
    func testAnUnreadableAnswerKeepsTheCard() throws {
        let json = #"{"at":"2026-10-17T10:00:06.000Z","session":"s1","run":"r1","component":{"type":"result","id":"done","text":"Done."},"answer":{"outcome":"set up"}}"#
        let shown = try KIFFClient.decoder.decode(TafsCloud.Shown.self, from: Data(json.utf8))
        XCTAssertEqual(shown.component.id, "done")
        XCTAssertNil(shown.answer?.outcome)
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
        await model.sendSetup("Use the test account")
        XCTAssertEqual(model.setupUnsent.map(\.text), ["Use the test account"])
        XCTAssertEqual(model.setupUnsent.first?.why, "that's 10 messages in 10 minutes")
        XCTAssertFalse(model.setupThread.messages.contains { $0.session == "pending" })
        XCTAssertNil(model.setupError, "the message says it, not a line under the thread")

        // Retry, with tafs cloud taking it: sent once.
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = nil
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages"] = (200, #"{"session":"s1","new_session":false}"#)
        StubProtocol.sent = []
        await model.retrySetup(try XCTUnwrap(model.setupUnsent.first))
        XCTAssertTrue(model.setupUnsent.isEmpty)
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").map { body($0)["text"] as? String }, ["Use the test account"])

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

        Keychain.delete(AppModel.planqueAccount)
    }

    /// A card's answer that doesn't go stays on the card, in the app's words, and the card can
    /// be answered again; one tafs cloud says the card no longer waits for (409) reads the thread
    /// again. Neither becomes a message, and an answer the thread already has isn't sent again.
    func testACardsAnswerThatDoesntGoStaysOnTheCard() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":null}"#)
        await model.loadSetup()
        let which = try SetupTests.component(#"{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]}"#)

        StubProtocol.responses["POST /v1/tafs/taf-setup/components/which/answer"] = (503, #"{"error":"agents is down"}"#)
        await model.answer(which, "live")
        XCTAssertFalse(model.answered.contains("which"), "the card can be answered again")
        XCTAssertEqual(model.cardNotes["which"], "Your answer didn't reach Setup. Tap it again.")
        XCTAssertTrue(model.setupUnsent.isEmpty, "never a message")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages").isEmpty)

        StubProtocol.responses["POST /v1/tafs/taf-setup/components/which/answer"] = (200, #"{"session":"s1","run":"r1","status":"RUNNING"}"#)
        StubProtocol.sent = []
        await model.answer(which, "test")
        XCTAssertNil(model.cardNotes["which"])
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/components/which/answer").last)["answer"] as? String, "test")

        // The card stopped waiting meanwhile: the thread is read again, nothing is kept.
        let signIn = try SetupTests.component(#"{"type":"sign_in","id":"in","service":"mcp.stripe.com","grant":"g_01"}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/components/in/answer"] = (409, #"{"error":"the card doesn't wait for an answer any more: read the conversation"}"#)
        StubProtocol.sent = []
        await model.answer(signIn, "not now")
        XCTAssertFalse(model.answered.contains("in"))
        XCTAssertFalse(sent("GET", "cloud.test", "/v1/tafs/taf-setup/conversation").isEmpty, "the thread read again")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A card's recorded answer draws it as answered (kiff/agents' words), whatever this phone
    /// sent: a confirm by its option, an approval, a sign-in's "not now", questions by their words.
    func testARecordedAnswerDrawsTheCard() throws {
        let confirm = try SetupTests.component(#"{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test mode"},{"value":"live","label":"Live"}]}"#)
        XCTAssertEqual(SetupThread.answer(TafsCloud.CardAnswer(words: "Test mode"), to: confirm)?.text, "test")
        let approval = try SetupTests.component(#"{"type":"approval","id":"a","grant":"g_01"}"#)
        XCTAssertEqual(SetupThread.answer(TafsCloud.CardAnswer(words: "Approved"), to: approval)?.text, "approved")
        XCTAssertEqual(SetupThread.answer(TafsCloud.CardAnswer(words: "Declined"), to: approval)?.text, "declined")
        let failed = SetupThread.answer(TafsCloud.CardAnswer(words: "Approve · it didn't go through: the grant expired"), to: approval)
        XCTAssertEqual(failed?.failed, true)
        XCTAssertEqual(failed?.why, "the grant expired")
        let signIn = try SetupTests.component(#"{"type":"sign_in","id":"in","service":"mcp.stripe.com","grant":"g_01"}"#)
        XCTAssertEqual(SetupThread.answer(TafsCloud.CardAnswer(words: "Not now"), to: signIn)?.text, "not now")
        let questions = try SetupTests.component(#"{"type":"questions","id":"q","questions":[]}"#)
        XCTAssertEqual(SetupThread.answer(TafsCloud.CardAnswer(words: "Test mode · Monday"), to: questions)?.words, "Test mode · Monday")
        XCTAssertNil(SetupThread.answer(TafsCloud.CardAnswer(state: "no_answer"), to: confirm), "it stopped waiting: not answered")
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

    /// Setup shows a second approval of a grant in the turn it changes the grant: a read
    /// between the two finds the grant as it was, signed in, and that says nothing of the new
    /// card (kiff/tafs#236, item 3). It waits for the owner until the grant changes after it.
    func testASecondApprovalIsntDoneByTheGrantAsItWas() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        // Listed at 10:00:00, before the second card (10:00:05): the read before Setup's change.
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "listed", signedIn: true))]}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
            {"messages":[{"at":"2026-10-17T10:00:00.000Z","from":"owner","kind":"message","text":"Refunds, please","session":"s1"},
                         {"at":"2026-10-17T10:00:03.000Z","from":"owner","kind":"message","text":"[approval connect] approved, signed in","session":"s1"}],
             "components":[
              {"at":"2026-10-17T09:59:58.000Z","session":"s1","run":"r1","seq":1,"component":{"type":"approval","id":"connect","grant":"g_01"}},
              {"at":"2026-10-17T10:00:05.000Z","session":"s1","run":"r2","seq":1,"component":{"type":"approval","id":"reads","grant":"g_01"}}]}
            """)
        await model.loadSetup()
        let reads = try SetupTests.component(#"{"type":"approval","id":"reads","grant":"g_01"}"#)
        let connect = try SetupTests.component(#"{"type":"approval","id":"connect","grant":"g_01"}"#)
        XCTAssertEqual(model.setupCardState(connect), .done, "the first card's answer says so")
        XCTAssertEqual(model.setupCardState(reads), .needsYou, "signed in before it, so not its approval")
        XCTAssertEqual(model.setupWaiting?.id, "reads")
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

    /// A second approval on a grant the app already holds (kiff/tafs#138): Setup asks to
    /// approve a read tool after the sign-in. The grant as read before is listed, which says
    /// nothing about this approval: the grant is read again, and the card waits for the owner.
    func testANewApprovalOnAKnownGrantWaitsForTheGrantReadAgain() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "listed", signedIn: true))]}"#)
        let connect = #"{"at":"2026-10-17T10:00:02.000Z","session":"s1","run":"r1","seq":2,"component":{"type":"approval","id":"connect","grant":"g_01"}}"#
        let messages = #"""
        [{"at":"2026-10-17T10:00:00.000Z","from":"owner","kind":"message","text":"Refunds, please","session":"s1"},
         {"at":"2026-10-17T10:00:05.000Z","from":"owner","kind":"message","text":"[approval connect] approved, signed in","session":"s1"}]
        """#
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":\#(messages),"components":[\#(connect)]}"#)
        await model.loadSetup()
        XCTAssertEqual(model.setupCardState(try SetupTests.component(#"{"type":"approval","id":"connect","grant":"g_01"}"#)), .done)

        // The read tool, on the same grant: planque has it waiting for the owner now.
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "pending"))]}"#)
        let read = #"{"at":"2026-10-17T10:01:00.000Z","session":"s1","run":"r2","seq":4,"component":{"type":"approval","id":"read","grant":"g_01"}}"#
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":\#(messages),"components":[\#(connect),\#(read)]}"#)
        await model.loadSetupThread()
        XCTAssertEqual(model.grants.first?.status, "pending", "the grant is read again for the new approval")
        XCTAssertEqual(model.setupCardState(try SetupTests.component(#"{"type":"approval","id":"read","grant":"g_01"}"#)), .needsYou)
        XCTAssertEqual(model.setupWaiting?.id, "read", "its Approve button shows")

        // Approved, then planque failed the grant at connect: the card says it didn't go
        // through, with planque's reason, though the answer reads approved.
        let answered = #"""
        [{"at":"2026-10-17T10:00:00.000Z","from":"owner","kind":"message","text":"Refunds, please","session":"s1"},
         {"at":"2026-10-17T10:00:05.000Z","from":"owner","kind":"message","text":"[approval connect] approved, signed in","session":"s1"},
         {"at":"2026-10-17T10:02:00.000Z","from":"owner","kind":"message","text":"[approval read] approved, signed in","session":"s1"}]
        """#
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":\#(answered),"components":[\#(connect),\#(read)]}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "failed", reason: "a read-only tool isn't read-only at the vendor"))]}"#)
        await model.loadSetupThread()
        await model.refreshConnections()
        XCTAssertEqual(model.setupCardState(try SetupTests.component(#"{"type":"approval","id":"read","grant":"g_01"}"#)),
                       .failed("a read-only tool isn't read-only at the vendor"))
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A sent message is found again by its words (kiff/tafs#138, review on #154): as stored,
    /// and as the end of a queued message it was joined to; never a different message.
    func testASentMessageIsFoundByItsWords() {
        XCTAssertTrue(SetupThread.matchesSent("look up pi_1", "look up pi_1"))
        XCTAssertTrue(SetupThread.matchesSent("first thing\n\nlook up pi_1", "look up pi_1"), "joined behind a queued one")
        XCTAssertFalse(SetupThread.matchesSent("look up pi_12", "look up pi_1"))
        XCTAssertFalse(SetupThread.matchesSent("look up pi_1 please", "look up pi_1"))
    }

    /// One connection's error shows on its newest card only (kiff/tafs#138, item 19): an older
    /// approval naming the same grant is replaced, so a failure doesn't spread to every card.
    /// A card on another grant, and the newest one, aren't.
    func testOnlyTheNewestCardForAConnectionIsLive() throws {
        let t0 = Date(timeIntervalSince1970: 1_792_500_000)
        func shown(_ json: String, _ at: TimeInterval) throws -> ShownComponent {
            ShownComponent(firstAt: t0.addingTimeInterval(at), session: "s1", run: "r1", component: try SetupTests.component(json))
        }
        var thread = SetupThread()
        thread.components = [
            try shown(#"{"type":"approval","id":"connect","grant":"g_01"}"#, 0),
            try shown(#"{"type":"approval","id":"read","grant":"g_01"}"#, 60),
            try shown(#"{"type":"approval","id":"other","grant":"g_02"}"#, 30),
            try shown(#"{"type":"confirm","id":"mode"}"#, 90),
        ]
        XCTAssertTrue(thread.replaced("connect"), "a newer approval names the same connection")
        XCTAssertFalse(thread.replaced("read"), "the newest card for it stays live")
        XCTAssertFalse(thread.replaced("other"), "another connection's card")
        XCTAssertFalse(thread.replaced("mode"), "a question names no connection")
    }

    /// A request refused for itself (kiff/tafs#138: a tool name already connected) isn't
    /// offered again unchanged: a 4xx is the request's own, a lapsed sign-in or a timeout
    /// isn't, and a grant planque failed can't be approved again either.
    func testARequestRefusedForItselfIsntSentAgainUnchanged() async throws {
        XCTAssertTrue(AppModel.refusesTheRequest(PlanqueClient.PlanqueError.server(status: 409, message: "a tool named read_payment is already connected")))
        XCTAssertTrue(AppModel.refusesTheRequest(PlanqueClient.PlanqueError.server(status: 422, message: "bad field")))
        for status in [401, 403, 408, 429, 500, 502] {
            XCTAssertFalse(AppModel.refusesTheRequest(PlanqueClient.PlanqueError.server(status: status, message: "")), "\(status) is worth trying again")
        }
        XCTAssertFalse(AppModel.refusesTheRequest(URLError(.timedOut)))

        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[{"at":"2026-10-17T10:00:02.000Z","session":"s1","run":"r1","seq":2,"component":{"type":"approval","id":"read","grant":"g_01"}}]}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "failed", reason: "a tool named read_payment is already connected"))]}"#)
        await model.loadSetup()
        XCTAssertTrue(model.requestRefused(try SetupTests.component(#"{"type":"approval","id":"read","grant":"g_01"}"#)))
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "pending"))]}"#)
        await model.refreshConnections()
        XCTAssertFalse(model.requestRefused(try SetupTests.component(#"{"type":"approval","id":"read","grant":"g_01"}"#)), "a pending request can be approved")
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

    /// A tapped choice goes as the card's answer, never as a message: the card goes quiet at
    /// once, and shows the choice as kiff/agents recorded it once the thread has it.
    func testATappedChoiceIsTheCardsAnswer() async throws {
        let model = await model(signer: FaceIDSigner())
        model.planqueSetUp(for: "t_01")
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[],"working":null}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/components/which/answer"] = (200, #"{"session":"s1","run":"r1","status":"RUNNING"}"#)
        await model.loadSetup()
        let which = try SetupTests.component(#"{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]}"#)
        StubProtocol.sent = []
        await model.answer(which, "live")
        XCTAssertTrue(model.answered.contains("which"), "the card goes quiet at once")
        XCTAssertFalse(model.setupThread.messages.contains { $0.from == .owner }, "no message in the old form")
        XCTAssertEqual(body(sent("POST", "cloud.test", "/v1/tafs/taf-setup/components/which/answer").last)["answer"] as? String, "live")

        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, """
        {"messages":[],"components":[{"at":"2026-10-17T10:00:00.000Z","session":"s1","run":"r1","seq":1,
          "component":{"type":"confirm","id":"which","text":"Which?","options":[{"value":"test","label":"Test"},{"value":"live","label":"Live"}]},
          "answer":{"state":"answered","words":"Live","at":"2026-10-17T10:00:05.000Z"}}],"working":null}
        """)
        await model.loadSetupThread()
        XCTAssertEqual(model.setupThread.answer(to: "which")?.text, "live", "the card shows the choice")
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
        model.lastError = nil

        // Archived lists it, and Put back returns it to the list (#97).
        StubProtocol.responses["GET /v1/tafs/taf-setup/chats/archived"] = (200, """
        {"chats":[{"session":"s_2","started":"2026-10-18T10:00:00.000Z","updated":"2026-10-18T10:05:00.000Z","title":"Answer customer questions"}]}
        """)
        await model.loadArchivedSetupChats()
        XCTAssertEqual(model.archivedSetupChats.map(\.title), ["Answer customer questions"])
        StubProtocol.responses["POST /v1/tafs/taf-setup/chats/s_2/unarchive"] = (200, #"{"session":"s_2","archived":false}"#)
        StubProtocol.sent = []
        let back = await model.unarchiveSetupChat("s_2")
        XCTAssertTrue(back)
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/tafs/taf-setup/chats/s_2/unarchive").count, 1)
        XCTAssertTrue(model.archivedSetupChats.isEmpty)
        XCTAssertFalse(sent("GET", "cloud.test", "/v1/tafs/taf-setup/chats").isEmpty, "the list is read again")
        Keychain.delete(AppModel.planqueAccount)
    }

    // A sign-in and the hand-over carry Setup's own words, in the owner's language;
    // without them the app's English stays (kiff/tafs#75).
    func testCardsCarryTheirOwnWords() throws {
        let signIn = try Self.component(#"{"type":"sign_in","id":"s1","grant":"g1","service":"mcp.stripe.com","yes":"Abrir Stripe","no":"Ahora no","note":"Se abre la página de Stripe."}"#)
        XCTAssertEqual(signIn.yes, "Abrir Stripe")
        XCTAssertEqual(signIn.no, "Ahora no")
        XCTAssertEqual(signIn.note, "Se abre la página de Stripe.")
        let result = try Self.component(#"{"type":"result","id":"r1","text":"Listo.","taf":{"agent":"reembolsos-x","name":"Reembolsos"},"yes":"Ver Reembolsos"}"#)
        XCTAssertEqual(result.yes, "Ver Reembolsos")
        // Where a sign-in goes comes from its service, whatever Setup's button says.
        let steered = try Self.component(#"{"type":"sign_in","id":"s3","grant":"g1","service":"mcp.evil.example","yes":"Abrir Stripe"}"#)
        XCTAssertEqual(steered.destination, "mcp.evil.example")
        let plain = try Self.component(#"{"type":"sign_in","id":"s2","grant":"g1"}"#)
        XCTAssertEqual(plain.destination, "the vendor's page")
        XCTAssertNil(plain.yes)
        XCTAssertNil(plain.note)
    }

    static func component(_ json: String) throws -> SetupComponent {
        try JSONDecoder().decode(SetupComponent.self, from: Data(json.utf8))
    }
}
