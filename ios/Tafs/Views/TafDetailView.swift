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
            LateNote(text: "This taf is gone.").screenBackground()
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
                Text(taf.name).scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                Text(taf.tools.isEmpty ? "No tools yet" : taf.tools.joined(separator: " · "))
                    .scaledFont(13).foregroundColor(Theme.dim)
                versionLine(taf)
                #if os(iOS)
                // Who it's shared with, and Share.
                ShareLine(tafID: taf.id)
                #endif
            }
            Spacer()
            StateChip(state: taf.state)
        }
    }

    /// The ways into its versions (kiff/tafs#173): the live one, which opens Versions, and an
    /// open trial, to resume.
    @ViewBuilder
    private func versionLine(_ taf: Taf) -> some View {
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil {
            HStack(spacing: 10) {
                NavigationLink { VersionsScreen(tafID: taf.id) } label: {
                    let live = model.versions[taf.id]?.first(where: \.live)?.n
                    Text(live.map { "Version \($0) · live" } ?? "Versions")
                        .scaledFont(13, weight: .semibold)
                }
                if let trial = model.openTrials[taf.id], let chat = model.trialChat(of: taf.id) {
                    OpenTrialLink(chatID: chat, tafID: taf.id) {
                        Text("Trying v\(trial.n) · Resume").scaledFont(13, weight: .semibold).foregroundColor(Theme.amber)
                    }
                }
            }
            .task(id: taf.id) {
                await model.loadVersions(taf.id)
                await model.loadTrial(taf.id)
            }
        }
    }

    @ViewBuilder
    private func activity(_ taf: Taf) -> some View {
        Panel {
            Eyebrow(text: "The job")
            Text(taf.job).scaledFont(15).foregroundColor(Theme.ink)
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
                        .scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                } else {
                Text(cloud.paused ? "Paused" : cloud.nextRun.map { "Next run at \($0.shortTime)" } ?? "On its schedule")
                    .scaledFont(15).foregroundColor(Theme.ink)
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
        CardLines(rules: shown?.rules ?? [])
        // A vendor's refusal still counts against the Card's totals (kiff/tafs#211, 6.13).
        if let refused = HeldCallCard.refusedLine(model.refusedToday(taf.id)) {
            Text(refused)
                .scaledFont(13).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
        }
        ConnectionsPanel(tafID: taf.id)
        // Halting is a job of its own too: its row opens its screen.
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil, taf.state != .revoked {
            HaltRow(tafID: taf.id, framed: true)
        }
        // Its usage is the account's Usage, opened from a row with this taf's count (kiff/tafs#184).
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil {
            PanelLink { TafUsageView(tafID: taf.id).environmentObject(model) } label: {
                Label("Usage", systemImage: "chart.bar").scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
                Spacer()
                if let n = model.countedTasks(for: taf.id) {
                    Text(UsageView.tafCount(n)).scaledFont(14).foregroundColor(Theme.soft)
                }
            }
            // Read again whenever its record grows, not only the first time: its count was a
            // task behind until Usage was opened (the 755 retest).
            .task(id: model.entries(for: taf.id).count) { await model.refreshUsage() }
        }
        // Its versions are a job of their own: their own screen, not another section here.
        if model.mode == .kiff, model.cloudTaf(for: taf.id) != nil {
            PanelLink { VersionsScreen(tafID: taf.id) } label: {
                Label("Versions", systemImage: "square.stack.3d.up")
                    .scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
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
                            Text(KIFFMapping.vendorWords(grant.vendor)).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Text("Keys kept sealed\(grant.account.map { " · \($0)" } ?? "")")
                                .scaledFont(12).foregroundColor(Theme.dim)
                            if grant.connected {
                                // What it may do, in words, not the tools' ids (kiff/tafs#138, item 52).
                                Text(grant.request.tools.filter(\.tafs).map(\.words).joined(separator: " · "))
                                    .scaledFont(12).foregroundColor(Theme.soft)
                                if model.leftOver(grant, of: tafID) {
                                    // Left over after a version moved its Card (kiff/tafs#138). An older version may still
                                    // run on it, so the words don't say to remove it.
                                    Text("Not on the live version's Card: the taf doesn't use it now. Going back to an older version may need it.")
                                        .scaledFont(12, weight: .semibold).foregroundColor(Theme.amberText)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            } else {
                                // Signed in, not connected (contract 5): no tool is in KIFF yet.
                                Text(grant.listed ? "Signed in · the tools are being chosen" : "Signed in · tools waiting for your approval")
                                    .scaledFont(12, weight: .semibold).foregroundColor(Theme.amberText)
                                if let end = grant.heldUntilDate {
                                    Text("Unless you approve its tools, the sign-in is revoked \(end.shown(date: .abbreviated, time: .shortened)).")
                                        .scaledFont(12).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            if let last = grant.events.last {
                                Text(last.what).scaledFont(12).foregroundColor(Theme.soft)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer(minLength: 0)
                        Button("Disconnect") { confirming = grant }
                            .scaledFont(14, weight: .semibold)
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
/// Try it opens a trial of a version beside the live one, under a Card of its own
/// (kiff/tafs#173); Install this version makes it live, forward or back, with one Face ID.
struct VersionsPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// The version whose Card the owner reads before Face ID.
    @State private var reading: TafsCloud.Version?
    /// The version whose trial the owner is about to start.
    @State private var starting: TafsCloud.Version?
    /// The trial just started: its chat opens (kiff/tafs#247).
    @State private var opened: UUID?
    @Environment(\.sidebarSelects) private var sidebarSelects

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
            Text("Try a version first: you chat with it under a Card of its own, while the live version keeps working. Installing one makes it the live version.")
                .scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            if let sw = model.switches[tafID] { switchUnderWay(sw) }
            if let n = model.staleVersionCards[tafID], let v = model.versions[tafID]?.first(where: { $0.n == n }) {
                Button("Draft its Card again") { use(v, redraft: true) }
                    .buttonStyle(OutlineButtonStyle())
            }
            if let list = model.versions[tafID] {
                if list.isEmpty {
                    Text("One version so far. When you improve it, each change is kept here.")
                        .scaledFont(14).foregroundColor(Theme.soft)
                }
                ForEach(list.reversed()) { row($0) }
            } else if model.versionErrors[tafID] == nil {
                ProgressView().tint(Theme.amber)
            }
            if let error = model.versionErrors[tafID] {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
            }
            if let n = model.blockedInstalls[tafID], let v = model.versions[tafID]?.first(where: { $0.n == n }) {
                // One step: what's in the way stops (a held call withdrawn, nothing sent), then
                // the install begins (kiff/tafs#206).
                Button("Stop it and install") { use(v, stopBlocking: true) }
                    .buttonStyle(OutlineButtonStyle())
            }
            if let error = model.trialErrors[tafID] {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
            }
        }
        .task(id: tafID) {
            await model.loadVersions(tafID)
            await model.loadTrial(tafID)
        }
        .sheet(item: $reading) { v in VersionCardSheet(version: v, tafID: tafID) }
        // Started: its chat opens; beside a sidebar, by selecting its row (kiff/tafs#253).
        .sheet(item: $starting) { v in
            TrialStartSheet(version: v, tafID: tafID) { chat in
                if sidebarSelects || model.sidebarShown { model.requestedTaf = chat } else { opened = chat }
            }
        }
        .navigationDestination(isPresented: Binding(get: { opened != nil }, set: { if !$0 { opened = nil } })) {
            if let chat = opened { TrialScreen(chatID: chat, tafID: tafID) }
        }
        // Terms that changed between the screen and the tap open again, to read anew.
        .onChange(of: model.changedVersionCards[tafID]) { changed in
            if changed != nil, reading == nil, let v = model.versions[tafID]?.first(where: { model.switchDraft($0, of: tafID) == changed?.id }) { reading = v }
        }
    }

    private func row(_ v: TafsCloud.Version) -> some View {
        let switching = model.switchingVersion[tafID]
        let trial = model.openTrials[tafID]
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text("Version \(v.n)").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                if v.live {
                    Text("Live now").scaledFont(11, weight: .bold).foregroundColor(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.ok).clipShape(Capsule())
                } else if trial?.n == v.n {
                    Text("Trying").scaledFont(11, weight: .bold).foregroundColor(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.amber).clipShape(Capsule())
                }
                Spacer(minLength: 0)
            }
            if let note = v.note, !note.isEmpty {
                Text(note).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            Text(Self.byline(v)).scaledFont(12).foregroundColor(Theme.dim)
            // What each ended trial came to, the one line kept once its chat is gone.
            ForEach(Array((v.trials ?? []).enumerated()), id: \.offset) { _, t in
                Text(Self.tried(t)).scaledFont(12).foregroundColor(Theme.soft)
            }
            HStack(spacing: 8) {
                if !v.live {
                    if trial?.n == v.n, let chat = model.trialChat(of: tafID) {
                        OpenTrialLink(chatID: chat, tafID: tafID) {
                            Text("Resume the trial")
                        }
                        .buttonStyle(OutlineButtonStyle())
                    } else {
                        // One trial per taf: another waits for the open one to end.
                        Button("Try it") { starting = v }
                            .buttonStyle(OutlineButtonStyle())
                            .disabled(trial != nil || model.trialBusy.contains(tafID))
                    }
                    Button(switching == v.n ? "Installing…" : "Install this version") {
                        use(v)
                    }
                    .buttonStyle(OutlineButtonStyle())
                    // One switch at a time, and none while a trial is open: Install ends the trial.
                    .disabled(switching != nil || trial != nil || model.switches[tafID].map { $0.n != v.n } ?? false)
                }
            }
        }
        .padding(.vertical, 4)
    }

    /// One ended trial, in a line: "Tried 21 Oct · 3 calls, 1 held · went back".
    static func tried(_ t: TafsCloud.TrialSummary) -> String {
        var counts = "\(t.calls) call\(t.calls == 1 ? "" : "s")"
        if t.held > 0 { counts += ", \(t.held) held" }
        if t.refused > 0 { counts += ", \(t.refused) refused" }
        let how: String
        switch t.why {
        case "installed": how = "installed"
        case "back": how = "went back"
        case "idle": how = "ended unused"
        default: how = "ended"
        }
        return ["Tried \(t.started.formatted(date: .abbreviated, time: .omitted))", counts, how].joined(separator: " · ")
    }

    /// A switch that didn't finish: the taf doesn't run until it's finished or cancelled.
    private func switchUnderWay(_ sw: TafsCloud.Switching) -> some View {
        let version = model.versions[tafID]?.first { $0.n == sw.n }
        let working = model.switchingVersion[tafID] != nil
        return VStack(alignment: .leading, spacing: 6) {
            Text("Installing version \(sw.n)").scaledFont(15, weight: .semibold).foregroundColor(Theme.amberText)
            Text("Your taf doesn't run until it's installed or you cancel.")
                .scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
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
        return [by, v.created.shown(date: .abbreviated, time: .shortened), v.draft == nil ? nil : "brings a new Card"]
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
                        .scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    if let draft, draft.cards != nil {
                        DraftCardsView(draft: draft)
                        Text(draft.retires?.isEmpty == false
                             ? "While it switches, your taf doesn't run. Confirming with \(Biometry.name) once issues these Cards in KIFF and removes the ones this version doesn't have, then this version goes live."
                             : "While it switches, your taf doesn't run. Confirming with \(Biometry.name) once issues these Cards in KIFF, then this version goes live.")
                            .scaledFont(12).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    } else if loaded {
                        Text(model.versionErrors[tafID] ?? "This version's Card couldn't be read.")
                            .scaledFont(13).foregroundColor(Theme.no)
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
}

/// The Cards a draft brings, as the owner reads them before Face ID: each Card it revises
/// as it is now and as it will be, each new one, and each it removes.
struct DraftCardsView: View {
    let draft: KIFFClient.AgentDraft

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            let lines = draft.cardLines
            ForEach(Array((draft.cards ?? []).enumerated()), id: \.offset) { i, card in cardView(card, lines: lines[i]) }
            ForEach(draft.retires ?? [], id: \.id) { retiredView($0) }
        }
    }

    /// A Card this version doesn't have: confirming removes it, so what it allows stops.
    private func retiredView(_ card: KIFFClient.AgentDraft.Retired) -> some View {
        Panel {
            Eyebrow(text: "Removes a Card")
            ForEach(card.current?.lines ?? [], id: \.self) {
                Text($0).strikethrough().scaledFont(14).foregroundColor(Theme.soft)
            }
            Text("Your taf can't do this any more.").scaledFont(12).foregroundColor(Theme.dim)
        }
    }

    @ViewBuilder
    /// One Card of the version, with its lines as the draft reads them (`cardLines`).
    private func cardView(_ card: KIFFClient.DraftCard, lines: [String]) -> some View {
        Panel {
            if card.isUnchanged {
                // Re-issued as it is (kiff/tafs#138): its terms once, not a now and after.
                Eyebrow(text: "Unchanged")
                ForEach(lines, id: \.self) { Text($0).scaledFont(14).foregroundColor(Theme.soft) }
            } else if let current = card.current {
                Eyebrow(text: "Changes a Card")
                Text("Now").scaledFont(12, weight: .semibold).foregroundColor(Theme.dim)
                ForEach(current.lines, id: \.self) { Text($0).scaledFont(14).foregroundColor(Theme.soft) }
                Text("After").scaledFont(12, weight: .semibold).foregroundColor(Theme.dim)
            } else {
                Eyebrow(text: "A new Card")
            }
            if !card.isUnchanged {
                ForEach(lines, id: \.self) { Text($0).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink) }
            }
        }
    }
}

/// What the taf remembers between its runs and chats, each note with a Forget button.
struct MemoryPanel: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    /// The note waiting for the owner's yes: forgetting can't be undone (the 755 retest).
    @State private var forgetting: TafsCloud.Note?

    var body: some View {
        Panel {
            Eyebrow(text: "What it remembers")
            if let notes = model.memories[tafID] {
                if notes.isEmpty {
                    Text("Nothing yet. It writes down what it learns about how you like the job done.")
                        .scaledFont(14).foregroundColor(Theme.soft)
                }
                ForEach(notes) { note in
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(note.content).scaledFont(15).foregroundColor(Theme.ink)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("\(note.title) · \(note.updatedAt.shortTime)").scaledFont(12).foregroundColor(Theme.soft)
                        }
                        Spacer(minLength: 0)
                        Button("Forget") { forgetting = note }
                            .scaledFont(14, weight: .semibold)
                            .foregroundColor(Theme.soft)
                            .frame(minHeight: 44)
                            .accessibilityLabel("Forget: \(note.content)")
                    }
                }
                Text("Every run and chat starts with these. They shape how it works, never what its Card allows. Forget removes that one note and its earlier versions; past chats and run reports stay until the taf is removed.")
                    .scaledFont(12).foregroundColor(Theme.soft)
            } else if model.memoryErrors[tafID] == nil {
                ProgressView().tint(Theme.amber)
            }
            if let error = model.memoryErrors[tafID] {
                Text(error).scaledFont(13).foregroundColor(Theme.no)
            }
        }
        .task(id: tafID) { await model.loadMemory(tafID) }
        .confirmationDialog("Forget this note?", isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } }),
                            titleVisibility: .visible, presenting: forgetting) { note in
            Button("Forget", role: .destructive) { Task { _ = await model.forget(note, from: tafID) } }
            Button("Cancel", role: .cancel) {}
        } message: { note in
            Text("\u{201C}\(note.content)\u{201D}\n\nIt can't be undone: the taf stops working with it from its next run or chat. Other notes stay.")
        }
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
                Button("Refresh", action: reload).scaledFont(13).foregroundColor(Theme.amberText)
            }
            if let report {
                if let at = report.at {
                    Text("\(at.shortTime) · \(report.finished ? "finished" : report.status == "running" ? "still running" : "no session")")
                        .scaledFont(12, design: .monospaced).foregroundColor(Theme.dim)
                }
                if !report.text.isEmpty {
                    // A taf writes markdown: shown as the chat shows it, never raw (kiff/tafs#96).
                    MarkdownText(report.text, size: 14).foregroundColor(Theme.ink).textSelection(.enabled)
                } else if report.session == nil && report.error == nil {
                    Text("No run yet.").scaledFont(14).foregroundColor(Theme.dim)
                } else if !report.finished && report.error == nil {
                    Text("Its report shows here when the run ends.").scaledFont(14).foregroundColor(Theme.dim)
                }
                if let error = report.error {
                    Text(error).scaledFont(13).foregroundColor(Theme.no)
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
    @Environment(\.dynamicTypeSize) private var textSize

    /// At most this wide, on the iPhone and the iPad alike.
    static let width: CGFloat = 300
    /// A payment card's proportions, 85.6 by 54 mm.
    static let ratio: CGFloat = 85.6 / 54

    /// A live Card in the taf's colour; one scoped to another environment in grey.
    static func fill(_ card: TafCard, color: Color) -> Color { card.live ? color : Theme.dim }

    var body: some View {
        face
            // At the accessibility text sizes the Card grows to its text, never clipping it
            // (kiff/tafs#210); otherwise it keeps a payment card's shape.
            .aspectRatio(textSize.isAccessibilitySize ? nil : Self.ratio, contentMode: .fit)
            .fixedSize(horizontal: false, vertical: textSize.isAccessibilitySize)
            .background(Self.fill(card, color: color).opacity(revoked || !card.active ? 0.4 : 1))
            .overlay(alignment: .topTrailing) {
                Circle().stroke(Color.white.opacity(0.18)).frame(width: 130, height: 130).offset(x: 35, y: -45)
                    .allowsHitTesting(false).accessibilityHidden(true)
            }
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .frame(maxWidth: Self.width)
            // VoiceOver reads its balance first (kiff/tafs#210).
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.spoken(card, issuedBy: issuedBy, revoked: revoked))
    }

    /// The Card as VoiceOver reads it: what's left, then its name, its limits, who issued it
    /// and until when.
    static func spoken(_ card: TafCard, issuedBy: String, revoked: Bool) -> String {
        var parts: [String] = []
        if let hero = card.hero { parts.append("\(hero.figure) \(hero.words)") }
        parts.append("\(card.title), \(card.environmentLabel)")
        parts += card.facts
        if card.rules.isEmpty { parts.append("No actions yet") }
        parts.append("Issued by \(issuedBy)")
        let end = card.expires?.formatted(date: .abbreviated, time: .omitted)
        parts.append(revoked ? "Revoked" : !card.active ? "Ended \(end ?? "")" : end.map { "Until \($0)" } ?? "No end date")
        return parts.map { $0.trimmingCharacters(in: .whitespaces) }.joined(separator: ". ") + "."
    }

    private var face: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(card.title).scaledFont(12, weight: .semibold, design: .monospaced).oneLineUnlessLarge()
                Spacer(minLength: 4)
                EnvironmentPill(text: card.environmentLabel)
            }
            Spacer(minLength: 0)
            if let hero = card.hero {
                VStack(alignment: .leading, spacing: 2) {
                    Text(hero.figure).scaledFont(34, weight: .bold, relativeTo: .title).monospacedDigit()
                        .oneLineUnlessLarge().minimumScaleFactor(0.6)
                    Text(hero.words).scaledFont(12, weight: .medium).opacity(0.9).oneLineUnlessLarge()
                }
            }
            ForEach(card.facts, id: \.self) { fact in
                Text(fact).scaledFont(11, design: .monospaced).opacity(0.92).oneLineUnlessLarge().minimumScaleFactor(0.8)
            }
            if card.rules.isEmpty { Text("no actions yet").scaledFont(12, design: .monospaced) }
            Spacer(minLength: 0)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Issued by").scaledFont(10).opacity(0.75)
                    Text(issuedBy).scaledFont(12, weight: .semibold).oneLineUnlessLarge()
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 1) {
                    // A Card with no end says so, never a far-off date (kiff/tafs#96).
                    Text(revoked ? "Revoked" : !card.active ? "Ended" : card.expires == nil ? "Ends" : "Until")
                        .scaledFont(10).opacity(0.75)
                    Text(card.expires?.shown(date: .abbreviated, time: .omitted) ?? "No end date")
                        .scaledFont(12, weight: .semibold)
                }
            }
        }
        .foregroundColor(.white)
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: textSize.isAccessibilitySize ? nil : .infinity, alignment: .topLeading)
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
                        .scaledFont(15, weight: .semibold).monospacedDigit().foregroundColor(Theme.ink)
                        .oneLineUnlessLarge().minimumScaleFactor(0.8)
                    Text(details).scaledFont(12).foregroundColor(Theme.dim).oneLineUnlessLarge()
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
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

