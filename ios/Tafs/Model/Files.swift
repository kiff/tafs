import Foundation

/// Files (docs/files.md): what the account's tafs delivered in their runs, kept 30 days from the run and seen by everyone
/// on the account. Built behind this switch until tafs cloud's store and reads land; until then the screens read
/// `StubFilesStore`, and nothing of it shows.
enum FilesFeature {
    static let on = false
    /// How long a file is kept from its run (the owner's pick, 1a): tafs cloud's rule, shown here, never applied here.
    static let keptDays = 30
}

/// A file a taf delivered in a run, as tafs cloud lists it.
struct DeliveredFile: Identifiable, Hashable, Codable, Sendable {
    var id: String
    var name: String
    /// Its type, as its extension says it, upper case: "CSV", "PDF".
    var type: String
    var bytes: Int
    /// The taf that made it: tafs cloud's id and its name.
    var tafID: String
    var tafName: String
    /// The run it came from, and when that run was.
    var runID: String
    var runAt: Date
    /// Where else the run sent it, in tafs cloud's words ("Google Drive · Support"); nil when nowhere else.
    var sentTo: String?
    /// When it goes away, as tafs cloud says: the app counts the days to it and never decides it.
    var goesAt: Date
}

/// Where the files come from: tafs cloud, once its reads land; `StubFilesStore` until then.
protocol FilesStore: Sendable {
    /// Every file the account's tafs delivered and still keep.
    func files() async throws -> [DeliveredFile]
    /// A file's bytes, for its preview, Download and Share (the file itself, never a link: the owner's pick, 2a).
    func contents(of id: String) async throws -> Data
    /// Delete now, for everyone on the account.
    func delete(_ id: String) async throws
}

/// How the lists are drawn: in tafs cloud's facts, grouped and ordered, nothing else worked out.
enum FilesGrouping {
    struct Group: Identifiable, Equatable {
        /// The day (`YYYY-MM-DD`), the taf's id or the run's id.
        var id: String
        var files: [DeliveredFile]
    }

    /// Newest first, under the day of their run in the account's zone.
    static func byDay(_ files: [DeliveredFile], zone: TimeZone) -> [Group] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        func day(_ date: Date) -> String {
            let c = calendar.dateComponents([.year, .month, .day], from: date)
            return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        }
        return grouped(newestFirst(files), by: { day($0.runAt) })
    }

    /// Each taf with its files, the taf with the newest run first.
    static func byTaf(_ files: [DeliveredFile]) -> [Group] {
        grouped(newestFirst(files), by: \.tafID)
    }

    /// Each run with its files, the newest run first.
    static func byRun(_ files: [DeliveredFile]) -> [Group] {
        grouped(newestFirst(files), by: \.runID)
    }

    /// Whole days left before it goes, never below none: 0 is today.
    static func daysLeft(_ file: DeliveredFile, now: Date) -> Int {
        max(0, Int((file.goesAt.timeIntervalSince(now) / 86_400).rounded(.down)))
    }

    /// How many go within the week.
    static func goingThisWeek(_ files: [DeliveredFile], now: Date) -> Int {
        files.filter { daysLeft($0, now: now) <= 7 }.count
    }

    private static func newestFirst(_ files: [DeliveredFile]) -> [DeliveredFile] {
        // Stable for files of one run: the name breaks the tie, so the order never moves between reads.
        files.sorted { $0.runAt != $1.runAt ? $0.runAt > $1.runAt : $0.name < $1.name }
    }

    /// Groups in the order their first file comes, each keeping its files' order.
    private static func grouped(_ files: [DeliveredFile], by key: (DeliveredFile) -> String) -> [Group] {
        var order: [String] = []
        var groups: [String: [DeliveredFile]] = [:]
        for file in files {
            let k = key(file)
            if groups[k] == nil { order.append(k) }
            groups[k, default: []].append(file)
        }
        return order.map { Group(id: $0, files: groups[$0] ?? []) }
    }
}

/// The account's files on screen: read, and Delete now for everyone.
@MainActor
final class FilesModel: ObservableObject {
    enum State: Equatable {
        case loading
        case loaded([DeliveredFile])
        case failed
    }

    @Published private(set) var state: State = .loading
    let store: FilesStore

    init(store: FilesStore) {
        self.store = store
    }

    /// The files, newest first; a failed read says so, and keeps nothing it had.
    func load() async {
        do {
            state = .loaded(try await store.files().sorted { $0.runAt > $1.runAt })
        } catch {
            state = .failed
        }
    }

    /// The files of one taf, or of one run, from what was read.
    func files(taf: String? = nil, run: String? = nil) -> [DeliveredFile] {
        guard case .loaded(let all) = state else { return [] }
        return all.filter { (taf == nil || $0.tafID == taf) && (run == nil || $0.runID == run) }
    }

    /// Delete now: gone from the list only once the store has deleted it. False when it couldn't be.
    func delete(_ id: String) async -> Bool {
        do {
            try await store.delete(id)
        } catch {
            return false
        }
        if case .loaded(let all) = state { state = .loaded(all.filter { $0.id != id }) }
        return true
    }
}

/// The files until tafs cloud's store lands: the approved mockup's samples, none of them about money.
struct StubFilesStore: FilesStore {
    let now: Date

    init(now: Date = Date()) {
        self.now = now
    }

    func files() async throws -> [DeliveredFile] {
        func file(_ id: String, _ name: String, _ type: String, _ kb: Int, taf: (String, String), run: String, daysAgo: Double,
                  sentTo: String? = nil) -> DeliveredFile {
            let at = now.addingTimeInterval(-daysAgo * 86_400)
            return DeliveredFile(id: id, name: name, type: type, bytes: kb * 1_000, tafID: taf.0, tafName: taf.1, runID: run,
                                 runAt: at, sentTo: sentTo, goesAt: at.addingTimeInterval(Double(FilesFeature.keptDays) * 86_400))
        }
        let support = ("depl_support", "Support replies"), parcels = ("depl_parcels", "Late parcels"),
            leads = ("depl_leads", "Lead follow-up"), access = ("depl_access", "Access")
        return [
            file("f1", "Tickets answered.csv", "CSV", 14, taf: support, run: "r1", daysAgo: 0.1, sentTo: "Google Drive · Support"),
            file("f2", "Late parcels.pdf", "PDF", 86, taf: parcels, run: "r2", daysAgo: 0.2),
            file("f3", "Parcels without a scan.csv", "CSV", 6, taf: parcels, run: "r2", daysAgo: 0.2),
            file("f4", "New leads this week.xlsx", "XLSX", 22, taf: leads, run: "r3", daysAgo: 1.2, sentTo: "Email"),
            file("f5", "Call notes.docx", "DOCX", 31, taf: leads, run: "r3", daysAgo: 1.2),
            file("f7", "Access review.csv", "CSV", 9, taf: access, run: "r5", daysAgo: 26),
        ]
    }

    func contents(of id: String) async throws -> Data {
        Data("Item,Done\n#7781,09:04\n#7783,09:06\n".utf8)
    }

    func delete(_ id: String) async throws {}
}
