import SwiftUI

/// The tafs palette, taken from the mascot: a warm cream ground, deep ink, and the
/// mascot's orange → pink → blue as the brand. Status colours stay plain so they
/// read at a glance: amber for "needs you", green for done, coral for refused.
///
/// Each colour follows the device's appearance: in dark, a warm near-black ground and
/// brighter green, amber and red, the values the owner reviewed (the same on Android).
enum Theme {
    static let background = Color(light: 0xFBF7EE, dark: 0x141311)
    static let panel = Color(light: 0xFFFFFF, dark: 0x1F1E1B)
    static let raised = Color(light: 0xF3ECDF, dark: 0x2A2824)
    static let ink = Color(light: 0x1B1726, dark: 0xF2EFE9)
    static let soft = Color(light: 0x4F4A5E, dark: 0xA8A49C)
    /// Secondary text: 4.5:1 on the background and on panels, in both (kiff/tafs#210).
    static let dim = Color(light: 0x736D7F, dark: 0x96918A)
    static let line = Color(light: 0x1B1726, dark: 0xF2EFE9, lightOpacity: 0.10, darkOpacity: 0.12)
    static let amber = Color(hex: 0xFF8A1F)
    /// Amber as text: the amber itself is 2.2:1 on the background, this 4.5:1 (kiff/tafs#210).
    static let amberText = Color(light: 0xB35500, dark: 0xF0A45A)
    /// Text on the brand gradient: white in both, 4.5:1 all along it.
    static let amberInk = Color.white
    /// Text and marks on an ink, Coach, green or red fill: white in light, the dark ground in
    /// dark, where those fills are light.
    static let onFill = Color(light: 0xFFFFFF, dark: 0x141311)
    static let ok = Color(light: 0x198056, dark: 0x4CC38A)
    static let no = Color(light: 0xDB1F25, dark: 0xFF8A73)

    static let orange = Color(hex: 0xFF9A2E)
    static let pink = Color(hex: 0xFF4F7B)
    static let blue = Color(light: 0x2A64F5, dark: 0x7FA2FF)
    /// Coach mode's own blue, and its wash (kiff/tafs#138): never a taf's chat.
    static let coach = Color(light: 0x2A64F5, dark: 0x7FA2FF)
    static let coachWash = Color(light: 0xEEF3FF, dark: 0x1A2033)
    /// What Coach mode has made so far, beside the conversation: a shade deeper than its wash.
    static let coachPanel = Color(light: 0xE3EBFF, dark: 0x232B42)
    static let sky = Color(hex: 0x3EC2FF)

    /// The mascot's sweep, for the main button and the brand. White text on it: each stop a
    /// shade deeper than the mascot's, so the text is 4.5:1 all along it (kiff/tafs#210).
    static let brand = LinearGradient(colors: [Color(hex: 0xB35C00), Color(hex: 0xE8003A), Color(hex: 0x2A64F5)],
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

extension Color {
    /// One colour for the light appearance and one for dark, each with its opacity.
    init(light: UInt32, dark: UInt32, lightOpacity: CGFloat = 1, darkOpacity: CGFloat = 1) {
        func ui(_ hex: UInt32, _ alpha: CGFloat) -> UIColor {
            UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                    blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
        }
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? ui(dark, darkOpacity) : ui(light, lightOpacity)
        })
    }
}
