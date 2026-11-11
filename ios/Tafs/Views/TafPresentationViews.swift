import SwiftUI

struct CardUnitExceptionPanel: View {
    @EnvironmentObject private var model: AppModel
    let ask: TafsCloud.CardUnitException
    private var canDiscuss: Bool {
        model.mayUseCoach && model.tafs.contains { model.cloudTaf(for: $0.id)?.id == ask.taf && model.manages($0.id) }
    }
    var body: some View {
        Panel {
            Text(ask.title).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
            Text(ask.words).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            Text(ask.history).scaledFont(12).foregroundColor(Theme.dim)
            if canDiscuss { Button(ask.action_title) { model.discussCardUnit(ask) } }
            if model.canAnswerHolds != false {
                Button(ask.dismiss_title, role: .destructive) { Task { await model.dismissCardUnit(ask) } }
                    .confirming()
                    .disabled(model.dismissingCardUnits.contains(ask.id))
            }
        }
    }
}

/// The service supplies the names, mappings, counts, limits and summaries for both apps.
struct ConnectedToolsRing: View {
    @EnvironmentObject private var model: AppModel
    let taf: Taf
    let activity: (String) -> Void
    @State private var app: TafsCloud.ConnectedApp?
    @State private var job = false
    @State private var workflow = false
    @State private var connect = false

    var body: some View {
        let source = model.tafSetups[taf.id]
        Panel {
            Eyebrow(text: Words.Connect.connectedTools)
            if let error = model.tafSetupErrors[taf.id] ?? source?.connections_error {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
                Button(Words.Common.tryAgain) { Task { await model.loadTafSetup(taf.id) } }
            }
            if source == nil && model.tafSetupErrors[taf.id] == nil { ProgressView() }
            if let source {
                ring(source.connections)
                DisclosureGroup(isExpanded: $workflow) {
                    ForEach(source.workflow.steps) { step in
                        HStack(alignment: .top) {
                            Text("\(step.number)").foregroundColor(step.running ? Theme.ok : Theme.soft)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(step.label).scaledFont(15, weight: .semibold)
                                if !step.words.isEmpty { Text(step.words).scaledFont(13).foregroundColor(Theme.soft) }
                                if let app = step.app { Text(app).scaledFont(12).foregroundColor(Theme.amberText) }
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 4)
                    }
                    if model.manages(taf.id) { Button(Words.Taf.Workflow.changeWithCoach) { coach() } }
                } label: {
                    VStack(alignment: .leading) { Text(source.workflow.title); Text(source.workflow.summary).scaledFont(13).foregroundColor(Theme.soft) }
                }
            }
        }
        .sheet(item: $app) { chosen in
            ConnectedAppSheet(tafID: taf.id, app: chosen, activity: { app = nil; activity($0) }).environmentObject(model)
                .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $connect) { NavStack { ConnectAppsView().environmentObject(model) } }
        .alert(taf.name, isPresented: $job) { Button(Words.Common.done, role: .cancel) {} } message: { Text(taf.job) }
    }

