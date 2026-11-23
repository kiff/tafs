import CryptoKit
import Foundation

/// The phone's release of one sealed sign-in value for one fill (docs/chrome.md, "The phone's release card"), as Android's
/// `SealedRelease`. The extension fills a sealed value only with it (`protocol.checkRelease`): the phone's approval key, behind
/// a fresh Face ID, over `tafs-chrome/release/v1` ‖ 0x00 ‖ JCS of `{chrome_id, op, sealed, origin, field_ref, read_id, nonce,
/// exp}` (vectors/chrome-release.json). The phone never has the value; it only names which one, where, and for which field.
///
/// **Off** (`enabled`) until the hold's browser block and KIFF's half are in: nothing draws the card or signs a release while
/// it's false, and a sealed fill offers Refuse only.
enum SealedRelease {
    /// Off until planque holds a sealed fill and KIFF serves its block. Nothing draws the card or signs a release while it's false.
    static let enabled = false

    static let label = "tafs-chrome/release/v1"

    /// A release is good for 60 s from the owner's yes (`exp`, unix ms): the extension takes it at most 2 minutes ahead of its
    /// own clock.
    static let validMilliseconds: Int64 = 60_000

    /// Which part of a saved sign-in a handle names: the card says it, read from the handle, never from planque's words.
    enum Part: Equatable { case password, username }

    /// The part `handle` names, when it's exactly `sec_<id>:password` or `sec_<id>:username` as the extension makes them
    /// (`parseHandle`), the id made of letters, digits, `_` and `-`. Anything else, a third segment, another part, a space or a
    /// line end included, is nil: then nothing is signed, and the card is unreadable.
    static func part(_ handle: String) -> Part? {
        let pieces = handle.split(separator: ":", omittingEmptySubsequences: false)
        guard pieces.count == 2, pieces[0].hasPrefix("sec_") else { return nil }
        let id = pieces[0].dropFirst(4)
        guard !id.isEmpty, id.unicodeScalars.allSatisfy({ $0.isASCII && (CharacterSet.alphanumerics.contains($0) || $0 == "_" || $0 == "-") })
        else { return nil }
        switch pieces[1] {
        case "password": return .password
        case "username": return .username
        default: return nil
        }
    }

    /// A held sealed fill as the release signs it: the held step's own fields, copied byte for byte, and the hold's end.
    struct Fill: Equatable {
        let chromeID: String
        let op: String
        let sealed: String
        let origin: String
        let fieldRef: String
        let readID: String
        let expiresAt: Date
    }

    /// Why the phone won't sign a release, whatever planque says: the card is then drawn as an unreadable hold, and says why.
    enum Refusal: Equatable {
        /// The hold has no op, read or field to name.
        case incomplete
        /// The handle isn't exactly `sec_<id>:password` or `:username`.
        case handle
        /// The Chrome isn't one this phone is paired with.
        case chrome
        /// The hold has expired: a late yes signs nothing.
        case expired
        /// The origin isn't `https://host` (an optional port, no path, no user).
        case origin
        /// The origin isn't within the sites this phone itself signed for that Chrome.
        case notSigned
        /// A sensitive site with no allowance of this phone's own for it.
        case sensitive
    }

    /// Whether the phone may sign a release for `fill` (nil) or why not. Every check is the phone's own, never planque's word:
    /// `pairedChromes` are the Chromes this phone is paired with; `signedSites` the sites this phone's own key signed for that
    /// Chrome (`tafs-chrome/sites/v1`, already verified by the caller); `allowedSensitive` the sensitive sites this phone's own key
    /// allowed there (`tafs-chrome/sensitive/v1`, verified too).
    static func refusal(_ fill: Fill, pairedChromes: Set<String>, signedSites: [String], allowedSensitive: [String], now: Date) -> Refusal? {
        if fill.op.isEmpty || fill.readID.isEmpty || fill.fieldRef.isEmpty { return .incomplete }
        if part(fill.sealed) == nil { return .handle }
        if fill.chromeID.isEmpty || !pairedChromes.contains(fill.chromeID) { return .chrome }
        if now >= fill.expiresAt { return .expired }
        guard let host = httpsHost(fill.origin) else { return .origin }
        guard signedSites.contains(where: { within(host, $0) }) else { return .notSigned }
        if ChromePairing.isSensitive(host), !allowedSensitive.contains(where: { within(host, $0) }) { return .sensitive }
        return nil
    }

