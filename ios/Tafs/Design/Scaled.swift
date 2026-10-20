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
    #else
    // A Mac has no taps to give.
    static func tap() {}
    static func success() {}
    #endif
}
