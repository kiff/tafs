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

/// Under the message bar while dictating (kiff/tafs#208): the microphone's sound as it comes,
/// so it's plain that dictation is on, with cancel (the box goes back as it was) and stop
/// (what was heard stays, unsent).
struct DictationBar: View {
    @ObservedObject var dictation: Dictation

    var body: some View {
        if dictation.listening {
            HStack(spacing: 12) {
                Button { dictation.cancel() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(Theme.ink)
                        .frame(width: 36, height: 36)
                        .background(Theme.panel)
                        .clipShape(Circle())
                }
                .accessibilityLabel("Cancel dictating")
                .accessibilityHint("Puts the message box back as it was.")
                Waveform(levels: dictation.levels)
                    .frame(height: 28)
                    .accessibilityElement()
                    .accessibilityLabel("Listening in \(dictation.language.name)")
                Button { dictation.stop() } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 36, height: 36)
                        .background(Theme.no)
                        .clipShape(Circle())
                }
                .accessibilityLabel("Stop dictating")
                .accessibilityHint("What you said stays in the message box, unsent.")
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(Theme.panel.opacity(0.6))
            .overlay(Capsule().stroke(Theme.line))
            .clipShape(Capsule())
            .padding(.horizontal, 16).padding(.bottom, 8)
            .transition(.opacity)
        }
    }
}

/// The latest levels as bars, newest on the right; dots where it was quiet.
private struct Waveform: View {
    let levels: [Float]

    var body: some View {
        GeometryReader { geo in
            let count = Dictation.levelCount
            let step = geo.size.width / CGFloat(count)
            let shown = Array(repeating: Float(0), count: max(0, count - levels.count)) + levels.suffix(count)
            HStack(alignment: .center, spacing: 0) {
                ForEach(Array(shown.enumerated()), id: \.offset) { _, level in
                    Capsule()
                        .fill(level > 0.05 ? Theme.ink : Theme.dim)
                        .frame(width: max(2, step * 0.45), height: max(2, geo.size.height * CGFloat(level)))
                        .frame(width: step)
                }
            }
            .frame(height: geo.size.height)
            .animation(.linear(duration: 0.08), value: levels)
        }
    }
}
#endif
