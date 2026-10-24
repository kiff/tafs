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

    /// The window now: "today", "this week", "this month".
    var current: String {
        switch self {
        case .day: return "today"
        case .week: return "this week"
        case .month: return "this month"
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
        return "\(left.formatted) left \(window.current)"
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

/// A Card's total for its window and what it has used: nil when KIFF couldn't read it,
/// never shown as zero.
struct CardTotal<Value: Hashable & Codable & Sendable>: Hashable, Codable, Sendable {
    var limit: Value
    var used: Value?
    var window: Window
}

/// One KIFF Card, drawn on its own (kiff/tafs#211): a taf can hold several, each with its
/// own limits and balance, and a test Card is kept apart from the live one.
struct TafCard: Identifiable, Hashable, Codable {
    var id: String
    /// Its own title from KIFF, else its main tool's.
    var title: String
    /// The environment KIFF scopes it to, as KIFF names it; empty for every environment.
    var environment = ""
    /// It covers what the taf does for real: production, or every environment.
    var live = true
    var active = true
    var expires: Date?
    var rules: [CardRule]
    /// Its money total, at the ceiling it has now.
    var total: CardTotal<Money>?
    /// Its count of calls.
    var calls: CardTotal<Int>?

    /// The pill on the face: LIVE, or the environment it's scoped to ("TEST").
    var environmentLabel: String { live ? "LIVE" : environment.uppercased() }

    /// The face's big figure and the words under it: what's left of the money total, else of
    /// the calls. Use KIFF couldn't read shows the limit alone, never as zero left or used.
    var hero: (figure: String, words: String)? {
        if let t = total {
            if let used = t.used, used.currency == t.limit.currency, used.scale == t.limit.scale {
                let left = Money(minor: max(0, t.limit.minor - used.minor), currency: t.limit.currency, scale: t.limit.scale)
                return (left.formatted, "left \(t.window.current) of \(t.limit.formatted)")
            }
            return (t.limit.formatted, "\(t.window.label) · use unavailable")
        }
        if let c = calls {
            guard let used = c.used else { return ("\(c.limit)", "calls \(c.window.label) · use unavailable") }
            let left = max(0, c.limit - used)
            return ("\(left)", "\(left == 1 ? "call" : "calls") left \(c.window.current) of \(c.limit)")
        }
        return nil
    }

    /// What's left, short enough for a Card's edge in the stack (kiff/tafs#236, item 9):
    /// "US$170 left", "998 calls left".
    var leftLine: String? {
        guard let hero else { return nil }
        if total != nil { return hero.words.hasPrefix("left") ? "\(hero.figure) left" : hero.figure }
        if let c = calls, c.used != nil { return "\(hero.figure) \(hero.figure == "1" ? "call" : "calls") left" }
        return nil
    }

    /// The face's small lines under the figure: the main tool's limit for one call and what
    /// happens above it, then how many more tools the Card covers. A Card with no figure
    /// shows its first lines instead.
    var facts: [String] {
        var out: [String] = []
        if hero == nil {
            out = rules.prefix(2).map { "\($0.label.lowercased()) \($0.summary)" }
            if rules.count > 2 { out.append("\(rules.count - 2) more") }
            return out
        }
        if let rule = rules.first(where: { $0.perAction != nil }), let each = rule.perAction {
            out.append("≤ \(each.formatted) each · \(rule.outside == .hold ? "asks you above" : "refused above")")
        }
        if rules.count > 1 { out.append("\(rules.count) tools") }
        return out
    }

    /// One Card from a taf's lines as the app had them before KIFF's Cards came apart: a
    /// draft's, the sample's, or a list from an older tafs cloud.
    static func merged(id: String, title: String, rules: [CardRule], expires: Date?, active: Bool = true,
                       used: [String: Money] = [:], unknown: Set<String> = [], counted: [String: Int] = [:]) -> TafCard {
        var card = TafCard(id: id, title: title, active: active, expires: expires, rules: rules)
        if let rule = rules.first(where: { $0.perWindow != nil }), let limit = rule.perWindow {
            let read = used[rule.action] ?? (unknown.contains(rule.action) ? nil : Money(minor: 0, currency: limit.currency, scale: limit.scale))
            card.total = CardTotal(limit: limit, used: read, window: rule.window)
        } else if let rule = rules.first(where: { $0.countPerWindow != nil }), let limit = rule.countPerWindow {
            // As the money total: no count is none used, unless KIFF couldn't read it.
            card.calls = CardTotal(limit: limit, used: counted[rule.action] ?? (unknown.contains(rule.action) ? nil : 0), window: rule.window)
        }
        return card
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
    /// Each of its KIFF Cards on its own, the live ones first (kiff/tafs#211); nil from an
    /// older tafs cloud, and in the sample.
    var kiffCards: [TafCard]?
    /// Its doors, as its avatar's rim shows them; nil when it has none, or before doors.
    var doors: [DoorLight]? = nil

    var initial: String { String(name.prefix(1)).uppercased() }

    /// The Cards its page draws: each KIFF Card, else its lines as one Card.
    var shownCards: [TafCard] {
        // The live Cards first, and among them the one that counts money (kiff/tafs#236,
        // item 9): for a taf that pays out, what's left to spend matters most. Otherwise as KIFF lists them.
        if let kiffCards, !kiffCards.isEmpty {
            return kiffCards.enumerated().sorted { a, b in
                let ka = (a.element.live ? 0 : 2) + (a.element.total != nil ? 0 : 1)
                let kb = (b.element.live ? 0 : 2) + (b.element.total != nil ? 0 : 1)
                return ka != kb ? ka < kb : a.offset < b.offset
            }.map(\.element)
        }
        return [TafCard.merged(id: "merged", title: name, rules: card.rules, expires: card.expires, active: !card.revoked,
                               used: used, unknown: usageUnknown ?? [], counted: counted)]
    }
}

/// One of a taf's doors, as its avatar's rim shows it: lit while open, amber while a
/// knock waits, grey while it rests for the day.
struct DoorLight: Hashable, Codable {
    var app: String
    var open: Bool
    var resting: Bool = false
    var knocks: Int = 0

    init(app: String, open: Bool, resting: Bool = false, knocks: Int = 0) {
        self.app = app; self.open = open; self.resting = resting; self.knocks = knocks
    }

    enum CodingKeys: String, CodingKey { case app, open, resting, knocks }
    /// What's missing reads as nothing to show, so a rim never stops the list loading.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        app = (try? c.decode(String.self, forKey: .app)) ?? ""
        open = (try? c.decode(Bool.self, forKey: .open)) ?? false
        resting = (try? c.decode(Bool.self, forKey: .resting)) ?? false
        knocks = (try? c.decode(Int.self, forKey: .knocks)) ?? 0
    }
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

/// The owner's answer to a held call: approve it once, refuse it, refuse it with one of
/// the Card's next steps for the taf to follow ("Return first"), or give its Card the
/// extra room this call needs until that total resets (kiff/tafs#211, 6.13): KIFF's
/// change-card, the only answer that passes a call over a total kept across calls.
enum Answer: Hashable, Codable {
    case approveOnce
    case refuse
    case nextStep(String)
    case extraRoom

    /// The words signed and sent, stable across versions.
    var wireName: String {
        switch self {
        case .approveOnce: return "approve-once"
        case .refuse: return "reject"
        case .nextStep(let step): return "reject:\(step)"
        case .extraRoom: return "change-card"
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
    /// KIFF allowed the call and the vendor refused it, and it still counts against the
    /// Card's totals (kiff/tafs#211, 6.13).
    var refusedByTool: Bool?
    /// Who refused it at the tool: the vendor as connected, when known.
    var refusedBy: String?
}
