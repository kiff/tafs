import CryptoKit
import Foundation
import libsecp256k1

/// Checks a KIFF receipt on the device (kiff/tafs#167), as KIFF's own `VerifyAttestation`
/// does (kiff-cloud `receipt/attester.go`): the signed digest is
/// keccak256(ascii(sha256hex(causal_chain))), and the signer recovered from the 65-byte
/// signature must be KIFF's.
///
/// The chain's bytes are taken from the response as KIFF wrote them, never re-encoded:
/// `GET /v1/receipts/{trace_id}` writes the stored receipt with Go's encoder, so its
/// `causal_chain` is byte for byte what KIFF hashed. Only the chain is signed: what the
/// sheet shows as checked comes from its steps.
enum ReceiptCheck: Equatable {
    /// The signature recovers to KIFF's signer over this chain.
    case verified(signer: String)
    /// KIFF signs in its next sweep, about 30 s after the decision.
    case notSignedYet
    /// It doesn't check out, and why.
    case failed(String)

    /// KIFF's receipt signer in production (kiff/tafs#167): one key for every account, the
    /// deployer in kiff-cloud's `contract/deployments.json` and the sender of its anchor
    /// transactions. A rotation ships in a new build first.
    static let kiffSigner = "0x2f297affd7b62ef712a1e3158421ccb4bf2efcbe"

    static let algorithm = "secp256k1+keccak256"

    static func check(_ response: Data, signer expected: String = kiffSigner) -> ReceiptCheck {
        guard let receipt = try? JSONSerialization.jsonObject(with: response) as? [String: Any] else {
            return .failed("The receipt couldn't be read.")
        }
        guard let attestation = receipt["attestation"] as? [String: Any] else { return .notSignedYet }
        guard attestation["algorithm"] as? String == algorithm else {
            return .failed("It's signed in a way this app doesn't know.")
        }
        guard let chain = RawJSON.value(of: "causal_chain", in: response) else {
            return .failed("The receipt has no steps to check.")
        }
        guard let signature = (attestation["signature"] as? String).flatMap(Hex.decode), signature.count == 65 else {
            return .failed("The signature is malformed.")
        }
        let declared = (attestation["wallet_address"] as? String ?? "").lowercased()

        let chainHash = Hex.encode(Data(SHA256.hash(data: chain)))
        let digest = Keccak.hash256(Data(chainHash.utf8))
        guard let recovered = recoverAddress(digest: digest, signature: signature) else {
            return .failed("The signature doesn't match these steps.")
        }
        guard recovered == declared else { return .failed("The signature doesn't match these steps.") }
        guard recovered == expected.lowercased() else { return .failed("It isn't signed by KIFF.") }
        return .verified(signer: recovered)
    }

    /// libsecp256k1's context is read-only once made, so one is shared across threads.
    nonisolated(unsafe) private static let context: OpaquePointer? = secp256k1_context_create(UInt32(SECP256K1_CONTEXT_NONE))

    /// The Ethereum-style address that signed the digest: keccak256 of the uncompressed key,
    /// its last 20 bytes. Nil when the signature can't be parsed or recovers no key.
    static func recoverAddress(digest: Data, signature: Data) -> String? {
        guard let context, digest.count == 32, signature.count == 65 else { return nil }
        var v = Int32(signature[signature.startIndex + 64])
        if v >= 27 { v -= 27 }
        guard (0...3).contains(v) else { return nil }
        var parsed = secp256k1_ecdsa_recoverable_signature()
        let compact = [UInt8](signature.prefix(64))
        guard secp256k1_ecdsa_recoverable_signature_parse_compact(context, &parsed, compact, v) == 1 else { return nil }
        var key = secp256k1_pubkey()
        let message = [UInt8](digest)
        guard secp256k1_ecdsa_recover(context, &key, &parsed, message) == 1 else { return nil }
        var out = [UInt8](repeating: 0, count: 65)
        var length = 65
        guard secp256k1_ec_pubkey_serialize(context, &out, &length, &key, UInt32(SECP256K1_EC_UNCOMPRESSED)) == 1,
              length == 65 else { return nil }
        let hash = Keccak.hash256(Data(out[1...]))
        return "0x" + Hex.encode(hash.suffix(20))
    }
}

