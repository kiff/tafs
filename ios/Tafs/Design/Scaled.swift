import SwiftUI
import UIKit

/// A system font at `size` points at the default text size, growing and shrinking with
/// the owner's Dynamic Type setting (kiff/tafs#58).
struct ScaledFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    let weight: Font.Weight
    let design: Font.Design

    init(size: CGFloat, weight: Font.Weight, design: Font.Design, relativeTo style: Font.TextStyle) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: style)
        self.weight = weight
        self.design = design
    }

    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: weight, design: design))
    }
}

extension View {
    func scaledFont(_ size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default,
                    relativeTo style: Font.TextStyle = .body) -> some View {
        modifier(ScaledFont(size: size, weight: weight, design: design, relativeTo: style))
    }
}

/// The app's haptics, one rule on both apps: a light tap on every button press (the shared
/// button styles, `DriveButton`), a success tick when an approval or a send goes through, a
/// warning tick when something is refused or fails, and a step tick on dial and selector stops.
/// None when Haptics is off in Settings (the 901 test).
@MainActor
enum Haptics {
    static let key = "haptics"
    static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }
    /// What played, for the tests: "tap", "success", "warning" or "step".
    static var played: ((String) -> Void)?
    /// A press and the action it starts tap once, not twice: a second tap this soon is the same one.
    private static var lastTap = Date.distantPast
    static func tap() {
        guard enabled, Date().timeIntervalSince(lastTap) > 0.15 else { return }
        lastTap = Date()
        play("tap") { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    }
    static func success() { if enabled { play("success") { UINotificationFeedbackGenerator().notificationOccurred(.success) } } }
    static func warning() { if enabled { play("warning") { UINotificationFeedbackGenerator().notificationOccurred(.warning) } } }
    /// A click as a dial or a selector reaches each stop.
    static func step() { if enabled { play("step") { UISelectionFeedbackGenerator().selectionChanged() } } }

    private static func play(_ kind: String, _ feel: () -> Void) {
        if let played { played(kind) } else { feel() }
    }
}

/// One line at the usual text sizes; at the accessibility sizes the text wraps rather than
/// be cut off (kiff/tafs#210).
struct OneLineUnlessLarge: ViewModifier {
    @Environment(\.dynamicTypeSize) private var size

    func body(content: Content) -> some View {
        content.lineLimit(size.isAccessibilitySize ? nil : 1)
    }
}

extension View {
    func oneLineUnlessLarge() -> some View { modifier(OneLineUnlessLarge()) }
}

/// Whether the owner turned on Reduce Motion (kiff/tafs#210).
@MainActor
enum Motion {
    static var reduced: Bool {
        UIAccessibility.isReduceMotionEnabled
    }
}

/// `withAnimation`, except with Reduce Motion on: then nothing slides or bounces, it just
/// changes (kiff/tafs#210).
@MainActor
func withMotion<Result>(_ animation: Animation? = .default, _ body: () throws -> Result) rethrows -> Result {
    try withAnimation(Motion.reduced ? nil : animation, body)
}
