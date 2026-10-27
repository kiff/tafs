import SwiftUI

/// The account's usage this period (kiff/tafs#184): its own screen, opened from Settings. The
/// tasks the period has (what the plan includes, and packs) and what's left, who bills the
/// plan, adding tasks or asking an admin for them, what the tafs did that counted, and what
/// wasn't counted and why. Titled as the account's, with each number saying whose it is, so
/// it reads at a glance with no text explaining it (kiff/tafs#245, item 2).
struct UsageView: View {
    @EnvironmentObject private var model: AppModel
    /// Read on this screen: an earlier read isn't shown as now (kiff/tafs#206 5.1).
    @State private var read = false
    @State private var addingTasks = false
    @State private var asking = false
    @State private var declining: KIFFClient.PackRequest?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if read, let u = model.usage {
                    tasksPanel(u)
                    if let notice = Self.notice(u) {
                        Panel {
                            Text(notice).scaledFont(15).foregroundColor(Theme.ink)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    if model.mode == .kiff {
                        tasksActions(u)
                        requestsPanel(u)
                        billingPanel(u)
                    }
                    if let message = model.tasksMessage {
                        Panel { Text(message).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true) }
                    }
                    if let packs = u.packList, !packs.isEmpty {
                        Panel {
                            Eyebrow(text: "Packs")
                            ForEach(packs) { p in
                                HStack {
                                    Text(p.name).scaledFont(14).foregroundColor(Theme.ink)
                                    Spacer()
                                    Text(Self.packLeft(p)).scaledFont(14).foregroundColor(Theme.soft)
                                }
                            }
                        }
                    }
                    if let ai = model.aiUse, let used = ai.usedPct {
                        NavigationLink(destination: AIUseView().environmentObject(model)) {
                            Panel {
                                HStack {
                                    Text("AI use").scaledFont(15).foregroundColor(Theme.ink)
                                    Spacer()
                                    Text(ai.paused ? "Asking you" : "\(used)%").scaledFont(15)
                                        .foregroundColor(ai.paused ? Theme.amber : Theme.soft)
                                    Image(systemName: "chevron.right").scaledFont(13).foregroundColor(Theme.dim)
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
                                CountRow(label: model.tafName(forAgent: a.agent), value: "\(a.counted)")
                            }
                        }
                    }
                    if let free = u.tasks.free, !free.lines.isEmpty {
                        Panel {
                            Eyebrow(text: "Not counted")
                            ForEach(free.lines, id: \.0) { line in CountRow(label: line.0, value: "\(line.1)") }
                        }
                    }
                    // Agents that aren't tafs, named as the home list names them; only when they
                    // counted something (kiff/tafs#245, item 2).
                    if let others = Self.othersLine(u) {
                        Panel {
                            CountRow(label: "Other agents with Cards", value: others)
                        }
                    }
                    if !model.countedTasks.isEmpty {
                        Panel {
                            Eyebrow(text: "Counted tasks")
                            ForEach(model.countedTasks) { CountedTaskRow(task: $0) }
                        }
                    }
                } else if let why = model.usageError {
                    Panel { Text(why).scaledFont(14).foregroundColor(Theme.no) }
                } else {
                    // Loading draws the screen at its full width, never a narrow strip (kiff/tafs#245, item 2).
                    Panel {
                        Eyebrow(text: Self.allTafs)
                        ProgressView().frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(Self.title)
        .inlineTitle()
        .task { await model.refreshUsage(); read = true }
        .refreshable { await model.refreshUsage() }
        .sheet(isPresented: $addingTasks) { AddTasksView().environmentObject(model) }
        .sheet(isPresented: $asking) { AskAdminView().environmentObject(model) }
        .sheet(item: $declining) { DeclineRequestView(request: $0).environmentObject(model) }
    }

    private func tasksPanel(_ u: KIFFClient.Usage) -> some View {
        Panel {
            Eyebrow(text: Self.whose(u))
            Text(Self.count(u.tasks.counted, of: u.tasks.left == nil ? 0 : u.tasks.total, "tasks")).scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
            if u.tasks.left != nil, u.tasks.total > 0 {
                Meter(fraction: Double(u.tasks.counted) / Double(u.tasks.total),
                      color: u.tasks.usedUp ? Theme.no : Theme.ok)
            }
            if let left = Self.leftLine(u) {
                Text(left).scaledFont(15, weight: .semibold).foregroundColor(u.tasks.usedUp ? Theme.no : Theme.ink)
            }
            Text(Self.periodEnds(u.period.end)).scaledFont(13).foregroundColor(Theme.soft)
        }
    }

    /// Adding tasks, for someone who may; asking an admin, or waiting on the answer, for a
    /// member. Only where tasks have a limit.
    @ViewBuilder private func tasksActions(_ u: KIFFClient.Usage) -> some View {
        if u.tasks.left != nil, u.store != nil {
            if u.canAddTasks == true {
                if u.tasks.usedUp {
                    Button("Add tasks") { addingTasks = true }.buttonStyle(PrimaryButtonStyle())
                } else {
                    Button("Add tasks") { addingTasks = true }.buttonStyle(OutlineButtonStyle())
                }
                if Self.offersSolo(u) {
                    NavigationLink(destination: SoloView().environmentObject(model)) {
                        Panel {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Solo").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                                    Text("Tafs and KIFF for one person, every month.").scaledFont(13).foregroundColor(Theme.dim)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").scaledFont(13).foregroundColor(Theme.dim)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            } else if let mine = Self.waiting(u) {
                Panel {
                    Eyebrow(text: "Waiting")
                    Text("You asked for \(Self.packLine(mine.pack, in: u)). An admin answers.").scaledFont(15).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Withdraw") { Task { await model.withdrawRequest(mine) } }.buttonStyle(OutlineButtonStyle())
                }
            } else {
                if let answer = Self.lastAnswer(u) {
                    Panel { Text(answer).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true) }
                }
                if u.tasks.usedUp {
                    Button("Ask an admin") { asking = true }.buttonStyle(PrimaryButtonStyle())
                } else {
                    Button("Ask an admin") { asking = true }.buttonStyle(OutlineButtonStyle())
                }
            }
        }
    }

    /// Members' requests waiting for an answer, for someone who may add tasks: approving buys
    /// the pack.
    @ViewBuilder private func requestsPanel(_ u: KIFFClient.Usage) -> some View {
        let open = Self.openRequests(u)
        if !open.isEmpty {
            Panel(stroke: Theme.amber.opacity(0.5)) {
                Eyebrow(text: "Requests", color: Theme.amber)
                ForEach(open) { r in
                    VStack(alignment: .leading, spacing: 8) {
                        Text("A member asks for \(Self.packLine(r.pack, in: u)).").scaledFont(15).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        if !r.reason.isEmpty {
                            Text(r.reason).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        }
                        HStack(spacing: 10) {
                            Button("Approve") {
                                guard let pack = u.store?.packs.first(where: { $0.id == r.pack }) else { return }
                                Task { await model.buyPack(pack, answering: r) }
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(model.purchases.buying != nil)
                            Button("Decline") { declining = r }.buttonStyle(OutlineButtonStyle())
                        }
                    }
                }
            }
        }
    }

    /// Who bills the plan, in one line; Apple's with Manage. After a move from Solo to Team,
    /// while Solo still renews with Apple, a line says so with Apple's own page to cancel it:
    /// the app never points anywhere else to buy.
    @ViewBuilder private func billingPanel(_ u: KIFFClient.Usage) -> some View {
        if let line = Self.billingLine(u) {
            Panel {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text(line).scaledFont(15).foregroundColor(Theme.ink)
                        Spacer()
                        if Self.managedByApple(u) {
                            Button("Manage") { Task { await Purchases.manageSubscriptions() } }
                                .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                        }
                    }
                    if let renews = Self.soloStillRenews(u) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(renews).scaledFont(14).foregroundColor(Theme.soft)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 8)
                            Button("Cancel Solo") { Task { await Purchases.manageSubscriptions() } }
                                .scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                        }
                    }
                }
            }
        }
    }

