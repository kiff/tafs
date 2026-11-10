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
                if let why = model.previousCreditsError {
                    Text(why).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                } else if previousRead {
                    Text(Self.unread).scaledFont(14).foregroundColor(Theme.soft)
                } else {
                    ProgressView().frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        } else if let why = model.creditsError {
            // This period couldn't be read: tafs cloud's words (a 503 when KIFF can't be read).
            Panel {
                Eyebrow(text: Self.title)
                Text(why).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
        } else {
            EmptyView()
        }
    }

    private var picker: some View {
        Picker(Self.title, selection: $previous) {
            Text(Self.lastPeriod).tag(true)
            Text(Self.thisPeriod).tag(false)
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
            // A refresh that failed keeps what was read and says why, as Android does.
            if let why = previous ? model.previousCreditsError : model.creditsError {
                Text(why).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
            Text(c.words).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
        ForEach(c.tafs) { t in
            // A taf's row opens its runs; the row for tafs removed since, and the sample's, don't.
            if model.mode == .kiff, let taf = t.taf {
                PanelLink { TafCreditsView(taf: taf, previous: previous).environmentObject(model) } label: {
                    Text(t.words).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
            } else {
                Panel { Text(t.words).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true) }
            }
        }
        // Coach past its fair use: its own row, which doesn't open.
        if let coach = c.coach {
            Panel { Text(coach.words).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true) }
        }
        if let not = c.notCounted, !not.words.isEmpty || !not.lines.isEmpty {
            Panel {
                if !not.words.isEmpty { Text(not.words).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink) }
                ForEach(Array(not.lines.enumerated()), id: \.offset) { _, line in
                    Text(line.words).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        // The note comes last, as the contract and Android have it.
        if let note = c.note {
            Text(note).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 4)
        }
    }

    static let title = Words.Usage.credits
    static let thisPeriod = Words.Usage.thisPeriod
    static let lastPeriod = Words.Usage.lastPeriod
    static let unread = "The last period's credits couldn't be read. Choose it again to try again."
}

/// One taf's credits in a period, with its runs, newest first: each in tafs cloud's words, and
/// opening the run's request, what it did step by step, while tafs cloud says it can (`opens`).
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
                    if !mine.items.isEmpty {
                        Panel {
                            ForEach(mine.items) { CreditsRunRow(taf: taf, run: $0) }
                            if mine.next != nil {
                                Button(Self.more) { Task { await model.readTafCredits(taf, previous: previous, more: true) } }
                                    .buttonStyle(OutlineButtonStyle())
                            }
                            if let why = model.tafCreditsError[key] {
                                Text(why).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                } else if read {
                    Panel { Text(model.tafCreditsError[key] ?? Self.unread).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true) }
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

    static let more = Words.Usage.moreRuns
    static let unread = "These runs couldn't be read. Pull to try again."
}

/// One run: tafs cloud's words and when; it opens the run's request while `opens` says so.
struct CreditsRunRow: View {
    @EnvironmentObject private var model: AppModel
    let taf: String
    let run: TafsCloud.TafCredits.Run

    var body: some View {
        if run.opens, let tafID = model.tafID(cloud: taf) {
            NavigationLink { KnobRequestDetail(tafID: tafID, run: run.run).environmentObject(model) } label: {
                HStack(spacing: 8) {
                    line
                    Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim).accessibilityHidden(true)
                }
            }
            .buttonStyle(.drivablePlain)
        } else {
            line.accessibilityElement(children: .combine)
        }
    }

    private var line: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(run.words).scaledFont(14, weight: run.counted ? .semibold : .regular)
                .foregroundColor(run.counted ? Theme.ink : Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
            // A short time in the account's zone, as Android shows it.
            Text(run.at.shortTime).scaledFont(13).foregroundColor(Theme.dim)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
