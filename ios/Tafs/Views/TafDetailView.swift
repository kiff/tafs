import SwiftUI

struct TafDetailView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// 0 chat, 1 activity, 2 card. Chat first: talking to a taf is the main thing.
    @State private var section = Screenshots.enabled && Screenshots.screen == "taf" ? 1 : 0
    @State private var confirmRevoke = false
    @State private var confirmRemove = false
    /// What removing it did, shown once it's done.
    @State private var removal: String?
    /// Set on the tap itself, before the request starts, so a second tap can't send one.
    @State private var runTapped = false
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var width: CGFloat = 0

    /// A wide screen (kiff/tafs#85): beside the chat or the activity, a second column with
    /// the call waiting for the owner and the Card. A compact width keeps one column.
    private var sideColumn: Bool { sizeClass == .regular && width >= 700 && section != 2 }

    var body: some View {
        if let taf = model.taf(tafID) {
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 12) {
                    header(taf)
                    Picker("View", selection: $section) {
                        if model.canChat(taf.id) { Text("Chat").tag(0) }
                        Text("Activity").tag(1)
                        Text("Card").tag(2)
                    }
                    .pickerStyle(.segmented)
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 4)

                HStack(alignment: .top, spacing: 0) {
                    if section == 0 && model.canChat(taf.id) {
                        TafChatView(tafID: taf.id)
                    } else {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 16) {
                                if section == 2 { card(taf) } else { activity(taf) }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                        }
                    }
                    if sideColumn {
                        ScrollView { side(taf).padding(.horizontal, 16).padding(.vertical, 12) }
                            .frame(width: 360)
                    }
                }
            }
            .background(GeometryReader { g in
                Color.clear.onAppear { width = g.size.width }.onChange(of: g.size.width) { width = $0 }
            })
            .screenBackground()
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog("Revoke \(taf.name)'s Card?", isPresented: $confirmRevoke, titleVisibility: .visible) {
                Button("Revoke and stop", role: .destructive) { Task { await model.revoke(tafID) } }
            } message: {
                Text("It stops at once. Anything already done stays done.")
            }
            .confirmationDialog("Remove \(taf.name) for good?", isPresented: $confirmRemove, titleVisibility: .visible) {
                Button("Remove", role: .destructive) { Task { removal = await model.removeTaf(tafID) } }
            } message: {
                Text("Its Cards are revoked, its schedule stopped, its chats, run reports, memory and key deleted, and its name freed. KIFF's receipts stay.")
            }
            .alert("Remove \(taf.name)", isPresented: Binding(get: { removal != nil }, set: { if !$0 { removal = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(removal ?? "")
            }
        } else {
            Text("This taf is gone.").foregroundColor(Theme.dim).screenBackground()
        }
    }

    private func header(_ taf: Taf) -> some View {
        HStack(spacing: 12) {
            TafAvatar(taf: taf, size: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(taf.name).font(Theme.display(30)).foregroundColor(Theme.ink)
                Text(taf.tools.isEmpty ? "No tools yet" : taf.tools.joined(separator: " · "))
                    .font(.system(size: 13)).foregroundColor(Theme.dim)
            }
            Spacer()
            StateChip(state: taf.state)
        }
    }

    @ViewBuilder
    private func activity(_ taf: Taf) -> some View {
        Panel {
            Eyebrow(text: "The job")
            Text(taf.job).font(.system(size: 15)).foregroundColor(Theme.ink)
        }
        // How it does the job, beside the job: not part of its Card (kiff/tafs#96).
        if model.mode == .sample || model.cloudTaf(for: taf.id) != nil {
            MemoryPanel(tafID: taf.id)
        }
        if let cloud = model.cloudTaf(for: taf.id) {
            let inFlight = runTapped || model.runsInFlight.contains(cloud.id)
            Panel {
                Eyebrow(text: "Runs")
                Text(cloud.paused ? "Paused" : cloud.nextRun.map { "Next run at \($0.shortTime)" } ?? "On its schedule")
                    .font(.system(size: 15)).foregroundColor(Theme.ink)
                HStack(spacing: 8) {
                    Button(inFlight ? "Starting a run…" : "Run now") {
                        guard !runTapped else { return }
                        runTapped = true
                        Task {
                            if await model.runNow(cloud) != nil { await model.loadReport(cloud) }
                            runTapped = false
                        }
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(inFlight)
                    Button(cloud.paused ? "Resume" : "Pause") {
                        Task { await model.setPaused(taf.id, !cloud.paused) }
                    }
                    .buttonStyle(OutlineButtonStyle())
                }
            }
            RunReportPanel(report: model.reports[cloud.id]) { Task { await model.loadReport(cloud) } }
                .task(id: cloud.id) { await model.loadReport(cloud) }
        }
        // Beside it on a wide screen, in the second column.
        if !sideColumn { ForEach(model.heldCalls(for: taf.id)) { HeldCallCard(call: $0) } }
        let entries = model.entries(for: taf.id)
        if entries.isEmpty {
            Text("Nothing yet.").foregroundColor(Theme.dim)
        }
        ForEach(entries) { RecordRow(entry: $0) }
    }

    /// The second column on a wide screen: what waits for the owner, then the Card.
    private func side(_ taf: Taf) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            let held = model.heldCalls(for: taf.id)
            if !held.isEmpty {
                Eyebrow(text: "Needs you", color: Theme.amber)
                ForEach(held) { HeldCallCard(call: $0) }
            }
            Eyebrow(text: "Card")
            CardFace(name: taf.name, rules: taf.card.rules, issuedBy: taf.card.issuedBy,
                     expires: taf.card.expires, color: Theme.tafColor(taf.colorIndex), revoked: taf.card.revoked)
            Button("All of the Card") { section = 2 }
                .buttonStyle(OutlineButtonStyle())
        }
    }

    @ViewBuilder
    private func card(_ taf: Taf) -> some View {
        CardFace(name: taf.name, rules: taf.card.rules, issuedBy: taf.card.issuedBy,
                 expires: taf.card.expires, color: Theme.tafColor(taf.colorIndex), revoked: taf.card.revoked)
        Panel {
            ForEach(taf.card.rules) { rule in
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.label).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                        Text(rule.outside == .hold ? "Outside: held for you" : "Outside: refused")
                            .font(.system(size: 12)).foregroundColor(Theme.dim)
                    }
                    Spacer()
                    Text(rule.summary).font(Theme.mono(13))
                        .foregroundColor(rule.never ? Theme.no : Theme.ink)
                        .multilineTextAlignment(.trailing)
                }
            }
        }
        ConnectionsPanel(tafID: taf.id)
        if taf.state != .revoked {
            HStack(spacing: 8) {
                if model.mode == .sample {
                    Button(taf.state == .paused ? "Resume" : "Pause") { model.togglePause(taf.id) }
                        .buttonStyle(OutlineButtonStyle())
                }
                Button("Revoke") { confirmRevoke = true }
                    .buttonStyle(OutlineButtonStyle(color: Theme.no))
            }
        }
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil {
            Button("Remove for good") { confirmRemove = true }
                .buttonStyle(OutlineButtonStyle(color: Theme.no))
        }
    }
}

