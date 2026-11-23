import CryptoKit
import XCTest
@testable import Tafs

/// The release of a sealed sign-in value (docs/chrome.md, "The phone's release card"): the same bytes, handles and checks as the
/// extension's `checkRelease` and `parseHandle` and Android's `SealedRelease`, read from vectors/chrome-release.json.
final class SealedReleaseTests: XCTestCase {
    private func vector() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("vectors/chrome-release.json")
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testTheBytesAreTheExtensionsAndTheSwitchIsOff() throws {
        let v = try vector()
        func s(_ k: String) throws -> String { try XCTUnwrap(v[k] as? String, k) }
        let exp = try XCTUnwrap((v["exp"] as? NSNumber)?.int64Value)
        let bytes = try SealedRelease.bytes(chromeID: try s("chrome_id"), op: try s("op"), sealed: try s("sealed"), origin: try s("origin"),
                                            fieldRef: try s("field_ref"), readID: try s("read_id"), nonce: try s("nonce"), exp: exp)
        XCTAssertEqual(ChromePairing.base64URL(bytes), try s("signed_bytes"))
        XCTAssertFalse(SealedRelease.enabled, "off until the hold's block and KIFF's half are in")
    }

    /// Exactly `sec_<id>:password` or `sec_<id>:username`; every other handle in the vector (a third segment, another part, an
    /// empty id, a space, a line end, a capital) is none.
    func testOnlyAHandleOfTheExtensionsShapeNamesAPart() throws {
        let handles = try XCTUnwrap(try vector()["handles"] as? [String: Any])
        XCTAssertFalse(handles.isEmpty)
        for (handle, expected) in handles {
            let want: SealedRelease.Part? = switch expected as? String {
            case "password": .password
            case "username": .username
            default: nil
            }
            XCTAssertEqual(SealedRelease.part(handle), want, "\"\(handle)\"")
        }
    }

    func testOnlyAnHTTPSOriginWithNoPathOrUserIsOne() {
        XCTAssertEqual(SealedRelease.httpsHost("https://shop.example"), "shop.example")
        XCTAssertEqual(SealedRelease.httpsHost("https://shop.example:8443"), "shop.example")
        for bad in ["http://shop.example", "https://shop.example/", "https://shop.example/login", "https://u@shop.example",
                    "https://shop.example?x=1", "https://shop.example#f", "https://SHOP.example", "shop.example", "", "https://"] {
            XCTAssertNil(SealedRelease.httpsHost(bad), bad)
        }
    }

    private func fill(_ changes: (inout [String: String]) -> Void = { _ in }, expires: Date = Date(timeIntervalSinceNow: 120)) -> SealedRelease.Fill {
        var f = ["chrome": "chr_1", "op": "op_9", "sealed": "sec_Ab3:password", "origin": "https://login.shop.example", "field": "f2", "read": "rd_1"]
        changes(&f)
        return SealedRelease.Fill(chromeID: f["chrome"]!, op: f["op"]!, sealed: f["sealed"]!, origin: f["origin"]!,
                                  fieldRef: f["field"]!, readID: f["read"]!, expiresAt: expires)
    }

    private func refusal(_ fill: SealedRelease.Fill, sites: [String] = ["shop.example"], sensitive: [String] = []) -> SealedRelease.Refusal? {
        SealedRelease.refusal(fill, pairedChromes: ["chr_1"], signedSites: sites, allowedSensitive: sensitive, now: Date())
    }

    /// The phone's own checks, whatever planque says: each refusal in the design's order, and a fill that passes them all.
    func testThePhoneRefusesWhatItDidntSignOrCantRead() {
        XCTAssertNil(refusal(fill()), "a subdomain of a site this phone signed for that Chrome")
        XCTAssertEqual(refusal(fill { $0["op"] = "" }), .incomplete)
        XCTAssertEqual(refusal(fill { $0["read"] = "" }), .incomplete)
        XCTAssertEqual(refusal(fill { $0["field"] = "" }), .incomplete)
        XCTAssertEqual(refusal(fill { $0["sealed"] = "sec_x:password:username" }), .handle)
        XCTAssertEqual(refusal(fill { $0["chrome"] = "chr_other" }), .chrome)
        XCTAssertEqual(refusal(fill(expires: Date(timeIntervalSinceNow: -1))), .expired)
        XCTAssertEqual(refusal(fill { $0["origin"] = "http://shop.example" }), .origin)
        XCTAssertEqual(refusal(fill { $0["origin"] = "https://evil.example" }), .notSigned)
        XCTAssertEqual(refusal(fill { $0["origin"] = "https://shop.example.evil.example" }), .notSigned, "a lookalike isn't within")
        XCTAssertEqual(refusal(fill(), sites: []), .notSigned, "no site this phone signed")
        XCTAssertEqual(refusal(fill { $0["origin"] = "https://1password.com" }, sites: ["1password.com"]), .sensitive)
        XCTAssertNil(refusal(fill { $0["origin"] = "https://1password.com" }, sites: ["1password.com"], sensitive: ["1password.com"]))
        XCTAssertEqual(SealedRelease.words(.notSigned), Words.Chrome.releaseNotConnected)
        XCTAssertNil(SealedRelease.words(.expired), "the card says it expired")
    }

    /// A release: a new 16-byte nonce, `exp` 60 s after the yes, and the approval key's signature, raw r‖s, over the vector's
    /// shape of bytes, which a P-256 key verifies.
    func testAReleaseIsSignedOverItsOwnBytesWithANewNonceAndSixtySeconds() throws {
        let key = P256.Signing.PrivateKey()
        let now = Date(timeIntervalSince1970: 1_795_430_400)
        let f = fill()
        let release = try SealedRelease.release(f, now: now) { bytes in
            try key.signature(for: bytes).derRepresentation.base64EncodedString()
        }
        XCTAssertEqual(release.exp, 1_795_430_400_000 + 60_000)
        XCTAssertEqual(ChromePairing.base64URLDecoded(release.nonce)?.count, 16)
        XCTAssertFalse(release.nonce.contains("="), "unpadded")
        XCTAssertEqual([release.chromeID, release.op, release.origin], [f.chromeID, f.op, f.origin])
        let raw = try XCTUnwrap(ChromePairing.base64URLDecoded(release.sig))
        XCTAssertEqual(raw.count, 64)
        let signed = try SealedRelease.bytes(chromeID: f.chromeID, op: f.op, sealed: f.sealed, origin: f.origin, fieldRef: f.fieldRef,
                                             readID: f.readID, nonce: release.nonce, exp: release.exp)
        XCTAssertTrue(key.publicKey.isValidSignature(try P256.Signing.ECDSASignature(rawRepresentation: raw), for: signed))
        XCTAssertNotEqual(SealedRelease.newNonce(), SealedRelease.newNonce(), "never reused")
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: try JSONEncoder().encode(release)) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["chrome_id", "op", "origin", "nonce", "exp", "sig"], "as the answer carries it")
    }
}
