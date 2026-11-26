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

    /// The wait until the next held call's end, when it's dropped (`dropEnded`). Real time on the watch; a test steps the
    /// clock and calls `dropEnded` itself.
    private let sleep: @Sendable (TimeInterval) async -> Void
    /// The wait for the next end, while Needs you is read.
    private var endTimer: Task<Void, Never>?

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init, lock: WristLock = DeviceWristLock(),
         briefOn: Bool = NeedsYouModel.briefOnWatch,
         sleep: @escaping @Sendable (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64(max(0, $0) * 1_000_000_000)) }) {
        self.phone = phone
        self.clock = clock
        self.lock = lock
        self.briefOn = briefOn
        self.sleep = sleep
        WatchPushes.shared.listen(self)
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
            if let needsYou = reply.needsYou { state = .loaded(needsYou); dropEnded() } else { state = .failed(Words.Watch.phoneCouldNotReachCloud) }
            if briefOn, case .loaded = state { await loadBrief() }
        case .signedOut:
            state = .signedOut
            brief = nil
        case .failed:
            state = .failed(reply.line ?? Words.Watch.phoneCouldNotReachCloud)
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

    /// Approve on your phone: the phone opens Needs you at this held call, in front or with a notification, once its own fresh
    /// read still lists it (`WatchRequest.Kind.openOnPhone`). Only the hold's id goes, with the account mark; nothing is signed
    /// or answered here, and nothing on the watch changes. Not in reach, nothing is said: the button stays to tap again.
    func approveOnPhone(_ hold: String) async {
        _ = await phone.reach(WatchRequest(.openOnPhone, made: clock(), hold: hold))
    }

    /// A held call past its end on this watch's own clock leaves Needs you, once for everything that reads it (the list, the
    /// brief's Refuse, `beyondBrief`, Coach's home count), and isn't counted, as the Inbox leaves it, before the phone's next
    /// push or read. Then it waits for the next end and does it again.
    func dropEnded() {
        endTimer?.cancel()
        endTimer = nil
        guard case .loaded(let page) = state else { return }
        let now = clock()
        let shown = Self.shown(page, now: now)
        if shown != page { state = .loaded(shown) }
        guard let next = shown.calls.compactMap(\.ends).filter({ $0 > now }).min() else { return }
        let sleep = self.sleep
        endTimer = Task { [weak self] in
            await sleep(next.timeIntervalSince(now))
            guard !Task.isCancelled else { return }
            self?.dropEnded()
        }
    }

    /// Needs you as shown now: a held call past its end on this watch's own clock is left out, and out of the count, as the
    /// Inbox leaves it (`InboxModel.took`), even before the phone's next push or read.
    static func shown(_ needsYou: WatchNeedsYou, now: Date) -> WatchNeedsYou {
        var shown = needsYou
        shown.calls = needsYou.calls.filter { call in call.ends.map { $0 > now } ?? true }
        shown.count = max(0, needsYou.count - (needsYou.calls.count - shown.calls.count))
        return shown
    }

    /// The app left the screen: what was read goes.
    func leave() {
        endTimer?.cancel()
        endTimer = nil
        state = .loading
        brief = nil
        answers = [:]
    }
}

/// The phone's push of its held calls (`needs_you`): a call on screen that it no longer lists (answered on any device, or
/// lapsed) goes at once, with its Refuse; one it lists that isn't on screen is read live, amount and all, as on opening.
/// Nothing is drawn from the push itself, and nothing is read with the app put away (`leave`).
extension NeedsYouModel: WatchPushListening {
    func heard(_ push: WatchPush) {
        guard push.kind == .needsYou, let holds = push.holds, case .loaded(var page) = state, holds.at >= page.at else { return }
        let listed = Set(holds.calls.map(\.id))
        let shown = Set(page.calls.map(\.id))
        page.calls.removeAll { !listed.contains($0.id) }
        page.count = holds.count
        page.at = holds.at
        state = .loaded(page)
        dropEnded()
        if !listed.isSubset(of: shown) { Task { await load() } }
    }
}
