import SwiftUI

/// A taf's dial screen (K1 to K3 of the approved "The knob, end to end"): its name and what
/// waits on the owner, the dial, the chosen stop's line, today's work, then Overview, Ask
/// Coach and Pause. "Needs you" puts the taf's oldest held call in the face, where a tap
/// approves it once; the Card stays as it is.
struct TafDialScreen: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var face: TafKnobView.Face = .overview

    private var taf: Taf? { model.tafs.first { $0.id == tafID } }
    private var held: [HeldCall] { model.heldCalls(for: tafID).sorted { $0.heldAt < $1.heldAt } }
    private var paused: Bool { taf?.state == .paused }

    var body: some View {
        VStack(spacing: 16) {
            header
            if let knob = model.knobs[tafID] {
                TafKnobView(knob: knob, face: $face, held: held,
                            press: { _, item in Task { await model.pressKnob(tafID, item) } },
                            approve: { call in Task { await model.answer(call, with: .approveOnce) } })
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 280)
            }
            Spacer(minLength: 0)
            footer
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .background(Theme.background.ignoresSafeArea())
        .task(id: tafID) { await model.loadKnob(tafID) }
        .onChange(of: held.map(\.id)) { ids in
            // An answered call leaves the face.
            if case .held(let id) = face, !ids.contains(id) { face = .overview }
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(taf?.name ?? "").scaledFont(26, weight: .heavy, relativeTo: .title).foregroundColor(Theme.ink)
                .lineLimit(1).minimumScaleFactor(0.7)
            Spacer()
            Button {
                if let first = held.first { face = .held(first.id) }
            } label: {
                Text(status).scaledFont(13, weight: .semibold, relativeTo: .footnote)
                    .foregroundColor(held.isEmpty ? Theme.dim : Theme.amberText)
                    .padding(.horizontal, 12).frame(minHeight: 36)
                    .overlay(Capsule().stroke(held.isEmpty ? Theme.line : Theme.amber.opacity(0.5)))
            }
            .buttonStyle(.plain)
            .disabled(held.isEmpty)
            .accessibilityIdentifier("dial.status")
        }
    }

    private var status: String {
        if paused { return "● Paused" }
        return held.isEmpty ? "● Working" : "● Needs you · \(held.count)"
    }

    private var footer: some View {
        HStack(spacing: 8) {
            if face != .overview {
                Button("‹ Overview") { withMotion { face = .overview } }
                    .buttonStyle(OutlineButtonStyle())
            }
            Spacer()
            Button("Ask Coach") { model.openCoach() }
                .buttonStyle(OutlineButtonStyle())
            Button(paused ? "Resume" : "Pause") { Task { await model.setPaused(tafID, !paused) } }
                .buttonStyle(OutlineButtonStyle())
                .disabled(taf == nil)
        }
    }
}
