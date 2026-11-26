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

    /// v2's Keep for later (docs/watch-app.md, "Keep for later"; Wear OS's `WatchModel.Draft`): one message to Coach kept on
    /// the watch to send when the phone is back. Its words, its message id and the chat it continues; text only.
    struct Kept: Codable, Equatable {
        let text: String
        let message: String
        let ticket: WatchCoachTicket?
        /// Whose words these are (`WatchMark`): the phone's mark when they were kept. Sent only while the phone answers with
        /// the same mark; under another, or none, they go unsent.
        var mark: String? = nil
    }

    /// Read more (a taf's chat): the whole answer, fetched only on the tap and held only while the chat is open.
    enum Whole: Equatable {
        case reading
        case read(WatchCoachAnswer)
        /// Not read: the rest is on the phone.
        case onPhone
    }

    /// Keep for later (row 8), on: it keeps the person's words on the watch, which the privacy page's Apple Watch paragraph
    /// says, with the owner's yes. Off, no store is given and it's never offered.
    nonisolated static let keepForLaterOn = true

    /// v2's Quick reply (docs/watch-app.md, "Step (iii)"): four ready-made lines, each to Check before sending, in Wear OS's
    /// order.
    static var quickReplies: [String] {
        [Words.Watch.Qr.needsMeToday, Words.Watch.Qr.tellMeMore, Words.Watch.Qr.notNow, Words.Watch.Qr.thanks]
    }

    @Published var draft = ""
    /// v2: the words wait in Check before sending, and nothing leaves the watch before Send.
    @Published private(set) var reviewing = false
    @Published private(set) var state: State = .writing
    /// Watch row 24: each tap on the complication (`ComplicationLink.ask`), counted, so a view on screen sees the tap
    /// (`onChange`).
    @Published private(set) var askRequests = 0
    /// A complication tap not yet acted on. `onChange` sees only a change while a view is on screen, never the value it
    /// first appears with, and a complication most often opens the app cold, so the pages and Coach's home also look at
    /// this when they appear. Coach's home takes it once (`takeAskPending`), and listens.
    @Published private(set) var askPending = false
    /// v3: a Send got no reply and goes again by itself: the screen says "Linking tafs", never Try again.
    @Published private(set) var linking = false
    /// v2: the last Send got no reply from the phone, which may have it: Keep for later is offered beside Send.
    @Published private(set) var unsure = false
    /// v2's Keep for later: the message kept on this watch, or none. On disk (`DraftStore`) until it's sent, deleted, or the
    /// phone says it's signed out; putting the app away keeps it.
    @Published private(set) var kept: Kept?
    /// v2 (W2): the words that left, shown above "Sent" and Coach's answer.
    @Published private(set) var asked: String?
    /// v2 (W4): Ask Coach as one conversation, oldest first: what was said from this watch since the app was opened, each with
    /// Coach's answer. In memory only, forgotten when the app is put away or signed out here: nothing new is kept on the watch.
    @Published private(set) var turns: [Turn] = []
    /// A taf's chat: its last turns from before this visit, read through the phone when the chat opens. Forgotten with the
    /// turns; never kept on the watch.
    @Published private(set) var history: [WatchChatLine] = []
    /// The account's zone, as the phone sends it with Your tafs and a taf's chat, so times and days read as on the
    /// phone; the watch's own until the phone says (row 44, C).
    var zone: TimeZone { WatchAccountZone.zone }

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
    /// v3: before a Send that got no reply goes again (`reachRetrying`): 2 s, then 4, then 8.
    private let linkPause: (Int) async -> Void
    /// Counts each leave, so a read loop started before it stops.
    private var visit = 0
    /// The owner's call 1: where this thread's copy is kept (`WatchThreads`, off until the privacy page says so).
    private let threads: WatchThreads
    /// Where Keep for later keeps its message: v2's file, or none (v1, and tests that don't use it), and then it's never offered.
    private let drafts: DraftStore?
    /// A kept message on its way: the next read doesn't send it again meanwhile.
    private var sendingKept = false
    /// Whose data the watch keeps (`WatchAccountMark`): a kept message is shown and sent only under its own account's mark.
    /// None in the tests that don't use it.
    private let account: WatchAccountMark?

    /// v2 (W2): how many times the watch reads Coach's answer before the rest is on the phone: the first `quickReads`
    /// 1.5 s apart, then 3 s apart (two pauses), so 4 × 1.5 s + 8 × 3 s = 30 s. Coach's turns take seconds, so the slower
    /// reads show no lag, and an ask is 12 reads of the chat rather than 20.
    static let answerReads = 12
    static let quickReads = 4

    init(phone: PhoneAsking, taf: String? = nil, clock: @escaping () -> Date = Date.init, lock: WristLock = DeviceWristLock(),
         newID: @escaping () -> UUID = UUID.init, drafts: DraftStore? = nil,
         pause: @escaping () async -> Void = { try? await Task.sleep(nanoseconds: 1_500_000_000) },
         linkPause: @escaping (Int) async -> Void = { attempt in
             try? await Task.sleep(nanoseconds: UInt64(1 << min(attempt, 3)) * 1_000_000_000)
         },
         threads: WatchThreads = .shared, account: WatchAccountMark? = nil) {
        self.phone = phone
        self.account = account
        self.threads = threads
        self.linkPause = linkPause
        self.taf = taf
        self.clock = clock
        self.lock = lock
        self.newID = newID
        self.drafts = drafts
        self.pause = pause
        kept = taf == nil ? readKept() : nil
        // As the Inbox: nothing of it stays on a watch without a passcode.
        checkLock()
        WatchPushes.shared.listen(self)
        reopen()
    }

    /// This thread's key where it's kept: Coach's, or the taf's id.
    var threadKey: String { taf ?? WatchThreads.coach }

    /// The owner's call 1: the thread opens at once on its stored copy, before any read. Nothing when it's off.
    func reopen() {
        guard turns.isEmpty, history.isEmpty else { return }
        history = threads.lines(threadKey)
    }

    /// The thread as shown, as lines: what came before, then this visit's turns with their answers.
    var shownLines: [WatchChatLine] {
        var lines = history
        for turn in turns {
            lines.append(WatchChatLine(id: turn.id, mine: true, at: turn.at, text: turn.said, more: false))
            if case .answered(let answer?) = turn.answer, !answer.text.isEmpty {
                lines.append(WatchChatLine(id: turn.id + ".answer", mine: false, at: answer.at ?? turn.at, text: answer.text,
                                           more: answer.more))
            }
        }
        return lines
    }

    /// Keeps the thread's copy as shown now (`WatchThreads`; nothing when it's off).
    private func keepThread() {
        threads.keep(threadKey, shownLines)
    }

    /// The app was opened or put away, as the Inbox's `checkLock`: on a watch without a passcode, a message kept for later is
    /// deleted, unsent, as the Inbox's lines are, so the watch keeps nothing without one. A watch that's only locked keeps it.
    func checkLock() {
        guard taf == nil, drafts != nil, !lock.isSecure else { return }
        deleteKept()
    }

    /// The complication was tapped: Coach's home listens, as its microphone does. Nothing is sent before the person's Send.
    func askFromComplication() {
        askPending = true
        askRequests += 1
    }

    /// Takes the complication's pending tap, once: true the first time after a tap, then false until the next.
    func takeAskPending() -> Bool {
        guard askPending else { return false }
        askPending = false
        return true
    }

    /// The words are on their way, or Coach is at them: nothing else is sent, re-recorded or deleted.
    var inFlight: Bool { state == .sending || state == .sent || state == .answering }

    /// One tap to talk (row 44, A): Ask goes straight to the system's dictation only when there's nothing to come back to (no
    /// words on their way, none kept in the review after a failed Send) and the watch has a passcode, as Wear OS never
    /// listens without a screen lock. Otherwise Ask opens the ask as before.
    var listensFirst: Bool { !inFlight && !reviewing && lock.isSecure }

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
        let reply: WatchReply
        switch await phone.reach(WatchRequest(.coach, made: clock(), text: text, message: id.uuidString.lowercased())) {
        case .reply(let answered): reply = answered
        case .failed(let why): state = .failed(why.line); return
        }
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
        unsure = false
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
        unsure = false
        state = .sending
        let reply: WatchReply
        let request = WatchRequest(.coachSend, made: clock(), taf: taf, text: text, message: id.uuidString.lowercased(),
                                   ticket: lastTicket)
        switch await reachRetrying(request) {
        case .reply(let answered): reply = answered
        case .failed(let why):
            // No reply: the phone may have the words, or may be out of reach. Coach's may be kept for later (as Wear OS's
            // "Not sure it arrived"); a taf's never are, since nothing of a taf's chat is kept on the watch.
            state = .failed(why.waiting)
            unsure = taf == nil && drafts != nil
            return
        }
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
            keepThread()
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

    /// v3 (the owner's calls on the watch, docs/watch-app.md, "Linking tafs"): a Send the phone didn't answer goes again by
    /// itself, under the same id, which tafs cloud takes once, so the person never has to tap Try again. Between tries the
    /// watch says "Linking tafs. Keep your phone nearby and connected." `linkTries` sends in all, `linkPause` apart; tafs
    /// missing from the phone isn't tried again.
    static let linkTries = 4

    private func reachRetrying(_ request: WatchRequest) async -> PhoneReach {
        let visit = self.visit
        var reached = await phone.reach(request)
        for attempt in 1..<Self.linkTries {
            guard case .failed(let why) = reached, why != .noApp, visit == self.visit else { break }
            linking = true
            await linkPause(attempt)
            guard visit == self.visit else { break }
            reached = await phone.reach(request)
        }
        linking = false
        return reached
    }

    /// Coach's answer, read until its chat no longer says it's working and it has written, for `answerReads` at most. A
    /// read the phone can't answer is read again: the words were sent, so the watch never says they weren't.
    private func read(_ ticket: WatchCoachTicket, message: String, shown: Bool = true) async {
        let visit = self.visit
        var last: WatchCoachAnswer?
        for index in 0..<Self.answerReads {
            await pause()
            if index >= Self.quickReads { await pause() }
            guard visit == self.visit, !answered(message) else { return }
            let reply = await phone.ask(WatchRequest(.coachRead, made: clock(), ticket: ticket))
            guard visit == self.visit, !answered(message) else { return }
            guard let reply, reply.outcome == .ok else { continue }
            if let coach = reply.coach { last = coach }
            if reply.answering == true {
                if shown { state = .answering }
                setTurn(message, .answering)
            } else if let coach = reply.coach {
                finish(coach, message: message, shown: shown)
                return
            }
        }
        last?.more = true
        finish(last, message: message, shown: shown)
    }

    /// `shown` is false for a kept message sent at a read: it's a turn of the conversation, and never the ask on screen.
    private func finish(_ answer: WatchCoachAnswer?, message: String, shown: Bool = true) {
        // The ask may have been closed meanwhile: the conversation still gets its answer.
        if shown, inFlight { state = .answered(answer) }
        setTurn(message, .answered(answer))
        keepThread()
        // The Inbox keeps Coach's answers only: nothing of a taf's chat is kept on the watch.
        if taf == nil { onAnswer?(answer, message) }
    }

    /// A push from the phone answered this turn already: the reads stop.
    private func answered(_ id: String) -> Bool {
        guard case .answered? = turns.first(where: { $0.id == id })?.answer else { return false }
        return true
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
        WatchAccountZone.learn(reply.zone)
        // This visit's turns are shown below them: only what came before the first is history, so none shows twice.
        guard let first = turns.first?.at else { history = chat; keepThread(); return }
        history = chat.filter { $0.at < first }
        keepThread()
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

    /// View on phone (docs/watch-app.md, "View on phone"): the phone opens this chat, Coach's or the taf's, in front or with a
    /// notification. Nothing on the watch changes, and nothing is said if the phone isn't in reach: the row stays to tap again.
    func openOnPhone() async {
        _ = await phone.reach(WatchRequest(.openOnPhone, made: clock(), taf: taf))
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
        unsure = false
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
        unsure = false
        state = .deleted
    }

    // MARK: Keep for later (v2, docs/watch-app.md; Wear OS's `keepForLater`)

    /// Whether Keep for later is offered: a Send to Coach the phone didn't answer, with its words still in the review.
    var offersKeep: Bool { unsure && reviewing && taf == nil && drafts != nil && state != .sending }

    /// Keep for later: the words are kept on the watch with their id and the chat they continue, and sent at the next read
    /// that reaches the phone, under the same id, so tafs cloud starts them at most once if the first Send did arrive. One at
    /// a time: a newer one replaces it. The review closes.
    func keepForLater() {
        guard offersKeep, let sending, sending.words == words else { return }
        let message = Kept(text: sending.words, message: sending.id.uuidString.lowercased(), ticket: lastTicket,
                           mark: account?.kept)
        drafts?.write(message)
        kept = message
        draft = ""
        self.sending = nil
        reviewing = false
        unsure = false
        state = .writing
    }

    /// The message kept for later, as it may be shown: only once this run heard the phone's mark, and only its account's.
    private func readKept() -> Kept? {
        guard let message = drafts?.read() else { return nil }
        guard let account else { return message }
        return account.mayDraw && message.mark != nil && message.mark == account.kept ? message : nil
    }

    /// The phone's mark was heard and it's the kept one (`WatchAccountMark.onConfirm`): what was kept is shown now.
    func accountConfirmed() {
        if taf == nil, kept == nil { kept = readKept() }
        reopen()
    }

    /// Delete on what was kept: nothing is sent. Also on Sign out here, and when the phone says it's signed out.
    func deleteKept() {
        drafts?.clear()
        kept = nil
    }

    /// After a read of the phone (Needs you, when the app opens or Coach's home shows): a phone that answered takes what was
    /// kept; a phone that says it's signed out deletes it; no answer leaves it for the next read.
    func afterRead(_ read: NeedsYouModel.State) async {
        guard taf == nil, drafts != nil else { return }
        switch read {
        case .signedOut: deleteKept()
        case .loaded, .failed: await sendKept()
        case .loading, .unreachable: return
        }
    }

    /// Sends what was kept, from a watch with a passcode only, as Coach's Send. Taken, it's gone from the watch and is a turn
    /// of the conversation at work; anything else leaves it for the next read, never sent twice under two ids.
    func sendKept() async {
        // Words kept under another account, or before marks, go unsent once the phone's mark is heard.
        if let account, account.confirmed, let stored = drafts?.read(), stored.mark == nil || stored.mark != account.kept {
            return deleteKept()
        }
        // A file the watch couldn't read when the app opened (it was locked) is read again now.
        if kept == nil { kept = readKept() }
        guard let message = kept, !sendingKept, lock.isSecure else { return }
        // Only under the account it was written for: never before this run has heard the phone's mark, and never as someone
        // else. Under another account, or none, it goes unsent.
        if let account {
            guard account.confirmed else { return }
            guard message.mark != nil, message.mark == account.kept else { return deleteKept() }
        }
        sendingKept = true
        defer { sendingKept = false }
        let reached = await phone.reach(WatchRequest(.coachSend, made: clock(), text: message.text, message: message.message,
                                                     ticket: message.ticket))
        guard kept == message, let reply = reached.reply else { return }
        if reply.outcome == .signedOut { return deleteKept() }
        guard reply.outcome == .ok, let ticket = reply.ticket else { return }
        deleteKept()
        lastTicket = ticket
        turns.append(Turn(id: message.message, said: message.text, at: clock(), answer: .sent, ticket: ticket))
        await read(ticket, message: message.message, shown: false)
    }

    /// The app left the screen: the words and the answer go.
    func leave() {
        // What was shown stays as the thread's copy, for the next open (`WatchThreads`, when it's on).
        if !turns.isEmpty { keepThread() }
        visit += 1
        asked = nil
        turns = []
        whole = [:]
        history = []
        lastTicket = nil
        draft = ""
        sending = nil
        reviewing = false
        unsure = false
        state = .writing
    }
}

