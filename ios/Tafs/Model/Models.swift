import Foundation

/// An amount as an integer, with what it means when that is known: a unit
/// (a currency code such as "EUR", or another unit) and its decimal places.
///
/// Without a unit it is a bare number and is shown as stored: the app never
/// guesses a currency or a scale.
struct Money: Hashable, Comparable, Codable {
    var minor: Int64
    /// The declared unit; nil when there is none or it could not be read.
    var currency: String?
    /// Decimal places in `minor`: 2 means 2550 is 25.50.
    var scale: Int

    init(minor: Int64, currency: String?, scale: Int) {
        self.minor = minor
        self.currency = currency
        self.scale = currency == nil ? 0 : scale
    }

    /// A number with no declared unit.
    static func bare(_ value: Int64) -> Money { Money(minor: value, currency: nil, scale: 0) }
    static func usd(_ dollars: Double) -> Money { Money(minor: Int64((dollars * 100).rounded()), currency: "USD", scale: 2) }
    static let zero = Money.bare(0)

    var hasUnit: Bool { currency != nil }

    static func < (a: Money, b: Money) -> Bool { a.minor < b.minor }
    /// Adding to zero takes the other side's unit, so sums start from `.zero`.
    static func + (a: Money, b: Money) -> Money {
        let unit = a.minor == 0 && !a.hasUnit ? b : a
        return Money(minor: a.minor + b.minor, currency: unit.currency, scale: unit.scale)
    }

    /// The amount in whole units: 2550 at scale 2 is 25.5.
    var value: Decimal { Decimal(minor) / pow(Decimal(10), scale) }

    var formatted: String {
        guard let currency else {
            return NumberFormatter.localizedString(from: NSNumber(value: minor), number: .decimal)
        }
        let f = NumberFormatter()
        let whole = minor % Self.power(scale) == 0
        // NSLocale's list: Locale's is macOS 13 and later, and the Mac app runs on 12 (kiff/tafs#85).
        if NSLocale.commonISOCurrencyCodes.contains(currency.uppercased()) {
            f.numberStyle = .currency
            f.currencyCode = currency.uppercased()
            f.minimumFractionDigits = whole ? 0 : scale
            f.maximumFractionDigits = whole ? 0 : scale
            return f.string(from: value as NSDecimalNumber) ?? "\(minor)"
        }
        f.numberStyle = .decimal
        f.minimumFractionDigits = whole ? 0 : scale
        f.maximumFractionDigits = whole ? 0 : scale
        return "\(f.string(from: value as NSDecimalNumber) ?? "\(minor)") \(currency)"
    }

    private static func power(_ n: Int) -> Int64 { (0..<max(n, 0)).reduce(1) { acc, _ in acc * 10 } }

    // Samples saved before units existed were all US cents.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let minor = try c.decode(Int64.self, forKey: .minor)
        let currency = try c.decodeIfPresent(String.self, forKey: .currency)
        let scale = try c.decodeIfPresent(Int.self, forKey: .scale) ?? (currency == nil ? 0 : 2)
        self.init(minor: minor, currency: currency, scale: scale)
    }
}

enum Window: String, Codable, CaseIterable {
    case day, week, month

    var label: String {
        switch self {
        case .day: return "a day"
        case .week: return "a week"
        case .month: return "a month"
        }
    }
}

/// What happens to an action the Card does not allow.
enum Outside: String, Codable {
    case hold    // waits for the owner
    case refuse  // refused without asking
}

/// One line of a Card: the limits on one kind of action.
struct CardRule: Identifiable, Hashable, Codable {
    var id = UUID()
    var action: String          // "refund", "pay_invoice", "grant_access"
    var label: String           // "Refunds"
    var perAction: Money?       // up to, each
    var perWindow: Money?       // up to, per window
    var countPerWindow: Int?    // how many, per window
    var window: Window = .day
    var never = false           // never allowed, whatever the amount
    var alwaysAsk = false       // allowed only with the owner's answer
    var outside: Outside = .hold
    /// Answers the owner may give besides approve and refuse, when held.
    var nextSteps: [String] = []

    /// What's left of its total this window ("€800 left today"), given what it has used,
    /// when both are in the same unit; nil otherwise.
    func left(used: Money?) -> String? {
        guard let total = perWindow, let used, total.currency == used.currency, total.scale == used.scale else { return nil }
        let left = Money(minor: max(0, total.minor - used.minor), currency: total.currency, scale: total.scale)
        let when: String
        switch window {
        case .day: when = "today"
        case .week: when = "this week"
        case .month: when = "this month"
        }
        return "\(left.formatted) left \(when)"
    }

    var summary: String {
        if never { return "never" }
        if alwaysAsk { return "always asks you" }
        var parts: [String] = []
        if let perAction { parts.append("≤ \(perAction.formatted) each") }
        // A bare total and a count read apart (kiff/tafs#94): "10 in total a day · 20 calls a day".
        if let perWindow { parts.append(perWindow.hasUnit ? "\(perWindow.formatted) \(window.label)" : "\(perWindow.formatted) in total \(window.label)") }
        if let countPerWindow { parts.append("\(countPerWindow) \(countPerWindow == 1 ? "call" : "calls") \(window.label)") }
        return parts.joined(separator: " · ")
    }
}

