import Foundation

/// An amount in minor units (cents) of one currency.
struct Money: Hashable, Comparable, Codable {
    var minor: Int64
    var currency: String = "USD"

    static func usd(_ dollars: Double) -> Money { Money(minor: Int64((dollars * 100).rounded())) }
    static let zero = Money(minor: 0)

    static func < (a: Money, b: Money) -> Bool { a.minor < b.minor }
    static func + (a: Money, b: Money) -> Money { Money(minor: a.minor + b.minor, currency: a.currency) }

    var formatted: String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = currency
        f.maximumFractionDigits = minor % 100 == 0 ? 0 : 2
        return f.string(from: NSNumber(value: Double(minor) / 100)) ?? "\(minor)"
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

    var summary: String {
        if never { return "never" }
        if alwaysAsk { return "always asks you" }
        var parts: [String] = []
        if let perAction { parts.append("≤ \(perAction.formatted) each") }
        if let perWindow { parts.append("\(perWindow.formatted) \(window.label)") }
        if let countPerWindow { parts.append("\(countPerWindow) \(window.label)") }
        return parts.joined(separator: " · ")
    }
}

/// The authority a taf holds. KIFF enforces it in production; the app shows it.
struct Card: Hashable, Codable {
    var rules: [CardRule]
    var issuedBy: String
    var expires: Date
    var revoked = false
}

enum TafState: String, Codable {
    case working, waiting, idle, paused, revoked

    var label: String {
        switch self {
        case .working: return "Working"
        case .waiting: return "Waiting"
        case .idle: return "Idle"
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
    /// Other answers the Card allows besides approve and refuse ("return first").
    var nextSteps: [String] = []
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
    enum Kind: String, Codable { case done, approved, refused, held, unknown, revoked, started }

    var id = UUID()
    var tafID: UUID
    var kind: Kind
    var title: String
    var detail: String
    var at: Date
    /// Present when the owner approved: the signature made on this iPhone.
    var signature: String?
}
