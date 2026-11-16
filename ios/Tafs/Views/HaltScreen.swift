import SwiftUI
import AppIntents

/// A halt of one taf or, with none, of every taf: its own screen,
/// opened from a row. Halting is one tap, no Face ID: it only takes authority away. It reads
/// Halted only once kiff/agents, KIFF and planque all hold it, Halting… until then. Resuming
/// asks for Face ID, and what the taf was doing before the halt doesn't come back.
struct HaltScreen: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID?
    @State private var busy = false

    private var name: String { tafID.flatMap { model.taf($0)?.name } ?? Words.Taf.Halt.allTafs }

    var body: some View {
        let h = model.haltState(tafID)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Panel {
                    Eyebrow(text: name, color: h.state == .running ? Theme.dim : Theme.no)
                    Text(Self.headline(h)).scaledFont(26, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    Text(Self.explanation(h, all: tafID == nil))
                        .scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                // Siri's own tip, so turning it on isn't a question asked in an emergency
                // (kiff/tafs#236, item 28). Not on the Mac, which has no such tip (docs/mac-app.md).
                #if !targetEnvironment(macCatalyst)
                if tafID == nil, h.state == .running {
                    SiriTipView(intent: HaltAllTafsIntent())
                }
                #endif
                if h.state != .running {
                    Panel {
                        Eyebrow(text: Words.Taf.Halt.whoHolds)
                        HoldRow(text: Words.Taf.Halt.noRunStarts, held: h.runtime)
                        HoldRow(text: Words.Taf.Halt.kiffRefuses, held: h.kiff)
                        HoldRow(text: Words.Taf.Halt.connectionSendsNothing, held: h.planque)
                        if h.account && tafID != nil {
                            Text(Words.Taf.Halt.haltedWithAll)
                                .scaledFont(13).foregroundColor(Theme.dim)
                        }
                    }
                }
                switch h.state {
                case .running:
                    Button(busy ? Words.Taf.Halt.halting : (tafID == nil ? Words.Halt.allNow : Words.Halt.now)) { halt() }
                        .buttonStyle(OutlineButtonStyle(color: Theme.no))
                        .confirming()
                        .disabled(busy)
                case .halting:
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(Words.Taf.Halt.haltingWait).scaledFont(14).foregroundColor(Theme.dim)
                    }
                    // Halt again: a part that didn't take it the first time is asked again.
                    Button(Words.Halt.again) { halt() }
                        .buttonStyle(OutlineButtonStyle(color: Theme.no))
                        .confirming()
                        .disabled(busy)
                case .halted:
                    if !(h.account && tafID != nil) {
                        Button(busy ? Words.Taf.Halt.resuming : Words.Taf.Halt.resumeWith(biometry: "\(Biometry.name)")) {
                            busy = true
                            Task { await model.resumeHalt(tafID); busy = false }
                        }
                        .buttonStyle(OutlineButtonStyle())
                        .confirming()
                        .disabled(busy)
                    }
                }
                if let error = model.haltError(tafID) {
                    Text(error).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(tafID == nil ? Words.Taf.Halt.titleAll : Words.Taf.Halt.title)
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
        case .running: return Words.Taf.Now.running
        case .halting: return Words.Taf.Halt.halting
        case .halted: return Words.Taf.State.halted
        }
    }

    static func explanation(_ h: TafsCloud.Halt, all: Bool) -> String {
        let who = all ? Words.Halt.yourTafsLower : Words.Record.On.it
        switch h.state {
        case .running:
            return Words.Halt.haltingStopsNoRunStarts(who: "\(who)")
        case .halting:
            return Words.Halt.Explain.halting
        case .halted:
            return h.haltedAt.map { Words.Halt.Explain.haltedSince(time: $0.shortTime) } ?? Words.Halt.Explain.halted
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
            Text(text).scaledFont(15).foregroundColor(Theme.ink)
            Spacer()
            Text(held ? Words.Taf.Halt.partHeld : Words.Taf.Halt.partWaiting).scaledFont(13).foregroundColor(Theme.dim)
        }
    }
}

/// The row that opens a taf's halt, or every taf's, with where it stands.
struct HaltRow: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID?
    /// In a panel of its own, which takes the tap all over, padding too (the 755 retest).
    var framed = false

    var body: some View {
        NavigationLink { HaltScreen(tafID: tafID) } label: {
            if framed {
                Panel { row }.contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            } else {
                row
            }
        }
        .buttonStyle(.drivablePlain)
        .task(id: tafID) { await model.refreshHalt(tafID) }
    }

    private var row: some View {
        let h = model.haltState(tafID)
        return HStack(spacing: 12) {
            Image(systemName: h.state == .running ? "hand.raised" : "hand.raised.fill")
                .scaledFont(16, weight: .semibold).foregroundColor(Theme.no)
            VStack(alignment: .leading, spacing: 2) {
                Text(tafID == nil ? Words.Taf.Halt.titleAll : Words.Taf.Halt.title).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                Text(h.state == .running ? Words.Halt.rowRunning : HaltScreen.headline(h))
                    .scaledFont(13).foregroundColor(h.state == .running ? Theme.dim : Theme.no)
            }
            Spacer()
            Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
        }
        // The whole row opens it, not only its words and caret (kiff/tafs#236, item 12).
        .contentShape(Rectangle())
    }
}
