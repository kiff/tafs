import Foundation
import StoreKit
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// A product as the app shows it: its name and the price the App Store gives, and, for Solo,
/// its terms.
struct StoreProduct: Equatable {
    let id: String
    let displayName: String
    /// The App Store's price, in the person's currency ("$4.99").
    let displayPrice: String
    /// Solo only: how long a period is, and its free trial when this person can have it.
    var subscription: Terms? = nil

    struct Terms: Equatable {
        /// "month", "week", "year".
        let period: String
        /// "1 week" when this person can start with the free trial; nil otherwise.
        var freeTrial: String? = nil
    }
}

/// A purchase the App Store reported: Apple's signed transaction, and finishing it.
struct StorePurchase: Sendable {
    let id: UInt64
    let productID: String
    /// The transaction as Apple signed it (its JWS): what KIFF verifies.
    let signed: String
    let finish: @Sendable () async -> Void
}

/// How a purchase ended.
enum PurchaseOutcome: Equatable {
    /// Bought and recorded by KIFF.
    case done
    /// The person cancelled.
    case cancelled
    /// Waiting on something outside the app (Ask to Buy, a payment check): it arrives later.
    case pending
    /// Bought, but KIFF hasn't recorded it yet: it's sent again at the next launch.
    case notRecorded(String)
    /// KIFF refused it for good, such as a test purchase on a real account: finished,
    /// with nothing added. The text is what the person reads.
    case notAdded(String)
    /// KIFF can't add it, and a retry won't change that, though the person paid: kept
    /// unfinished for support to sort out. The text is what the person reads.
    case needsSupport(String)
    /// The App Store couldn't sell it.
    case failed(String)
}

/// The App Store, as the app uses it. `AppStore` is StoreKit's; tests use their own.
@MainActor
protocol StoreFront: AnyObject {
    func products(_ ids: [String]) async throws -> [StoreProduct]
    /// Buys a product for the account `accountToken` names. Nil when cancelled or pending.
    func purchase(_ id: String, accountToken: UUID) async throws -> (StorePurchase?, pending: Bool)
    /// Purchases the App Store reports while the app runs: renewals, refunds, Ask to Buy,
    /// purchases made on another device.
    func updates() -> AsyncStream<StorePurchase>
    /// Purchases not finished yet, such as one KIFF didn't record before the app closed.
    func unfinished() async -> [StorePurchase]
    /// Brings back this Apple account's purchases: Solo while it's active, and any not
    /// finished.
    func restore() async throws -> [StorePurchase]
}

/// Buying Solo and task packs in the app, through the App Store.
///
/// Every purchase carries the account's `appAccountToken`, read from KIFF, so KIFF knows
/// which account it is for. Each purchase, whether made here, reported later or restored,
/// is sent to KIFF, and finished only after KIFF has recorded it: one that isn't recorded
/// stays with the App Store and is sent again at the next launch.
@MainActor
final class Purchases: ObservableObject {
    @Published private(set) var products: [String: StoreProduct] = [:]
    @Published private(set) var buying: String?

    private let front: StoreFront
    /// Sends a signed purchase to KIFF, with the request it answers.
    var record: (@MainActor (String, String?) async throws -> Void)?
    /// Called once KIFF has recorded a purchase, so the screens read the account again.
    var recorded: (@MainActor () async -> Void)?
    private var listening: Task<Void, Never>?

    init(front: StoreFront = AppStoreFront()) {
        self.front = front
    }

    /// Listens for purchases the App Store reports, from launch: each is sent to KIFF as it
    /// comes.
    func start() {
        guard listening == nil else { return }
        let stream = front.updates()
        listening = Task { [weak self] in
            for await p in stream {
                guard let self else { return }
                _ = await self.deliver(p, requestID: nil)
            }
        }
    }

    /// Sends the purchases not finished yet: once signed in, at each launch and sign-in.
    func sendUnfinished() async {
        for p in await front.unfinished() { _ = await deliver(p, requestID: nil) }
    }

    func stop() {
        listening?.cancel()
        listening = nil
    }

    /// Shows these products as the App Store's, for the screenshots of the purchase screens:
    /// a simulator has no App Store to read prices from.
    func showSample(_ list: [StoreProduct]) {
        for p in list { products[p.id] = p }
    }

    /// Reads the products' names and prices from the App Store.
    func load(_ ids: [String]) async {
        let missing = ids.filter { products[$0] == nil }
        guard !missing.isEmpty, let read = try? await front.products(missing) else { return }
        for p in read { products[p.id] = p }
    }

