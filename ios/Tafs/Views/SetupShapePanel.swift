import SwiftUI

/// Beside Setup's conversation on a wide screen (kiff/tafs#85): what it has made so far,
/// the taf's name, the plan, the tools it connects and the Card KIFF drafted. Only what
/// the app already holds; the cards to answer stay in the conversation.
struct SetupShapePanel: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let shape = model.setupShape
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 6) {
                    Eyebrow(text: "Taking shape")
                    if let heading = shape.name ?? shape.title {
                        Text(heading).scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if shape.isEmpty {
                        Text("As the conversation goes, the plan, the tools being connected and the taf's Card show here.")
                            .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    }
                }
                if !shape.steps.isEmpty { plan(shape.steps) }
                if !shape.tools.isEmpty { tools(shape.tools) }
                if let card = shape.card { self.card(card) }
            }
            .padding(20)
        }
        // Coach mode's blue, a shade deeper than the conversation beside it (kiff/tafs#138).
        .background(Theme.coachPanel.ignoresSafeArea())
    }

    private func plan(_ steps: [SetupShape.Step]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: "Plan · \(steps.filter { $0.state == .done }.count) of \(steps.count)")
            ForEach(Array(steps.enumerated()), id: \.offset) { _, step in
                HStack(alignment: .top, spacing: 10) {
                    StepMark(status: Self.mark(step.state)).padding(.top, 1)
                    VStack(alignment: .leading, spacing: 2) {
                        // Text's own strikethrough, before the font: a view's is macOS 13 (kiff/tafs#85).
                        Text(step.label)
                            .strikethrough(step.state == .skipped)
                            .scaledFont(14, weight: step.state == .needsYou || step.state == .now ? .semibold : .regular)
                            .foregroundColor(step.state == .toDo || step.state == .skipped ? Theme.dim : Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        if step.state == .needsYou {
                            Text("Needs you").scaledFont(12, weight: .semibold, relativeTo: .caption).foregroundColor(Theme.amberText)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    private static func mark(_ state: SetupShape.Step.State) -> String {
        switch state {
        case .done: return "done"
        case .failed: return "failed"
        case .needsYou, .now: return "active"
        case .toDo, .skipped: return "pending"
        }
    }

    private func tools(_ tools: [SetupShape.Tool]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: "Tools")
            ForEach(tools) { tool in
                let status = Self.status(tool.state)
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(tool.vendor).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Spacer(minLength: 8)
                        Text(status.0).scaledFont(12, weight: .semibold, relativeTo: .caption).foregroundColor(status.1)
                    }
                    if !tool.actions.isEmpty {
                        Text(tool.actions.joined(separator: ", "))
                            .scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.soft)
                    }
                    if case .failed(let why?) = tool.state {
                        Text(why).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(12)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .accessibilityElement(children: .combine)
            }
            Text("Sign-ins are kept sealed: no taf ever sees them.")
                .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
        }
    }

    private static func status(_ state: SetupShape.Tool.State) -> (String, Color) {
        switch state {
        case .connected: return ("Connected", Theme.ok)
        case .signedIn: return ("Signed in", Theme.amberText)
        case .waiting: return ("Waiting for you", Theme.amberText)
        case .failed: return ("Not connected", Theme.no)
        }
    }

    private func card(_ card: SetupShape.Card) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: card.confirmed ? "Card · confirmed by KIFF" : "Card · draft · by KIFF")
            VStack(alignment: .leading, spacing: 6) {
                Text(card.title).scaledFont(13, weight: .semibold, design: .monospaced, relativeTo: .caption)
                ForEach(Array(card.lines.enumerated()), id: \.offset) { _, line in
                    Text(line).scaledFont(12, design: .monospaced, relativeTo: .caption)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if card.lines.isEmpty { Text("no actions yet").scaledFont(12, design: .monospaced, relativeTo: .caption) }
            }
            .foregroundColor(Theme.ink)
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.panel)
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(card.confirmed ? Theme.ok : Theme.ink.opacity(0.25), style: StrokeStyle(lineWidth: 1, dash: card.confirmed ? [] : [5, 4])))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            if !card.confirmed {
                Text("A draft until you approve it in the conversation.")
                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
            }
        }
    }
}