    private func coach() { model.improveTaf(taf.id); model.coachOpen = true }
    private func ring(_ apps: [TafsCloud.ConnectedApp]) -> some View {
        GeometryReader { g in
            let count = apps.count + (model.manages(taf.id) ? 1 : 0)
            let center = CGPoint(x: g.size.width / 2, y: g.size.height / 2)
            let radius = min(g.size.width, g.size.height) / 2 - 36
            ZStack {
                ForEach(Array(apps.enumerated()), id: \.element.id) { index, item in
                    let angle = -Double.pi / 2 + Double(index) * 2 * .pi / Double(max(count, 1))
                    let point = CGPoint(x: center.x + CGFloat(cos(angle)) * radius, y: center.y + CGFloat(sin(angle)) * radius)
                    Path { p in p.move(to: center); p.addLine(to: point) }.stroke(Theme.line, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    if item.running {
                        Circle().trim(from: 0, to: 0.1).stroke(Theme.ok, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                            .rotationEffect(.radians(angle)).frame(width: radius * 2, height: radius * 2).position(center)
                    }
                    Button { app = item } label: {
                        VStack(spacing: 4) {
                            Text(item.name).scaledFont(13, weight: .semibold).lineLimit(2)
                            Text(item.action_words).scaledFont(11).foregroundColor(item.running ? Theme.ok : Theme.soft).lineLimit(2)
                        }.frame(width: 78).padding(5).background(Theme.panel).clipShape(RoundedRectangle(cornerRadius: 14))
                    }.buttonStyle(.drivablePlain).position(point)
                }
                if model.manages(taf.id) {
                    let angle = -Double.pi / 2 + Double(apps.count) * 2 * .pi / Double(max(count, 1))
                    Button { connect = true } label: {
                        Image(systemName: "plus").scaledFont(24).frame(width: 46, height: 46)
                            .background(Theme.raised).clipShape(Circle()).overlay(Circle().stroke(Theme.line, style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
                    }.accessibilityLabel(Words.Connect.connectAnApp).position(x: center.x + CGFloat(cos(angle)) * radius, y: center.y + CGFloat(sin(angle)) * radius)
                }
                Button { job = true } label: {
                    Text(taf.initial).scaledFont(27, weight: .bold, design: .rounded).foregroundColor(.white)
                        .frame(width: 54, height: 54).background(Theme.tafColor(taf.colorIndex)).clipShape(RoundedRectangle(cornerRadius: 16))
                }.accessibilityLabel(Words.Taf.jobLabel(taf: "\(taf.name)")).position(center)
            }
        }.frame(height: 280)
    }
}

private struct ConnectedAppSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let tafID: UUID
    let app: TafsCloud.ConnectedApp
    let activity: (String) -> Void
    @State private var disconnect = false
    @State private var busy = false
    @State private var replacing: PlanqueClient.Grant?
    var body: some View {
        NavStack {
            List {
                Section {
                    Text(app.status_words).foregroundColor(app.reconnect ? Theme.amberText : Theme.soft)
                    if app.reconnect, model.manages(tafID) {
                        Button(Words.Connect.signInAgain) { Task { await model.reconnectApp(app.id); await model.loadTafSetup(tafID) } }
                    }
                    // Gap 13, where an owner whose taf fails looks first: its last failed call, and
                    // how to fix the connection.
                    if let g = model.grants.first(where: { $0.id == app.id }) {
                        if let failed = model.lastFailure(g) { Text(failed).scaledFont(13).foregroundColor(Theme.no) }
                        if !app.reconnect, model.manages(tafID), g.connected || g.needsReconnect {
                            if g.signsInThroughProvider {
                                Text(ConnectionFixWords.throughProvider(g.vendor)).scaledFont(13).foregroundColor(Theme.soft)
                            } else {
                                Button(g.takesKey ? ConnectionFixWords.replaceKey : ConnectionFixWords.signInAgain) {
                                    if g.takesKey { replacing = g } else { Task { await model.reconnect(g); await model.loadTafSetup(tafID) } }
                                }
                                .disabled(model.connecting != nil)
                                .accessibilityIdentifier("taf.fix." + g.id)
                                .confirming()
                            }
                        }
                    }
                    if let error = model.reconnectErrors[app.id] { Text(error).foregroundColor(Theme.no) }
                }
                Section(Words.Taf.App.whatItMayDo) {
                    ForEach(app.actions) { action in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(action.title).scaledFont(16, weight: .semibold)
                            ForEach(Array(action.limits.enumerated()), id: \.offset) { _, line in Text(line).scaledFont(13).foregroundColor(Theme.soft) }
                            if !action.step_words.isEmpty {
                                Text(action.step_words).scaledFont(13).foregroundColor(Theme.dim)
                            }
                        }
                    }
                }
                Section(Words.Taf.Workflow.title) {
                    if let words = app.workflow_words { Text(words).foregroundColor(Theme.dim) }
                    ForEach(app.workflow_steps) { step in Text(step.words.isEmpty ? "\(step.number) · \(step.label)" : "\(step.number) · \(step.label)\n\(step.words)") }
                }
                Section {
                    Button(app.stats.words) { activity(app.stats.activity_query) }.disabled(app.actions.isEmpty)
                }
                if model.manages(tafID) {
                    Section {
                        Button(Words.Taf.App.addActions) { dismiss(); model.improveTaf(tafID); model.coachOpen = true }
                        Button(Words.Connect.disconnectButton, role: .destructive) { disconnect = true }.confirming().disabled(busy)
                    }
                }
            }
            .navigationTitle(app.name).screenBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(Words.Common.done) { dismiss() } } }
            .sheet(item: $replacing) { g in
                ConnectApprovalSheet(start: .init(grant: g.id, needsKey: true)).environmentObject(model)
            }
            .confirmationDialog(Words.Connect.disconnectAppQuestion(app: "\(app.name)"), isPresented: $disconnect, titleVisibility: .visible) {
                Button(Words.Connect.disconnectButton, role: .destructive) {
                    busy = true
                    Task {
                        defer { busy = false }
                        if await model.disconnect(grantID: app.id) { await model.loadTafSetup(tafID); dismiss() }
                    }
                }
            } message: { Text(Words.Connect.disconnectBody) }
        }
    }
}

