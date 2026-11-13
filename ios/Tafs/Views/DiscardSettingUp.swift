import SwiftUI

/// Discard, beside Finish setting up, for the person whose setup it was (docs/tafs.md): every word
/// is tafs cloud's (`setting_up[].discard.words`). The confirmation asks first; its yes revokes the
/// half-made taf's Cards and drops it, signed behind Face ID, as a removal is.
struct DiscardSettingUpButton: View {
    @EnvironmentObject private var model: AppModel
    let taf: Taf
    @State private var confirming = false
    @State private var discarding = false

    var body: some View {
        if let words = taf.discard {
            Button { confirming = true } label: {
                Text(discarding ? Words.Common.loading : words.button).padding(.horizontal, 16)
            }
            .buttonStyle(OutlineButtonStyle(color: Theme.soft))
            .fixedSize(horizontal: true, vertical: false)
            .disabled(discarding)
            .accessibilityIdentifier("home.discard-setup")
            .confirmationDialog(words.title.isEmpty ? words.button : words.title, isPresented: $confirming, titleVisibility: .visible) {
                Button(words.yes, role: .destructive) {
                    discarding = true
                    Task {
                        if let note = await model.discardSettingUp(taf.id) { model.lastError = note }
                        discarding = false
                    }
                }
                Button(words.no.isEmpty ? Words.Common.cancel : words.no, role: .cancel) {}
            } message: {
                if !words.text.isEmpty { Text(words.text) }
            }
        }
    }
}