/// Every action a Card covers, with its limits and what happens outside them: what the
/// owner reads in full on the Card tab and wherever they sign for a Card, since the face
/// shows only its main limit (kiff/tafs#211).
struct CardLines: View {
    let rules: [CardRule]

    var body: some View {
        Panel {
            ForEach(rules) { rule in
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.label).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(rule.outside == .hold ? "Outside: held for you" : "Outside: refused")
                            .scaledFont(12).foregroundColor(Theme.dim)
                    }
                    Spacer()
                    Text(rule.summary).scaledFont(13, design: .monospaced)
                        .foregroundColor(rule.never ? Theme.no : Theme.ink)
                        .multilineTextAlignment(.trailing)
                }
            }
        }
    }
}

/// LIVE, or the environment a Card is scoped to.
struct EnvironmentPill: View {
    let text: String
    var body: some View {
        Text(text).tracking(0.6).scaledFont(10, weight: .semibold, design: .monospaced)
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
                    withMotion(.spring(response: 0.35, dampingFraction: 0.85)) { front = card.id }
                } label: {
                    HStack(spacing: 8) {
                        Text(card.title).scaledFont(11, weight: .semibold, design: .monospaced).oneLineUnlessLarge()
                        Spacer(minLength: 4)
                        // What's left on it shows on its edge too, so no Card hides another's (kiff/tafs#236, item 9).
                        if let left = card.leftLine {
                            Text(left).scaledFont(12, weight: .bold).monospacedDigit().oneLineUnlessLarge()
                        }
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
                .accessibilityLabel("\(card.title), \(card.environmentLabel)\(card.leftLine.map { ", \($0)" } ?? ""). Show this Card")
            }
            if let shown {
                TafCardFace(card: shown, issuedBy: issuedBy, color: color, revoked: revoked)
                    .shadow(color: .black.opacity(0.12), radius: 3, y: -1)
            }
        }
        .frame(maxWidth: TafCardFace.width)
    }
}
