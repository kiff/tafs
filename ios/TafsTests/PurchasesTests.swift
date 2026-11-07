import XCTest
@testable import Tafs

/// What happened, in order: KIFF recording a purchase, the App Store finishing it.
@MainActor
final class StoreLog {
    var lines: [String] = []
    func add(_ line: String) { lines.append(line) }
}

/// The App Store, as tests drive it.
@MainActor
final class FakeStore: StoreFront {
    let log: StoreLog
    var sold: [StoreProduct] = []
    var bought: StorePurchase?
    var pending = false
    var restored: [StorePurchase] = []
    var left: [StorePurchase] = []
    var tokens: [UUID] = []
    private var stream: AsyncStream<StorePurchase>.Continuation?

    init(log: StoreLog) { self.log = log }

    func purchaseNamed(_ id: UInt64, product: String = "bot.tafs.pack.rayo") -> StorePurchase {
        let log = self.log
        return StorePurchase(id: id, productID: product, signed: "jws-\(id)", finish: { await log.add("finish \(id)") })
    }

    func products(_ ids: [String]) async throws -> [StoreProduct] { sold.filter { ids.contains($0.id) } }

    func purchase(_ id: String, accountToken: UUID) async throws -> (StorePurchase?, pending: Bool) {
        tokens.append(accountToken)
        return (bought, pending)
    }

    func updates() -> AsyncStream<StorePurchase> {
        AsyncStream { self.stream = $0 }
    }

    func report(_ p: StorePurchase) { stream?.yield(p) }

    func unfinished() async -> [StorePurchase] { left }

    func restore() async throws -> [StorePurchase] { restored }
}

@MainActor
final class PurchasesTests: XCTestCase {
    private func purchases(recording log: StoreLog, failing: Bool = false) -> (Purchases, FakeStore) {
        let store = FakeStore(log: log)
        let p = Purchases(front: store)
        p.record = { signed, request in
            if failing { throw KIFFClient.KIFFError.server(status: 503, message: "KIFF is down") }
            log.add("kiff \(signed) \(request ?? "-")")
        }
        return (p, store)
    }

    /// A purchase is sent to KIFF, with the account's token and the request it answers, and
    /// finished only after KIFF recorded it.
    func testAPurchaseIsSentToKIFFBeforeItIsFinished() async {
        let log = StoreLog()
        let (p, store) = purchases(recording: log)
        store.bought = store.purchaseNamed(1)
        let token = UUID()
        let outcome = await p.buy("bot.tafs.pack.rayo", accountToken: token, requestID: "pr_1")
        XCTAssertEqual(outcome, .done)
        XCTAssertEqual(store.tokens, [token])
        XCTAssertEqual(log.lines, ["kiff jws-1 pr_1", "finish 1"])
    }

    /// KIFF didn't record it: the purchase isn't finished, so the App Store gives it again.
    func testAPurchaseKIFFDidntRecordIsntFinished() async {
        let log = StoreLog()
        let (p, store) = purchases(recording: log, failing: true)
        store.bought = store.purchaseNamed(2)
        let outcome = await p.buy("bot.tafs.pack.rayo", accountToken: UUID())
        guard case .notRecorded = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(log.lines, [])
        // Cancelled and pending buy nothing and send nothing.
        store.bought = nil
        let cancelled = await p.buy("bot.tafs.pack.rayo", accountToken: UUID())
        XCTAssertEqual(cancelled, .cancelled)
        store.pending = true
        let pending = await p.buy("bot.tafs.pack.rayo", accountToken: UUID())
        XCTAssertEqual(pending, .pending)
    }

    /// Restoring sends each of the Apple account's purchases to KIFF, and finishes each.
    func testRestore() async {
        let log = StoreLog()
        let (p, store) = purchases(recording: log)
        store.restored = [store.purchaseNamed(3, product: "bot.tafs.solo.monthly"), store.purchaseNamed(4)]
        let outcome = await p.restore()
        XCTAssertEqual(outcome, .done)
        XCTAssertEqual(log.lines, ["kiff jws-3 -", "finish 3", "kiff jws-4 -", "finish 4"])
    }

