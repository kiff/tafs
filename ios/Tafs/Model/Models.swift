import Foundation

/// An amount as an integer, with what it means when that is known: a unit
/// (a currency code such as "EUR", or another unit) and its decimal places.
///
/// Without a unit it is a bare number and is shown as stored: the app never
/// guesses a currency or a scale, and never lets it read like money
/// ("30 000 (no currency)", kiff/tafs#309).
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

    /// The amount as shown: with its unit, or saying it has none (kiff/tafs#309). An amount
    /// with no currency is the vendor's own number, often its smallest unit (cents), so it
    /// never stands alone where money would.
    var formatted: String { hasUnit ? figure : "\(figure) (no currency)" }

    /// The amount without the "no currency" note: for a figure whose words say it once.
    var figure: String {
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

/// A Card's spending window: KIFF's own name for it (calendar_day, rolling_24h, rolling_1h or
/// lifetime) and its words. tafs cloud sends the words with it; for what the app reads from
/// KIFF itself, KIFF's four are known here, exactly. A window it doesn't know reads as it was
/// sent, never as a day (a lifetime or hourly total read as daily before).
struct Window: Hashable, Codable, Sendable {
    let raw: String
    /// Ends a limit: "a day", "in any hour", "in total".
    let label: String
    /// The window's use so far: "today", "in the last hour", "so far".
    let current: String

    static let day = Window("calendar_day")

    private static let known: [String: (each: String, now: String)] = [
        "calendar_day": ("a day", "today"),
        "rolling_24h": ("in any 24 hours", "in the last 24 hours"),
        "rolling_1h": ("in any hour", "in the last hour"),
        "lifetime": ("in total", "so far"),
    ]

    /// The window `raw`, in `words` when the server sent them.
    init(_ raw: String, each: String? = nil, now: String? = nil) {
        // "day" is how the app kept a window before it kept KIFF's own name.
        let raw = raw == "day" ? "calendar_day" : raw
        self.raw = raw
        label = each ?? Self.known[raw]?.each ?? raw
        current = now ?? Self.known[raw]?.now ?? raw
    }

    /// Ends an amount's total: "in total a day" for a day's, so a bare number reads apart from
    /// a count; any other window's words already say it ("in any hour", "in total").
    var sumLabel: String { raw == "calendar_day" ? "in total \(label)" : label }

    init(from decoder: Decoder) throws {
        self.init(try decoder.singleValueContainer().decode(String.self))
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(raw)
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
    /// Of perWindow, the extra room KIFF says is active (given on a held call), the Card's own
    /// limit, and when the room ends, as KIFF says them: shown apart from the Card's own total,
    /// never worked out here (the 755 retest).
    var extraRoom: Money?
    var baseLimit: Money?
    var roomEnds: Date?

    /// The Card's own total for its window: KIFF's, without the extra room in it.
    var ownTotal: Money? {
        extraRoom != nil ? (baseLimit ?? perWindow) : perWindow
    }

    /// "+30 US$ extra room until 00:00", when KIFF says there is some: with its date when the
    /// room ends on a later day, as a week's or a month's does.
    var roomWords: String? {
        guard let extraRoom, baseLimit != nil else { return nil }
        return "+\(extraRoom.formatted) extra room " + (roomEnds.map { "until \($0.endsWords)" } ?? window.current)
    }

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
        if outside == .hold {
            // Past a limit the call asks the owner, in the words KIFF's Cards use, the same on
            // both apps: "up to $40 each; more asks you first".
            if let perAction { parts.append("up to \(perAction.formatted) each; more asks you first") }
            if let own = ownTotal { parts.append("\(own.formatted) \(window.sumLabel), then it asks you") }
            if let roomWords { parts.append(roomWords) }
            if let countPerWindow {
                parts.append(countPerWindow == 0 ? "Each call asks you first"
                             : "\(countPerWindow) \(countPerWindow == 1 ? "call" : "calls") \(window.label), then it asks you")
            }
            return parts.joined(separator: " · ")
        }
        if let perAction { parts.append("≤ \(perAction.formatted) each") }
        // A bare total and a count read apart (kiff/tafs#94): "10 in total a day · 20 calls a day".
        if let own = ownTotal { parts.append(own.hasUnit ? "\(own.formatted) \(window.label)" : "\(own.formatted) \(window.sumLabel)") }
        if let roomWords { parts.append(roomWords) }
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
    /// Of limit, the extra room KIFF says is active, the Card's own limit, and when the room
    /// ends; nil without any.
    var extra: Value? = nil
    var base: Value? = nil
    var roomEnds: Date? = nil
    /// What of its use is from calls the tool refused and still counts, in KIFF's words.
    var pending: String? = nil
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

    /// What of its totals' use is from calls the tool refused, in KIFF's words: they count
    /// until KIFF can confirm nothing moved (kiff-cloud's limit lines).
    var pending: [String] { [total?.pending, calls?.pending].compactMap { $0 } }

    /// The pill on the face: LIVE, or the environment it's scoped to ("TEST").
    /// The environment a Card is scoped to, when it isn't every one or production: nothing for
    /// a Card that acts for real, since "Live" read as live money beside a test-mode connection
    /// (the 870 test).
    var environmentLabel: String? { live || environment.isEmpty ? nil : environment.uppercased() }

    /// The face's big figure and the words under it: what's left of the money total, else of
    /// the calls. Use KIFF couldn't read shows the limit alone, never as zero left or used.
    var hero: (figure: String, words: String)? {
        if let t = total {
            // An amount with no currency says so once, after the words, and its figures stand
            // bare beside it: "30 000 left today of 30 000 · no currency" (kiff/tafs#309).
            let unitless = !t.limit.hasUnit
            let shown = { (m: Money) in unitless ? m.figure : m.formatted }
            let note = unitless ? " · no currency" : ""
            if let used = t.used, used.currency == t.limit.currency, used.scale == t.limit.scale {
                let left = Money(minor: max(0, t.limit.minor - used.minor), currency: t.limit.currency, scale: t.limit.scale)
                // The Card's own total and the extra room apart, as KIFF says them (the 755 retest):
                // "of 300 US$ + 30 US$ extra room until 00:00".
                if let extra = t.extra, let own = t.base {
                    let until = t.roomEnds.map { " until \($0.endsWords)" } ?? ""
                    return (shown(left), "left \(t.window.current) of \(shown(own)) + \(shown(extra)) extra room\(until)\(note)")
                }
                return (shown(left), "left \(t.window.current) of \(shown(t.limit))\(note)")
            }
            return (shown(t.limit), "\(t.window.label) · use unavailable\(note)")
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
        if let t = total {
            let figure = t.limit.hasUnit ? hero.figure : "\(hero.figure) (no currency)"
            return hero.words.hasPrefix("left") ? "\(figure) left" : figure
        }
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
            out.append(rule.outside == .hold ? "up to \(each.formatted) each; more asks you first" : "≤ \(each.formatted) each · refused above")
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
    /// Halted, on its own or with its account (kiff/tafs#236, item 13): nothing of it runs until resumed.
    case halted

    var label: String {
        switch self {
        case .working: return "Working"
        case .waiting: return "Waiting"
        case .idle: return "Ready"
        case .paused: return "Paused"
        case .revoked: return "Stopped"
        case .halted: return "Halted"
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
    /// A short word beside its name, as tafs cloud sends it: "Trial" for an open trial's
    /// entry (kiff/tafs#247); nil for a taf.
    var badge: String? = nil

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
    enum Kind: String, Codable {
        case done, approved, refused, held, unknown, revoked, started, notDone = "not_done", sending
        /// Something done in tafs itself, not a call: a halt, a resume, a door's knock (kiff/tafs#236).
        case inTafs = "in_tafs"
    }

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