    /// "Your Solo in the App Store renews on 3 November…" after a move to Team; nil otherwise.
    static func soloStillRenews(_ u: KIFFClient.Usage) -> String? {
        guard u.billing?.source == "stripe", let until = u.billing?.appleSoloUntil else { return nil }
        return "Team is billed by KIFF now. Your Solo in the App Store renews on \(until.shown(date: .long, time: .omitted)) unless you cancel it."
    }

    /// One line on who bills the plan; nil when no plan is billed.
    static func billingLine(_ u: KIFFClient.Usage) -> String? {
        switch u.billing?.source {
        case "apple": return "Billed by Apple"
        case "stripe": return "Billed by KIFF"
        case "contract": return "Managed by your company's agreement"
        default: return nil
        }
    }

    static func managedByApple(_ u: KIFFClient.Usage) -> Bool { u.billing?.source == "apple" }

    /// Whether the app offers Solo: to someone who may add tasks, on an account with no plan
    /// billed (the trial, or one that ended). Never to one billed by KIFF or an agreement.
    static func offersSolo(_ u: KIFFClient.Usage) -> Bool {
        guard u.canAddTasks == true, u.store != nil else { return false }
        return (u.billing?.source ?? "").isEmpty && u.plan == "trial"
    }

    /// The member's own request still waiting.
    static func waiting(_ u: KIFFClient.Usage) -> KIFFClient.PackRequest? {
        guard u.canAddTasks != true else { return nil }
        return u.requests?.first { $0.status == "open" }
    }

