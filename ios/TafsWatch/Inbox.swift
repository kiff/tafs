import Foundation

/// One line of v2's Inbox (docs/watch-app.md, "Step (iv)"), as Wear OS's: Coach's answer to the person's words, or a held call
/// that needs them. Kept on the watch until dismissed, with whether it was read; cleared on sign-out and whenever this watch has
/// no passcode. Read and dismissed stay on this watch until tafs cloud keeps them in step with the phone (server piece c).
struct InboxLine: Codable, Equatable, Identifiable, Sendable {
    enum Kind: String, Codable, Sendable {
        /// Coach's answer to words sent from this watch.
        case answer
        /// A held call Needs you listed.
        case held
    }

    /// `answer:<message id>` or `held:<hold id>`: one line per answer or held call, never two.
    var id: String
    var kind: Kind
    /// The taf, as the phone named it; none for Coach's answer.
    var title: String?
    /// Coach's words, or what the held call asks with its amount, in the phone's words.
    var line: String
    /// When the watch first saw it, for the order: newest first.
    var at: Date
    var read = false

    /// The answer's or the held call's own id.
    var ref: String { String(id.drop { $0 != ":" }.dropFirst()) }

    static func answerID(_ message: String) -> String { "answer:" + message }
    static func heldID(_ hold: String) -> String { "held:" + hold }
}

/// Where the Inbox is kept: the watch's own defaults, or a list in a test. `dismissed` are the ids taken away (Dismiss, Clear
/// read) that the phone still lists, so a read doesn't bring them back; each goes once the phone no longer lists it.
@MainActor
protocol InboxStore: AnyObject {
    func lines() -> [InboxLine]
    func write(_ lines: [InboxLine])
    func dismissed() -> Set<String>
    func writeDismissed(_ ids: Set<String>)
    func clear()
}

/// `InboxStore` in this app's own defaults on the watch, as one JSON list, and the ids taken away beside it. watchOS keeps them
/// encrypted with the watch's passcode, and the Inbox is kept only while there is one.
@MainActor
final class DefaultsInboxStore: InboxStore {
    private let defaults: UserDefaults
    private let linesKey: String
    private let dismissedKey: String

    init(defaults: UserDefaults = .standard, key: String = InboxModel.storeKey) {
        self.defaults = defaults
        linesKey = key
        dismissedKey = key + InboxModel.dismissedSuffix
    }

    func lines() -> [InboxLine] {
        guard let data = defaults.data(forKey: linesKey) else { return [] }
        return (try? JSONDecoder().decode([InboxLine].self, from: data)) ?? []
    }

    func write(_ lines: [InboxLine]) {
        defaults.set(try? JSONEncoder().encode(lines), forKey: linesKey)
    }

    func dismissed() -> Set<String> {
        Set(defaults.stringArray(forKey: dismissedKey) ?? [])
    }

    func writeDismissed(_ ids: Set<String>) {
        defaults.set(ids.sorted(), forKey: dismissedKey)
    }

    func clear() {
        defaults.removeObject(forKey: linesKey)
        defaults.removeObject(forKey: dismissedKey)
    }
}

/// v2's Inbox: each held call Needs you lists and each answer Coach gave this watch, newest first, kept until dismissed. With no
/// passcode on this watch nothing is stored, and the page shows what the phone answered now (`kept` false).
@MainActor
final class InboxModel: ObservableObject {
    /// The defaults key the lines are kept under, as Wear OS's preferences file.
    static let storeKey = "tafs.inbox"
    static let dismissedSuffix = ".dismissed"

    /// The Inbox, newest first.
    @Published private(set) var lines: [InboxLine] = []
    /// Whether the Inbox can be kept on this watch now: only with a passcode. Checked when the app is opened and put away.
    @Published private(set) var kept: Bool
    private let store: InboxStore
    private let lock: WristLock
    private let clock: () -> Date
    private var dismissed: Set<String>

