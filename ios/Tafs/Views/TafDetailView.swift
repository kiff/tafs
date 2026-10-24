import SwiftUI

struct TafDetailView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// 0 chat, 1 activity, 2 card, 3 doors. Chat first: talking to a taf is the main thing.
    @State private var section = !Screenshots.enabled ? 0 : Screenshots.screen == "taf" ? 1 : Screenshots.screen == "card" ? 2 : 0
    @State private var confirmRevoke = false
    @State private var confirmRemove = false
    /// What removing it did, shown once it's done.
    @State private var removal: String?
    /// Set on the tap itself, before the request starts, so a second tap can't send one.
    @State private var runTapped = false
    /// The Card in front of the stack (kiff/tafs#211); nil for the first, the live one.
    @State private var frontCard: String?
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #else
    private let sizeClass: UserInterfaceSizeClass? = .regular
    #endif
    @State private var width: CGFloat = 0
    @State private var height: CGFloat = 0

    /// The side column's width, and the least the chat keeps beside it: the largest iPhone's
    /// width (kiff/tafs#211, the owner's 4A).
    static let sideWidth: CGFloat = 360
    static let chatAtLeast: CGFloat = 430

    /// A wide screen (kiff/tafs#85): beside the chat or the activity, a second column with
    /// the call waiting for the owner and the Cards. Only in landscape and only when the chat
    /// keeps an iPhone's width beside it (kiff/tafs#211): iPad landscape and the Mac. A
    /// narrower screen keeps one column, with the Card bar above it.
    private var sideColumn: Bool {
        sizeClass == .regular && section < 2 && width > height
            && width - Self.sideWidth - 32 >= Self.chatAtLeast
    }

    var body: some View {
        if let taf = model.taf(tafID) {
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 12) {
                    header(taf)
                    Picker("View", selection: $section) {
                        if model.canChat(taf.id) { Text("Chat").tag(0) }
                        Text("Activity").tag(1)
                        Text("Card").tag(2)
                        if hasDoors(taf) { Text("Doors").tag(3) }
                    }
                    .pickerStyle(.segmented)
                    // Without the side column, the Cards are one line above the chat or the
                    // activity; it opens the Card tab (kiff/tafs#211).
                    if section != 2 && !sideColumn, let front = shownFront(taf) {
                        CardBar(card: front, count: taf.shownCards.count, color: Theme.tafColor(taf.colorIndex), revoked: taf.card.revoked) {
                            section = 2
                        }
                    }
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
                                if section == 3, hasDoors(taf), let cloudID = model.cloudTaf(for: taf.id)?.id {
                                    DoorsView(taf: taf, cloud: model.cloud, cloudID: cloudID)
                                } else if section == 2 { card(taf) } else { activity(taf) }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                        }
                        .coachClearance()
                    }
                    if sideColumn {
                        ScrollView { side(taf).padding(.horizontal, 16).padding(.vertical, 12) }
                            .coachClearance()
                            .frame(width: Self.sideWidth)
                    }
                }
            }
            .background(GeometryReader { g in
                Color.clear.onAppear { width = g.size.width; height = g.size.height }
                    .onChange(of: g.size.width) { width = $0 }
                    .onChange(of: g.size.height) { height = $0 }
            })
            .screenBackground()
            .inlineTitle()
            // Coach mode opened from here works on this taf (kiff/tafs#138).
            .onAppear { model.tafOnScreen = tafID }
            .onDisappear { if model.tafOnScreen == tafID { model.tafOnScreen = nil } }
            .confirmationDialog("Revoke \(taf.name)'s Card?", isPresented: $confirmRevoke, titleVisibility: .visible) {
                Button("Revoke and stop", role: .destructive) { Task { await model.revoke(tafID) } }
            } message: {
                Text("No new action goes through. Anything already done stays done.")
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

    /// Doors are tafs cloud's, and only where it offers one (docs/doors.md): a taf it runs,
    /// signed in to KIFF. The sample has none.
    private func hasDoors(_ taf: Taf) -> Bool {
        model.mode == .kiff && !model.doorsOffered.isEmpty && model.cloudTaf(for: taf.id) != nil
    }

    /// The Card in front of the stack: the one tapped, else the first, the live one.
    private func shownFront(_ taf: Taf) -> TafCard? {
        let cards = taf.shownCards
        return cards.first(where: { $0.id == frontCard }) ?? cards.first
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
                if cloud.worksFromChat {
                    // Its tools can't find its work by themselves (kiff/tafs#138): no schedule.
                    Text("It works when you tell it, in its chat: no schedule runs it.")
                        .font(.system(size: 15)).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                } else {
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
            let cards = taf.shownCards
            Eyebrow(text: cards.count == 1 ? "Card" : "Cards")
            CardStack(cards: cards, issuedBy: taf.card.issuedBy, color: Theme.tafColor(taf.colorIndex),
                      revoked: taf.card.revoked, front: $frontCard)
            Button(cards.count == 1 ? "All of the Card" : "All of the Cards") { section = 2 }
                .buttonStyle(OutlineButtonStyle())
        }
    }

    @ViewBuilder
    private func card(_ taf: Taf) -> some View {
        let cards = taf.shownCards
        let shown = cards.first(where: { $0.id == frontCard }) ?? cards.first
        CardStack(cards: cards, issuedBy: taf.card.issuedBy, color: Theme.tafColor(taf.colorIndex),
                  revoked: taf.card.revoked, front: $frontCard)
            .frame(maxWidth: .infinity)
        // All of the front Card's limits: the face shows the main one.
        Panel {
            ForEach(shown?.rules ?? []) { rule in
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
        // Halting is a job of its own too (kiff/kiff-cloud#1061): its row opens its screen.
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil, taf.state != .revoked {
            Panel { HaltRow(tafID: taf.id) }
        }
        // Its usage is the account's Usage, opened from a row with this taf's count (kiff/tafs#184).
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil {
            Panel {
                NavigationLink { TafUsageView(tafID: taf.id).environmentObject(model) } label: {
                    HStack {
                        Label("Usage", systemImage: "chart.bar").font(.system(size: 15, weight: .semibold))
                        Spacer()
                        if let n = model.countedTasks(for: taf.id) {
                            Text(UsageView.tafCount(n)).font(.system(size: 14)).foregroundColor(Theme.soft)
                        }
                    }
                }
            }
            .task { if model.usage == nil { await model.refreshUsage() } }
        }
        // Its versions are a job of their own: their own screen, not another section here.
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil {
            Panel {
                NavigationLink { VersionsScreen(tafID: taf.id) } label: {
                    Label("Versions", systemImage: "square.stack.3d.up")
                        .font(.system(size: 15, weight: .semibold))
                }
            }
        }
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
                            Text(KIFFMapping.vendorWords(grant.vendor)).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                            Text("Keys kept sealed\(grant.account.map { " · \($0)" } ?? "")")
                                .font(.system(size: 12)).foregroundColor(Theme.dim)
                            if grant.connected {
                                // What it may do, in words, not the tools' ids (kiff/tafs#138, item 52).
                                Text(grant.request.tools.filter(\.tafs).map(\.words).joined(separator: " · "))
                                    .font(.system(size: 12)).foregroundColor(Theme.soft)
                                if model.leftOver(grant, of: tafID) {
                                    // Left over after a version moved its Card (kiff/tafs#138). An older version may still
                                    // run on it, so the words don't say to remove it.
                                    Text("Not on the live version's Card: the taf doesn't use it now. Going back to an older version may need it.")
                                        .font(.system(size: 12, weight: .semibold)).foregroundColor(Theme.amber)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
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
                                Text(last.what).font(.system(size: 12)).foregroundColor(Theme.soft)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer(minLength: 0)
                        Button("Disconnect") { confirming = grant }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(Theme.no)
                    }
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

/// A taf's versions, on their own screen.
struct VersionsScreen: View {
    let tafID: UUID

    var body: some View {
        ScrollView {
            VersionsPanel(tafID: tafID).padding(.horizontal, 16).padding(.vertical, 12)
        }
        .coachClearance()
        .screenBackground()
        .navigationTitle("Versions")
        .inlineTitle()
    }
}

/// The taf's versions, like app builds (kiff/tafs#107): newest first, the live one marked.
/// Try it first runs a version on what the owner asks, with KIFF deciding each call and
/// none sent; Install this version makes it live, forward or back, with one Face ID
/// (kiff/tafs#138).
struct VersionsPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// The version whose Card the owner reads before Face ID.
    @State private var reading: TafsCloud.Version?
    /// The version the owner is about to try, and what it should try (kiff/tafs#138).
    @State private var trying: Int?
    @State private var trialInput = ""

    /// Install this version: the switch begins, with the Card draft tafs cloud prepared (its own,
    /// drafted again, or a restore when going back), which is read first, now → after
    /// (kiff/tafs#107, #122). With no Card to change, one Face ID makes it live.
    private func use(_ v: TafsCloud.Version, redraft: Bool = false, stopBlocking: Bool = false) {
        Task {
            guard let sw = await model.prepareSwitch(v, of: tafID, redraft: redraft, stopBlocking: stopBlocking) else { return }
            if sw.draft == nil { await model.useVersion(v, of: tafID) } else { reading = v }
        }
    }

    var body: some View {
        Panel {
            Eyebrow(text: "Versions")
            Text("Try a version first: it does the work you give it, and KIFF decides each call without sending any. Installing one makes it the live version.")
                .font(.system(size: 13)).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            if let sw = model.switches[tafID] { switchUnderWay(sw) }
            if let n = model.staleVersionCards[tafID], let v = model.versions[tafID]?.first(where: { $0.n == n }) {
                Button("Draft its Card again") { use(v, redraft: true) }
                    .buttonStyle(OutlineButtonStyle())
            }
            if let list = model.versions[tafID] {
                if list.isEmpty {
                    Text("One version so far. When you improve it, each change is kept here.")
                        .font(.system(size: 14)).foregroundColor(Theme.soft)
                }
                ForEach(list.reversed()) { row($0) }
            } else if model.versionErrors[tafID] == nil {
                ProgressView().tint(Theme.amber)
            }
            if let error = model.versionErrors[tafID] {
                Text(error).font(.system(size: 13)).foregroundColor(Theme.no)
            }
            if let n = model.blockedInstalls[tafID], let v = model.versions[tafID]?.first(where: { $0.n == n }) {
                // One step: what's in the way stops (a held call withdrawn, nothing sent), then
                // the install begins (kiff/tafs#206).
                Button("Stop it and install") { use(v, stopBlocking: true) }
                    .buttonStyle(OutlineButtonStyle())
            }
        }
        .task(id: tafID) { await model.loadVersions(tafID) }
        .sheet(item: $reading) { v in VersionCardSheet(version: v, tafID: tafID) }
        // Terms that changed between the screen and the tap open again, to read anew.
        .onChange(of: model.changedVersionCards[tafID]) { changed in
            if changed != nil, reading == nil, let v = model.versions[tafID]?.first(where: { model.switchDraft($0, of: tafID) == changed?.id }) { reading = v }
        }
    }

    private func row(_ v: TafsCloud.Version) -> some View {
        let running = model.trials[tafID].map { !$0.trial.run.finished } ?? false
        let switching = model.switchingVersion[tafID]
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text("Version \(v.n)").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                if v.live {
                    Text("Live now").font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.ok).clipShape(Capsule())
                }
                Spacer(minLength: 0)
            }
            if let note = v.note, !note.isEmpty {
                Text(note).font(.system(size: 14)).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            Text(Self.byline(v)).font(.system(size: 12)).foregroundColor(Theme.dim)
            if trying == v.n {
                // What to try (kiff/tafs#138): a task in the owner's words. The version's note
                // describes the change, so it's the hint, not the task.
                VStack(alignment: .leading, spacing: 6) {
                    Text("What should it try?").font(.system(size: 12, weight: .semibold)).foregroundColor(Theme.dim)
                    TextField(v.note.map { "For example, to check: \($0)" } ?? "For example: look up payment pi_…", text: $trialInput)
                        .textFieldStyle(.roundedBorder)
                    HStack(spacing: 8) {
                        Button("Run the trial") {
                            let input = trialInput.trimmingCharacters(in: .whitespacesAndNewlines)
                            trying = nil
                            Task { await model.tryVersion(v.n, of: tafID, input: input) }
                        }
                        .buttonStyle(OutlineButtonStyle())
                        .disabled(trialInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Button("Cancel") { trying = nil }
                            .buttonStyle(OutlineButtonStyle(color: Theme.dim))
                    }
                }
            }
            HStack(spacing: 8) {
                if trying != v.n {
                    Button("Try it first") {
                        trialInput = ""
                        trying = v.n
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(running)
                }
                if !v.live {
                    Button(switching == v.n ? "Installing…" : "Install this version") {
                        use(v)
                    }
                    .buttonStyle(OutlineButtonStyle())
                    // One switch at a time: another waits for the one under way.
                    .disabled(switching != nil || model.switches[tafID].map { $0.n != v.n } ?? false)
                }
            }
            // The trial's calls under the version tried, as they come.
            if let trial = model.trials[tafID], trial.version == v.n { TrialView(trial: trial) }
        }
        .padding(.vertical, 4)
    }

    /// A switch that didn't finish: the taf doesn't run until it's finished or cancelled.
    private func switchUnderWay(_ sw: TafsCloud.Switching) -> some View {
        let version = model.versions[tafID]?.first { $0.n == sw.n }
        let working = model.switchingVersion[tafID] != nil
        return VStack(alignment: .leading, spacing: 6) {
            Text("Installing version \(sw.n)").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.amber)
            Text("Your taf doesn't run until it's installed or you cancel.")
                .font(.system(size: 13)).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                if let version {
                    Button(working ? "Installing…" : "Finish") { use(version) }
                        .buttonStyle(OutlineButtonStyle())
                        .disabled(working)
                }
                Button("Cancel") { Task { await model.cancelSwitch(tafID) } }
                    .buttonStyle(OutlineButtonStyle(color: Theme.no))
                    .disabled(working)
            }
        }
        .padding(.vertical, 4)
    }

    /// Who made it, when, and whether it brings a new Card. A version kiff/agents wrote
    /// itself (the first, or an Upgrade's) names no one.
    static func byline(_ v: TafsCloud.Version) -> String {
        var by: String?
        switch v.by {
        case nil, "": by = nil
        case "setup": by = "By Coach mode"
        default: by = "By you"
        }
        return [by, v.created.formatted(date: .abbreviated, time: .shortened), v.draft == nil ? nil : "brings a new Card"]
            .compactMap { $0 }.joined(separator: " · ")
    }
}

/// The Card a version brings, read before Face ID (kiff/tafs#107): each Card it revises as
/// it is now and as it will be, and each new one. Install this version signs exactly what this
/// sheet shows (kiff/tafs#115).
struct VersionCardSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let version: TafsCloud.Version
    let tafID: UUID
    /// Coach's held install this answers (kiff/tafs#206): approved with the same Face ID,
    /// once the version is live.
    var approving: HeldCall? = nil
    @State private var draft: KIFFClient.AgentDraft?
    @State private var loaded = false

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Version \(version.n)\(version.note.map { ": \($0)" } ?? "")")
                        .font(.system(size: 17, weight: .semibold)).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    if let draft, let cards = draft.cards {
                        let lines = draft.cardLines
                        ForEach(Array(cards.enumerated()), id: \.offset) { i, card in cardView(card, lines: lines[i]) }
                        ForEach(draft.retires ?? [], id: \.id) { retiredView($0) }
                        Text(draft.retires?.isEmpty == false
                             ? "While it switches, your taf doesn't run. Confirming with \(Biometry.name) once issues these Cards in KIFF and removes the ones this version doesn't have, then this version goes live."
                             : "While it switches, your taf doesn't run. Confirming with \(Biometry.name) once issues these Cards in KIFF, then this version goes live.")
                            .font(.system(size: 12)).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    } else if loaded {
                        Text(model.versionErrors[tafID] ?? "This version's Card couldn't be read.")
                            .font(.system(size: 13)).foregroundColor(Theme.no)
                    } else {
                        ProgressView().tint(Theme.amber)
                    }
                }
                .padding(16)
            }
            .screenBackground()
            .navigationTitle("Its Card")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Not now: the switch it began is dropped, and the taf runs as it was.
                    Button("Cancel") {
                        dismiss()
                        Task { await model.cancelSwitch(tafID) }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Install this version") {
                        let shown = draft?.version
                        dismiss()
                        Task { await model.useVersion(version, of: tafID, shownDraft: shown, approving: approving) }
                    }
                    .disabled(draft?.cards == nil)
                }
            }
        }
        // The switch is begun while this is open: Cancel or Use ends it, never a swipe
        // that would leave the taf not running with nothing on screen to finish it.
        .interactiveDismissDisabled()
        .task {
            // The terms that changed since last shown, when that's why this opened.
            if let changed = model.changedVersionCards[tafID], changed.id == model.switchDraft(version, of: tafID) { draft = changed }
            else { draft = await model.versionCard(version, of: tafID) }
            loaded = true
        }
    }

    /// A Card this version doesn't have: confirming removes it, so what it allows stops.
    private func retiredView(_ card: KIFFClient.AgentDraft.Retired) -> some View {
        Panel {
            Eyebrow(text: "Removes a Card")
            ForEach(card.current?.lines ?? [], id: \.self) {
                Text($0).font(.system(size: 14)).foregroundColor(Theme.soft).strikethrough()
            }
            Text("Your taf can't do this any more.").font(.system(size: 12)).foregroundColor(Theme.dim)
        }
    }

    @ViewBuilder
    /// One Card of the version, with its lines as the draft reads them (`cardLines`).
    private func cardView(_ card: KIFFClient.DraftCard, lines: [String]) -> some View {
        Panel {
            if card.isUnchanged {
                // Re-issued as it is (kiff/tafs#138): its terms once, not a now and after.
                Eyebrow(text: "Unchanged")
                ForEach(lines, id: \.self) { Text($0).font(.system(size: 14)).foregroundColor(Theme.soft) }
            } else if let current = card.current {
                Eyebrow(text: "Changes a Card")
                Text("Now").font(.system(size: 12, weight: .semibold)).foregroundColor(Theme.dim)
                ForEach(current.lines, id: \.self) { Text($0).font(.system(size: 14)).foregroundColor(Theme.soft) }
                Text("After").font(.system(size: 12, weight: .semibold)).foregroundColor(Theme.dim)
            } else {
                Eyebrow(text: "A new Card")
            }
            if !card.isUnchanged {
                ForEach(lines, id: \.self) { Text($0).font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink) }
            }
        }
    }
}

