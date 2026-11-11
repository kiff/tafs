import XCTest
@testable import Tafs

/// Plan & usage with credits as the balance (pricing phase 2, docs/accounts.md › Credits and
/// plans): the balance read only from tafs cloud, in its words, for Plan & usage, Settings'
/// Plan card and Home. The same read as Android's.
@MainActor
final class UsageTests: XCTestCase {
    /// The balance read as docs/accounts.md › The balance read gives it.
    static let balance = #"""
    {"balance":{"state":"warning","why":null,"meter_pct":80,
                "words":"Company · 4 seats · 80% of the spend limit used",
                "detail":"The plan's included credits are used. Beyond them, credits go on this period's invoice.",
                "warning":"80% of the spend limit is used. At the limit, your tafs wait until an admin raises it.",
                "raise":{"words":"Raise the spend limit by one step","from_cents":12345},"buy":null},
     "plan_line":"Company · 4 seats · 80% of the spend limit used","plan_tone":"warning","home_line":"80% of the spend limit is used"}
    """#

    func testTheBalanceReadDecodes() throws {
        let b = try KIFFClient.decoder.decode(TafsCloud.Balance.self, from: Data(Self.balance.utf8))
        XCTAssertEqual(b.balance.state, "warning")
        XCTAssertNil(b.balance.why)
        XCTAssertEqual(b.balance.meterPct, 80)
        XCTAssertEqual(b.balance.words, "Company · 4 seats · 80% of the spend limit used")
        XCTAssertEqual(b.balance.detail, "The plan's included credits are used. Beyond them, credits go on this period's invoice.")
        XCTAssertEqual(b.balance.warning, "80% of the spend limit is used. At the limit, your tafs wait until an admin raises it.")
        XCTAssertEqual(b.balance.raise, TafsCloud.Balance.Offer(words: "Raise the spend limit by one step", fromCents: 12345))
        XCTAssertNil(b.balance.buy)
        XCTAssertFalse(b.balance.waiting)
        XCTAssertEqual(b.planLine, "Company · 4 seats · 80% of the spend limit used")
        XCTAssertEqual(b.planTone, "warning")
        XCTAssertEqual(b.homeLine, "80% of the spend limit is used")
    }

    /// Waiting, with no plan bounding it, and what the store may sell: a part the app can't read
    /// is left out, never the read.
    func testTheBalanceReadIsLenient() throws {
        let waiting = try KIFFClient.decoder.decode(TafsCloud.Balance.self, from: Data(#"""
        {"balance":{"state":"waiting","why":"credits_used_up","meter_pct":130,
                    "words":"Solo · this period's credits and your packs are used","detail":"",
                    "warning":"Your tafs are waiting: this period's credits and your packs are used.",
                    "raise":"no","buy":{"solo":false,"packs":true}},
         "plan_line":"","plan_tone":7,"home_line":null}
        """#.utf8))
        XCTAssertTrue(waiting.balance.waiting)
        XCTAssertEqual(waiting.balance.why, "credits_used_up")
        XCTAssertEqual(waiting.balance.meterPct, 100, "a bar never past full")
        XCTAssertNil(waiting.balance.detail, "an empty line is no line")
        XCTAssertNil(waiting.balance.raise, "unreadable: not offered")
        XCTAssertEqual(waiting.balance.buy, TafsCloud.Balance.Buy(solo: false, packs: true))
        XCTAssertNil(waiting.planLine)
        XCTAssertEqual(waiting.planTone, "ok", "a tone it can't read is calm")
        XCTAssertNil(waiting.homeLine)

        let none = try KIFFClient.decoder.decode(TafsCloud.Balance.self, from: Data(#"""
        {"balance":{"state":"waiting","why":"no_plan","meter_pct":null,"words":"No plan","buy":{"solo":true}}}
        """#.utf8))
        XCTAssertNil(none.balance.meterPct, "nothing bounds it: no bar")
        XCTAssertEqual(none.balance.buy, TafsCloud.Balance.Buy(solo: true, packs: false))
        XCTAssertNil(none.planLine)
        XCTAssertThrowsError(try KIFFClient.decoder.decode(TafsCloud.Balance.self, from: Data(#"{"balance":{"state":"ok"}}"#.utf8)),
                             "a balance with no words isn't one")
    }

    /// The tone's colour: waiting in red, warning in amber, else calm.
    func testTheTonesColour() {
        XCTAssertEqual(UsageView.color("waiting"), Theme.no)
        XCTAssertEqual(UsageView.color("warning"), Theme.amberText)
        XCTAssertEqual(UsageView.color("ok"), Theme.ink)
        XCTAssertEqual(UsageView.color("something_new", calm: Theme.ok), Theme.ok)
    }

    private func kiffModel() async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: RecordingSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme"}"#)
        StubProtocol.responses["GET /v1/me/usage"] = (200, PurchasesTests.read)
        StubProtocol.responses["GET /v1/usage/balance"] = (200, Self.balance)
        await model.useSession("kiff_dev_x", tenant: "t1")
        return model
    }

    private func reads(_ pathAndQuery: String) -> Int {
        StubProtocol.sent.filter { r in r.url.map { $0.path + ($0.query.map { "?" + $0 } ?? "") } == pathAndQuery }.count
    }

    /// Each refresh reads the balance, for Settings' Plan card and Home's line, and nothing of
    /// KIFF's usage; Plan & usage reads the balance, the credits and KIFF's read for the store
    /// and the requests. Neither reads the tasks, AI use or the trial any more.
    func testTheBalanceIsReadWithEachRefreshAndInPlanAndUsage() async throws {
        let model = await kiffModel()
        XCTAssertEqual(model.balance?.planLine, "Company · 4 seats · 80% of the spend limit used", "read with the sign-in's refresh")
        StubProtocol.sent = []
        await model.refresh()
        XCTAssertEqual(reads("/v1/usage/balance"), 1)
        XCTAssertEqual(reads("/v1/me/usage"), 0, "KIFF's read waits for Plan & usage")

        StubProtocol.responses["GET /v1/usage/credits"] = (200, #"{"credits":10,"runs":1,"words":"This period: 10 credits used","tafs":[]}"#)
        StubProtocol.sent = []
        await model.refreshUsage()
        XCTAssertEqual(model.balance?.balance.words, "Company · 4 seats · 80% of the spend limit used")
        XCTAssertEqual(model.credits?.words, "This period: 10 credits used")
        XCTAssertEqual(model.usage?.store?.packs.map(\.id), ["rayo"])
        XCTAssertNil(model.usageError)
        for gone in ["/v1/usage/ai", "/v1/me/usage/tasks"] {
            XCTAssertEqual(reads(gone), 0, "\(gone) isn't read")
        }

        await model.signOut()
        XCTAssertNil(model.balance)
        XCTAssertNil(model.balanceError)
        XCTAssertFalse(model.balanceStale)
        XCTAssertNil(model.usage)
    }

    /// A tafs cloud from before the balance (404) shows none, and the Credits panel stays. A
    /// read that fails later keeps what was read and says why in tafs cloud's words, never
    /// silence; one that works clears it.
    func testNoBalanceShowsNoneAndAFailedReadKeepsWhatWasRead() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/usage/balance"] = nil
        StubProtocol.responses["GET /v1/usage/credits"] = (200, #"{"credits":10,"runs":1,"words":"This period: 10 credits used","tafs":[]}"#)
        await model.refreshUsage()
        XCTAssertNil(model.balance)
        XCTAssertNil(model.balanceError)
        XCTAssertNotNil(model.credits, "the Credits panel stays")

        StubProtocol.responses["GET /v1/usage/balance"] = (200, Self.balance)
        await model.refreshUsage()
        StubProtocol.responses["GET /v1/usage/balance"] = (503, #"{"error":"kiff unavailable","words":"The balance can't be read right now. Try again in a minute."}"#)
        await model.refreshUsage()
        XCTAssertEqual(model.balance?.balance.meterPct, 80, "a failed read keeps what was read")
        XCTAssertEqual(model.balanceError, "The balance can't be read right now. Try again in a minute.")
        StubProtocol.responses["GET /v1/usage/balance"] = (503, #"{"error":"down"}"#)
        await model.refreshUsage()
        XCTAssertTrue(model.balanceError?.hasPrefix("The balance couldn't be read") ?? false, "without words, the app's own line, never silence")
        StubProtocol.responses["GET /v1/usage/balance"] = (200, Self.balance)
        await model.refresh()
        XCTAssertNil(model.balanceError, "a read that works clears it")

        // KIFF's read failing says so too, and the balance stays.
        StubProtocol.responses["GET /v1/me/usage"] = (503, #"{"error":"down"}"#)
        await model.refreshUsage()
        XCTAssertNotNil(model.usageError)
        XCTAssertNotNil(model.balance)
    }

    /// The last period is read again on each refresh once it has been read, never kept for
    /// good; before it's asked for, a refresh doesn't read it.
    func testTheLastPeriodIsReadAgainOnEachRefresh() async throws {
        let model = await kiffModel()
        StubProtocol.responses["GET /v1/usage/credits"] = (200, #"{"credits":10,"runs":1,"words":"This period: 10 credits used","tafs":[]}"#)
        StubProtocol.responses["GET /v1/usage/credits?period=previous"] = (200, #"{"credits":980,"runs":50,"words":"Last period: 980 credits used · 1 Oct to 31 Oct","tafs":[]}"#)
        StubProtocol.sent = []
        await model.refreshUsage()
        XCTAssertEqual(reads("/v1/usage/credits?period=previous"), 0, "not until it's asked for")
        await model.readPreviousCredits()
        XCTAssertEqual(model.previousCredits?.credits, 980)

        StubProtocol.responses["GET /v1/usage/credits?period=previous"] = (200, #"{"credits":990,"runs":51,"words":"Last period: 990 credits used · 1 Oct to 31 Oct","tafs":[]}"#)
        await model.refreshUsage()
        XCTAssertEqual(reads("/v1/usage/credits?period=previous"), 2, "read again with the refresh")
        XCTAssertEqual(model.previousCredits?.words, "Last period: 990 credits used · 1 Oct to 31 Oct")

        StubProtocol.responses["GET /v1/usage/credits?period=previous"] = (503, #"{"error":"down","words":"The last period can't be read right now."}"#)
        await model.refreshUsage()
        XCTAssertEqual(model.previousCredits?.credits, 990, "a failed read keeps what was read")
        XCTAssertEqual(model.previousCreditsError, "The last period can't be read right now.")
    }

    /// The sample shows a balance and credits, as tafs cloud words them and as Android's sample
    /// does, with no prices and nothing of the trial or tasks.
    func testTheSampleShowsTheBalance() async {
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        await model.refreshUsage()
        let b = try? XCTUnwrap(model.balance)
        XCTAssertEqual(b?.balance.words, "Solo · 260 of 500 credits left this period", "Android's sample words")
        XCTAssertEqual(b?.balance.detail, "Then 900 in packs, which never expire")
        XCTAssertEqual(b?.balance.meterPct, 48)
        XCTAssertEqual(b?.planLine, "Solo · 260 credits left")
        XCTAssertNil(b?.homeLine, "Home stays about the tafs")
        XCTAssertEqual(model.credits?.note, AppModel.sampleCreditsNote)
        for line in [b?.balance.words, b?.balance.detail, b?.planLine, model.credits?.note].compactMap({ $0 }) {
            XCTAssertFalse(line.contains("€") || line.contains("$"), "no prices: \(line)")
            XCTAssertFalse(line.lowercased().contains("trial") || line.lowercased().contains("tasks"), "no trial, no tasks: \(line)")
        }
        XCTAssertNil(model.usage?.store, "the sample sells nothing")
    }

    // MARK: Plan & usage on a new account (docs/accounts.md › The balance read)

    private func store(_ packs: String) throws -> KIFFClient.Usage.Store {
        try KIFFClient.decoder.decode(KIFFClient.Usage.Store.self, from: Data(#"{"solo_product":"bot.tafs.solo.monthly","packs":\#(packs)}"#.utf8))
    }

    private func line(_ balance: String) throws -> TafsCloud.Balance.Line {
        try KIFFClient.decoder.decode(TafsCloud.Balance.self, from: Data(#"{"balance":\#(balance)}"#.utf8)).balance
    }

    /// The Credits panel follows tafs cloud's flag: left out only where it says false. A tafs
    /// cloud from before the flag, or a value the app can't read, shows the panel.
    func testTheCreditsPanelFollowsTafsCloudsFlag() throws {
        XCTAssertFalse(try line(#"{"words":"No plan","credits_panel":false}"#).creditsPanel)
        XCTAssertTrue(try line(#"{"words":"Solo","credits_panel":true}"#).creditsPanel)
        XCTAssertTrue(try line(#"{"words":"Solo"}"#).creditsPanel, "a tafs cloud from before the flag shows it")
        XCTAssertTrue(try line(#"{"words":"Solo","credits_panel":"no"}"#).creditsPanel, "unreadable: shown")
    }

    /// The First month pack is offered only where tafs cloud says it can be bought.
    func testTheFirstMonthOfferIsReadFromTheBalance() throws {
        let running = try line(#"{"words":"Solo","buy":{"solo":false,"packs":true,"first_month":{"ready":true,"words":null}}}"#)
        XCTAssertEqual(running.buy?.firstMonthReady, true)
        // Before a plan, tafs cloud sends null; one from before sent it not ready, with words.
        let none = try line(#"{"words":"No plan","buy":{"solo":true,"packs":false,"first_month":null}}"#)
        XCTAssertNil(none.buy?.firstMonth)
        XCTAssertEqual(none.buy?.firstMonthReady, false)
        let before = try line(#"{"words":"No plan","buy":{"solo":true,"first_month":{"ready":false,"words":"Once Solo starts"}}}"#)
        XCTAssertEqual(before.buy?.firstMonthReady, false, "not ready: not shown, never alone or greyed")
        let older = try line(#"{"words":"Solo","buy":{"solo":false,"packs":true}}"#)
        XCTAssertEqual(older.buy?.firstMonthReady, false, "a tafs cloud that doesn't say offers none")
    }

    /// What Coach's help includes, under the Solo offer: tafs cloud's sentence, or no line.
    func testWhatCoachIncludesIsTafsCloudsSentenceOrNothing() throws {
        let said = try line(#"{"words":"No plan","buy":{"solo":true,"packs":false,"first_month":null,"coach":"Coach is included."}}"#)
        XCTAssertEqual(said.buy?.coach, "Coach is included.")
        // A tafs cloud from before it, a blank one, or one that isn't words: no line.
        XCTAssertNil(try line(#"{"words":"No plan","buy":{"solo":true,"packs":false}}"#).buy?.coach)
        XCTAssertNil(try line(#"{"words":"No plan","buy":{"solo":true,"coach":"  "}}"#).buy?.coach)
        XCTAssertNil(try line(#"{"words":"No plan","buy":{"solo":true,"coach":4}}"#).buy?.coach)
        XCTAssertEqual(try line(#"{"words":"No plan","buy":{"solo":true,"coach":4}}"#).buy?.solo, true, "the offer still reads")
    }

    /// The packs as listed: the First month pack first while it can be bought, then the packs
    /// that can be bought again; otherwise it isn't listed at all.
    func testTheFirstMonthPackLeadsThePacksOnlyWhileItCanBeBought() throws {
        let s = try store(#"""
        [{"id":"rayo","name":"Rayo","line":"Rayo · 1,000 credits","credits":1000,"apple_product":"bot.tafs.pack.rayo"},
         {"id":"first","name":"First month","line":"First month pack","credits":5000,"apple_product":"bot.tafs.pack.first","once":true},
         {"id":"trueno","name":"Trueno","line":"Trueno · 5,000 credits","credits":5000,"apple_product":"bot.tafs.pack.trueno"}]
        """#)
        XCTAssertEqual(s.packs.map(\.once), [false, true, false])
        XCTAssertEqual(s.offered(firstMonth: true).map(\.id), ["first", "rayo", "trueno"], "first among the packs")
        XCTAssertEqual(s.offered(firstMonth: false).map(\.id), ["rayo", "trueno"], "not offered: not listed")
        // KIFF lists the pack with no App Store product: the app can't sell it, so it isn't there.
        let unsold = try store(#"""
        [{"id":"first","name":"First month","line":"First month pack","once":true},
         {"id":"rayo","name":"Rayo","line":"Rayo · 1,000 credits","apple_product":"bot.tafs.pack.rayo"}]
        """#)
        XCTAssertEqual(unsold.offered(firstMonth: true).map(\.id), ["rayo"])
        // Bought once: KIFF drops it, and nothing leads.
        let bought = try store(#"[{"id":"rayo","name":"Rayo","line":"Rayo","apple_product":"bot.tafs.pack.rayo"}]"#)
        XCTAssertEqual(bought.offered(firstMonth: true).map(\.id), ["rayo"])
    }
}
