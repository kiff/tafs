import SwiftUI

/// What a taf does while it answers, and what it did once it has (kiff/tafs#285): the live
/// line on top, and the steps done under it.
/// Once the answer is in, the steps fold into "Worked for 12 s", which opens them again.
enum WorkLog {
    /// One row of steps: a step, or the tries of the same step one after another.
    struct Row: Equatable {
        let words: String
        let status: AppModel.LiveStep.Status
        let tries: Int
    }

    /// The steps as rows. Tries of the same step after a failed or unmade one are one row,
    /// with the last try's outcome: trying again until it works is normal. A failed step is
    /// left out once something worked, or while the answer is still being worked on: what
    /// shows is what got done. When nothing worked, the failures say why. A step not made
    /// and not tried again stays: it isn't a failure, and it wasn't done.
    static func rows(_ steps: [(words: String, status: AppModel.LiveStep.Status)], running: Bool) -> [Row] {
        var groups: [[(words: String, status: AppModel.LiveStep.Status)]] = []
        for s in steps {
            if let last = groups.last, last[0].words == s.words, [.error, .notMade].contains(last[last.count - 1].status) {
                groups[groups.count - 1].append(s)
            } else {
                groups.append([s])
            }
        }
        let rows = groups.map { g in Row(words: g[0].words, status: g[g.count - 1].status, tries: g.count) }
        return running || rows.contains { $0.status == .done } ? rows.filter { $0.status != .error } : rows
    }

    /// "12 s", "1 min 5 s".
    static func duration(ms: Int) -> String {
        let s = Swift.max(1, Int((Double(ms) / 1000).rounded()))
        return s < 60 ? "\(s) s" : "\(s / 60) min \(s % 60) s"
    }
}

/// The work of the answer streaming now: the live line on top, as Android draws it (tafs
/// cloud's `state`, with a step's words or Coach's own progress note; before it comes, the
/// step in progress, or what it's at), and the steps done so far. Nothing while it only
/// writes, with no step taken. The model's thinking text is never drawn (`LiveAnswer.line`).
struct LiveWork: View {
    @EnvironmentObject private var model: AppModel
    let live: AppModel.LiveAnswer

    var body: some View {
        let at = live.steps.lastIndex { $0.status == .running }
        let current = at.map { live.steps[$0] }
        let done = live.steps.enumerated().filter { $0.offset != at }.map { (words: Self.words($0.element, model), status: $0.element.status) }
        let rows = WorkLog.rows(done, running: true)
        let writing = !live.text.isEmpty
        if current != nil || !writing || !rows.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.mini).tint(Theme.dim)
                    Text(live.line(current: current.map { Self.words($0, model) }))
                        .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                if !rows.isEmpty { StepList(rows: rows) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("work-block")
        }
    }

    static func words(_ step: AppModel.LiveStep, _ model: AppModel) -> String {
        step.words ?? model.tafActivityWords(step.tool) ?? step.tool
    }
}

/// Steps done, under the step in progress or under "Worked for".
struct StepList: View {
    let rows: [WorkLog.Row]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Image(systemName: Self.mark(row.status))
                        .scaledFont(11, relativeTo: .caption)
                        .foregroundColor(row.status == .error ? Theme.no : row.status == .held ? Theme.amber : Theme.dim)
                        .frame(width: 14)
                    Text(Self.past(row.words)).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    if row.status == .notMade {
                        Text("not made · read first").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    } else if row.tries > 1 {
                        Text("\(row.tries) tries").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.leading, 6)
        .overlay(alignment: .leading) { Rectangle().fill(Theme.line).frame(width: 1) }
        .padding(.leading, 4)
    }

    static func mark(_ status: AppModel.LiveStep.Status) -> String {
        switch status {
        case .running: return "circle.dotted"
        case .done: return "checkmark"
        case .error: return "exclamationmark.circle"
        case .held: return "hand.raised"
        case .notMade: return "arrow.uturn.left"
        }
    }

    /// A step's words without the trailing "…" they have while it runs.
    static func past(_ words: String) -> String {
        words.hasSuffix("…") ? String(words.dropLast()) : words
    }
}

/// A finished answer's work, folded: "Worked for 12 s ›", which opens its steps.
struct WorkSummary: View {
    @EnvironmentObject private var model: AppModel
    let work: TafsCloud.Work
    /// Coach's work: its steps in Coach's words, never "Asking KIFF to run planque" (the 870
    /// test), and without what it showed, as while it works.
    var coach = false
    @State private var open = false

    var body: some View {
        let steps = coach ? work.steps.filter { AppModel.activityWords($0.tool) != nil } : work.steps
        let rows = WorkLog.rows(steps.map { (words: Self.words($0.tool, coach: coach, model), status: $0.notMade ? .notMade : $0.error ? .error : .done) }, running: false)
        VStack(alignment: .leading, spacing: 6) {
            Button { withMotion(.easeOut(duration: 0.25)) { open.toggle() } } label: {
                HStack(spacing: 4) {
                    Text("Worked for \(WorkLog.duration(ms: work.ms))")
                    Image(systemName: "chevron.right").rotationEffect(.degrees(open ? 90 : 0))
                }
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Worked for \(WorkLog.duration(ms: work.ms)), \(rows.count == 1 ? "1 step" : "\(rows.count) steps")")
            .accessibilityHint(open ? "Hides the steps" : "Shows the steps")
            if open { StepList(rows: rows).transition(.opacity) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("work-summary")
    }

    static func words(_ tool: String, coach: Bool, _ model: AppModel) -> String {
        coach ? (AppModel.activityWords(tool) ?? tool) : (model.tafActivityWords(tool) ?? tool)
    }
}

/// An answer streaming in: its text shown at an even pace, not in the bursts it arrives in,
/// catching up within a few frames, and with any formatting still being written closed, so
/// no Markdown symbol shows on the way. With Reduce Motion, all of it at once.
struct SteadyMarkdown: View {
    let text: String
    var size: CGFloat = 16
    /// How many characters show; nil shows all.
    @State private var shown: Int?

    var body: some View {
        let healed = MarkdownBlock.healed(text)
        MarkdownText(healed, visible: shown, size: size)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("answer-streaming")
            .task(id: text) {
                let target = MarkdownText(healed).length
                guard !Motion.reduced else { shown = nil; return }
                // The first words show at once; what comes after, paced.
                var at = Swift.min(shown ?? target, target)
                shown = at
                while at < target, !Task.isCancelled {
                    at = Swift.min(target, at + Swift.max(2, (target - at + 5) / 6))
                    shown = at
                    try? await Task.sleep(nanoseconds: 16_000_000)
                }
            }
    }
}
