import SwiftUI

/// "Your brief" (Missions design v2.1 §7, the owner's validated mockups): decisions first, each with
/// Coach's recommendation and what happens meanwhile, then one next move, then what got done, case by
/// case. Two columns on an iPad's width, decisions beside Done. Every word, count and order is tafs
/// cloud's; a recommendation is shown, never acted on, and nothing is signed from here.
struct BriefView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// A screenshot's `-screen mission-log`: the first mission's log, opened over the brief.
    @State private var screenshotLog = Screenshots.brief && Screenshots.screen == "mission-log"

    var body: some View {
        if let brief = model.brief {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header(brief)
                    if sizeClass == .regular {
                        HStack(alignment: .top, spacing: 20) {
                            VStack(alignment: .leading, spacing: 14) { decisions(brief) }
                                .frame(maxWidth: .infinity, alignment: .topLeading)
                            VStack(alignment: .leading, spacing: 14) {
                                done(brief)
                                // Under Done, so it stays in view beside the decisions (the iPad mockup).
                                nextMove(brief)
                                rest(brief)
                            }
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                        }
                    } else {
                        decisions(brief)
                        nextMove(brief)
                        done(brief)
                        rest(brief)
                    }
                    if let credits = brief.credits {
                        Text(credits).scaledFont(12).foregroundColor(Theme.dim).frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 12)
            }
            .screenBackground()
            .navigationDestination(isPresented: $screenshotLog) {
                if let group = brief.done.first(where: { $0.log != nil }), let log = group.log { MissionLogView(mission: log, title: group.mission ?? "") }
            }
            .accessibilityIdentifier("brief")
        } else {
            NeedsYouView()
        }
    }

    // MARK: Header and chips

    private func header(_ brief: TafsCloud.Brief) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(brief.words.title).scaledFont(34, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
            if !brief.words.when.isEmpty {
                Label(brief.words.when, systemImage: "clock").scaledFont(13).foregroundColor(Theme.soft)
            }
            if !brief.labels.isEmpty { chips(brief) }
        }
    }

    /// All, then two labels, then "+N": the tafs list's chips, filtering the brief.
    private func chips(_ brief: TafsCloud.Brief) -> some View {
        LabelChips(labels: brief.labels, selected: model.briefLabel, all: brief.words.all.isEmpty ? model.labelsAllWords : brief.words.all) { id in Task { await model.filterBrief(id) } }
            .accessibilityIdentifier("brief.labels")
    }

    // MARK: Decisions

    @ViewBuilder private func decisions(_ brief: TafsCloud.Brief) -> some View {
        let open = model.briefDecisions
        if !open.isEmpty {
            Eyebrow(text: brief.words.decisions)
            ForEach(open) { decision in DecisionCard(decision: decision, words: brief.words) }
            if let more = brief.more { Text(more.words).scaledFont(13).foregroundColor(Theme.soft) }
        }
    }

    // MARK: Next move

    @ViewBuilder private func nextMove(_ brief: TafsCloud.Brief) -> some View {
        if let move = brief.nextMove, !model.nextMoveDismissed {
            Panel(stroke: Theme.amber.opacity(0.35)) {
                Label(brief.words.nextMove.uppercased(), systemImage: "sparkle")
                    .scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.amberText)
                Text(move.words).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 10) {
                    Button(move.start) { model.startNextMove(move) }.buttonStyle(OutlineButtonStyle(color: Theme.amberText))
                    Button(move.dismiss) { model.dismissNextMove() }.buttonStyle(OutlineButtonStyle())
                }
            }
            .accessibilityIdentifier("brief.next-move")
        }
    }

    // MARK: Done

    @ViewBuilder private func done(_ brief: TafsCloud.Brief) -> some View {
        if !brief.done.isEmpty {
            Eyebrow(text: brief.words.done)
            Panel {
                ForEach(Array(brief.done.enumerated()), id: \.element.id) { i, group in
                    if i > 0 { Divider() }
                    ForEach(group.cases) { c in CaseRow(item: c) }
                    if group.value != nil || group.log != nil {
                        HStack {
                            if let value = group.value { Text(value).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink) }
                            Spacer()
                            if let mission = group.log {
                                NavigationLink { MissionLogView(mission: mission, title: group.mission ?? "") } label: {
                                    Text(brief.words.evidence + " ›").scaledFont(13, weight: .semibold).foregroundColor(Theme.coach)
                                }
                                .accessibilityIdentifier("brief.evidence." + mission)
                            }
                        }
                    }
                }
            }
            .accessibilityIdentifier("brief.done")
        }
    }

    /// The things that need nothing now, in one line: today's Needs you list, unchanged.
    @ViewBuilder private func rest(_ brief: TafsCloud.Brief) -> some View {
        if let rest = brief.rest {
            PanelLink { NeedsYouView() } label: {
                Text(rest.words).scaledFont(14).foregroundColor(Theme.ink)
            }
            .accessibilityIdentifier("brief.rest")
        }
    }
}

