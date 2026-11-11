import SwiftUI

/// The Record's search box: tafs cloud's words as its placeholder. What's typed is searched once
/// the person stops typing (`AppModel.recordQueryTyped`), the Search key searches at once, and an
/// emptied box shows the Record again.
struct RecordSearchBox: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundColor(Theme.dim).accessibilityHidden(true)
            DriveField(model.recordHint ?? Words.Record.searchHint, text: $query)
                .scaledFont(15).foregroundColor(Theme.ink)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .onSubmit { Task { await model.recordQuerySubmitted(query) } }
                .onChange(of: query) { typed in model.recordQueryTyped(typed) }
                .accessibilityIdentifier("record-search")
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .task { await model.loadRecordHint() }
    }

}

/// What a search found, in tafs cloud's groups, each call as its taf's Activity draws it, under
/// its taf's name. Nothing found says so in tafs cloud's words; more pages come on More.
struct RecordResults: View {
    @EnvironmentObject private var model: AppModel
    let search: RecordSearch

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(search.pages.enumerated()), id: \.offset) { _, page in
                ForEach(page.shownGroups) { group in
                    if !group.title.isEmpty {
                        Text(group.title).scaledFont(12, weight: .semibold).foregroundColor(Theme.dim).padding(.top, 4)
                    }
                    ForEach(group.entries) { RecordHitRow(hit: $0) }
                }
            }
            if !search.pages.isEmpty, search.pages.allSatisfy(\.isEmpty), let none = search.pages.last?.none_words {
                Text(none).scaledFont(14).foregroundColor(Theme.soft)
            }
            if let error = search.error { Text(error).scaledFont(13).foregroundColor(Theme.no) }
            if search.loading {
                ProgressView().frame(maxWidth: .infinity)
            } else if search.nextCursor != nil {
                Button(Words.Common.more) { Task { await model.searchRecord(search.q, more: true) } }
                    .buttonStyle(OutlineButtonStyle())
            } else if let end = search.pages.last?.end_words {
                Text(end).scaledFont(13).foregroundColor(Theme.dim)
            }
        }
    }
}

/// One call found: its taf's name, the call as its taf's Activity draws it (its receipt and its
/// own next step), and what it's about, which opens the case. A taf the phone doesn't list (one
/// the person oversees only) shows the call as tafs cloud words it, without its next step.
struct RecordHitRow: View {
    @EnvironmentObject private var model: AppModel
    let hit: TafsCloud.RecordHit
    /// Off in a case, where every step is about the same thing.
    var opensCase = true

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(hit.taf.name).scaledFont(11, weight: .medium, design: .monospaced).textCase(.uppercase).foregroundColor(Theme.dim)
            if let tafID = model.tafID(cloudID: hit.taf.id) {
                SourceActivityRow(tafID: tafID, entry: hit.entry)
            } else {
                Panel {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(hit.entry.title).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(hit.entry.detail).scaledFont(13).foregroundColor(Theme.soft)
                        if let amount = hit.entry.amount_text { Text(amount).scaledFont(14, weight: .medium).foregroundColor(Theme.ink) }
                        Text(hit.entry.time).scaledFont(12).foregroundColor(Theme.dim)
                    }
                }
            }
            if opensCase, let about = hit.about {
                Button { Task { await model.openRecordCase(about) } } label: {
                    Text("\(about) ›").scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                }
                .buttonStyle(.drivablePlain)
            }
        }
    }
}

/// One case: every call about the same thing, oldest first, each as its taf's Activity draws it.
/// The step that didn't go through is named first, in tafs cloud's words; its retry is that
/// step's own next step, under its taf's Card. The Record never sends anything again itself.
struct RecordCaseSheet: View {
    @EnvironmentObject private var model: AppModel
    let recordCase: TafsCloud.RecordCase
    let error: String?

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(recordCase.about).scaledFont(24, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    if let words = recordCase.failed?.words, !words.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text(words).scaledFont(15, weight: .semibold).foregroundColor(Theme.no)
                    }
                    if let error { Text(error).scaledFont(14).foregroundColor(Theme.no) }
                    if recordCase.steps.isEmpty && error == nil { ProgressView().frame(maxWidth: .infinity) }
                    ForEach(recordCase.steps) { RecordHitRow(hit: $0, opensCase: false) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(Words.Common.done) { model.closeRecordCase() } }
            }
        }
    }
}

/// Export receipts, for someone who sees every taf's Record: a range of days inside tafs
/// cloud's 92, then its export as two files through the share sheet (KIFF's receipts as tafs
/// cloud passed them, and its CSV).
struct ExportReceiptsRow: View {
    @EnvironmentObject private var model: AppModel
    /// The day ranges offered.
    static let days = [7, 30, 90]
    /// Today, for the range; a test passes its own.
    var today: () -> Date = Date.init
    @State private var open = false
    @State private var preparing = false
    @State private var why: String?
    @State private var missing = 0
    @State private var files: SharedFiles?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { open.toggle() } label: {
                HStack(spacing: 8) {
                    if preparing { ProgressView() }
                    Text(preparing ? Words.Record.exportPreparing : Words.Record.exportReceipts)
                        .scaledFont(15, weight: .semibold).foregroundColor(preparing ? Theme.dim : Theme.amberText)
                }
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
            }
            .buttonStyle(.drivablePlain).disabled(preparing)
            .accessibilityIdentifier("export-receipts")
            .sheet(item: $files) { ShareSheet(items: $0.urls).ignoresSafeArea() }
            if open && !preparing {
                HStack(spacing: 8) {
                    ForEach(Self.days, id: \.self) { count in
                        Button(Words.Record.exportLastDays(count: count)) { export(count) }.buttonStyle(OutlineButtonStyle())
                    }
                }
            }
            if missing > 0 { Text(Words.Record.exportMissing(count: missing)).scaledFont(13).foregroundColor(Theme.soft) }
            if let why { Text(why).scaledFont(13).foregroundColor(Theme.no) }
        }
    }

    /// The last `count` days, today among them, as the account's calendar days.
    static func range(_ count: Int, today: Date, calendar: Calendar = AccountTime.calendar) -> (from: String, to: String) {
        let day = ISO8601DateFormatter()
        day.formatOptions = [.withFullDate]
        day.timeZone = calendar.timeZone
        let from = calendar.date(byAdding: .day, value: -(count - 1), to: today) ?? today
        return (day.string(from: from), day.string(from: today))
    }

    private func export(_ count: Int) {
        guard !preparing else { return }
        let range = Self.range(count, today: today())
        preparing = true; why = nil; missing = 0
        Task {
            let result = await model.exportReceipts(from: range.from, to: range.to)
            preparing = false
            if let urls = result.files {
                missing = result.missing; open = false
                files = SharedFiles(urls: urls)
            } else {
                why = Words.Record.exportNotExported(why: result.why ?? "")
            }
        }
    }
}
