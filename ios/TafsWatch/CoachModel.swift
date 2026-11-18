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
    }

    @Published var draft = ""
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
        case .signedOut:
            state = .failed(Words.Watch.signInOnPhone)
        case .failed:
            state = .failed(reply.line ?? Words.Alert.notSent)
        case .refused, .stale, .notYet, .answered:
            state = .failed(Words.Watch.phoneNotReachable)
        }
    }

    /// The app left the screen: the words and the answer go.
    func leave() {
        draft = ""
        sending = nil
        state = .writing
    }
}
