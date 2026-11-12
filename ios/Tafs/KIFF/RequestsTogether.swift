import CryptoKit
import Foundation

/// Two requests under one Face ID (`kiff.requests.v1`, docs/secure-connection.md): the phone
/// builds both in full, with one `signed_at`, and signs once, with the approval key, over the
/// two request messages' hashes. Each service rebuilds its own request's message, takes the
/// other's hash as given (`with`), and checks the one signature. Neither reads the other's
/// request.
enum RequestsTogether {
    static let scheme = "kiff.requests.v1"
    /// The approval step tafs cloud asks it for: an app's key and its taf's Card.
    static let keyAndCards = "key_and_cards"

    /// A request message's hash, as both services write it: lowercase hex SHA-256.
    static func hash(_ message: Data) -> String {
        SHA256.hash(data: message).map { String(format: "%02x", $0) }.joined()
    }

    /// What the approval key signs: the scheme, the account, the one `signed_at`, and the two
    /// hashes in sorted order, a line each.
    static func message(tenant: String, signedAt: String, _ one: String, _ other: String) -> Data {
        let hashes = one <= other ? [one, other] : [other, one]
        return Data(([scheme, tenant, signedAt] + hashes).joined(separator: "\n").utf8)
    }

    /// A request's signature header: `v3` for the one signature, `with` for the other request's hash.
    static func header(signedAt: String, signature: String, with other: String) -> String {
        "t=\(signedAt),v3=\(signature),with=sha256:\(other)"
    }
}
