import SwiftUI

/// Coach's tools still to give, as one line above the conversation (docs/coach.md › Coach's
/// tools step): it opens the step. Never a panel there, so the message box keeps its room,
/// keyboard open or not. Nothing once the tools are given, or before tafs cloud has said.
struct CoachToolsRow: View {
    @EnvironmentObject private var model: AppModel
    let open: () -> Void

    var body: some View {
        if let tools = model.coachTools, !tools.given {
            Button(action: open) {
                HStack(spacing: 8) {
                    Text(Self.words(tools))
                        .scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").scaledFont(13).foregroundColor(Theme.dim)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 16)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.drivablePlain)
            .accessibilityIdentifier("coach-tools-row")
        }
    }

    /// tafs cloud's line for the row, or the app's own from a tafs cloud that sends none.
    static func words(_ tools: TafsCloud.CoachTools) -> String {
        CoachToolsSheet.said(tools.step?.row, Words.Coach.Tools.title)
    }
}

/// Coach's tools as a step of their own (docs/coach.md): what tafs cloud says is still to
/// give, in its words, with each tool and Coach's Cards folded under a few groups; then the
/// one confirmation that gives them, or Not now. Opened at the end of a first Start, and from
/// the row after. Every word is tafs cloud's or KIFF's; the app's own stand in only for a
/// tafs cloud from before the step.
struct CoachToolsSheet: View {
    @EnvironmentObject private var model: AppModel
    let close: () -> Void
    @State private var folded = true

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if let tools = model.coachTools {
                        content(tools)
                    }
                    if let error = model.coachToolsError {
                        Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button(Self.said(model.coachTools?.step?.notNow, Words.Common.notNow)) {
                        model.notNowCoachTools()
                        close()
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(model.givingCoachTools)
                    .accessibilityIdentifier("coach-tools-not-now")
                }
                .padding(20)
            }
            .screenBackground()
            .navigationTitle(Self.title(model.coachTools))
            .inlineTitle()
        }
        // Given while the sheet was open, or nothing to show: it closes itself.
        .onChange(of: model.coachTools?.given) { given in
            if given == true { close() }
        }
    }

    @ViewBuilder
    private func content(_ tools: TafsCloud.CoachTools) -> some View {
        switch tools.state {
        case "taken":
            line(tools.taken.map { Words.Coach.Taken.named(name: "\($0)") } ?? Words.Coach.Taken.unnamed, 15, Theme.ink)
        case "to_give":
            toGive(tools)
        default:
            line(Words.Coach.toolsUnknown, 15, Theme.ink)
        }
    }

    @ViewBuilder
    private func toGive(_ tools: TafsCloud.CoachTools) -> some View {
        let step = tools.step
        let grouped = !(step?.groups.isEmpty ?? true)
        if let step, grouped {
            if !step.intro.isEmpty { line(step.intro, 15, Theme.soft) }
            // A few groups up front; each tool and its limits folded under them, closed at first.
            ForEach(Array(step.groups.enumerated()), id: \.offset) { _, group in
                VStack(alignment: .leading, spacing: 2) {
                    line(group.name, 15, Theme.ink, .semibold)
                    if !group.line.isEmpty { line(group.line, 14, Theme.soft) }
                }
                .padding(.top, 4)
            }
            if !step.details.isEmpty {
                Button { folded.toggle() } label: {
                    HStack(spacing: 6) {
                        Text(step.details).scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                        Image(systemName: folded ? "chevron.down" : "chevron.up").scaledFont(12).foregroundColor(Theme.amberText)
                            .accessibilityHidden(true)
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.drivablePlain)
                .accessibilityIdentifier("coach-tools-details")
            }
        }
        // What's signed: each tool in tafs cloud's words and the Cards in KIFF's, under the
        // fold where the step has one.
        let open = !grouped || (step?.details.isEmpty ?? true) || !folded
        if open {
            if let step, grouped {
                ForEach(Array(step.groups.enumerated()), id: \.offset) { _, group in
                    if !group.tools.isEmpty { line(group.name, 13, Theme.ink, .semibold).padding(.top, 4) }
                    ForEach(Array(group.tools.enumerated()), id: \.offset) { _, tool in
                        VStack(alignment: .leading, spacing: 2) {
                            line(tool.name, 14, Theme.ink, .semibold)
                            if !tool.does.isEmpty { line(tool.does, 13, Theme.ink) }
                            if !tool.why.isEmpty { line(tool.why, 13, Theme.soft) }
                        }
                    }
                }
            } else if let lines = tools.connect?.lines {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, said in line(said, 14, Theme.ink) }
            }
            if let card = tools.card {
                if let title = card.cardTitle, !title.isEmpty { line(title, 14, Theme.ink, .semibold).padding(.top, 4) }
                ForEach(Array(card.cards.enumerated()), id: \.offset) { _, words in
                    VStack(alignment: .leading, spacing: 2) {
                        if !words.title.isEmpty { line(words.title, 13, Theme.ink, .semibold) }
                        ForEach(Array(words.lines.enumerated()), id: \.offset) { _, said in line(said, 13, Theme.soft) }
                    }
                }
            }
        }
        if let ask = step?.ask, !ask.isEmpty { line(ask, 15, Theme.ink, .semibold).padding(.top, 6) }
        // The one confirmation: the person's, never a drive's. A phone that can't approve yet
        // reads why in the button's place.
        ApprovalGate {
            Button {
                Task { if await model.giveCoachTools() { close() } }
            } label: {
                Label(model.givingCoachTools ? Words.Coach.Tools.giving : Self.said(step?.give, Words.Coach.Tools.give),
                      systemImage: Biometry.symbol)
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.givingCoachTools)
            .accessibilityIdentifier("coach-tools-give")
        }
    }

    private func line(_ words: String, _ size: CGFloat, _ color: Color, _ weight: Font.Weight = .regular) -> some View {
        Text(words).scaledFont(size, weight: weight).foregroundColor(color)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// tafs cloud's words where it sends them; the app's own only for a tafs cloud from before them.
    static func said(_ server: String?, _ own: String) -> String {
        guard let server, !server.trimmingCharacters(in: .whitespaces).isEmpty else { return own }
        return server
    }

    static func title(_ tools: TafsCloud.CoachTools?) -> String { said(tools?.step?.title, Words.Coach.Tools.title) }
}
