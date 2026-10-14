import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var tab: Tab
    @State private var showingNew = false

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

                Button("Play the next sample actions") { model.playNextDemoActions() }
                    .buttonStyle(OutlineButtonStyle(color: Theme.soft))
                Text("Sample data. The Card is checked on this phone; with KIFF connected, KIFF decides.")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.dim)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .refreshable { model.playNextDemoActions() }
        .screenBackground()
        .navigationDestination(for: UUID.self) { TafDetailView(tafID: $0) }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showingNew = true } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(Theme.amberInk)
                        .frame(width: 36, height: 36)
                        .background(Theme.amber)
                        .clipShape(Circle())
                }
                .accessibilityLabel("Start a new taf")
            }
        }
        .sheet(isPresented: $showingNew) { NewTafFlow() }
    }

    private var headline: some View {
        let working = model.workingCount
        let waiting = model.held.count
        return VStack(alignment: .leading, spacing: 0) {
            Text("\(working) \(working == 1 ? "taf" : "tafs") working.")
                .foregroundColor(Theme.ink)
            Text(waiting == 0 ? "Nothing needs you." : "\(waiting) \(waiting == 1 ? "needs" : "need") you.")
                .foregroundColor(Theme.dim)
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
                VStack(spacing: 6) {
                    HStack {
                        Text("\(rule.label) · \(limit.formatted) \(rule.window.label)")
                        Spacer()
                        Text(used.formatted).font(Theme.mono(12))
                    }
                    .font(.system(size: 12))
                    .foregroundColor(Theme.dim)
                    Meter(fraction: Double(used.minor) / Double(max(limit.minor, 1)), color: Theme.tafColor(taf.colorIndex))
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
