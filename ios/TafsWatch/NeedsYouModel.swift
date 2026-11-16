import Foundation

/// Needs you on the wrist, as the phone reads it. Kept in memory only, and dropped when the app
/// leaves the screen (docs/watch-app.md, "What the watch keeps").
@MainActor
final class NeedsYouModel: ObservableObject {
    enum State: Equatable {
        /// Asking the phone; nothing shown yet, never "nothing is waiting".
        case loading
        case loaded(WatchNeedsYou)
        /// The phone isn't in reach, or didn't answer.
        case unreachable
        /// The phone isn't signed in: nothing of the account is shown.
        case signedOut
        /// The phone answered that it couldn't read it, in its words.
        case failed(String)
    }

    @Published private(set) var state: State = .loading
    private let phone: PhoneAsking
    private let clock: () -> Date

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init) {
        self.phone = phone
        self.clock = clock
    }

    private var asking = false

    /// Reads Needs you from the phone, once at a time.
    func load() async {
        guard !asking else { return }
        asking = true
        defer { asking = false }
        if case .loaded = state {} else { state = .loading }
        guard let reply = await phone.ask(WatchRequest(.needsYou, made: clock())) else {
            state = .unreachable
            return
        }
        switch reply.outcome {
        case .ok:
            if let needsYou = reply.needsYou { state = .loaded(needsYou) } else { state = .failed(Words.Alert.tafsCouldNotReachKiff) }
        case .signedOut:
            state = .signedOut
        case .failed:
            state = .failed(reply.line ?? Words.Alert.tafsCouldNotReachKiff)
        case .refused, .stale, .notYet:
            // The phone dropped it, or is an older version: ask again from the screen.
            state = .unreachable
        }
    }

    /// The app left the screen: what was read goes.
    func leave() {
        state = .loading
    }
}
