import Foundation

/// Your tafs on the wrist, with Halt and Halt all. In memory, like Needs you, apart from `WatchCache` (behind its switch):
/// each taf's name and whether it's halted or paused, so the page opens at once.
@MainActor
final class TafsModel: ObservableObject {
    enum State: Equatable {
        case loading
        case loaded([WatchTaf])
        case unreachable(PhoneFailure)
        case signedOut
        case failed(String)
    }

    /// Halt all's key in `halts`.
    static let all = ""

    @Published private(set) var state: State = .loading
    /// What's shown is the kept read, or the last one through a short drop, while the phone is asked: the page's dot.
    @Published private(set) var updating = false
    /// Each Halt sent from here, by tafs cloud's id for the taf; Halt all under `all`.
    @Published private(set) var halts: [String: WatchAnswer] = [:]
    let phone: PhoneAsking
    let clock: () -> Date
    /// Sending needs a passcode on this watch (`WristLock`).
    let lock: WristLock
    private var asking = false
    let cache: WatchCache
    private var grace = ReadGrace()
    private let pause: (TimeInterval) async -> Void
    /// The one more read when a short drop's grace ends.
    private var askAgain: Task<Void, Never>?
    /// Each taf's chat opened this visit (chatting with a taf), by tafs cloud's id: its turns stay while the app is open, and go
    /// with `leave`. Nothing of them is kept on the watch.
    private var chats: [String: CoachModel] = [:]

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init, lock: WristLock = DeviceWristLock(),
         cache: WatchCache = .shared,
         pause: @escaping (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }) {
        self.phone = phone
        self.clock = clock
        self.lock = lock
        self.cache = cache
        self.pause = pause
    }

    func load() async {
        guard !asking else { return }
        asking = true
        defer { asking = false }
        if case .loaded = state {
        } else if let kept = cache.tafs() {
            // Opens at once on the kept read, with the dot, while the phone is asked.
            state = .loaded(kept)
            updating = true
        } else {
            state = .loading
        }
        let reply: WatchReply
        switch await phone.reach(WatchRequest(.tafs, made: clock())) {
        case .reply(let answered): reply = answered
        case .failed(let why): unreachable(why); return
        }
        switch reply.outcome {
        case .ok:
            WatchAccountZone.learn(reply.zone)
            let tafs = reply.tafs ?? []
            state = .loaded(tafs)
            cache.keep(tafs)
            answered()
        case .signedOut:
            state = .signedOut
            halts = [:]
            cache.clear()
            answered()
        case .failed:
            state = .failed(reply.line ?? Words.Alert.tafsCouldNotReachKiff)
            answered()
        case .refused, .stale, .notYet, .answered:
            unreachable()
        }
    }

    /// The phone didn't answer: what's shown stays, with the dot, through a short drop, and the phone is asked once more as
    /// the grace ends; past it, or with nothing shown, the phone isn't in reach.
    private func unreachable(_ why: PhoneFailure = .notReachable) {
        guard case .loaded = state, grace.keeps(at: clock()) else {
            state = .unreachable(why)
            answered()
            return
        }
        updating = true
        guard askAgain == nil else { return }
        let wait = grace.left(at: clock())
        askAgain = Task { [weak self] in
            await self?.pause(wait)
            guard let self, !Task.isCancelled, self.updating else { return }
            self.askAgain = nil
            await self.load()
        }
    }

    /// An answer, whatever it says: no dot, no grace, nothing to ask again.
    private func answered() {
        updating = false
        grace.answered()
        askAgain?.cancel()
        askAgain = nil
    }

    /// Halts one taf, or with none every taf, through the phone: no Face ID, since it only
    /// takes authority away. Then reads the list again, so it shows what tafs cloud holds.
    func halt(_ taf: String?) async {
        let key = taf ?? Self.all
        guard halts[key]?.isSending != true else { return }
        // No passcode on this watch: nothing is asked of the phone.
        guard lock.isSecure else { halts[key] = .failed(Words.Watch.needsLock); return }
        halts[key] = .sending
        let request = taf.map { WatchRequest(.halt, made: clock(), taf: $0) } ?? WatchRequest(.haltAll, made: clock())
        let reached = await phone.reach(request)
        if reached.reply?.outcome == .signedOut { state = .signedOut; halts = [:]; return }
        halts[key] = WatchAnswer.from(reached)
        if reached.reply?.outcome == .ok { await load() }
    }

    /// A taf's chat: the same one each time it's opened this visit.
    func chat(_ taf: WatchTaf) -> CoachModel {
        if let open = chats[taf.id] { return open }
        let made = CoachModel(phone: phone, taf: taf.id, clock: clock, lock: lock)
        chats[taf.id] = made
        return made
    }

    func leave() {
        state = .loading
        answered()
        halts = [:]
        chats.values.forEach { $0.leave() }
        chats = [:]
    }
}
