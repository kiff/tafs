import Foundation

/// One line of v2's Inbox (docs/watch-app.md, "Step (iv)"), as Wear OS's: Coach's answer to the person's words, or a held call
/// that needs them. Kept on the watch until dismissed, with whether it was read; cleared on sign-out and whenever this watch has
/// no passcode. Read and dismissed are kept in step across the person's watches by tafs cloud, by id alone (row 21).
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
    /// Coach's words, or what the held call asks without its amount, in the phone's words: no amount is kept on the watch.
    var line: String
    /// When the watch first saw it, for the order: newest first.
    var at: Date
    var read = false

    /// The answer's or the held call's own id.
    var ref: String { String(id.drop { $0 != ":" }.dropFirst()) }

    static func answerID(_ message: String) -> String { "answer:" + message }
    static func heldID(_ hold: String) -> String { "held:" + hold }
}

/// Where the Inbox is kept: a protected file on the watch, or a list in a test. `dismissed` are the ids taken away (Dismiss, Clear
/// read) that the phone still lists, so a read doesn't bring them back; each goes once the phone no longer lists it.
@MainActor
protocol InboxStore: AnyObject {
    func lines() -> [InboxLine]
    func write(_ lines: [InboxLine])
    func dismissed() -> Set<String>
    func writeDismissed(_ ids: Set<String>)
    func clear()
}

/// `InboxStore` in its own file in this app's container on the watch (`tafs.inbox.json`: the lines, and the ids taken away),
/// written with complete file protection: unreadable whenever the watch is locked, not only before its first unlock, as a
/// held call's amount should be. The Inbox is kept only while the watch has a passcode.
@MainActor
final class FileInboxStore: InboxStore {
    private struct Kept: Codable {
        var lines: [InboxLine] = []
        var dismissed: [String] = []
    }

    private let url: URL
    /// What the file holds, once read: both halves are written from it together, so one never goes with the other.
    private var known: Kept?

    init(url: URL = FileInboxStore.place) {
        self.url = url
    }

    /// Application Support, which isn't a cache the system clears.
    static var place: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(InboxModel.storeKey + ".json")
    }

    func lines() -> [InboxLine] { read()?.lines ?? [] }

    func write(_ lines: [InboxLine]) {
        // A file there that can't be read now (the watch locked) is never written over: half of it would be lost.
        guard var kept = read() else { return }
        kept.lines = lines
        save(kept)
    }

    func dismissed() -> Set<String> { Set(read()?.dismissed ?? []) }

    func writeDismissed(_ ids: Set<String>) {
        guard var kept = read() else { return }
        kept.dismissed = ids.sorted()
        save(kept)
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
        known = Kept()
    }

    /// The file's two halves: what was read or written last, else the file; nothing kept is none. Nil when the file is there
    /// but can't be read now, as while the watch is locked.
    private func read() -> Kept? {
        if let known { return known }
        guard FileManager.default.fileExists(atPath: url.path) else {
            known = Kept()
            return known
        }
        guard let data = try? Data(contentsOf: url) else { return nil }
        known = (try? JSONDecoder().decode(Kept.self, from: data)) ?? Kept()
        return known
    }

    private func save(_ kept: Kept) {
        guard !kept.lines.isEmpty || !kept.dismissed.isEmpty else { return clear() }
        known = kept
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(kept).write(to: url, options: [.atomic, .completeFileProtection])
    }
}

/// v2's Inbox: each held call Needs you lists, at the top, then each answer Coach gave this watch, each newest first. An answer
/// is kept until dismissed; a held call until it's answered on any device or lapses, or it's dismissed. With no passcode on this
/// watch nothing is stored, and the page shows what the phone answered now (`kept` false).
@MainActor
final class InboxModel: ObservableObject {
    /// The name the Inbox is kept under, as Wear OS's preferences file.
    static let storeKey = "tafs.inbox"

    /// The Inbox: held calls first, then answers, each newest first (`ordered`).
    @Published private(set) var lines: [InboxLine] = []
    /// How much waits for the person, as the phone last said (a read or its push), held calls that lapsed left out. In memory
    /// only, never kept: nil until the phone has said, this open.
    @Published private(set) var waiting: Int?
    /// When each held call lapses, by its line's id, as the phone said: in memory only, so a line past it goes on this watch's
    /// own clock. Not kept in the file, which holds only the taf and what the call asks.
    private var ends: [String: Date] = [:]
    /// Whether the Inbox can be kept on this watch now: only with a passcode. Checked when the app is opened and put away.
    @Published private(set) var kept: Bool
    private let store: InboxStore
    private let lock: WristLock
    private let clock: () -> Date
    private var dismissed: Set<String>
    /// Row 21: the phone, which reads and sends the marks tafs cloud keeps; none in a test that doesn't use it.
    private let phone: PhoneAsking?
    /// The last mark sent, so a test can wait for it.
    private(set) var marking: Task<Void, Never>?
    /// Whether what's kept belongs to the account the phone has now (`WatchAccountMark.mayDraw`): until this run has heard
    /// so, the kept lines aren't shown, and come in once it has (`checkLock`).
    private let mayDraw: @MainActor () -> Bool

