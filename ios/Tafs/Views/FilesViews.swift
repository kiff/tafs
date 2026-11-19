import SwiftUI
import CoreTransferable
import QuickLook
import UniformTypeIdentifiers

/// Files (docs/files.md): the account's files, or one taf's, newest first. On a phone a file opens over the list; on an
/// iPad's full width the list and the open file sit side by side, so the list is walked without going back each time.
struct FilesView: View {
    static var title: String { Words.Files.title }

    /// One taf's files (its page's Files row, its chat's Files entry), by tafs cloud's id; nil for the account's (Account's row).
    let taf: String?
    @StateObject private var files: FilesModel
    @EnvironmentObject private var model: AppModel
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var grouping: Grouping = .all
    /// A chip's taf, narrowing the account's files to one.
    @State private var chip: String?
    @State private var selected: String?

    enum Grouping: Hashable, CaseIterable {
        case all, byTaf, byRun
        var title: String {
            switch self {
            case .all: return Words.Files.all
            case .byTaf: return Words.Files.byTaf
            case .byRun: return Words.Files.byRun
            }
        }
    }

    init(taf: String? = nil, store: FilesStore = StubFilesStore()) {
        self.taf = taf
        _files = StateObject(wrappedValue: FilesModel(store: store))
    }

    private var wide: Bool { sizeClass == .regular }

