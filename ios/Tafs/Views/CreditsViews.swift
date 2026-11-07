import SwiftUI

/// The credits the account's tafs used (pricing phase 1, docs/accounts.md): a panel at the top
/// of Plan & usage, read only. This period or the one before; the period's total, each taf
/// that used credits (opening its runs), and what wasn't counted and why. Every line is tafs
/// cloud's `words`, as it writes them: the app adds nothing to them but the order.
struct CreditsPanel: View {
    @EnvironmentObject private var model: AppModel
    @State private var previous = false
    /// The period before was asked for and its read is over, whatever it gave.
    @State private var previousRead = false

    var body: some View {
        if let shown = previous ? model.previousCredits : model.credits {
            content(shown)
        } else if previous {
            Panel {
                Eyebrow(text: Self.title)
                picker
                if previousRead {
                    Text(Self.unread).scaledFont(14).foregroundColor(Theme.soft)
                } else {
                    ProgressView().frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        } else {
            EmptyView()
        }
    }

    private var picker: some View {
        Picker(Self.title, selection: $previous) {
            Text(Self.thisPeriod).tag(false)
            Text(Self.lastPeriod).tag(true)
        }
        .pickerStyle(.segmented)
        .task(id: previous) {
            guard previous, model.previousCredits == nil else { return }
            previousRead = false
            await model.readPreviousCredits()
            previousRead = true
        }
    }

    @ViewBuilder private func content(_ c: TafsCloud.Credits) -> some View {
        Panel {
            Eyebrow(text: Self.title)
            picker
            Text(c.words).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            if let note = c.note {
                Text(note).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
        }
        ForEach(c.tafs) { t in
            // A taf this app knows by its tafs cloud id opens its runs; the sample's don't.
            if model.mode == .kiff {
                PanelLink { TafCreditsView(taf: t.taf, previous: previous).environmentObject(model) } label: {
                    Text(t.words).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
            } else {
                Panel { Text(t.words).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true) }
            }
        }
        if let not = c.notCounted, !not.words.isEmpty || !not.lines.isEmpty {
            Panel {
                if !not.words.isEmpty { Text(not.words).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink) }
                ForEach(Array(not.lines.enumerated()), id: \.offset) { _, line in
                    Text(line.words).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    static let title = "Credits"
    static let thisPeriod = "This period"
    static let lastPeriod = "Last period"
    static let unread = "The last period's credits couldn't be read. Choose it again to try again."
}

/// One taf's credits in a period, with its runs, newest first: each in tafs cloud's words, and
/// opening its Record entry where it has one (a chat that only answered has none).
struct TafCreditsView: View {
    @EnvironmentObject private var model: AppModel
    let taf: String
    let previous: Bool
    /// Read on this screen: an earlier read isn't shown as now.
    @State private var read = false

    private var key: AppModel.CreditsKey { .init(taf: taf, previous: previous) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if read, let mine = model.tafCredits[key] {
                    Panel {
                        Text(mine.words).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if !mine.runs.isEmpty {
                        Panel {
                            ForEach(mine.runs) { CreditsRunRow(run: $0) }
                            if mine.next != nil {
                                Button(Self.more) { Task { await model.readTafCredits(taf, previous: previous, more: true) } }
                                    .buttonStyle(OutlineButtonStyle())
                            }
                        }
                    }
                } else if read {
                    Panel { Text(Self.unread).scaledFont(14).foregroundColor(Theme.soft) }
                } else {
                    Panel { ProgressView().frame(maxWidth: .infinity, alignment: .leading) }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(model.tafID(cloud: taf).flatMap { model.taf($0)?.name } ?? CreditsPanel.title)
        .inlineTitle()
        .task { await model.readTafCredits(taf, previous: previous); read = true }
        .refreshable { await model.readTafCredits(taf, previous: previous) }
    }

    static let more = "Earlier runs"
    static let unread = "These runs couldn't be read. Pull to try again."
}

/// One run: tafs cloud's words and when; it opens its Record entry's receipt when it has one.
struct CreditsRunRow: View {
    @EnvironmentObject private var model: AppModel
    let run: TafsCloud.TafCredits.Run

    var body: some View {
        if let record = run.record, let entry = model.recordEntry(forCall: record) {
            RecordLinkRow(entry: entry) { line }
        } else {
            line.accessibilityElement(children: .combine)
        }
    }

    private var line: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(run.words).scaledFont(14, weight: run.counted ? .semibold : .regular)
                .foregroundColor(run.counted ? Theme.ink : Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
            Text(run.at.formatted(.relative(presentation: .named))).scaledFont(13).foregroundColor(Theme.dim)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A row that opens a Record entry's receipt.
private struct RecordLinkRow<Label: View>: View {
    @EnvironmentObject private var model: AppModel
    let entry: RecordEntry
    @ViewBuilder var label: () -> Label
    @State private var showing = false

    var body: some View {
        Button { showing = true } label: {
            HStack(spacing: 8) {
                label()
                Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim).accessibilityHidden(true)
            }
        }
        .buttonStyle(.drivablePlain)
        .sheet(isPresented: $showing) { ReceiptView(entry: entry).environmentObject(model) }
        .accessibilityHint("Shows the receipt")
    }
}
