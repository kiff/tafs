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

/// What's copied is the text alone: no direction character, so a code, a key or an IBAN copied from a right-to-left
/// screen (whose values are set apart by FSI … PDI, `Words.isolated`) pastes as its own characters.
enum Clipboard {
    static func copy(_ text: String) {
        UIPasteboard.general.string = Words.withoutDirection(text)
    }

    /// A secret shown once (an `api` door's key): kept on this device only, never on the person's other devices through
    /// Universal Clipboard, and gone from the pasteboard after a minute, whether or not tafs is still open.
    static let secretLifetime: TimeInterval = 60

    static func copySecret(_ text: String, now: Date = Date()) {
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: Words.withoutDirection(text)]],
                                      options: secretOptions(now: now))
    }

    /// How long a copied Chrome pairing code stays on the pasteboard: a few minutes, and the code lapses in ten and works once.
    static let pairingCodeLifetime: TimeInterval = 5 * 60

    /// A Chrome pairing code, copied to type or paste into the computer's Chrome. Not this-device-only, so Universal Clipboard can
    /// carry it to the person's own computer; it leaves the pasteboard after a few minutes, whether or not tafs is still open.
    static func copyPairingCode(_ text: String, now: Date = Date()) {
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: Words.withoutDirection(text)]],
                                      options: pairingCodeOptions(now: now))
    }

    static func pairingCodeOptions(now: Date) -> [UIPasteboard.OptionsKey: Any] {
        [.expirationDate: now.addingTimeInterval(pairingCodeLifetime)]
    }

    /// The options a secret is copied with: this device only, and an expiry a minute from now.
    static func secretOptions(now: Date) -> [UIPasteboard.OptionsKey: Any] {
        [.localOnly: true, .expirationDate: now.addingTimeInterval(secretLifetime)]
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
