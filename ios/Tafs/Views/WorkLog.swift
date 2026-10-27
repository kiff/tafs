import SwiftUI

/// What a taf does while it answers, and what it did once it has (kiff/tafs#285): the step
/// in progress on top, one calm line of what it's thinking, and the steps done under it.
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

    /// One readable line of what the model is thinking, as plain text: the last complete
    /// sentence while the next is still a fragment, otherwise the sentence in progress. Lists,
    /// code and lines that introduce a list are skipped; a long sentence is cut at a word.
    static func thinkingLine(_ text: String, max: Int = 140) -> String {
        var sentences: [String] = []
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
                .replacingOccurrences(of: #"\[([^\]]+)\]\([^)]*\)"#, with: "$1", options: .regularExpression)
            guard !line.isEmpty, !isNoise(line) else { continue }
            for s in split(plain(line)) where !s.hasSuffix(":") && wordy(s) { sentences.append(s) }
        }
        guard var line = sentences.last else { return "" }
        if sentences.count > 1, line.count < 40, !(line.last.map { ".!?…".contains($0) } ?? false) {
            line = sentences[sentences.count - 2]
        }
        guard line.count > max else { return line }
        let cut = String(line.prefix(max))
        let word = cut.lastIndex(of: " ").map { String(cut[..<$0]) } ?? cut
        return word.trimmingCharacters(in: CharacterSet(charactersIn: " ,;:.–-")) + "…"
    }

    /// "12 s", "1 min 5 s".
    static func duration(ms: Int) -> String {
        let s = Swift.max(1, Int((Double(ms) / 1000).rounded()))
        return s < 60 ? "\(s) s" : "\(s / 60) min \(s % 60) s"
    }

    /// A list item, a mapping or code: noise on one line.
    private static func isNoise(_ line: String) -> Bool {
        if MarkdownBlock.groups(line, #"^([-*•+]|\d+[.)])\s"#) != nil { return true }
        return line.contains("->") || line.contains("→") || line.contains("=>") || line.rangeOfCharacter(from: CharacterSet(charactersIn: "{}[]<>=")) != nil
    }

    /// The line without its Markdown (its links already their text): no emphasis, heading or code marks.
    private static func plain(_ line: String) -> String {
        var s = line
        for mark in ["**", "__", "`", "~~"] { s = s.replacingOccurrences(of: mark, with: "") }
        s = s.replacingOccurrences(of: #"^#+\s*"#, with: "", options: .regularExpression)
        return s.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    }

    /// Sentences, each ending at . ! ? or … followed by a space, the last one as it stands.
    private static func split(_ line: String) -> [String] {
        var out: [String] = []
        var current = ""
        var ended = false
        for ch in line {
            if ended, ch == " " {
                out.append(current.trimmingCharacters(in: .whitespaces))
                current = ""
                ended = false
                continue
            }
            current.append(ch)
            ended = ".!?…".contains(ch)
        }
        let rest = current.trimmingCharacters(in: .whitespaces)
        if !rest.isEmpty { out.append(rest) }
        return out
    }

    /// Three words or more, mostly letters.
    private static func wordy(_ s: String) -> Bool {
        guard s.split(separator: " ").count >= 3 else { return false }
        let letters = s.unicodeScalars.filter { CharacterSet.letters.contains($0) || $0 == " " }.count
        return Double(letters) / Double(Swift.max(1, s.unicodeScalars.count)) >= 0.75
    }
}

/// The work of the answer streaming now: the step in progress, or what it's at ("Thinking",
/// "Writing"), the line of what it's thinking while it hasn't started writing, and the
/// steps done so far. Nothing while it only writes, with no step taken.
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
                    Text(current.map { Self.words($0, model) } ?? (writing ? "Writing…" : "Thinking…"))
                        .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                if !writing { ThinkingLine(source: live.thinking) }
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

/// One calm line of what the taf is thinking, shown once it has thought for a few seconds
/// (a quick answer's thinking is noise) and changed at most every second and a half, so it
/// can be read. Gone when there's nothing to say.
struct ThinkingLine: View {
    let source: String
    /// How long before the line shows, and how often it may change.
    static let showAfter: TimeInterval = 3
    static let refresh: TimeInterval = 1.5
    @State private var line = ""
    @State private var since = Date()

    var body: some View {
        Group {
            if !line.isEmpty {
                Text(line)
                    .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 22)
                    .transition(.opacity)
                    .id(line)
                    .accessibilityLabel("Thinking: \(line)")
            }
        }
        .task {
            since = Date()
            while !Task.isCancelled {
                if Date().timeIntervalSince(since) >= Self.showAfter {
                    let next = WorkLog.thinkingLine(source)
                    if !next.isEmpty, next != line { withMotion(.easeOut(duration: 0.3)) { line = next } }
                }
                try? await Task.sleep(nanoseconds: UInt64(Self.refresh * 1_000_000_000))
            }
        }
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
                        Text("not made · one call at a time").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
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
    @State private var open = false

    var body: some View {
        let rows = WorkLog.rows(work.steps.map { (words: model.tafActivityWords($0.tool) ?? $0.tool, status: $0.notMade ? .notMade : $0.error ? .error : .done) }, running: false)
        VStack(alignment: .leading, spacing: 6) {
            Button { withMotion(.easeOut(duration: 0.25)) { open.toggle() } } label: {
                HStack(spacing: 4) {
                    Text("Worked for \(WorkLog.duration(ms: work.ms))")
                    Image(systemName: "chevron.right").rotationEffect(.degrees(open ? 90 : 0))
                }
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Worked for \(WorkLog.duration(ms: work.ms)), \(rows.count) steps")
            .accessibilityHint(open ? "Hides the steps" : "Shows the steps")
            if open { StepList(rows: rows).transition(.opacity) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("work-summary")
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
