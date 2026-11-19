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
    /// The chat the run answered in (its session), for the files under that chat; nil for a run with no chat.
    var chat: String? = nil
    /// When it goes away, as tafs cloud says: the app counts the days to it and never decides it.
    var goesAt: Date
}

/// Where the files come from: tafs cloud (`CloudFilesStore`), or `StubFilesStore` while Files is off. On the main
/// actor, as the app's tafs cloud client is.
@MainActor
protocol FilesStore {
    /// Every file the account's tafs delivered and still keep.
    func files() async throws -> [DeliveredFile]
    /// A file's start as plain text, as tafs cloud cleaned it (control and direction characters gone), drawn as text and
    /// never as Markdown or HTML; nil for a type that has no text start.
    func preview(of id: String) async throws -> FilePreviewText?
    /// A file's bytes, for Download and Share only (the file itself, never a link: the owner's pick, 2a).
    func contents(of id: String) async throws -> Data
    /// Delete now, for everyone on the account.
    func delete(_ id: String) async throws
}

/// A file's start as tafs cloud sends it: its first 4 KB as plain text, and whether there's more.
struct FilePreviewText: Equatable, Sendable {
    var text: String
    var truncated: Bool
}

/// A file's name and its preview as the app draws them (qa-kiff's musts, as Android's `FilesModel`): plain text only, never
/// a path, and no control or direction character, even if tafs cloud's own cleaning ever let one through.
enum FileText {
    /// U+202A–U+202E (embeddings and overrides), U+2066–U+2069 (isolates), and the marks U+200E, U+200F and U+061C.
    static func isDirection(_ s: Unicode.Scalar) -> Bool {
        (0x202A...0x202E).contains(s.value) || (0x2066...0x2069).contains(s.value) || [0x200E, 0x200F, 0x061C].contains(s.value)
    }

    static func isControl(_ s: Unicode.Scalar) -> Bool {
        s.properties.generalCategory == .control
    }

    /// The name alone: what follows the last `/` or `\`, with control and direction characters taken out; `fallback` if
    /// nothing's left, or only "." or "..".
    static func name(_ raw: String, fallback: String) -> String {
        let last = raw.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        var kept = String.UnicodeScalarView()
        kept.append(contentsOf: last.unicodeScalars.filter { !isDirection($0) && !isControl($0) })
        let clean = String(kept).trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty || clean == "." || clean == ".." ? fallback : clean
    }

    /// A preview's text: lines and tabs kept, every other control or direction character taken out, Windows' line ends
    /// made one.
    static func preview(_ raw: String) -> String {
        let lines = raw.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var kept = String.UnicodeScalarView()
        kept.append(contentsOf: lines.unicodeScalars.filter { $0 == "\n" || $0 == "\t" || (!isDirection($0) && !isControl($0)) })
        return String(kept)
    }
}

/// The copy of a file this device holds while it's open: in its own folder under the app's temporary directory, named
/// as the file is, and gone when the file's screen closes. A file is never kept on the device past its screen, so
/// Delete now and the 30 days hold here too.
enum FileCopy {
    /// Every copy's folder: cleared with the opened files on sign-out and an account switch (`AppModel.removeOpenedFiles`).
    static var root: URL { FileManager.default.temporaryDirectory.appendingPathComponent("files", isDirectory: true) }

    static func folder(_ id: String) -> URL {
        root.appendingPathComponent(safeName(id, id: "file"), isDirectory: true)
    }

    /// A name that stays one path component whatever tafs cloud sends (`FileText.name`), else the file's id.
    static func safeName(_ name: String, id: String) -> String {
        FileText.name(name, fallback: id)
    }

    /// Removes a file's copy (its screen closed).
    static func remove(_ id: String) {
        try? FileManager.default.removeItem(at: folder(id))
    }
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

    /// The files of one taf, one run or one chat, from what was read (tafs cloud's `?taf=`, `?run=` and `?chat=`).
    func files(taf: String? = nil, run: String? = nil, chat: String? = nil) -> [DeliveredFile] {
        guard case .loaded(let all) = state else { return [] }
        return all.filter { (taf == nil || $0.tafID == taf) && (run == nil || $0.runID == run) && (chat == nil || $0.chat == chat) }
    }

