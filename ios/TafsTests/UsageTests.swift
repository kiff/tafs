import XCTest
@testable import Tafs

/// Usage in the app (kiff/tafs#184), against KIFF's usage read as proposed on
/// kiff/kiff-cloud#1316: both meters, what wasn't counted, the ceiling and the trial. Counts
/// only, and nothing that sells (App Store Review Guideline 3.1.3(f)).
@MainActor
final class UsageTests: XCTestCase {
    static let read = #"""
    {"plan":"trial","period":{"start":"2026-10-20T09:12:00Z","end":"2026-10-27T09:12:00Z"},
     "tasks":{"counted":14,"included":25,"ceiling":25,"ceiling_reached":false,
              "by_agent":[{"agent":"taf-refunds-x7k3","counted":9},{"agent":"taf-chargebacks-p2q8","counted":5}],
              "free":{"refused":3,"held_refused":1,"halted":0,"failed":2,"invalid":0,"retry":1,"read":120,"trial":4,"setup_read":6}},
     "actions":{"counted":40,"included":500,
                "free":{"refused":2,"held_refused":0,"halted":0,"failed":0,"invalid":1,"retry":0,"read":310,"trial":0,"setup_read":0}},
     "trial":{"ends_at":"2026-10-27T09:12:00Z","days_left":5,"tasks_left":11,"actions_left":460}}
    """#

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    func testTheReadDecodes() throws {
        let u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8))
        XCTAssertEqual(u.plan, "trial")
        XCTAssertEqual(u.tasks.counted, 14)
        XCTAssertEqual(u.tasks.ceiling, 25)
        XCTAssertEqual(u.tasks.byAgent?.map(\.agent), ["taf-refunds-x7k3", "taf-chargebacks-p2q8"])
        XCTAssertEqual(u.trial?.tasksLeft, 11)
        XCTAssertNil(u.actions.ceiling, "paid KIFF actions never stop")
        // Only the reasons with a count, in the order they matter.
        XCTAssertEqual(u.tasks.free?.lines.map(\.0), ["Refused by KIFF", "Held, then refused", "Failed at the tool", "Retries", "Reads",
                                                      "Trials of a new version", "Setup's reads"])
        // A reason KIFF leaves out counted nothing, and a paid plan has no trial.
        let paid = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(#"""
        {"plan":"solo","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
         "tasks":{"counted":3,"included":20,"ceiling":40,"ceiling_reached":false,"free":{"read":7}},
         "actions":{"counted":0,"included":500},"trial":null}
        """#.utf8))
        XCTAssertEqual(paid.tasks.free?.lines.map(\.0), ["Reads"])
        XCTAssertNil(paid.trial)
        XCTAssertNil(paid.actions.free)
    }

    func testItSaysWhereThingsStandWithoutSelling() throws {
        var u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8))
        XCTAssertEqual(UsageView.notice(u), "Your trial: 5 days and 11 tasks left.")
        u.trial = .init(endsAt: Date(), daysLeft: 0, tasksLeft: 11, actionsLeft: 460)
        XCTAssertEqual(UsageView.notice(u), "Your trial has ended.")
        u.tasks.ceilingReached = true
        XCTAssertEqual(UsageView.notice(u), "Your trial's tasks are used. Your tafs are paused.")
        let paid = KIFFClient.Usage(plan: "solo", period: u.period,
                                    tasks: .init(counted: 40, included: 20, ceiling: 40, ceilingReached: true),
                                    actions: u.actions, trial: nil)
        XCTAssertEqual(UsageView.notice(paid), "This month's ceiling is reached. Your tafs are paused until the month ends or the ceiling is raised.")
        XCTAssertNil(UsageView.notice(KIFFClient.Usage(plan: "solo", period: u.period, tasks: .init(counted: 3, included: 20, ceiling: 20),
                                                       actions: u.actions, trial: nil)))
        // Guideline 3.1.3(f): no price, no "Upgrade", nothing to buy.
        for text in [UsageView.notice(u), UsageView.notice(paid), UsageView.planName("solo")].compactMap({ $0 }) {
            for word in ["€", "$", "Upgrade", "upgrade", "buy", "Buy", "price", "subscribe"] {
                XCTAssertFalse(text.contains(word), "\(text) says \(word)")
            }
        }
    }

    func testTheSampleHasAMonth() {
        let u = AppModel.sampleUsage(now: Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertEqual(u.plan, "trial")
        XCTAssertLessThanOrEqual(u.tasks.counted, u.tasks.included)
        XCTAssertNotNil(UsageView.notice(u))
    }

    func testItReadsKIFFAndSetsTheCeilingSigned() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let signer = RecordingSigner()
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme"}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")

        StubProtocol.responses["GET /v1/me/usage"] = (200, Self.read.replacingOccurrences(of: #""plan":"trial""#, with: #""plan":"solo""#))
        StubProtocol.responses["GET /v1/me/usage/tasks"] = (200, #"{"items":[{"record":"rec_1","agent":"taf-refunds-x7k3","tool":"refund_order","at":"2026-10-22T08:41:10Z"}],"next":null}"#)
        await model.refreshUsage()
        XCTAssertEqual(model.usage?.tasks.counted, 14)
        XCTAssertEqual(model.countedTasks.map(\.record), ["rec_1"])
        XCTAssertNil(model.usageError)

        XCTAssertTrue(KIFFClient.signsSetup("/v1/me/usage/ceiling"), "raising the ceiling allows more to be billed")
        StubProtocol.responses["PUT /v1/me/usage/ceiling"] = (200, #"{"counted":14,"included":25,"ceiling":40,"ceiling_reached":false}"#)
        let unlocksBefore = signer.signed.count
        let done = await model.setTaskCeiling(40)
        XCTAssertTrue(done)
        let put = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PUT" })
        XCTAssertEqual(try JSONDecoder().decode([String: Int].self, from: try XCTUnwrap(put.httpBody)), ["tasks": 40])
        XCTAssertNotNil(put.value(forHTTPHeaderField: "KIFF-Device-Signature"), "a signed setup call")
        let during = Array(signer.signed.dropFirst(unlocksBefore))
        XCTAssertTrue(during.first?.hasPrefix("tafs.usage-ceiling.v1|t1|40") ?? false, "Face ID first: \(during)")
        XCTAssertEqual(model.usage?.tasks.ceiling, 40)

        StubProtocol.responses["PUT /v1/me/usage/ceiling"] = (400, #"{"error":"the ceiling can't be below what is included"}"#)
        let refused = await model.setTaskCeiling(10)
        XCTAssertFalse(refused)
        XCTAssertNotNil(model.usageError)

        await model.signOut()
        XCTAssertNil(model.usage)
        XCTAssertTrue(model.countedTasks.isEmpty)
    }

    // MARK: AI fair use (kiff/tafs#193)

    func testAIUseDecodesAndSaysWhereTheTafsAskWithoutSelling() throws {
        let u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.replacingOccurrences(
            of: #""plan":"trial","#, with: #""plan":"team","seats":3,"ai":{"go_ons":1},"#).utf8))
        XCTAssertEqual(u.seats, 3)
        XCTAssertEqual(u.ai?.goOns, 1)
        XCTAssertNil(try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8)).ai, "a read before AI fair use")

        let first = try KIFFClient.decoder.decode(TafsCloud.AIUse.self, from: Data(#"{"used_pct":42,"asks_at_pct":100,"paused":false}"#.utf8))
        XCTAssertEqual(first, .init(usedPct: 42, asksAtPct: 100, paused: false))
        let none = try KIFFClient.decoder.decode(TafsCloud.AIUse.self, from: Data(#"{"used_pct":null,"asks_at_pct":null,"paused":false}"#.utf8))
        XCTAssertNil(none.usedPct, "no bound on this account")
        XCTAssertTrue(AIUseView.explain(first).contains("At 100%"))
        XCTAssertTrue(AIUseView.explain(.init(usedPct: 130, asksAtPct: 200, paused: false)).contains("again at 200%"))
        for text in [AIUseView.asking, AIUseView.explain(first), AIUseView.explain(.init(usedPct: 200, asksAtPct: 200, paused: true))] {
            for word in ["€", "$", "Upgrade", "upgrade", "buy", "Buy", "price", "subscribe"] {
                XCTAssertFalse(text.contains(word), "\(text) says \(word)")
            }
        }
    }

    func testGoOnIsSignedBehindFaceIDAndReadsTheUseAgain() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let signer = RecordingSigner()
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme"}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")

        StubProtocol.responses["GET /v1/me/usage"] = (200, Self.read.replacingOccurrences(of: #""plan":"trial""#, with: #""plan":"solo""#))
        StubProtocol.responses["GET /v1/me/usage/tasks"] = (200, #"{"items":[],"next":null}"#)
        StubProtocol.responses["GET /v1/usage/ai"] = (200, #"{"used_pct":100,"asks_at_pct":100,"paused":true}"#)
        await model.refreshUsage()
        XCTAssertEqual(model.aiUse, .init(usedPct: 100, asksAtPct: 100, paused: true))

        XCTAssertTrue(KIFFClient.signsSetup("/v1/me/usage/ai"), "going on lets the tafs use more")
        StubProtocol.responses["PUT /v1/me/usage/ai"] = (200, #"{"go_ons":1}"#)
        StubProtocol.responses["GET /v1/usage/ai"] = (200, #"{"used_pct":100,"asks_at_pct":200,"paused":false}"#)
        let unlocksBefore = signer.signed.count
        let done = await model.goOnAI()
        XCTAssertTrue(done)
        let put = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PUT" })
        XCTAssertEqual(try JSONDecoder().decode([String: Bool].self, from: try XCTUnwrap(put.httpBody)), ["go_on": true])
        XCTAssertNotNil(put.value(forHTTPHeaderField: "KIFF-Device-Signature"), "a signed setup call")
        let during = Array(signer.signed.dropFirst(unlocksBefore))
        XCTAssertTrue(during.first?.hasPrefix("tafs.ai-go-on.v1|t1|100") ?? false, "Face ID first: \(during)")
        XCTAssertEqual(model.aiUse, .init(usedPct: 100, asksAtPct: 200, paused: false))
        XCTAssertEqual(model.usage?.ai?.goOns, 1)

        StubProtocol.responses["PUT /v1/me/usage/ai"] = (409, #"{"error":"this signature was used"}"#)
        let refused = await model.goOnAI()
        XCTAssertFalse(refused)
        XCTAssertNotNil(model.usageError)

        await model.signOut()
        XCTAssertNil(model.aiUse)
    }
}
