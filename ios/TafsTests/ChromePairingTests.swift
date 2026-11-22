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
        /// The connections a re-pairing carries, and the bytes the phone signs with them in the body.
        struct Entry: Decodable { let grant: String; let sites: [String] }
        let carried: [Entry]
        let signedBytesWithCarried: String
    }

    private func vectors() throws -> Vectors {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("vectors/chrome-pairing.json")
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(Vectors.self, from: Data(contentsOf: url))
    }

    /// "Your Chrome" 1: a re-pairing's carried connections are in the body the phone signs for the extension, in the order sent,
    /// the same bytes as the extension's (vectors/chrome-pairing.json); with none, the body is today's.
    func testCarriedConnectionsAreInTheSignedBytes() throws {
        let v = try vectors()
        let chromeKey = try XCTUnwrap(ChromePairing.base64URLDecoded(v.chromeKey))
        let phoneKey = try XCTUnwrap(ChromePairing.base64URLDecoded(v.phoneKey))
        var c = ChromePairing.confirmation(secret: v.secret, chromeKey: chromeKey, name: v.name, phoneKey: phoneKey)
        XCTAssertEqual(ChromePairing.base64URL(try ChromePairing.signedBytes(pairing: v.pairing, c)), v.signedBytes, "no list: today's bytes")
        c.carried = v.carried.map { ChromePairing.Carried(grant: $0.grant, sites: $0.sites) }
        XCTAssertEqual(ChromePairing.base64URL(try ChromePairing.signedBytes(pairing: v.pairing, c)), v.signedBytesWithCarried)
        c.carried = []
        XCTAssertEqual(ChromePairing.base64URL(try ChromePairing.signedBytes(pairing: v.pairing, c)), v.signedBytes, "an empty list is no list")
        c.carried = v.carried.reversed().map { ChromePairing.Carried(grant: $0.grant, sites: $0.sites) }
        XCTAssertNotEqual(ChromePairing.base64URL(try ChromePairing.signedBytes(pairing: v.pairing, c)), v.signedBytesWithCarried, "the order sent is signed")
        c.carried = [ChromePairing.Carried(grant: v.carried[0].grant, sites: v.carried[0].sites + ["extra.example"])] + v.carried.dropFirst().map { ChromePairing.Carried(grant: $0.grant, sites: $0.sites) }
        XCTAssertNotEqual(ChromePairing.base64URL(try ChromePairing.signedBytes(pairing: v.pairing, c)), v.signedBytesWithCarried, "a site added changes the bytes")
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

    private func chromeRow(_ id: String, profile: String? = nil, browser: String? = "Chrome 131", os: String? = "macOS") throws -> PlanqueClient.Chrome {
        var fields = ["\"chrome_id\":\"\(id)\"", "\"name\":\"Mac \(id)\""]
        if let profile { fields.append("\"profile_id\":\"\(profile)\"") }
        if let browser { fields.append("\"browser\":\"\(browser)\"") }
        if let os { fields.append("\"os\":\"\(os)\"") }
        return try JSONDecoder().decode(PlanqueClient.Chrome.self, from: Data(("{" + fields.joined(separator: ",") + "}").utf8))
    }

    private func offer(profile: String = "", browser: String = "Chrome 131", os: String = "macOS") -> PlanqueClient.ChromeOffer {
        PlanqueClient.ChromeOffer(key: "k", browser: browser, os: os, proof: "p", profileID: profile)
    }

    /// Re-pairing replaces, never adds: the pick starts on the Chrome with the offer's profile id; failing that, on the one Chrome
    /// of the same browser and system; otherwise on none, and nothing moves.
    func testThePickStartsOnTheChromeItMostLikelyReplaces() throws {
        let a = try chromeRow("chr_a", profile: String(repeating: "a", count: 32))
        let b = try chromeRow("chr_b", profile: String(repeating: "b", count: 32), os: "Windows")
        XCTAssertEqual(ChromeReplace.preselect(offer: offer(profile: String(repeating: "b", count: 32)), chromes: [a, b])?.id, "chr_b", "the same profile")
        XCTAssertEqual(ChromeReplace.preselect(offer: offer(profile: String(repeating: "c", count: 32)), chromes: [a, b])?.id, "chr_a",
                       "a reinstalled extension (a new profile id): the one Chrome of the same browser and system")
        XCTAssertNil(ChromeReplace.preselect(offer: offer(profile: String(repeating: "c", count: 32), os: "Linux"), chromes: [a, b]), "none alike: a new Chrome")
        let twin = try chromeRow("chr_c", profile: String(repeating: "d", count: 32))
        XCTAssertNil(ChromeReplace.preselect(offer: offer(profile: String(repeating: "c", count: 32)), chromes: [a, twin, b]), "two alike: never a guess")
        XCTAssertNil(ChromeReplace.preselect(offer: offer(), chromes: []))
        XCTAssertNil(ChromeReplace.preselect(offer: offer(browser: "", os: ""), chromes: [try chromeRow("chr_x", browser: nil, os: nil)]), "nothing known: a new Chrome")
    }

    /// Only a connection live on the Chrome replaced whose sites **this phone's own signature verifies** moves, in planque's order;
    /// the rest end with the old Chrome. Never from what planque says: no signature, a signature by another key, over other sites,
    /// for another grant or another Chrome, or a connection carried here from an earlier Chrome, moves nothing.
    func testOnlyConnectionsTheirOwnSignatureVerifiesAreCarried() throws {
        let key = P256.Signing.PrivateKey()
        let other = P256.Signing.PrivateKey()
        let phone = key.publicKey.derRepresentation
        let old = try chromeRow("chr_1")
        func signed(_ grant: String, chrome: String = "chr_1", sites: [String], by signer: P256.Signing.PrivateKey? = nil) throws -> String {
            let bytes = try ChromePairing.sitesBytes(chromeID: chrome, grant: grant, sites: sites)
            return try ChromePairing.rawSignature(fromDER: (signer ?? key).signature(for: bytes).derRepresentation.base64EncodedString())
        }
        func grant(_ id: String, chrome: String = "chr_1", status: String = "connected", sites: [String], sig: String?, carried: String? = nil) throws -> PlanqueClient.Grant {
            var g = try self.grant(id, chrome: chrome, status: status, sites: sites)
            g.chromeSitesSig = sig
            g.chromeSitesCarried = carried
            return g
        }
        let grants = [
            try grant("g1", sites: ["wikipedia.org", "wikimedia.org"], sig: try signed("g1", sites: ["wikipedia.org", "wikimedia.org"])),
            try grant("g2", sites: ["shop.example"], sig: nil),
            try grant("g3", sites: ["a.example"], sig: try signed("g3", sites: ["a.example"], by: other)),
            try grant("g4", sites: ["b.example", "evil.example"], sig: try signed("g4", sites: ["b.example"])),
            try grant("g5", sites: ["c.example"], sig: try signed("g_other", sites: ["c.example"])),
            try grant("g6", sites: ["d.example"], sig: try signed("g6", chrome: "chr_2", sites: ["d.example"])),
            try grant("g7", sites: ["e.example"], sig: nil, carried: "chr_1"),
            try grant("g8", status: "pending", sites: ["f.example"], sig: try signed("g8", sites: ["f.example"])),
            try grant("g9", chrome: "chr_2", sites: ["g.example"], sig: try signed("g9", chrome: "chr_2", sites: ["g.example"])),
            try grant("g10", sites: ["h.example"], sig: try signed("g10", sites: ["h.example"])),
        ]
        let carried = ChromeReplace.carried(from: old, in: grants, phoneKey: phone)
        XCTAssertEqual(carried, [ChromePairing.Carried(grant: "g1", sites: ["wikipedia.org", "wikimedia.org"]), ChromePairing.Carried(grant: "g10", sites: ["h.example"])])
        XCTAssertEqual(ChromeReplace.ending(on: old, in: grants, carried: carried).map(\.id), ["g2", "g3", "g4", "g5", "g6", "g7"],
                       "the live connections that don't move end with the old Chrome (pending ones are not live on it)")
        XCTAssertEqual(ChromeReplace.carried(from: old, in: [], phoneKey: phone), [])
        XCTAssertEqual(ChromeReplace.carried(from: old, in: grants, phoneKey: Data()), [], "no key to verify with: nothing moves")
        // Today planque's list carries no signature at all, so nothing moves and the replace only unpairs.
        let unsigned = try (0..<3).map { try grant("u\($0)", sites: ["s\($0).example"], sig: nil) }
        XCTAssertEqual(ChromeReplace.carried(from: old, in: unsigned, phoneKey: phone), [])
        let many = try (0..<60).map { try grant("m\($0)", sites: ["s\($0).example"], sig: try signed("m\($0)", sites: ["s\($0).example"])) }
        XCTAssertEqual(ChromeReplace.carried(from: old, in: many, phoneKey: phone).count, 50, "the extension keeps at most 50 connections")
    }
}
