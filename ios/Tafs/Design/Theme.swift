import SwiftUI

/// The tafs palette, taken from the mascot: a warm cream ground, deep ink, and the
/// mascot's orange → pink → blue as the brand. Status colours stay plain so they
/// read at a glance: amber for "needs you", green for done, coral for refused.
enum Theme {
    static let background = Color(hex: 0xFBF7EE)
    static let panel = Color(hex: 0xFFFFFF)
    static let raised = Color(hex: 0xF3ECDF)
    static let ink = Color(hex: 0x1B1726)
    static let soft = Color(hex: 0x4F4A5E)
    /// Secondary text: 4.5:1 on the background and on panels (kiff/tafs#210).
    static let dim = Color(hex: 0x736D7F)
    static let line = Color(hex: 0x1B1726).opacity(0.10)
    static let amber = Color(hex: 0xFF8A1F)
    /// Amber as text: the amber itself is 2.2:1 on the background, this 4.5:1 (kiff/tafs#210).
    static let amberText = Color(hex: 0xB35500)
    /// Text on the brand gradient and on amber.
    static let amberInk = Color.white
    static let ok = Color(hex: 0x198056)
    static let no = Color(hex: 0xDB1F25)

    static let orange = Color(hex: 0xFF9A2E)
    static let pink = Color(hex: 0xFF4F7B)
    static let blue = Color(hex: 0x2A64F5)
    /// Coach mode's own blue, and its wash (kiff/tafs#138): never a taf's chat.
    static let coach = Color(hex: 0x2A64F5)
    static let coachWash = Color(hex: 0xEEF3FF)
    /// What Coach mode has made so far, beside the conversation: a shade deeper than its wash.
    static let coachPanel = Color(hex: 0xE3EBFF)
    static let sky = Color(hex: 0x3EC2FF)

    /// The mascot's sweep, for the main button and the brand. White text on it: each stop a
    /// shade deeper than the mascot's, so the text is 4.5:1 all along it (kiff/tafs#210).
    static let brand = LinearGradient(colors: [Color(hex: 0xB35C00), Color(hex: 0xE8003A), blue],
                                      startPoint: .topLeading, endPoint: .bottomTrailing)

    /// Taf colours, from the mascot too: bright, and dark enough for white text, 4.5:1 or
    /// more (kiff/tafs#210).
    static let tafColors: [Color] = [
        Color(hex: 0xC25100), Color(hex: 0xE51255), Color(hex: 0x7B4DFF),
        Color(hex: 0x2A64F5), Color(hex: 0x0B7DA4), Color(hex: 0x128462),
    ]

    static var tafColorCount: Int { tafColors.count }

    static func tafColor(_ index: Int) -> Color {
        tafColors[((index % tafColors.count) + tafColors.count) % tafColors.count]
    }

    static func display(_ size: CGFloat) -> Font { .system(size: size, weight: .bold, design: .rounded) }
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
}

/// The tafs mascot, from the shared asset catalog (the app and the Live Activity).
struct Mascot: View {
    var size: CGFloat = 40
    var body: some View {
        Image("Mascot").resizable().scaledToFit().frame(width: size, height: size).accessibilityHidden(true)
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