/// A trial as it goes: each call the version made, with what KIFF decided. No call was sent,
/// reads included (kiff-cloud#1285): a read had no data, and the version went on without it.
struct TrialView: View {
    let trial: AppModel.VersionTrial

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Divider()
            Text(trial.trial.run.finished ? "Trial of version \(trial.version)" : "Trying version \(trial.version)…")
                .font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink)
            ForEach(Array(trial.trial.writes.enumerated()), id: \.offset) { _, write in
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: Self.symbol(write.decision)).foregroundColor(Self.color(write.decision))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(write.title ?? write.tool).font(.system(size: 14)).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(Self.words(write.decision) + (write.reason.map { ": \($0)" } ?? ""))
                            .font(.system(size: 12)).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            if trial.trial.run.finished {
                if trial.trial.writes.isEmpty {
                    Text("It made no writes.").font(.system(size: 13)).foregroundColor(Theme.dim)
                }
                if let output = trial.trial.run.output, !output.isEmpty {
                    MarkdownText(output, size: 14).foregroundColor(Theme.ink)
                }
                if let error = trial.trial.run.error, !error.isEmpty {
                    Text(error).font(.system(size: 13)).foregroundColor(Theme.no)
                }
            } else {
                ProgressView().tint(Theme.amber)
            }
            Text("No call was sent, reads included: KIFF decided each one and stopped there.")
                .font(.system(size: 12)).foregroundColor(Theme.soft)
        }
    }

    static func words(_ decision: String) -> String {
        switch decision {
        case "allowed": return "KIFF would allow it"
        case "held": return "KIFF would hold it for you"
        default: return "KIFF would refuse it"
        }
    }

    static func symbol(_ decision: String) -> String {
        switch decision {
        case "allowed": return "checkmark.circle.fill"
        case "held": return "pause.circle.fill"
        default: return "xmark.octagon.fill"
        }
    }

    static func color(_ decision: String) -> Color {
        switch decision {
        case "allowed": return Theme.ok
        case "held": return Theme.amber
        default: return Theme.no
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

/// A Card drawn from its lines, as one Card: a draft's, before KIFF issued it.
struct CardFace: View {
    let name: String
    let rules: [CardRule]
    let issuedBy: String
    let expires: Date?
    let color: Color
    /// What each rule has used this window, as KIFF says: the face shows what's left.
    var used: [String: Money] = [:]
    var revoked = false

    var body: some View {
        TafCardFace(card: TafCard.merged(id: "draft", title: name, rules: rules, expires: expires, active: !revoked, used: used),
                    issuedBy: issuedBy, color: color, revoked: revoked)
    }
}

/// A Card drawn as a card (kiff/tafs#211): a payment card's shape at a set size, never the
/// full width; what's left is the big figure, the other limits small lines under it.
struct TafCardFace: View {
    let card: TafCard
    let issuedBy: String
    let color: Color
    var revoked = false

    /// At most this wide, on the iPhone and the iPad alike.
    static let width: CGFloat = 300
    /// A payment card's proportions, 85.6 by 54 mm.
    static let ratio: CGFloat = 85.6 / 54

    /// A live Card in the taf's colour; one scoped to another environment in grey.
    static func fill(_ card: TafCard, color: Color) -> Color { card.live ? color : Theme.dim }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(card.title).font(Theme.mono(12, weight: .semibold)).lineLimit(1)
                Spacer(minLength: 4)
                EnvironmentPill(text: card.environmentLabel)
            }
            Spacer(minLength: 0)
            if let hero = card.hero {
                VStack(alignment: .leading, spacing: 2) {
                    Text(hero.figure).font(.system(size: 34, weight: .bold).monospacedDigit())
                        .lineLimit(1).minimumScaleFactor(0.6)
                    Text(hero.words).font(.system(size: 12, weight: .medium)).opacity(0.9).lineLimit(1)
                }
            }
            ForEach(card.facts, id: \.self) { fact in
                Text(fact).font(Theme.mono(11)).opacity(0.92).lineLimit(1).minimumScaleFactor(0.8)
            }
            if card.rules.isEmpty { Text("no actions yet").font(Theme.mono(12)) }
            Spacer(minLength: 0)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Issued by").font(.system(size: 10)).opacity(0.75)
                    Text(issuedBy).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 1) {
                    // A Card with no end says so, never a far-off date (kiff/tafs#96).
                    Text(revoked ? "Revoked" : !card.active ? "Ended" : card.expires == nil ? "Ends" : "Until")
                        .font(.system(size: 10)).opacity(0.75)
                    Text(card.expires?.formatted(date: .abbreviated, time: .omitted) ?? "No end date")
                        .font(.system(size: 12, weight: .semibold))
                }
            }
        }
        .foregroundColor(.white)
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .aspectRatio(Self.ratio, contentMode: .fit)
        .background(Self.fill(card, color: color).opacity(revoked || !card.active ? 0.4 : 1))
        .overlay(alignment: .topTrailing) {
            Circle().stroke(Color.white.opacity(0.18)).frame(width: 130, height: 130).offset(x: 35, y: -45)
                .allowsHitTesting(false).accessibilityHidden(true)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .frame(maxWidth: Self.width)
        .accessibilityElement(children: .combine)
    }
}

