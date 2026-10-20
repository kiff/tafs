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
    static let dim = Color(hex: 0x8A8496)
    static let line = Color(hex: 0x1B1726).opacity(0.10)
    static let amber = Color(hex: 0xFF8A1F)
    /// Text on the brand gradient and on amber.
    static let amberInk = Color.white
    static let ok = Color(hex: 0x1F9E6A)
    static let no = Color(hex: 0xE5484D)

    static let orange = Color(hex: 0xFF9A2E)
    static let pink = Color(hex: 0xFF4F7B)
    static let blue = Color(hex: 0x2F6BFF)
    /// Coach mode's own blue, and its wash (kiff/tafs#138): never a taf's chat.
    static let coach = Color(hex: 0x2F6BFF)
    static let coachWash = Color(hex: 0xEEF3FF)
    /// What Coach mode has made so far, beside the conversation: a shade deeper than its wash.
    static let coachPanel = Color(hex: 0xE3EBFF)
    static let sky = Color(hex: 0x3EC2FF)

    /// The mascot's sweep, for the main button and the brand.
    static let brand = LinearGradient(colors: [orange, pink, blue], startPoint: .topLeading, endPoint: .bottomTrailing)

    /// Taf colours, from the mascot too: bright, and dark enough for white text.
    static let tafColors: [Color] = [
        Color(hex: 0xFF7A1A), Color(hex: 0xF0457B), Color(hex: 0x7B4DFF),
        Color(hex: 0x2F6BFF), Color(hex: 0x0FA3D6), Color(hex: 0x16A37A),
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
