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

    private struct GrantVectors: Decodable {
        let chromeId, grant, site, sitesSignedBytes, sensitiveSignedBytes: String
        let sites: [String]
    }

    private struct SensitiveVectors: Decodable {
        let sites: [String]
        let labels: String
        let hosts: [String: Bool]
    }

    private func repoFile<T: Decodable>(_ path: String) throws -> T {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent(path)
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(T.self, from: Data(contentsOf: url))
    }

    /// Approving a browser grant: the bytes for its sites and for one sensitive site are the extension's
    /// (vectors/chrome-grant.json), so what the phone signs is what `checkSites` and `checkAllowances` verify.
    func testAGrantsSitesAndASensitiveAllowanceSignTheExtensionsBytes() throws {
        let g: GrantVectors = try repoFile("vectors/chrome-grant.json")
        XCTAssertEqual(ChromePairing.base64URL(try ChromePairing.sitesBytes(chromeID: g.chromeId, grant: g.grant, sites: g.sites)), g.sitesSignedBytes)
        XCTAssertEqual(ChromePairing.base64URL(try ChromePairing.sensitiveBytes(chromeID: g.chromeId, grant: g.grant, site: g.site)), g.sensitiveSignedBytes)
        XCTAssertNotEqual(try ChromePairing.sitesBytes(chromeID: g.chromeId, grant: g.grant, sites: g.sites + ["evil.example"]),
                          try ChromePairing.sitesBytes(chromeID: g.chromeId, grant: g.grant, sites: g.sites), "another site list is other bytes")
    }

    /// `chrome_sites_sig`: the phone signs exactly the extension's bytes for the grant's sites (vectors/chrome-grant.json), and
    /// sends the signature as raw r‖s that the extension's check accepts.
    func testAnExtensionGrantIsApprovedWithTheSignatureOverItsSites() throws {
        let g: GrantVectors = try repoFile("vectors/chrome-grant.json")
        let key = P256.Signing.PrivateKey()
        var signed: [Data] = []
        let browser = PlanqueClient.Grant.Request.Browser(driver: "extension", chrome: g.chromeId, sites: g.sites)
        let sig = try XCTUnwrap(try ChromePairing.sitesSignature(of: browser, grant: g.grant) { message in
            signed.append(message)
            return try key.signature(for: message).derRepresentation.base64EncodedString()
        })
        XCTAssertEqual(signed.map { ChromePairing.base64URL($0) }, [g.sitesSignedBytes], "the one message signed is the vector's")
        let raw = try XCTUnwrap(ChromePairing.base64URLDecoded(sig))
        XCTAssertEqual(raw.count, 64)
        XCTAssertTrue(key.publicKey.isValidSignature(try P256.Signing.ECDSASignature(rawRepresentation: raw), for: signed[0]))
        XCTAssertFalse(key.publicKey.isValidSignature(try P256.Signing.ECDSASignature(rawRepresentation: raw),
                                                      for: try ChromePairing.sitesBytes(chromeID: g.chromeId, grant: g.grant, sites: g.sites + ["evil.example"])),
                       "another site list isn't what was signed")
    }

    /// A grant that doesn't use the owner's Chrome signs nothing; one that does and names no Chrome or no sites is refused.
    func testOnlyAnExtensionGrantWithAChromeAndSitesIsSigned() throws {
        var called = false
        let sign: (Data) throws -> String = { _ in called = true; return "" }
        XCTAssertNil(try ChromePairing.sitesSignature(of: nil, grant: "g_1", sign: sign))
        XCTAssertNil(try ChromePairing.sitesSignature(of: PlanqueClient.Grant.Request.Browser(driver: nil, chrome: nil, sites: ["shop.example"]), grant: "g_1", sign: sign))
        XCTAssertThrowsError(try ChromePairing.sitesSignature(of: PlanqueClient.Grant.Request.Browser(driver: "extension", chrome: nil, sites: ["shop.example"]), grant: "g_1", sign: sign))
        XCTAssertThrowsError(try ChromePairing.sitesSignature(of: PlanqueClient.Grant.Request.Browser(driver: "extension", chrome: "chr_1", sites: []), grant: "g_1", sign: sign))
        XCTAssertThrowsError(try ChromePairing.sitesSignature(of: PlanqueClient.Grant.Request.Browser(driver: "extension", chrome: "chr_1", sites: nil), grant: "g_1", sign: sign))
        XCTAssertFalse(called, "nothing is signed for these")
    }

    /// The approve body carries `chrome_sites_sig` only when the phone made one, so every other approval is the body it was.
    func testTheApproveBodyCarriesTheSitesSignatureOnlyWhenThereIsOne() throws {
        func keys(_ body: PlanqueClient.ApproveBody) throws -> Set<String> {
            let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: Any]
            return Set(try XCTUnwrap(object).keys)
        }
        XCTAssertEqual(try keys(.init(digest: "d", kiffProfile: nil)), ["digest"])
        XCTAssertEqual(try keys(.init(digest: "d", kiffProfile: nil, chromeSitesSig: "sig")), ["digest", "chrome_sites_sig"])
    }

    /// A browser grant's limits are read from planque's request, and a grant without them reads as before.
    func testAGrantsBrowserLimitsAreRead() throws {
        let with = try JSONDecoder().decode(PlanqueClient.Grant.Request.self, from: Data(#"{"server":"s","agent":"a","tools":[],"browser":{"driver":"extension","chrome":"chr_1","sites":["shop.example"]}}"#.utf8))
        XCTAssertEqual(with.browser, PlanqueClient.Grant.Request.Browser(driver: "extension", chrome: "chr_1", sites: ["shop.example"]))
        let without = try JSONDecoder().decode(PlanqueClient.Grant.Request.self, from: Data(#"{"server":"s","agent":"a","tools":[]}"#.utf8))
        XCTAssertNil(without.browser)
    }

    /// The sensitive list is the extension's (vectors/chrome-sensitive.json), and reads each sample host as it does.
    func testTheSensitiveListIsTheExtensions() throws {
        let s: SensitiveVectors = try repoFile("vectors/chrome-sensitive.json")
        XCTAssertEqual(ChromePairing.sensitiveSites, s.sites)
        XCTAssertEqual(ChromePairing.sensitiveLabels.pattern, s.labels)
        for (host, want) in s.hosts {
            XCTAssertEqual(ChromePairing.isSensitive(host), want, host)
        }
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

/// "Your Chrome" (docs/chrome.md): which connections run in a Chrome, read to the owner before it is unpaired.
final class ChromeConnectionsTests: XCTestCase {
    private func grant(_ id: String, chrome: String?, status: String, sites: [String], driver: String? = "extension") throws -> PlanqueClient.Grant {
        let sitesJSON = "[" + sites.map { "\"\($0)\"" }.joined(separator: ",") + "]"
        let browser = "{\"driver\":\(driver.map { "\"\($0)\"" } ?? "null"),\"chrome\":\(chrome.map { "\"\($0)\"" } ?? "null"),\"chrome_name\":\"Work Mac\",\"sites\":\(sitesJSON)}"
        let json = "{\"id\":\"\(id)\",\"status\":\"\(status)\",\"text\":\"\",\"digest\":\"d\",\"events\":[],\"request\":{\"server\":\"s\",\"agent\":\"a\",\"tools\":[],\"browser\":\(browser)}}"
        return try JSONDecoder().decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    private func chrome(_ id: String) throws -> PlanqueClient.Chrome {
        try JSONDecoder().decode(PlanqueClient.Chrome.self, from: Data("{\"chrome_id\":\"\(id)\",\"name\":\"Work Mac\"}".utf8))
    }

    func testOnlyAChromesOwnLiveConnectionsAreListed() throws {
        let grants = [
            try grant("g1", chrome: "chr_1", status: "connected", sites: ["wikipedia.org"]),
            try grant("g2", chrome: "chr_1", status: "pending", sites: ["shop.example", "wikipedia.org"]),
            try grant("g3", chrome: "chr_2", status: "connected", sites: ["other.example"]),
            try grant("g4", chrome: "chr_1", status: "disconnected", sites: ["ended.example"]),
            try grant("g5", chrome: "chr_1", status: "failed", sites: ["failed.example"]),
            try grant("g6", chrome: "chr_1", status: "connected", sites: ["hosted.example"], driver: nil),
        ]
        let on = ChromeConnections.on(try chrome("chr_1"), in: grants)
        XCTAssertEqual(on.map(\.id), ["g1", "g2"], "its own, not ended, not another Chrome's, not planque's hosted browser")
        XCTAssertEqual(ChromeConnections.sites(of: on), "wikipedia.org, shop.example", "each site once, in order")
        XCTAssertEqual(ChromeConnections.sites(of: []), "")
        XCTAssertEqual(on.first?.request.browser?.chromeName, "Work Mac", "planque's chrome_name is read")
    }
}
