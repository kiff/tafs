import SwiftUI

/// The account's usage this period (kiff/tafs#184): its own screen, opened from Settings. What
/// the tafs did that counted, what the plan includes, what wasn't counted and why, the month's
/// ceiling, and the trial. It sells nothing (App Store Review Guideline 3.1.3(f)): no price, no
/// purchase, no link to buy; a trial's end or a reached ceiling is said plainly.
struct UsageView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let u = model.usage {
                    tasksPanel(u)
                    if let notice = Self.notice(u) {
                        Panel {
                            Text(notice).font(.system(size: 15)).foregroundColor(Theme.ink)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    if model.mode == .kiff, u.plan != "trial", let ceiling = u.tasks.ceiling {
                        NavigationLink(destination: CeilingView(current: ceiling, least: max(u.tasks.included, u.tasks.counted)).environmentObject(model)) {
                            Panel {
                                HStack {
                                    Text("Monthly ceiling").font(.system(size: 15)).foregroundColor(Theme.ink)
                                    Spacer()
                                    Text("\(ceiling) tasks").font(.system(size: 15)).foregroundColor(Theme.soft)
                                    Image(systemName: "chevron.right").font(.system(size: 13)).foregroundColor(Theme.dim)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    if let by = u.tasks.byAgent, !by.isEmpty {
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
                        Text("\(u.actions.counted) of \(u.actions.included) actions").font(.system(size: 15)).foregroundColor(Theme.ink)
                        Text("Actions your own agents take through KIFF, apart from the tafs.")
                            .font(.system(size: 13)).foregroundColor(Theme.dim)
                    }
                    if !model.countedTasks.isEmpty {
                        Panel {
                            Eyebrow(text: "Counted tasks")
                            ForEach(model.countedTasks) { t in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(model.tafName(forAgent: t.agent)).font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink)
                                    Text("\(t.tool) · \(t.at.formatted(.relative(presentation: .named)))")
                                        .font(.system(size: 13)).foregroundColor(Theme.dim)
                                }
                            }
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
        .task { await model.refreshUsage() }
        .refreshable { await model.refreshUsage() }
    }

    private func tasksPanel(_ u: KIFFClient.Usage) -> some View {
        Panel {
            Eyebrow(text: Self.planName(u.plan))
            Text("\(u.tasks.counted) of \(u.tasks.included) tasks").font(Theme.display(26)).foregroundColor(Theme.ink)
            Meter(fraction: Double(u.tasks.counted) / Double(max(u.tasks.included, 1)),
                  color: u.tasks.ceilingReached == true ? Theme.no : Theme.ok)
            Text("A task is an action a taf took that KIFF allowed and sent. Reads, refusals and anything that failed aren't counted. This period ends \(u.period.end.formatted(date: .abbreviated, time: .omitted)).")
                .font(.system(size: 13)).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
        }
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
        if trial.daysLeft <= 0 { return "Your trial has ended." }
        let days = trial.daysLeft == 1 ? "1 day" : "\(trial.daysLeft) days"
        let tasks = trial.tasksLeft == 1 ? "1 task" : "\(trial.tasksLeft) tasks"
        return "Your trial: \(days) and \(tasks) left."
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

/// The month's ceiling of tafs tasks: its own screen. At the ceiling the tafs pause and ask;
/// nothing is billed past it. Saving asks for Face ID.
struct CeilingView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var tasks: Int
    @State private var saving = false
    let least: Int

    init(current: Int, least: Int) {
        _tasks = State(initialValue: max(current, least))
        self.least = least
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Panel {
                    Eyebrow(text: "Monthly ceiling")
                    Stepper(value: $tasks, in: least...max(least * 20, 1000), step: 5) {
                        Text("\(tasks) tasks").font(Theme.display(26)).foregroundColor(Theme.ink)
                    }
                    Text("When your tafs reach it, they pause and ask you. Nothing past it is counted.")
                        .font(.system(size: 13)).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let why = model.usageError {
                    Text(why).font(.system(size: 14)).foregroundColor(Theme.no)
                }
                Button(saving ? "Saving…" : "Save with Face ID") {
                    saving = true
                    Task {
                        let done = await model.setTaskCeiling(tasks)
                        saving = false
                        if done { dismiss() }
                    }
                }
                .buttonStyle(OutlineButtonStyle())
                .disabled(saving || tasks == model.usage?.tasks.ceiling)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle("Ceiling")
        .inlineTitle()
    }
}
