import Foundation

// What the watch asks the iPhone, and what the iPhone answers (docs/watch-app.md, "The relay").
// Both apps compile this file. A request goes live with WatchConnectivity's message data and a
// reply, never queued: one made more than `WatchRequest.maxAge` ago is dropped on the phone.

/// A request from the watch. Only the seven kinds below decode, so anything else, an approval
/// among them, never reaches the phone's code.
struct WatchRequest: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Sendable {
        case needsYou = "needs_you"
        case tafs
        case refuse
        case halt
        case haltAll = "halt_all"
        case coach
        /// Your brief, as the phone reads it: read only, behind the watch's switch (`briefOnWatch`).
        case brief
    }

    /// How old a request may be when the phone reads it, either way.
    static let maxAge: TimeInterval = 30
    /// The most Coach's words may be, as on Wear OS: longer is refused before anything is sent.
    static let coachLimit = 2_000

    var kind: Kind
    /// When the watch made it.
    var made: Date
    /// Refuse: KIFF's id for the held call.
    var hold: String?
    /// Halt: tafs cloud's id for the taf. Halt all names none.
    var taf: String?
    /// Coach: the person's words, as the watch's dictation wrote them. Never audio.
    var text: String?

    init(_ kind: Kind, made: Date, hold: String? = nil, taf: String? = nil, text: String? = nil) {
        self.kind = kind
        self.made = made
        self.hold = hold
        self.taf = taf
        self.text = text
    }

    /// Whether it names what its kind needs: Refuse its hold, Halt its taf, Coach its words.
    var isComplete: Bool {
        func given(_ value: String?) -> Bool { !(value ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        switch kind {
        case .needsYou, .tafs, .haltAll, .brief: return true
        case .refuse: return given(hold)
        case .halt: return given(taf)
        case .coach: return given(text) && (text ?? "").count <= Self.coachLimit
        }
    }
}

/// The phone's answer.
struct WatchReply: Codable, Equatable, Sendable {
    enum Outcome: String, Codable, Sendable {
        case ok
        /// Not a request the phone answers from the watch: unknown, malformed, or missing what
        /// it names. Nothing was done and nothing was signed.
        case refused
        /// Made more than `WatchRequest.maxAge` ago, or ahead of the phone's clock: dropped.
        case stale
        /// The phone isn't signed in to KIFF: the watch shows nothing it kept.
        case signedOut = "signed_out"
        /// The phone couldn't read or do it: `line` says why, in the phone's words.
        case failed
        /// Refuse: the held call was already answered elsewhere, or lapsed. Nothing was sent.
        case answered = "already_answered"
        /// A request this version of the phone doesn't answer from the watch yet.
        case notYet = "not_yet"
    }

    var outcome: Outcome
    var needsYou: WatchNeedsYou?
    var tafs: [WatchTaf]?
    var coach: WatchCoachAnswer?
    /// Your brief; nil with `ok` when the account has none, and the watch shows Needs you.
    var brief: WatchBrief?
    /// What happened, or why not, in the phone's words: shown only once the phone has KIFF's
    /// or tafs cloud's answer.
    var line: String?

    init(_ outcome: Outcome, needsYou: WatchNeedsYou? = nil, tafs: [WatchTaf]? = nil, coach: WatchCoachAnswer? = nil,
         brief: WatchBrief? = nil, line: String? = nil) {
        self.outcome = outcome
        self.needsYou = needsYou
        self.tafs = tafs
        self.coach = coach
        self.brief = brief
        self.line = line
    }
}

/// Your brief on the wrist, every word tafs cloud's: its title and when, the decisions still to answer
/// with no answers (they're answered on the phone), the line for more, Done's heading and its first cases,
/// and the rest. Read only: a decision about a held call names it (`holdID`), so the watch can offer
/// Refuse where its own Needs you read does.
struct WatchBrief: Codable, Equatable, Sendable {
    struct Decision: Codable, Equatable, Sendable, Identifiable {
        var id: String
        var question: String
        /// The taf's name, when the phone could name it.
        var taf: String?
        /// When it must be decided by, in tafs cloud's words.
        var deadline: String?
        var urgent: Bool
        /// Why it can't wait, in tafs cloud's words.
        var cantWait: String?
        /// What it's about: `hold`, `step`, `connection`, `proposal` or `check`, and its id.
        var refKind: String
        var refID: String
        /// KIFF's id for the held call, when it's about one: what Refuse names.
        var holdID: String?
    }

    /// The most decisions sent to the wrist; the rest are counted in `more`.
    static let wristDecisions = 5
    /// The most of Done's cases sent by name.
    static let wristCases = 2

    var title: String
    var when: String?
    /// tafs cloud's word for urgent.
    var urgent: String?
    var decisions: [Decision]
    /// tafs cloud's line for more decisions than shown.
    var more: String?
    /// Done's heading, and its first cases by name.
    var done: String?
    var doneCases: [String]
    /// tafs cloud's line for the rest, which needs nothing now.
    var rest: String?
}

/// Coach's answer on the wrist: its words, cut short, never its cards. Anything Coach asks
/// to approve, or any card it shows, is on the phone.
struct WatchCoachAnswer: Codable, Equatable, Sendable {
    /// The most of Coach's words the wrist shows.
    static let wristLength = 300

    /// Coach's words, at most `wristLength` characters.
    var text: String
    /// They were cut, or Coach hadn't finished: the rest is on the phone.
    var more: Bool
    /// The answer holds a card or a step: "Approve on your phone", and nothing of it here.
    var approveOnPhone: Bool

    /// Coach's words as the wrist shows them, cut at a word where it can be.
    static func cut(_ words: String, more: Bool, approveOnPhone: Bool) -> WatchCoachAnswer {
        let text = words.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count > wristLength else { return WatchCoachAnswer(text: text, more: more, approveOnPhone: approveOnPhone) }
        let head = String(text.prefix(wristLength))
        let atWord = head.lastIndex(where: \.isWhitespace).map { String(head[..<$0]) } ?? head
        return WatchCoachAnswer(text: atWord + "…", more: true, approveOnPhone: approveOnPhone)
    }
}

/// A taf on the wrist: its name, its state in one line, and whether it's halted.
struct WatchTaf: Codable, Equatable, Sendable, Identifiable {
    /// tafs cloud's id: what Halt names.
    var id: String
    var name: String
    /// Its state in the phone's words ("Halted", "Paused", its next run); nil when there's
    /// nothing to say.
    var line: String?
    /// Halted, or halting: Halt isn't offered again.
    var halted: Bool
}

/// Needs you, as the phone reads it: the count, and each held call in the phone's words.
struct WatchNeedsYou: Codable, Equatable, Sendable {
    struct Call: Codable, Equatable, Sendable, Identifiable {
        /// KIFF's id for the held call.
        var id: String
        /// The taf's name, or Coach's; nil when the phone couldn't name it.
        var taf: String?
        /// What it asks, with its amount: "Refund order 1042, €150.00".
        var what: String
        /// Why it was held: "Over €50 each".
        var reason: String
        /// When it lapses, in the account's time zone: "14:05".
        var answerBy: String
    }

    /// Everything that waits for the person, as tafs cloud counts it. It can be more than
    /// `calls`: steps and other requests are answered on the phone.
    var count: Int
    /// The held calls, the soonest to lapse first.
    var calls: [Call]
    /// When the phone read it.
    var at: Date
}

/// The bytes on the wire: JSON, the same on both sides.
enum WatchMessage {
    static func encode<T: Encodable>(_ value: T) -> Data {
        (try? JSONEncoder().encode(value)) ?? Data()
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) -> T? {
        try? JSONDecoder().decode(type, from: data)
    }
}
