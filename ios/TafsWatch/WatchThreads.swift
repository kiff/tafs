import Foundation

/// The owner's call 1 (docs/watch-app.md, "Kept and pushed"): Coach's thread and each taf's thread opened on the watch, kept
/// on the watch so a thread opens at once on its stored copy, then moves with the phone's pushes and reads.
///
/// **What's kept:** at most `mostThreads` threads, each its last `mostLines` messages, as text with any amount taken out (the
/// Inbox's rule, `WatchAmount.strip`, as Wear OS keeps its threads), with when each was sent and whose it is. The thread on
/// screen keeps its amounts; only the copy at rest has none. **Never:** an attachment, an image or audio, a held call's
/// details, a session or a key.
///
/// **At rest:** one file in Application Support with complete file protection, so it can't be read while the watch is locked.
/// **Cleared:** on either sign-out (on the phone or on this watch); whenever the app opens or is put away on a watch with no
/// passcode, when nothing is kept at all; and a thread not opened for `unused` goes.
///
/// Behind `on` (on): the privacy page's Apple Watch paragraph says the watch keeps the last 30 messages of up to 20 threads,
/// as text, locked, gone on sign-out, without a passcode and after 30 days unopened, and the owner said yes. Off, nothing is
/// written and nothing is read, as before that sentence went live.
@MainActor
final class WatchThreads {
    struct Thread: Codable, Equatable {
        /// "coach", or tafs cloud's id for the taf.
        var key: String
        /// When the thread was last opened or written: a thread unused for `unused` goes.
        var used: Date
        var lines: [WatchChatLine]
    }

    /// On, as the privacy page says (above), together with Wear OS's `KEEP_THREADS`.
    nonisolated static let on = true
    nonisolated static let mostThreads = 20
    nonisolated static let mostLines = 30
    /// As the chats: a thread not opened for 30 days goes.
    nonisolated static let unused: TimeInterval = 30 * 24 * 60 * 60
    /// Coach's thread's key.
    nonisolated static let coach = "coach"

    static let shared = WatchThreads()

    let url: URL
    private let on: Bool
    private let lock: WristLock
    private let clock: () -> Date

    init(url: URL? = nil, on: Bool = WatchThreads.on, lock: WristLock = DeviceWristLock(), clock: @escaping () -> Date = Date.init) {
        self.url = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("tafs.watch_threads")
        self.on = on
        self.lock = lock
        self.clock = clock
    }

    /// The thread's stored copy, oldest first, or none: switched off, no passcode (and then the file goes), nothing kept,
    /// unused too long, or unreadable.
    func lines(_ key: String) -> [WatchChatLine] {
        guard on else { return [] }
        guard lock.isSecure else { clear(); return [] }
        return read().first { $0.key == key }?.lines ?? []
    }

    /// The thread as shown now: its last `mostLines`, written over its copy. The least used go past `mostThreads`.
    func keep(_ key: String, _ lines: [WatchChatLine]) {
        guard on else { return }
        guard lock.isSecure else { clear(); return }
        var threads = read().filter { $0.key != key }
        let kept = lines.suffix(Self.mostLines).map { line -> WatchChatLine in
            var line = line
            line.text = WatchAmount.strip(line.text)
            return line
        }
        threads.append(Thread(key: key, used: clock(), lines: kept))
        threads = Array(threads.sorted { $0.used > $1.used }.prefix(Self.mostThreads))
        write(threads)
    }

    /// Opened or put away: with no passcode, nothing stays.
    func checkLock() {
        if !lock.isSecure { clear() }
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
    }

    /// What's kept and still in use; a file that won't read is none, and goes. A thread unused too long goes from the file
    /// the moment it's found, not at the next `keep`.
    private func read() -> [Thread] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        guard let threads = try? JSONDecoder().decode([Thread].self, from: data) else { clear(); return [] }
        let now = clock()
        let used = threads.filter { now.timeIntervalSince($0.used) < Self.unused }
        if used.count != threads.count {
            if used.isEmpty { clear() } else { write(used) }
        }
        return used
    }

    private func write(_ threads: [Thread]) {
        guard let data = try? JSONEncoder().encode(threads) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
    }
}
