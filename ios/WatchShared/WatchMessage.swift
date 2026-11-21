import Foundation

// What the watch asks the iPhone, and what the iPhone answers (docs/watch-app.md, "The relay").
// Both apps compile this file. A request goes live with WatchConnectivity's message data and a
// reply, never queued: one made more than `WatchRequest.maxAge` ago is dropped on the phone.

/// A request from the watch. Only the twelve kinds below decode, so anything else, an approval
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
        /// One taf's page (watch v2), by tafs cloud's id in `taf`: its state, job, Cards with today's use, and
        /// how many of its calls wait on the person. Read only.
        case taf
        /// A calendar day (watch v2): `day` as `YYYY-MM-DD`, or today in the account's zone with none. Read only.
        case calendar
        /// Who is signed in, and the plan's line (watch v2). Read only.
        case account
        /// v2's Send (W2): Coach's words as `coach` sends them, answered as soon as tafs cloud has them, with where Coach's
        /// answer will be (`WatchReply.ticket`), so the watch can say "Sent" before Coach answers. With `taf`, the words go
        /// to that taf's own chat instead (chatting with a taf, docs/watch-ask.md), never to Coach.
        case coachSend = "coach_send"
        /// v2 (W2): Coach's answer so far to the words a ticket names, and whether Coach is still at it. Read only.
        case coachRead = "coach_read"
        /// Chatting with a taf, Read more: the whole answer to the watch request a ticket names, read only on the tap.
        /// Read only.
        case askFull = "ask_full"
        /// Chatting with a taf: the last turns of the person's own chat with the taf in `taf`, as the phone shows it. Read only.
        case tafChat = "taf_chat"
    }

    /// How old a request may be when the phone reads it, either way.
    static let maxAge: TimeInterval = 30
    /// The most Coach's words may be, as on Wear OS: longer is refused before anything is sent.
    static let coachLimit = 2_000
    /// The longest taf id a taf's page may name, as on Wear OS.
    static let tafIDLimit = 128

    var kind: Kind
    /// When the watch made it.
    var made: Date
    /// Refuse: KIFF's id for the held call.
    var hold: String?
    /// Halt: tafs cloud's id for the taf. Halt all names none. `coachSend`: the taf whose own chat takes the words.
    var taf: String?
    /// Coach: the person's words, as the watch's dictation wrote them. Never audio.
    var text: String?
    /// Coach: the message's own id (a UUID), made on the watch for the words it sends, and the same when
    /// the person sends those words again after no answer came. The phone sends it to tafs cloud as
    /// `client_message_id`, so a message that reached Coach once is acted on once. A phone from before
    /// it ignores it.
    var message: String?
    /// Calendar: the day, `YYYY-MM-DD`; none is today in the account's zone.
    var day: String?
    /// Coach's answer so far (`coachRead`): where it will be, as the phone said when it sent the words.
    var ticket: WatchCoachTicket?

    init(_ kind: Kind, made: Date, hold: String? = nil, taf: String? = nil, text: String? = nil, message: String? = nil,
         day: String? = nil, ticket: WatchCoachTicket? = nil) {
        self.kind = kind
        self.made = made
        self.hold = hold
        self.taf = taf
        self.text = text
        self.message = message
        self.day = day
        self.ticket = ticket
    }

    /// A calendar day as the watch names it: `YYYY-MM-DD`, and nothing else.
    static func isDay(_ value: String) -> Bool {
        value.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) != nil
    }

    /// Whether it names what its kind needs: Refuse its hold, Halt and a taf's page their taf, Coach its words, and a
    /// calendar day, if it names one, a day.
    var isComplete: Bool {
        func given(_ value: String?) -> Bool { !(value ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        switch kind {
        case .needsYou, .tafs, .haltAll, .brief, .account: return true
        case .refuse: return given(hold)
        case .halt: return given(taf)
        case .taf: return given(taf) && (taf ?? "").count <= Self.tafIDLimit
        case .calendar: return day.map(Self.isDay) ?? true
        case .coach: return given(text) && (text ?? "").count <= Self.coachLimit
        case .coachSend:
            return given(text) && (text ?? "").count <= Self.coachLimit && (taf.map { given($0) && $0.count <= Self.tafIDLimit } ?? true)
        case .coachRead: return ticket?.isComplete ?? false
        case .askFull: return (ticket?.isComplete ?? false) && ticket?.askID != nil
        case .tafChat: return given(taf) && (taf ?? "").count <= Self.tafIDLimit
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
    /// A taf's page; nil with `failed` for a taf the phone doesn't list.
    var tafPage: WatchTafPage?
    /// A calendar day.
    var calendar: WatchCalendarDay?
    /// Who is signed in, and the plan's line.
    var account: WatchAccount?
    /// What happened, or why not, in the phone's words: shown only once the phone has KIFF's
    /// or tafs cloud's answer.
    var line: String?
    /// `coachSend`: where Coach's answer to the words will be, for `coachRead`.
    var ticket: WatchCoachTicket?
    /// `coachRead`: Coach is still at it (its chat says it's working).
    var answering: Bool?
    /// `tafChat`: the last turns of the person's chat with the taf, oldest first.
    var chat: [WatchChatLine]?
    /// `tafs` and `tafChat`: the account's time zone (an IANA identifier), so the wrist's times and days read as the
    /// phone's from the first screen (row 44, C), as Wear OS's `WatchReply.Tafs.zone`. None from an older phone: the
    /// watch's own.
    var zone: String?

    init(_ outcome: Outcome, needsYou: WatchNeedsYou? = nil, tafs: [WatchTaf]? = nil, coach: WatchCoachAnswer? = nil,
         brief: WatchBrief? = nil, tafPage: WatchTafPage? = nil, calendar: WatchCalendarDay? = nil, account: WatchAccount? = nil,
         line: String? = nil, ticket: WatchCoachTicket? = nil, answering: Bool? = nil, chat: [WatchChatLine]? = nil,
         zone: String? = nil) {
        self.outcome = outcome
        self.needsYou = needsYou
        self.tafs = tafs
        self.coach = coach
        self.brief = brief
        self.tafPage = tafPage
        self.calendar = calendar
        self.account = account
        self.line = line
        self.ticket = ticket
        self.answering = answering
        self.chat = chat
        self.zone = zone
    }
}

/// One turn of a taf's chat as the wrist shows it (chatting with a taf): who said it, when, and its words cut as an answer is.
/// Shown while the chat is open and never kept on the watch.
struct WatchChatLine: Codable, Equatable, Sendable, Identifiable {
    var id: String
    /// The person (true) or the taf.
    var mine: Bool
    var at: Date
    var text: String
    /// Cut: the rest is on the phone.
    var more: Bool

    /// The most turns the wrist shows from before this visit.
    static let last = 6
}

/// Where Coach's answer to the words the watch sent will be (v2's Send, W2): Coach's chat, the conversation the words
/// went into, and tafs cloud's id for the message. The phone reads only Coach's own chat by it.
struct WatchCoachTicket: Codable, Equatable, Sendable {
    var chat: String
    var session: String
    var sent: String?

    /// A ticket for a watch request of its own (docs/watch-ask.md): `chat` is this, and `session` the request's id.
    static let askChat = "watch-ask"

    /// The watch request this ticket names, when it names one.
    var askID: String? { chat == Self.askChat ? session : nil }

    /// Each id named, and none longer than a taf's id may be.
    var isComplete: Bool {
        [chat, session, sent ?? "x"].allSatisfy { id in
            !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && id.count <= WatchRequest.tafIDLimit
        }
    }
}

/// One taf's page on the wrist (watch v2), in the phone's words: its state, its job, each Card as the phone's Card tab
/// draws it (title, the face's figure and line, its rules one line each), and how many of its calls wait on the
/// person. Nothing on it is changed from the wrist but Halt.
struct WatchTafPage: Codable, Equatable, Sendable {
    struct Card: Codable, Equatable, Sendable {
        var title: String
        var figure: String?
        var words: String?
        var rules: [String]
    }

    /// tafs cloud's id: what Halt names.
    var id: String
    var name: String
    var state: String
    var halted: Bool
    var job: String
    var cards: [Card]
    var waiting: Int
}

/// A calendar day on the wrist (watch v2), in tafs cloud's words: its line, and that day's events in time order.
struct WatchCalendarDay: Codable, Equatable, Sendable {
    struct Event: Codable, Equatable, Sendable {
        /// Its time in the account's zone, as tafs cloud writes it.
        var time: String
        /// Its taf's name.
        var taf: String
        var words: String
        var count: Int
    }

    var day: String
    var words: String
    var events: [Event]
    var halted: Bool
}

/// Who is signed in on the phone, the account's name, and the plan's line in tafs cloud's words (watch v2).
struct WatchAccount: Codable, Equatable, Sendable {
    var email: String?
    var account: String?
    var plan: String?
}

/// Your brief on the wrist, every word tafs cloud's: its title and when, every decision it sends still to answer
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

    /// The most of Done's cases sent by name. Decisions aren't cut: tafs cloud already caps them (every urgent
    /// one kept), and `more` is its count of the rest.
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

    /// The most of a whole answer (Read more) the wrist takes: tafs cloud caps it at 16 KB, and the watch never holds more.
    static let wholeLength = 16_000

    /// A whole answer, for Read more: never cut at `wristLength`, only at `wholeLength`.
    static func whole(_ words: String, approveOnPhone: Bool) -> WatchCoachAnswer {
        let text = words.trimmingCharacters(in: .whitespacesAndNewlines)
        return WatchCoachAnswer(text: String(text.prefix(wholeLength)), more: false, approveOnPhone: approveOnPhone)
    }

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
    /// Paused (and not halted): its chat still takes a message, which it reads when it's back. Nil from a phone from before.
    var paused: Bool? = nil
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
        /// What it asks, without its amount: "Refund order 1042". The one line watch v2's Inbox keeps, as Wear OS's: the
        /// amount is shown only while Needs you holds the call, in memory. Nil from a phone from before it.
        var summary: String? = nil
        /// Why it was held: "Over €50 each".
        var reason: String
        /// When it lapses, in the account's time zone: "14:05".
        var answerBy: String

        init(id: String, taf: String?, what: String, summary: String? = nil, reason: String, answerBy: String) {
            self.id = id
            self.taf = taf
            self.what = what
            self.summary = summary
            self.reason = reason
            self.answerBy = answerBy
        }
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
    /// Every message between the phone and the watch, with no direction character in any of its text (U+202A–U+202E,
    /// U+2066–U+2069, U+200E, U+200F, U+061C: the phone's `FileText.isDirection`), so a payee or taf name can't reorder an
    /// amount on the watch's approval. The encoder writes them as themselves, never escaped, and none is ever JSON's own.
    static func encode<T: Encodable>(_ value: T) -> Data {
        guard let data = try? JSONEncoder().encode(value), let text = String(data: data, encoding: .utf8) else { return Data() }
        return Data(String(String.UnicodeScalarView(text.unicodeScalars.filter { !isDirection($0) })).utf8)
    }

    static func isDirection(_ s: Unicode.Scalar) -> Bool {
        (0x202A...0x202E).contains(s.value) || (0x2066...0x2069).contains(s.value) || [0x200E, 0x200F, 0x061C].contains(s.value)
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) -> T? {
        try? JSONDecoder().decode(type, from: data)
    }
}
