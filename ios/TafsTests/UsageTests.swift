import XCTest
@testable import Tafs

/// Usage in the app (kiff/tafs#184), against KIFF's usage read: both meters, the tasks the
/// period has and what's left, what wasn't counted, and the trial.
@MainActor
final class UsageTests: XCTestCase {
    static let read = #"""
    {"plan":"trial","period":{"start":"2026-10-20T09:12:00Z","end":"2026-10-27T09:12:00Z"},
     "tasks":{"counted":14,"included":25,"packs":0,"left":11,"ceiling":25,"ceiling_reached":false,
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
        XCTAssertEqual(u.tasks.total, 25)
        XCTAssertEqual(u.tasks.left, 11)
        XCTAssertEqual(u.tasks.byAgent?.map(\.agent), ["taf-refunds-x7k3", "taf-chargebacks-p2q8"])
        XCTAssertEqual(u.trial?.tasksLeft, 11)
        XCTAssertNil(u.actions.left, "paid KIFF actions never stop")
        // Only the reasons with a count, in the order they matter.
        XCTAssertEqual(u.tasks.free?.lines.map(\.0), ["Refused by KIFF", "Held, then refused", "Failed at the tool", "Retries", "Reads",
                                                      "Trials of a new version", "Coach and Setup"])
        // A reason KIFF leaves out counted nothing, and a paid plan has no trial.
        let paid = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(#"""
        {"plan":"solo","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
         "tasks":{"counted":3,"included":20,"packs":20,"left":37,"free":{"read":7}},
         "actions":{"counted":0,"included":500},"trial":null}
        """#.utf8))
        XCTAssertEqual(paid.tasks.free?.lines.map(\.0), ["Reads"])
        XCTAssertNil(paid.trial)
        XCTAssertNil(paid.actions.free)
    }

    func testItSaysWhereThingsStand() throws {
        var u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8))
        // Read on 22 Oct, while the trial runs: the real clock is past its end (27 Oct).
        XCTAssertEqual(UsageView.notice(u, now: Self.now), "Your trial: 5 days and 11 tasks left.")
        XCTAssertEqual(UsageView.leftLine(u), "11 tasks left")
        u.trial = .init(endsAt: Date(), daysLeft: 0, tasksLeft: 11, actionsLeft: 460)
        XCTAssertEqual(UsageView.notice(u), "Your trial has ended.")
        // A running trial whose tasks are used: its end is still ahead.
        u.trial = .init(endsAt: Date().addingTimeInterval(3 * 86_400), daysLeft: 3, tasksLeft: 0, actionsLeft: 460)
        u.tasks.left = 0
        XCTAssertEqual(UsageView.notice(u), "Your trial's tasks are used. Your tafs are paused until tasks are added.")
        let paid = KIFFClient.Usage(plan: "solo", period: u.period,
                                    tasks: .init(counted: 90, included: 20, packs: 70, left: 0),
                                    actions: u.actions, trial: nil)
        XCTAssertEqual(UsageView.notice(paid), "This period's tasks are used. Your tafs are paused until tasks are added or the next period starts.")
        XCTAssertEqual(UsageView.count(paid.tasks.counted, of: paid.tasks.total, "tasks"), "90 of 90 tasks")
        let some = KIFFClient.Usage(plan: "solo", period: u.period, tasks: .init(counted: 30, included: 20, packs: 70, left: 60),
                                    actions: u.actions, trial: nil)
        XCTAssertNil(UsageView.notice(some))
        XCTAssertEqual(UsageView.leftLine(some), "60 tasks left · 20 included, 70 from packs")
    }

    /// Coach's and Setup's calls read under either name KIFF gives them, `setup_read` now and
    /// `setup` once every call with a setup_reader key is free (#206 Part 2), as one line.
    func testCoachAndSetupReadUnderEitherName() throws {
        let free = try KIFFClient.decoder.decode(KIFFClient.Usage.Free.self, from: Data(#"{"setup_read":2,"setup":4}"#.utf8))
        XCTAssertEqual(free.setupRead, 6)
        XCTAssertEqual(free.lines.map(\.0), ["Coach and Setup"])
        let renamed = try KIFFClient.decoder.decode(KIFFClient.Usage.Free.self, from: Data(#"{"setup":3}"#.utf8))
        XCTAssertEqual(renamed.lines.first?.1, 3)
    }

    func testTheSampleHasAMonth() {
        let u = AppModel.sampleUsage(now: Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertEqual(u.plan, "trial")
        XCTAssertLessThanOrEqual(u.tasks.counted, u.tasks.included)
        XCTAssertNotNil(UsageView.notice(u))
    }

    func testItReadsKIFFsUsage() async throws {
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

        // Each refresh reads the plan too, for Settings' Plan card and Home's line.
        StubProtocol.responses["GET /v1/me/usage"] = (200, Self.read.replacingOccurrences(of: #""plan":"trial""#, with: #""plan":"team""#))
        StubProtocol.sent = []
        await model.refresh()
        XCTAssertEqual(model.usage?.plan, "team")
        XCTAssertTrue(StubProtocol.sent.contains { $0.url?.path == "/v1/me/usage" })
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.path == "/v1/me/usage/tasks" }, "counted tasks wait for Plan & usage")

        await model.signOut()
        XCTAssertNil(model.usage)
        XCTAssertTrue(model.countedTasks.isEmpty)
    }

    /// Our own accounts (internal) and Company have no limit: KIFF sends included 0 and no
    /// tasks left, which reads as the count alone.
    func testNoLimitReadsAsTheCountAlone() throws {
        let u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(#"""
        {"plan":"internal","seats":0,"period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
         "tasks":{"counted":472,"included":0,"packs":0,"left":null,"by_agent":[],"free":{}},
         "actions":{"counted":909,"included":0,"free":{}},"trial":null,"ai":{"go_ons":0}}
        """#.utf8))
        XCTAssertEqual(UsageView.count(u.tasks.counted, of: u.tasks.included, "tasks"), "472 tasks")
        XCTAssertEqual(UsageView.count(u.actions.counted, of: u.actions.included, "actions"), "909 actions")
        XCTAssertEqual(UsageView.count(14, of: 25, "tasks"), "14 of 25 tasks")
        XCTAssertNil(u.tasks.left, "no limit")
        XCTAssertNil(UsageView.leftLine(u))
        XCTAssertNil(UsageView.notice(u))
        // A plan that isn't sold has no name to show: the count is all tafs' (kiff/tafs#245, item 2).
        XCTAssertNil(UsageView.planName(u.plan))
        XCTAssertEqual(UsageView.whose(u), "All tafs")
        XCTAssertEqual(UsageView.othersLine(u), "909 actions")
    }

    /// Usage reads at a glance (kiff/tafs#245, item 2): the account's screen is the account's, each
    /// count says whose it is, and other agents show only once they did something.
    func testEachCountSaysWhoseItIs() throws {
        let u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8))
        XCTAssertEqual(UsageView.title, "Plan & usage")
        XCTAssertEqual(UsageView.whose(u), "All tafs · Trial")
        XCTAssertEqual(UsageView.planName("solo"), "Solo")
        let none = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(#"""
        {"plan":"internal","seats":0,"period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
         "tasks":{"counted":8,"included":0,"packs":0,"left":null,"by_agent":[],"free":{}},
         "actions":{"counted":0,"included":0,"free":{}},"trial":null,"ai":{"go_ons":0}}
        """#.utf8))
        XCTAssertNil(UsageView.othersLine(none), "no line asks what 0 means")
        XCTAssertEqual(UsageView.tasks(1), "1 task")
        XCTAssertEqual(UsageView.tasks(8), "8 tasks")
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

    func testToolsReadAsWordsAndATafCountsItsPeriod() {
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

        // KIFF's usage contract: by_agent carries its own free, tasks filter by agent, and
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
        XCTAssertEqual(model.tafUsage(for: rae.id)?.free?.lines.map(\.0), ["Refused by KIFF", "Failed at the tool", "Retries", "Reads", "Coach and Setup"])
        let task = try XCTUnwrap(model.tafTasks[rae.id]?.first)
        XCTAssertEqual(UsageView.toolName(task), "Refund an order", "named as the Record names it")
        XCTAssertNil(model.recordEntry(forCall: "tc-not-in-the-record"))
        // Without a title, the tool in words.
        XCTAssertEqual(UsageView.toolName(.init(record: "tc-2", agent: "rae-k3f9", tool: "refund_order", at: Date())), "Refund order")
    }

    /// The live read, as KIFF answered the eval account on 22 Oct (deployed at
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


    // MARK: The ended trial, and the plan in sight

    /// 22 Oct 2026, 12:00 UTC, while `read`'s trial runs (it ends 27 Oct): the tests read the
    /// trial against a fixed now, never the clock.
    static let now = Date(timeIntervalSince1970: 1_792_670_400)

    /// What KIFF sends an ended trial today: no tasks on purpose, packs held, none on sale.
    static let ended = #"""
    {"plan":"trial","period":{"start":"2026-10-13T00:00:00Z","end":"2026-10-20T00:00:00Z"},
     "tasks":{"counted":0,"included":0,"packs":0,"left":0},"actions":{"counted":0,"included":500},
     "trial":{"ends_at":"2026-10-20T00:00:00Z","days_left":0,"tasks_left":0,"actions_left":0},
     "pack_list":[{"id":"pico","name":"Pico","tasks":12,"tasks_left":12,"expires_at":"2027-11-01T00:00:00Z"}],
     "can_add_tasks":true,"can_add_packs":false,
     "store":{"solo_product":"bot.tafs.solo","packs":[{"id":"pico","name":"Pico","line":"Pico · 12 tasks","tasks":12,"months":12,"apple_product":"bot.tafs.pack.pico"}]}}
    """#

    func usage(_ json: String) throws -> KIFFClient.Usage {
        try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(json.utf8))
    }

    /// An ended trial says so, before used-up (its `left` is 0 by design), with Solo where it's
    /// offered; it runs no period, so no end date and no red meter.
    func testAnEndedTrialSaysSoNotUsedUp() throws {
        let u = try usage(Self.ended)
        XCTAssertTrue(u.tasks.usedUp, "KIFF gives an ended trial left 0")
        XCTAssertTrue(UsageView.trialEnded(u, now: Self.now))
        XCTAssertTrue(UsageView.noPeriod(u, now: Self.now))
        XCTAssertEqual(UsageView.notice(u, now: Self.now), "Your trial has ended.")
        XCTAssertTrue(UsageView.offersSolo(u))
        XCTAssertEqual(UsageView.startSolo, "Start Solo to keep your tafs running.")
        // Not started: no period either.
        var fresh = u
        fresh.trial = .init(endsAt: nil, daysLeft: 7, tasksLeft: 25, actionsLeft: 500)
        fresh.tasks = .init(counted: 0, included: 25, packs: 0, left: 25)
        XCTAssertTrue(UsageView.noPeriod(fresh, now: Self.now))
        XCTAssertEqual(UsageView.notice(fresh, now: Self.now), "Your trial hasn't started.")
        // A running trial has its period.
        let running = try usage(Self.read)
        XCTAssertFalse(UsageView.noPeriod(running, now: Self.now))
        XCTAssertFalse(UsageView.trialEnded(running, now: Self.now))
    }

    /// Packs are on sale only while a plan runs (`can_add_packs`), falling back to
    /// `can_add_tasks` from a KIFF that doesn't say; held ones count once Solo starts.
    func testPacksOnlyWhileAPlanRuns() throws {
        var u = try usage(Self.ended)
        XCTAssertFalse(u.packsOffered)
        XCTAssertEqual(u.packList?.map(\.name), ["Pico"])
        XCTAssertEqual(UsageView.packsWaitForSolo, "Counts once Solo starts.")
        u.canAddPacks = true
        XCTAssertTrue(u.packsOffered)
        u.canAddPacks = nil
        XCTAssertTrue(u.packsOffered, "an older KIFF: whoever may add tasks")
        u.canAddTasks = false
        XCTAssertFalse(u.packsOffered)
    }

    /// "1 task", never "1 tasks", wherever a count is said.
    func testOneTaskInTheSingular() {
        XCTAssertEqual(UsageView.count(1, of: 0, "tasks"), "1 task")
        XCTAssertEqual(UsageView.count(0, of: 1, "tasks"), "0 of 1 task")
        XCTAssertEqual(UsageView.count(1, of: 20, "tasks"), "1 of 20 tasks")
        XCTAssertEqual(UsageView.count(1, of: 0, "actions"), "1 action")
        XCTAssertEqual(UsageView.tasks(1), "1 task")
        XCTAssertEqual(UsageView.tafCount(1), "1 task this period")
    }

    /// Settings' Plan card: one line for each plan, in its tone, as Android's.
    func testThePlanCardSaysThePlan() throws {
        let ended = try usage(Self.ended)
        XCTAssertEqual(UsageView.planLine(ended, now: Self.now), "Trial ended · Start Solo")
        XCTAssertEqual(UsageView.planTone(ended, now: Self.now), .attention)
        var member = ended
        member.canAddTasks = false
        XCTAssertEqual(UsageView.planLine(member, now: Self.now), "Trial ended", "Solo isn't offered to a member")

        var trial = try usage(Self.read)
        trial.trial = .init(endsAt: Self.now.addingTimeInterval(5 * 86_400), daysLeft: 5, tasksLeft: 18, actionsLeft: 400)
        trial.tasks.left = 18
        XCTAssertEqual(UsageView.planLine(trial, now: Self.now), "Trial · 5 days and 18 tasks left")
        XCTAssertEqual(UsageView.planTone(trial, now: Self.now), .calm)
        trial.trial = .init(endsAt: Self.now.addingTimeInterval(86_400), daysLeft: 1, tasksLeft: 1, actionsLeft: 400)
        trial.tasks.left = 1
        XCTAssertEqual(UsageView.planLine(trial, now: Self.now), "Trial · 1 day and 1 task left")
        trial.trial = .init(endsAt: nil, daysLeft: 7, tasksLeft: 25, actionsLeft: 500)
        XCTAssertEqual(UsageView.planLine(trial, now: Self.now), "Trial · not started")
        XCTAssertEqual(UsageView.planTone(trial, now: Self.now), .attention)

        // A billed plan: "until" its period's end, true whether it renews or not.
        var solo = try usage(#"""
        {"plan":"solo","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T12:00:00Z"},
         "tasks":{"counted":8,"included":20,"packs":0,"left":12},"actions":{"counted":0,"included":500},
         "trial":null,"billing":{"source":"apple"}}
        """#)
        let until = solo.period.end.shown(date: .abbreviated, time: .omitted)
        XCTAssertEqual(UsageView.planLine(solo, now: Self.now), "Solo · 12 of 20 tasks left until \(until) · Apple")
        XCTAssertEqual(UsageView.planTone(solo, now: Self.now), .calm)
        solo.billing = .init(source: "google")
        XCTAssertEqual(UsageView.planLine(solo, now: Self.now), "Solo · 12 of 20 tasks left until \(until) · Google Play")
        solo.billing = .init(source: "stripe")
        XCTAssertEqual(UsageView.planLine(solo, now: Self.now), "Solo · 12 of 20 tasks left until \(until) · KIFF")
        solo.tasks.left = 0
        XCTAssertEqual(UsageView.planTone(solo, now: Self.now), .out, "red when used up")
        solo.billing = .init(source: "contract")
        solo.tasks.left = 12
        XCTAssertEqual(UsageView.planLine(solo, now: Self.now), "Solo · 12 of 20 tasks left", "no end date where no one bills it")

        // No limit: the count; our own accounts say Internal.
        let company = try usage(#"""
        {"plan":"company","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},
         "tasks":{"counted":472,"included":0,"packs":0,"left":null},"actions":{"counted":0,"included":0},"trial":null,
         "billing":{"source":"contract"}}
        """#)
        XCTAssertEqual(UsageView.planLine(company, now: Self.now), "Company · 472 tasks this period")
        let ours = KIFFClient.Usage(plan: "internal", period: company.period, tasks: company.tasks, actions: company.actions, trial: nil)
        XCTAssertEqual(UsageView.planLine(ours, now: Self.now), "Internal · 472 tasks this period")
    }

    /// Home's line shows only when the plan needs the owner, at Android's thresholds.
    func testHomeSaysThePlanOnlyWhenItNeedsYou() throws {
        let ended = try usage(Self.ended)
        XCTAssertEqual(UsageView.homeLine(ended, now: Self.now), "Your trial has ended. Start Solo to keep your tafs running.")

        var trial = try usage(Self.read)
        trial.trial = .init(endsAt: nil, daysLeft: 7, tasksLeft: 25, actionsLeft: 500)
        XCTAssertNil(UsageView.homeLine(trial, now: Self.now), "not started: the card says so, Home doesn't")
        trial.trial = .init(endsAt: Self.now.addingTimeInterval(5 * 86_400), daysLeft: 5, tasksLeft: 11, actionsLeft: 400)
        XCTAssertNil(UsageView.homeLine(trial, now: Self.now))
        trial.trial = .init(endsAt: Self.now.addingTimeInterval(2 * 86_400), daysLeft: 2, tasksLeft: 11, actionsLeft: 400)
        XCTAssertEqual(UsageView.homeLine(trial, now: Self.now), "Your trial ends in 2 days.")
        trial.trial = .init(endsAt: Self.now.addingTimeInterval(3_600), daysLeft: 1, tasksLeft: 11, actionsLeft: 400)
        XCTAssertEqual(UsageView.homeLine(trial, now: Self.now), "Your trial ends within a day.")

        var solo = KIFFClient.Usage(plan: "solo", period: trial.period, tasks: .init(counted: 17, included: 20, packs: 0, left: 3),
                                    actions: trial.actions, trial: nil)
        XCTAssertNil(UsageView.homeLine(solo, now: Self.now), "3 of 20 is more than a tenth")
        solo.tasks.left = 2
        XCTAssertEqual(UsageView.homeLine(solo, now: Self.now), "2 tasks left this period.")
        solo.tasks.left = 1
        XCTAssertEqual(UsageView.homeLine(solo, now: Self.now), "1 task left this period.")
        solo.tasks.left = 0
        XCTAssertEqual(UsageView.homeLine(solo, now: Self.now), UsageView.notice(solo, now: Self.now))
        XCTAssertEqual(UsageView.planTone(solo, now: Self.now), .out)
        let unlimited = KIFFClient.Usage(plan: "internal", period: trial.period, tasks: .init(counted: 9, included: 0, left: nil),
                                         actions: trial.actions, trial: nil)
        XCTAssertNil(UsageView.homeLine(unlimited, now: Self.now))
    }
}