struct TafActivityList: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @Binding var selection: String
    @State private var filter = "all"

    /// What a taf's Activity draws above its record, in order, as on Android: what needs the owner
    /// first, then how it's doing, then its runs. This is the Activity a taf on tafs cloud uses
    /// (`TafDetailView.pane`), so its work (health and its fixes, its oldest waiting case, Answer
    /// within, skipped firings, requests) and its runs (when it runs next, Run now) are drawn here;
    /// the runs it kept stand for its latest run's report. The older `activity(_:)` view is the
    /// sample's alone. A taf's knocks are in its chat.
    enum Part: Hashable { case held, cardUnits, work, runs, retainedRuns }
    static func parts(cloud: Bool) -> [Part] {
        cloud ? [.held, .cardUnits, .work, .runs, .retainedRuns] : [.held, .cardUnits, .retainedRuns]
    }

    var body: some View {
        let cache = model.activityPages[model.activityKey(tafID, filter, selection)] ?? SourcePages()
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                ForEach(Self.parts(cloud: model.cloudTaf(for: tafID) != nil), id: \.self) { part in
                    switch part {
                    case .held:
                        ForEach(model.heldCalls(for: tafID)) { HeldCallCard(call: $0) }
                    case .cardUnits:
                        ForEach(cache.pages.last?.card_unit_exceptions ?? model.cardUnitExceptions.filter { $0.taf == model.cloudTaf(for: tafID)?.id }) { exception in CardUnitExceptionPanel(ask: exception) }
                        if let notice = cache.pages.last?.card_unit_exceptions_notice { Text(notice).scaledFont(13).foregroundColor(Theme.dim) }
                    case .work:
                        WorkPanel(tafID: tafID)
                    case .runs:
                        RunsPanel(tafID: tafID)
                    case .retainedRuns:
                        RetainedRuns(tafID: tafID)
                    }
                }
                if !selection.isEmpty { Button(Words.Taf.Activity.allApps) { selection = "" } }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(cache.pages.first?.filters ?? []) { choice in
                            Button(choice.title) { filter = choice.id }
                                .buttonStyle(.bordered).tint(filter == choice.id ? Theme.amberText : Theme.soft)
                                .accessibilityAddTraits(filter == choice.id ? .isSelected : [])
                        }
                    }
                }
                ForEach(Array(cache.pages.enumerated()), id: \.offset) { _, page in
                    ForEach(page.groups) { group in
                        if !group.title.isEmpty { Text(group.title).scaledFont(12, weight: .semibold).foregroundColor(Theme.dim) }
                        ForEach(group.entries) { entry in SourceActivityRow(tafID: tafID, entry: entry) }
                    }
                }
                if let error = cache.error {
                    Text(error).foregroundColor(Theme.no)
                    Button(Words.Common.tryAgain) { Task { await model.loadActivityPage(tafID, filter: filter, more: !cache.pages.isEmpty, selection: selection) } }
                } else if let cursor = cache.pages.last?.next_cursor {
                    ProgressView().frame(maxWidth: .infinity)
                        .task(id: cursor) { await model.loadActivityPage(tafID, filter: filter, more: true, selection: selection) }
                } else if cache.loading { ProgressView() }
                else if let words = cache.pages.last?.end_words { Text(words).foregroundColor(Theme.dim) }
                else if cache.pages.allSatisfy({ $0.entries.isEmpty }) { Text(Words.Record.nothingHere).foregroundColor(Theme.dim) }
            }.padding(16)
        }
        .task(id: "\(tafID):\(filter):\(selection)") { await model.loadActivityPage(tafID, filter: filter, selection: selection) }
        .refreshable { await model.loadActivityPage(tafID, filter: filter, selection: selection); await model.loadRunPage(tafID) }
    }
}

/// A call as a taf's Activity draws it: its receipt, and its own next step. The Record's search
/// and its cases draw their calls with it too.
struct SourceActivityRow: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    let entry: TafsCloud.ActivityEntry
    @State private var receipt = false
    /// Only the evidence codec is adapted. Its displayed result and words stay server-owned.
    private var evidence: RecordEntry? {
        guard let actor = entry.call.actorID, var result = KIFFMapping.record([entry.call], ids: [actor: tafID]).first else { return nil }
        result.kind = RecordEntry.Kind(rawValue: entry.kind) ?? .unknown
        result.title = entry.title; result.detail = entry.detail
        return result
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { receipt = true } label: {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entry.title).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(entry.detail).scaledFont(13).foregroundColor(Theme.soft)
                    }
                    Spacer()
                    VStack(alignment: .trailing) {
                        Text(entry.time).scaledFont(12).foregroundColor(Theme.dim)
                        if let amount = entry.amount_text { Text(amount).scaledFont(13, weight: .semibold).foregroundColor(Theme.ink) }
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 5).contentShape(Rectangle())
            }.buttonStyle(.drivablePlain).accessibilityHint(Words.Record.showsReceipt)
            if let next = entry.next { NextStepRow(tafID: tafID, entry: entry, next: next) }
        }
        .sheet(isPresented: $receipt) { if let evidence { ReceiptView(entry: evidence).environmentObject(model) } }
    }
}

