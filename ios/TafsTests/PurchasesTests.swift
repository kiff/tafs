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

    // MARK: Usage's lines

    static func usage(source: String, plan: String = "solo", canAdd: Bool = true, left: Int? = 10) -> KIFFClient.Usage {
        var u = KIFFClient.Usage(plan: plan, period: .init(start: Date(), end: Date().addingTimeInterval(86_400)),
                                 tasks: .init(counted: 10, included: 20, packs: 0, left: left), actions: .init(counted: 0, included: 500), trial: nil)
        u.billing = .init(source: source, manageURL: source == "apple" ? URL(string: "https://apps.apple.com/account/subscriptions") : nil)
        u.canAddTasks = canAdd
        u.store = .init(appAccountToken: UUID(), soloProduct: "bot.tafs.solo.monthly",
                        packs: [.init(id: "rayo", name: "Rayo", line: "Rayo · 70 tasks", tasks: 70, months: 12, appleProduct: "bot.tafs.pack.rayo")])
        return u
    }

    /// One billing line: Apple's, KIFF's or the agreement's. KIFF's says nothing more: no link,
    /// and nothing about where to buy.
    func testEachBillingLine() {
        XCTAssertEqual(UsageView.billingLine(Self.usage(source: "apple")), "Billed by Apple")
        XCTAssertTrue(UsageView.managedByApple(Self.usage(source: "apple")))
        XCTAssertEqual(UsageView.billingLine(Self.usage(source: "stripe")), "Billed by KIFF")
        XCTAssertFalse(UsageView.managedByApple(Self.usage(source: "stripe")))
        XCTAssertEqual(UsageView.billingLine(Self.usage(source: "contract", plan: "company")), "Managed by your company's agreement")
        XCTAssertNil(UsageView.billingLine(Self.usage(source: "", plan: "trial")))
    }

    /// After a move from Solo to Team (billed by KIFF), Solo still renews in the App Store until
    /// it's cancelled there: one line says so, with Apple's own page. Nothing about where to buy.
    func testSoloStillRenewsAfterTheMoveToTeam() {
        var u = Self.usage(source: "stripe", plan: "team")
        XCTAssertNil(UsageView.soloStillRenews(u), "nothing to say without a Solo renewing")
        u.billing?.appleSoloUntil = ISO8601DateFormatter().date(from: "2026-11-03T09:00:00Z")
        let line = try? XCTUnwrap(UsageView.soloStillRenews(u))
        XCTAssertTrue(line?.hasPrefix("Team is billed by KIFF now. Your Solo in the App Store renews on ") ?? false, line ?? "")
        XCTAssertTrue(line?.hasSuffix(" unless you cancel it.") ?? false)
        XCTAssertFalse(line?.contains("kiff.dev") ?? true, "the app never points to buying elsewhere")
        u.billing?.source = "apple"
        XCTAssertNil(UsageView.soloStillRenews(u), "while Apple bills, Manage says it all")
    }

    func testTheSoloRenewalIsRead() throws {
        let json = #"{"source":"stripe","manage_url":null,"apple_solo_until":"2026-11-03T09:00:00Z"}"#
        let b = try KIFFClient.decoder.decode(KIFFClient.Usage.Billing.self, from: Data(json.utf8))
        XCTAssertEqual(b.appleSoloUntil, ISO8601DateFormatter().date(from: "2026-11-03T09:00:00Z"))
    }

    /// Solo is offered only to an account with no plan billed, to someone who may add tasks:
    /// never to one billed by KIFF, by an agreement, or already by Apple.
    func testNoSubscriptionOfferToAnAccountBilledElsewhere() {
        XCTAssertTrue(UsageView.offersSolo(Self.usage(source: "", plan: "trial")))
        XCTAssertFalse(UsageView.offersSolo(Self.usage(source: "stripe")))
        XCTAssertFalse(UsageView.offersSolo(Self.usage(source: "stripe", plan: "trial")), "a Stripe trial is billed by KIFF")
        XCTAssertFalse(UsageView.offersSolo(Self.usage(source: "contract", plan: "company")))
        XCTAssertFalse(UsageView.offersSolo(Self.usage(source: "apple")))
        XCTAssertFalse(UsageView.offersSolo(Self.usage(source: "", plan: "trial", canAdd: false)), "a member asks an admin")
    }

    /// Solo's terms go with every offer: its period and price, that it renews until
    /// cancelled, and how to cancel.
    func testSoloCarriesItsTerms() {
        let trial = StoreProduct(id: "bot.tafs.solo.monthly", displayName: "Solo", displayPrice: "$10.99",
                                 subscription: .init(period: "month", freeTrial: "1 week"))
        XCTAssertEqual(SoloView.action(trial), "Try 1 week free")
        let terms = SoloView.terms(trial)
        for part in ["Free for 1 week, then $10.99 a month.", "one month at $10.99", "renews every month until you cancel", "Cancel any time"] {
            XCTAssertTrue(terms.contains(part), "\(terms) lacks \(part)")
        }
        let plain = StoreProduct(id: "bot.tafs.solo.monthly", displayName: "Solo", displayPrice: "$10.99", subscription: .init(period: "month"))
        XCTAssertEqual(SoloView.action(plain), "Subscribe for $10.99 a month")
        XCTAssertFalse(SoloView.terms(plain).contains("Free"))
    }

    // MARK: Asking an admin

    static let read = #"""
    {"plan":"team","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},
     "tasks":{"counted":40,"included":40,"packs":0,"left":0,"by_agent":[],"free":{}},
     "actions":{"counted":0,"included":1000},"trial":null,
     "billing":{"source":"stripe","manage_url":null},"pack_list":[],"can_add_tasks":true,
     "store":{"app_account_token":"6f1c2b9e-3a4d-4c1f-9e2a-1b2c3d4e5f60","solo_product":"bot.tafs.solo.monthly",
              "packs":[{"id":"rayo","name":"Rayo","line":"Rayo · 70 tasks","tasks":70,"months":12,"apple_product":"bot.tafs.pack.rayo","stripe_price":"x"}]},
     "requests":[{"id":"pr_1","asked_by":"bob","pack":"rayo","reason":"Refunds ran out","status":"open",
                  "created_at":"2026-10-20T09:00:00Z","expires_at":"2026-11-01T00:00:00Z"}]}
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
        StubProtocol.responses["GET /v1/me/usage/tasks"] = (200, #"{"items":[],"next":null}"#)
        await model.useSession("kiff_dev_x", tenant: "t1")
        await model.refreshUsage()
        return (model, store, signer)
    }

    private func sent(_ method: String, _ path: String) -> [String: String]? {
        guard let r = StubProtocol.sent.last(where: { $0.httpMethod == method && $0.url?.path == path }), let body = r.httpBody else { return nil }
        return try? JSONDecoder().decode([String: String].self, from: body)
    }

    /// The admin approves a member's request by buying the pack: the purchase goes to KIFF
    /// with the request's id and the account's token, then is finished.
    func testApprovingBuysThePackForTheRequest() async throws {
        let log = StoreLog()
        let (model, store, _) = await signedIn(log)
        let u = try XCTUnwrap(model.usage)
        XCTAssertEqual(UsageView.openRequests(u).map(\.id), ["pr_1"])
        StubProtocol.responses["POST /v1/me/billing/apple/transactions"] = (200, #"{"recorded":true,"action":"pack"}"#)
        let pack = try XCTUnwrap(u.store?.packs.first)
        let request = try XCTUnwrap(u.requests?.first)
        store.bought = store.purchaseNamed(7)
        await model.buyPack(pack, answering: request)
        XCTAssertEqual(sent("POST", "/v1/me/billing/apple/transactions"), ["signed_transaction": "jws-7", "request_id": "pr_1"])
        XCTAssertEqual(store.tokens, [UUID(uuidString: "6f1c2b9e-3a4d-4c1f-9e2a-1b2c3d4e5f60")!])
        XCTAssertEqual(log.lines, ["finish 7"])
        XCTAssertNil(model.tasksMessage)
    }

    /// A test purchase on an account that takes only real ones: KIFF refuses it, so the app
    /// finishes it, adds nothing, and says so; it isn't sent again at the next launch.
    func testARefusedTestPurchaseIsFinishedAndSaysSo() async throws {
        let log = StoreLog()
        let (model, store, _) = await signedIn(log)
        StubProtocol.responses["POST /v1/me/billing/apple/transactions"] = (403,
            #"{"error":"test_purchase","message":"Test purchases don't add tasks to this account."}"#)
        let pack = try XCTUnwrap(model.usage?.store?.packs.first)
        store.bought = store.purchaseNamed(8)
        await model.buyPack(pack)
        XCTAssertEqual(log.lines, ["finish 8"])
        XCTAssertEqual(model.tasksMessage, "Test purchases don't add tasks to this account.")
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
        XCTAssertEqual(model.tasksMessage, Purchases.anotherAccount)
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
        XCTAssertEqual(model.tasksMessage, Purchases.contactSupport)
        XCTAssertFalse(model.tasksMessage?.contains("tries again") ?? true)
    }

    /// A product KIFF doesn't sell: paid, kept unfinished for support, and said so.
    func testAnUnknownProductGoesToSupport() async throws {
        let log = StoreLog()
        let (model, store, _) = await signedIn(log)
        StubProtocol.responses["POST /v1/me/billing/apple/transactions"] = (422, #"{"error":"unknown_product"}"#)
        store.bought = store.purchaseNamed(11)
        await model.buyPack(try XCTUnwrap(model.usage?.store?.packs.first))
        XCTAssertEqual(log.lines, [])
        XCTAssertEqual(model.tasksMessage, Purchases.contactSupport)
    }

    /// Declining is the admin's answer, signed behind Face ID; asking and withdrawing are a
    /// member's, unsigned.
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

        StubProtocol.responses["POST /v1/me/pack-requests"] = (201,
            #"{"id":"pr_2","asked_by":"bob","pack":"rayo","reason":"Refunds","status":"open","created_at":"2026-10-20T09:00:00Z","expires_at":"2026-11-01T00:00:00Z"}"#)
        let pack = try XCTUnwrap(model.usage?.store?.packs.first)
        let asked = await model.askForPack(pack, reason: "Refunds ")
        XCTAssertTrue(asked)
        XCTAssertEqual(sent("POST", "/v1/me/pack-requests"), ["pack": "rayo", "reason": "Refunds"])
        let ask = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/me/pack-requests" })
        XCTAssertNil(ask.value(forHTTPHeaderField: "KIFF-Device-Signature"))
    }

    /// A member sees their request waiting, and what an admin answered.
    func testAMemberSeesTheirRequest() throws {
        var u = try KIFFClient.decoder.decode(KIFFClient.Usage.self, from: Data(Self.read.utf8))
        u.canAddTasks = false
        XCTAssertEqual(UsageView.waiting(u)?.id, "pr_1")
        XCTAssertTrue(UsageView.openRequests(u).isEmpty, "a member answers nothing")
        u.requests = [.init(id: "pr_1", askedBy: "bob", pack: "rayo", status: "declined", createdAt: Date(), expiresAt: Date(), note: "Next month")]
        XCTAssertNil(UsageView.waiting(u))
        XCTAssertEqual(UsageView.lastAnswer(u), "An admin declined Rayo · 70 tasks: Next month")
        XCTAssertTrue(KIFFClient.signsSetup("/v1/me/pack-requests/pr_1/decline"))
        XCTAssertFalse(KIFFClient.signsSetup("/v1/me/pack-requests/pr_1/withdraw"))
        XCTAssertFalse(KIFFClient.signsSetup("/v1/me/pack-requests"))
    }
}
