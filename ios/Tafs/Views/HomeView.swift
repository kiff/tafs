import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var tab: Tab
    @State private var showingNew = false
    @State private var showingSettings = false
    @State private var showingAddTasks = false
    @State private var showingSolo = false
    @State private var openingFirst = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Eyebrow(text: "They taf. You kiff.")
                headline

                if let first = model.held.first, let taf = model.taf(first.tafID) {
                    Button { tab = .needsYou } label: { needsYouStrip(first, taf) }
                        .buttonStyle(.plain)
                }

                VStack(spacing: 10) {
                    ForEach(model.listedTafs) { taf in
                        NavigationLink(value: taf.id) { TafRow(taf: taf) }
                            .buttonStyle(.plain)
                        // Its open trial, its own entry under it (kiff/tafs#173).
                        if let chat = model.trialChat(of: taf.id), let trying = model.taf(chat) {
                            NavigationLink(value: chat) { TafRow(taf: trying) }
                                .buttonStyle(.plain)
                        }
                    }
                }

                if !model.otherAgents.isEmpty {
                    // Agents made through KIFF's API, not run by tafs: their Cards, apart.
                    VStack(alignment: .leading, spacing: 10) {
                        Eyebrow(text: "Other agents with Cards")
                        ForEach(model.otherAgents) { taf in
                            NavigationLink(value: taf.id) { TafRow(taf: taf) }
                                .buttonStyle(.plain)
                        }
                    }
                }

                if model.mode == .kiff {
                    // Connecting tools is Coach mode's (kiff/tafs#138).
                    Button { model.openCoach() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "plus").scaledFont(16, weight: .bold).foregroundColor(.white)
                                .frame(width: 36, height: 36).background(Theme.brand).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Connect a tool").scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                                Text("You sign in and approve each step").scaledFont(13).foregroundColor(Theme.dim)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                        }
                        .padding(14)
                        .background(Theme.panel)
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line))
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }

                if model.mode == .kiff, !model.cloudTafs.isEmpty {
                    // Every taf at once, from its own screen.
                    HaltRow(tafID: nil, framed: true)
                }

                ForEach(model.unfinishedRemovals) { r in
                    Panel {
                        Text("\(r.name) isn't fully removed yet.").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text("It no longer runs. tafs deletes what's left each time it refreshes.")
                            .scaledFont(14).foregroundColor(Theme.soft)
                    }
                }

                if model.mode == .kiff, model.listLoaded, !model.listUnread, model.tafs.isEmpty {
                    Panel {
                        Text("No tafs yet.").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text("Connect a tool to set up your first taf. Pull down to refresh.")
                            .scaledFont(14).foregroundColor(Theme.soft)
                    }
                }

                if model.mode == .sample {
                    Button("Play the next sample actions") { model.playNextDemoActions() }
                        .buttonStyle(OutlineButtonStyle(color: Theme.soft))
                    Text("Sample data. The Card is checked on this phone; signed in with KIFF, KIFF decides.")
                        .scaledFont(12)
                        .foregroundColor(Theme.dim)
                } else {
                    Text("Signed in with KIFF. KIFF decides every action. Pull down to refresh.")
                        .scaledFont(12)
                        .foregroundColor(Theme.dim)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .coachClearance()
        .refreshable { await model.refresh() }
        .screenBackground()
        .navigationDestination(for: UUID.self) { id in
            if let tafID = model.tafOfTrialChat(id) { TrialScreen(chatID: id, tafID: tafID) } else { TafDetailView(tafID: id) }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { showingSettings = true } label: {
                    Mascot(size: 34)
                }
                .accessibilityLabel("Settings")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                if model.canStartTafs {
                    // A new taf starts in Coach mode, a conversation (kiff/tafs#138, item 59).
                    Button { if model.mode == .kiff { model.startNewTaf() } else { showingNew = true } } label: {
                        Image(systemName: "plus")
                            .scaledFont(17, weight: .bold)
                            .foregroundColor(Theme.amberInk)
                            .frame(width: 36, height: 36)
                            .background(Theme.brand)
                            .clipShape(Circle())
                    }
                    .accessibilityLabel("Start a new taf")
                }
            }
        }
        .sheet(isPresented: $showingNew) { NewTafFlow().environmentObject(model) }
        .sheet(isPresented: $showingSettings) { SettingsView().environmentObject(model) }
        // The purchase screens, for their screenshots (Screenshots.screen).
        .sheet(isPresented: $showingAddTasks) { AddTasksView().environmentObject(model) }
        .sheet(isPresented: $showingSolo) { NavStack { SoloView() }.environmentObject(model) }
        .navigationDestination(isPresented: $openingFirst) {
            if let id = model.tafs.first(where: { $0.name == "Refunds" })?.id ?? model.tafs.first?.id {
                TafDetailView(tafID: id)
            }
        }
        .task(id: model.tafs.count) {
            guard Screenshots.enabled, model.tafs.count >= TafTemplates.all.count else { return }
            try? await Task.sleep(nanoseconds: 500_000_000)
            switch Screenshots.screen {
            case "taf", "chat", "card": openingFirst = true
            case "new": showingNew = true
            case "add-tasks": showingAddTasks = true
            case "solo": showingSolo = true
            default: break
            }
        }
    }

    private var headline: some View {
        let working = model.workingCount
        let waiting = model.held.count
        return VStack(alignment: .leading, spacing: 0) {
            if model.loadingList {
                // Never "0 tafs working" before the tafs are read (#91).
                Text("Loading your tafs…")
                    .foregroundColor(Theme.dim)
            } else if model.listUnread {
                Text("Couldn't load your tafs.")
                    .foregroundColor(Theme.dim)
            } else {
                Text("\(working) \(working == 1 ? "taf" : "tafs") working.")
                    .foregroundColor(Theme.ink)
                Text(waiting == 0 ? "Nothing needs you." : "\(waiting) \(waiting == 1 ? "needs" : "need") you.")
                    .foregroundColor(Theme.dim)
            }
        }
        .scaledFont(36, weight: .bold, design: .rounded, relativeTo: .title)
    }

    private func needsYouStrip(_ call: HeldCall, _ taf: Taf) -> some View {
        HStack(spacing: 12) {
            Circle().fill(Theme.amber).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(taf.name): \(call.proposed.summary)")
                    .scaledFont(14, weight: .semibold)
                    .foregroundColor(Theme.ink)
                    .oneLineUnlessLarge()
                Text("\(call.reason) · answer by \(call.expiresAt.zonedShortTime)")
                    .scaledFont(13)
                    .foregroundColor(Theme.soft)
            }
            Spacer()
            Text("Answer").scaledFont(14, weight: .medium).foregroundColor(Theme.amberText)
        }
        .padding(14)
        .background(Theme.amber.opacity(0.1))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.amber.opacity(0.4)))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct TafRow: View {
    let taf: Taf

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                TafAvatar(taf: taf)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(taf.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        if let badge = taf.badge {
                        Text(badge).scaledFont(11, weight: .bold).foregroundColor(.white)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(Theme.amber).clipShape(Capsule())
                    }
                    }
                    Text(taf.now).scaledFont(13).foregroundColor(Theme.dim).oneLineUnlessLarge()
                }
                Spacer(minLength: 8)
                StateChip(state: taf.state)
            }
            if let rule = taf.card.rules.first(where: { $0.perWindow != nil }), let limit = rule.perWindow {
                let used = taf.used[rule.action] ?? .zero
                // KIFF couldn't read the use: say so, never show an empty meter.
                let unknown = taf.usageUnknown?.contains(rule.action) == true
                VStack(spacing: 6) {
                    HStack {
                        Text("\(rule.label) · \(limit.formatted) \(rule.window.label)")
                        Spacer()
                        Text(unknown ? "use unavailable" : used.formatted).scaledFont(12, design: .monospaced)
                    }
                    .scaledFont(12)
                    .foregroundColor(Theme.dim)
                    if !unknown {
                        Meter(fraction: Double(used.minor) / Double(max(limit.minor, 1)), color: Theme.tafColor(taf.colorIndex))
                    }
                }
            }
        }
        .padding(16)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .combine)
    }
}