    /// Buys a product for the account, answering a member's request when there is one.
    func buy(_ id: String, accountToken: UUID, requestID: String? = nil) async -> PurchaseOutcome {
        buying = id
        defer { buying = nil }
        do {
            let (purchase, pending) = try await front.purchase(id, accountToken: accountToken)
            guard let purchase else { return pending ? .pending : .cancelled }
            return await deliver(purchase, requestID: requestID)
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// Restores this Apple account's purchases and sends each to KIFF.
    func restore() async -> PurchaseOutcome {
        do {
            var outcome = PurchaseOutcome.done
            for p in try await front.restore() {
                switch await deliver(p, requestID: nil) {
                case .needsSupport(let why): outcome = .needsSupport(why)
                case .notRecorded(let why):
                    if case .needsSupport = outcome { break }
                    outcome = .notRecorded(why)
                case .notAdded(let why) where outcome == .done: outcome = .notAdded(why)
                default: break
                }
            }
            return outcome
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// Sends a purchase to KIFF and finishes it once KIFF recorded it.
    func deliver(_ p: StorePurchase, requestID: String?) async -> PurchaseOutcome {
        guard let record else { return .notRecorded("Not signed in.") }
        do {
            try await record(p.signed, requestID)
        } catch KIFFClient.KIFFError.server(403, "test_purchase", _) {
            // A test purchase (TestFlight, the sandbox) on an account that takes only real
            // ones: KIFF adds nothing, now or on a retry, so it's finished.
            await p.finish()
            return .notAdded(Self.testPurchase)
        } catch KIFFClient.KIFFError.server(403, "not_this_account", _) {
            // Another tafs account's purchase on this Apple ID: that account gets it from the
            // App Store's own notice, so this phone finishes it.
            await p.finish()
            return .notAdded(Self.anotherAccount)
        } catch KIFFClient.KIFFError.server(let status, let code, _)
                    where (status == 409 && code == "billed_elsewhere") || (status == 422 && code == "unknown_product") {
            // Paid for, but KIFF can't add it, and a retry won't change that: kept for support.
            return .needsSupport(Self.contactSupport)
        } catch {
            return .notRecorded(error.localizedDescription)
        }
        await p.finish()
        await recorded?()
        return .done
    }

    /// What the person reads when KIFF refused a test purchase.
    static let testPurchase = "Test purchases don't add tasks to this account."
    /// What the person reads when the purchase is another tafs account's.
    static let anotherAccount = "This purchase belongs to another tafs account on this Apple ID, so nothing was added here."
    /// What the person reads when KIFF can't add what they paid for.
    static let contactSupport = "KIFF can't add this purchase to this account. You paid for it, so contact support at support@kiff.dev and we'll sort it out."

    /// Opens the App Store's page for this Apple account's subscriptions.
    static func manageSubscriptions() async {
        #if os(iOS)
        if let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
           (try? await AppStore.showManageSubscriptions(in: scene)) != nil {
            return
        }
        if let url = URL(string: "https://apps.apple.com/account/subscriptions") { _ = await UIApplication.shared.open(url) }
        #elseif os(macOS)
        if let url = URL(string: "https://apps.apple.com/account/subscriptions") { NSWorkspace.shared.open(url) }
        #endif
    }
}

/// StoreKit's App Store.
@MainActor
final class AppStoreFront: StoreFront {
    private var loaded: [String: Product] = [:]

    func products(_ ids: [String]) async throws -> [StoreProduct] {
        var out: [StoreProduct] = []
        for p in try await Product.products(for: ids) {
            loaded[p.id] = p
            var terms: StoreProduct.Terms?
            if let sub = p.subscription {
                terms = .init(period: Self.unit(sub.subscriptionPeriod.unit))
                if let intro = sub.introductoryOffer, intro.paymentMode == .freeTrial, await sub.isEligibleForIntroOffer {
                    terms?.freeTrial = Self.length(intro.period)
                }
            }
            out.append(StoreProduct(id: p.id, displayName: p.displayName, displayPrice: p.displayPrice, subscription: terms))
        }
        return out
    }

    func purchase(_ id: String, accountToken: UUID) async throws -> (StorePurchase?, pending: Bool) {
        if loaded[id] == nil { _ = try await products([id]) }
        guard let product = loaded[id] else { throw StoreError.unavailable }
        switch try await product.purchase(options: [.appAccountToken(accountToken)]) {
        case .success(let result):
            guard let p = Self.purchase(result) else { throw StoreError.unverified }
            return (p, false)
        case .pending:
            return (nil, true)
        case .userCancelled:
            return (nil, false)
        @unknown default:
            return (nil, false)
        }
    }

    func updates() -> AsyncStream<StorePurchase> {
        AsyncStream { continuation in
            let task = Task.detached {
                for await result in Transaction.updates {
                    if let p = Self.purchase(result) { continuation.yield(p) }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func unfinished() async -> [StorePurchase] {
        var out: [StorePurchase] = []
        for await result in Transaction.unfinished {
            if let p = Self.purchase(result) { out.append(p) }
        }
        return out
    }

    func restore() async throws -> [StorePurchase] {
        try await AppStore.sync()
        var out: [StorePurchase] = []
        for await result in Transaction.currentEntitlements {
            if let p = Self.purchase(result) { out.append(p) }
        }
        let seen = Set(out.map(\.id))
        out += await unfinished().filter { !seen.contains($0.id) }
        return out
    }

    /// A purchase StoreKit verified, with Apple's signed form for KIFF to verify again.
    nonisolated static func purchase(_ result: VerificationResult<Transaction>) -> StorePurchase? {
        guard case .verified(let t) = result else { return nil }
        return StorePurchase(id: t.id, productID: t.productID, signed: result.jwsRepresentation, finish: { await t.finish() })
    }

    nonisolated static func unit(_ u: Product.SubscriptionPeriod.Unit) -> String {
        switch u {
        case .day: return "day"
        case .week: return "week"
        case .month: return "month"
        case .year: return "year"
        @unknown default: return "period"
        }
    }

    /// "1 week", "2 weeks".
    nonisolated static func length(_ p: Product.SubscriptionPeriod) -> String {
        let unit = unit(p.unit)
        return p.value == 1 ? "1 \(unit)" : "\(p.value) \(unit)s"
    }

    enum StoreError: LocalizedError {
        case unavailable, unverified
        var errorDescription: String? {
            switch self {
            case .unavailable: return "The App Store doesn't offer this right now."
            case .unverified: return "The App Store couldn't confirm the purchase."
            }
        }
    }
}
