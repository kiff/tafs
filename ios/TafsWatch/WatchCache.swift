import Foundation

/// So the watch opens at once (row 44, D; as Wear OS's `WatchCache`, tafs#1594): the last Your tafs it read, drawn the
/// moment the app opens, with a dot while the phone is asked, and the phone's read in its place once it answers.
///
/// **What's kept:** each taf's tafs cloud id, name, and whether it's halted or paused. **Never:** a taf's state line (it can
/// name what a held call wants), a held call's details, an amount, or any chat. Needs you isn't kept here: with a passcode
/// the Inbox already opens at once with its count, and without one nothing is kept at all.
///
/// **At rest:** one file in the app's Application Support, with complete file protection, so it can't be read while the
/// watch is locked (and a watch taken off the wrist locks). **Cleared:** on either sign-out (on the phone, or on this watch),
/// whenever the app opens or is put away on a watch with no passcode, and after 24 hours. A file that won't read is none.
///
/// Behind `on` (off): the privacy page's Apple Watch paragraph says the watch keeps nothing apart from its Inbox, so this is
/// switched on only with its sentence live there, and the owner's yes.
@MainActor
final class WatchCache {
    struct Kept: Codable, Equatable {
        struct Taf: Codable, Equatable {
            var id: String
            var name: String
            var halted: Bool
            var paused: Bool?
        }

        var at: Date
        var tafs: [Taf]
    }

    /// Off until the privacy page says so (above).
    nonisolated static let on = false
    /// After this, what's kept is old enough to mislead, and goes.
    nonisolated static let keepFor: TimeInterval = 24 * 60 * 60

    static let shared = WatchCache()

    let url: URL
    private let on: Bool
    private let lock: WristLock
    private let clock: () -> Date

    init(url: URL? = nil, on: Bool = WatchCache.on, lock: WristLock = DeviceWristLock(), clock: @escaping () -> Date = Date.init) {
        self.url = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("tafs.watch_cache")
        self.on = on
        self.lock = lock
        self.clock = clock
    }

    /// Your tafs as last read, or nil: switched off, no passcode (and then the file goes), nothing kept, older than a day
    /// (it goes), or unreadable.
    func tafs() -> [WatchTaf]? {
        guard on else { return nil }
        guard lock.isSecure else { clear(); return nil }
        guard let data = try? Data(contentsOf: url) else { return nil }
        guard let kept = try? JSONDecoder().decode(Kept.self, from: data) else { clear(); return nil }
        let age = clock().timeIntervalSince(kept.at)
        guard age >= 0, age < Self.keepFor else { clear(); return nil }
        return kept.tafs.map { WatchTaf(id: $0.id, name: $0.name, line: nil, halted: $0.halted, paused: $0.paused) }
    }

    /// The phone's read of Your tafs: each one's id, name, and halted or paused; never its line.
    func keep(_ tafs: [WatchTaf]) {
        guard on else { return }
        guard lock.isSecure else { clear(); return }
        let kept = Kept(at: clock(), tafs: tafs.map { Kept.Taf(id: $0.id, name: $0.name, halted: $0.halted, paused: $0.paused) })
        guard let data = try? JSONEncoder().encode(kept) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    /// Opened or put away: with no passcode, nothing stays.
    func checkLock() {
        if !lock.isSecure { clear() }
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
    }
}

/// A short drop keeps the last read (row 44, D; as Wear OS's 30 s): when the phone doesn't answer, what's shown stays, with
/// the dot, and only after `limit` is the phone said to be unreachable.
struct ReadGrace {
    static let limit: TimeInterval = 30

    private(set) var failingSince: Date?

    /// A read failed at `now`: true keeps what's shown, false is time to say why.
    mutating func keeps(at now: Date) -> Bool {
        let since = failingSince ?? now
        failingSince = since
        return now.timeIntervalSince(since) < Self.limit
    }

    /// How long until the grace ends, from `now`: when the page asks once more.
    func left(at now: Date) -> TimeInterval {
        guard let failingSince else { return Self.limit }
        return max(0, Self.limit - now.timeIntervalSince(failingSince))
    }

    mutating func answered() { failingSince = nil }
}
