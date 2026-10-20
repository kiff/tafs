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

    /// A version that brings a Card revision: one Face ID confirms its draft in KIFF, the
    /// version shown, and only then is it made live.
    func testUsingAVersionConfirmsItsCardThenSwitches() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"confirmed","version":"v7"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/live"] = (200, #"{"live":2}"#)
        await model.loadVersions(id)
        let v2 = try XCTUnwrap(model.versions[id]?.last)

        let done = await model.useVersion(v2, of: id)
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        XCTAssertEqual(signer.signed.first, "tafs.cards.v1|t_01|d_02|v7")
        let confirm = try XCTUnwrap(sent("POST", "/v1/me/agent-drafts/d_02/confirm").first)
        XCTAssertNotNil(confirm.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(sent("POST", "/v1/tafs/depl_1/versions/2/live").count, 1)
        let order = StubProtocol.sent.compactMap(\.url?.path)
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
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"claimed","version":"v7"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/live"] = (200, #"{"live":2}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id)
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        XCTAssertEqual(signer.signed, ["tafs.version.v1|t_01|depl_1|2"])
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
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
