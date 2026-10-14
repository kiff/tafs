import SwiftUI

struct TafDetailView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var section = 0
    @State private var confirmRevoke = false
    @State private var runStarted = false

    var body: some View {
        if let taf = model.taf(tafID) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header(taf)
                    Picker("View", selection: $section) {
                        Text("Activity").tag(0)
                        Text("Card").tag(1)
                    }
                    .pickerStyle(.segmented)

                    if section == 0 { activity(taf) } else { card(taf) }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .screenBackground()
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog("Revoke \(taf.name)'s Card?", isPresented: $confirmRevoke, titleVisibility: .visible) {
                Button("Revoke and stop", role: .destructive) { Task { await model.revoke(tafID) } }
            } message: {
                Text("It stops at once. Anything already done stays done.")
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
        if let cloud = model.cloudTaf(for: taf.id) {
            Panel {
                Eyebrow(text: "Runs")
                Text(cloud.paused ? "Paused" : cloud.nextRun.map { "Next run at \($0.shortTime)" } ?? "On its schedule")
                    .font(.system(size: 15)).foregroundColor(Theme.ink)
                if let last = cloud.lastRun {
                    Text("Last run at \(last.shortTime)").font(.system(size: 13)).foregroundColor(Theme.dim)
                }
                Button(runStarted ? "Run started" : "Run now") {
                    Task { runStarted = await model.runNow(cloud) != nil }
                }
                .buttonStyle(OutlineButtonStyle())
                .disabled(runStarted)
            }
        }
        ForEach(model.heldCalls(for: taf.id)) { HeldCallCard(call: $0) }
        let entries = model.entries(for: taf.id)
        if entries.isEmpty {
            Text("Nothing yet.").foregroundColor(Theme.dim)
        }
        ForEach(entries) { RecordRow(entry: $0) }
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
    }
}

/// A Card drawn as a card: the limits, who issued it, until when.
struct CardFace: View {
    let name: String
    let rules: [CardRule]
    let issuedBy: String
    let expires: Date
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
                    Text(revoked ? "Revoked" : "Until").font(.system(size: 11)).opacity(0.7)
                    Text(expires.formatted(date: .abbreviated, time: .omitted)).font(.system(size: 13, weight: .semibold))
                }
            }
        }
        .foregroundColor(Theme.background)
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
