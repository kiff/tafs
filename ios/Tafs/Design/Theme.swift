import SwiftUI

/// The tafs palette: dark ground, warm ink, one amber accent for "needs you",
/// green for done, coral for refused. Taf colours are bright and share a lightness,
/// so no taf reads as more important than another.
enum Theme {
    static let background = Color(hex: 0x0B0C0F)
    static let panel = Color(hex: 0x111318)
    static let raised = Color(hex: 0x17191F)
    static let ink = Color(hex: 0xF4EFE6)
    static let soft = Color(hex: 0xC9C3B8)
    static let dim = Color(hex: 0x9D978D)
    static let line = Color(hex: 0xF4EFE6).opacity(0.12)
    static let amber = Color(hex: 0xFF9A1A)
    static let amberInk = Color(hex: 0x1A1400)
    static let ok = Color(hex: 0x56C78B)
    static let no = Color(hex: 0xEF7A6B)

    static let tafColors: [Color] = [
        Color(hex: 0x8FA8FF), Color(hex: 0x7FD3C4), Color(hex: 0xD7B4FF),
        Color(hex: 0xF2B3A0), Color(hex: 0xFFD27A), Color(hex: 0x9FE0A0),
    ]

    static func tafColor(_ index: Int) -> Color {
        tafColors[((index % tafColors.count) + tafColors.count) % tafColors.count]
    }

    static func display(_ size: CGFloat) -> Font { .system(size: size, weight: .regular, design: .serif) }
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
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
