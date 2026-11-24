import Foundation

/// Needs you on the wrist, as the phone reads it. Kept in memory only, and dropped when the app
/// leaves the screen (docs/watch-app.md, "What the watch keeps").
@MainActor
final class NeedsYouModel: ObservableObject {
    enum State: Equatable {
        /// Asking the phone; nothing shown yet, never "nothing is waiting".
        case loading
        case loaded(WatchNeedsYou)
        /// The phone isn't in reach, or didn't answer: why, in its own words.
        case unreachable(PhoneFailure)
        /// The phone isn't signed in: nothing of the account is shown.
        case signedOut
        /// The phone answered that it couldn't read it, in its words.
        case failed(String)
    }

    /// Your brief on the wrist (S2), built and switched off: off, the watch asks and draws exactly what it did
    /// before. On, it reads the brief after Needs you and draws it in Needs you's place (docs/watch-app.md).
    nonisolated static let briefOnWatch = false

    @Published private(set) var state: State = .loading
    /// Your brief, when the switch is on and the phone has one; nil shows Needs you.
    @Published private(set) var brief: WatchBrief?
    private let briefOn: Bool
    /// Each Refuse sent from here, by KIFF's id for the held call.
    @Published private(set) var answers: [String: WatchAnswer] = [:]
    private let phone: PhoneAsking
    private let clock: () -> Date
    /// Sending needs a passcode on this watch (`WristLock`).
    private let lock: WristLock

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init, lock: WristLock = DeviceWristLock(),
         briefOn: Bool = NeedsYouModel.briefOnWatch) {
        self.phone = phone
        self.clock = clock
        self.lock = lock
        self.briefOn = briefOn
    }

    private var asking = false

    /// Reads Needs you from the phone, once at a time.
    func load() async {
        guard !asking else { return }
        asking = true
        defer { asking = false }
        if case .loaded = state {} else { state = .loading }
        let reply: WatchReply
        switch await phone.reach(WatchRequest(.needsYou, made: clock())) {
        case .reply(let answered): reply = answered
        case .failed(let why): state = .unreachable(why); return
        }
        switch reply.outcome {
        case .ok:
            if let needsYou = reply.needsYou { state = .loaded(needsYou) } else { state = .failed(Words.Alert.tafsCouldNotReachKiff) }
            if briefOn, case .loaded = state { await loadBrief() }
        case .signedOut:
            state = .signedOut
            brief = nil
        case .failed:
            state = .failed(reply.line ?? Words.Alert.tafsCouldNotReachKiff)
        case .refused, .stale, .notYet, .answered:
            // The phone dropped it, is an older version, or answered as if to a Refuse: ask again
            // from the screen.
            state = .unreachable(.notReachable)
        }
    }

    /// Your brief, after Needs you. Anything but the phone's brief (none, an older phone, no answer) leaves
    /// Needs you as it is: the brief never hides what waits.
    private func loadBrief() async {
        let reply = await phone.ask(WatchRequest(.brief, made: clock()))
        if reply?.outcome == .signedOut { state = .signedOut; brief = nil; answers = [:]; return }
        brief = reply?.outcome == .ok ? reply?.brief : nil
    }

    /// Refuse is offered on a decision only where Needs you's own read lists that held call: the same
    /// request, behind the same passcode.
    func refusable(_ decision: WatchBrief.Decision) -> Bool {
        guard let hold = decision.holdID, case .loaded(let needsYou) = state else { return false }
        return needsYou.calls.contains { $0.id == hold }
    }

    /// What Needs you holds beyond the brief's decisions, so nothing waiting is out of reach. Matched item by item,
    /// never by number, as the phone's `needsYouBeyondBrief`: each held call the brief names no decision for, by
    /// KIFF's id, plus what else Needs you counts (steps and the rest) less the brief's steps. A held call the brief
    /// names but Needs you no longer lists (answered between the two reads) never hides one it does.
    var beyondBrief: Int {
        guard let brief, case .loaded(let needsYou) = state else { return 0 }
        let named = Set(brief.decisions.compactMap(\.holdID))
        let calls = needsYou.calls.filter { !named.contains($0.id) }.count
        let steps = brief.decisions.filter { $0.refKind == "step" }.count
        return calls + max(0, needsYou.count - needsYou.calls.count - steps)
    }

    /// Refuses a held call through the phone, which signs it as from its notification. Once
    /// at a time per call; the result is the phone's.
    func refuse(_ hold: String) async {
        guard answers[hold]?.isSending != true else { return }
        // No passcode on this watch: nothing is asked of the phone.
        guard lock.isSecure else { answers[hold] = .failed(Words.Watch.needsLock); return }
        answers[hold] = .sending
        let reached = await phone.reach(WatchRequest(.refuse, made: clock(), hold: hold))
        if reached.reply?.outcome == .signedOut { state = .signedOut; answers = [:]; return }
        // A refusal goes to KIFF: a failure the phone gives no words for says so.
        answers[hold] = WatchAnswer.from(reached, notDone: Words.Alert.notRefused, unreached: Words.Alert.tafsCouldNotReachKiff)
    }

    /// The app left the screen: what was read goes.
    func leave() {
        state = .loading
        brief = nil
        answers = [:]
    }
}
