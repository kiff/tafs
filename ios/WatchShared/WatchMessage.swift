import Foundation

// What the watch asks the iPhone, and what the iPhone answers (docs/watch-app.md, "The relay").
// Both apps compile this file. A request goes live with WatchConnectivity's message data and a
// reply, never queued: one made more than `WatchRequest.maxAge` ago is dropped on the phone.

/// A request from the watch. Only the kinds below decode, so anything else, an approval among
/// them, never reaches the phone's code.
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
        /// The watch's own recording, sent ahead as a file (`WatchVoice`), turned into text on the phone with its on-device
        /// recognizer only, by the recording's id in `message`; only the text comes back (`WatchReply.text`). Never sent twice.
        case transcribe
        /// The Inbox's lines the person read and dismissed on any of their watches (row 21), by id alone, as tafs cloud keeps
        /// them (`GET /v1/watch/inbox`). Read only.
        case inboxMarks = "inbox_marks"
        /// Marks Inbox lines read or dismissed (`marks`), by id alone (`POST /v1/watch/inbox`). It changes no one's power.
        case inboxMark = "inbox_mark"
        /// View on phone (docs/watch-app.md, "View on phone"): the phone opens Coach's chat, or with `taf` that taf's, at
        /// once when tafs is in front, else with a notification whose tap opens it. Nothing is read or changed. With `hold`
        /// instead (Approve on your phone), Needs you at that held call, once the phone's own fresh read still lists it under
        /// the account it's signed in to; else Needs you with nothing chosen. Only the hold's id goes: nothing to sign.
        case openOnPhone = "open_on_phone"
        /// Read more on a cut line of a taf's chat from before this visit (row 10, Wear OS's `line-full`): the taf in `taf` and
        /// the line's id in `message`. The phone reads that taf's chat with its session and answers that one message whole, the
        /// taf's words only, never the person's. Read only, shown in place, never kept.
        case lineFull = "line_full"

        /// A read changes nothing, so one whose send failed may go once more (`PhoneRoute`). A write (Refuse, Halt, Halt
        /// all, a Coach message) is never sent twice: a send that failed may still have arrived. Nor is a recording's
        /// `transcribe`: the phone erases the file once it's read, so a second ask would find nothing.
        var isRead: Bool {
            switch self {
            case .needsYou, .tafs, .brief, .taf, .calendar, .account, .coachRead, .askFull, .tafChat, .inboxMarks, .openOnPhone,
                 .lineFull:
                return true
            case .refuse, .halt, .haltAll, .coach, .coachSend, .transcribe, .inboxMark: return false
            }
        }

        /// The person's words or acts (Refuse, Halt, Coach's and a taf's message, an Inbox mark), as Wear OS's
        /// `WatchLink.carriesMark`: the watch sends them with the account mark it holds, so a phone signed in as someone
        /// else since does nothing with them. A read, and a recording's words, are the same for anyone and carry none.
        var carriesMark: Bool {
            switch self {
            // Opening on the phone is the person's act too: a phone signed in as someone else since opens nothing of theirs.
            case .refuse, .halt, .haltAll, .coach, .coachSend, .inboxMark, .openOnPhone: return true
            case .needsYou, .tafs, .brief, .taf, .calendar, .account, .coachRead, .askFull, .tafChat, .inboxMarks,
                 .transcribe, .lineFull: return false
            }
        }
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
    /// Refuse, and Approve on your phone (`openOnPhone`): KIFF's id for the held call.
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
    /// `inboxMark`: the ids to mark read or dismissed.
    var marks: WatchInboxMarks?
    /// The account mark the watch holds (`WatchMark`), on the person's acts only (`Kind.carriesMark`): a phone whose own
    /// mark differs answers signed out and does nothing. None from a watch before marks, or that holds none yet.
    var mark: String?

    init(_ kind: Kind, made: Date, hold: String? = nil, taf: String? = nil, text: String? = nil, message: String? = nil,
         day: String? = nil, ticket: WatchCoachTicket? = nil, marks: WatchInboxMarks? = nil) {
        self.kind = kind
        self.made = made
        self.hold = hold
        self.taf = taf
        self.text = text
        self.message = message
        self.day = day
        self.ticket = ticket
        self.marks = marks
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
        case .transcribe: return WatchVoice.isID(message)
        case .inboxMarks: return true
        case .inboxMark: return marks?.isComplete ?? false
        case .lineFull:
            return given(taf) && (taf ?? "").count <= Self.tafIDLimit && WatchChatLine.isID(message)
        case .openOnPhone:
            // A chat or a held call, never both; a hold's id as KIFF writes them.
            if let hold { return taf == nil && WatchInboxMarks.isID(hold) }
            return taf.map { given($0) && $0.count <= Self.tafIDLimit } ?? true
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
    /// `transcribe`: the recording's words, as the phone's on-device recognizer heard them.
    var text: String?
    /// `inboxMarks` and `inboxMark`: the lines read and dismissed, as tafs cloud keeps them now.
    var marks: WatchInboxMarks?
    /// Whose answer this is (`WatchMark`): the phone's mark for the session it answered with, on every reply while it's
    /// signed in, none once it's signed out. Set by `WatchRelay.answer`, never by a single answer of its own.
    var mark: String?

    init(_ outcome: Outcome, needsYou: WatchNeedsYou? = nil, tafs: [WatchTaf]? = nil, coach: WatchCoachAnswer? = nil,
         brief: WatchBrief? = nil, tafPage: WatchTafPage? = nil, calendar: WatchCalendarDay? = nil, account: WatchAccount? = nil,
         line: String? = nil, ticket: WatchCoachTicket? = nil, answering: Bool? = nil, chat: [WatchChatLine]? = nil,
         zone: String? = nil, text: String? = nil, marks: WatchInboxMarks? = nil) {
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
        self.text = text
        self.marks = marks
    }
}

