import XCTest
@testable import Tafs

/// A taf's versions on its page (kiff/tafs#107): listed like app builds, tried with KIFF
/// deciding each write, and made live with one Face ID.
@MainActor
final class VersionsTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    /// A model signed in on this phone (account t_01) whose one taf is Rae, depl_1 on tafs cloud.
    private func kiffModel(signer: ApprovalSigning) async -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud)
        model.trialPollInterval = 1_000_000
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        let rae = model.tafs.first { $0.name == "Rae" }
        return (model, try! XCTUnwrap(rae?.id))
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    private static let begun = #"{"switching":{"n":2,"draft":"d_02","at":"2026-10-20T08:00:00Z"}}"#

    private static let twoVersions = #"{"versions":[{"n":1,"by":"setup","created":"2026-10-19T09:00:00Z","digest":"d1","live":true},{"n":2,"note":"Asks for the order number first","by":"setup","created":"2026-10-20T07:00:00.5Z","digest":"d2","live":false,"draft":"d_02"}]}"#

    func testLoadsATafsVersions() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        await model.loadVersions(id)
        let list = try XCTUnwrap(model.versions[id])
        XCTAssertEqual(list.map(\.n), [1, 2])
        XCTAssertNil(list[0].note, "a version with no note still loads")
        XCTAssertTrue(list[0].live)
        XCTAssertEqual(list[1].draft, "d_02")
        XCTAssertNil(model.versionErrors[id])
        XCTAssertNil(model.switches[id])

        // A switch that didn't finish shows, to finish or cancel.
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, #"{"versions":[],"switching":{"n":2,"draft":"d_02","at":"2026-10-20T08:00:00Z"}}"#)
        await model.loadVersions(id)
        XCTAssertEqual(model.switches[id]?.n, 2)
    }

    func testAVersionKiffAgentsWroteNamesNoOne() {
        let at = Date(timeIntervalSince1970: 0)
        let upgrade = TafsCloud.Version(n: 3, note: "Its rules were updated", by: nil, created: at, live: true, draft: nil)
        XCTAssertFalse(VersionsPanel.byline(upgrade).contains("By"))
        let setup = TafsCloud.Version(n: 2, note: nil, by: "setup", created: at, live: false, draft: "d_02")
        XCTAssertTrue(VersionsPanel.byline(setup).hasPrefix("By Setup · "))
        XCTAssertTrue(VersionsPanel.byline(setup).hasSuffix(" · brings a new Card"))
    }

    func testATrialIsFollowedUntilItStops() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/trial"] = (202, #"{"run":{"id":"run_t","status":"PENDING"}}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/trials/run_t"] = (200, #"{"run":{"id":"run_t","status":"RUNNING"},"writes":[{"tool":"refund","title":"Refund #4502","arguments":{"amount":4800},"decision":"allowed"}]}"#)
        StubProtocol.thenRespond["GET /v1/tafs/depl_1/trials/run_t"] = [
            "GET /v1/tafs/depl_1/trials/run_t": (200, #"{"run":{"id":"run_t","status":"COMPLETED","output":"Refunded one, held one."},"writes":[{"tool":"refund","title":"Refund #4502","arguments":{},"decision":"allowed"},{"tool":"refund","arguments":{},"decision":"held","reason":"over 200 EUR"}]}"#),
        ]
        await model.tryVersion(2, of: id)
        let trial = try XCTUnwrap(model.trials[id])
        XCTAssertEqual(trial.version, 2)
        XCTAssertTrue(trial.trial.run.finished)
        XCTAssertEqual(trial.trial.writes.map(\.decision), ["allowed", "held"])
        XCTAssertEqual(trial.trial.writes[1].reason, "over 200 EUR")
        XCTAssertEqual(trial.trial.run.output, "Refunded one, held one.")
        let start = try XCTUnwrap(sent("POST", "/v1/tafs/depl_1/versions/2/trial").first)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: start.httpBody ?? Data()) as? [String: String], ["input": ""])
    }

    func testARefusedTrialSaysWhy() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/trial"] = (502, #"{"error":"the gateway can't run trials"}"#)
        await model.tryVersion(2, of: id)
        XCTAssertNil(model.trials[id])
        XCTAssertTrue(model.versionErrors[id]?.contains("can't run trials") ?? false)
    }

    /// A version that brings a Card revision: the switch begins (nothing of the taf runs
    /// from then), one Face ID confirms its draft in KIFF, the version shown, and only then
    /// is it made live.
    func testUsingAVersionConfirmsItsCardThenSwitches() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"confirmed","version":"v7"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/live"] = (200, #"{"live":2}"#)
        await model.loadVersions(id)
        let v2 = try XCTUnwrap(model.versions[id]?.last)

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        XCTAssertEqual(signer.signed.first, "tafs.cards.v1|t_01|d_02|v7")
        let confirm = try XCTUnwrap(sent("POST", "/v1/me/agent-drafts/d_02/confirm").first)
        XCTAssertNotNil(confirm.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(sent("POST", "/v1/tafs/depl_1/versions/2/live").count, 1)
        let order = StubProtocol.sent.compactMap(\.url?.path)
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: "/v1/tafs/depl_1/versions/2/switch")),
                          try XCTUnwrap(order.firstIndex(of: "/v1/me/agent-drafts/d_02/confirm")), "the switch begun before the Card changes")
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: "/v1/me/agent-drafts/d_02/confirm")),
                          try XCTUnwrap(order.firstIndex(of: "/v1/tafs/depl_1/versions/2/live")), "the Card first, then the switch")
        XCTAssertNil(model.kiff.setupSigner)
        XCTAssertNil(model.switchingVersion[id])
    }

    /// Back to a version with the taf's current Cards: one Face ID approves the switch itself.
    func testGoingBackToAVersionWithoutACardAsksOnce() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/live"] = (200, #"{"live":1}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v1 = TafsCloud.Version(n: 1, note: nil, by: "setup", created: Date(), live: false, draft: nil)

        let done = await model.useVersion(v1, of: id)
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        XCTAssertEqual(signer.signed, ["tafs.version.v1|t_01|depl_1|1"])
        XCTAssertTrue(StubProtocol.sent.filter { $0.url?.path.hasPrefix("/v1/me/agent-drafts") ?? false }.isEmpty)
        XCTAssertEqual(sent("POST", "/v1/tafs/depl_1/versions/1/live").count, 1)
    }

    /// A draft already claimed has its Cards: the switch is approved, not the draft again.
    func testAClaimedDraftIsNotConfirmedAgain() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"claimed","version":"v7"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/live"] = (200, #"{"live":2}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        XCTAssertEqual(signer.signed, ["tafs.version.v1|t_01|depl_1|2"])
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
    }

    /// A version that brings a Card with nothing shown: nothing begins, nothing is signed.
    func testNothingShownNothingSigned() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")
        let done = await model.useVersion(v2, of: id)
        XCTAssertFalse(done)
        XCTAssertTrue(signer.signed.isEmpty)
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions/2/switch").isEmpty)
    }

    /// The Card changed between the screen and the tap (kiff/tafs#115): the new one goes on
    /// screen, the switch is dropped, and nothing is signed or confirmed.
    func testACardChangedSinceShownIsNotSigned() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v8"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/switching"] = (200, #"{"cancelled":true}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertTrue(signer.signed.isEmpty, "no Face ID over terms that weren't shown")
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
        XCTAssertEqual(sent("DELETE", "/v1/tafs/depl_1/switching").count, 1)
        XCTAssertEqual(model.changedVersionCards[id]?.version, "v8")
        XCTAssertTrue(model.versionErrors[id]?.contains("changed since it was shown") ?? false)
    }

    /// The Card a version brings reads now → after (kiff-cloud#1287).
    func testAVersionsCardReadsNowAndAfter() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7","cards":[{"revises":"m_refund","current":{"grants":[{"action":"create_refund","per_action_max":[{"quantity":"sum(amount)","max":500,"unit":"EUR","scale":2}]}]},"grants":[{"action":"create_refund","per_action_max":[{"quantity":"sum(amount)","max":2000,"unit":"EUR","scale":2}]}]},{"grants":[{"action":"retrieve_charge"}]}]}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")
        let draft = await model.versionCard(v2, of: id)
        let cards = try XCTUnwrap(draft?.cards)
        XCTAssertEqual(cards.count, 2)
        XCTAssertEqual(cards[0].revises, "m_refund")
        XCTAssertEqual(cards[0].current?.lines.count, 1)
        XCTAssertNotEqual(cards[0].current?.lines, cards[0].lines, "now and after differ")
        XCTAssertNil(cards[1].current, "a new Card has no now")
    }

    private func improvement(_ json: String) throws -> SetupComponent {
        try KIFFClient.decoder.decode(SetupComponent.self, from: Data(json.utf8))
    }

    /// An improvement's result makes the taf's next version through tafs cloud, never a
    /// new taf (kiff/tafs#107), and names the result it came from, so a repeat is the same.
    func testAnImprovementResultMakesTheNextVersion() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions"] = (200, #"{"version":2}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let c = try improvement(#"{"type":"result","id":"v2","text":"Ready as version 2.","taf":{"agent":"rae-k3f9","name":"Rae","job":"refunds","improves":"depl_1","note":"Asks before refunds over 50 EUR.","notes":"Ask first.","draft":"d_02"}}"#)
        await model.makeImprovedVersion(c)
        XCTAssertEqual(model.improvedVersions["v2"], 2, model.improveErrors["v2"] ?? "")
        let posted = try XCTUnwrap(sent("POST", "/v1/tafs/depl_1/versions").first)
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: posted.httpBody ?? Data()) as? [String: String])
        XCTAssertEqual(body["note"], "Asks before refunds over 50 EUR.")
        XCTAssertEqual(body["notes"], "Ask first.")
        XCTAssertEqual(body["draft"], "d_02")
        XCTAssertEqual(body["from"], "v2|d_02")
        XCTAssertNotNil(model.versions[id], "its versions read again")

        await model.startSetupTaf(c)
        XCTAssertTrue(sent("POST", "/v1/tafs").isEmpty, "an improvement never starts a new taf")
    }

    func testAnImprovementThatFailsSaysWhy() async throws {
        let (model, _) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions"] = (409, #"{"error":"that draft isn't this taf's"}"#)
        let c = try improvement(#"{"type":"result","id":"v2","text":"x","taf":{"agent":"rae-k3f9","name":"Rae","job":"refunds","improves":"depl_1","note":"x","draft":"d_09"}}"#)
        await model.makeImprovedVersion(c)
        XCTAssertNil(model.improvedVersions["v2"])
        XCTAssertTrue(model.improveErrors["v2"]?.contains("isn't this taf's") ?? false)
    }

    /// The owner backs out at Face ID: the switch is dropped, so the taf runs again, and
    /// nothing reaches KIFF.
    func testBackingOutAtFaceIDDropsTheSwitch() async throws {
        let (model, id) = await kiffModel(signer: BackingOutSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/switching"] = (200, #"{"cancelled":true}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertEqual(sent("DELETE", "/v1/tafs/depl_1/switching").count, 1)
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions/2/live").isEmpty)
        XCTAssertNil(model.switches[id])
    }

    /// KIFF doesn't confirm the Card: the switch stays begun (the taf doesn't run), and the
    /// page says it didn't finish, to finish or cancel.
    func testASwitchThatStopsHalfWayStaysToFinish() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (502, #"{"error":"try again"}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertEqual(model.switches[id]?.n, 2)
        XCTAssertTrue(model.versionErrors[id]?.contains("didn't finish") ?? false, model.versionErrors[id] ?? "")
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions/2/live").isEmpty)
        XCTAssertTrue(sent("DELETE", "/v1/tafs/depl_1/switching").isEmpty, "a Card that may be confirmed is never dropped from here")
    }

    /// A run of the taf is going: the switch doesn't begin, and Face ID isn't asked.
    func testNoSwitchWhileARunGoes() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (409, #"{"error":"a run of this agent is going or waiting on its owner: answer it, stop it or let it finish, then switch"}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertTrue(signer.signed.isEmpty)
        XCTAssertTrue(model.versionErrors[id]?.contains("answer it, stop it or let it finish") ?? false)
    }

    /// tafs cloud refuses the switch while the Card isn't confirmed: the page says so.
    func testARefusedSwitchSaysWhy() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/live"] = (409, #"{"error":"confirm this version's Card first"}"#)
        let v1 = TafsCloud.Version(n: 1, note: nil, by: "setup", created: Date(), live: false, draft: nil)
        let done = await model.useVersion(v1, of: id)
        XCTAssertFalse(done)
        XCTAssertTrue(model.versionErrors[id]?.contains("confirm this version's Card first") ?? false)
    }

    func testTheLiveVersionIsNotSwitchedAgain() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        let live = TafsCloud.Version(n: 1, note: nil, by: "setup", created: Date(), live: true, draft: nil)
        let done = await model.useVersion(live, of: id)
        XCTAssertFalse(done)
        XCTAssertTrue(signer.signed.isEmpty)
    }
}

/// The owner backs out at the Face ID prompt.
struct BackingOutSigner: ApprovalSigning {
    struct Cancelled: Error {}
    var publicKeyDER: Data { Data([0x30, 0x59]) }
    func sign(_ message: Data, reason: String) async throws -> String { throw Cancelled() }
    func signConfirmed(_ message: Data) throws -> String { throw Cancelled() }
}