/// The vendors connected to the taf through planque (#51): whose keys, which tools, what
/// happened last, and Disconnect, which revokes the sign-in at the vendor.
struct ConnectionsPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var confirming: PlanqueClient.Grant?

    var body: some View {
        let connections = model.connections(for: tafID)
        if model.mode == .kiff {
            Panel {
                Eyebrow(text: "Connections")
                ForEach(connections) { grant in
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(grant.vendor).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                            Text("Keys kept sealed\(grant.account.map { " · \($0)" } ?? "")")
                                .font(.system(size: 12)).foregroundColor(Theme.dim)
                            if grant.connected {
                                Text(grant.request.tools.map(\.action).joined(separator: ", "))
                                    .font(Theme.mono(12)).foregroundColor(Theme.soft)
                            } else {
                                // Signed in, not connected (contract 5): no tool is in KIFF yet.
                                Text(grant.listed ? "Signed in · the tools are being chosen" : "Signed in · tools waiting for your approval")
                                    .font(.system(size: 12, weight: .semibold)).foregroundColor(Theme.amber)
                                if let end = grant.heldUntilDate {
                                    Text("Unless you approve its tools, the sign-in is revoked \(end.formatted(date: .abbreviated, time: .shortened)).")
                                        .font(.system(size: 12)).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            if let last = grant.events.last {
                                Text(last.plain).font(.system(size: 12)).foregroundColor(Theme.soft)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer(minLength: 0)
                        Button("Disconnect") { confirming = grant }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(Theme.no)
                    }
                }
                NavigationLink { SetupChatView() } label: {
                    Label(connections.isEmpty ? "Connect a tool" : "Connect another tool", systemImage: "plus")
                        .font(.system(size: 15, weight: .semibold))
                }
            }
            .confirmationDialog("Disconnect \(confirming?.vendor ?? "")?", isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                                titleVisibility: .visible) {
                Button("Disconnect", role: .destructive) {
                    if let id = confirming?.id { Task { _ = await model.disconnect(grantID: id) } }
                }
            } message: {
                Text("The sign-in at the vendor is revoked and its tools are removed from KIFF. The taf can't use them until you connect again.")
            }
        }
    }
}

/// What the taf remembers between its runs and chats, each note with a Forget button.
struct MemoryPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID

    var body: some View {
        Panel {
            Eyebrow(text: "What it remembers")
            if let notes = model.memories[tafID] {
                if notes.isEmpty {
                    Text("Nothing yet. It writes down what it learns about how you like the job done.")
                        .font(.system(size: 14)).foregroundColor(Theme.soft)
                }
                ForEach(notes) { note in
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(note.content).font(.system(size: 15)).foregroundColor(Theme.ink)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("\(note.title) · \(note.updatedAt.shortTime)").font(.system(size: 12)).foregroundColor(Theme.soft)
                        }
                        Spacer(minLength: 0)
                        Button("Forget") { Task { await model.forget(note, from: tafID) } }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(Theme.soft)
                            .frame(minHeight: 44)
                            .accessibilityLabel("Forget: \(note.content)")
                    }
                }
                Text("Every run and chat starts with these. They shape how it works, never what its Card allows. Forget removes that one note and its earlier versions; past chats and run reports stay until the taf is removed.")
                    .font(.system(size: 12)).foregroundColor(Theme.soft)
            } else if model.memoryErrors[tafID] == nil {
                ProgressView().tint(Theme.amber)
            }
            if let error = model.memoryErrors[tafID] {
                Text(error).font(.system(size: 13)).foregroundColor(Theme.no)
            }
        }
        .task(id: tafID) { await model.loadMemory(tafID) }
    }
}