    /// The member's latest answered request, said plainly.
    static func lastAnswer(_ u: KIFFClient.Usage) -> String? {
        guard u.canAddTasks != true, let r = u.requests?.first else { return nil }
        let pack = packLine(r.pack, in: u)
        switch r.status {
        case "approved": return "An admin added \(pack)."
        case "declined":
            if let note = r.note, !note.isEmpty { return "An admin declined \(pack): \(note)" }
            return "An admin declined \(pack)."
        case "expired": return "Your request for \(pack) ended with the period."
        default: return nil
        }
    }

    /// Open requests, for someone who may add tasks.
    static func openRequests(_ u: KIFFClient.Usage) -> [KIFFClient.PackRequest] {
        guard u.canAddTasks == true else { return [] }
        return (u.requests ?? []).filter { $0.status == "open" }
    }

    /// A pack's line, "Rayo · 70 tasks", from what's on sale.
    static func packLine(_ id: String, in u: KIFFClient.Usage?) -> String {
        u?.store?.packs.first { $0.id == id }?.line ?? id.capitalized
    }

    /// "60 of 70 left, until 22 Oct 2027".
    static func packLeft(_ p: KIFFClient.Usage.HeldPack) -> String {
        "\(p.tasksLeft) of \(p.tasks) left, until \(p.expiresAt.shown(date: .abbreviated, time: .omitted))"
    }

    /// "65 tasks left"; nil where tasks have no limit.
    static func leftLine(_ u: KIFFClient.Usage) -> String? {
        guard let left = u.tasks.left else { return nil }
        let line = left == 1 ? "1 task left" : "\(left) tasks left"
        guard let packs = u.tasks.packs, packs > 0 else { return line }
        return "\(line) · \(u.tasks.included) included, \(packs) from packs"
    }

    /// A taf's counted tasks this period, for its page's Usage row.
    static func tafCount(_ n: Int) -> String {
        "\(tasks(n)) this period"
    }

    /// "1 task", "8 tasks".
    static func tasks(_ n: Int) -> String {
        n == 1 ? "1 task" : "\(n) tasks"
    }

    /// The account's screen is the account's, apart from a taf's own (kiff/tafs#245, item 2).
    static let title = "Account usage"

    /// Whose the account's numbers are: every taf's together.
    static let allTafs = "All tafs"

    /// "All tafs · Trial": whose the count is, and the plan where it has a name to show.
    static func whose(_ u: KIFFClient.Usage) -> String {
        planName(u.plan).map { "\(allTafs) · \($0)" } ?? allTafs
    }

    /// "This period ends 22 Nov 2026."
    static func periodEnds(_ end: Date) -> String {
        "This period ends \(end.shown(date: .abbreviated, time: .omitted))."
    }

    /// What agents that aren't tafs did through KIFF ("3 actions", "3 of 500 actions"); nil
    /// when they did nothing, so an empty line never asks the owner what 0 means.
    static func othersLine(_ u: KIFFClient.Usage) -> String? {
        u.actions.counted > 0 ? count(u.actions.counted, of: u.actions.included, "actions") : nil
    }

    /// A counted task's tool as the Record names it: KIFF's title, else its name in words.
    static func toolName(_ t: KIFFClient.CountedTask) -> String {
        if let title = t.toolTitle, !title.isEmpty { return title }
        return toolWords(t.tool)
    }

    /// A tool's name in words, when KIFF gives no title: "refund_order" reads "Refund order".
    static func toolWords(_ tool: String) -> String {
        let words = tool.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
        return words.prefix(1).uppercased() + words.dropFirst()
    }

