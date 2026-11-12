import Foundation
import SwiftUI

extension TafsCloud {
    /// The words of a key's sheet, by its grant (docs/secure-connection.md): tafs cloud works
    /// them out from the grant and the catalog entry it came from, in the screen's language.
    /// The phone draws them, and fills only the key's last 4 characters.
    struct ConnectionSheet: Decodable, Equatable {
        struct Vendor: Decodable, Equatable {
            let name: String
            var logo: String?
            var host: String?
        }
        /// One plain line. `kind` picks its icon only; the words are tafs cloud's.
        struct Line: Decodable, Equatable {
            let kind: String
            let text: String
        }
        struct Paste: Decodable, Equatable {
            let button: String
            let what: String
            let pasted: String
            let cleared: String
        }
        struct Details: Decodable, Equatable {
            struct Line: Decodable, Equatable {
                let label: String
                let value: String
            }
            let title: String
            var lines: [Line] = []
        }
        struct Legal: Decodable, Equatable {
            struct Link: Decodable, Equatable {
                let words: String
                let url: String
            }
            let text: String
            var terms: Link?
            var privacy: Link?
        }
        struct Receipt: Decodable, Equatable {
            let text: String
            let disconnect: String
        }
        let vendor: Vendor
        let title: String
        var lines: [Line] = []
        let paste: Paste
        var details: Details?
        var cardTitle: String?
        var legal: Legal?
        let button: String
        var receipt: Receipt?

        enum CodingKeys: String, CodingKey {
            case vendor, title, lines, paste, details, cardTitle = "card_title", legal, button, receipt
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            vendor = try c.decode(Vendor.self, forKey: .vendor)
            title = try c.decode(String.self, forKey: .title)
            lines = try c.decodeIfPresent([Line].self, forKey: .lines) ?? []
            paste = try c.decode(Paste.self, forKey: .paste)
            details = try c.decodeIfPresent(Details.self, forKey: .details)
            cardTitle = try c.decodeIfPresent(String.self, forKey: .cardTitle)
            legal = try c.decodeIfPresent(Legal.self, forKey: .legal)
            button = try c.decode(String.self, forKey: .button)
            receipt = try c.decodeIfPresent(Receipt.self, forKey: .receipt)
        }
    }

    /// `GET /v1/connect/grants/{grant}/sheet`: the sheet's words for a grant that takes a key.
    /// `proof` is this phone's signed grants read, as Connect apps sends it: the connection
    /// server shows the grant to tafs cloud only as this phone.
    func connectionSheet(grant: String, proof: String? = nil) async throws -> ConnectionSheet {
        let id = grant.addingPercentEncoding(withAllowedCharacters: CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? grant
        return try await send("GET", "/v1/connect/grants/\(id)/sheet", headers: proof.map { ["Planque-Device-Signature": $0] } ?? [:])
    }

    /// A sheet's logo: one of tafs cloud's own (`logoURL`), given as its path or as its full
    /// address on this tafs cloud. Any other address draws no logo.
    func sheetLogoURL(_ logo: String?) -> URL? {
        guard let logo else { return nil }
        if let url = logoURL(logo) { return url }
        guard let absolute = URL(string: logo), absolute.scheme == "https", let host = absolute.host, host == baseURL.host else { return nil }
        return logoURL(absolute.path)
    }
}

/// What the key's sheet works out on the phone: the key as pasted, its last 4 characters, and
/// tafs cloud's lines with them filled in. Nothing here is kept after the sheet closes.
enum SecureKey {
    /// Whether the sheet's first line is true of the grant this phone read from the
    /// connection server to sign its digest: "read only" only with at least one tool, every
    /// one a read. The line people trust most is never tafs cloud's word alone.
    static func saysTrue(_ sheet: TafsCloud.ConnectionSheet, of grant: PlanqueClient.Grant) -> Bool {
        guard sheet.lines.contains(where: { $0.kind == "reads_only" }) else { return true }
        let tools = grant.request.tools
        return !tools.isEmpty && tools.allSatisfy { $0.readOnly == true }
    }

    /// The key from a paste: the first text on the clipboard, without the blanks or line ends
    /// a copy adds around it. Nothing pasted, nothing taken.
    static func taken(from pasted: [String]) -> String? {
        let key = pasted.first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return key.isEmpty ? nil : key
    }

    /// The key's last 4 characters, the only part ever drawn. A key too short for 4 characters
    /// to hide the rest shows none.
    static func last4(of key: String) -> String {
        key.count >= 12 ? String(key.suffix(4)) : ""
    }

    /// A line of tafs cloud's with the phone's part filled in: `{last4}` from the pasted key,
    /// and `{vendor}` where tafs cloud left it.
    static func filled(_ text: String, last4: String, vendor: String) -> String {
        text.replacingOccurrences(of: "{last4}", with: last4).replacingOccurrences(of: "{vendor}", with: vendor)
    }

    /// The legal line, with `{terms}` and `{privacy}` as links. A link that isn't https is
    /// drawn as its words alone.
    static func legal(_ legal: TafsCloud.ConnectionSheet.Legal) -> AttributedString {
        var line = AttributedString()
        var rest = Substring(legal.text)
        let marks: [(String, TafsCloud.ConnectionSheet.Legal.Link?)] = [("{terms}", legal.terms), ("{privacy}", legal.privacy)]
        while !rest.isEmpty {
            let next = marks.compactMap { mark, link in rest.range(of: mark).map { ($0, link) } }.min { $0.0.lowerBound < $1.0.lowerBound }
            guard let (range, link) = next else { break }
            line += AttributedString(String(rest[rest.startIndex..<range.lowerBound]))
            if let link {
                var words = AttributedString(link.words)
                if let url = URL(string: link.url), url.scheme == "https" { words.link = url }
                line += words
            }
            rest = rest[range.upperBound...]
        }
        line += AttributedString(String(rest))
        return line
    }

    /// The icon beside one of the three lines, by its kind. A kind this app doesn't know gets a plain mark.
    static func icon(_ kind: String) -> String {
        switch kind {
        case "reads_only": return "eye"
        case "may_change": return "pencil"
        case "sealed": return "lock.shield"
        case "disconnect": return "xmark.circle"
        default: return "checkmark.circle"
        }
    }
}
