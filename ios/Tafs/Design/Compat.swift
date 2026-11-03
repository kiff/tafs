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

/// The chat's message field: it grows to six lines.
struct ChatField: View {
    let title: String
    @Binding var text: String
    /// Where the insertion point is, in characters, for dictation to put its words there;
    /// told from iOS 18, which says where it is.
    var cursor: ((Int) -> Void)? = nil

    var body: some View {
        if #available(iOS 18, *), let cursor {
            SelectingField(title: title, text: $text, cursor: cursor)
        } else {
            TextField(title, text: $text, axis: .vertical).lineLimit(1...6)
        }
    }
}

/// A message box that says where its insertion point is.
@available(iOS 18, *)
private struct SelectingField: View {
    let title: String
    @Binding var text: String
    let cursor: (Int) -> Void
    @State private var selection: TextSelection?

    var body: some View {
        TextField(title, text: $text, selection: $selection, axis: .vertical).lineLimit(1...6)
            .onChange(of: selection) { _, now in
                // Gone when the box loses focus (the mic's tap): the last point stays.
                guard let now, case .selection(let range) = now.indices, range.upperBound <= text.endIndex else { return }
                cursor(text.distance(from: text.startIndex, to: range.upperBound))
            }
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