    /// From launch, what the App Store reports reaches KIFF; and what wasn't finished before
    /// goes once signed in.
    func testUpdatesAndUnfinishedPurchasesReachKIFF() async {
        let log = StoreLog()
        let (p, store) = purchases(recording: log)
        p.start()
        store.report(store.purchaseNamed(5))
        for _ in 0..<100 where log.lines.count < 2 { await Task.yield() }
        XCTAssertEqual(log.lines, ["kiff jws-5 -", "finish 5"])
        store.left = [store.purchaseNamed(6)]
        await p.sendUnfinished()
        XCTAssertEqual(log.lines.suffix(2), ["kiff jws-6 -", "finish 6"])
        p.stop()
    }

    // MARK: Plan & usage's lines

    static func usage(source: String, canAdd: Bool = true) -> KIFFClient.Usage {
        KIFFClient.Usage(billing: .init(source: source, manageURL: source == "apple" ? URL(string: "https://apps.apple.com/account/subscriptions") : nil),
                         canAddTasks: canAdd,
                         store: .init(appAccountToken: UUID(), soloProduct: "bot.tafs.solo.monthly",
                                      packs: [.init(id: "rayo", name: "Rayo", line: "Rayo · a pack of credits", appleProduct: "bot.tafs.pack.rayo")]))
    }

    /// One billing line: Apple's, KIFF's or the agreement's. KIFF's says nothing more: no link,
    /// and nothing about where to buy.
    func testEachBillingLine() {
        XCTAssertEqual(UsageView.billingLine(Self.usage(source: "apple")), "Billed by Apple")
        XCTAssertTrue(UsageView.managedByApple(Self.usage(source: "apple")))
        XCTAssertEqual(UsageView.billingLine(Self.usage(source: "stripe")), "Billed by KIFF")
        XCTAssertFalse(UsageView.managedByApple(Self.usage(source: "stripe")))
        XCTAssertEqual(UsageView.billingLine(Self.usage(source: "contract")), "Managed by your company's agreement")
        XCTAssertNil(UsageView.billingLine(Self.usage(source: "")))
    }

    /// After a move from Solo to Company (billed by KIFF), Solo still renews in the App Store
    /// until it's cancelled there: one line says so, with Apple's own page. Nothing about where to buy.
    func testSoloStillRenewsAfterTheMoveToCompany() {
        var u = Self.usage(source: "stripe")
        XCTAssertNil(UsageView.soloStillRenews(u), "nothing to say without a Solo renewing")
        u.billing?.appleSoloUntil = ISO8601DateFormatter().date(from: "2026-11-03T09:00:00Z")
        let line = try? XCTUnwrap(UsageView.soloStillRenews(u))
        XCTAssertTrue(line?.hasPrefix("Company is billed by KIFF now. The Solo bought in the App Store renews on ") ?? false, line ?? "")
        XCTAssertTrue(line?.hasSuffix("unless it's cancelled, from the Apple account that bought it.") ?? false, line ?? "")
        XCTAssertFalse(line?.contains("kiff.dev") ?? true, "the app never points to buying elsewhere")
        u.billing?.source = "apple"
        XCTAssertNil(UsageView.soloStillRenews(u), "while Apple bills, Manage says it all")
    }

    func testTheSoloRenewalIsRead() throws {
        let json = #"{"source":"stripe","manage_url":null,"apple_solo_until":"2026-11-03T09:00:00Z"}"#
        let b = try KIFFClient.decoder.decode(KIFFClient.Usage.Billing.self, from: Data(json.utf8))
        XCTAssertEqual(b.appleSoloUntil, ISO8601DateFormatter().date(from: "2026-11-03T09:00:00Z"))
    }

    /// Solo's terms go with every offer: its period and price, that it renews until
    /// cancelled, and how to cancel. A free trial shows only where the App Store itself offers one.
    func testSoloCarriesItsTerms() {
        let plain = StoreProduct(id: "bot.tafs.solo.monthly", displayName: "Solo", displayPrice: "$10.99", subscription: .init(period: "month"))
        XCTAssertEqual(SoloView.action(plain), "Subscribe for $10.99 a month")
        let terms = SoloView.terms(plain)
        for part in ["one month at $10.99", "renews every month until you cancel", "Cancel any time"] {
            XCTAssertTrue(terms.contains(part), "\(terms) lacks \(part)")
        }
        XCTAssertFalse(terms.contains("Free"))
        XCTAssertFalse(SoloView.about.contains("trial"))
        XCTAssertNil(Screenshots.products.first { $0.id == Screenshots.soloProduct }?.subscription?.freeTrial, "no trial in the store's screenshots")
    }

    // MARK: Asking an admin

