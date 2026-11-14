import CryptoKit
import Foundation

/// A computer's pairing code, as its QR shows it (KIFF's pairing contract §5.1), read before anything is
/// shown or signed. Pairing gives the computer approval authority, so the code is read strictly: one
/// field out of bounds and the phone shows why, never the request.
struct PairingRequest: Equatable {
    static let version = "kiff.pair.v1"
    /// How far ahead `exp` may be (§5.1: at most 5 minutes), with a little room for the two clocks.
    static let longestAhead: TimeInterval = 5 * 60 + 30

    /// The device key and, for `hello` and `enclave`, the approval key: base64url, as the QR carries them.
    let pub: String
    let approvalPub: String?
    let name: String
    let platform: String
    let kind: String
    let nonce: String
    let exp: String
    /// `"software"` when the computer's device key isn't in hardware (only with `kind: "passkey"`): it
    /// approves only through the phone or a passkey, and the phone says so.
    let keyStore: String?
    /// The computer's own platform passkey (only with `kind: "passkey"`, as Windows pairs): its id and
    /// public key, base64url as the QR carries them. The pairing registers it for that computer.
    let credential: Credential?
    let expires: Date
    /// The keys' DER bytes, which the check code covers.
    let pubDER: Data
    let approvalDER: Data?

    struct Credential: Equatable {
        let id: String
        let publicKey: String
        let publicKeyDER: Data
        /// A credential id is at most 1023 bytes (WebAuthn's own bound).
        static let longestID = 1023
    }

    /// Why a code can't be paired; each is said to the person in their words.
    enum Problem: Error, Equatable {
        /// Not a pairing code at all, or another version.
        case notAPairingCode
        /// `exp` has passed: the computer shows a new code.
        case expired
        /// A key isn't P-256, two are the same, or one is missing or extra for its kind.
        case badKeys
        /// A name, platform, kind or nonce out of its bounds.
        case badField
    }

    static let platforms: Set<String> = ["windows", "macos", "linux"]
    /// The kinds that hold an approval key; a `passkey` computer holds none.
    static let approvalKinds: Set<String> = ["hello", "enclave"]
    static let kinds: Set<String> = approvalKinds.union(["passkey"])

    /// Reads a scanned code, checked as KIFF checks it (§5.5).
    static func read(_ text: String, now: Date = Date()) -> Result<PairingRequest, Problem> {
        guard let object = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
              object["v"] as? String == version else { return .failure(.notAPairingCode) }
        func string(_ key: String) -> String? { object[key] as? String }
        guard let pub = string("pub"), let name = string("name"), let platform = string("platform"), let kind = string("kind"),
              let nonce = string("nonce"), let exp = string("exp") else { return .failure(.notAPairingCode) }
        // Fields KIFF doesn't take aren't signed for it.
        let known: Set<String> = ["v", "pub", "approval_pub", "name", "platform", "kind", "nonce", "exp", "key_store", "credential"]
        guard Set(object.keys).isSubset(of: known) else { return .failure(.badField) }

        guard let expires = time(exp) else { return .failure(.badField) }
        guard expires > now else { return .failure(.expired) }
        guard expires.timeIntervalSince(now) <= longestAhead else { return .failure(.badField) }

        guard kinds.contains(kind), platforms.contains(platform), nameIsUsable(name),
              base64url(nonce)?.count == 32 else { return .failure(.badField) }

        // `key_store` is only ever "software", and only for a passkey computer.
        let keyStore = object["key_store"]
        if keyStore != nil, !(keyStore as? String == "software" && kind == "passkey") { return .failure(.badField) }

        let approvalPub = object["approval_pub"]
        if approvalPub != nil, !(approvalPub is String) { return .failure(.badKeys) }
        let approval = approvalPub as? String
        guard (approval != nil) == approvalKinds.contains(kind) else { return .failure(.badKeys) }
        guard let pubDER = p256(pub) else { return .failure(.badKeys) }
        var approvalDER: Data?
        if let approval {
            guard let der = p256(approval), der != pubDER else { return .failure(.badKeys) }
            approvalDER = der
        }

        // `credential`: only for a passkey computer, exactly `{id, public_key}`, its key P-256 and not the device key.
        var credential: Credential?
        if let field = object["credential"] {
            guard kind == "passkey", let fields = field as? [String: Any], Set(fields.keys) == ["id", "public_key"],
                  let id = fields["id"] as? String, let idBytes = base64url(id), (1...Credential.longestID).contains(idBytes.count),
                  let publicKey = fields["public_key"] as? String else { return .failure(.badField) }
            guard let der = p256(publicKey), der != pubDER else { return .failure(.badKeys) }
            credential = Credential(id: id, publicKey: publicKey, publicKeyDER: der)
        }
        return .success(PairingRequest(pub: pub, approvalPub: approval, name: name, platform: platform, kind: kind,
                                       nonce: nonce, exp: exp, keyStore: keyStore as? String, credential: credential, expires: expires,
                                       pubDER: pubDER, approvalDER: approvalDER))
    }

