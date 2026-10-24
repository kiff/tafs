import StoreKit
import StoreKitTest
import XCTest
@testable import Tafs

/// The purchase flows against the products as Tafs.storekit defines them, with StoreKit's
/// local testing: real purchases wait on Apple's Paid Apps agreement. KIFF can't verify a
/// local transaction (Xcode signs it, not Apple), so each one here is refused the way KIFF
/// refuses it, and the app must show that nothing was added yet and keep the transaction
/// unfinished, to send again.
@MainActor
final class StoreKitLocalTests: XCTestCase {
    private var session: SKTestSession!
    private let solo = "bot.tafs.solo.monthly"
    private let rayo = "bot.tafs.pack.rayo"

    override func setUp() async throws {
        session = try SKTestSession(configurationFileNamed: "Tafs")
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        // CI's simulator has no local App Store: even the products don't load there. The
        // real check is a sandbox purchase on a TestFlight build; these run wherever local
        // StoreKit testing works.
        if try await Product.products(for: [solo]).isEmpty {
            throw XCTSkip("StoreKit's local testing has no store on this machine: no products load.")
        }
    }

    override func tearDown() async throws {
        session.clearTransactions()
        session = nil
    }

    /// The app's purchases, with KIFF answering as it does to a transaction it can't verify.
    private func refusedByKIFF(_ sent: Sent? = nil) -> Purchases {
        let purchases = Purchases(front: AppStoreFront())
        purchases.record = { signed, _ in
            sent?.add(signed)
            throw KIFFClient.KIFFError.server(status: 400, message: "not a transaction signed by Apple for this app")
        }
        return purchases
    }

    func testTheProductsAndSolosFreeTrial() async throws {
        let purchases = refusedByKIFF()
        await purchases.load([solo, "bot.tafs.pack.pico", "bot.tafs.pack.vonk", rayo, "bot.tafs.pack.forte", "bot.tafs.pack.maxi"])
        XCTAssertEqual(purchases.products.count, 6, "Solo and the five packs")
        XCTAssertEqual(purchases.products[solo]?.subscription?.period, "month")
        XCTAssertEqual(purchases.products[solo]?.subscription?.freeTrial, "1 week", "a new subscriber starts with the free week")
        XCTAssertNil(purchases.products[rayo]?.subscription, "a pack is bought once")
    }

    /// A pack KIFF can't add yet: the app says so, and the transaction stays unfinished.
    func testAPackKIFFCantVerifyStaysUnfinished() async throws {
        let purchases = refusedByKIFF()
        let outcome = await purchases.buy(rayo, accountToken: UUID())
        guard case .notRecorded = outcome else { return XCTFail("outcome \(outcome)") }
        let left = await AppStoreFront().unfinished()
        XCTAssertTrue(left.contains { $0.productID == rayo }, "kept to send again at the next launch")
    }

    /// Solo with its free trial: the same, nothing finished until KIFF records it.
    func testSoloKIFFCantVerifyStaysUnfinished() async throws {
        let purchases = refusedByKIFF()
        let outcome = await purchases.buy(solo, accountToken: UUID())
        guard case .notRecorded = outcome else { return XCTFail("outcome \(outcome)") }
        let left = await AppStoreFront().unfinished()
        XCTAssertTrue(left.contains { $0.productID == solo })
    }

    /// A member's Ask to Buy waits for the parent: nothing to send yet, and the app says it's pending.
    func testAskToBuyIsPending() async throws {
        session.askToBuyEnabled = true
        let sent = Sent()
        let outcome = await refusedByKIFF(sent).buy(rayo, accountToken: UUID())
        XCTAssertEqual(outcome, .pending)
        XCTAssertTrue(sent.lines.isEmpty, "nothing reaches KIFF while it waits")
    }

    /// A purchase the App Store fails: the app says it failed, and nothing reaches KIFF.
    func testAFailedPurchase() async throws {
        session.failTransactionsEnabled = true
        let sent = Sent()
        let outcome = await refusedByKIFF(sent).buy(rayo, accountToken: UUID())
        guard case .failed = outcome else { return XCTFail("outcome \(outcome)") }
        XCTAssertTrue(sent.lines.isEmpty)
    }

    /// A refund the App Store reports comes in as an update, and is sent to KIFF like any
    /// other: KIFF takes the pack back when it can verify it.
    func testARefundIsSentToKIFF() async throws {
        let sent = Sent()
        let purchases = refusedByKIFF(sent)
        purchases.start()
        defer { purchases.stop() }
        _ = await purchases.buy(rayo, accountToken: UUID())
        let bought = sent.lines.count
        XCTAssertEqual(bought, 1)
        let id = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == rayo }?.identifier)
        try session.refundTransaction(identifier: id)
        for _ in 0..<50 where sent.lines.count == bought {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertGreaterThan(sent.lines.count, bought, "the refund reached KIFF")
    }
}

/// What reached KIFF, in order.
@MainActor
final class Sent {
    var lines: [String] = []
    func add(_ line: String) { lines.append(line) }
}