    /// KIFF's usage read as a KIFF from before still sends it, tasks and all: the app reads only
    /// the store, billing and requests from it.
    static let read = #"""
    {"plan":"team","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
     "tasks":{"counted":40,"included":40,"packs":0,"left":0,"by_agent":[],"free":{}},
     "actions":{"counted":0,"included":1000},"trial":null,
     "billing":{"source":"stripe","manage_url":null},"pack_list":[],"can_add_tasks":true,
     "store":{"app_account_token":"6f1c2b9e-3a4d-4c1f-9e2a-1b2c3d4e5f60","solo_product":"bot.tafs.solo.monthly",
              "packs":[{"id":"rayo","name":"Rayo","line":"Rayo · a pack of credits","credits":1000,"apple_product":"bot.tafs.pack.rayo","stripe_price":"x"},
                       {"id":"bad"}]},
     "requests":[{"id":"pr_1","asked_by":"bob","pack":"rayo","reason":"Refunds ran out","status":"open",
                  "created_at":"2026-10-20T09:00:00Z","expires_at":"2026-11-01T00:00:00Z"},
                 {"id":"pr_2","asked_by":"ann","kind":"raise","reason":"Month end","status":"open",
                  "created_at":"2026-10-21T09:00:00Z","expires_at":"2026-11-01T00:00:00Z"}]}
    """#

    /// A Company balance at the spend limit, for someone who may raise it.
    static let atTheLimit = #"""
    {"balance":{"state":"waiting","why":"limit_reached","meter_pct":100,"words":"Company · 4 seats · the spend limit is reached",
                "detail":"The plan's included credits are used.","warning":"Your tafs are waiting: the spend limit is reached. Raise it to let them go on.",
                "raise":{"words":"Raise the spend limit by one step","from_cents":8000},"buy":null},
     "plan_line":"Company · waiting: the spend limit is reached","plan_tone":"waiting","home_line":"Your tafs are waiting: the spend limit is reached"}
    """#

    static let raised = #"""
    {"balance":{"state":"ok","why":null,"meter_pct":62,"words":"Company · 4 seats · 62% of the spend limit used",
                "raise":{"words":"Raise the spend limit by one step","from_cents":13000}},
     "plan_line":"Company · 4 seats · 62% of the spend limit used","plan_tone":"ok","home_line":null}
    """#

    private func signedIn(_ log: StoreLog) async -> (AppModel, FakeStore, RecordingSigner) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let store = FakeStore(log: log)
        let signer = RecordingSigner()
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session),
                             purchases: Purchases(front: store))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t1","tenant_slug":"acme"}"#)
        StubProtocol.responses["GET /v1/me/usage"] = (200, Self.read)
        StubProtocol.responses["GET /v1/usage/balance"] = (200, Self.atTheLimit)
        await model.useSession("kiff_dev_x", tenant: "t1")
        await model.refreshUsage()
        return (model, store, signer)
    }

    private func sent(_ method: String, _ path: String) -> [String: String]? {
        guard let r = StubProtocol.sent.last(where: { $0.httpMethod == method && $0.url?.path == path }), let body = r.httpBody else { return nil }
        return try? JSONDecoder().decode([String: String].self, from: body)
    }

    private func reads(_ path: String) -> Int { StubProtocol.sent.filter { $0.httpMethod == "GET" && $0.url?.path == path }.count }

    /// KIFF's read from before decodes for what the app still reads; a pack or request it can't
    /// read is left out, and a request with no `kind` is for a pack.
    func testKIFFsReadFromBeforeDecodes() throws {
        let u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8))
        XCTAssertEqual(u.store?.packs.map(\.id), ["rayo"], "the unreadable pack is left out")
        XCTAssertEqual(u.store?.packs.first?.credits, 1000)
        XCTAssertEqual(u.store?.products, ["bot.tafs.solo.monthly", "bot.tafs.pack.rayo"])
        XCTAssertEqual(u.requests?.map(\.kind), ["pack", "raise"])
        XCTAssertNil(u.requests?.last?.pack, "a raise names no pack")
        XCTAssertTrue(u.managesBilling)
        XCTAssertEqual(u.billing?.source, "stripe")
    }

    /// The admin approves a member's request for a pack by buying it: the purchase goes to KIFF
    /// with the request's id and the account's token, then is finished.
    func testApprovingBuysThePackForTheRequest() async throws {
        let log = StoreLog()
        let (model, store, _) = await signedIn(log)
        let u = try XCTUnwrap(model.usage)
        XCTAssertEqual(UsageView.openRequests(u).map(\.id), ["pr_1", "pr_2"])
        XCTAssertEqual(UsageView.requestLine(u.requests![0], in: u), "Someone asks for Rayo · a pack of credits.")
        XCTAssertEqual(UsageView.requestLine(u.requests![1], in: u), "Someone asks to raise the spend limit.")
        StubProtocol.responses["POST /v1/me/billing/apple/transactions"] = (200, #"{"recorded":true,"action":"pack"}"#)
        let pack = try XCTUnwrap(u.store?.packs.first)
        let request = try XCTUnwrap(u.requests?.first)
        store.bought = store.purchaseNamed(7)
        await model.buyPack(pack, answering: request)
        XCTAssertEqual(sent("POST", "/v1/me/billing/apple/transactions"), ["signed_transaction": "jws-7", "request_id": "pr_1"])
        XCTAssertEqual(store.tokens, [UUID(uuidString: "6f1c2b9e-3a4d-4c1f-9e2a-1b2c3d4e5f60")!])
        XCTAssertEqual(log.lines, ["finish 7"])
        XCTAssertNil(model.planMessage)
    }

    /// A test purchase on an account that takes only real ones: KIFF refuses it, so the app
    /// finishes it, adds nothing, and says so; it isn't sent again at the next launch.
    func testARefusedTestPurchaseIsFinishedAndSaysSo() async throws {
        let log = StoreLog()
        let (model, store, _) = await signedIn(log)
        StubProtocol.responses["POST /v1/me/billing/apple/transactions"] = (403,
            #"{"error":"test_purchase","message":"Test purchases don't add credits to this account."}"#)
        let pack = try XCTUnwrap(model.usage?.store?.packs.first)
        store.bought = store.purchaseNamed(8)
        await model.buyPack(pack)
        XCTAssertEqual(log.lines, ["finish 8"])
        XCTAssertEqual(model.planMessage, Purchases.testPurchase)
    }

    /// Another tafs account's purchase on this Apple ID: finished here, nothing added, and
    /// said so, never "tries again".
    func testAnotherAccountsPurchaseIsFinished() async throws {
        let log = StoreLog()
        let (model, store, _) = await signedIn(log)
        StubProtocol.responses["POST /v1/me/billing/apple/transactions"] = (403,
            #"{"error":"not_this_account","message":"This purchase belongs to another account."}"#)
        store.bought = store.purchaseNamed(9)
        await model.buyPack(try XCTUnwrap(model.usage?.store?.packs.first))
        XCTAssertEqual(log.lines, ["finish 9"])
        XCTAssertEqual(model.planMessage, Purchases.anotherAccount)
    }

    /// A plan billed elsewhere: paid, but KIFF can't add it. Kept unfinished for support,
    /// and the person is told to contact support, never "tries again".
    func testAPurchaseBilledElsewhereGoesToSupport() async throws {
        let log = StoreLog()
        let (model, store, _) = await signedIn(log)
        StubProtocol.responses["POST /v1/me/billing/apple/transactions"] = (409,
            #"{"error":"billed_elsewhere","message":"This account's plan is billed elsewhere."}"#)
        store.bought = store.purchaseNamed(10)
        await model.buyPack(try XCTUnwrap(model.usage?.store?.packs.first))
        XCTAssertEqual(log.lines, [])
        XCTAssertEqual(model.planMessage, Purchases.contactSupport)
        XCTAssertFalse(model.planMessage?.contains("tries again") ?? true)
    }

    /// A product KIFF doesn't sell: paid, kept unfinished for support, and said so.
    func testAnUnknownProductGoesToSupport() async throws {
        let log = StoreLog()
        let (model, store, _) = await signedIn(log)
        StubProtocol.responses["POST /v1/me/billing/apple/transactions"] = (422, #"{"error":"unknown_product"}"#)
        store.bought = store.purchaseNamed(11)
        await model.buyPack(try XCTUnwrap(model.usage?.store?.packs.first))
        XCTAssertEqual(log.lines, [])
        XCTAssertEqual(model.planMessage, Purchases.contactSupport)
    }

    /// Declining is the admin's answer, signed behind Face ID; asking and withdrawing are a
    /// member's, unsigned. A pack is asked for as before; a raise names no pack.
    func testDecliningIsSignedAndAskingIsNot() async throws {
        let log = StoreLog()
        let (model, _, signer) = await signedIn(log)
        let request = try XCTUnwrap(model.usage?.requests?.first)
        StubProtocol.responses["POST /v1/me/pack-requests/pr_1/decline"] = (200,
            #"{"id":"pr_1","asked_by":"bob","pack":"rayo","reason":"","status":"declined","created_at":"2026-10-20T09:00:00Z","expires_at":"2026-11-01T00:00:00Z","note":"Next month"}"#)
        let declined = await model.declineRequest(request, note: " Next month ")
        XCTAssertTrue(declined)
        XCTAssertEqual(sent("POST", "/v1/me/pack-requests/pr_1/decline"), ["note": "Next month"])
        let decline = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/me/pack-requests/pr_1/decline" })
        XCTAssertNotNil(decline.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertTrue(signer.signed.contains { $0.hasPrefix("tafs.decline-request.v1|t1|pr_1") })
        XCTAssertEqual(DeclineRequestView.question(try XCTUnwrap(model.usage?.requests?.last), in: model.usage),
                       "Decline the request to raise the spend limit?")

        StubProtocol.responses["POST /v1/me/pack-requests"] = (201,
            #"{"id":"pr_3","asked_by":"bob","pack":"rayo","reason":"Refunds","status":"open","created_at":"2026-10-20T09:00:00Z","expires_at":"2026-11-01T00:00:00Z"}"#)
        let pack = try XCTUnwrap(model.usage?.store?.packs.first)
        let asked = await model.askAdmin(raise: false, pack: pack, reason: "Refunds ")
        XCTAssertTrue(asked)
        XCTAssertEqual(sent("POST", "/v1/me/pack-requests"), ["pack": "rayo", "reason": "Refunds"], "a pack, as before")
        let ask = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/me/pack-requests" })
        XCTAssertNil(ask.value(forHTTPHeaderField: "KIFF-Device-Signature"))

        StubProtocol.responses["POST /v1/me/pack-requests"] = (201,
            #"{"id":"pr_4","asked_by":"bob","kind":"raise","reason":"Month end","status":"open","created_at":"2026-10-20T09:00:00Z","expires_at":"2026-11-01T00:00:00Z"}"#)
        let raise = await model.askAdmin(raise: true, pack: pack, reason: " Month end")
        XCTAssertTrue(raise)
        XCTAssertEqual(sent("POST", "/v1/me/pack-requests"), ["kind": "raise", "reason": "Month end"], "a raise names no pack")
    }

    /// What someone who doesn't manage billing asks for, as on Android: a raise on Company
    /// (KIFF's `team`), a pack on Solo while packs are on sale; nothing on other plans.
    func testAMemberAsksForARaiseOnCompanyAndAPackOnSolo() throws {
        var u = Self.usage(source: "stripe", canAdd: false)
        u.plan = "team"
        XCTAssertEqual(UsageView.asks(u), true)
        u.plan = "solo"
        XCTAssertEqual(UsageView.asks(u), false)
        u.store?.packs = []
        XCTAssertNil(UsageView.asks(u), "no pack on sale: nothing to ask for")
        for plan in ["company", "internal", nil] {
            u.plan = plan
            XCTAssertNil(UsageView.asks(u), plan ?? "no plan")
        }
        let read = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8))
        XCTAssertEqual(read.plan, "team")
    }

    /// Raising the spend limit is the act of someone who manages billing: signed behind Face
    /// ID, from the limit the balance gave, then the balance read again.
    func testRaisingTheLimitIsSignedAndReadsTheBalanceAgain() async throws {
        let log = StoreLog()
        let (model, _, signer) = await signedIn(log)
        XCTAssertEqual(model.balance?.balance.raise?.fromCents, 8000)
        XCTAssertTrue(KIFFClient.signsSetup("/v1/me/usage/limit"), "raising lets the tafs spend more")
        XCTAssertFalse(KIFFClient.signsSetup("/v1/me/usage/ai"), "Go on is gone")

        StubProtocol.responses["PUT /v1/me/usage/limit"] = (200, #"{"limit_cents":13000}"#)
        StubProtocol.responses["GET /v1/usage/balance"] = (200, Self.raised)
        let raised = await model.raiseLimit()
        XCTAssertNil(raised)
        let put = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PUT" && $0.url?.path == "/v1/me/usage/limit" })
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(put.httpBody)) as? [String: Any])
        XCTAssertEqual(body["raise"] as? Bool, true)
        XCTAssertEqual(body["from_cents"] as? Int, 8000, "the limit it raises from, signed with it")
        XCTAssertNotNil(put.value(forHTTPHeaderField: "KIFF-Device-Signature"), "a signed setup call")
        XCTAssertTrue(signer.signed.contains("tafs.raise-limit.v1|t1|8000"), "Face ID first")
        XCTAssertEqual(model.balance?.balance.state, "ok")
        XCTAssertEqual(model.balance?.balance.raise?.fromCents, 13000)
        XCTAssertFalse(model.balanceStale)
    }

    /// One raise at a time, from the tap: a fast double tap asks for one Face ID and sends one
    /// PUT. With no raise offered, nothing is signed or sent, and nothing says it was raised.
    func testADoubleTapRaisesOnceAndNoOfferRaisesNothing() async throws {
        let log = StoreLog()
        let (model, _, signer) = await signedIn(log)
        StubProtocol.responses["PUT /v1/me/usage/limit"] = (200, #"{"limit_cents":13000}"#)
        StubProtocol.responses["GET /v1/usage/balance"] = (200, Self.raised)
        let signed = signer.signed.count
        async let first = model.raiseLimit()
        async let second = model.raiseLimit()
        let answers = await [first, second]
        XCTAssertEqual(answers.filter { $0 == nil }.count, 1, "one raise went through")
        XCTAssertEqual(answers.filter { $0 == "" }.count, 1, "the second tap started nothing, and says nothing")
        XCTAssertEqual(signer.signed.count, signed + 1, "one Face ID")
        XCTAssertEqual(StubProtocol.sent.filter { $0.httpMethod == "PUT" && $0.url?.path == "/v1/me/usage/limit" }.count, 1)
        XCTAssertFalse(model.raising, "over once it's done")

        StubProtocol.responses["GET /v1/usage/balance"] = (200, #"{"balance":{"state":"ok","words":"Company · 4 seats · 20% of the spend limit used","raise":null}}"#)
        await model.readBalance()
        let none = await model.raiseLimit()
        XCTAssertEqual(none, AppModel.noRaise)
        XCTAssertNotEqual(none, AppModel.readBalanceFirst, "nothing was raised")
        XCTAssertEqual(signer.signed.count, signed + 1, "no Face ID")
    }

    /// A raise that went through, whose balance then couldn't be read, isn't offered again:
    /// another tap sends no PUT until the balance is read, and reading it signs nothing.
    func testAfterARaiseWhoseReadFailedNoSecondRaiseIsSentUntilItsRead() async throws {
        let log = StoreLog()
        let (model, _, signer) = await signedIn(log)
        StubProtocol.responses["PUT /v1/me/usage/limit"] = (200, #"{"limit_cents":13000}"#)
        StubProtocol.responses["GET /v1/usage/balance"] = (503, #"{"error":"kiff unavailable","words":"The balance can't be read right now."}"#)
        let raised = await model.raiseLimit()
        XCTAssertNil(raised, "it went through")
        XCTAssertTrue(model.balanceStale)
        XCTAssertEqual(model.balance?.balance.state, "waiting", "what was read before, marked stale")
        XCTAssertEqual(model.balanceError, "The balance can't be read right now.", "tafs cloud's words")

        let puts = { StubProtocol.sent.filter { $0.httpMethod == "PUT" && $0.url?.path == "/v1/me/usage/limit" }.count }
        let signed = signer.signed.count
        let again = await model.raiseLimit()
        XCTAssertEqual(again, AppModel.readBalanceFirst)
        XCTAssertEqual(puts(), 1, "no second PUT")
        XCTAssertEqual(signer.signed.count, signed, "no Face ID for a refused tap")

        StubProtocol.responses["GET /v1/usage/balance"] = (200, Self.raised)
        await model.readBalance()
        XCTAssertFalse(model.balanceStale)
        XCTAssertNil(model.balanceError)
        XCTAssertEqual(model.balance?.balance.raise?.fromCents, 13000)
        XCTAssertEqual(signer.signed.count, signed, "reading signs nothing")
    }

    /// A raise from a limit that changed meanwhile (another admin, a stale screen) is refused:
    /// the balance is read again, and the next raise is from the limit it is now. One past the
    /// phone's ceiling says to contact KIFF.
    func testARaiseFromAChangedLimitReadsTheBalanceAgain() async throws {
        let log = StoreLog()
        let (model, _, _) = await signedIn(log)
        StubProtocol.responses["PUT /v1/me/usage/limit"] = (409, #"{"error":"limit_changed"}"#)
        StubProtocol.responses["GET /v1/usage/balance"] = (200, Self.raised)
        let before = reads("/v1/usage/balance")
        let answer = await model.raiseLimit()
        XCTAssertEqual(answer, AppModel.limitChanged)
        XCTAssertEqual(reads("/v1/usage/balance"), before + 1, "read again")
        XCTAssertEqual(model.balance?.balance.raise?.fromCents, 13000)
        XCTAssertFalse(model.balanceStale, "nothing was raised")

        StubProtocol.responses["PUT /v1/me/usage/limit"] = (409, #"{"error":"limit_ceiling"}"#)
        let ceiling = await model.raiseLimit()
        XCTAssertEqual(ceiling, AppModel.limitCeiling)
        let body = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PUT" && $0.url?.path == "/v1/me/usage/limit" }?.httpBody)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: body) as? [String: Any])?["from_cents"] as? Int, 13000, "from the new limit")
    }

    /// A switch while the raise is out: its answer is the old account's, so it marks nothing
    /// stale and reads nothing for the account the app is in now.
    func testARaiseAnsweredAfterASwitchChangesNothing() async throws {
        let log = StoreLog()
        let (model, _, _) = await signedIn(log)
        StubProtocol.responses["PUT /v1/me/usage/limit"] = (200, #"{"limit_cents":13000}"#)
        StubProtocol.holdOnce = "PUT /v1/me/usage/limit"
        let raising = Task { await model.raiseLimit() }
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
        model.forgetSession()
        let readsBefore = reads("/v1/usage/balance")
        StubProtocol.release.signal()
        let answer = await raising.value
        XCTAssertEqual(answer, "", "nothing to say for an account that's gone")
        XCTAssertFalse(model.balanceStale)
        XCTAssertNil(model.balance)
        XCTAssertEqual(reads("/v1/usage/balance"), readsBefore, "no read after the switch")
    }

    /// A member sees their request waiting, and what an admin answered, in the lines both apps share.
    func testAMemberSeesTheirRequest() throws {
        var u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8))
        u.canAddTasks = false
        XCTAssertEqual(UsageView.waiting(u)?.id, "pr_1")
        XCTAssertEqual(UsageView.askedLine(try XCTUnwrap(UsageView.waiting(u)), in: u), "You asked an admin for a pack.")
        XCTAssertTrue(UsageView.openRequests(u).isEmpty, "a member answers nothing")
        u.requests = [.init(id: "pr_1", askedBy: "bob", pack: "rayo", status: "declined", createdAt: Date(), expiresAt: Date(), note: "Next month")]
        XCTAssertNil(UsageView.waiting(u))
        XCTAssertEqual(UsageView.lastAnswer(u), "An admin declined: Next month")
        u.requests = [.init(id: "pr_2", askedBy: "bob", kind: "raise", status: "open", createdAt: Date(), expiresAt: Date())]
        XCTAssertEqual(UsageView.askedLine(try XCTUnwrap(UsageView.waiting(u)), in: u), "You asked an admin to raise the spend limit.")
        u.requests = [.init(id: "pr_2", askedBy: "bob", kind: "raise", status: "approved", createdAt: Date(), expiresAt: Date())]
        XCTAssertEqual(UsageView.lastAnswer(u), "An admin raised the spend limit.")
        u.requests = [.init(id: "pr_1", askedBy: "bob", pack: "rayo", status: "approved", createdAt: Date(), expiresAt: Date())]
        XCTAssertEqual(UsageView.lastAnswer(u), "An admin added a pack.")
        u.requests = [.init(id: "pr_1", askedBy: "bob", pack: "rayo", status: "expired", createdAt: Date(), expiresAt: Date())]
        XCTAssertEqual(UsageView.lastAnswer(u), "Your request ended with the period.")
        XCTAssertTrue(KIFFClient.signsSetup("/v1/me/pack-requests/pr_1/decline"))
        XCTAssertFalse(KIFFClient.signsSetup("/v1/me/pack-requests/pr_1/withdraw"))
        XCTAssertFalse(KIFFClient.signsSetup("/v1/me/pack-requests"))
    }
}