enum Hex {
    static func encode(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    static func decode(_ string: String) -> Data? {
        var s = Substring(string)
        if s.hasPrefix("0x") { s = s.dropFirst(2) }
        guard s.count % 2 == 0 else { return nil }
        var out = Data(capacity: s.count / 2)
        var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            guard let byte = UInt8(s[i..<j], radix: 16) else { return nil }
            out.append(byte)
            i = j
        }
        return out
    }
}

/// A top-level value's own bytes in a JSON object, as written: the receipt's signed chain
/// must be hashed as KIFF wrote it, not as a decoder would write it again.
enum RawJSON {
    static func value(of key: String, in data: Data) -> Data? {
        let bytes = [UInt8](data)
        var i = skipSpace(bytes, 0)
        guard i < bytes.count, bytes[i] == UInt8(ascii: "{") else { return nil }
        i += 1
        while true {
            i = skipSpace(bytes, i)
            guard i < bytes.count else { return nil }
            if bytes[i] == UInt8(ascii: "}") { return nil }
            guard bytes[i] == UInt8(ascii: "\""), let keyEnd = endOfString(bytes, i) else { return nil }
            let name = (try? JSONSerialization.jsonObject(with: Data(bytes[i..<keyEnd]), options: .fragmentsAllowed)) as? String
            i = skipSpace(bytes, keyEnd)
            guard i < bytes.count, bytes[i] == UInt8(ascii: ":") else { return nil }
            i = skipSpace(bytes, i + 1)
            guard let valueEnd = endOfValue(bytes, i) else { return nil }
            if name == key { return Data(bytes[i..<valueEnd]) }
            i = skipSpace(bytes, valueEnd)
            guard i < bytes.count else { return nil }
            if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
            return nil
        }
    }

    private static func skipSpace(_ b: [UInt8], _ from: Int) -> Int {
        var i = from
        while i < b.count, [0x20, 0x09, 0x0A, 0x0D].contains(b[i]) { i += 1 }
        return i
    }

    /// The index after a string's closing quote; `start` is its opening quote.
    private static func endOfString(_ b: [UInt8], _ start: Int) -> Int? {
        var i = start + 1
        while i < b.count {
            if b[i] == UInt8(ascii: "\\") { i += 2; continue }
            if b[i] == UInt8(ascii: "\"") { return i + 1 }
            i += 1
        }
        return nil
    }

    /// The index after a value: a string, an object or array (to its matching close), or a
    /// literal or number (to the next delimiter).
    private static func endOfValue(_ b: [UInt8], _ start: Int) -> Int? {
        guard start < b.count else { return nil }
        switch b[start] {
        case UInt8(ascii: "\""):
            return endOfString(b, start)
        case UInt8(ascii: "{"), UInt8(ascii: "["):
            var depth = 0
            var i = start
            while i < b.count {
                switch b[i] {
                case UInt8(ascii: "\""):
                    guard let end = endOfString(b, i) else { return nil }
                    i = end
                    continue
                case UInt8(ascii: "{"), UInt8(ascii: "["):
                    depth += 1
                case UInt8(ascii: "}"), UInt8(ascii: "]"):
                    depth -= 1
                    if depth == 0 { return i + 1 }
                default:
                    break
                }
                i += 1
            }
            return nil
        default:
            var i = start
            while i < b.count, ![UInt8(ascii: ","), UInt8(ascii: "}"), UInt8(ascii: "]"), 0x20, 0x09, 0x0A, 0x0D].contains(b[i]) { i += 1 }
            return i > start ? i : nil
        }
    }
}