    init(store: InboxStore = FileInboxStore(), lock: WristLock = DeviceWristLock(), clock: @escaping () -> Date = Date.init,
         phone: PhoneAsking? = nil, mayDraw: @escaping @MainActor () -> Bool = { true }) {
        self.mayDraw = mayDraw
        self.store = store
        self.lock = lock
        self.clock = clock
        self.phone = phone
        kept = lock.isSecure
        dismissed = store.dismissed()
        if kept {
            if mayDraw() { lines = Self.ordered(store.lines()) }
        } else {
            store.write([])
        }
        WatchPushes.shared.listen(self)
    }

    /// How many lines aren't read yet: Coach's home says so.
    var unread: Int { lines.filter { !$0.read }.count }

    /// The Inbox's order: what needs the person on top (held calls, newest first), then Coach's answers, newest first.
    static func ordered(_ lines: [InboxLine]) -> [InboxLine] {
        lines.filter { $0.kind == .held }.sorted { $0.at > $1.at } + lines.filter { $0.kind != .held }.sorted { $0.at > $1.at }
    }

    /// The app was opened or put away: with no passcode, the lines go now, and the bare ids taken away stay, so a dismissed
    /// line doesn't come back unread once one is set. A held call past its time goes.
    func checkLock() {
        kept = lock.isSecure
        if kept { adoptStored() } else { clearLines() }
        dropEnded()
    }

    /// What the file holds that this model doesn't yet: a model made while the file couldn't be read (the watch locked)
    /// starts empty, and takes the kept lines and ids in once it can, before anything it writes could leave them out.
    private func adoptStored() {
        guard mayDraw() else { return }
        dismissed.formUnion(store.dismissed())
        let missing = store.lines().filter { stored in !lines.contains { $0.id == stored.id } }
        let next = Self.ordered((lines + missing).filter { !dismissed.contains($0.id) })
        guard next != lines else { return }
        save(next)
    }

    /// What Needs you read: taken in as the phone's push of its held calls is (`took`). Signed out on the phone, nothing of the
    /// Inbox stays.
    func heard(_ state: NeedsYouModel.State) {
        switch state {
        case .loaded(let page):
            took(WatchHolds(page))
        case .signedOut:
            clear()
        case .loading, .unreachable, .failed:
            break
        }
    }

    /// The phone's held calls, from Needs you's read or the phone's push (`WatchPush.Kind.needsYou`): each is a line at the top,
    /// with its taf and what it asks, never its amount. One the phone no longer lists (answered on any device, or lapsed), or
    /// past its time on this watch's clock, goes, and isn't counted; an id taken away goes once the phone no longer lists it.
    /// Only once this open has heard the account is the phone's (`mayDraw`): before that, the open's own read brings them.
    func took(_ holds: WatchHolds) {
        guard mayDraw() else { return }
        let now = clock()
        for call in holds.calls {
            if let end = call.ends { ends[InboxLine.heldID(call.id)] = end }
        }
        let live = holds.calls.filter { call in call.ends.map { $0 > now } ?? true }
        for call in live {
            put(InboxLine(id: InboxLine.heldID(call.id), kind: .held, title: call.taf, line: call.line ?? Words.NeedsYou.title, at: now))
        }
        let listed = Set(live.map { InboxLine.heldID($0.id) })
        let next = lines.filter { $0.kind != .held || listed.contains($0.id) }
        if next != lines { save(next) }
        ends = ends.filter { listed.contains($0.key) }
        prune(listed: Set(holds.calls.map { InboxLine.heldID($0.id) }))
        waiting = max(0, holds.count - (holds.calls.count - live.count))
    }

    /// Held calls past their time, on this watch's clock: gone from the lines and the count.
    private func dropEnded() {
        let now = clock()
        let ended = Set(ends.filter { $0.value <= now }.keys)
        guard !ended.isEmpty else { return }
        ends = ends.filter { !ended.contains($0.key) }
        let next = lines.filter { !ended.contains($0.id) }
        if let waiting { self.waiting = max(0, waiting - (lines.count - next.count)) }
        if next != lines { save(next) }
    }

    /// A held call's line as the Inbox keeps it: what it asks, never its amount (as Wear OS). A phone from
    /// before `summary` names none, and the line is Needs you's own title.
    static func line(_ call: WatchNeedsYou.Call) -> String {
        call.summary.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? Words.NeedsYou.title
    }

    /// Coach answered words sent from this watch: a line, read already, since the person saw it as it came. It's kept with
    /// no amount in it (`WatchAmount.strip`), as Wear OS keeps its asks' lines: the reply on screen keeps its amounts, in
    /// memory only.
    func answered(_ answer: WatchCoachAnswer?, message: String) {
        let kept = answer.map { WatchAmount.strip($0.text) } ?? ""
        let words = kept.isEmpty ? Words.Watch.moreOnPhone : kept
        put(InboxLine(id: InboxLine.answerID(message), kind: .answer, title: nil, line: words, at: clock(), read: true))
    }

