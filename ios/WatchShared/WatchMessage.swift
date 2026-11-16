import Foundation

// What the watch asks the iPhone, and what the iPhone answers (docs/watch-app.md, "The relay").
// Both apps compile this file. A request goes live with WatchConnectivity's message data and a
// reply, never queued: one made more than `WatchRequest.maxAge` ago is dropped on the phone.

/// A request from the watch. Only the six kinds below decode, so anything else, an approval
/// among them, never reaches the phone's code.
struct WatchRequest: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Sendable {
        case needsYou = "needs_you"
        case tafs
        case refuse
        case halt
        case haltAll = "halt_all"
        case coach
    }

    /// How old a request may be when the phone reads it, either way.
    static let maxAge: TimeInterval = 30

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
        case .needsYou, .tafs, .haltAll: return true
        case .refuse: return given(hold)
        case .halt: return given(taf)
        case .coach: return given(text)
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
        /// A request this version of the phone doesn't answer from the watch yet.
        case notYet = "not_yet"
    }

    var outcome: Outcome
    var needsYou: WatchNeedsYou?
    var line: String?

    init(_ outcome: Outcome, needsYou: WatchNeedsYou? = nil, line: String? = nil) {
        self.outcome = outcome
        self.needsYou = needsYou
        self.line = line
    }
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
