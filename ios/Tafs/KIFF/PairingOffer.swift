import CryptoKit
import Foundation

/// What a new phone or tablet shows to be paired from the person's other phone (KIFF's pairing contract
/// §5.8): the same `kiff.pair.v1` QR as a computer, with this device's two keys, its check code, and
/// the message its device key signs to claim the session once the other phone approves (§5.4).
/// The Mac build shows it too, as a computer: `macos`, with no `device_kind`.
struct PairingOffer: Equatable {
    let nonce: String
    /// The device key, base64url, as the QR carries it and the claim sends it.
    let pub: String
    let expires: Date
    /// The QR's text: the JSON the other phone reads (`PairingRequest.read`).
    let qr: String
    /// The 6 characters both screens show (§5.2).
    let checkCode: String
    /// `kiff.pair.claim.v1`, signed once by the device key: it covers only the nonce and the key, so the
    /// same proof serves every poll.
    let claimMessage: Data

    /// Under §5.1's 5 minutes, so the other phone's clock never reads it as too far ahead.
    static let lifetime: TimeInterval = 4 * 60 + 30
    static let enclave = "enclave"

    /// `publicKey` and `approvalKey` are SPKI DER; `nonce` is 32 random bytes.
    static func make(publicKey: Data, approvalKey: Data, name: String, platform: String, deviceKind: String?,
                     nonce: Data, now: Date = Date()) -> PairingOffer {
        let pub = base64url(publicKey)
        let nonceText = base64url(nonce)
        let expires = now.addingTimeInterval(lifetime)
        let exp = ISO8601DateFormatter().string(from: expires)
        var fields: [String: String] = ["v": PairingRequest.version, "pub": pub, "approval_pub": base64url(approvalKey),
                                        "name": name, "platform": platform, "kind": enclave, "nonce": nonceText, "exp": exp]
        if let deviceKind { fields["device_kind"] = deviceKind }
        let json = (try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data()
        let code = String(PairingRequest.base32(Data(SHA256.hash(data: publicKey + approvalKey))).prefix(6))
        let keyHash = SHA256.hash(data: publicKey).map { String(format: "%02x", $0) }.joined()
        return PairingOffer(nonce: nonceText, pub: pub, expires: expires, qr: String(decoding: json, as: UTF8.self), checkCode: code,
                            claimMessage: Data(["kiff.pair.claim.v1", nonceText, keyHash].joined(separator: "\n").utf8))
    }

    /// 32 bytes from the system's secure random source.
    static func freshNonce() -> Data { SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) } }

    /// The `device_kind` KIFF lists a phone or tablet by; a computer, the Mac build, sends none.
    static func deviceKind(platform: String, kiffKind: String) -> String? {
        switch (platform, kiffKind) {
        case ("ios", "iphone"): return "iphone"
        case ("ipados", "ipad"): return "ipad"
        default: return nil
        }
    }

    /// base64url without padding (§5.1).
    static func base64url(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }
}
