import Foundation

/// Talking to Coach from the wrist (docs/watch-app.md, "Coach"): the person's words, from the
/// watch's own dictation or Scribble, shown before they're sent; Coach's answer, short. Only
/// text goes to the phone, never audio. Kept in memory only, like Needs you.
@MainActor
final class CoachModel: ObservableObject {
    enum State: Equatable {
        case writing
        case sending
        /// v2 (W2): tafs cloud has the words; Coach hasn't started on them yet.
        case sent
        /// v2 (W2): Coach's chat says it's working on them.
        case answering
        /// Coach's answer, or none yet: the rest is on the phone.
        case answered(WatchCoachAnswer?)
        case failed(String)
        /// v2: Delete in Check before sending: the words are gone, and nothing was sent.
        case deleted
    }

    /// v2's conversation (W4): one thing said from this watch, and where Coach's answer to it is.
    struct Turn: Identifiable, Equatable {
        enum Answer: Equatable {
            /// tafs cloud has the words; Coach hasn't started.
            case sent
            /// Coach's chat says it's at them.
            case answering
            /// Coach's answer, or none: the rest is on the phone.
            case answered(WatchCoachAnswer?)
        }

        /// The message's id.
        let id: String
        let said: String
        let at: Date
        var answer: Answer
        /// Where its answer is: a watch request's ticket, for Read more.
        var ticket: WatchCoachTicket? = nil
    }

    /// Read more (a taf's chat): the whole answer, fetched only on the tap and held only while the chat is open.
    enum Whole: Equatable {
        case reading
        case read(WatchCoachAnswer)
        /// Not read: the rest is on the phone.
        case onPhone
    }

    /// v2's Quick reply (docs/watch-app.md, "Step (iii)"): four ready-made lines, each to Check before sending, in Wear OS's
    /// order.
    static var quickReplies: [String] {
        [Words.Watch.Qr.needsMeToday, Words.Watch.Qr.tellMeMore, Words.Watch.Qr.notNow, Words.Watch.Qr.thanks]
    }

    @Published var draft = ""
    /// v2: the words wait in Check before sending, and nothing leaves the watch before Send.
    @Published private(set) var reviewing = false
    @Published private(set) var state: State = .writing
    /// v2 (W2): the words that left, shown above "Sent" and Coach's answer.
    @Published private(set) var asked: String?
    /// v2 (W4): Ask Coach as one conversation, oldest first: what was said from this watch since the app was opened, each with
    /// Coach's answer. In memory only, forgotten when the app is put away or signed out here: nothing new is kept on the watch.
    @Published private(set) var turns: [Turn] = []
    /// A taf's chat: its last turns from before this visit, read through the phone when the chat opens. Forgotten with the
    /// turns; never kept on the watch.
    @Published private(set) var history: [WatchChatLine] = []
    /// The account's zone, as the phone sends it with a taf's chat, so times and days read as on the phone; the
    /// watch's own until the phone says (row 44, C).
    @Published private(set) var zone: TimeZone = .current

    /// A calendar in `zone`, for where a chat's days start.
    var calendar: Calendar {
        var c = Calendar.current
        c.timeZone = zone
        return c
    }
    /// Read more, by turn: each whole answer read on a tap. Forgotten with the turns.
    @Published private(set) var whole: [String: Whole] = [:]
    /// The last request this visit sent: the next Send replies to it (`reply_to`, docs/watch-ask.md), so Coach keeps the
    /// conversation. Gone when the app leaves the screen, as the turns are.
    private var lastTicket: WatchCoachTicket?
    /// Chatting with a taf (docs/watch-ask.md): tafs cloud's id for the taf whose own chat takes the words, or none for
    /// Coach. A taf's words never go to Coach, and its answers never go to the watch's Inbox.
    let taf: String?
    private let phone: PhoneAsking
    private let clock: () -> Date
    /// Sending needs a passcode on this watch (`WristLock`).
    private let lock: WristLock
    /// Makes a message's id.
    private let newID: () -> UUID
    /// The words last sent and their id: the same words sent again, after no answer came, go under
    /// the same id, so Coach acts on them once (docs/chat.md, "One message, one id"). Other words are
    /// another message.
    private var sending: (words: String, id: UUID)?
    /// v2: told of each answer Coach gave, with its message's id, for the Inbox (`InboxModel.answered`). v1 sets none.
    var onAnswer: (@MainActor (WatchCoachAnswer?, String) -> Void)?
    /// v2 (W2): between two reads of Coach's answer.
    private let pause: () async -> Void
    /// Counts each leave, so a read loop started before it stops.
    private var visit = 0

