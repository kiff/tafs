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

/// What the sidebar opens beside it.
enum HomeSelection: Hashable {
    case taf(UUID)
    /// A held call, shown on its taf's page.
    case held(UUID, taf: UUID)
    /// Setup, which connects the owner's tools.
    case setup
}

/// The sidebar (Theme.raised): the calls waiting for the owner, the tafs with a line on
/// each, Setup, and Start a new taf. The selected one opens beside it.
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
                    TafDetailView(tafID: id)
                case .setup?:
                    SetupChatView()
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
        }
        .navigationSplitViewStyle(.balanced)
        // "See it on…" in Setup selects the taf it made (kiff/tafs#96).
        .onChange(of: model.requestedTaf) { id in
            guard let id else { return }
            selection = .taf(id)
            model.requestedTaf = nil
        }
        .sheet(isPresented: $showingNew) {
            if model.mode == .kiff { StartTafFlow().environmentObject(model) } else { NewTafFlow().environmentObject(model) }
        }
        .sheet(isPresented: $showingSettings) { SettingsView().environmentObject(model) }
        // Something open from the start: the taf a call waits on, else the first (CI's
        // screenshots open Refunds, as on the phone).
        .task(id: model.tafs.map(\.id)) {
            if let selected = selection {
                switch selected {
                case .taf(let id), .held(_, let id): if model.taf(id) != nil { return }
                case .setup: return
                }
            }
            let screenshot = Screenshots.enabled ? model.tafs.first(where: { $0.name == "Refunds" })?.id : nil
            if let id = screenshot ?? model.held.first?.tafID ?? model.tafs.first?.id { selection = .taf(id) } else { selection = nil }
        }
    }

    private var sidebar: some View {
        List(selection: $selection) {
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
                ForEach(model.tafs) { taf in
                    NavigationLink(value: HomeSelection.taf(taf.id)) { tafRow(taf) }
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
            if model.mode == .kiff {
                Section {
                    // Setup (kiff/tafs#50, #82): connect the tools a taf works with.
                    NavigationLink(value: HomeSelection.setup) {
                        Label("Connected tools", systemImage: "link").scaledFont(15).foregroundColor(Theme.ink)
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .background(Theme.raised.ignoresSafeArea())
        .refreshable { await model.refresh() }
        .safeAreaInset(edge: .bottom) { footer }
        .navigationTitle("tafs")
    }

    private func tafRow(_ taf: Taf) -> some View {
        HStack(spacing: 12) {
            TafAvatar(taf: taf, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(taf.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink).lineLimit(1)
                Text(taf.now).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim).lineLimit(1)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private func heldRow(_ call: HeldCall, _ taf: Taf) -> some View {
        HStack(spacing: 10) {
            Circle().fill(Theme.amber).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(taf.name): \(call.proposed.summary)").scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).lineLimit(1)
                Text("\(call.reason) · answer by \(call.expiresAt.shortTime)")
                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.soft).lineLimit(1)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            if model.canStartTafs {
                Button { showingNew = true } label: { Label("Start a new taf", systemImage: "plus") }
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