/// A taf's Cards in one line (kiff/tafs#211): the front Card's balance, its name, its
/// environment and how many Cards there are. Tapping it opens the Card tab.
struct CardBar: View {
    let card: TafCard
    let count: Int
    let color: Color
    var revoked = false
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(TafCardFace.fill(card, color: color).opacity(revoked || !card.active ? 0.4 : 1))
                    .frame(width: 46, height: 29)
                VStack(alignment: .leading, spacing: 1) {
                    Text(card.hero.map { "\($0.figure) \($0.words)" } ?? card.title)
                        .font(.system(size: 15, weight: .semibold).monospacedDigit()).foregroundColor(Theme.ink)
                        .lineLimit(1).minimumScaleFactor(0.8)
                    Text(details).font(.system(size: 12)).foregroundColor(Theme.dim).lineLimit(1)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.dim)
            }
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(Theme.panel)
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Shows the Cards")
    }

    /// "Refunds · Live · 3 Cards": the Card's name, its environment, and the others.
    private var details: String {
        var parts = [card.title, card.live ? "Live" : card.environment.capitalized]
        if count > 1 { parts.append("\(count) Cards") }
        return parts.joined(separator: " · ")
    }
}

/// LIVE, or the environment a Card is scoped to.
struct EnvironmentPill: View {
    let text: String
    var body: some View {
        Text(text).font(Theme.mono(10, weight: .semibold)).tracking(0.6)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Color.white.opacity(0.22)).clipShape(Capsule())
    }
}

