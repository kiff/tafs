#if os(iOS)
import SwiftUI
import UIKit

/// The mic in a chat's message bar (kiff/tafs#208), the same in a taf's chat and in Coach
/// mode: tap to dictate into the box, tap again (or send) to stop. Nothing is sent by
/// voice: the words wait in the box until the owner sends them. It's always there, so
/// dictating can go on after what's already in the box.
struct MicButton: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var dictation: Dictation
    @Binding var text: String

    var body: some View {
        HStack(spacing: 4) {
            if dictation.state == .listening, dictation.languages.count > 1 {
                // The language, switched while dictating, among the device's own.
                Button(dictation.language.label) {
                    Task { await dictation.switchLanguage(text: text, hints: model.dictationHints) { text = $0 } }
                }
                .font(Theme.mono(12, weight: .semibold))
                .foregroundColor(Theme.ink)
                .frame(width: 34, height: 40)
                .accessibilityLabel("Dictating in \(dictation.language.name). Switch language")
            }
            Button {
                Task { await dictation.toggle(text: text, hints: model.dictationHints) { text = $0 } }
            } label: {
                Image(systemName: dictation.listening ? "stop.fill" : "mic.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(dictation.listening ? .white : Theme.ink)
                    .frame(width: 40, height: 40)
                    .background(dictation.listening ? Theme.no : Theme.panel)
                    .overlay(Circle().stroke(Theme.line))
                    .clipShape(Circle())
            }
            .accessibilityLabel(dictation.listening ? "Stop dictating" : "Dictate a message")
            .accessibilityHint(dictation.listening
                ? "What you said stays in the message box, unsent."
                : "Your voice becomes text on this device. It isn't sent until you send it.")
        }
        .alert("Dictation", isPresented: Binding(get: { blocked != nil }, set: { if !$0 { dictation.dismiss() } })) {
            if blocked?.contains("Settings") == true, let url = URL(string: UIApplication.openSettingsURLString) {
                Button("Open Settings") {
                    dictation.dismiss()
                    UIApplication.shared.open(url)
                }
            }
            Button("OK", role: .cancel) { dictation.dismiss() }
        } message: {
            Text(blocked ?? "")
        }
    }

    /// Why dictation can't run, while the owner reads it.
    private var blocked: String? {
        if case .blocked(let why) = dictation.state { return why }
        return nil
    }
}
#endif