/// A label chip: its name and count, an amber dot while one of its tafs waits; inverted when chosen.
struct LabelChip: View {
    let words: String
    let count: Int?
    let waiting: Bool
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if waiting { Circle().fill(Theme.amber).frame(width: 6, height: 6).accessibilityHidden(true) }
                Text(words).scaledFont(13, weight: .medium)
                if let count { Text("\(count)").scaledFont(12).opacity(0.7) }
            }
            .lineLimit(1)
            .padding(.horizontal, 12).frame(minHeight: 32)
            .foregroundColor(selected ? Theme.onFill : Theme.ink)
            .background(Capsule().fill(selected ? Theme.ink : Theme.panel))
            .overlay(Capsule().stroke(Theme.line, lineWidth: selected ? 0 : 1))
        }
        .buttonStyle(.drivablePlain)
        .driveWords(words)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// One decision: its taf, the question, Coach's recommendation with its evidence, what happens
/// meanwhile, why it can't wait when it can't, and its answers. The recommended answer is filled; the
/// others, a decline in red, and Later beside them. Nothing is decided by the app.
struct DecisionCard: View {
    @EnvironmentObject private var model: AppModel
    let decision: TafsCloud.Brief.Decision
    let words: TafsCloud.Brief.Words

    private var taf: Taf? {
        if let id = model.tafID(forCloud: decision.ref.taf), let t = model.taf(id) { return t }
        // The sample names its tafs by name.
        return model.mode == .sample ? model.tafs.first { $0.name == decision.ref.taf } : nil
    }

    var body: some View {
        Panel(stroke: decision.urgent ? Theme.no.opacity(0.5) : Theme.line) {
            HStack(spacing: 8) {
                if let taf {
                    TafAvatar(taf: taf, size: 26)
                    Text(taf.name).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                }
                Spacer()
                if decision.urgent {
                    // tafs cloud's word for it ("Urgent"); the bolt alone where it sent none.
                    HStack(spacing: 3) {
                        Image(systemName: "bolt.fill").scaledFont(10).accessibilityHidden(true)
                        if !words.urgent.isEmpty { Text(words.urgent).scaledFont(11, weight: .semibold) }
                    }
                    .foregroundColor(Theme.onFill)
                    .padding(.horizontal, 7).padding(.vertical, 4)
                    .background(Capsule().fill(Theme.no))
                    .accessibilityIdentifier("brief.urgent")
                } else if let deadline = decision.deadline {
                    Text(deadline).scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.amberText)
                }
            }
            Text(decision.question).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            if let pick = decision.recommended, let why = decision.why {
                VStack(alignment: .leading, spacing: 4) {
                    Label("\(words.recommends): \(pick.words)", systemImage: "sparkle")
                        .scaledFont(13, weight: .semibold).foregroundColor(Theme.coach)
                    Text(why).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.coachWash))
            }
            if !decision.meanwhile.isEmpty {
                (Text(words.meanwhile + " ").bold() + Text(decision.meanwhile))
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            if let why = decision.cantWait {
                Label(why, systemImage: "clock").scaledFont(13).foregroundColor(Theme.no)
                    .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.no.opacity(0.08)))
            }
            answers
            if let note = decision.note {
                Text(note).scaledFont(12).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityIdentifier("brief.decision." + decision.id)
    }

    @ViewBuilder private var answers: some View {
        let pick = decision.recommended
        let others = decision.answers.filter { $0 != pick }
        if let pick {
            Button { model.answerBrief(decision, pick) } label: { Label(pick.words, systemImage: "sparkle") }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("brief.answer." + pick.answer)
        }
        // An urgent decision can't be put off: no Later, whatever was sent.
        let later = decision.urgent ? nil : decision.later
        if !others.isEmpty || later != nil {
            HStack(spacing: 10) {
                ForEach(others) { a in
                    Button(a.words) { model.answerBrief(decision, a) }
                        .buttonStyle(OutlineButtonStyle(color: a.decline ? Theme.no : Theme.ink))
                        .accessibilityIdentifier("brief.answer." + a.answer)
                }
                if let later {
                    Button(later) { model.laterBrief(decision) }
                        .buttonStyle(OutlineButtonStyle())
                        .accessibilityIdentifier("brief.later")
                }
            }
        }
    }
}

