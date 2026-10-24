import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

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

/// The taps the chat gives (kiff/tafs#58): a light one on send, a success one when an
/// approval completes.
@MainActor
enum Haptics {
    #if canImport(UIKit)
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    /// A click as a dial reaches each stop.
    static func step() { UISelectionFeedbackGenerator().selectionChanged() }
    #else
    // A Mac has no taps to give.
    static func tap() {}
    static func success() {}
    static func step() {}
    #endif
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
        // iPhone and iPad (v1); the Mac target only compiles.
        #if canImport(UIKit)
        UIAccessibility.isReduceMotionEnabled
        #else
        false
        #endif
    }
}

/// `withAnimation`, except with Reduce Motion on: then nothing slides or bounces, it just
/// changes (kiff/tafs#210).
@MainActor
func withMotion<Result>(_ animation: Animation? = .default, _ body: () throws -> Result) rethrows -> Result {
    try withAnimation(Motion.reduced ? nil : animation, body)
}
