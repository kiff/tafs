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

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init) {
        self.phone = phone
        self.clock = clock
    }

    var canSend: Bool { state != .sending && !words.isEmpty && words.count <= WatchRequest.coachLimit }
    private var words: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Sends the words the person sees, through the phone.
    func send() async {
        guard canSend else { return }
        let text = words
        state = .sending
        let reply = await phone.ask(WatchRequest(.coach, made: clock(), text: text))
        guard let reply else { state = .failed(Words.Watch.phoneNotReachable); return }
        switch reply.outcome {
        case .ok:
            draft = ""
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
        state = .writing
    }
}