/// Where Keep for later keeps its one message: a file on the watch, or memory in a test. Text only, never audio.
@MainActor
protocol DraftStore: AnyObject {
    func read() -> CoachModel.Kept?
    func write(_ kept: CoachModel.Kept)
    func clear()
}

/// `DraftStore` in Application Support, which isn't a cache the system clears, with complete file protection, as the Inbox's
/// file: the words, their message id and the chat they continue, nothing else.
@MainActor
final class FileDraftStore: DraftStore {
    /// The name it's kept under, as Wear OS's preferences file.
    static let storeKey = "tafs.draft"
    private let url: URL

    init(url: URL = FileDraftStore.place) {
        self.url = url
    }

    static var place: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(storeKey + ".json")
    }

    /// None when there's no file, or it can't be read now (the watch is locked).
    func read() -> CoachModel.Kept? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(CoachModel.Kept.self, from: data)
    }

    func write(_ kept: CoachModel.Kept) {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(kept).write(to: url, options: [.atomic, .completeFileProtection])
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
    }
}

/// A push from the phone (`WatchPush`): the thread on the wrist moves with no read of its own.
extension CoachModel: WatchPushListening {
    func heard(_ push: WatchPush) {
        switch push.kind {
        case .ask:
            guard let ask = push.ask, let reply = push.reply, reply.outcome == .ok,
                  let turn = turns.first(where: { $0.ticket?.askID == ask }), !answered(turn.id) else { return }
            // The ask on screen is the last turn while its words are at work.
            let shown = turn.id == turns.last?.id && inFlight
            if reply.answering == true {
                if shown { state = .answering }
                setTurn(turn.id, .answering)
            } else if let coach = reply.coach {
                finish(coach, message: turn.id, shown: shown)
            }
        case .chat:
            // A taf's chat changed on the phone or in tafs cloud: read again, as when it opens.
            guard let taf, push.taf == taf else { return }
            Task { await loadHistory() }
        case .forget:
            // A conversation deleted on the phone: what this screen drew of it goes too (its kept copy went in `PhoneLink`).
            guard push.taf ?? WatchThreads.coach == threadKey else { return }
            threads.drop(threadKey)
            history = []
            if !inFlight { turns = [] }
        case .inbox, .account, .needsYou, .signIn:
            return
        }
    }
}