    /// Opened: read.
    func markRead(_ id: String) {
        guard let line = lines.first(where: { $0.id == id && !$0.read }) else { return }
        save(lines.map { line in
            var line = line
            if line.id == id { line.read = true }
            return line
        })
        // Coach's answer comes in read: only a held call's line is news to the person's other watches.
        if line.kind == .held { mark(WatchInboxMarks(read: [line.ref])) }
    }

    /// Dismiss: gone from the Inbox, and it stays gone while the phone still lists it.
    func dismiss(_ id: String) {
        guard let line = lines.first(where: { $0.id == id }) else { return }
        takeAway([id])
        save(lines.filter { $0.id != id })
        mark(WatchInboxMarks(dismissed: [line.ref]))
    }

    /// Clear read: every read line goes, and stays gone while the phone still lists it.
    func clearRead() {
        let read = lines.filter(\.read)
        guard !read.isEmpty else { return }
        takeAway(read.map(\.id))
        save(lines.filter { !$0.read })
        mark(WatchInboxMarks(dismissed: read.map(\.ref)))
    }

    // MARK: In step across the person's watches (row 21, docs/watch-app.md)

    /// Reads the marks tafs cloud keeps, through the phone, and takes them in: when the app opens (after Needs you) and when
    /// the Inbox shows. With no passcode nothing is kept, so nothing is read. A phone from before the marks, or no answer,
    /// leaves the lines as this watch has them.
    func sync() async {
        guard kept, let phone else { return }
        let reply = await phone.ask(WatchRequest(.inboxMarks, made: clock()))
        guard kept, let reply, reply.outcome == .ok, let marks = reply.marks else { return }
        take(marks)
    }

    /// The marks from tafs cloud: a line dismissed on any watch goes, and a held call's stays away while the phone lists it;
    /// a line read on any watch is read here too. Nothing is ever brought back.
    func take(_ marks: WatchInboxMarks) {
        guard kept else { return }
        let gone = Set(marks.dismissed)
        let read = Set(marks.read)
        // Each dismissed id as a held call's, so a call the phone lists later doesn't come in; one the phone doesn't list
        // goes at the next read (`prune`), so the list never grows.
        let away = dismissed.union(gone.map(InboxLine.heldID))
        if away != dismissed {
            dismissed = away
            store.writeDismissed(dismissed)
        }
        let next = lines.compactMap { line -> InboxLine? in
            guard !gone.contains(line.ref) else { return nil }
            var line = line
            if read.contains(line.ref) { line.read = true }
            return line
        }
        if next != lines { save(next) }
    }

    /// Sends a mark through the phone, in chunks tafs cloud takes. One that doesn't arrive changes nothing on this watch, which
    /// keeps its own marks as before.
    private func mark(_ marks: WatchInboxMarks) {
        guard kept, let phone else { return }
        let checked = marks.checked
        let chunks = stride(from: 0, to: checked.read.count, by: WatchInboxMarks.limit).map {
            WatchInboxMarks(read: Array(checked.read[$0..<min($0 + WatchInboxMarks.limit, checked.read.count)]))
        } + stride(from: 0, to: checked.dismissed.count, by: WatchInboxMarks.limit).map {
            WatchInboxMarks(dismissed: Array(checked.dismissed[$0..<min($0 + WatchInboxMarks.limit, checked.dismissed.count)]))
        }
        guard !chunks.isEmpty else { return }
        let clock = self.clock
        let before = marking
        marking = Task {
            await before?.value
            for chunk in chunks {
                _ = await phone.ask(WatchRequest(.inboxMark, made: clock(), marks: chunk))
            }
        }
    }

    /// Signed out: nothing of the Inbox stays on the watch, the ids taken away included.
    func clear() {
        store.clear()
        dismissed = []
        lines = []
        ends = [:]
        waiting = nil
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
            save(Self.ordered([line] + lines))
        }
    }

    /// Only a held call's id is kept once taken away: the phone may list the call again. Coach's answer is put once, by its
    /// message's id, so its id is never needed again.
    private func takeAway(_ ids: [String]) {
        let held = ids.filter { $0.hasPrefix(InboxLine.heldID("")) }
        guard !held.isEmpty else { return }
        dismissed.formUnion(held)
        store.writeDismissed(dismissed)
    }

    /// A held call's id taken away goes once the phone no longer lists it, so the list of them never grows.
    private func prune(listed: Set<String>) {
        let still = dismissed.filter { listed.contains($0) }
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

/// The phone's pushes, taken as a read's, with no ask: the Inbox's marks (`watch_inbox`), and its held calls (`needs_you`).
extension InboxModel: WatchPushListening {
    func heard(_ push: WatchPush) {
        switch push.kind {
        case .inbox:
            guard let reply = push.reply, reply.outcome == .ok, let marks = reply.marks else { return }
            take(marks)
        case .needsYou:
            guard let holds = push.holds else { return }
            took(holds)
        case .ask, .chat, .account, .forget:
            return
        }
    }
}
