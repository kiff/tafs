import SwiftUI

/// Links in a chat's answers. A clean `https` address opens on a tap, as any link, except in two cases, where the app first
/// shows its whole address, never shortened and always left to right, and opens it only on Open:
/// - **A page turn** (`page_turn`): the run read the page in front, from the Chrome extension, so the link may have come
///   from that page rather than from the person or Coach.
/// - **An answer still being written:** a stream picked back up or sent again says nothing of `page_turn`, so a live answer
///   asks until the thread's read settles it.
///
/// Any other address (another app's deep link, `tel:`, `sms:`, plain `http`, or one with a space or a direction mark in it)
/// never opens from a chat: it shows its address with Cancel alone, in any answer, so nothing in a chat can start another
/// app's action or show one address and open another.
enum PageLinks {
    /// How a message's links are opened.
    enum Mode: Equatable {
        /// A clean `https` link opens on a tap.
        case plain
        /// A page turn's answer: every link asks first, with the page line.
        case pageTurn
        /// An answer still being written: every link asks first, with the live line.
        case live
    }

    /// What a tap on `url` does in a message read in `mode`.
    enum Tap: Equatable {
        case open
        case ask(live: Bool)
        case blocked
    }

    static func tap(_ url: URL, in mode: Mode) -> Tap {
        guard opens(url) else { return .blocked }
        switch mode {
        case .plain: return .open
        case .pageTurn: return .ask(live: false)
        case .live: return .ask(live: true)
        }
    }

    /// Whether an address may be opened from a chat at all: `https` with a host, and nothing in it, as written or
    /// percent-decoded, that could make what's read differ from what opens: no space, no control and no format character
    /// (a direction mark, an override such as RLO, an isolate). As Android's `linkOpens`.
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

    /// The address as the dialog shows it: whole, as the link has it, in a left-to-right isolate so an Arabic, Hebrew or
    /// Urdu line around it never reorders its parts.
    static func shown(_ url: URL) -> String { "\u{2066}" + url.absoluteString + "\u{2069}" }
}

/// Opens a chat message's links as `PageLinks` says: at once, after asking, or never.
struct PageLinkGuard: ViewModifier {
    let mode: PageLinks.Mode
    /// The system's opener, read outside the override below: what Open uses.
    @Environment(\.openURL) private var openURL
    @State private var asking: (url: URL, tap: PageLinks.Tap)?

    func body(content: Content) -> some View {
        content
            .environment(\.openURL, OpenURLAction { url in
                let tap = PageLinks.tap(url, in: mode)
                if tap == .open { return .systemAction(url) }
                asking = (url, tap)
                return .handled
            })
            .alert(asking.map { $0.tap == .blocked ? Words.Chat.linkBlockedTitle : Words.Chat.linkConfirmTitle } ?? "",
                   isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }),
                   presenting: asking) { asked in
                // Open only where it may: a blocked address gets its address and Cancel alone.
                if asked.tap != .blocked { Button(Words.Chat.linkConfirmOpen) { openURL(asked.url) } }
                Button(Words.Common.cancel, role: .cancel) {}
            } message: { asked in
                Text(PageLinks.body(asked.url, asked.tap))
            }
    }
}

extension PageLinks {
    /// The dialog's line for `url`: why it won't open, or where it goes, said for a page turn or a live answer.
    static func body(_ url: URL, _ tap: Tap) -> String {
        let address = shown(url)
        switch tap {
        case .blocked, .open: return Words.Chat.linkBlockedBody(address: address)
        case .ask(live: true): return Words.Chat.linkConfirmBodyLive(address: address)
        case .ask(live: false): return Words.Chat.linkConfirmBody(address: address)
        }
    }
}
