import XCTest
@testable import Tafs

/// The approval window (kiff/tafs#494), as Android has it (`ApprovalWindowTest`, `PlanqueTest`,
/// `WindowWordsTest`): one Face ID opens it at KIFF, then a routine approval is a tap, signed by the
/// device key with the window's id. KIFF and planque decide; the phone falls back to the approval
/// key, or opens a new window, when told.
@MainActor
final class ApprovalWindowTests: XCTestCase {
    private let win = "win_abcdefghijklmnopqrstuvwxyz"
    private var windowOpened: String { #"{"window":{"id":"\#(win)","expires":"2026-10-28T09:05:00Z","open":true,"words":"No fingerprint for the next 5 minutes"}}"# }
    private static let setupJSON = #"{"id":"taf-setup","name":"Setup","job":"setup","kiff_agent":"setup","status":"active","next_run":null,"last_run":null}"#
    private static let ours = Data([0x30, 0x59, 0x02])

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        Keychain.delete(AppModel.approvalKeyedAccount)
        Keychain.delete(AppModel.planqueAccount)
        super.tearDown()
    }

    // MARK: The clients

    private func session() -> URLSession {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: config)
    }

    private func client(_ signed: WindowBox<[String]>, approvalKey: Bool = true) -> KIFFClient {
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, token: "kiff_dev_x", session: session())
        kiff.tenant = "t_01"
        kiff.setupSigner = { m in signed.value.append("device:" + (String(decoding: m, as: UTF8.self).split(separator: "\n").first.map(String.init) ?? "")); return "dev" }
        if approvalKey {
            kiff.approvalSigner = { m in signed.value.append("approval:" + (String(decoding: m, as: UTF8.self).split(separator: "\n").first.map(String.init) ?? "")); return "app" }
        }
        return kiff
    }

    private func sent(_ method: String, _ path: String, host: String? = nil) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path && (host == nil || $0.url?.host == host) }
    }

    private func json(_ request: URLRequest?) -> [String: Any] {
        guard let data = request?.httpBody, let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return object
    }

    func testTheWindowMessageIsKIFFsRequestWithTheWindowLast() {
        let message = String(decoding: KIFFClient.requestMessageWindow(tenant: "t_01", method: "POST", uri: "/v1/me/mandates", body: Data("{}".utf8),
                                                                       signedAt: "2026-11-05T11:00:00Z", window: win), as: UTF8.self)
        XCTAssertEqual(message.components(separatedBy: "\n"),
                       ["kiff.request.v2", "t_01", "POST", "/v1/me/mandates", "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", "2026-11-05T11:00:00Z", win])
    }

    func testPlanquesWindowMessageIsItsRequestWithTheWindowLastAndNoneWithoutOne() {
        let base = URL(string: "https://planque.example/")!
        let body = Data("{}".utf8)
        let v2 = String(decoding: PlanqueClient.messageWindow(base: base, tenant: "t_01", method: "POST", uri: "/v1/accounts/t_01/grants/g1/approve",
                                                              body: body, signedAt: "2026-11-05T11:00:00Z", window: win), as: UTF8.self)
        XCTAssertEqual(Array(v2.components(separatedBy: "\n").prefix(5)), ["planque.request.v2", "https://planque.example", "t_01", "POST", "/v1/accounts/t_01/grants/g1/approve"])
        XCTAssertEqual(v2.components(separatedBy: "\n").last, win)
        let v1 = PlanqueClient.messageWindow(base: base, tenant: "t_01", method: "POST", uri: "/v1/accounts/t_01/grants/g1/approve", body: body, signedAt: "2026-11-05T11:00:00Z", window: nil)
        XCTAssertEqual(v1, PlanqueClient.message(base: base, tenant: "t_01", method: "POST", uri: "/v1/accounts/t_01/grants/g1/approve", body: body, signedAt: "2026-11-05T11:00:00Z"))
    }

    func testOpeningIsSignedByTheApprovalKeyWithKIFFsMessageAndHoldsTheWindow() async throws {
        let signed = WindowBox<[String]>([])
        let kiff = client(signed)
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        let opened = try await kiff.openWindow(expires: "2026-11-05T11:05:00Z", nonce: "0123456789abcdef0123456789abcdef")
        XCTAssertEqual(signed.value, ["approval:kiff.request.v1"])
        let request = try XCTUnwrap(sent("POST", "/v1/me/window").first)
        XCTAssertTrue(request.value(forHTTPHeaderField: "KIFF-Device-Signature")?.contains(",v1=app") ?? false)
        XCTAssertEqual(json(request) as NSDictionary, ["message": "kiff.window.v1", "expires": "2026-11-05T11:05:00Z", "nonce": "0123456789abcdef0123456789abcdef"])
        XCTAssertEqual(opened.id, win)
        XCTAssertEqual(kiff.window?.id, win)
        XCTAssertEqual(kiff.window?.words, "No fingerprint for the next 5 minutes")
    }

    func testReadingTheWindowIsLenient() async throws {
        let kiff = client(WindowBox([]))
        StubProtocol.responses["GET /v1/me/window"] = (200, #"{"window":{"id":"\#(win)","open":true,"words":"No fingerprint for the next 4 minutes","cap":"2026-10-28T09:15:00Z"},"words":"No fingerprint for the next 4 minutes"}"#)
        let read = try await kiff.readWindow()
        XCTAssertEqual(read.window, KIFFClient.ApprovalWindow(id: win, cap: "2026-10-28T09:15:00Z", open: true, words: "No fingerprint for the next 4 minutes"))
        XCTAssertEqual(read.words, "No fingerprint for the next 4 minutes")
        XCTAssertEqual(sent("GET", "/v1/me/window").first?.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_x")
        // A field of another shape is none; a window with no id isn't one; no window at all is none.
        let odd = try KIFFClient.decoder.decode(KIFFClient.WindowRead.self, from: Data(#"{"window":{"id":"w","open":"yes","words":5,"expires":1}}"#.utf8))
        XCTAssertEqual(odd.window, KIFFClient.ApprovalWindow(id: "w"))
        XCTAssertFalse(odd.window?.open ?? true)
        XCTAssertNil(try KIFFClient.decoder.decode(KIFFClient.WindowRead.self, from: Data(#"{"window":{"open":true}}"#.utf8)).window)
        XCTAssertNil(try KIFFClient.decoder.decode(KIFFClient.WindowRead.self, from: Data(#"{"window":null}"#.utf8)).window)
        XCTAssertNil(try KIFFClient.decoder.decode(KIFFClient.WindowRead.self, from: Data("{}".utf8)).window)
    }

    func testInsideAWindowWhatTheApprovalKeyWouldSignIsATapByTheDeviceKey() async throws {
        let signed = WindowBox<[String]>([])
        let kiff = client(signed)
        kiff.window = KIFFClient.ApprovalWindow(id: win, open: true)
        kiff.signsInWindow = true
        StubProtocol.responses["POST /v1/me/did-this-happen/prop_1"] = (200, "{}")
        StubProtocol.responses["PUT /v1/me/time-zone"] = (200, #"{"time_zone":"UTC"}"#)
        try await kiff.answerHappened("prop_1", happened: true)
        _ = try await kiff.setTimeZone("UTC")
        XCTAssertEqual(signed.value, ["device:kiff.request.v2", "device:kiff.request.v1"])
        let header = try XCTUnwrap(sent("POST", "/v1/me/did-this-happen/prop_1").first?.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertNotNil(header.range(of: "^t=[^,]+,v2=dev,w=\(win)$", options: .regularExpression), header)
        // What grants nothing is signed as before, never with the window.
        XCTAssertTrue(sent("PUT", "/v1/me/time-zone").first?.value(forHTTPHeaderField: "KIFF-Device-Signature")?.contains(",v1=dev") ?? false)
    }

    /// A Card's authority is the approval key's behind Face ID even inside a held window, in the very step that
    /// took it: a draft confirmed, a draft made, a Card issued. Never a tap, so Approve never grants a Card
    /// without the Face ID it names.
    func testACardIsNeverATapInTheWindow() async throws {
        let signed = WindowBox<[String]>([])
        let kiff = client(signed)
        kiff.window = KIFFClient.ApprovalWindow(id: win, open: true)
        kiff.signsInWindow = true
        StubProtocol.responses["POST /v1/me/agent-drafts/d1/confirm"] = (200, #"{"id":"d1","agent_id":"refunds","status":"confirmed","version":"v3"}"#)
        _ = try await kiff.confirmDraft(id: "d1", version: "v3")
        XCTAssertEqual(signed.value, ["approval:kiff.request.v1"])
        let header = try XCTUnwrap(sent("POST", "/v1/me/agent-drafts/d1/confirm").first?.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertFalse(header.contains(",w="), header)
        for (method, path) in [("POST", "/v1/me/agent-drafts"), ("POST", "/v1/me/agent-drafts/d1/confirm"), ("POST", "/v1/me/mandates")] {
            XCTAssertTrue(KIFFClient.grantsCard(method, path), path)
        }
        for (method, path) in [("POST", "/v1/me/did-this-happen/prop_1"), ("GET", "/v1/me/agent-drafts/d1"), ("POST", "/v1/me/agent-drafts/d1/confirm/x")] {
            XCTAssertFalse(KIFFClient.grantsCard(method, path), path)
        }
    }

    /// Adding someone to a group is the approval key's even inside a held window (group changes B): KIFF
    /// would take a window-named signature on that route as the device key's.
    func testAddingSomeoneToAGroupIsNeverATapInTheWindow() async throws {
        let signed = WindowBox<[String]>([])
        let kiff = client(signed)
        kiff.window = KIFFClient.ApprovalWindow(id: win, open: true)
        kiff.signsInWindow = true
        StubProtocol.responses["PUT /v1/groups/grp_support/members/user_ana"] = (200, #"{"subject":"user_ana","role":"member"}"#)
        try await kiff.setGroupMember("grp_support", subject: "user_ana", role: "member")
        XCTAssertEqual(signed.value, ["approval:kiff.request.v1"])
        let header = try XCTUnwrap(sent("PUT", "/v1/groups/grp_support/members/user_ana").first?.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertFalse(header.contains(",w="), header)
    }

    /// Only the approval step that took the window signs in it: outside that step, a held window
    /// changes nothing, and the approval key signs as before.
    func testOutsideTheStepThatTookItTheApprovalKeySignsAsBefore() async throws {
        let signed = WindowBox<[String]>([])
        let kiff = client(signed)
        kiff.window = KIFFClient.ApprovalWindow(id: win, open: true)
        StubProtocol.responses["POST /v1/me/agent-drafts/d1/confirm"] = (200, #"{"id":"d1","agent_id":"refunds","status":"confirmed","version":"v3"}"#)
        _ = try await kiff.confirmDraft(id: "d1", version: "v3")
        XCTAssertEqual(signed.value, ["approval:kiff.request.v1"])
        XCTAssertFalse(sent("POST", "/v1/me/agent-drafts/d1/confirm").first?.value(forHTTPHeaderField: "KIFF-Device-Signature")?.contains(",w=") ?? true)
    }

    func testAnActThatTakesTheFingerprintEveryTimeIsSignedAgainByTheApprovalKeyAfterFaceIDAndTheWindowStays() async throws {
        let signed = WindowBox<[String]>([])
        let kiff = client(signed)
        kiff.window = KIFFClient.ApprovalWindow(id: win, open: true)
        kiff.signsInWindow = true
        kiff.windowReconfirm = { signed.value.append("face-id") }
        StubProtocol.responses["POST /v1/me/did-this-happen/prop_1"] = (403, #"{"error":"critical_key_required","detail":"This takes your fingerprint"}"#)
        StubProtocol.thenRespond["POST /v1/me/did-this-happen/prop_1"] = ["POST /v1/me/did-this-happen/prop_1": (200, "{}")]
        try await kiff.answerHappened("prop_1", happened: false)
        XCTAssertEqual(signed.value, ["device:kiff.request.v2", "face-id", "approval:kiff.request.v1"])
        XCTAssertEqual(kiff.window?.id, win)
    }

    func testAWindowThatEndedIsForgottenAndTheApprovalKeySignsAgainOnce() async throws {
        let signed = WindowBox<[String]>([])
        let kiff = client(signed)
        kiff.window = KIFFClient.ApprovalWindow(id: win, open: true)
        kiff.signsInWindow = true
        kiff.windowReconfirm = { signed.value.append("face-id") }
        StubProtocol.responses["POST /v1/me/did-this-happen/prop_1"] = (403, #"{"error":"window_required"}"#)
        do {
            try await kiff.answerHappened("prop_1", happened: true)
            XCTFail("a second refusal is the service's")
        } catch KIFFClient.KIFFError.server(let status, _, _) {
            XCTAssertEqual(status, 403)
        }
        XCTAssertEqual(signed.value, ["device:kiff.request.v2", "face-id", "approval:kiff.request.v1"])
        XCTAssertNil(kiff.window)
    }

    func testWithNoApprovalKeyARefusalInTheWindowKeepsKIFFsCodeAndSaysItInTheAppsWords() async {
        let kiff = client(WindowBox([]), approvalKey: false)
        kiff.window = KIFFClient.ApprovalWindow(id: win, open: true)
        kiff.signsInWindow = true
        kiff.windowReconfirm = {}
        StubProtocol.responses["POST /v1/me/did-this-happen/prop_1"] = (403, #"{"error":"critical_key_required","detail":"This takes your fingerprint"}"#)
        do {
            try await kiff.answerHappened("prop_1", happened: false)
            XCTFail("refused")
        } catch let error as KIFFClient.KIFFError {
            XCTAssertEqual(error, .windowRefused(code: "critical_key_required"))
            // KIFF's `detail` is for the log: the person reads the app's own line.
            XCTAssertEqual(error.errorDescription, Words.Error.needsConfirmationFirst)
        } catch {
            XCTFail("\(error)")
        }
        XCTAssertEqual(KIFFClient.KIFFError.windowRefused(code: "window_required").errorDescription, Words.Error.approvalWindowEnded)
    }

    func testClosingIsDeviceSignedAndForgetsTheWindowWhateverKIFFAnswers() async {
        let kiff = client(WindowBox([]))
        kiff.window = KIFFClient.ApprovalWindow(id: win, open: true)
        StubProtocol.responses["POST /v1/me/window/\(win)/close"] = (503, "{}")
        try? await kiff.closeWindow(signedBy: { _ in "devclose" })
        XCTAssertNil(kiff.window)
        let header = sent("POST", "/v1/me/window/\(win)/close").first?.value(forHTTPHeaderField: "KIFF-Device-Signature") ?? ""
        XCTAssertTrue(header.contains(",v1=devclose"), header)
        XCTAssertEqual(sent("POST", "/v1/me/window/\(win)/close").first?.httpBody, Data("{}".utf8))
    }

    func testPlanqueSignsInTheWindowItIsGivenAndSaysWhenItEnded() async throws {
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.example")!, session: session())
        planque.tenant = "t_01"
        planque.deviceID = "dev_phone"
        planque.signer = { _ in "dev" }
        planque.window = win
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g1/approve"] = (403, #"{"error":"Your approval window has ended.","code":"window_required"}"#)
        do {
            _ = try await planque.approve(grantID: "g1", body: PlanqueClient.ApproveBody(digest: "d1", kiffProfile: nil))
            XCTFail("refused")
        } catch let error as PlanqueClient.PlanqueError {
            XCTAssertEqual(error, .windowRequired)
            // planque's `error` is for the log: the person reads the app's own line.
            XCTAssertEqual(error.errorDescription, Words.Error.approvalWindowEnded)
        }
        let header = sent("POST", "/v1/accounts/t_01/grants/g1/approve").first?.value(forHTTPHeaderField: "Planque-Device-Signature") ?? ""
        XCTAssertNotNil(header.range(of: "^t=[^,]+,v2=dev,w=\(win),d=dev_phone$", options: .regularExpression), header)
        XCTAssertNil(planque.window)
    }

    /// tafs cloud's hint of the key a call takes is read as sent, and another shape is none.
    func testTheAsksKeyHintIsReadLeniently() throws {
        func ask(_ key: String) throws -> TafsCloud.ApprovalDecision.Ask {
            try KIFFClient.decoder.decode(TafsCloud.ApprovalDecision.Ask.self, from: Data(#"{"id":"ask_1","step":"connect","message":"x","reason":"r"\#(key)}"#.utf8))
        }
        XCTAssertEqual(try ask(#","key":"window""#).key, "window")
        XCTAssertEqual(try ask(#","key":"critical""#).key, "critical")
        XCTAssertNil(try ask("").key)
        XCTAssertNil(try ask(#","key":1"#).key, "another shape: none, never the ask unread")
        XCTAssertEqual(try ask(#","key":1"#).step, "connect")
    }

    // MARK: Approve on Coach's card

    private var askRoute: String { "POST /v1/tafs/taf-setup/components/connect-stripe/approval" }
    private var reportRoute: String { "POST /v1/tafs/taf-setup/components/connect-stripe/approval/ask_1" }
    private var approveRoute: String { "POST /v1/accounts/t_01/grants/g_01/approve" }
    private var connectMessage: String { "tafs.connect.v1|t_01|g_01|\(PlanqueTests.digest)" }

    private func ask(key: String?) -> String {
        let hint = key.map { #","key":"\#($0)""# } ?? ""
        return #"{"outcome":"sign","ask":{"id":"ask_1","step":"connect","message":"\#(connectMessage)","reason":"Connect Stripe to Refunds"\#(hint),"planque":{"grant":"g_01","body":{"digest":"\#(PlanqueTests.digest)"}}}}"#
    }

    /// Signed in with the approval key KIFF holds, Connect apps set up, Setup's taf and a pending grant read.
    private func model(_ signer: TwoKeySigner, clock: @escaping () -> Date = Date.init) async -> AppModel {
        let session = self.session()
        Keychain.delete(AppModel.planqueAccount)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: StubVendor(), clock: clock)
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[]}"#)
        StubProtocol.responses["POST /v1/me/device/approval-key"] = (200, #"{"fingerprint":""# + KIFFClient.fingerprint(Self.ours) + #""}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "pending"))]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        model.planqueSetUp(for: "t_01")
        await model.loadSetup()
        await model.refreshConnections()
        StubProtocol.responses[approveRoute] = (200, #"{"status":"connected"}"#)
        StubProtocol.responses[reportRoute] = (200, #"{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}"#)
        StubProtocol.sent = []
        return model
    }

    private func approveCard(_ model: AppModel) async throws {
        let card = try SetupTests.component(#"{"type":"approval","id":"connect-stripe","grant":"g_01","signs":{"card":false,"takes":"tap"}}"#)
        await model.approve(card, shown: model.grants.first?.digest, shownDraft: nil)
    }

    private func planqueHeaders() -> [String] {
        sent("POST", "/v1/accounts/t_01/grants/g_01/approve", host: "planque.test").compactMap { $0.value(forHTTPHeaderField: "Planque-Device-Signature") }
    }

    func testAWindowAskOpensAWindowWithOneFaceIDAndTheConnectionGoesAsATap() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        XCTAssertTrue(model.sessionHasApprovalKey)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened.replacingOccurrences(of: "5 minutes", with: "4 minutes"))
        try await approveCard(model)
        // One Face ID, over exactly the message tafs cloud named: the window's.
        XCTAssertEqual(signer.currentPrompted, [connectMessage], model.cardNotes["connect-stripe"] ?? "")
        let opening = try XCTUnwrap(sent("POST", "/v1/me/window").first)
        XCTAssertEqual(json(opening)["message"] as? String, "kiff.window.v1")
        XCTAssertEqual((json(opening)["nonce"] as? String)?.count, 32)
        XCTAssertNotNil(json(opening)["expires"] as? String)
        XCTAssertTrue(opening.value(forHTTPHeaderField: "KIFF-Device-Signature")?.contains(",v1=approval") ?? false, "opened by the approval key")
        // planque's approve goes as a tap: the device key, with the window's id.
        let header = try XCTUnwrap(planqueHeaders().first)
        XCTAssertTrue(header.contains(",v2=current,w=\(win),d="), header)
        XCTAssertTrue(signer.current.contains { $0.hasPrefix("planque.request.v2\n") && $0.hasSuffix("\n" + win) })
        // The report is signed as it always was (v1), and KIFF's words for the next tap are read again.
        let report = try XCTUnwrap(sent("POST", "/v1/tafs/taf-setup/components/connect-stripe/approval/ask_1").first)
        XCTAssertTrue(report.value(forHTTPHeaderField: "KIFF-Device-Signature")?.contains(",v1=current") ?? false)
        XCTAssertEqual(json(report)["result"] as? String, "connected")
        XCTAssertEqual(model.windowWords, "No fingerprint for the next 4 minutes")
        // Nothing of the step stays in the window.
        XCTAssertNil(model.planque.window)
        XCTAssertFalse(model.kiff.signsInWindow)
        XCTAssertNil(model.kiff.windowReconfirm)
    }

    func testASecondWindowAskInsideTheWindowAsksNoFaceID() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened)
        try await approveCard(model)
        try await approveCard(model)
        XCTAssertEqual(signer.currentPrompted.count, 1)
        XCTAssertEqual(sent("POST", "/v1/me/window").count, 1)
        XCTAssertEqual(planqueHeaders().filter { $0.contains(",w=\(win),") }.count, 2)
    }

    func testPlanqueSayingTheWindowEndedOpensANewOneAndTriesOnceMore() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened)
        StubProtocol.responses[approveRoute] = (403, #"{"error":"Your approval window has ended.","code":"window_required"}"#)
        StubProtocol.thenRespond[approveRoute] = [approveRoute: (200, #"{"status":"connected"}"#)]
        try await approveCard(model)
        XCTAssertEqual(sent("POST", "/v1/me/window").count, 2)
        XCTAssertEqual(signer.currentPrompted.count, 2)
        XCTAssertEqual(planqueHeaders().count, 2)
        XCTAssertEqual(json(sent("POST", "/v1/tafs/taf-setup/components/connect-stripe/approval/ask_1").first)["result"] as? String, "connected")
    }

    func testAKIFFWithoutWindowsLeavesTheStepAsItWas() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (404, #"{"error":"not_found"}"#)
        try await approveCard(model)
        XCTAssertEqual(signer.currentPrompted, [connectMessage], "Face ID, as before")
        let header = try XCTUnwrap(planqueHeaders().first)
        XCTAssertTrue(header.contains(",v1=") && !header.contains(",w="), header)
        XCTAssertNil(model.windowWords)
        XCTAssertNil(model.kiff.window)
    }

    func testAnAskWithNoHintOrACriticalOneOpensNoWindow() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        for key in [nil, "critical"] {
            StubProtocol.responses[askRoute] = (200, ask(key: key))
            try await approveCard(model)
        }
        XCTAssertTrue(sent("POST", "/v1/me/window").isEmpty)
        XCTAssertEqual(signer.currentPrompted.count, 2, "Face ID each time")
        XCTAssertTrue(planqueHeaders().allSatisfy { !$0.contains(",w=") })
    }

    /// A window is used only on a card that says Approve is a tap: one whose line still says Face ID is asked (no
    /// `signs.takes`, or `fingerprint`) asks it, even while this phone holds a window and tafs cloud's ask says
    /// "window" (the owner's emulator pass, 21 Nov: Approve never approves without the Face ID it promised).
    func testACardThatPromisesFaceIDAsksItEvenInsideAWindow() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened)
        try await approveCard(model)
        XCTAssertNotNil(model.kiff.window)
        for marks in [#""signs":{"card":false}"#, #""signs":{"card":false,"takes":"fingerprint"}"#] {
            let card = try SetupTests.component(#"{"type":"approval","id":"connect-stripe","grant":"g_01",\#(marks)}"#)
            await model.approve(card, shown: model.grants.first?.digest, shownDraft: nil)
        }
        XCTAssertEqual(signer.currentPrompted.count, 3, "Face ID for the tap card's window, then for each card that promised it")
        XCTAssertEqual(planqueHeaders().filter { $0.contains(",w=") }.count, 1, "only the tap card went in the window")
    }

    /// A critical ask while a window is held still takes Face ID, and signs outside the window.
    func testACriticalAskInsideAWindowStillTakesFaceID() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened)
        try await approveCard(model)
        XCTAssertNotNil(model.kiff.window)
        StubProtocol.responses[askRoute] = (200, ask(key: "critical"))
        try await approveCard(model)
        XCTAssertEqual(signer.currentPrompted.count, 2)
        XCTAssertFalse(planqueHeaders().last?.contains(",w=") ?? true)
    }

    /// No approval key KIFF holds (a phone with none): no window is opened, and Face ID asks as before.
    func testWithoutTheApprovalKeyNoWindowIsOpened() async throws {
        let signer = FaceIDSigner()
        let session = self.session()
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session),
                             planque: PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session), vendorSignIn: StubVendor())
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "pending"))]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        model.planqueSetUp(for: "t_01")
        await model.loadSetup()
        await model.refreshConnections()
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses[approveRoute] = (200, #"{"status":"connected"}"#)
        StubProtocol.responses[reportRoute] = (200, #"{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}"#)
        try await approveCard(model)
        XCTAssertFalse(model.sessionHasApprovalKey)
        XCTAssertTrue(sent("POST", "/v1/me/window").isEmpty)
        XCTAssertEqual(signer.prompts, [connectMessage])
    }

    // MARK: The direct connect: Connect apps, Connect again, an approval card's Connect

    private func directConnect(_ model: AppModel) async -> PlanqueClient.Grant? {
        await model.connect(grantID: "g_01", shown: model.grants.first?.digest)
    }

    /// A connection planque failed can't be approved: the line under the card is planque's words for its state, or the app's
    /// own "Not connected", never planque's English `reason` (as Android's `aFailedGrantsReasonNeverReachesThePerson`).
    func testAFailedGrantsReasonNeverReachesThePerson() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        let reason = "KIFF answered 403: approval_refused (profile_not_approved)"
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "failed", reason: reason, statusWords: "Échec"))]}"#)
        let failed = await directConnect(model)
        XCTAssertNil(failed)
        XCTAssertEqual(model.lastError, "Échec")
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[\#(PlanqueTests.grantJSONFor(status: "failed", reason: reason))]}"#)
        let again = await directConnect(model)
        XCTAssertNil(again)
        XCTAssertEqual(model.lastError, Words.Coach.Shape.notConnected)
        for word in ["KIFF", "403", "approval_refused", "profile_not_approved"] {
            XCTAssertFalse(model.lastError?.contains(word) ?? false, word)
        }
        XCTAssertTrue(signer.currentPrompted.isEmpty, "nothing to sign for a connection that can't be approved")
    }

    /// As Android's (tafs#1272): the connection's own Face ID opens KIFF's window with the approval key, and planque's
    /// approve goes as a tap in it, never as a windowless v1. The window is closed once the connection is done.
    func testTheDirectConnectApprovesInItsOwnWindow() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        let connected = await directConnect(model)
        XCTAssertNotNil(connected, model.lastError ?? "")
        XCTAssertEqual(signer.currentPrompted, [connectMessage], "one Face ID, naming the grant")
        let opening = try XCTUnwrap(sent("POST", "/v1/me/window").first)
        XCTAssertTrue(opening.value(forHTTPHeaderField: "KIFF-Device-Signature")?.contains(",v1=approval") ?? false, "opened by the approval key")
        let header = try XCTUnwrap(planqueHeaders().first)
        XCTAssertTrue(header.contains(",v2=current,w=\(win),d="), header)
        XCTAssertNil(model.planque.window, "the window is on for planque's call alone")
        XCTAssertFalse(model.kiff.signsInWindow)
        try await waitFor { !self.closes().isEmpty }
        XCTAssertEqual(closes().count, 1, "the connection's window is closed when it's done")
    }

    /// A window held from Coach's step is closed first, and the connection opens its own behind its own Face ID.
    func testAWindowHeldFromBeforeIsClosedFirst() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened)
        try await approveCard(model)
        XCTAssertNotNil(model.kiff.window)
        StubProtocol.sent = []
        _ = await directConnect(model)
        let windowPosts = StubProtocol.sent.filter { $0.httpMethod == "POST" && ($0.url?.path.hasPrefix("/v1/me/window") ?? false) }.compactMap { $0.url?.path }
        XCTAssertEqual(windowPosts.first, "/v1/me/window/\(win)/close", "the held window is closed first")
        XCTAssertTrue(windowPosts.contains("/v1/me/window"), "then the connection opens its own")
        XCTAssertEqual(signer.currentPrompted.count, 2, "the connection's own Face ID, not a tap in the old window")
    }

    /// KIFF refusing the window (an unsigned or device-key open, `approval_key_required`) stops the connection:
    /// nothing reaches planque, so a refused open never becomes a windowless v1 approve.
    func testARefusedWindowOpenSendsNothingToPlanque() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses["POST /v1/me/window"] = (403, #"{"error":"approval_key_required","code":"approval_key_required"}"#)
        let connected = await directConnect(model)
        XCTAssertNil(connected)
        XCTAssertNotNil(model.lastError)
        XCTAssertTrue(planqueHeaders().isEmpty, "no approve, in a window or not")
        XCTAssertTrue(sent("POST", "/v1/accounts/t_01/grants/g_01/confirm", host: "planque.test").isEmpty)
    }

    /// A KIFF without windows (404): the connection goes as it did, with Face ID and a v1 approve.
    func testWithAKIFFWithoutWindowsTheDirectConnectGoesAsBefore() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses["POST /v1/me/window"] = (404, #"{"error":"not_found"}"#)
        let connected = await directConnect(model)
        XCTAssertNotNil(connected, model.lastError ?? "")
        let header = try XCTUnwrap(planqueHeaders().first)
        XCTAssertTrue(header.contains(",v1=") && !header.contains(",w="), header)
        XCTAssertTrue(closes().isEmpty)
    }

    /// planque says the window ended before it did anything: one more Face ID opens a new window, and only that call
    /// is made again, in it.
    func testPlanqueSayingTheWindowEndedOnADirectConnectOpensANewOneAndTriesOnceMore() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses[approveRoute] = (403, #"{"error":"Your approval window has ended.","code":"window_required"}"#)
        StubProtocol.thenRespond[approveRoute] = [approveRoute: (200, #"{"status":"connected"}"#)]
        let connected = await directConnect(model)
        XCTAssertNotNil(connected, model.lastError ?? "")
        XCTAssertEqual(sent("POST", "/v1/me/window").count, 2)
        XCTAssertEqual(signer.currentPrompted.count, 2)
        XCTAssertEqual(planqueHeaders().count, 2)
        XCTAssertTrue(planqueHeaders().allSatisfy { $0.contains(",w=\(win),") })
    }

    /// The Face ID for the new window is refused: it stops there, with nothing sent again and no window left.
    func testARefusedFaceIDForTheNewWindowStopsThereWithNothingSentAgain() async throws {
        let signer = TwoKeySigner()
        signer.currentRefusedFrom = 1
        let model = await model(signer)
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses[approveRoute] = (403, #"{"error":"Your approval window has ended.","code":"window_required"}"#)
        let connected = await directConnect(model)
        XCTAssertNil(connected)
        XCTAssertEqual(planqueHeaders().count, 1, "the approve is sent once")
        XCTAssertEqual(sent("POST", "/v1/me/window").count, 1, "no second window")
        XCTAssertNil(model.kiff.window)
        XCTAssertNil(model.planque.window)
    }

    /// planque says the window ended, and KIFF won't open a new one: it stops, and the call is never made again
    /// without a window.
    func testWhenKIFFOpensNoNewWindowTheCallIsNotMadeAgainWithoutOne() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.thenRespond["POST /v1/me/window"] = ["POST /v1/me/window": (404, #"{"error":"not_found"}"#)]
        StubProtocol.responses[approveRoute] = (403, #"{"error":"Your approval window has ended.","code":"window_required"}"#)
        let connected = await directConnect(model)
        XCTAssertNil(connected)
        XCTAssertEqual(planqueHeaders().count, 1, "no windowless approve after it")
        XCTAssertTrue(planqueHeaders().allSatisfy { $0.contains(",w=\(win),") })
    }

    // MARK: Closed, dropped, expired

    private func waitFor(_ condition: @MainActor () -> Bool) async throws {
        for _ in 0..<200 where !condition() { try await Task.sleep(nanoseconds: 10_000_000) }
    }

    private func closes() -> [URLRequest] { sent("POST", "/v1/me/window/\(win)/close") }

    func testTheWindowClosesAfterThirtySecondsAwayAndAtOnceWhenTheScreenLocks() async throws {
        let signer = TwoKeySigner()
        let now = WindowBox(Date(timeIntervalSince1970: 1_800_000_000))
        let model = await model(signer, clock: { now.value })
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened)
        StubProtocol.responses["POST /v1/me/window/\(win)/close"] = (200, #"{"window":{"id":"\#(win)","open":false}}"#)
        try await approveCard(model)
        // The window asked for runs five minutes from the Face ID, by this phone's clock.
        XCTAssertEqual(json(sent("POST", "/v1/me/window").first)["expires"] as? String, "2027-01-15T08:05:00Z")

        // Back within 30 seconds: it stays, and KIFF's words are read again.
        model.windowAwayNanoseconds = 60_000_000_000
        model.wentToBackground()
        now.value += 20
        let reads = sent("GET", "/v1/me/window").count
        model.cameToForeground()
        try await waitFor { self.sent("GET", "/v1/me/window").count > reads }
        try await Task.sleep(nanoseconds: 50_000_000) // its answer lands
        XCTAssertTrue(closes().isEmpty)
        XCTAssertNotNil(model.kiff.window)

        // Away for longer: closed, device-signed, and its words gone.
        model.windowAwayNanoseconds = 10_000_000
        model.wentToBackground()
        try await waitFor { !self.closes().isEmpty }
        let closed = try XCTUnwrap(closes().first)
        XCTAssertTrue(closed.value(forHTTPHeaderField: "KIFF-Device-Signature")?.contains(",v1=current") ?? false)
        try await waitFor { model.windowWords == nil }
        XCTAssertNil(model.windowWords)
        XCTAssertNil(model.kiff.window)

        // iOS suspended the app before its 30 seconds: back after them, it closes then.
        try await approveCard(model)
        model.windowAwayNanoseconds = 60_000_000_000
        model.wentToBackground()
        now.value += 31
        model.cameToForeground()
        try await waitFor { self.closes().count == 2 }
        XCTAssertEqual(closes().count, 2)

        // A new window, then the screen locks: closed at once.
        try await approveCard(model)
        model.screenLocked()
        try await waitFor { self.closes().count == 3 && model.windowWords == nil }
        XCTAssertEqual(closes().count, 3)
        XCTAssertNil(model.windowWords)
    }

    func testSigningOutDropsTheWindow() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened)
        try await approveCard(model)
        XCTAssertEqual(model.windowWords, "No fingerprint for the next 5 minutes")
        await model.signOut()
        XCTAssertNil(model.windowWords)
        XCTAssertNil(model.kiff.window)
        // Nothing of the last session's window is closed or signed afterwards.
        model.screenLocked()
        model.wentToBackground()
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertTrue(closes().isEmpty)
    }

    func testANewSignInForgetsTheLastSessionsWindow() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, windowOpened)
        try await approveCard(model)
        XCTAssertNotNil(model.kiff.window)
        await model.useSession("kiff_dev_y", tenant: "t_02")
        XCTAssertNil(model.windowWords)
        XCTAssertNil(model.kiff.window)
        model.screenLocked()
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertTrue(closes().isEmpty)
    }

    /// KIFF says the window isn't open any more (it reached its time): it's forgotten, its words go,
    /// and the next approval asks for Face ID and opens a new one, never a tap in the old one.
    func testAWindowKIFFSaysHasEndedIsNotUsedAgain() async throws {
        let signer = TwoKeySigner()
        let model = await model(signer)
        StubProtocol.responses[askRoute] = (200, ask(key: "window"))
        StubProtocol.responses["POST /v1/me/window"] = (201, windowOpened)
        StubProtocol.responses["GET /v1/me/window"] = (200, #"{"window":{"id":"\#(win)","open":false}}"#)
        try await approveCard(model)
        XCTAssertNil(model.kiff.window)
        XCTAssertNil(model.windowWords)
        let card = try SetupTests.component(#"{"type":"approval","id":"connect-stripe","grant":"g_01","signs":{"card":false,"takes":"tap"}}"#)
        XCTAssertEqual(SetupApproval.approveLine(card, held: model.windowWords, approving: false), Words.Coach.approvingAsks(biometry: "\(Biometry.name)"))
        try await approveCard(model)
        XCTAssertEqual(signer.currentPrompted.count, 2, "Face ID again")
        XCTAssertEqual(sent("POST", "/v1/me/window").count, 2)

        // No window at all in KIFF's answer counts the same.
        StubProtocol.responses["GET /v1/me/window"] = (200, "{}")
        try await approveCard(model)
        XCTAssertNil(model.kiff.window)
        XCTAssertEqual(signer.currentPrompted.count, 3)
    }

    // MARK: The card

    /// Approve names Face ID only where it still asks for it: on a card tafs cloud marks a tap, while a
    /// window is held, KIFF's words stand in its place (as Android's "Approve" over `windowWordsFor`).
    func testTheLineUnderApproveNamesFaceIDOnlyWhereItsStillAsked() throws {
        let words = "No fingerprint for the next 4 minutes"
        let faceID = Words.Coach.approvingAsks(biometry: "\(Biometry.name)")
        let tap = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","signs":{"card":false,"takes":"tap"}}"#)
        XCTAssertEqual(SetupApproval.approveLine(tap, held: words, approving: false), words)
        XCTAssertNil(SetupApproval.approveLine(tap, held: words, approving: true), "hidden while it approves, as on Android")
        XCTAssertEqual(SetupApproval.approveLine(tap, held: nil, approving: false), faceID, "no window: Face ID")
        let fingerprint = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","signs":{"card":true,"takes":"fingerprint"}}"#)
        XCTAssertEqual(SetupApproval.approveLine(fingerprint, held: words, approving: false), faceID, "tools or a key with their Card")
        let unmarked = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","signs":{"card":true}}"#)
        XCTAssertEqual(SetupApproval.approveLine(unmarked, held: words, approving: false), faceID, "no takes: no tap promised")
        let none = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1"}"#)
        XCTAssertEqual(SetupApproval.approveLine(none, held: words, approving: false), faceID, "no mark")

        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let chat = try String(contentsOf: views.appendingPathComponent("SetupChatView.swift"), encoding: .utf8)
        XCTAssertTrue(chat.contains("if let line = Self.approveLine(component, held: model.windowWords, approving: model.connecting != nil) {"))
        XCTAssertFalse(chat.contains("CardNote(id: component.id)\n                Text(Words.Coach.approvingAsks"), "never Face ID unconditionally under Approve")
    }
}

/// A value a test's closures write to and read back.
final class WindowBox<T> {
    var value: T
    init(_ value: T) { self.value = value }
}
