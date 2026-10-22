import SwiftUI

/// A halt (kiff/kiff-cloud#1061) of one taf or, with none, of every taf: its own screen,
/// opened from a row. Halting is one tap, no Face ID: it only takes authority away. It reads
/// Halted only once kiff/agents, KIFF and planque all hold it, Halting… until then. Resuming
/// asks for Face ID, and what the taf was doing before the halt doesn't come back.
struct HaltScreen: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID?
    @State private var busy = false

    private var name: String { tafID.flatMap { model.taf($0)?.name } ?? "All your tafs" }

    var body: some View {
        let h = model.haltState(tafID)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Panel {
                    Eyebrow(text: name, color: h.state == .running ? Theme.dim : Theme.no)
                    Text(Self.headline(h)).font(Theme.display(26)).foregroundColor(Theme.ink)
                    Text(Self.explanation(h, all: tafID == nil))
                        .font(.system(size: 15)).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if h.state != .running {
                    Panel {
                        Eyebrow(text: "Who holds the halt")
                        HoldRow(text: "No run starts", held: h.runtime)
                        HoldRow(text: "KIFF refuses its calls", held: h.kiff)
                        HoldRow(text: "planque sends nothing to your tools", held: h.planque)
                        if h.account && tafID != nil {
                            Text("Halted with all your tafs. Resume them all to let it run.")
                                .font(.system(size: 13)).foregroundColor(Theme.dim)
                        }
                    }
                }
                switch h.state {
                case .running:
                    Button(busy ? "Halting…" : (tafID == nil ? "Halt all now" : "Halt now")) { halt() }
                        .buttonStyle(OutlineButtonStyle(color: Theme.no))
                        .disabled(busy)
                case .halting:
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Halting… waiting for every part to confirm.").font(.system(size: 14)).foregroundColor(Theme.dim)
                    }
                    // Halt again: a part that didn't take it the first time is asked again.
                    Button("Halt again") { halt() }
                        .buttonStyle(OutlineButtonStyle(color: Theme.no))
                        .disabled(busy)
                case .halted:
                    if !(h.account && tafID != nil) {
                        Button(busy ? "Resuming…" : "Resume with Face ID") {
                            busy = true
                            Task { await model.resumeHalt(tafID); busy = false }
                        }
                        .buttonStyle(OutlineButtonStyle())
                        .disabled(busy)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .coachClearance()
        .screenBackground()
        .navigationTitle(tafID == nil ? "Halt all" : "Halt")
        .inlineTitle()
        // Read now, then again every 2 seconds while it is halting, for up to 2 minutes.
        .task(id: tafID) {
            await model.refreshHalt(tafID)
            for _ in 0..<60 where model.haltState(tafID).state == .halting {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                await model.refreshHalt(tafID)
            }
        }
    }

    private func halt() {
        busy = true
        Task {
            await model.halt(tafID)
            busy = false
            for _ in 0..<60 where model.haltState(tafID).state == .halting {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                await model.refreshHalt(tafID)
            }
        }
    }

    static func headline(_ h: TafsCloud.Halt) -> String {
        switch h.state {
        case .running: return "Running"
        case .halting: return "Halting…"
        case .halted: return "Halted"
        }
    }

    static func explanation(_ h: TafsCloud.Halt, all: Bool) -> String {
        let who = all ? "your tafs" : "it"
        switch h.state {
        case .running:
            return "Halting stops \(who) at once: no run starts, and nothing reaches your tools until you resume. A call already on its way may still arrive; nothing is undone."
        case .halting:
            return "Nothing new starts. Halted shows once KIFF and planque both confirm."
        case .halted:
            let since = h.haltedAt.map { " since \($0.shortTime)" } ?? ""
            return "Halted\(since). Nothing reaches your tools. When you resume, only new work runs: nothing from before the halt picks up again."
        }
    }
}

/// One part of a halt, and whether it holds it yet.
private struct HoldRow: View {
    let text: String
    let held: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: held ? "checkmark.circle.fill" : "circle.dotted")
                .foregroundColor(held ? Theme.ok : Theme.dim)
            Text(text).font(.system(size: 15)).foregroundColor(Theme.ink)
            Spacer()
            Text(held ? "Done" : "Waiting").font(.system(size: 13)).foregroundColor(Theme.dim)
        }
    }
}

/// The row that opens a taf's halt, or every taf's, with where it stands.
struct HaltRow: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID?

    var body: some View {
        let h = model.haltState(tafID)
        NavigationLink { HaltScreen(tafID: tafID) } label: {
            HStack(spacing: 12) {
                Image(systemName: h.state == .running ? "hand.raised" : "hand.raised.fill")
                    .font(.system(size: 16, weight: .semibold)).foregroundColor(Theme.no)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tafID == nil ? "Halt all" : "Halt").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                    Text(h.state == .running ? "Stop it at once, until you resume" : HaltScreen.headline(h))
                        .font(.system(size: 13)).foregroundColor(h.state == .running ? Theme.dim : Theme.no)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.dim)
            }
        }
        .buttonStyle(.plain)
        .task(id: tafID) { await model.refreshHalt(tafID) }
    }
}
