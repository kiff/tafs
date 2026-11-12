import SwiftUI

enum ThemePreference: String, CaseIterable, Identifiable {
    case light, dark, system
    var id: String { rawValue }
    /// In the app's language (the picker read "Light / Dark / System" in every language).
    var title: String {
        switch self {
        case .light: return Words.Settings.Theme.light
        case .dark: return Words.Settings.Theme.dark
        case .system: return Words.Settings.Theme.system
        }
    }
    var icon: String { self == .light ? "sun.max" : self == .dark ? "moon" : "circle.lefthalf.filled" }
    var scheme: ColorScheme? { self == .light ? .light : self == .dark ? .dark : nil }
    static let key = "tafs.theme"
}
