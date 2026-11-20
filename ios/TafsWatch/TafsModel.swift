import Foundation

/// Your tafs on the wrist, with Halt and Halt all. Kept in memory only, like Needs you.
@MainActor
final class TafsModel: ObservableObject {
    enum State: Equatable {
        case loading
        case loaded([WatchTaf])
        case unreachable
        case signedOut
        case failed(String)
    }

    /// Halt all's key in `halts`.
    static let all = ""

    @Published private(set) var state: State = .loading
    /// Each Halt sent from here, by tafs cloud's id for the taf; Halt all under `all`.
    @Published private(set) var halts: [String: WatchAnswer] = [:]
    let phone: PhoneAsking
    let clock: () -> Date
    /// Sending needs a passcode on this watch (`WristLock`).
    let lock: WristLock
    private var asking = false
    /// Each taf's chat opened this visit (chatting with a taf), by tafs cloud's id: its turns stay while the app is open, and go
    /// with `leave`. Nothing of them is kept on the watch.
    private var chats: [String: CoachModel] = [:]

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init, lock: WristLock = DeviceWristLock()) {
        self.phone = phone
        self.clock = clock
        self.lock = lock
    }

    func load() async {
        guard !asking else { return }
        asking = true
        defer { asking = false }
        if case .loaded = state {} else { state = .loading }
        guard let reply = await phone.ask(WatchRequest(.tafs, made: clock())) else {
            state = .unreachable
            return
        }
        switch reply.outcome {
        case .ok: state = .loaded(reply.tafs ?? [])
        case .signedOut: state = .signedOut; halts = [:]
        case .failed: state = .failed(reply.line ?? Words.Alert.tafsCouldNotReachKiff)
        case .refused, .stale, .notYet, .answered: state = .unreachable
        }
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
        let reply = await phone.ask(request)
        if reply?.outcome == .signedOut { state = .signedOut; halts = [:]; return }
        halts[key] = WatchAnswer.from(reply)
        if reply?.outcome == .ok { await load() }
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
        halts = [:]
        chats.values.forEach { $0.leave() }
        chats = [:]
    }
}