    /// Why the card can't allow, in its words. A late hold says nothing here: the card says it expired.
    static func words(_ refusal: Refusal) -> String? {
        switch refusal {
        case .incomplete, .handle: return Words.Chrome.releaseUnreadable
        case .chrome: return Words.Chrome.releaseOtherChrome
        case .expired: return nil
        case .origin: return Words.Chrome.releaseNotHttps
        case .notSigned: return Words.Chrome.releaseNotConnected
        case .sensitive: return Words.Chrome.releaseSensitive
        }
    }

    /// What the release's Face ID prompt says it signs: which part, for which site.
    static func prompt(_ part: Part, site: String) -> String {
        part == .password ? Words.Chrome.releasePromptPassword(site: site) : Words.Chrome.releasePromptUsername(site: site)
    }

    /// `origin`'s host when it's exactly `https://host` or `https://host:port`, lower case; nil for anything else.
    static func httpsHost(_ origin: String) -> String? {
        guard let parts = URLComponents(string: origin), parts.scheme == "https", parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.percentEncodedPath.isEmpty,
              let host = parts.host?.lowercased(), !host.isEmpty else { return nil }
        let rebuilt = "https://" + host + (parts.port.map { ":\($0)" } ?? "")
        return rebuilt == origin ? host : nil
    }

    /// Whether `host` is `site` or one of its subdomains, as the extension reads a run's sites.
    static func within(_ host: String, _ site: String) -> Bool {
        !site.isEmpty && (host == site || host.hasSuffix("." + site))
    }

    /// The bytes the approval key signs: label ‖ 0x00 ‖ JCS of the eight fields (vectors/chrome-release.json `signed_bytes`).
    static func bytes(chromeID: String, op: String, sealed: String, origin: String, fieldRef: String, readID: String,
                      nonce: String, exp: Int64) throws -> Data {
        let body: JCS.Value = .object([
            "chrome_id": .string(chromeID), "op": .string(op), "sealed": .string(sealed), "origin": .string(origin),
            "field_ref": .string(fieldRef), "read_id": .string(readID), "nonce": .string(nonce), "exp": .number(String(exp)),
        ])
        var out = Data(label.utf8)
        out.append(0)
        out.append(try JCS.canonical(body))
        return out
    }

    /// A nonce of the phone's own: 16 random bytes, unpadded base64url, made when the owner allows and never reused.
    static func newNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        var generator = SystemRandomNumberGenerator()
        for i in bytes.indices { bytes[i] = UInt8.random(in: .min ... .max, using: &generator) }
        return ChromePairing.base64URL(Data(bytes))
    }

    /// The release that goes in the hold's answer (`release`), for planque to put on the step: `{chrome_id, op, origin, nonce,
    /// exp, sig}`.
    struct Release: Encodable, Equatable {
        let chromeID: String
        let op: String
        let origin: String
        let nonce: String
        let exp: Int64
        /// The approval key's signature, raw r‖s, base64url.
        let sig: String
        enum CodingKeys: String, CodingKey { case chromeID = "chrome_id", op, origin, nonce, exp, sig }
    }

    /// The release for `fill`: a new nonce, `exp` 60 s after `now` (the moment the owner allowed), and `sign`'s signature (the
    /// approval key's DER, base64, behind Face ID) as raw r‖s. The caller checks `refusal` first, so a release is never made for a
    /// fill the card couldn't draw.
    static func release(_ fill: Fill, now: Date, nonce: String = newNonce(), sign: (Data) async throws -> String) async throws -> Release {
        let exp = Int64((now.timeIntervalSince1970 * 1000).rounded(.down)) + validMilliseconds
        let der = try await sign(try bytes(chromeID: fill.chromeID, op: fill.op, sealed: fill.sealed, origin: fill.origin,
                                           fieldRef: fill.fieldRef, readID: fill.readID, nonce: nonce, exp: exp))
        return Release(chromeID: fill.chromeID, op: fill.op, origin: fill.origin, nonce: nonce, exp: exp,
                       sig: try ChromePairing.rawSignature(fromDER: der))
    }
}
