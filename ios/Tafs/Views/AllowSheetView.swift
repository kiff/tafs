import SwiftUI

/// "Allow this from now on" (docs/allow-from-now-on.md): tafs cloud's change, before and after,
/// in its words; KIFF's own lines for each Card that changes, under them; what it would have
/// done; the held call's switch; and one signature, only when KIFF's draft is what was offered.
/// A `none` says tafs cloud's words, with nothing to sign.
struct AllowSheetView: View {
    @EnvironmentObject private var model: AppModel
    /// The sheet as it was opened. What's drawn is the model's, as it changes.
    let open: AppModel.AllowSheet

    private var now: AppModel.AllowSheet { model.allow?.call.id == open.call.id ? (model.allow ?? open) : open }

    var body: some View {
        let open = now
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(Words.NeedsYou.allowTitle).scaledFont(22, weight: .bold).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
                    if let answer = open.answer {
                        if let offer = answer.offer {
                            offered(open, offer)
                        } else {
                            // No offer, whatever its reason, one this app knows or not: tafs cloud's words, nothing to sign.
                            Text(answer.words ?? "").scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("allow.none")
                        }
                    } else if open.busy {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                    if let error = open.error {
                        Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(20)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { model.allow = nil } } }
        }
    }

    @ViewBuilder private func offered(_ open: AppModel.AllowSheet, _ offer: TafsCloud.AllowOffer) -> some View {
        ForEach(Array(offer.changes.enumerated()), id: \.offset) { _, change in
            VStack(alignment: .leading, spacing: 2) {
                Text(change.wordsBefore ?? "").scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                Text("→ " + (change.wordsAfter ?? "")).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
        }
        // KIFF's own lines for each Card that changes, as it keeps them: what's signed.
        if let draft = open.draft {
            let lines = zip(draft.cards ?? [], draft.cardLines).filter { !$0.0.isUnchanged }.flatMap { $0.1 }
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
            }
        }
        if let would = offer.would?.words, !would.isEmpty {
            Text(would).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
        }
        if !open.matches {
            // A draft that couldn't be read says so in the error line alone: it isn't one that differs from the offer.
            if open.draft != nil || (!open.busy && open.error == nil) {
                Text(Words.NeedsYou.allowNotOffered).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("allow.not-offered")
            }
        } else {
            Toggle(isOn: Binding(get: { open.sendHeld }, set: { model.allow?.sendHeld = $0 })) {
                Text(Words.NeedsYou.allowSendThisToo).scaledFont(15).foregroundColor(Theme.ink)
            }
            .disabled(open.busy)
            .accessibilityIdentifier("allow.send-held")
            if !open.sendHeld {
                Text(Words.NeedsYou.allowSwitchOff).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
            if !open.rounded, let figure = offer.round?.words, !figure.isEmpty {
                Button(Words.NeedsYou.allowRoundUp(amount: "\(figure)")) { model.roundAllow() }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(open.busy)
                    .accessibilityIdentifier("allow.round")
            }
            if model.showsApprovalKeyLost {
                ApprovalKeyLostLine()
            } else {
                ApprovalGate {
                    Button(open.busy ? Words.Approval.approving : Words.Approval.approveWith(biometry: "\(Biometry.name)")) {
                        Task { await model.confirmAllow() }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(open.busy)
                    .accessibilityIdentifier("allow.confirm")
                }
            }
        }
    }
}