/// A case's line: its name, what was done, what it waits on and when it's next checked, as tafs cloud
/// says it. Green once the source says it's closed, amber while it waits on someone.
struct CaseRow: View {
    let item: TafsCloud.Brief.Case

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(item.closed ? Theme.ok : item.waiting ? Theme.amber : Theme.dim)
                .frame(width: 7, height: 7).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                if !item.name.isEmpty { Text(item.name).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink) }
                Text(item.words).scaledFont(13).foregroundColor(item.closed ? Theme.ok : Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A mission's log (design v2.1 §6): read only, newest first, by day. Each entry says when, by whom, its
/// kind and its words, with its evidence and value; a removed taf's entries stay, greyed and marked.
struct MissionLogView: View {
    @EnvironmentObject private var model: AppModel
    let mission: String
    let title: String
    @State private var log: TafsCloud.MissionLog?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if !title.isEmpty {
                    Text(title).scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                }
                if model.mayUseCoach || model.mode == .sample {
                    Button { model.setupDraft = title; model.coachOpen = true } label: { Label(Words.Coach.ask, systemImage: "sparkle") }
                        .buttonStyle(OutlineButtonStyle(color: Theme.coach))
                        .accessibilityIdentifier("mission.ask-coach")
                }
                if let log {
                    ForEach(days(log.items), id: \.title) { day in
                        Eyebrow(text: day.title)
                        ForEach(day.items) { item in LogRow(item: item) }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding(16)
        }
        .screenBackground()
        .task { log = await model.missionLog(mission) }
        .accessibilityIdentifier("mission.log")
    }

    private struct Day { let title: String; var items: [TafsCloud.MissionLog.Item] }

    private func days(_ items: [TafsCloud.MissionLog.Item]) -> [Day] {
        var out: [Day] = []
        for item in items {
            let date = TafsCloud.HomeTaf.time(item.at) ?? Date()
            let title = RecordView.dayTitle(date)
            if out.last?.title == title { out[out.count - 1].items.append(item) } else { out.append(Day(title: title, items: [item])) }
        }
        return out
    }
}

/// One log entry: who, its kind as a coloured word, the time, its words, its evidence and value.
struct LogRow: View {
    let item: TafsCloud.MissionLog.Item

    private var tint: Color {
        switch item.kind {
        case "delivered": return Theme.ok
        case "handed": return Theme.blue
        case "stuck": return Theme.amber
        case "decided": return Theme.ink
        default: return Theme.dim
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Rectangle().fill(item.removed ? Theme.line : tint).frame(width: 3)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(item.byWords.isEmpty ? item.by : item.byWords).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    if !item.kindWords.isEmpty {
                        Text(item.kindWords).scaledFont(11, weight: .medium).foregroundColor(tint)
                            .padding(.horizontal, 6).padding(.vertical, 2).background(Capsule().fill(tint.opacity(0.12)))
                    }
                    Spacer()
                    if let at = TafsCloud.HomeTaf.time(item.at) {
                        Text(at.shortTime).scaledFont(12, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.dim)
                    }
                }
                Text(item.words).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                if !item.evidence.isEmpty || item.value != nil {
                    HStack(spacing: 6) {
                        ForEach(item.evidence) { e in
                            Text(e.words).scaledFont(11, design: .monospaced, relativeTo: .caption).foregroundColor(Theme.coach)
                                .padding(.horizontal, 6).padding(.vertical, 2).background(Capsule().fill(Theme.coachWash))
                        }
                        if let value = item.value { Text(value).scaledFont(12, weight: .semibold).foregroundColor(Theme.ink) }
                    }
                }
            }
            .padding(12)
        }
        .background(Theme.panel)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.line, lineWidth: 1))
        .opacity(item.removed ? 0.5 : 1)
        .accessibilityElement(children: .combine)
    }
}

/// A row of label chips: All, two labels, then "+N" for the rest, which shows them all once tapped. The
/// chosen one is inverted; tapped again, it goes back to All.
struct LabelChips: View {
    let labels: [TafsCloud.Brief.Label]
    let selected: String?
    /// tafs cloud's word for All (`AppModel.labelsAllWords`).
    let all: String
    let choose: (String?) -> Void
    @State private var expanded = false

    var body: some View {
        let shown = expanded ? labels : Array(labels.prefix(2))
        let hidden = labels.count - shown.count
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                LabelChip(words: all, count: nil, waiting: false, selected: selected == nil) { choose(nil) }
                ForEach(shown) { label in
                    LabelChip(words: label.name, count: label.count, waiting: label.waiting > 0, selected: selected == label.id) {
                        choose(selected == label.id ? nil : label.id)
                    }
                }
                if hidden > 0 { LabelChip(words: "+\(hidden)", count: nil, waiting: false, selected: false) { expanded = true } }
            }
            .padding(.vertical, 2)
        }
        .accessibilityIdentifier("labels")
    }
}

/// A taf's labels (Missions draft), as small grey chips under its line: two, then "+N"; nothing without labels.
struct TafLabels: View {
    @EnvironmentObject private var model: AppModel
    let taf: Taf

    var body: some View {
        let labels = model.labels(of: taf)
        if !labels.isEmpty {
            // Two, then "+1" (docs/labels.md: a taf carries at most three).
            HStack(spacing: 4) {
                ForEach(labels.prefix(2)) { label in chip(label.name) }
                if labels.count > 2 { chip("+\(labels.count - 2)") }
            }
            .accessibilityIdentifier("taf.labels")
        }
    }

    private func chip(_ words: String) -> some View {
        Text(words).scaledFont(11, weight: .medium).foregroundColor(Theme.soft)
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(Capsule().fill(Theme.raised))
    }
}