    /// "14 of 25 tasks", or "472 tasks" where there's no limit (KIFF's `included: 0`, on our
    /// own accounts and Company).
    static func count(_ counted: Int, of included: Int, _ unit: String) -> String {
        included > 0 ? "\(counted) of \(included) \(unit)" : "\(counted) \(unit)"
    }

    /// The plan's name as the owner bought it; nil for one that isn't sold (our own
    /// accounts' "internal"), which has no name to show (kiff/tafs#245, item 2).
    static func planName(_ plan: String) -> String? {
        switch plan {
        case "trial": return "Trial"
        case "solo": return "Solo"
        case "team": return "Team"
        case "company": return "Company"
        default: return nil
        }
    }

    /// What the account should know now: tasks used up, an ended trial, or what's left of it.
    /// Nil when there's nothing to say.
    static func notice(_ u: KIFFClient.Usage) -> String? {
        if u.tasks.usedUp {
            return u.plan == "trial"
                ? "Your trial's tasks are used. Your tafs are paused until tasks are added."
                : "This period's tasks are used. Your tafs are paused until tasks are added or the next period starts."
        }
        guard let trial = u.trial else { return nil }
        guard trial.endsAt != nil else { return "Your trial hasn't started." }
        if trial.daysLeft <= 0 { return "Your trial has ended." }
        let days = trial.daysLeft == 1 ? "1 day" : "\(trial.daysLeft) days"
        let left = u.tasks.left ?? trial.tasksLeft
        let tasks = left == 1 ? "1 task" : "\(left) tasks"
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
                Text(model.tafName(forAgent: task.agent)).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                Text("\(UsageView.toolName(task)) · \(task.at.formatted(.relative(presentation: .named)))")
                    .scaledFont(13).foregroundColor(Theme.dim)
            }
        }
    }
}

/// One taf's usage this period (kiff/tafs#184): its own screen, from a row on its page. What it
/// counted, what it didn't and why, and its counted tasks; the account's Usage is one tap away.
/// Titled with the taf's name, and the row to the account's carries all tafs' count, so its
/// own count never reads as the account's (kiff/tafs#245, item 2).
struct TafUsageView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// Read on this screen: an earlier read isn't shown as now (kiff/tafs#206 5.1).
    @State private var read = false

    private var name: String { model.taf(tafID)?.name ?? "This taf" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if read, let mine = model.tafUsage(for: tafID) {
                    Panel {
                        Eyebrow(text: name)
                        Text(UsageView.tasks(mine.counted)).scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                        if let end = model.usage?.period.end {
                            Text(UsageView.periodEnds(end)).scaledFont(13).foregroundColor(Theme.soft)
                        }
                    }
                    if let free = mine.free, !free.lines.isEmpty {
                        Panel {
                            Eyebrow(text: "Not counted")
                            ForEach(free.lines, id: \.0) { line in CountRow(label: line.0, value: "\(line.1)") }
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
                                Text(UsageView.allTafs).scaledFont(15).foregroundColor(Theme.ink)
                                Spacer()
                                if let all = model.usage?.tasks.counted {
                                    Text(UsageView.tasks(all)).scaledFont(15).foregroundColor(Theme.soft)
                                }
                                Image(systemName: "chevron.right").scaledFont(13).foregroundColor(Theme.dim)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                } else if let why = model.usageError {
                    Panel { Text(why).scaledFont(14).foregroundColor(Theme.no) }
                } else {
                    Panel {
                        Eyebrow(text: name)
                        ProgressView().frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(name)
        .inlineTitle()
        .task { await model.refreshUsage(); await model.refreshTafUsage(tafID); read = true }
        .refreshable { await model.refreshUsage(); await model.refreshTafUsage(tafID) }
    }
}

private struct CountRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label).scaledFont(14).foregroundColor(Theme.ink)
            Spacer()
            Text(value).scaledFont(14).foregroundColor(Theme.soft)
        }
    }
}

/// AI use from chats and Setup this period (kiff/tafs#193): its own screen, read only. It's
/// included, and never billed: past the plan's fair use the tafs ask before going on.
struct AIUseView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let ai = model.aiUse, let used = ai.usedPct {
                    Panel {
                        Eyebrow(text: "AI use this month")
                        Text("\(used)%").scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                        Meter(fraction: Double(used) / Double(max(ai.asksAtPct ?? 100, 1)), color: ai.paused ? Theme.amber : Theme.ok)
                        Text(Self.explain(ai)).scaledFont(13).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if ai.paused {
                        Panel {
                            Text(Self.asking).scaledFont(15).foregroundColor(Theme.ink)
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