/// The authority a taf holds. KIFF enforces it in production; the app shows it.
struct Card: Hashable, Codable {
    var rules: [CardRule]
    var issuedBy: String
    /// When the Card ends; nil when it has no end (KIFF gives no valid_until).
    var expires: Date?
    var revoked = false
}

enum TafState: String, Codable {
    case working, waiting, idle, paused, revoked

    var label: String {
        switch self {
        case .working: return "Working"
        case .waiting: return "Waiting"
        case .idle: return "Ready"
        case .paused: return "Paused"
        case .revoked: return "Stopped"
        }
    }
}

struct Taf: Identifiable, Hashable, Codable {
    var id = UUID()
    var name: String
    var job: String
    var tools: [String]
    var colorIndex: Int
    var card: Card
    var state: TafState = .idle
    var now: String = "Ready"
    /// Spent so far in the current window, per rule action.
    var used: [String: Money] = [:]
    /// Rule actions whose use KIFF could not read: shown as unknown, never as zero.
    /// Nil in sample mode.
    var usageUnknown: Set<String>?
    var counted: [String: Int] = [:]
    /// The KIFF Cards this taf holds, when signed in to KIFF.
    var kiffCardIDs: [String] = []

    var initial: String { String(name.prefix(1)).uppercased() }
}

/// An action a taf asked to take.
struct ProposedAction: Hashable, Codable {
    var action: String
    var summary: String         // "Refund order #1042"
    var amount: Money?
    var context: String = ""    // what the taf saw, in one line
}

/// An action outside the Card, waiting for the owner.
struct HeldCall: Identifiable, Hashable, Codable {
    var id = UUID()
    var tafID: UUID
    var proposed: ProposedAction
    var reason: String          // "Over $50 each"
    var heldAt: Date
    var expiresAt: Date
    /// KIFF's id for this held call, when it came from KIFF.
    var kiffID: String?
    /// The proposal KIFF held, which its record files the call under.
    var proposalID: String?
    /// Whether approving it once can pass it (kiff/tafs#138, item 49): not when it's over a
    /// total the Card keeps across calls (a day's), which only a change to the Card can lift.
    var approvableOnce = true
    /// KIFF's accounting version for the hold, signed with the answer.
    var accountingVersion: Int?
    /// Other answers the Card allows besides approve and refuse ("return first").
    var nextSteps: [String] = []
    /// What the call is on, one line each, as KIFF keeps its arguments. Optional, so a
    /// saved sample from before it still loads.
    var details: [String]?
    /// Coach asks to install a version (kiff/tafs#206): approving it is installing it,
    /// switch and Card first, then this answer, in one Face ID. Nil for any other call.
    var install: VersionInstall?
}

/// The taf and version a held version_install names, as Coach sent them: the taf's id in
/// tafs cloud, or its name.
struct VersionInstall: Hashable, Codable {
    var taf: String
    var n: Int
}

/// The owner's answer to a held call: approve it once, refuse it, or refuse it with
/// one of the Card's next steps for the taf to follow ("Return first").
enum Answer: Hashable, Codable {
    case approveOnce
    case refuse
    case nextStep(String)

    /// The words signed and sent, stable across versions.
    var wireName: String {
        switch self {
        case .approveOnce: return "approve-once"
        case .refuse: return "reject"
        case .nextStep(let step): return "reject:\(step)"
        }
    }
}

struct RecordEntry: Identifiable, Hashable, Codable {
    /// done and approved: the tool did it. notDone: allowed, but the tool refused it or it
    /// was never sent. sending: sent, the tool hasn't answered. unknown: whether the tool did
    /// it isn't known (kiff/tafs#115).
    enum Kind: String, Codable { case done, approved, refused, held, unknown, revoked, started, notDone = "not_done", sending }

    var id = UUID()
    var tafID: UUID
    var kind: Kind
    var title: String
    var detail: String
    var at: Date
    /// Present when the owner approved: the signature made on this iPhone.
    var signature: String?
    /// The owner approved it (a held call), whatever the tool then did.
    var ownerApproved: Bool?
    /// What the Record says of it (kiff/tafs#167): how much, what it acted on, a read, the
    /// owner's answer, and its receipt. What it acted on is KIFF's hash of it; its value is
    /// tafs' own (the run's call_ref, 30 days), shown only when it matches KIFF's hash.
    var amount: KIFFClient.ActivityCall.Amount?
    var readOnly: Bool?
    var entityID: String?
    var onHash: String?
    var onArg: String?
    var onValue: String?
    var answer: KIFFClient.ActivityCall.Answer?
    var receipt: KIFFClient.ActivityCall.Receipt?
    var why: [String]?
    /// When the call was made (KIFF's first decision), and why it wasn't sent, in KIFF's words.
    var madeAt: Date?
    var reason: String?
}
