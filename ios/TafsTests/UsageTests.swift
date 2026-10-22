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

    /// Our own accounts (internal) and Company have no limit: KIFF sends included 0 and
    /// ceiling 0, which reads as the count alone, and there's no ceiling to set.
    func testNoLimitReadsAsTheCountAlone() throws {
        let u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(#"""
        {"plan":"internal","seats":0,"period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
         "tasks":{"counted":472,"included":0,"ceiling":0,"ceiling_reached":false,"by_agent":[],"free":{}},
         "actions":{"counted":909,"included":0,"free":{}},"trial":null,"ai":{"go_ons":0}}
        """#.utf8))
        XCTAssertEqual(UsageView.count(u.tasks.counted, of: u.tasks.included, "tasks"), "472 tasks")
        XCTAssertEqual(UsageView.count(u.actions.counted, of: u.actions.included, "actions"), "909 actions")
        XCTAssertEqual(UsageView.count(14, of: 25, "tasks"), "14 of 25 tasks")
        XCTAssertEqual(u.tasks.ceiling, 0, "no ceiling row: there's nothing to set")
        XCTAssertNil(UsageView.notice(u))
    }

    /// A trial that hasn't started (no checkout yet) decodes, and says so without selling.
    func testATrialNotStartedDecodesAndSaysSo() throws {
        let u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.replacingOccurrences(
            of: #""trial":{"ends_at":"2026-10-27T09:12:00Z","days_left":5,"tasks_left":11,"actions_left":460}"#,
            with: #""trial":{"ends_at":null,"days_left":0,"tasks_left":25,"actions_left":500}"#).utf8))
        XCTAssertNil(u.trial?.endsAt)
        XCTAssertEqual(UsageView.notice(u), "Your trial hasn't started.")
    }

    // MARK: The follow-up on #184

    func testATaskCountsWhenItWorkedAndToolsReadAsWords() {
        XCTAssertTrue(UsageView.taskMeaning.contains("allowed, sent, and that worked"))
        XCTAssertTrue(UsageView.taskMeaning.contains("lost answers"))
        XCTAssertEqual(UsageView.toolWords("refund_order"), "Refund order")
        XCTAssertEqual(UsageView.tafCount(1), "1 task this period")
        XCTAssertEqual(UsageView.tafCount(9), "9 tasks this period")
    }

    /// A taf's page counts its own tasks, from KIFF's by_agent under the agent it acts as.
    func testATafsPageCountsItsOwnTasks() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: RecordingSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme"}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" })
        XCTAssertNil(model.countedTasks(for: rae.id), "nothing until usage is read")

        // kiff-cloud#1334's contract: by_agent carries its own free, tasks filter by agent, and
        // each task names its tool as the Record does.
        StubProtocol.responses["GET /v1/me/usage"] = (200, Self.read.replacingOccurrences(
            of: #"{"agent":"taf-refunds-x7k3","counted":9}"#,
            with: #"{"agent":"rae-k3f9","counted":9,"free":{"refused":1,"held_refused":0,"halted":0,"failed":1,"invalid":0,"retry":1,"read":80,"trial":0,"setup_read":6}}"#))
        StubProtocol.responses["GET /v1/me/usage/tasks"] = (200, #"{"items":[],"next":null}"#)
        StubProtocol.responses["GET /v1/me/usage/tasks?agent=rae-k3f9"] = (200,
            #"{"items":[{"record":"tc-1","agent":"rae-k3f9","tool":"refund_order","tool_title":"Refund an order","at":"2026-10-22T08:41:10Z"}],"next":null}"#)
        await model.refreshTafUsage(rae.id)
        XCTAssertEqual(model.kiffAgent(for: rae.id), "rae-k3f9")
        XCTAssertEqual(model.countedTasks(for: rae.id), 9)
        XCTAssertEqual(model.tafUsage(for: rae.id)?.free?.lines.map(\.0), ["Refused by KIFF", "Failed at the tool", "Retries", "Reads", "Setup's reads"])
        let task = try XCTUnwrap(model.tafTasks[rae.id]?.first)
        XCTAssertEqual(UsageView.toolName(task), "Refund an order", "named as the Record names it")
        XCTAssertNil(model.recordEntry(forCall: "tc-not-in-the-record"))
        // Without a title, the tool in words.
        XCTAssertEqual(UsageView.toolName(.init(record: "tc-2", agent: "rae-k3f9", tool: "refund_order", at: Date())), "Refund order")
    }

    /// The live read, as KIFF answered the eval account on 22 Oct (kiff-cloud#1337 deployed at
    /// ebc04927), trimmed to three tafs and one task: it decodes whole, including KIFF's
    /// microsecond times and an empty tool_title.
    func testTheLiveReadDecodes() throws {
        let u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(#"""
        {"plan":"internal","seats":0,"period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},"tasks":{"counted":448,"included":0,"ceiling":0,"ceiling_reached":false,"by_agent":[{"agent":"e2e-setup","counted":51,"free":{"failed":9,"halted":0,"held_refused":0,"invalid":0,"read":6,"refused":0,"retry":0,"setup_read":0,"trial":0}},{"agent":"reembolsos-vseezv4h","counted":11,"free":{"failed":0,"halted":0,"held_refused":2,"invalid":0,"read":0,"refused":0,"retry":0,"setup_read":0,"trial":2}},{"agent":"reembolsos","counted":0,"free":{"failed":0,"halted":0,"held_refused":0,"invalid":0,"read":0,"refused":0,"retry":0,"setup_read":0,"trial":0}}],"free":{"failed":19,"halted":0,"held_refused":90,"invalid":0,"read":11,"refused":7,"retry":0,"setup_read":0,"trial":4}},"actions":{"counted":927,"included":0,"free":{"failed":152,"halted":0,"held_refused":0,"invalid":0,"read":1348,"refused":157,"retry":0,"setup_read":0,"trial":0}},"trial":null,"ai":{"go_ons":0}}
        """#.utf8))
        XCTAssertEqual(u.plan, "internal")
        XCTAssertEqual(u.tasks.counted, 448)
        XCTAssertEqual(u.tasks.byAgent?.count, 3)
        XCTAssertEqual(u.tasks.byAgent?.first?.free?.failed, 9)
        XCTAssertEqual(u.tasks.byAgent?.filter { $0.counted > 0 }.count, 2, "a taf at 0 isn't shown under By taf")
        XCTAssertEqual(UsageView.count(u.tasks.counted, of: u.tasks.included, "tasks"), "448 tasks")

        struct Page: Decodable { let items: [KIFFClient.CountedTask]; var next: String? }
        let page = try KIFFClient.decoder.decode(Page.self, from: Data(#"""
        {"items":[{"record":"tc-10aa90622c8e13a88b572bb80ff82083bdd0f2c6","agent":"e2e-setup","tool":"api_details_kc8m","at":"2026-10-22T18:44:37.973785Z","tool_title":""}],"next":"MjAyNi0xMC0yMlQxMDoyNTo1MS43ODcyNzlafHRjLWMyODQxMTQyODZjNzcyNGRlYjcyY2M3ZTlhYjg0YzMzNTU2ZmUzMDA"}
        """#.utf8))
        let task = try XCTUnwrap(page.items.first)
        XCTAssertEqual(task.toolTitle, "")
        XCTAssertEqual(UsageView.toolName(task), "Api details kc8m", "an empty title falls back to the tool in words")
        XCTAssertNotNil(page.next)
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
