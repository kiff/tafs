import SwiftUI

enum ThemePreference: String, CaseIterable, Identifiable {
    case light, dark, system
    var id: String { rawValue }
    var title: String { rawValue.capitalized }
    var icon: String { self == .light ? "sun.max" : self == .dark ? "moon" : "circle.lefthalf.filled" }
    var scheme: ColorScheme? { self == .light ? .light : self == .dark ? .dark : nil }
    static let key = "tafs.theme"
}
