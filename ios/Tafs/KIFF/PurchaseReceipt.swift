import CryptoKit
import Foundation

/// KIFF's signed receipt for a purchase (contracts/purchase.json, `receipt`; docs/purchases.md): what was decided and what
/// happened, every word KIFF's, `unknown` said as unknown. KIFF signs it with Ed25519 over the canonical JSON (RFC 8785)
/// of its `value` without `sig`, under a `kid`. The device checks it against keys built into the app (`pinned`), never a
/// key fetched with it; it decides nothing from it.
struct PurchaseReceipt: Equatable {
    let caseID: String
    let version: Int
    let digest: String
    /// KIFF's state for the case (`done`, `not_bought`, `unknown`…).
    let caseState: String
    let words: Words
    let kid: String
    /// What KIFF signed: the receipt's `value` without `sig`, made canonical (RFC 8785).
    let signed: Data
    let sig: String

    /// KIFF's words for the Record, each as sent; only `line` and `state` are always there.
    struct Words: Equatable {
        let line: String
        let state: String
        var allowed: String?
        var card: String?
        /// The order's line (`words.order`, or `shipping` on a receipt issued before the rename).
        var order: String?
        var refunds: String?

        /// The lines under the state, in the contract's order.
        var lines: [String] { [allowed, card, order, refunds].compactMap { $0 } }
    }

    /// What the device's check of a receipt found.
    enum Check: Equatable {
        /// Signed by KIFF with a key this app holds, and unchanged since.
        case verified
        /// Signed with a key this app holds, but its signature doesn't match what it says: changed, or not KIFF's.
        case changed
        /// Signed with a key this app doesn't hold, or one KIFF revoked: never shown as checked.
        case unknownKey
        /// It isn't a receipt for the case its Record line names.
        case otherCase
    }

    static let schema = "kiff.purchase-receipt.v1"

    /// KIFF's receipt keys, built into this app (raw 32-byte Ed25519 public keys by `kid`), as `ReceiptCheck.kiffSigner`
    /// is for the Record's other receipts: a receipt checks only against a key the app already holds. KIFF's published
    /// key set is for others; a new key ships here before KIFF signs with it, and a revoked one leaves in a release.
    /// `kiff-receipts-1`: KIFF's first receipt key (raw Ed25519 public key, base64url, as KIFF publishes it in its key set).
    static let pinned: [String: Data] = [
        "kiff-receipts-1": PurchaseReceipt.decode("_6t9hrjlcGOCqoGUrOf95mAk6oW9Z8zEj1_rtj2c4Pg")!,
    ]

    /// Checks the receipt against `keys`, for the case `caseID` its Record line names.
    func check(caseID: String, keys: [String: Data] = PurchaseReceipt.pinned, revoked: Set<String> = []) -> Check {
        guard caseID == self.caseID else { return .otherCase }
        guard let raw = keys[kid], raw.count == 32, !revoked.contains(kid),
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: raw) else { return .unknownKey }
        guard let signature = Self.decode(sig), signature.count == 64 else { return .changed }
        return key.isValidSignature(signature, for: signed) ? .verified : .changed
    }

    /// Reads KIFF's answer: the receipt's `value` itself, or an object holding it as `value`. Nil when it isn't a purchase
    /// receipt this app can draw: another schema, or no case id, version, digest, kid, signature, line or state.
    static func read(_ data: Data) -> PurchaseReceipt? {
        guard let root = try? JCS.parse(data), case .object(let top) = root else { return nil }
        let value: [String: JCS.Value]
        if case .object(let inner)? = top["value"] { value = inner } else { value = top }
        guard value["schema"]?.string == schema, case .object(let w)? = value["words"] else { return nil }
        func word(_ key: String) -> String? {
            w[key]?.string.map(KIFFMapping.visible).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        }
        guard let line = word("line"), let state = word("state"),
              let caseID = value["case_id"]?.string, let digest = value["digest"]?.string,
              case .number(let v)? = value["version"], let version = Int(v),
              let kid = value["kid"]?.string, let sig = value["sig"]?.string else { return nil }
        var unsigned = value
        unsigned["sig"] = nil
        guard let signed = try? JCS.canonical(.object(unsigned)) else { return nil }
        var caseState = ""
        if case .object(let states)? = value["states"] { caseState = states["case"]?.string ?? "" }
        return PurchaseReceipt(caseID: caseID, version: version, digest: digest, caseState: caseState,
                               words: Words(line: line, state: state, allowed: word("allowed"), card: word("card"),
                                            order: word("order") ?? word("shipping"), refunds: word("refunds")),
                               kid: kid, signed: signed, sig: sig)
    }

    /// Base64url or standard base64, padded or not.
    static func decode(_ s: String) -> Data? {
        var b = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b += String(repeating: "=", count: (4 - b.count % 4) % 4)
        return Data(base64Encoded: b)
    }
}
