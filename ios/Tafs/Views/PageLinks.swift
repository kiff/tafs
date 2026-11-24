import SwiftUI

/// Links in a taf's words from a page turn (`page_turn`): the run read the page in front, from the Chrome extension, so a
/// link in its answer may have come from that page rather than from the person or Coach. Tapping one doesn't open it: the app
/// shows its whole address, never shortened and always left to right, and opens it only on Open. Cancel opens nothing. Any
/// other message's links open as before.
enum PageLinks {
    /// The address as the confirm shows it: whole, as the link has it, in a left-to-right isolate so an Arabic, Hebrew or
    /// Urdu line around it never reorders its parts.
    static func shown(_ url: URL) -> String { "\u{2066}" + url.absoluteString + "\u{2069}" }
}

/// Asks before opening a link in words from a page turn (`PageLinks`); does nothing when `on` is false.
struct PageLinkGuard: ViewModifier {
    let on: Bool
    /// The system's opener, read outside the override below: what Open uses.
    @Environment(\.openURL) private var openURL
    @State private var asking: URL?

    func body(content: Content) -> some View {
        if on {
            content
                .environment(\.openURL, OpenURLAction { url in
                    asking = url
                    return .handled
                })
                .alert(Words.Chat.linkConfirmTitle,
                       isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }),
                       presenting: asking) { url in
                    Button(Words.Chat.linkConfirmOpen) { openURL(url) }
                    Button(Words.Common.cancel, role: .cancel) {}
                } message: { url in
                    Text(Words.Chat.linkConfirmBody(address: PageLinks.shown(url)))
                }
        } else {
            content
        }
    }
}
