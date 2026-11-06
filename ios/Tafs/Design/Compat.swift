import SwiftUI
import UIKit

// Shared screen helpers: each names what the screens do, so a call reads as its purpose.

extension View {
    /// The inline navigation title.
    @ViewBuilder func inlineTitle() -> some View {
        navigationBarTitleDisplayMode(.inline)
    }

    /// A list or form over the screen's own background.
    @ViewBuilder func hiddenScrollBackground() -> some View {
        scrollContentBackground(.hidden)
    }

    /// A screen that covers the whole screen, with nothing behind it showing (kiff/tafs#206 6.10).
    @ViewBuilder func fullCover<Content: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping () -> Content) -> some View {
        fullScreenCover(isPresented: isPresented, content: content)
    }

    /// Dragging the chat down puts the keyboard away.
    @ViewBuilder func dismissesKeyboardInteractively() -> some View {
        scrollDismissesKeyboard(.interactively)
    }
}

extension View {
    /// The bar under a chat: the screen's colour, with a soft shadow above it.
    @ViewBuilder func barBackground() -> some View {
        background(Theme.background.shadow(.drop(color: Theme.ink.opacity(0.06), radius: 6, y: -2)))
    }

    /// Semibold, keeping the font's size.
    @ViewBuilder func semibold() -> some View {
        fontWeight(.semibold)
    }
}

extension ToolbarItemPlacement {
    /// The navigation bar's trailing end.
    static var trailingBar: ToolbarItemPlacement {
        .navigationBarTrailing
    }
}

/// A NavigationStack around a sheet's or a screen's content.
struct NavStack<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        NavigationStack(root: content)
    }
}

enum Clipboard {
    static func copy(_ text: String) {
        UIPasteboard.general.string = text
    }
}

/// Buttons in rows that wrap (FlowRow).
struct WrapRow<Content: View>: View {
    var spacing: CGFloat = 8
    @ViewBuilder var content: () -> Content

    var body: some View {
        FlowRow(spacing: spacing) { content() }
    }
}