/// The watch Inbox's lines read and dismissed (row 21), by id alone: a held call's KIFF id, or the id of the message whose
/// answer the line is. Never a line's words, its taf or an amount. tafs cloud keeps them per person (`/v1/watch/inbox`), so
/// each of the person's watches shows the same lines read and gone.
struct WatchInboxMarks: Codable, Equatable, Sendable {
    var read: [String] = []
    var dismissed: [String] = []

    /// The most ids one mark takes, as tafs cloud does.
    static let limit = 100

    /// An id tafs cloud takes: 1 to 100 letters, digits, `_` or `-`.
    static func isID(_ value: String) -> Bool {
        value.range(of: #"^[A-Za-z0-9_-]{1,100}$"#, options: .regularExpression) != nil
    }

    /// A mark to send: 1 to `limit` ids in all, each one tafs cloud takes.
    var isComplete: Bool {
        let all = read + dismissed
        return !all.isEmpty && all.count <= Self.limit && all.allSatisfy(Self.isID)
    }

    /// Only the ids tafs cloud takes, each once, so a read never trips on one it can't use.
    var checked: WatchInboxMarks {
        func each(_ ids: [String]) -> [String] {
            var seen = Set<String>()
            return ids.filter { Self.isID($0) && seen.insert($0).inserted }
        }
        return WatchInboxMarks(read: each(read), dismissed: each(dismissed))
    }
}

/// The watch's own recording for the phone to turn into text (row 44, A; as Wear OS's tafs#1596), behind `on` (off): the
/// watch records itself, so a pause doesn't cut it off, and the phone's on-device recognizer makes the text. The file goes
/// over WatchConnectivity's `transferFile` with its id in the metadata, then `transcribe` asks for its text by that id.
enum WatchVoice {
    /// Off until the privacy page's Apple Watch paragraph says the watch records and the phone turns it into text, and the
    /// owner says yes: it says today that the watch makes no recording.
    static let on = false
    /// The metadata key that marks a file as a recording and gives its id.
    static let metadataKey = "tafs_voice"
    /// The longest recording, as Wear OS's.
    static let limit: TimeInterval = 30
    /// About this long with nothing said ends the recording, as Wear OS's.
    static let quietEnds: TimeInterval = 8
    /// The longest either copy is kept, however it ends: on the phone from when it arrives, on the watch from when it's made.
    static let keepFor: TimeInterval = 120

