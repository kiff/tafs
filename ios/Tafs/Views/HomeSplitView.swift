import SwiftUI

/// The Tafs tab: on a wide screen (kiff/tafs#85) the tafs in a sidebar beside the open
/// taf; on a compact width, the iPhone's or an iPad's narrow split, the list that pushes.
struct TafsTab: View {
    @Binding var tab: Tab
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if sizeClass == .regular {
            HomeSplitView()
        } else {
            NavigationStack { HomeView(tab: $tab) }
        }
    }
}

/// Whether a sidebar beside this screen shows what's open (kiff/tafs#253): opening a taf or
/// a trial then selects its row, rather than pushing a screen the sidebar doesn't show.
private struct SidebarSelectsKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var sidebarSelects: Bool {
        get { self[SidebarSelectsKey.self] }
        set { self[SidebarSelectsKey.self] = newValue }
    }
}

/// What the sidebar opens beside it.
enum HomeSelection: Hashable {
    case taf(UUID)
    /// A held call, shown on its taf's page.
    case held(UUID, taf: UUID)
    /// Every taf's halt, as the phone's home has it.
    case haltAll
    /// Today across the tafs this person uses or oversees.
    case overview
}

/// The sidebar (Theme.raised): Coach mode, where tafs are made and changed, the calls
/// waiting for the owner, and the tafs with a line on each. The selected one opens beside it.
struct HomeSplitView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selection: HomeSelection?
    /// What the app opened on by itself, and whether the owner has chosen since: until they
    /// do, the first taf in the list is the one open (the 859 test).
    @State private var opened: HomeSelection?
    @State private var chosen = false
    @State private var showingNew = false
    @State private var showingSettings = false

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            NavigationStack {
                switch selection {
                case .taf(let id)?, .held(_, let id)?:
                    if let tafID = model.tafOfTrialChat(id) { TrialScreen(chatID: id, tafID: tafID) } else { TafDetailView(tafID: id) }
                case .haltAll?:
                    HaltScreen(tafID: nil)
                case .overview?:
                    OverviewView { open in withMotion(.easeInOut(duration: 0.25)) { selection = open } }
                case nil:
                    VStack(spacing: 12) {
                        Mascot(size: 72)
                        Text(model.loadingList ? "Loading your tafs…" : model.listUnread ? "Couldn't load your tafs."
                             : model.tafs.isEmpty ? "No tafs yet." : "Choose a taf.")
                            .scaledFont(16).foregroundColor(Theme.dim)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .screenBackground()
                }
            }
            // A new selection starts its own stack: nothing pushed for the last one stays.
            .id(selection)
            .environment(\.sidebarSelects, true)
        }
        .navigationSplitViewStyle(.balanced)
        .onAppear { model.sidebarShown = true }
        .onDisappear { model.sidebarShown = false }
        .onChange(of: selection) { s in
            if s != opened { chosen = true }
        }
        // "See it on…" in Setup selects the taf it made (kiff/tafs#96).
        .onChange(of: model.requestedTaf) { id in
            guard let id else { return }
            // A cross-fade, as iOS moves between a list's items, not a cut (the 755 retest).
            withMotion(.easeInOut(duration: 0.25)) { selection = .taf(id) }
            model.requestedTaf = nil
        }
        .sheet(isPresented: $showingNew) { NewTafFlow().environmentObject(model) }
        .sheet(isPresented: $showingSettings) { SettingsView().environmentObject(model) }
        // Something open from the start: the first taf in the list, which is the one used
        // last, a call waiting on it first (CI's screenshots open Refunds, as on the phone).
        // While the list settles at launch it follows its first taf, until the owner chooses
        // (the 859 test: the app opened Refunds while Refunds 4 led the list).
        .task(id: model.tafs.map(\.id) + [model.listedTafs.first?.id].compactMap { $0 }) {
            if chosen, let selected = selection {
                switch selected {
                case .taf(let id), .held(_, let id): if model.taf(id) != nil { return }
                case .haltAll, .overview: return
                }
            }
            opened = Self.opening(model.listedTafs, screenshot: Screenshots.enabled && !Screenshots.listSettlesOnReturn)
            chosen = false
            selection = opened
        }
    }

    private var haltAllRow: some View {
        let h = model.haltState(nil)
        return Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("Halt all").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                if h.state != .running {
                    Text(HaltScreen.headline(h)).scaledFont(13).foregroundColor(Theme.no)
                }
            }
        } icon: {
            Image(systemName: h.state == .running ? "hand.raised" : "hand.raised.fill").foregroundColor(Theme.no)
        }
        .task { await model.refreshHalt(nil) }
    }

    /// The taf the app opens on by itself: the first in the list as it stands, which puts the
    /// one a call waits on first, then the one used last (the 859 test). CI's screenshots open
    /// Refunds, as on the phone.
    static func opening(_ listed: [Taf], screenshot: Bool) -> HomeSelection? {
        let refunds = screenshot ? listed.first(where: { $0.name == "Refunds" })?.id : nil
        return (refunds ?? listed.first?.id).map { .taf($0) }
    }

    private var sidebar: some View {
        // Only the owner's taps write through this binding, so each one is a choice, a tap on
        // the taf already open too: the list settling later doesn't take them away from it.
        List(selection: Binding(get: { selection }, set: { chosen = true; selection = $0 })) {
            if model.mode == .kiff {
                Section {
                    // Coach mode (kiff/tafs#138): where tafs are made, connected and changed,
                    // first in the sidebar.
                    if model.mayUseCoach {
                        Button { model.openCoach() } label: {
                            Label("Coach mode", systemImage: "sparkle").scaledFont(15, weight: .semibold).foregroundColor(Theme.coach)
                        }
                    }
                    // Today across the tafs, one line each.
                    if !model.tafs.isEmpty {
                        NavigationLink(value: HomeSelection.overview) {
                            Label("Overview", systemImage: "square.grid.2x2").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        }
                    }
                    // Every taf at once, from its own screen, as on the phone's home.
                    // The owner looked for it here (kiff/tafs#138).
                    if !model.cloudTafs.isEmpty, model.canManageCards {
                        NavigationLink(value: HomeSelection.haltAll) { haltAllRow }
                    }
                }
            }
            if !model.held.isEmpty {
                Section {
                    ForEach(model.held) { call in
                        if let taf = model.taf(call.tafID) {
                            NavigationLink(value: HomeSelection.held(call.id, taf: taf.id)) { heldRow(call, taf) }
                        }
                    }
                } header: { Eyebrow(text: "Needs you", color: Theme.amber) }
            }
            let sections = model.tafSections
            // Theirs, then the ones shared with them (kiff/tafs#306); someone with only shared
            // tafs sees just those.
            if !sections.own.isEmpty || sections.shared.isEmpty {
                Section {
                    ForEach(sections.own) { taf in tafLinks(taf) }
                    if model.mode == .kiff, model.listLoaded, !model.listUnread, model.tafs.isEmpty {
                        Text("Connect a tool to set up your first taf.")
                            .scaledFont(13).foregroundColor(Theme.dim)
                            .listRowBackground(Color.clear)
                    }
                } header: { Eyebrow(text: "Your tafs") }
            }
            if !sections.shared.isEmpty {
                Section {
                    ForEach(sections.shared) { taf in tafLinks(taf) }
                } header: { Eyebrow(text: "Shared with you") }
            }
            if !model.otherAgents.isEmpty {
                // Agents made through KIFF's API, not run by tafs: their Cards, apart.
                Section {
                    ForEach(model.otherAgents) { taf in
                        NavigationLink(value: HomeSelection.taf(taf.id)) { tafRow(taf) }
                    }
                } header: { Eyebrow(text: "Other agents with Cards") }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .background(Theme.raised.ignoresSafeArea())
        .refreshable { await model.refresh() }
        .safeAreaInset(edge: .bottom) { footer }
        .navigationTitle("tafs")
    }

    /// A new taf starts in Coach mode, a conversation (kiff/tafs#138, item 59); the sample
    /// keeps its own form, with nothing real behind it.
    private func startNewTaf() {
        if model.mode == .kiff { model.startNewTaf() } else { showingNew = true }
    }

    /// A taf in the list, and its open trial under it (kiff/tafs#173).
    @ViewBuilder private func tafLinks(_ taf: Taf) -> some View {
        // No tap gesture of its own: one on the row took the list's taps over its icon and
        // name, so only the row's empty end selected it (the 901 test).
        NavigationLink(value: HomeSelection.taf(taf.id)) { tafRow(taf) }
        if let chat = model.trialChat(of: taf.id), let trying = model.taf(chat) {
            NavigationLink(value: HomeSelection.taf(chat)) { tafRow(trying) }
        }
    }

    private func tafRow(_ taf: Taf) -> some View {
        HStack(spacing: 12) {
            TafAvatar(taf: taf, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(taf.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                    if let badge = taf.badge {
                    Text(badge).scaledFont(11, weight: .bold).foregroundColor(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Theme.amber).clipShape(Capsule())
                    }
                }
                if let out = model.signedOut(taf.id) {
                    Text(out.signedOutWords).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.amberText).oneLineUnlessLarge()
                } else {
                    Text(taf.now).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim).oneLineUnlessLarge()
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("taf-row")
    }

    private func heldRow(_ call: HeldCall, _ taf: Taf) -> some View {
        HStack(spacing: 10) {
            Circle().fill(Theme.amber).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(taf.name): \(call.proposed.summary)").scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                Text("\(call.reason) · answer by \(call.expiresAt.zonedShortTime)")
                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.soft).oneLineUnlessLarge()
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            if model.canStartTafs {
                Button { startNewTaf() } label: { Label("Start a new taf", systemImage: "plus") }
                    .buttonStyle(PrimaryButtonStyle())
            }
            Button { showingSettings = true } label: {
                VStack(spacing: 2) {
                    Label("Settings", systemImage: "gearshape").scaledFont(14).foregroundColor(Theme.soft)
                    // The account this is, always in sight (the 870 test).
                    if let account = model.accountLabel {
                        Text(account).scaledFont(12).foregroundColor(Theme.dim).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(model.accountLabel.map { "Settings, in \($0)" } ?? "Settings")
            // KIFF can't be reached just now (the 930 test): under the account's name, with
            // the status page; nothing when all is well.
            if let words = model.unreachable { UnreachableLine(words: words, retrying: model.retryingRefresh) }
            // The plan, only when it needs the owner: a trial ended or ending, tasks nearly gone.
            PlanLine()
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Theme.raised)
    }
}
