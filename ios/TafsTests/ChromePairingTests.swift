import CryptoKit
import XCTest
@testable import Tafs

/// Pairing a Chrome from the phone (docs/chrome.md, "Pairing"): the same proofs and signed bytes as the extension
/// (vectors/chrome-pairing.json, which chrome/test/vectors.test.js reads too), a wrong proof refused, and a signature sent
/// as raw r‖s.
final class ChromePairingTests: XCTestCase {
    private struct Vectors: Decodable {
        let secret, chromeKey, phoneKey, pairing, name, extensionProof, chromeKeySha256, phoneProof, signedBytes: String
        let kiffOffer, extensionProofWithKiffOffer: String
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
        XCTAssertTrue(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey, proof: v.extensionProofWithKiffOffer, kiffOffer: v.kiffOffer))
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey, proof: v.extensionProofWithKiffOffer), "the KIFF offer is covered")
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey, proof: v.extensionProofWithKiffOffer,
                                                         kiffOffer: v.kiffOffer.replacingOccurrences(of: "extension", with: "hello")), "a changed KIFF offer")
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
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: "AAAAAA", key: chromeKey, proof: v.extensionProof))
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey + Data([0]), proof: v.extensionProof))
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey, proof: "not+base64url"))
        XCTAssertFalse(ChromePairing.extensionProofHolds(secret: v.secret, key: chromeKey, proof: ""))
    }

    func testTheSecretIsSixCharactersReadWithoutMistakes() {
        for _ in 0..<50 {
            let s = ChromePairing.newSecret()
            XCTAssertEqual(s.count, 6)
            XCTAssertTrue(s.allSatisfy { ChromePairing.alphabet.contains($0) })
        }
        XCTAssertFalse(ChromePairing.alphabet.contains { "01ILOU".contains($0) })
        XCTAssertEqual(ChromePairing.code(lookup: "K7QF", secret: "29XMDP"), "K7QF-29XMDP")
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

    /// The extension's KIFF device offer reads only as a view-only browser extension: never with an approval key, never as
    /// a computer's kind.
    func testTheExtensionsKIFFOfferIsViewOnlyOrRefused() throws {
        let v = try vectors()
        guard case .success(let r) = PairingRequest.read(v.kiffOffer, now: ISO8601DateFormatter().date(from: "2026-11-21T12:00:00Z")!) else {
            return XCTFail("the extension's offer reads")
        }
        XCTAssertEqual(r.platform, "chrome")
        XCTAssertEqual(r.kind, "extension")
        XCTAssertTrue(r.softwareKey)
        let now = ISO8601DateFormatter().date(from: "2026-11-21T12:00:00Z")!
        let keyed = v.kiffOffer.replacingOccurrences(of: #""key_store":"software","#, with: "")
        guard case .failure = PairingRequest.read(keyed, now: now) else { return XCTFail("an extension that could approve") }
        let asComputer = v.kiffOffer.replacingOccurrences(of: #""kind":"extension""#, with: #""kind":"hello""#)
        guard case .failure = PairingRequest.read(asComputer, now: now) else { return XCTFail("chrome as a computer's kind") }
        let elsewhere = v.kiffOffer.replacingOccurrences(of: #""platform":"chrome""#, with: #""platform":"macos""#)
        guard case .failure = PairingRequest.read(elsewhere, now: now) else { return XCTFail("an extension that says it's a Mac") }
    }
}