    /// Its device key is in software: it never approves by itself.
    var softwareKey: Bool { keyStore == "software" }

    /// The 6 characters both screens show before the fingerprint (§5.2): base32 of sha256 over the device
    /// key's DER, then the approval key's when there is one, or the passkey's in its place, so the code
    /// covers the passkey the phone registers.
    var checkCode: String {
        String(Self.base32(Data(SHA256.hash(data: pubDER + (approvalDER ?? credential?.publicKeyDER ?? Data())))).prefix(6))
    }

    /// `POST /v1/me/pairings`'s body (§5.3): the QR's fields as it sent them. These exact bytes are what
    /// the approval key's `kiff.request.v1` covers.
    var body: Data {
        var fields: [String: Any] = ["v": Self.version, "pub": pub, "name": name, "platform": platform,
                                     "kind": kind, "nonce": nonce, "exp": exp]
        if let approvalPub { fields["approval_pub"] = approvalPub }
        if let keyStore { fields["key_store"] = keyStore }
        if let credential { fields["credential"] = ["id": credential.id, "public_key": credential.publicKey] }
        return (try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data()
    }

    // MARK: Reading the fields

    /// 1 to 64 characters, none of them a control character.
    static func nameIsUsable(_ name: String) -> Bool {
        (1...64).contains(name.count) && !name.unicodeScalars.contains { $0.properties.generalCategory == .control }
    }

    /// A P-256 public key's SPKI DER, from base64url; nil for anything else.
    static func p256(_ text: String) -> Data? {
        guard let der = base64url(text), (try? P256.Signing.PublicKey(derRepresentation: der)) != nil else { return nil }
        return der
    }

    /// base64url without padding (§5.1); padding is taken too.
    static func base64url(_ text: String) -> Data? {
        guard !text.isEmpty, text.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "-_=".contains($0)) }) else { return nil }
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s = s.trimmingCharacters(in: CharacterSet(charactersIn: "="))
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        return Data(base64Encoded: s)
    }

    /// RFC 4648 base32, without padding.
    static func base32(_ data: Data) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var out = "", buffer = 0, bits = 0
        for byte in data {
            buffer = ((buffer << 8) | Int(byte)) & 0xFFFF
            bits += 8
            while bits >= 5 {
                out.append(alphabet[(buffer >> (bits - 5)) & 31])
                bits -= 5
            }
        }
        if bits > 0 { out.append(alphabet[(buffer << (5 - bits)) & 31]) }
        return out
    }

    /// An RFC 3339 time, with or without fractions of a second.
    static func time(_ text: String) -> Date? {
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        if let date = plain.date(from: text) { return date }
        let fractions = ISO8601DateFormatter()
        fractions.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractions.date(from: text)
    }
}