    /// A recording's id: a UUID, so it can name nothing else, and a file named by it can't leave its folder.
    static func isID(_ value: String?) -> Bool {
        guard let value else { return false }
        return UUID(uuidString: value)?.uuidString.lowercased() == value.lowercased()
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

    /// A line's id as the phone makes it from a chat message (`TafsCloud.ChatMessage.id`, e.g. `s1|1763600005.0|taf|message`):
    /// 1 to 200 letters, digits, `_`, `-`, `.`, `:` or `|`.
    static func isID(_ value: String?) -> Bool {
        guard let value else { return false }
        return value.range(of: #"^[A-Za-z0-9_.:|-]{1,200}$"#, options: .regularExpression) != nil
    }
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
    /// When the answer came, shown under it on the wrist (row 44, C), as Wear OS does. None from an older phone.
    var at: Date? = nil

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
        /// When it lapses, so a line on the watch past it goes by the watch's own clock (`WatchHolds`). Nil from a phone from
        /// before it.
        var ends: Date? = nil

        init(id: String, taf: String?, what: String, summary: String? = nil, reason: String, answerBy: String, ends: Date? = nil) {
            self.id = id
            self.taf = taf
            self.what = what
            self.summary = summary
            self.reason = reason
            self.answerBy = answerBy
            self.ends = ends
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

/// The phone's held calls as it hands them to the watch on its own (`WatchPush.Kind.needsYou`), as Wear OS's `kept/needs-you`:
/// what the Privacy page lets the watch keep, and nothing more. The count of what waits, and each held call as its taf's name
/// and what it asks **with its amount taken out** (`summary`), with when it lapses so a line past it goes on the watch's own
/// clock. Never the amount, why it was held, or anything else of the call: those come from the live Needs you read when the
/// person opens the line, shown in memory only.
struct WatchHolds: Codable, Equatable, Sendable {
    struct Held: Codable, Equatable, Sendable, Identifiable {
        /// KIFF's id for the held call: what Refuse and Approve on your phone name.
        var id: String
        /// The taf's name, or Coach's; nil when the phone couldn't name it.
        var taf: String?
        /// What it asks, without its amount; nil when the phone gave none, and the watch says Needs you.
        var line: String?
        /// When it lapses.
        var ends: Date?
    }

    /// Everything that waits for the person, as tafs cloud counts it.
    var count: Int
    /// The held calls, the soonest to lapse first.
    var calls: [Held]
    /// When the phone read it.
    var at: Date

    init(count: Int, calls: [Held], at: Date) {
        self.count = count
        self.calls = calls
        self.at = at
    }

    /// What of Needs you goes to the watch: no `what`, `reason` or `answerBy`, so no amount and no detail.
    init(_ needsYou: WatchNeedsYou) {
        count = needsYou.count
        calls = needsYou.calls.map { call in
            let line = call.summary?.trimmingCharacters(in: .whitespaces)
            return Held(id: call.id, taf: call.taf, line: line?.isEmpty == false ? line : nil, ends: call.ends)
        }
        at = needsYou.at
    }

    /// The same calls and count, whenever read: whether the watch needs telling again.
    func sameAs(_ other: WatchHolds) -> Bool { count == other.count && calls == other.calls }
}

/// What the phone hands its watch on its own (docs/watch-app.md, "Kept and pushed"): a watch request's new state, a taf chat
/// that changed, or the Inbox's marks, as tafs cloud's silent push told the phone and the phone read with its session.
/// It goes with WatchConnectivity's `transferUserInfo`, queued and delivered in order even while the watch app is put away
/// or the phone's app is in the background, so the thread on the wrist updates the moment it's shown, with no ask.
/// Never a session, a key or a held call's details: what the watch already shows of a request, its line and state, and of a
/// held call only what its Inbox keeps (`WatchHolds`).
struct WatchPush: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Sendable {
        /// A watch request (Coach's, or a taf's chat) changed: `ask` and the phone's read of it, `reply`.
        case ask
        /// A taf's chat changed on the phone or in tafs cloud: `taf`, for a chat on screen to read again.
        case chat
        /// The Inbox's lines read and dismissed: `reply.marks`.
        case inbox
        /// The phone signed in or out: `mark` is the new session's, or none once signed out. Sent at once and as the
        /// application context, so a watch out of reach hears it before anything else when it's back.
        case account
        /// A conversation was deleted on the phone for good: the watch drops its kept copy of the thread `taf` names, or of
        /// Coach's when none (`WatchThreads.drop`). It's only a cache, read again on the next visit.
        case forget
        /// The phone's held calls changed (a hold came, was answered on any device, or ended): `holds`, for the Inbox's lines
        /// at the top and Needs you on screen. Under the account mark, like the rest.
        case needsYou = "needs_you"
    }

    /// The `userInfo` key its bytes go under.
    static let key = "tafs.push"

    var kind: Kind
    var ask: String?
    var taf: String?
    var reply: WatchReply?
    /// `needsYou`: the held calls, as the watch may keep them.
    var holds: WatchHolds?
    var at: Date
    /// Whose push this is (`WatchMark`), as on a reply: set by the phone as it hands the push on, none once signed out.
    var mark: String?

    init(_ kind: Kind, ask: String? = nil, taf: String? = nil, reply: WatchReply? = nil, holds: WatchHolds? = nil, at: Date,
         mark: String? = nil) {
        self.kind = kind
        self.ask = ask
        self.taf = taf
        self.reply = reply
        self.holds = holds
        self.at = at
        self.mark = mark
    }

    /// As `transferUserInfo` takes it: the push's bytes under `key`.
    var userInfo: [String: Any] { [Self.key: WatchMessage.encode(self)] }

    /// The push in what `transferUserInfo` delivered, or nil for anything else.
    static func from(_ userInfo: [String: Any]) -> WatchPush? {
        guard let data = userInfo[key] as? Data else { return nil }
        return WatchMessage.decode(WatchPush.self, from: data)
    }
}

/// Whose data the watch keeps (the account wipe, as Wear OS's): the phone makes a random mark at each sign-in, from
/// nothing about the account (not its tenant, its person or an email), so the watch learns nothing from it. Every reply and
/// push carries it while the phone is signed in. The watch keeps the mark it last heard, and when one arrives that differs,
/// or none arrives after it kept one, everything it kept goes before anything is drawn (`WatchAccountMark`).
enum WatchMark {
    /// A new mark: 32 hex digits of a random UUID.
    static func make() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    /// A mark as the phone makes them, so a damaged one never counts as an account.
    static func isMark(_ value: String?) -> Bool {
        guard let value else { return false }
        return value.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil
    }
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