/// The taf's latest run on tafs cloud and, once it stops, what it reported.
struct RunReportPanel: View {
    let report: AppModel.RunReport?
    let reload: () -> Void

    var body: some View {
        Panel {
            HStack {
                Eyebrow(text: "Last run")
                Spacer()
                Button("Refresh", action: reload).font(.system(size: 13)).foregroundColor(Theme.amber)
            }
            if let report {
                if let at = report.at {
                    Text("\(at.shortTime) · \(report.finished ? "finished" : report.status == "running" ? "still running" : "no session")")
                        .font(Theme.mono(12)).foregroundColor(Theme.dim)
                }
                if !report.text.isEmpty {
                    // A taf writes markdown: shown as the chat shows it, never raw (kiff/tafs#96).
                    MarkdownText(report.text, size: 14).foregroundColor(Theme.ink).textSelection(.enabled)
                } else if report.session == nil && report.error == nil {
                    Text("No run yet.").font(.system(size: 14)).foregroundColor(Theme.dim)
                } else if !report.finished && report.error == nil {
                    Text("Its report shows here when the run ends.").font(.system(size: 14)).foregroundColor(Theme.dim)
                }
                if let error = report.error {
                    Text(error).font(.system(size: 13)).foregroundColor(Theme.no)
                }
            } else {
                ProgressView().tint(Theme.amber)
            }
        }
    }
}

/// A Card drawn as a card: the limits, who issued it, until when.
struct CardFace: View {
    let name: String
    let rules: [CardRule]
    let issuedBy: String
    let expires: Date?
    let color: Color
    var revoked = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("tafs").font(Theme.mono(13, weight: .semibold)).tracking(1.2)
                Spacer()
                Text("Card by KIFF · \(name)").font(Theme.mono(11))
            }
            VStack(alignment: .leading, spacing: 4) {
                ForEach(rules) { rule in
                    Text("\(rule.label.lowercased()) \(rule.summary)").font(Theme.mono(13))
                }
                if rules.isEmpty { Text("no actions yet").font(Theme.mono(13)) }
            }
            Spacer(minLength: 0)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Issued by").font(.system(size: 11)).opacity(0.7)
                    Text(issuedBy).font(.system(size: 13, weight: .semibold))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    // A Card with no end says so, never a far-off date (kiff/tafs#96).
                    Text(revoked ? "Revoked" : expires == nil ? "Ends" : "Until").font(.system(size: 11)).opacity(0.7)
                    Text(expires?.formatted(date: .abbreviated, time: .omitted) ?? "No end date").font(.system(size: 13, weight: .semibold))
                }
            }
        }
        .foregroundColor(.white)
        .padding(20)
        .frame(maxWidth: .infinity, minHeight: 200, alignment: .topLeading)
        .background(color.opacity(revoked ? 0.4 : 1))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(alignment: .topTrailing) {
            Circle().stroke(Theme.background.opacity(0.15)).frame(width: 150, height: 150).offset(x: 40, y: -40)
                .clipped().allowsHitTesting(false).accessibilityHidden(true)
        }
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}
