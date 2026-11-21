import CryptoKit
import XCTest
@testable import Tafs

/// Pairing a Chrome from the phone (docs/chrome.md, "Pairing"): the same proofs and signed bytes as the extension
/// (vectors/chrome-pairing.json, which chrome/test/vectors.test.js reads too), a wrong proof refused, and a signature sent
/// as raw r‖s.
final class ChromePairingTests: XCTestCase {
    private struct Vectors: Decodable {
        let secret, chromeKey, phoneKey, pairing, name, extensionProof, chromeKeySha256, phoneProof, signedBytes: String
    }

    private func vectors() throws -> Vectors {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("vectors/chrome-pairing.json")
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(Vectors.self, from: Data(contentsOf: url))
    }

    func testTheProofsAndSignedBytesAreTheExtensions() throws {
        let v = try vectors()
        let chromeKey = try XCTUnwrap(ChromePairing.base64URLDecoded(v.chromeKey))
        let phoneKey = try XCTUnwrap(ChromePairing.base64URLDecoded(v.phoneKey))
        XCTAssertTrue(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey, proof: v.extensionProof))
        let c = ChromePairing.confirmation(secret: v.secret, chromeKey: chromeKey, name: v.name, phoneKey: phoneKey)
        XCTAssertEqual(c.chromeKeySHA256, v.chromeKeySha256)
        XCTAssertEqual(c.phoneProof, v.phoneProof)
        XCTAssertEqual(c.phoneKey, v.phoneKey)
        XCTAssertEqual(ChromePairing.base64URL(try ChromePairing.signedBytes(pairing: v.pairing, c)), v.signedBytes)
    }

    /// Another extension that used the code's lookup can't prove its key: it doesn't have the secret.
    func testAProofUnderAnotherSecretOrForAnotherKeyIsRefused() throws {
        let v = try vectors()
        let chromeKey = try XCTUnwrap(ChromePairing.base64URLDecoded(v.chromeKey))
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: "AAAAAAAAAAAAAAAAA", key: chromeKey, proof: v.extensionProof))
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey + Data([0]), proof: v.extensionProof))
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey, proof: "not+base64url"))
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey, proof: ""))
    }

    func testTheSecretIsSeventeenCharactersReadWithoutMistakes() {
        for _ in 0..<50 {
            let s = ChromePairing.newSecret()
            XCTAssertEqual(s.count, 17)
            XCTAssertTrue(s.allSatisfy { ChromePairing.alphabet.contains($0) })
        }
        XCTAssertFalse(ChromePairing.alphabet.contains { "01ILOU".contains($0) })
        XCTAssertEqual(ChromePairing.code(lookup: "K7QF", secret: "29XMDPQRSTVWXYZ23"), "K7QF-29XM-DPQR-STVW-XYZ2-3")
        XCTAssertGreaterThan(log2(Double(ChromePairing.alphabet.count)) * Double(ChromePairing.secretLength), 80)
    }

    /// The keystore's DER signature goes as raw r‖s, base64url, and still verifies.
    func testASignatureGoesAsRawRS() throws {
        let key = P256.Signing.PrivateKey()
        let message = Data("tafs-chrome/pair/v1\0{}".utf8)
        let der = try key.signature(for: message).derRepresentation.base64EncodedString()
        let raw = try XCTUnwrap(ChromePairing.base64URLDecoded(try ChromePairing.rawSignature(fromDER: der)))
        XCTAssertEqual(raw.count, 64)
        XCTAssertTrue(key.publicKey.isValidSignature(try P256.Signing.ECDSASignature(rawRepresentation: raw), for: message))
    }

    func testTheNameIsTheOwnersOrTheBrowsers() {
        XCTAssertEqual(ChromePairing.name("  Work Mac ", browser: "Chrome 131 on macOS"), "Work Mac")
        XCTAssertEqual(ChromePairing.name("", browser: "Chrome 131 on macOS"), "Chrome 131 on macOS")
        XCTAssertEqual(ChromePairing.name(String(repeating: "x", count: 60), browser: "").count, 40)
    }
}