/// A not-done call's next step, as tafs cloud says it (kiff/tafs#476, gap 10): Connect it again
/// (signing in again where its vendor signed it out, else Connect apps), Ask Coach (Coach on
/// this taf, its first message written in, nothing sent), or a line. Only someone who manages
/// the taf gets a button; anyone else, and a kind this app doesn't know, reads the words alone.
private struct NextStepRow: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    let entry: TafsCloud.ActivityEntry
    let next: TafsCloud.NextStep
    @State private var connect = false
    @State private var busy = false
    var body: some View {
        let action = model.nextStep(next, tafID: tafID, fallback: "\(entry.title): \(entry.detail)")
        let may: Bool = {
            guard model.mode == .kiff, model.manages(tafID) else { return false }
            if case .askCoach = action { return model.mayUseCoach }
            return action != .line
        }()
        if may {
            Button(next.words) {
                switch action {
                case .signInAgain(let grant):
                    busy = true
                    Task { await model.reconnect(grant); busy = false }
                case .askCoach(let words): model.askCoachAbout(tafID, words: words)
                case .connectApps: connect = true
                case .line: break
                }
            }
            .buttonStyle(OutlineButtonStyle()).disabled(busy)
            .sheet(isPresented: $connect) { NavStack { ConnectAppsView().environmentObject(model) } }
        } else {
            Text(next.words).scaledFont(13).foregroundColor(Theme.soft)
        }
    }
}

struct RetainedRuns: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    var all = false
    @State private var showAll = false
    var body: some View {
        Group {
            if all { LazyVStack(alignment: .leading, spacing: 14) { content } }
            else { Panel { content } }
        }
        .task(id: tafID) { await model.loadRunPage(tafID, all: all) }
        .sheet(isPresented: $showAll) {
            NavStack { ScrollView { RetainedRuns(tafID: tafID, all: true).padding(16) }.navigationTitle(Words.Taf.itsWork) }.environmentObject(model)
        }
    }
    @ViewBuilder private var content: some View {
        let cache = model.runPages[model.runKey(tafID, all)] ?? SourcePages()
        Eyebrow(text: cache.pages.first?.title ?? Words.Taf.itsWork)
        ForEach(Array(cache.pages.enumerated()), id: \.offset) { _, page in
            ForEach(page.requests) { request in
                NavigationLink { KnobRequestDetail(tafID: tafID, run: request.id) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(request.line).scaledFont(14)
                        ForEach(Array(request.deliveries.enumerated()), id: \.offset) { _, delivery in Text(delivery.line).scaledFont(12).foregroundColor(Theme.dim) }
                    }
                }
            }
        }
        if let error = cache.error { Text(error).foregroundColor(Theme.no); Button(Words.Common.tryAgain) { Task { await model.loadRunPage(tafID, all: all) } } }
        if all, let cursor = cache.pages.last?.next_cursor, cache.error == nil {
            ProgressView().task(id: cursor) { await model.loadRunPage(tafID, all: true, more: true) }
        } else if !all, let title = cache.pages.first?.more_title { Button(title) { showAll = true } }
        if cache.loading { ProgressView() }
        else if all, cache.error == nil, let words = cache.pages.last?.end_words { Text(words).foregroundColor(Theme.dim) }
    }
}

struct MemorySummaryPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    var body: some View {
        Panel {
            Eyebrow(text: Words.Taf.Memory.title)
            if let memory = model.tafSetups[tafID]?.memory {
                Text(memory.title).scaledFont(15, weight: .semibold)
                if let latest = memory.latest { Text(latest).scaledFont(14).foregroundColor(Theme.soft).lineLimit(2) }
                NavigationLink(Words.Taf.Memory.seeAll) {
                    ScrollView { MemoryPanel(tafID: tafID, loadAll: true).padding(16) }.navigationTitle(Words.Taf.Memory.title)
                        .onDisappear { Task { await model.loadTafSetup(tafID) } }
                }
            } else { ProgressView() }
        }
    }
}
