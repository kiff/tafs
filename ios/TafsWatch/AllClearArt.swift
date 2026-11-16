import SwiftUI

/// Needs you with nothing waiting, on the wrist (board 11 of the approved illustrations): a calm check
/// in a green circle with a soft halo. Shapes and one symbol only, never a letter, number or word, and
/// hidden from VoiceOver, since the line under it says it in the phone's words.
struct AllClearArt: View {
    /// The boards' green (#2E9E5B): the watch has no theme of its own.
    static let green = Color(red: 46 / 255, green: 158 / 255, blue: 91 / 255)

    var body: some View {
        ZStack {
            Circle().fill(Self.green.opacity(0.18)).frame(width: 66, height: 66)
            Circle().fill(Self.green).frame(width: 46, height: 46)
            Image(systemName: "checkmark")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 4)
        .accessibilityHidden(true)
    }
}
