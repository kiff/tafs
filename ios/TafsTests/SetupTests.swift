import XCTest
@testable import Tafs

/// The setup chat (kiff/tafs#50, contract 1 on kiff/agents#91): starting Setup once, its
/// thread of messages and components, and the owner's answers.
@MainActor
final class SetupTests: XCTestCase {
    private static let setupJSON = #"{"id":"taf-setup","name":"Setup","job":"setup","kiff_agent":"setup","status":"active","next_run":null,"last_run":null}"#

    private func model(signer: FaceIDSigner, vendor: StubVendor = StubVendor()) async -> AppModel {
        StubProtocol.responses = [:]
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