    /// The file's own bytes as a copy on this device (`FileCopy`), for Download and the share sheet: read only when one of
    /// them is asked for, never to draw the preview.
    func localCopy(of file: DeliveredFile) async throws -> URL {
        let data = try await store.contents(of: file.id)
        let folder = FileCopy.folder(file.id)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(FileCopy.safeName(file.name, id: file.id))
        try data.write(to: url, options: .completeFileProtection)
        return url
    }

    /// What a file's screen draws above its buttons.
    enum PreviewRead: Equatable {
        /// tafs cloud's cleaned start, drawn as plain text.
        case text(FilePreviewText)
        /// The bytes on the device, drawn by the system: only for a type that has no text start.
        case copy(URL)
        /// tafs cloud answered a text type with no start: no preview is drawn, and no bytes read. The file's details,
        /// Download and Share stay, as on Android.
        case noPreview
        /// tafs cloud couldn't be reached, for a type that has a text start: the "couldn't reach" line, and no bytes read.
        case unreachable
    }

    /// The types tafs cloud gives a text start for. Their bytes are never drawn on a failed read: that would show the raw
    /// file, which the cleaned start is there to avoid.
    static let textTypes: Set<String> = ["CSV", "MD", "TXT", "JSON"]

    /// A file's preview: tafs cloud's start when it gives one. The bytes are read for the system to draw only when it
    /// answered with none, or when the type has no text start; a failed read of a text type reads no bytes.
    func readPreview(of file: DeliveredFile) async -> PreviewRead {
        let text = Self.textTypes.contains(file.type.uppercased())
        let start: FilePreviewText?
        do {
            start = try await store.preview(of: file.id)
        } catch {
            guard !text else { return .unreachable }
            start = nil
        }
        if let start { return .text(start) }
        // A text type always has a start from tafs cloud: one answered without it (a malformed or older answer) reads no
        // bytes either, so its raw file is never drawn in the cleaned start's place (agentgabo's read). tafs cloud was
        // reached, so no line says otherwise: there's simply no preview, as on Android (dev-android's read).
        guard !text else { return .noPreview }
        do { return .copy(try await localCopy(of: file)) } catch { return .unreachable }
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
        func file(_ id: String, _ name: String, _ type: String, _ kb: Int, taf: (String, String), run: String, daysAgo: Double) -> DeliveredFile {
            let at = now.addingTimeInterval(-daysAgo * 86_400)
            return DeliveredFile(id: id, name: name, type: type, bytes: kb * 1_000, tafID: taf.0, tafName: taf.1, runID: run,
                                 runAt: at, chat: "s_" + run,
                                 goesAt: at.addingTimeInterval(Double(FilesFeature.keptDays) * 86_400))
        }
        let support = ("depl_support", "Support replies"), parcels = ("depl_parcels", "Late parcels"),
            leads = ("depl_leads", "Lead follow-up"), access = ("depl_access", "Access")
        return [
            file("f1", "Tickets answered.csv", "CSV", 14, taf: support, run: "r1", daysAgo: 0.1),
            file("f2", "Late parcels.pdf", "PDF", 86, taf: parcels, run: "r2", daysAgo: 0.2),
            file("f3", "Parcels without a scan.csv", "CSV", 6, taf: parcels, run: "r2", daysAgo: 0.2),
            file("f4", "New leads this week.xlsx", "XLSX", 22, taf: leads, run: "r3", daysAgo: 1.2),
            file("f5", "Call notes.docx", "DOCX", 31, taf: leads, run: "r3", daysAgo: 1.2),
            file("f7", "Access review.csv", "CSV", 9, taf: access, run: "r5", daysAgo: 26),
        ]
    }

    func preview(of id: String) async throws -> FilePreviewText? {
        FilePreviewText(text: String(decoding: try await contents(of: id), as: UTF8.self), truncated: false)
    }

    func contents(of id: String) async throws -> Data {
        Data("Item,Done\n#7781,09:04\n#7783,09:06\n".utf8)
    }

    func delete(_ id: String) async throws {}
}
