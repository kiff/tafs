import Foundation

/// Talking to Coach from the wrist (docs/watch-app.md, "Coach"): the person's words, from the
/// watch's own dictation or Scribble, shown before they're sent; Coach's answer, short. Only
/// text goes to the phone, never audio. Kept in memory only, like Needs you.
@MainActor
final class CoachModel: ObservableObject {
    enum State: Equatable {
        case writing
        case sending
        /// Coach's answer, or none yet: the rest is on the phone.
        case answered(WatchCoachAnswer?)
        case failed(String)
        /// v2: Delete in Check before sending: the words are gone, and nothing was sent.
        case deleted
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

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init, lock: WristLock = DeviceWristLock(),
         newID: @escaping () -> UUID = UUID.init) {
        self.phone = phone
        self.clock = clock
        self.lock = lock
        self.newID = newID
    }

    var canSend: Bool { state != .sending && !words.isEmpty && words.count <= WatchRequest.coachLimit }
    private var words: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Sends the words the person sees, through the phone.
    func send() async {
        guard canSend else { return }
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
        guard state != .sending else { return }
        let text = String(words.trimmingCharacters(in: .whitespacesAndNewlines).prefix(WatchRequest.coachLimit))
        guard !text.isEmpty else { return }
        draft = text
        reviewing = true
        state = .writing
    }

    /// v2's Send, from Check before sending: v1's send of the words shown. They leave the review only once the phone
    /// has them; a failure keeps them there, to send again under the same id.
    func sendReviewed() async {
        guard reviewing else { return }
        await send()
        if case .answered = state { reviewing = false }
    }

    /// v2's Delete, in Check before sending: the words go, and nothing was sent. Not while they're in flight, since then
    /// they may already have reached Coach.
    func delete() {
        guard state != .sending else { return }
        draft = ""
        sending = nil
        reviewing = false
        state = .deleted
    }

    /// The app left the screen: the words and the answer go.
    func leave() {
        draft = ""
        sending = nil
        reviewing = false
        state = .writing
    }
}
