import SwiftUI

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
            Eyebrow(text: "Connected tools")
            if let error = model.tafSetupErrors[taf.id] ?? source?.connections_error {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
                Button("Try again") { Task { await model.loadTafSetup(taf.id) } }
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
                                Text(step.words).scaledFont(13).foregroundColor(Theme.soft)
                                if let app = step.app { Text(app).scaledFont(12).foregroundColor(Theme.amberText) }
                            }
                        }.padding(.vertical, 4)
                    }
                    if model.manages(taf.id) { Button("Change the workflow with Coach") { coach() } }
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
        .alert(taf.name, isPresented: $job) { Button("Done", role: .cancel) {} } message: { Text(taf.job) }
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
                    }.buttonStyle(.plain).position(point)
                }
                if model.manages(taf.id) {
                    let angle = -Double.pi / 2 + Double(apps.count) * 2 * .pi / Double(max(count, 1))
                    Button { connect = true } label: {
                        Image(systemName: "plus").scaledFont(24).frame(width: 46, height: 46)
                            .background(Theme.raised).clipShape(Circle()).overlay(Circle().stroke(Theme.line, style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
                    }.accessibilityLabel("Connect an app").position(x: center.x + CGFloat(cos(angle)) * radius, y: center.y + CGFloat(sin(angle)) * radius)
                }
                Button { job = true } label: {
                    Text(taf.initial).scaledFont(27, weight: .bold, design: .rounded).foregroundColor(.white)
                        .frame(width: 54, height: 54).background(Theme.tafColor(taf.colorIndex)).clipShape(RoundedRectangle(cornerRadius: 16))
                }.accessibilityLabel("\(taf.name)'s job").position(center)
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
    var body: some View {
        NavStack {
            List {
                Section("What it may do") {
                    ForEach(app.actions) { action in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(action.title).scaledFont(16, weight: .semibold)
                            ForEach(Array(action.limits.enumerated()), id: \.offset) { _, line in Text(line).scaledFont(13).foregroundColor(Theme.soft) }
                            Text(action.step_words).scaledFont(13).foregroundColor(Theme.dim)
                        }
                    }
                }
                Section("Workflow") {
                    ForEach(app.workflow_steps) { step in Text("\(step.number) · \(step.label)\n\(step.words)") }
                }
                Section {
                    Button(app.stats.words) { activity(app.stats.activity_query) }.disabled(app.actions.isEmpty)
                }
                if model.manages(tafID) {
                    Section {
                        Button("Add actions with Coach") { dismiss(); model.improveTaf(tafID); model.coachOpen = true }
                        Button("Disconnect", role: .destructive) { disconnect = true }.disabled(busy)
                    }
                }
            }
            .navigationTitle(app.name).screenBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Disconnect \(app.name)?", isPresented: $disconnect, titleVisibility: .visible) {
                Button("Disconnect", role: .destructive) {
                    busy = true
                    Task {
                        defer { busy = false }
                        if await model.disconnect(grantID: app.id) { await model.loadTafSetup(tafID); dismiss() }
                    }
                }
            } message: { Text("Its actions stop working for this taf.") }
        }
    }
}

struct TafActivityList: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @Binding var selection: String
    @State private var filter = "all"
    var body: some View {
        let cache = model.activityPages[model.activityKey(tafID, filter, selection)] ?? SourcePages()
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                ForEach(model.heldCalls(for: tafID)) { HeldCallCard(call: $0) }
                RetainedRuns(tafID: tafID)
                if !selection.isEmpty { Button("All apps") { selection = "" } }
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
                    Button("Try again") { Task { await model.loadActivityPage(tafID, filter: filter, more: !cache.pages.isEmpty, selection: selection) } }
                } else if let cursor = cache.pages.last?.next_cursor {
                    ProgressView().frame(maxWidth: .infinity)
                        .task(id: cursor) { await model.loadActivityPage(tafID, filter: filter, more: true, selection: selection) }
                } else if cache.loading { ProgressView() }
                else if cache.pages.allSatisfy({ $0.entries.isEmpty }) { Text("Nothing here.").foregroundColor(Theme.dim) }
            }.padding(16)
        }
        .task(id: "\(tafID):\(filter):\(selection)") { await model.loadActivityPage(tafID, filter: filter, selection: selection) }
        .refreshable { await model.loadActivityPage(tafID, filter: filter, selection: selection); await model.loadRunPage(tafID) }
    }
}

private struct SourceActivityRow: View {
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
        }.buttonStyle(.plain).accessibilityHint("Shows the receipt")
            .sheet(isPresented: $receipt) { if let evidence { ReceiptView(entry: evidence).environmentObject(model) } }
    }
}

struct RetainedRuns: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    var all = false
    @State private var showAll = false
    var body: some View {
        let cache = model.runPages[model.runKey(tafID, all)] ?? SourcePages()
        Panel {
            Eyebrow(text: cache.pages.first?.title ?? "Its work")
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
            if let error = cache.error { Text(error).foregroundColor(Theme.no); Button("Try again") { Task { await model.loadRunPage(tafID, all: all) } } }
            if all, let cursor = cache.pages.last?.next_cursor, cache.error == nil {
                ProgressView().task(id: cursor) { await model.loadRunPage(tafID, all: true, more: true) }
            } else if !all, let title = cache.pages.first?.more_title { Button(title) { showAll = true } }
            if cache.loading { ProgressView() }
        }
        .task(id: tafID) { await model.loadRunPage(tafID, all: all) }
        .sheet(isPresented: $showAll) {
            NavStack { ScrollView { RetainedRuns(tafID: tafID, all: true).padding(16) }.navigationTitle("Its work") }.environmentObject(model)
        }
    }
}

struct MemorySummaryPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    var body: some View {
        Panel {
            Eyebrow(text: "What it remembers")
            if let memory = model.tafSetups[tafID]?.memory {
                Text(memory.title).scaledFont(15, weight: .semibold)
                if let latest = memory.latest { Text(latest).scaledFont(14).foregroundColor(Theme.soft).lineLimit(2) }
                NavigationLink("See all") {
                    ScrollView { MemoryPanel(tafID: tafID, loadAll: true).padding(16) }.navigationTitle("What it remembers")
                        .onDisappear { Task { await model.loadTafSetup(tafID) } }
                }
            } else { ProgressView() }
        }
    }
}