    /// v2 (W2): how many times the watch reads Coach's answer before the rest is on the phone: the first `quickReads`
    /// 1.5 s apart, then 3 s apart (two pauses), so 4 × 1.5 s + 8 × 3 s = 30 s. Coach's turns take seconds, so the slower
    /// reads show no lag, and an ask is 12 reads of the chat rather than 20.
    static let answerReads = 12
    static let quickReads = 4

    init(phone: PhoneAsking, taf: String? = nil, clock: @escaping () -> Date = Date.init, lock: WristLock = DeviceWristLock(),
         newID: @escaping () -> UUID = UUID.init,
         pause: @escaping () async -> Void = { try? await Task.sleep(nanoseconds: 1_500_000_000) }) {
        self.phone = phone
        self.taf = taf
        self.clock = clock
        self.lock = lock
        self.newID = newID
        self.pause = pause
    }

    /// The words are on their way, or Coach is at them: nothing else is sent, re-recorded or deleted.
    var inFlight: Bool { state == .sending || state == .sent || state == .answering }

    var canSend: Bool { !inFlight && !words.isEmpty && words.count <= WatchRequest.coachLimit }
    private var words: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Sends the words the person sees, through the phone.
    func send() async {
        guard canSend else { return }
        // v1's send is Coach's: a taf's words never take it.
        guard taf == nil else { state = .failed(Words.Watch.openOnPhone); return }
        // No passcode on this watch: nothing is sent, and the words stay to send once one is set.
        guard lock.isSecure else { state = .failed(Words.Watch.needsLock); return }
        let text = words
        let id = sending.flatMap { $0.words == text ? $0.id : nil } ?? newID()
        sending = (text, id)
        state = .sending
        let reply = await phone.ask(WatchRequest(.coach, made: clock(), text: text, message: id.uuidString.lowercased()))
        guard let reply else { state = .failed(Words.Watch.phoneNotReachable); return }
        switch reply.outcome {
        case .ok:
            draft = ""
            sending = nil
            state = .answered(reply.coach)
            onAnswer?(reply.coach, id.uuidString.lowercased())
        case .signedOut:
            state = .failed(Words.Watch.signInOnPhone)
        case .failed:
            state = .failed(reply.line ?? Words.Alert.notSent)
        case .refused, .stale, .notYet, .answered:
            state = .failed(Words.Watch.phoneNotReachable)
        }
    }

    /// v2: words from the system's input (dictation, Scribble or the keyboard) or a Quick reply, to Check before sending.
    /// Blank is nothing, and the words are cut at what Coach takes, as on Wear OS. Re-record lands here too: the new
    /// words take the old ones' place, never while a Send is in flight.
    func review(_ words: String) {
        // Words in flight stay the words shown until the phone answers: new words now would show what wasn't sent.
        guard !inFlight else { return }
        let text = String(words.trimmingCharacters(in: .whitespacesAndNewlines).prefix(WatchRequest.coachLimit))
        guard !text.isEmpty else { return }
        draft = text
        reviewing = true
        state = .writing
    }

    /// v2's Send, from Check before sending (W2): the words leave as v1's do (the same id when sent again, the lock checked),
    /// and the phone answers as soon as tafs cloud has them: "Sent". Then the watch reads Coach's answer: "Coach is
    /// answering" while its chat says so, then the answer; past `answerReads`, what it has, with the rest on the phone.
    /// A failure keeps the words in the review, to send again under the same id. A phone from before W2 is sent v1's way.
    func sendReviewed() async {
        guard reviewing, canSend else { return }
        guard lock.isSecure else { state = .failed(Words.Watch.needsLock); return }
        let text = words
        let id = sending.flatMap { $0.words == text ? $0.id : nil } ?? newID()
        sending = (text, id)
        state = .sending
        let reply = await phone.ask(WatchRequest(.coachSend, made: clock(), taf: taf, text: text, message: id.uuidString.lowercased(),
                                                 ticket: lastTicket))
        guard let reply else { state = .failed(Words.Watch.phoneNotReachable); return }
        switch reply.outcome {
        case .ok:
            guard let ticket = reply.ticket else { state = .failed(Words.Watch.phoneNotReachable); return }
            lastTicket = ticket
            draft = ""
            sending = nil
            reviewing = false
            asked = text
            state = .sent
            turns.append(Turn(id: id.uuidString.lowercased(), said: text, at: clock(), answer: .sent, ticket: ticket))
            await read(ticket, message: id.uuidString.lowercased())
        case .notYet where taf != nil, .refused where taf != nil:
            // A phone from before chatting with a taf: the words stay, and never go to Coach.
            state = .failed(Words.Watch.openOnPhone)
        case .notYet, .refused:
            // A phone from before W2 doesn't know the two steps: v1's send, the same words under the same id.
            state = .writing
            await send()
            if case .answered(let answer) = state {
                asked = text
                reviewing = false
                turns.append(Turn(id: id.uuidString.lowercased(), said: text, at: clock(), answer: .answered(answer)))
            }
        case .signedOut:
            state = .failed(Words.Watch.signInOnPhone)
        case .failed:
            state = .failed(reply.line ?? Words.Alert.notSent)
        case .stale, .answered:
            state = .failed(Words.Watch.phoneNotReachable)
        }
    }

