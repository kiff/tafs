import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

// The iPhone's screens on the Mac app (kiff/tafs#85), which runs on macOS 12: each helper
// is the iOS call as it was on iOS, and what macOS 12 has in its place on the Mac.

#if os(macOS)
/// A Mac window is always wide: the iPhone's screens read it as regular.
enum UserInterfaceSizeClass { case compact, regular }
#endif

extension View {
    /// iOS's inline navigation title; a Mac window shows its title itself.
    @ViewBuilder func inlineTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    /// A list or form over the screen's own background.
    @ViewBuilder func hiddenScrollBackground() -> some View {
        if #available(iOS 16, macOS 13, *) {
            scrollContentBackground(.hidden)
        } else {
            self
        }
    }

    /// Dragging the chat down puts the keyboard away; a Mac has none to put away.
    @ViewBuilder func dismissesKeyboardInteractively() -> some View {
        #if os(iOS)
        scrollDismissesKeyboard(.interactively)
        #else
        self
        #endif
    }
}

extension View {
    /// The bar under a chat: the screen's colour, with a soft shadow above it.
    @ViewBuilder func barBackground() -> some View {
        if #available(iOS 16, macOS 13, *) {
            background(Theme.background.shadow(.drop(color: Theme.ink.opacity(0.06), radius: 6, y: -2)))
        } else {
            background(Theme.background.shadow(color: Theme.ink.opacity(0.06), radius: 6, y: -2))
        }
    }

    /// Semibold, keeping the font's size (iOS 16, macOS 13); as it is on macOS 12.
    @ViewBuilder func semibold() -> some View {
        if #available(iOS 16, macOS 13, *) {
            fontWeight(.semibold)
        } else {
            self
        }
    }
}

extension ToolbarItemPlacement {
    /// The navigation bar's trailing end on iOS; the window toolbar's on the Mac.
    static var trailingBar: ToolbarItemPlacement {
        #if os(iOS)
        .navigationBarTrailing
        #else
        .primaryAction
        #endif
    }
}

/// A NavigationStack where there is one (iOS 16, macOS 13). On macOS 12 the content stands
/// alone: a sheet or a window's detail needs no stack of its own there.
struct NavStack<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        if #available(iOS 16, macOS 13, *) {
            NavigationStack(root: content)
        } else {
            content()
        }
    }
}

/// The chat's message field: it grows to six lines where it can (iOS 16, macOS 13), one
/// line on macOS 12.
struct ChatField: View {
    let title: String
    @Binding var text: String

    var body: some View {
        if #available(iOS 16, macOS 13, *) {
            TextField(title, text: $text, axis: .vertical).lineLimit(1...6)
        } else {
            TextField(title, text: $text)
        }
    }
}

enum Clipboard {
    static func copy(_ text: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}

/// Buttons in rows that wrap (FlowRow, a Layout: iOS 16 and macOS 13); one under another
/// on macOS 12.
struct WrapRow<Content: View>: View {
    var spacing: CGFloat = 8
    @ViewBuilder var content: () -> Content

    var body: some View {
        if #available(iOS 16, macOS 13, *) {
            FlowRow(spacing: spacing) { content() }
        } else {
            VStack(alignment: .leading, spacing: spacing) { content() }
        }
    }
}
