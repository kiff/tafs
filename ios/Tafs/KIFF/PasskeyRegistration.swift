import CryptoKit
import Foundation

/// Registering a passkey for the account at kiff.dev (KIFF's pairing contract, "Registering one"): what the
/// app asks the platform for, and the body it sends KIFF with the platform's answer. The same rules as
/// Android's, checked by both apps' tests against one vector (`vectors/passkey-registration.json`).
///
/// The body proves the key (proof of possession): it's the creation's own `clientDataJSON` and
/// `attestationObject`, never a credential id or public key the app read out. KIFF reads both from the
/// authenticator data. Registering is signed by this session's approval key, behind Face ID or Touch ID,
/// by an owner or admin only: a session can't add a passkey on its own.
enum PasskeyRegistration {
    /// The relying party: kiff.dev, which vouches for this app (`webcredentials:kiff.dev`).
    static let relyingParty = "kiff.dev"
    /// ES256, the only algorithm KIFF takes.
    static let algorithm = -7
    /// A passkey's name, at most this many characters (Unicode code points).
    static let longestName = 64
    /// How far `issued_at` may be from KIFF's clock.
    static let issuedWithin: TimeInterval = 600

    /// What the challenge is the hash of: the registration's own message, so the passkey is made for this
    /// account, this person and this moment (`kiff.passkey.register.v1`).
    static func message(tenant: String, subject: String, issuedAt: String) -> String {
        "kiff.passkey.register.v1\n\(tenant)\n\(subject)\n\(issuedAt)"
    }

    /// The challenge the platform signs: sha256 of the message. The platform writes it into
    /// `clientDataJSON` as base64url.
    static func challenge(tenant: String, subject: String, issuedAt: String) -> Data {
        Data(SHA256.hash(data: Data(message(tenant: tenant, subject: subject, issuedAt: issuedAt).utf8)))
    }

    /// The passkey's user handle: the subject's own bytes.
    static func userID(subject: String) -> Data { Data(subject.utf8) }

    /// `issued_at` as KIFF reads it: RFC 3339, in UTC, to the second.
    static func issuedAt(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: date)
    }

    /// The name as sent: trimmed, 1 to 64 characters; nil when it isn't one KIFF takes.
    static func name(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let count = trimmed.unicodeScalars.count
        return count >= 1 && count <= longestName ? trimmed : nil
    }

    /// The body of `POST /v1/me/passkeys`: the name, when it was issued, and the creation's answer as the
    /// platform gave it, base64url without padding. Nothing else: KIFF refuses a body that also carries
    /// the old `credential_id` or `public_key`.
    struct Body: Encodable, Equatable {
        let name: String
        let issuedAt: String
        let clientDataJSON: String
        let attestationObject: String

        enum CodingKeys: String, CodingKey {
            case name, issuedAt = "issued_at", clientDataJSON = "client_data_json", attestationObject = "attestation_object"
        }
    }

    /// The body for a passkey the platform made, or nil for a name KIFF wouldn't take.
    static func body(name raw: String, issuedAt: String, clientDataJSON: Data, attestationObject: Data) -> Body? {
        guard let name = name(raw) else { return nil }
        return Body(name: name, issuedAt: issuedAt, clientDataJSON: base64url(clientDataJSON),
                    attestationObject: base64url(attestationObject))
    }

    /// base64url, without padding, as WebAuthn writes it.
    static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