    var body: some View {
        Group {
            if wide {
                HStack(spacing: 0) {
                    list.frame(width: 380)
                    Divider()
                    detail.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else {
                list
            }
        }
        .screenBackground()
        .navigationTitle(Self.title)
        .task { await files.load() }
        .refreshable { await files.load() }
    }

    private var zone: TimeZone { model.kiffAccount?.timeZone.flatMap(TimeZone.init(identifier:)) ?? .current }

    private var shown: [DeliveredFile] {
        files.files(taf: taf ?? chip)
    }

    @ViewBuilder private var list: some View {
        List {
            switch files.state {
            case .loading:
                ProgressView().frame(maxWidth: .infinity)
            case .failed:
                Text(Words.Alert.tafsCouldNotReachKiff).scaledFont(14).foregroundColor(Theme.no)
            case .loaded(let all) where all.isEmpty:
                Text(Words.Files.empty).scaledFont(14).foregroundColor(Theme.soft)
            case .loaded(let all):
                if taf == nil {
                    Section {
                        Picker(Words.Files.title, selection: $grouping) {
                            ForEach(Grouping.allCases, id: \.self) { Text($0.title).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        chips(all)
                    }
                }
                groups
                Section {} footer: { Text(Words.Files.keptLine) }
            }
        }
        .scrollContentBackground(.hidden)
    }

    /// One chip per taf with files, and All tafs.
    private func chips(_ all: [DeliveredFile]) -> some View {
        let tafs = FilesGrouping.byTaf(all).compactMap { $0.files.first.map { ($0.tafID, $0.tafName) } }
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                chipButton(Words.Files.allTafs, on: chip == nil) { chip = nil }
                ForEach(tafs, id: \.0) { id, name in chipButton(name, on: chip == id) { chip = id } }
            }
        }
    }

    private func chipButton(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).scaledFont(13, weight: .semibold)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(on ? Theme.ink : Theme.ink.opacity(0.08)))
                .foregroundColor(on ? Theme.background : Theme.ink)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var groups: some View {
        let now = Date()
        switch taf == nil ? grouping : .byRun {
        case .all:
            ForEach(FilesGrouping.byDay(shown, zone: zone)) { group in
                Section { rows(group.files, now: now) } header: { Text(day(group.files.first?.runAt)) }
            }
        case .byTaf:
            ForEach(FilesGrouping.byTaf(shown)) { group in
                Section { rows(group.files, now: now) } header: { Text(group.files.first?.tafName ?? "") }
            }
        case .byRun:
            ForEach(FilesGrouping.byRun(shown)) { group in
                Section { rows(group.files, now: now) } header: {
                    Text([group.files.first?.tafName, day(group.files.first?.runAt)].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                }
            }
        }
    }

    @ViewBuilder private func rows(_ group: [DeliveredFile], now: Date) -> some View {
        ForEach(group) { file in
            if wide {
                Button { selected = file.id } label: { FileRow(file: file, now: now) }
                    .listRowBackground(selected == file.id ? Theme.ink.opacity(0.06) : nil)
            } else {
                NavigationLink { FileDetailView(file: file, files: files) } label: { FileRow(file: file, now: now) }
            }
        }
    }

    @ViewBuilder private var detail: some View {
        if let file = files.files().first(where: { $0.id == (selected ?? shown.first?.id) }) {
            FileDetailView(file: file, files: files) { selected = nil }
                .id(file.id)
        } else {
            Text(Words.Files.pickOne).scaledFont(14).foregroundColor(Theme.soft)
        }
    }

    private func day(_ date: Date?) -> String {
        guard let date else { return "" }
        var style = Date.FormatStyle.dateTime.weekday(.abbreviated).day().month(.abbreviated)
        style.timeZone = zone
        return date.formatted(style)
    }
}

/// A file in a list: its type, its name, the taf that made it with its size, and when it goes.
struct FileRow: View {
    let file: DeliveredFile
    let now: Date

    var body: some View {
        HStack(spacing: 12) {
            FileBadge(type: file.type)
            VStack(alignment: .leading, spacing: 2) {
                Text(file.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                Text([file.tafName, FileSize.words(file.bytes)].filter { !$0.isEmpty }.joined(separator: " · "))
                    .scaledFont(13).foregroundColor(Theme.dim)
                Text(FileDays.goes(FilesGrouping.daysLeft(file, now: now)))
                    .scaledFont(12).foregroundColor(Theme.amberText)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The file's type, as a small badge: its own extension, never an icon that says more than it knows.
struct FileBadge: View {
    let type: String
    var size: CGFloat = 40

    var body: some View {
        Text(type)
            .scaledFont(10, weight: .bold)
            .foregroundColor(Theme.background)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size / 4).fill(Theme.soft))
            .accessibilityHidden(true)
    }
}

enum FileSize {
    /// A size as the system words it, in the person's language.
    static func words(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

enum FileDays {
    /// When it goes, in days: today, tomorrow, or in so many.
    static func goes(_ days: Int) -> String {
        switch days {
        case 0: return Words.Files.goesToday
        case 1: return Words.Files.goesTomorrow
        default: return Words.Files.goesInDays(count: days)
        }
    }
}

/// A file: a preview drawn on the device from the file itself, Download and Share (the file itself through the share
/// sheet, never a link), the taf and run it came from, where else it went, who sees it and when it goes; Delete now apart,
/// at the end, behind a confirm.
struct FileDetailView: View {
    let file: DeliveredFile
    @ObservedObject var files: FilesModel
    /// After Delete now, on an iPad's full width: the side's selection clears. On a phone the screen closes.
    var deleted: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var model: AppModel
    /// What's drawn above the buttons; nil until read (`FilesModel.readPreview`).
    @State private var shown: FilesModel.PreviewRead?
    /// The file's own bytes on this device, only once Download or (for a type with no text start) its preview asks.
    @State private var local: URL?
    @State private var saving = false
    @State private var confirming = false
    @State private var note: String?

    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    FileBadge(type: file.type, size: 44)
                    Text(file.name).scaledFont(17, weight: .bold).foregroundColor(Theme.ink)
                }
                switch shown {
                case .text(let preview):
                    // tafs cloud's cleaned start, drawn as plain text: never Markdown, HTML or a viewer of the bytes.
                    ScrollView {
                        Text(verbatim: preview.text)
                            .font(.system(.footnote, design: .monospaced))
                            .foregroundColor(Theme.ink)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 320)
                    if preview.truncated {
                        Text(Words.Files.previewStart).scaledFont(12).foregroundColor(Theme.soft)
                    }
                case .copy(let copy):
                    // No text start from tafs cloud: the system draws the file on the device.
                    FilePreview(url: copy).frame(height: 320)
                case .unreachable:
                    Text(Words.Alert.tafsCouldNotReachKiff).scaledFont(14).foregroundColor(Theme.no)
                case .noPreview?:
                    // tafs cloud answered with no start for a text type: no preview, only the details and the buttons.
                    EmptyView()
                case nil:
                    ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                }
                HStack(spacing: 10) {
                    Button(Words.Files.download) { Task { await download() } }
                        .buttonStyle(.borderedProminent)
                    // The file itself through the share sheet, its bytes read only when it's shared.
                    ShareLink(item: SharedFile(file: file, files: files), preview: SharePreview(file.name)) { Text(Words.Files.share) }
                        .buttonStyle(.bordered)
                }
                if let note { Text(note).scaledFont(13).foregroundColor(Theme.soft) }
            }
            Section {
                if !file.tafName.isEmpty { LabeledContent(Words.Files.madeBy, value: file.tafName) }
                LabeledContent(Words.Files.inRun, value: file.runAt.formatted(date: .abbreviated, time: .shortened))
                LabeledContent(Words.Files.type, value: file.type)
                LabeledContent(Words.Files.size, value: FileSize.words(file.bytes))
                LabeledContent(Words.Files.whoSees, value: Words.Files.everyone)
                LabeledContent(Words.Files.goesAway, value: Words.Files.goesOn(date: file.goesAt.formatted(date: .abbreviated, time: .omitted)))
            }
            Section {
                Button(Words.Files.deleteNow, role: .destructive) { confirming = true }
                    .confirming()
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(file.name)
        .task { await readPreview() }
        // The copy goes with the screen: never kept on the device past it.
        .onDisappear { FileCopy.remove(file.id) }
        .fileExporter(isPresented: $saving, document: local.map(FileForSaving.init), contentType: .data, defaultFilename: file.name) { result in
            if case .success = result { note = Words.Files.saved }
        }
        .confirmationDialog(Words.Files.deleteQuestion(name: file.name), isPresented: $confirming, titleVisibility: .visible) {
            Button(Words.Files.deleteNow, role: .destructive) {
                Task {
                    if await files.delete(file.id) {
                        if let deleted { deleted() } else { dismiss() }
                    } else {
                        note = Words.Alert.tafsCouldNotReachKiff
                    }
                }
            }
        } message: {
            Text(Words.Files.deleteBody)
        }
    }

    /// tafs cloud's start of the file, once; the bytes only as `FilesModel.readPreview` allows.
    private func readPreview() async {
        guard shown == nil else { return }
        let read = await files.readPreview(of: file)
        if case .copy(let copy) = read { local = copy }
        shown = read
    }

    /// Download: the file's own bytes, then where the person picks.
    private func download() async {
        if local == nil {
            do { local = try await files.localCopy(of: file) } catch { note = Words.Alert.tafsCouldNotReachKiff; return }
        }
        saving = true
    }
}

/// The file itself for the share sheet, read from tafs cloud only when it's shared: never a link.
struct SharedFile: Transferable, Sendable {
    let file: DeliveredFile
    let files: FilesModel

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .data) { shared in
            SentTransferredFile(try await shared.files.localCopy(of: shared.file))
        }
    }
}


/// The system's preview of a file on this device: nothing goes to a viewer service.
struct FilePreview: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let preview = QLPreviewController()
        preview.dataSource = context.coordinator
        return preview
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {
        context.coordinator.url = url
        controller.reloadData()
    }

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        var url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

/// The file as Download saves it: its own bytes, to wherever the person picks.
struct FileForSaving: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    let url: URL

    init(url: URL) { self.url = url }

    init(configuration: ReadConfiguration) throws {
        throw CocoaError(.featureUnsupported)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        try FileWrapper(url: url)
    }
}

/// The files a run delivered, as chips under its message in the chat: each opens the same file screen. Drawn once tafs
/// cloud says which run a message is (docs/files.md, "Still to come").
struct ChatFileChips: View {
    let files: [DeliveredFile]
    @ObservedObject var model: FilesModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(files) { file in
                NavigationLink { FileDetailView(file: file, files: model) } label: {
                    HStack(spacing: 8) {
                        FileBadge(type: file.type, size: 28)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(file.name).scaledFont(13, weight: .semibold).foregroundColor(Theme.ink)
                            Text([file.type, FileSize.words(file.bytes)].joined(separator: " · ")).scaledFont(12).foregroundColor(Theme.dim)
                        }
                    }
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Theme.ink.opacity(0.05)))
                }
                .buttonStyle(.plain)
            }
            if let goes = files.map(\.goesAt).min() {
                Text(Words.Files.keptUntil(date: goes.formatted(date: .abbreviated, time: .omitted)))
                    .scaledFont(12).foregroundColor(Theme.amberText)
            }
        }
    }
}

