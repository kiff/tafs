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
}

/// The sidebar (Theme.raised): Coach mode, where tafs are made and changed, the calls
/// waiting for the owner, and the tafs with a line on each. The selected one opens beside it.
struct HomeSplitView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selection: HomeSelection?
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
        // "See it on…" in Setup selects the taf it made (kiff/tafs#96).
        .onChange(of: model.requestedTaf) { id in
            guard let id else { return }
            // A cross-fade, as iOS moves between a list's items, not a cut (the 755 retest).
            withMotion(.easeInOut(duration: 0.25)) { selection = .taf(id) }
            model.requestedTaf = nil
        }
        .sheet(isPresented: $showingNew) { NewTafFlow().environmentObject(model) }
        .sheet(isPresented: $showingSettings) { SettingsView().environmentObject(model) }
        // Something open from the start: the taf a call waits on, else the first (CI's
        // screenshots open Refunds, as on the phone).
        .task(id: model.tafs.map(\.id)) {
            if let selected = selection {
                switch selected {
                case .taf(let id), .held(_, let id): if model.taf(id) != nil { return }
                case .haltAll: return
                }
            }
            let screenshot = Screenshots.enabled ? model.tafs.first(where: { $0.name == "Refunds" })?.id : nil
            if let id = screenshot ?? model.held.first?.tafID ?? model.tafs.first?.id { selection = .taf(id) } else { selection = nil }
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

    private var sidebar: some View {
        List(selection: $selection) {
            if model.mode == .kiff {
                Section {
                    // Coach mode (kiff/tafs#138): where tafs are made, connected and changed,
                    // first in the sidebar.
                    Button { model.openCoach() } label: {
                        Label("Coach mode", systemImage: "sparkle").scaledFont(15, weight: .semibold).foregroundColor(Theme.coach)
                    }
                    // Every taf at once, from its own screen, as on the phone's home.
                    // The owner looked for it here (kiff/tafs#138).
                    if !model.cloudTafs.isEmpty {
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
            Section {
                ForEach(model.listedTafs) { taf in
                    NavigationLink(value: HomeSelection.taf(taf.id)) { tafRow(taf) }
                    // Its open trial, its own entry under it (kiff/tafs#173).
                    if let chat = model.trialChat(of: taf.id), let trying = model.taf(chat) {
                        NavigationLink(value: HomeSelection.taf(chat)) { tafRow(trying) }
                    }
                }
                if model.mode == .kiff, model.listLoaded, !model.listUnread, model.tafs.isEmpty {
                    Text("Connect a tool to set up your first taf.")
                        .scaledFont(13).foregroundColor(Theme.dim)
                        .listRowBackground(Color.clear)
                }
            } header: { Eyebrow(text: "Your tafs") }
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
                Text(taf.now).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim).oneLineUnlessLarge()
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
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
                Label("Settings", systemImage: "gearshape").scaledFont(14).foregroundColor(Theme.soft)
                    .frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Theme.raised)
    }
}
