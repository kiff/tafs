import SwiftUI

/// Links in an answer, a taf's or Coach's (docs/chat.md, "The confirm"). A tap on one that isn't a clean `https` address
/// (`opens`) opens nothing: "This link won't open", its whole address and Cancel alone, in any answer, so a page or a model
/// can't steer the app into another app's action (`tel:`, `sms:`, a deep link, plain `http`) or show one address and open
/// another. A clean `https` link opens on a tap as before, except in words from a page turn (`page_turn`: the run read the page
/// in front, from the Chrome extension, so the link may have come from that page) or in Coach's answer still arriving (a
/// stream picked back up says nothing of `page_turn`). Those show the whole address, never shortened and always left to
/// right, and open only on Open. The owner's own words open their links as before.
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

    /// What a tap asks before anything opens; none opens the link as before.
    enum Ask: Equatable {
        /// Not a clean `https` address: "This link won't open", with Cancel alone.
        case blocked
        /// From a page turn: "Open this link?", with the page's line.
        case pageTurn
        /// Coach's answer still arriving: "Open this link?", with the live line.
        case live
    }

    /// What a tap on `url` in an answer asks. A link that won't open is said so whatever the message; a clean `https` one
    /// asks only from a page turn or an answer still arriving.
    static func ask(_ url: URL, pageTurn: Bool, live: Bool) -> Ask? {
        if !opens(url) { return .blocked }
        if pageTurn { return .pageTurn }
        if live { return .live }
        return nil
    }

    static func title(_ ask: Ask) -> String {
        ask == .blocked ? Words.Chat.linkBlockedTitle : Words.Chat.linkConfirmTitle
    }

    /// The question's line with the address set apart left to right in every language. `Words.line` sets a value apart by
    /// its first letter (U+2068) in a right-to-left language and takes out any direction character, so the address goes in
    /// after the line is made, in its own left-to-right isolate: an Arabic, Hebrew or Urdu sentence can't reorder it.
    static func body(_ ask: Ask, _ url: URL) -> String {
        let mark = "\u{E000}"
        let address = shown(URL(string: Words.withoutDirection(url.absoluteString)) ?? url)
        let line: String
        switch ask {
        case .blocked: line = Words.Chat.linkBlockedBody(address: mark)
        case .pageTurn: line = Words.Chat.linkConfirmBody(address: mark)
        case .live: line = Words.Chat.linkConfirmBodyLive(address: mark)
        }
        return line
            .replacingOccurrences(of: "\u{2068}" + mark + "\u{2069}", with: address)
            .replacingOccurrences(of: mark, with: address)
    }

    /// A page turn's line, as the confirm shows it.
    static func confirmBody(_ url: URL) -> String { body(.pageTurn, url) }
}

/// A tap on a link in an answer asks first where `PageLinks.ask` says to: "This link won't open" with Cancel alone, or "Open
/// this link?" with Open and Cancel. A link it doesn't ask about opens as before. Off for the owner's own words.
struct PageLinkGuard: ViewModifier {
    /// The words are an answer (a taf's or Coach's), not the owner's own.
    let answer: Bool
    var pageTurn = false
    var live = false
    /// The system's opener, read outside the override below: what Open uses.
    @Environment(\.openURL) private var openURL
    @State private var asking: Asking?

    struct Asking: Equatable {
        let url: URL
        let ask: PageLinks.Ask
    }

    func body(content: Content) -> some View {
        if answer {
            content
                .environment(\.openURL, OpenURLAction { url in
                    guard let ask = PageLinks.ask(url, pageTurn: pageTurn, live: live) else { return .systemAction }
                    asking = Asking(url: url, ask: ask)
                    return .handled
                })
                .alert(asking.map { PageLinks.title($0.ask) } ?? "",
                       isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }),
                       presenting: asking) { asked in
                    // Open only for a clean https address; a link that won't open has Cancel alone.
                    if asked.ask != .blocked { Button(Words.Chat.linkConfirmOpen) { openURL(asked.url) } }
                    Button(Words.Common.cancel, role: .cancel) {}
                } message: { asked in
                    Text(PageLinks.body(asked.ask, asked.url))
                }
        } else {
            content
        }
    }
}
