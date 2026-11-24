import SwiftUI

/// Links in a taf's words from a page turn (`page_turn`): the run read the page in front, from the Chrome extension, so a
/// link in its answer may have come from that page rather than from the person or Coach. Tapping one doesn't open it: the app
/// shows its whole address, never shortened and always left to right, and opens it only on Open, which is offered for an
/// `https` address alone. Any other scheme (another app's deep link, `tel:`, `sms:`, a payment app's) shows its address with
/// Cancel only, so a page can't steer Coach into starting another app's action. Cancel opens nothing. Any other message's
/// links open as before.
enum PageLinks {
    /// Whether Open is offered: an `https` address with a host, and nothing in it, as written or percent-decoded, that
    /// could make what's read differ from what opens: no space, no control and no format character (a direction mark, an
    /// override such as RLO, an isolate). As Android's `linkOpens`.
    static func opens(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty else { return false }
        let address = url.absoluteString
        for text in [address, address.removingPercentEncoding ?? address] {
            for scalar in text.unicodeScalars {
                switch scalar.properties.generalCategory {
                case .control, .format, .spaceSeparator, .lineSeparator, .paragraphSeparator: return false
                default: continue
                }
            }
        }
        return true
    }

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
                    // Open for an https address alone; any other scheme gets its address and Cancel.
                    if PageLinks.opens(url) { Button(Words.Chat.linkConfirmOpen) { openURL(url) } }
                    Button(Words.Common.cancel, role: .cancel) {}
                } message: { url in
                    Text(Words.Chat.linkConfirmBody(address: PageLinks.shown(url)))
                }
        } else {
            content
        }
    }
}