/// A taf's Cards as a wallet stack (kiff/tafs#211): the Card in front whole, the others
/// behind it showing their top edge, so their number and names read at once. Tapping one
/// brings it forward.
struct CardStack: View {
    let cards: [TafCard]
    let issuedBy: String
    let color: Color
    var revoked = false
    /// The Card in front; nil for the first, the live one.
    @Binding var front: String?

    /// How much of a Card behind shows.
    private static let edge: CGFloat = 40

    var body: some View {
        let shown = cards.first(where: { $0.id == front }) ?? cards.first
        // The furthest back on top, as in a wallet: the reverse of the order they're listed in.
        let behind = cards.filter { $0.id != shown?.id }.reversed()
        VStack(spacing: -12) {
            ForEach(Array(behind)) { card in
                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { front = card.id }
                } label: {
                    HStack(spacing: 8) {
                        Text(card.title).font(Theme.mono(11, weight: .semibold)).lineLimit(1)
                        Spacer(minLength: 4)
                        EnvironmentPill(text: card.environmentLabel)
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.top, 9)
                    .frame(maxWidth: .infinity, minHeight: Self.edge + 12, alignment: .topLeading)
                    .background(TafCardFace.fill(card, color: color).opacity(revoked || !card.active ? 0.4 : 1))
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .shadow(color: .black.opacity(0.12), radius: 3, y: -1)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(card.title), \(card.environmentLabel). Show this Card")
            }
            if let shown {
                TafCardFace(card: shown, issuedBy: issuedBy, color: color, revoked: revoked)
                    .shadow(color: .black.opacity(0.12), radius: 3, y: -1)
            }
        }
        .frame(maxWidth: TafCardFace.width)
    }
}
