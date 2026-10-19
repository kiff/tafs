import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var tab: Tab
    @State private var showingNew = false
    @State private var showingSettings = false
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
                    ForEach(model.tafs) { taf in
                        NavigationLink(value: taf.id) { TafRow(taf: taf) }
                            .buttonStyle(.plain)
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
                    // Setup (kiff/tafs#50): connect the tools a taf works with.
                    NavigationLink { SetupChatView() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "plus").font(.system(size: 16, weight: .bold)).foregroundColor(.white)
                                .frame(width: 36, height: 36).background(Theme.brand).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Connect a tool").font(.system(size: 16, weight: .semibold)).foregroundColor(Theme.ink)
                                Text("You sign in and approve each step").font(.system(size: 13)).foregroundColor(Theme.dim)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.dim)
                        }
                        .padding(14)
                        .background(Theme.panel)
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line))
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }

                ForEach(model.unfinishedRemovals) { r in
                    Panel {
                        Text("\(r.name) isn't fully removed yet.").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                        Text("It no longer runs. tafs deletes what's left each time it refreshes.")
                            .font(.system(size: 14)).foregroundColor(Theme.soft)
                    }
                }

                if model.mode == .kiff, model.listLoaded, !model.listUnread, model.tafs.isEmpty {
                    Panel {
                        Text("No tafs yet.").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                        Text("Connect a tool to set up your first taf. Pull down to refresh.")
                            .font(.system(size: 14)).foregroundColor(Theme.soft)
                    }
                }

                if model.mode == .sample {
                    Button("Play the next sample actions") { model.playNextDemoActions() }
                        .buttonStyle(OutlineButtonStyle(color: Theme.soft))
                    Text("Sample data. The Card is checked on this phone; signed in with KIFF, KIFF decides.")
                        .font(.system(size: 12))
                        .foregroundColor(Theme.dim)
                } else {
                    Text("Signed in with KIFF. KIFF decides every action. Pull down to refresh.")
                        .font(.system(size: 12))
                        .foregroundColor(Theme.dim)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .refreshable { await model.refresh() }
        .screenBackground()
        .navigationDestination(for: UUID.self) { TafDetailView(tafID: $0) }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { showingSettings = true } label: {
                    Mascot(size: 34)
                }
                .accessibilityLabel("Settings")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                if model.canStartTafs {
                    Button { showingNew = true } label: {
                        Image(systemName: "plus")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundColor(Theme.amberInk)
                            .frame(width: 36, height: 36)
                            .background(Theme.brand)
                            .clipShape(Circle())
                    }
                    .accessibilityLabel("Start a new taf")
                }
            }
        }
        .sheet(isPresented: $showingNew) {
            if model.mode == .kiff { StartTafFlow().environmentObject(model) } else { NewTafFlow().environmentObject(model) }
        }
        .sheet(isPresented: $showingSettings) { SettingsView().environmentObject(model) }
        .navigationDestination(isPresented: $openingFirst) {
            if let id = model.tafs.first(where: { $0.name == "Refunds" })?.id ?? model.tafs.first?.id {
                TafDetailView(tafID: id)
            }
        }
        .task(id: model.tafs.count) {
            guard Screenshots.enabled, model.tafs.count >= TafTemplates.all.count else { return }
            try? await Task.sleep(nanoseconds: 500_000_000)
            switch Screenshots.screen {
            case "taf", "chat": openingFirst = true
            case "new": showingNew = true
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
        .font(Theme.display(36))
    }

    private func needsYouStrip(_ call: HeldCall, _ taf: Taf) -> some View {
        HStack(spacing: 12) {
            Circle().fill(Theme.amber).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(taf.name): \(call.proposed.summary)")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Theme.ink)
                    .lineLimit(1)
                Text("\(call.reason) · answer by \(call.expiresAt.shortTime)")
                    .font(.system(size: 13))
                    .foregroundColor(Theme.soft)
            }
            Spacer()
            Text("Answer").font(.system(size: 14, weight: .medium)).foregroundColor(Theme.amber)
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
                    Text(taf.name).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                    Text(taf.now).font(.system(size: 13)).foregroundColor(Theme.dim).lineLimit(1)
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
                        Text(unknown ? "use unavailable" : used.formatted).font(Theme.mono(12))
                    }
                    .font(.system(size: 12))
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