    init(store: InboxStore = DefaultsInboxStore(), lock: WristLock = DeviceWristLock(), clock: @escaping () -> Date = Date.init) {
        self.store = store
        self.lock = lock
        self.clock = clock
        kept = lock.isSecure
        dismissed = store.dismissed()
        if kept {
            lines = store.lines().sorted { $0.at > $1.at }
        } else {
            store.write([])
        }
    }

    /// How many lines aren't read yet: Coach's home says so.
    var unread: Int { lines.filter { !$0.read }.count }

    /// The app was opened or put away: with no passcode, the lines go now, and the bare ids taken away stay, so a dismissed
    /// line doesn't come back unread once one is set.
    func checkLock() {
        kept = lock.isSecure
        if !kept { clearLines() }
    }

    /// What Needs you read: each held call is a line, kept until dismissed; an id taken away goes once the phone no longer lists
    /// it. Signed out on the phone, nothing of the Inbox stays.
    func heard(_ state: NeedsYouModel.State) {
        switch state {
        case .loaded(let page):
            for call in page.calls {
                put(InboxLine(id: InboxLine.heldID(call.id), kind: .held, title: call.taf, line: call.what, at: clock()))
            }
            prune(kind: .held, listed: Set(page.calls.map { InboxLine.heldID($0.id) }))
        case .signedOut:
            clear()
        case .loading, .unreachable, .failed:
            break
        }
    }

    /// Coach answered words sent from this watch: a line, read already, since the person saw it as it came.
    func answered(_ answer: WatchCoachAnswer?, message: String) {
        let words = answer.map(\.text).flatMap { $0.isEmpty ? nil : $0 } ?? Words.Watch.moreOnPhone
        put(InboxLine(id: InboxLine.answerID(message), kind: .answer, title: nil, line: words, at: clock(), read: true))
    }

    /// Opened: read.
    func markRead(_ id: String) {
        guard lines.contains(where: { $0.id == id && !$0.read }) else { return }
        save(lines.map { line in
            var line = line
            if line.id == id { line.read = true }
            return line
        })
    }

    /// Dismiss: gone from the Inbox, and it stays gone while the phone still lists it.
    func dismiss(_ id: String) {
        guard lines.contains(where: { $0.id == id }) else { return }
        takeAway([id])
        save(lines.filter { $0.id != id })
    }

    /// Clear read: every read line goes, and stays gone while the phone still lists it.
    func clearRead() {
        let read = lines.filter(\.read).map(\.id)
        guard !read.isEmpty else { return }
        takeAway(read)
        save(lines.filter { !$0.read })
    }

    /// Signed out: nothing of the Inbox stays on the watch, the ids taken away included.
    func clear() {
        store.clear()
        dismissed = []
        lines = []
    }

    private func put(_ line: InboxLine) {
        // No passcode: nothing is added, and nothing is stored.
        guard kept, !dismissed.contains(line.id) else { return }
        if let at = lines.firstIndex(where: { $0.id == line.id }) {
            var next = lines
            next[at].title = line.title
            next[at].line = line.line
            save(next)
        } else {
            save([line] + lines)
        }
    }

    private func takeAway(_ ids: [String]) {
        dismissed.formUnion(ids)
        store.writeDismissed(dismissed)
    }

    /// An id taken away goes once the phone no longer lists it, so the list of them never grows.
    private func prune(kind: InboxLine.Kind, listed: Set<String>) {
        let prefix = kind == .held ? InboxLine.heldID("") : InboxLine.answerID("")
        let still = dismissed.filter { !$0.hasPrefix(prefix) || listed.contains($0) }
        guard still != dismissed else { return }
        dismissed = still
        store.writeDismissed(dismissed)
    }

    private func clearLines() {
        store.write([])
        lines = []
    }

    private func save(_ next: [InboxLine]) {
        lines = next
        store.write(next)
    }
}
