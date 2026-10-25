import XCTest
@testable import Tafs

/// Trying a version (kiff/tafs#173): one Face ID confirms its Card in KIFF as a trial, the
/// trial is its own entry with its own chat, and it ends on Install or Go back.
@MainActor
final class TrialTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    private static let openTrial = #"{"id":"tr_1","n":2,"draft":"d_02","started":"2026-10-21T09:00:00Z","used":"2026-10-21T09:00:00Z"}"#
    private static let ended = #"{"started":"2026-10-21T09:00:00Z","ended":"2026-10-21T10:00:00Z","why":"back","calls":3,"held":1,"refused":0}"#
    private static let v2 = TafsCloud.Version(n: 2, note: "Asks for the order number first", by: "setup", created: Date(), live: false, draft: "d_02")

    /// A model signed in on this phone (account t_01) whose one taf is Rae, depl_1 on tafs
    /// cloud; with `trial`, Rae has that trial open in the home list.
    private func kiffModel(signer: ApprovalSigning, trial: String? = nil) async -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud)
        let rae = trial.map { HomeJSON.rae.replacingOccurrences(of: #""waiting":0,"#, with: #""waiting":0,"trial":\#($0),"#) } ?? HomeJSON.rae
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, #"{"versions":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return (model, try! XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id))
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    private func body(_ request: URLRequest?) -> [String: Any] {
        guard let data = request?.httpBody, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return json
    }

    /// Start trying: the draft shown is the one Face ID signs, KIFF confirms it as a trial,
    /// and only once KIFF says "trying" does the trial open, as its own entry.
    func testStartingATrialConfirmsItsCardAsATrialThenOpensIt() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/try"] = (200, #"{"draft":"d_02"}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"trying","version":"v7"}"#)
        StubProtocol.responses["PUT /v1/tafs/depl_1/trial"] = (200, #"{"trial":\#(Self.openTrial)}"#)

        let chat = await model.startTrying(Self.v2, of: id, shownDraft: "v7")
        let chatID = try XCTUnwrap(chat, model.trialErrors[id] ?? "")
        XCTAssertEqual(signer.signed.first, "tafs.trial.v1|t_01|d_02|v7")
        let confirm = try XCTUnwrap(sent("POST", "/v1/me/agent-drafts/d_02/confirm").first)
        XCTAssertNotNil(confirm.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(body(confirm)["as"] as? String, "trial")
        XCTAssertEqual(body(sent("PUT", "/v1/tafs/depl_1/trial").first)["draft"] as? String, "d_02")
        XCTAssertEqual(model.openTrials[id]?.n, 2)
        XCTAssertEqual(model.tafOfTrialChat(chatID), id)
        // Labelled from what tafs cloud sends, with its own fallback (kiff/tafs#247).
        XCTAssertEqual(model.taf(chatID)?.name, "Rae · v2")
        XCTAssertEqual(model.taf(chatID)?.badge, "Trial")
        XCTAssertEqual(model.taf(chatID)?.now.hasPrefix("Trying version 2"), true)
        XCTAssertNil(model.kiff.setupSigner)
    }

    /// A KIFF that doesn't answer "trying" never opens a trial: nothing would run under it.
    func testATrialKIFFDidntStartIsNotOpened() async throws {
        let (model, id) = await kiffModel(signer: RecordingSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/try"] = (200, #"{"draft":"d_02"}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"confirmed","version":"v7"}"#)

        let chat = await model.startTrying(Self.v2, of: id, shownDraft: "v7")
        XCTAssertNil(chat)
        XCTAssertTrue(sent("PUT", "/v1/tafs/depl_1/trial").isEmpty)
        XCTAssertNil(model.openTrials[id])
        XCTAssertTrue(model.trialErrors[id]?.contains("didn't start a trial") ?? false)
    }

    /// Terms changed since shown: nothing is signed, and the new ones go on screen.
    func testATrialCardChangedSinceShownIsNotSigned() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/try"] = (200, #"{"draft":"d_02"}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v8"}"#)

        let chat = await model.startTrying(Self.v2, of: id, shownDraft: "v7")
        XCTAssertNil(chat)
        XCTAssertTrue(signer.signed.isEmpty)
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
        XCTAssertEqual(model.changedVersionCards[id]?.version, "v8")
    }

    /// The home list brings an open trial: its chat is the trial's own, and its messages and
    /// thread go to the trial's routes, never the taf's live chat.
    func testATrialsChatGoesToTheTrial() async throws {
        let (model, id) = await kiffModel(signer: StubSigner(), trial: Self.openTrial)
        let chat = try XCTUnwrap(model.trialChat(of: id))
        StubProtocol.responses["POST /v1/tafs/depl_1/trial/messages"] = (200, #"{"session":"sess_t","run":"run_t","trial":\#(Self.openTrial)}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/trial/conversation"] = (200, #"{"messages":[{"at":"2026-10-21T09:01:00Z","from":"owner","text":"Refund ch_1.","session":"sess_t"}],"working":{"session":"sess_t","run":"run_t"}}"#)

        let sentOK = await model.sendChat("Refund ch_1.", to: chat)
        XCTAssertTrue(sentOK, model.chatErrors[chat] ?? "")
        XCTAssertEqual(sent("POST", "/v1/tafs/depl_1/trial/messages").count, 1)
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/messages").isEmpty, "nothing went to the live chat")
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/messages/stream").isEmpty)
        XCTAssertEqual(sent("GET", "/v1/tafs/depl_1/trial/conversation").count, 1)
        XCTAssertNil(model.chats[id], "the taf's own chat is untouched")
    }

    /// Go back: no Face ID; the phone ends the trial's Card in KIFF itself, signed, then
    /// tafs cloud records it (kiff/tafs#247), and its entry and chat leave the app.
    func testGoingBackEndsTheTrialWithoutFaceID() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer, trial: Self.openTrial)
        let chat = try XCTUnwrap(model.trialChat(of: id))
        StubProtocol.responses["DELETE /v1/me/agent-drafts/d_02/trial"] = (200, #"{"status":"ended","calls":3,"held":1,"refused":0}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/trial"] = (200, #"{"ended":\#(Self.ended)}"#)

        let done = await model.goBackFromTrial(id)
        XCTAssertTrue(done, model.trialErrors[id] ?? "")
        XCTAssertTrue(signer.reusedUnlock.isEmpty, "no Face ID was asked")
        let end = try XCTUnwrap(sent("DELETE", "/v1/me/agent-drafts/d_02/trial").first, "the phone ends it in KIFF")
        XCTAssertNotNil(end.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(sent("DELETE", "/v1/tafs/depl_1/trial").count, 1)
        XCTAssertNil(model.kiff.setupSigner)
        XCTAssertNil(model.openTrials[id])
        XCTAssertNil(model.trialChat(of: id))
        XCTAssertNil(model.taf(chat))
    }

    /// A trial KIFF had ended already (404 or 409) still goes back: tafs cloud records it.
    func testGoingBackFromATrialKIFFEndedAlreadyStillEndsIt() async throws {
        let (model, id) = await kiffModel(signer: RecordingSigner(), trial: Self.openTrial)
        StubProtocol.responses["DELETE /v1/me/agent-drafts/d_02/trial"] = (409, #"{"error":"not trying"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/trial"] = (200, #"{"ended":\#(Self.ended)}"#)
        let done = await model.goBackFromTrial(id)
        XCTAssertTrue(done, model.trialErrors[id] ?? "")
        XCTAssertNil(model.openTrials[id])
    }

    /// Install: one Face ID confirms the trial's Card live, then tafs cloud makes it live.
    func testInstallingConfirmsTheCardLiveThenEndsTheTrial() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer, trial: Self.openTrial)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"trying","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"confirmed","version":"v7"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/trial/install"] = (200, #"{"live":2,"ended":\#(Self.ended.replacingOccurrences(of: "back", with: "installed"))}"#)

        let done = await model.installTrial(id)
        XCTAssertTrue(done, model.trialErrors[id] ?? "")
        XCTAssertEqual(signer.signed.first, "tafs.cards.v1|t_01|d_02|v7")
        let confirm = try XCTUnwrap(sent("POST", "/v1/me/agent-drafts/d_02/confirm").first)
        XCTAssertNil(body(confirm)["as"], "Install confirms it live, not as a trial")
        let order = StubProtocol.sent.compactMap(\.url?.path)
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: "/v1/me/agent-drafts/d_02/confirm")),
                          try XCTUnwrap(order.firstIndex(of: "/v1/tafs/depl_1/trial/install")), "the Card first, then live")
        XCTAssertNil(model.openTrials[id])
    }

    /// Versions keeps one line about each ended trial.
    func testAnEndedTrialIsOneLine() throws {
        let t = try KIFFClient.decoder.decode(TafsCloud.TrialSummary.self, from: Data(Self.ended.utf8))
        XCTAssertTrue(VersionsPanel.tried(t).hasPrefix("Tried "))
        XCTAssertTrue(VersionsPanel.tried(t).hasSuffix(" · 3 calls, 1 held · went back"))
    }
}