    /// Coach's answer, read until its chat no longer says it's working and it has written, for `answerReads` at most. A
    /// read the phone can't answer is read again: the words were sent, so the watch never says they weren't.
    private func read(_ ticket: WatchCoachTicket, message: String) async {
        let visit = self.visit
        var last: WatchCoachAnswer?
        for index in 0..<Self.answerReads {
            await pause()
            if index >= Self.quickReads { await pause() }
            guard visit == self.visit else { return }
            let reply = await phone.ask(WatchRequest(.coachRead, made: clock(), ticket: ticket))
            guard visit == self.visit else { return }
            guard let reply, reply.outcome == .ok else { continue }
            if let coach = reply.coach { last = coach }
            if reply.answering == true {
                state = .answering
                setTurn(message, .answering)
            } else if let coach = reply.coach {
                finish(coach, message: message)
                return
            }
        }
        last?.more = true
        finish(last, message: message)
    }

    private func finish(_ answer: WatchCoachAnswer?, message: String) {
        // The ask may have been closed meanwhile: the conversation still gets its answer.
        if inFlight { state = .answered(answer) }
        setTurn(message, .answered(answer))
        // The Inbox keeps Coach's answers only: nothing of a taf's chat is kept on the watch.
        if taf == nil { onAnswer?(answer, message) }
    }

    private func setTurn(_ id: String, _ answer: Turn.Answer) {
        guard let i = turns.firstIndex(where: { $0.id == id }) else { return }
        turns[i].answer = answer
    }

    /// A taf's chat opened: its last turns, read through the phone. Nothing for Coach, whose home shows this visit's turns.
    /// A read that fails leaves what's shown.
    func loadHistory() async {
        guard let taf else { return }
        let visit = self.visit
        let reply = await phone.ask(WatchRequest(.tafChat, made: clock(), taf: taf))
        guard visit == self.visit, let reply, reply.outcome == .ok, let chat = reply.chat else { return }
        if let id = reply.zone, let given = TimeZone(identifier: id) { zone = given }
        // This visit's turns are shown below them: only what came before the first is history, so none shows twice.
        guard let first = turns.first?.at else { history = chat; return }
        history = chat.filter { $0.at < first }
    }

    /// Read more: the whole answer to a turn, read through the phone only now, and shown in place. A phone or a tafs cloud that
    /// can't give it leaves the fallback, "The rest is on your phone."
    func readMore(_ turn: String) async {
        guard let ticket = turns.first(where: { $0.id == turn })?.ticket, ticket.askID != nil else { whole[turn] = .onPhone; return }
        if case .reading? = whole[turn] { return }
        if case .read? = whole[turn] { return }
        let visit = self.visit
        whole[turn] = .reading
        let reply = await phone.ask(WatchRequest(.askFull, made: clock(), ticket: ticket))
        guard visit == self.visit else { return }
        if let reply, reply.outcome == .ok, let answer = reply.coach, !answer.text.isEmpty {
            whole[turn] = .read(answer)
        } else {
            whole[turn] = .onPhone
        }
    }

    /// v2: the ask closed with the app still open. Words not sent go; a Send on its way carries on, and its answer lands in
    /// the conversation (W4).
    func closeAsk() {
        // Still on its way to the phone: what comes back decides. Sent goes on in the conversation; a failure keeps the words
        // in the review, to send again under the same id.
        if state == .sending { return }
        draft = ""
        sending = nil
        reviewing = false
        guard !inFlight else { return }
        asked = nil
        state = .writing
    }

    /// v2's Delete, in Check before sending: the words go, and nothing was sent. Not while they're in flight, since then
    /// they may already have reached Coach.
    func delete() {
        guard !inFlight else { return }
        draft = ""
        sending = nil
        reviewing = false
        state = .deleted
    }

    /// The app left the screen: the words and the answer go.
    func leave() {
        visit += 1
        asked = nil
        turns = []
        whole = [:]
        history = []
        lastTicket = nil
        draft = ""
        sending = nil
        reviewing = false
        state = .writing
    }
}
