import SwiftUI

/// The account's usage this period (kiff/tafs#184): its own screen, opened from Settings. What
/// the tafs did that counted, what the plan includes, what wasn't counted and why, the month's
/// ceiling, and the trial. It sells nothing: no price, no purchase and no call to action (App
/// Store Review Guideline 3.1.1), no link to buy; a trial's end or a reached ceiling is said plainly.
struct UsageView: View {
    @EnvironmentObject private var model: AppModel
    /// Read on this screen: an earlier read isn't shown as now (kiff/tafs#206 5.1).
    @State private var read = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if read, let u = model.usage {
                    tasksPanel(u)
                    if let notice = Self.notice(u) {
                        Panel {
                            Text(notice).font(.system(size: 15)).foregroundColor(Theme.ink)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    // Read only: the ceiling is never changed in the app (kiff/tafs#185).
                    if model.mode == .kiff, u.plan != "trial", let ceiling = u.tasks.ceiling, ceiling > 0 {
                        Panel {
                            HStack {
                                Text("Monthly ceiling").font(.system(size: 15)).foregroundColor(Theme.ink)
                                Spacer()
                                Text("\(ceiling) tasks").font(.system(size: 15)).foregroundColor(Theme.soft)
                            }
                        }
                    }
                    if let ai = model.aiUse, let used = ai.usedPct {
                        NavigationLink(destination: AIUseView().environmentObject(model)) {
                            Panel {
                                HStack {
                                    Text("AI use").font(.system(size: 15)).foregroundColor(Theme.ink)
                                    Spacer()
                                    Text(ai.paused ? "Asking you" : "\(used)%").font(.system(size: 15))
                                        .foregroundColor(ai.paused ? Theme.amber : Theme.soft)
                                    Image(systemName: "chevron.right").font(.system(size: 13)).foregroundColor(Theme.dim)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    // Every taf is listed by KIFF, most at 0 on a busy account: only those that counted.
                    if let by = u.tasks.byAgent?.filter({ $0.counted > 0 }), !by.isEmpty {
                        Panel {
                            Eyebrow(text: "By taf")
                            ForEach(by, id: \.agent) { a in
                                CountRow(label: model.tafName(forAgent: a.agent), count: a.counted)
                            }
                        }
                    }
                    if let free = u.tasks.free, !free.lines.isEmpty {
                        Panel {
                            Eyebrow(text: "Not counted")
                            ForEach(free.lines, id: \.0) { line in CountRow(label: line.0, count: line.1) }
                        }
                    }
                    Panel {
                        Eyebrow(text: "Your own agents in KIFF")
                        Text(Self.count(u.actions.counted, of: u.actions.included, "actions")).font(.system(size: 15)).foregroundColor(Theme.ink)
                        Text("Actions your own agents take through KIFF, apart from the tafs.")
                            .font(.system(size: 13)).foregroundColor(Theme.dim)
                    }
                    if !model.countedTasks.isEmpty {
                        Panel {
                            Eyebrow(text: "Counted tasks")
                            ForEach(model.countedTasks) { CountedTaskRow(task: $0) }
                        }
                    }
                } else if let why = model.usageError {
                    Panel { Text(why).font(.system(size: 14)).foregroundColor(Theme.no) }
                } else {
                    ProgressView()
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle("Usage")
        .inlineTitle()
        .task { await model.refreshUsage(); read = true }
        .refreshable { await model.refreshUsage() }
    }

    private func tasksPanel(_ u: KIFFClient.Usage) -> some View {
        Panel {
            Eyebrow(text: Self.planName(u.plan))
            Text(Self.count(u.tasks.counted, of: u.tasks.included, "tasks")).font(Theme.display(26)).foregroundColor(Theme.ink)
            if u.tasks.included > 0 {
                Meter(fraction: Double(u.tasks.counted) / Double(u.tasks.included),
                      color: u.tasks.ceilingReached == true ? Theme.no : Theme.ok)
            }
            Text("\(Self.taskMeaning) This period ends \(u.period.end.formatted(date: .abbreviated, time: .omitted)).")
                .font(.system(size: 13)).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// A taf's counted tasks this period, for its page's Usage row.
    static func tafCount(_ n: Int) -> String {
        n == 1 ? "1 task this period" : "\(n) tasks this period"
    }

    /// What a task is: counted means done (kiff-cloud#1316), as /pricing says it.
    static let taskMeaning = "A task is an action a taf took that KIFF allowed, sent, and that worked. Reads, refusals, failures and lost answers aren't counted."

    /// A counted task's tool as the Record names it: KIFF's title (kiff-cloud#1334), else its
    /// name in words.
    static func toolName(_ t: KIFFClient.CountedTask) -> String {
        if let title = t.toolTitle, !title.isEmpty { return title }
        return toolWords(t.tool)
    }

    /// A tool's name in words, when KIFF gives no title: "refund_order" reads "Refund order".
    static func toolWords(_ tool: String) -> String {
        let words = tool.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
        return words.prefix(1).uppercased() + words.dropFirst()
    }

    /// "14 of 25 tasks", or "472 tasks" where nothing is included because there's no limit
    /// (KIFF's `included: 0`, on our own accounts and Company).
    static func count(_ counted: Int, of included: Int, _ unit: String) -> String {
        included > 0 ? "\(counted) of \(included) \(unit)" : "\(counted) \(unit)"
    }

    static func planName(_ plan: String) -> String {
        switch plan {
        case "trial": return "Trial"
        case "solo": return "Solo"
        case "team": return "Team"
        case "company": return "Company"
        default: return plan.capitalized
        }
    }

    /// What the owner should know now, said neutrally: a reached ceiling, an ended trial, or
    /// what's left of it. Nil when there's nothing to say.
    static func notice(_ u: KIFFClient.Usage) -> String? {
        if u.tasks.ceilingReached == true {
            return u.plan == "trial"
                ? "Your trial's tasks are used. Your tafs are paused."
                : "This month's ceiling is reached. Your tafs are paused until the month ends or the ceiling is raised."
        }
        guard let trial = u.trial else { return nil }
        // It starts at checkout on the web; the app says so and links nowhere (3.1.1).
        guard trial.endsAt != nil else { return "Your trial hasn't started." }
        if trial.daysLeft <= 0 { return "Your trial has ended." }
        let days = trial.daysLeft == 1 ? "1 day" : "\(trial.daysLeft) days"
        let tasks = trial.tasksLeft == 1 ? "1 task" : "\(trial.tasksLeft) tasks"
        return "Your trial: \(days) and \(tasks) left."
    }
}

/// One counted task: the Record's own line for that call when the app holds it, opening its
/// receipt; otherwise the taf and the tool as the Record names it (kiff/tafs#184).
struct CountedTaskRow: View {
    @EnvironmentObject private var model: AppModel
    let task: KIFFClient.CountedTask

    var body: some View {
        if let entry = model.recordEntry(forCall: task.record) {
            RecordRow(entry: entry, showTaf: true)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.tafName(forAgent: task.agent)).font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink)
                Text("\(UsageView.toolName(task)) · \(task.at.formatted(.relative(presentation: .named)))")
                    .font(.system(size: 13)).foregroundColor(Theme.dim)
            }
        }
    }
}

/// One taf's usage this period (kiff/tafs#184): its own screen, from a row on its page. What it
/// counted, what it didn't and why, and its counted tasks; the account's Usage is one tap away.
struct TafUsageView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// Read on this screen: an earlier read isn't shown as now (kiff/tafs#206 5.1).
    @State private var read = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if read, let mine = model.tafUsage(for: tafID) {
                    Panel {
                        Eyebrow(text: model.taf(tafID)?.name ?? "This taf")
                        Text(UsageView.tafCount(mine.counted)).font(Theme.display(26)).foregroundColor(Theme.ink)
                        Text(UsageView.taskMeaning).font(.system(size: 13)).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let free = mine.free, !free.lines.isEmpty {
                        Panel {
                            Eyebrow(text: "Not counted")
                            ForEach(free.lines, id: \.0) { line in CountRow(label: line.0, count: line.1) }
                        }
                    }
                    if let tasks = model.tafTasks[tafID], !tasks.isEmpty {
                        Panel {
                            Eyebrow(text: "Counted tasks")
                            ForEach(tasks) { CountedTaskRow(task: $0) }
                        }
                    }
                    NavigationLink(destination: UsageView().environmentObject(model)) {
                        Panel {
                            HStack {
                                Text("The account's usage").font(.system(size: 15)).foregroundColor(Theme.ink)
                                Spacer()
                                Image(systemName: "chevron.right").font(.system(size: 13)).foregroundColor(Theme.dim)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                } else if let why = model.usageError {
                    Panel { Text(why).font(.system(size: 14)).foregroundColor(Theme.no) }
                } else {
                    ProgressView()
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle("Usage")
        .inlineTitle()
        .task { await model.refreshUsage(); await model.refreshTafUsage(tafID); read = true }
        .refreshable { await model.refreshUsage(); await model.refreshTafUsage(tafID) }
    }
}

private struct CountRow: View {
    let label: String
    let count: Int

    var body: some View {
        HStack {
            Text(label).font(.system(size: 14)).foregroundColor(Theme.ink)
            Spacer()
            Text("\(count)").font(.system(size: 14)).foregroundColor(Theme.soft)
        }
    }
}

/// AI use from chats and Setup this period (kiff/tafs#193): its own screen, read only. It's
/// included, and never billed: past the plan's fair use the tafs ask before going on, and
/// going on is changed for the account outside the app, never in it (kiff/tafs#185). Said
/// neutrally, with no price, nothing to buy and no link.
struct AIUseView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let ai = model.aiUse, let used = ai.usedPct {
                    Panel {
                        Eyebrow(text: "AI use this month")
                        Text("\(used)%").font(Theme.display(26)).foregroundColor(Theme.ink)
                        Meter(fraction: Double(used) / Double(max(ai.asksAtPct ?? 100, 1)), color: ai.paused ? Theme.amber : Theme.ok)
                        Text(Self.explain(ai)).font(.system(size: 13)).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if ai.paused {
                        Panel {
                            Text(Self.asking).font(.system(size: 15)).foregroundColor(Theme.ink)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle("AI use")
        .inlineTitle()
    }

    static let asking = "This month's AI use is high: your tafs ask before going on."

    /// What the share means, and where the tafs ask next.
    static func explain(_ ai: TafsCloud.AIUse) -> String {
        let at = ai.asksAtPct ?? 100
        let next = at <= 100 ? "At 100%, your tafs ask you before going on." : "You said go on; your tafs ask you again at \(at)%."
        return "Chats with your tafs and Setup are included. 100% is this month's fair use. \(next) It's never billed."
    }
}