/// A taf's one way into its files: first on its header's line, "Files · Version 5 · live" (the owner's test of 3054, 19 Nov,
/// and the mockup's option A). In the page's ink, at least 44 pt to tap, with a count when it has files kept now. It shows
/// with none yet too, so a taf's files are always found in the same place; the chat has no second Files link.
struct TafFilesEntry: View {
    /// The taf, by tafs cloud's id.
    let taf: String
    @StateObject private var files: FilesModel

    init(taf: String, store: FilesStore) {
        self.taf = taf
        _files = StateObject(wrappedValue: FilesModel(store: store))
    }

    var body: some View {
        let count = Self.count(files, taf: taf)
        NavigationLink { FilesView(taf: taf, store: files.store) } label: {
            HStack(spacing: 6) {
                Image(systemName: "doc.on.doc").scaledFont(14, weight: .semibold).accessibilityHidden(true)
                Text(Words.Files.title).scaledFont(14, weight: .semibold).oneLineUnlessLarge()
                if count > 0 {
                    Text(Words.number(count))
                        .scaledFont(11, weight: .bold).monospacedDigit().foregroundColor(Theme.panel)
                        .padding(.horizontal, 6).frame(minWidth: 20, minHeight: 20)
                        .background(Capsule().fill(Theme.ink))
                        .accessibilityHidden(true)
                }
            }
            .foregroundColor(Theme.ink)
            .frame(minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.drivablePlain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.label(count: count))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("taf.files")
        .task(id: taf) { await files.load() }
    }

    /// The taf's files kept now, from the account's list; none while it's read or when the read fails.
    static func count(_ files: FilesModel, taf: String) -> Int { files.files(taf: taf).count }

    /// What VoiceOver says: "Files", then how many when there are some ("Files, 3 files from its runs").
    static func label(count: Int) -> String {
        count > 0 ? "\(Words.Files.title), \(Words.Files.tafRow(count: count))" : Words.Files.title
    }
}
