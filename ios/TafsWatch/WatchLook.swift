import SwiftUI

/// W7, colour by meaning (the owner-approved Watch Coach mockup): one palette, the same values as Wear OS's `Look`
/// (android/wear/.../Look.kt, #1451), so both watches read alike. Send and your words orange, Coach blue, what waits for you
/// amber, done green, delete and halt red; everything else stays the system's grey.
enum WatchLook {
    /// Send, the microphone, your words.
    static let send = Color(hex: 0xE8531D)
    /// Coach: its answers, the unread dots.
    static let coach = Color(hex: 0x7FA8FF)
    /// Under Coach's words, and the words on it.
    static let coachGround = Color(hex: 0x1E2231)
    static let onCoachGround = Color(hex: 0xE6ECFF)
    /// Under your words in the conversation, and the words on it (W4).
    static let mineGround = Color(hex: 0x3A2A22)
    static let onMineGround = Color(hex: 0xFFE6D6)
    /// A taf's own words in its chat (chatting with a taf): green, so Coach's blue and yours stay apart. The mockup's values,
    /// for Wear OS to take too.
    static let taf = Color(hex: 0x8FE0B0)
    static let tafGround = Color(hex: 0x17271F)
    static let onTafGround = Color(hex: 0xDDF5E6)
    /// Held, waits for you: "Needs you", Approve on your phone.
    static let held = Color(hex: 0xFFB547)
    /// Done: the Sent check.
    static let done = Color(hex: 0x3FCF7E)
    /// Delete, halt, refuse.
    static let danger = Color(hex: 0xFF5A5F)
}

extension Color {
    /// An sRGB colour from its 0xRRGGBB value.
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: 1)
    }
}
