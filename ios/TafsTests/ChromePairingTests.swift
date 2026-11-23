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

}

/// "Your Chrome" (docs/chrome.md): the connections that run in a Chrome are planque's list on the Chrome, read to the owner before it
/// is unpaired and the only ones a re-pairing can carry.
final class ChromeConnectionsTests: XCTestCase {
    private func grant(_ id: String, chrome: String?, status: String, sites: [String], driver: String? = "extension") throws -> PlanqueClient.Grant {
        let sitesJSON = "[" + sites.map { "\"\($0)\"" }.joined(separator: ",") + "]"
        let browser = "{\"driver\":\(driver.map { "\"\($0)\"" } ?? "null"),\"chrome\":\(chrome.map { "\"\($0)\"" } ?? "null"),\"chrome_name\":\"Work Mac\",\"sites\":\(sitesJSON)}"
        let json = "{\"id\":\"\(id)\",\"status\":\"\(status)\",\"text\":\"\",\"digest\":\"d\",\"events\":[],\"request\":{\"server\":\"s\",\"agent\":\"a\",\"tools\":[],\"browser\":\(browser)}}"
        return try JSONDecoder().decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    /// A Chrome row as planque's list sends it, with `connections` as `[(grant, name, sites)]` (nil: a planque that doesn't list them).
    private func chrome(_ id: String, profile: String? = nil, browser: String? = "Chrome 131", os: String? = "macOS",
                        connections: [(String, String, [String])]? = nil) throws -> PlanqueClient.Chrome {
        var fields = ["\"chrome_id\":\"\(id)\"", "\"name\":\"Mac \(id)\""]
        if let profile { fields.append("\"profile_id\":\"\(profile)\"") }
        if let browser { fields.append("\"browser\":\"\(browser)\"") }
        if let os { fields.append("\"os\":\"\(os)\"") }
        if let connections {
            let rows = connections.map { c in "{\"grant\":\"\(c.0)\",\"name\":\"\(c.1)\",\"sites\":[" + c.2.map { "\"\($0)\"" }.joined(separator: ",") + "]}" }
            fields.append("\"connections\":[" + rows.joined(separator: ",") + "]")
        }
        return try JSONDecoder().decode(PlanqueClient.Chrome.self, from: Data(("{" + fields.joined(separator: ",") + "}").utf8))
    }

    private func offer(profile: String = "", browser: String = "Chrome 131", os: String = "macOS") -> PlanqueClient.ChromeOffer {
        PlanqueClient.ChromeOffer(key: "k", browser: browser, os: os, proof: "p", profileID: profile)
    }

    func testAChromeReadsItsConnectionsFromPlanquesList() throws {
        let with = try chrome("chr_1", connections: [("g1", "Wiki lookup", ["wikipedia.org", "wikimedia.org"]), ("g2", "Shop", ["shop.example", "wikipedia.org"])])
        XCTAssertEqual(with.connections?.map(\.grant), ["g1", "g2"])
        XCTAssertEqual(with.connections?.first?.name, "Wiki lookup")
        XCTAssertEqual(ChromeConnections.sites(of: with.connections ?? []), "wikipedia.org, wikimedia.org, shop.example", "each site once, in order")
        XCTAssertEqual(try chrome("chr_2", connections: []).connections, [], "a Chrome with none")
        XCTAssertNil(try chrome("chr_3").connections, "an older planque lists none: nothing is guessed")
        XCTAssertEqual(ChromeConnections.sites(of: []), "")
    }

    /// Re-pairing replaces, never adds: the pick starts on the Chrome with the offer's profile id; failing that, on the one Chrome
    /// of the same browser and system; otherwise on none, and nothing moves.
    func testThePickStartsOnTheChromeItMostLikelyReplaces() throws {
        let a = try chrome("chr_a", profile: String(repeating: "a", count: 32))
        let b = try chrome("chr_b", profile: String(repeating: "b", count: 32), os: "Windows")
        XCTAssertEqual(ChromeReplace.preselect(offer: offer(profile: String(repeating: "b", count: 32)), chromes: [a, b])?.id, "chr_b", "the same profile")
        XCTAssertEqual(ChromeReplace.preselect(offer: offer(profile: String(repeating: "c", count: 32)), chromes: [a, b])?.id, "chr_a",
                       "a reinstalled extension (a new profile id): the one Chrome of the same browser and system")
        XCTAssertNil(ChromeReplace.preselect(offer: offer(profile: String(repeating: "c", count: 32), os: "Linux"), chromes: [a, b]), "none alike: a new Chrome")
        let twin = try chrome("chr_c", profile: String(repeating: "d", count: 32))
        XCTAssertNil(ChromeReplace.preselect(offer: offer(profile: String(repeating: "c", count: 32)), chromes: [a, twin, b]), "two alike: never a guess")
        XCTAssertNil(ChromeReplace.preselect(offer: offer(), chromes: []))
        XCTAssertNil(ChromeReplace.preselect(offer: offer(browser: "", os: ""), chromes: [try chrome("chr_x", browser: nil, os: nil)]), "nothing known: a new Chrome")
    }

    /// Only a connection in planque's list for the Chrome replaced whose sites **this phone's own signature verifies** moves, in
    /// planque's order; the rest end with the old Chrome. Never from what planque says: no signature, a signature by another key, over
    /// other sites, for another grant or another Chrome, or a connection carried here from an earlier Chrome, moves nothing.
    func testOnlyConnectionsTheirOwnSignatureVerifiesAreCarried() throws {
        let key = P256.Signing.PrivateKey()
        let other = P256.Signing.PrivateKey()
        let phone = key.publicKey.derRepresentation
        func signed(_ grant: String, chrome: String = "chr_1", sites: [String], by signer: P256.Signing.PrivateKey? = nil) throws -> String {
            let bytes = try ChromePairing.sitesBytes(chromeID: chrome, grant: grant, sites: sites)
            return try ChromePairing.rawSignature(fromDER: (signer ?? key).signature(for: bytes).derRepresentation.base64EncodedString())
        }
        func grant(_ id: String, status: String = "connected", sites: [String], sig: String?, carried: String? = nil) throws -> PlanqueClient.Grant {
            var g = try self.grant(id, chrome: "chr_1", status: status, sites: sites)
            g.chromeSitesSig = sig
            g.chromeSitesCarried = carried
            return g
        }
        let grants = [
            try grant("g1", sites: ["wikipedia.org", "wikimedia.org"], sig: try signed("g1", sites: ["wikipedia.org", "wikimedia.org"])),
            try grant("g2", sites: ["shop.example"], sig: nil),
            try grant("g3", sites: ["a.example"], sig: try signed("g3", sites: ["a.example"], by: other)),
            try grant("g4", sites: ["b.example", "evil.example"], sig: try signed("g4", sites: ["b.example"])),
            try grant("g5", sites: ["c.example"], sig: try signed("g5_other", sites: ["c.example"])),
            try grant("g6", sites: ["d.example"], sig: try signed("g6", chrome: "chr_2", sites: ["d.example"])),
            try grant("g7", sites: ["e.example"], sig: nil, carried: "chr_1"),
            try grant("g8", status: "pending", sites: ["f.example"], sig: try signed("g8", sites: ["f.example"])),
            try grant("g10", sites: ["h.example"], sig: try signed("g10", sites: ["h.example"])),
            try grant("g11", status: "needs_reconnect", sites: ["i.example"], sig: try signed("g11", sites: ["i.example"])),
        ]
        // planque's list for the Chrome: the live connections with the sites it holds; g4's are wider than the owner signed.
        let old = try chrome("chr_1", connections: [
            ("g1", "Wiki", ["wikipedia.org", "wikimedia.org"]), ("g2", "Shop", ["shop.example"]), ("g3", "A", ["a.example"]),
            ("g4", "B", ["b.example", "evil.example"]), ("g5", "C", ["c.example"]), ("g6", "D", ["d.example"]), ("g7", "E", ["e.example"]),
            ("g8", "F", ["f.example"]), ("g10", "H", ["h.example"]), ("g11", "I", ["i.example"]),
        ])
        let carried = ChromeReplace.carried(from: old, in: grants, phoneKey: phone)
        XCTAssertEqual(carried, [ChromePairing.Carried(grant: "g1", sites: ["wikipedia.org", "wikimedia.org"]), ChromePairing.Carried(grant: "g10", sites: ["h.example"]),
                                 ChromePairing.Carried(grant: "g11", sites: ["i.example"])],
                       "a verified signature moves a connection, a signed-out one too; a pending one is left to end")
        XCTAssertEqual(ChromeReplace.ending(on: old, carried: carried).map(\.grant), ["g2", "g3", "g4", "g5", "g6", "g7", "g8"],
                       "every connection that doesn't move ends with the old Chrome, a pending one too")
        XCTAssertEqual(ChromeReplace.moving(from: old, carried: carried).map(\.grant), ["g1", "g10", "g11"])
        XCTAssertEqual(ChromeReplace.carried(from: old, in: [], phoneKey: phone), [], "no grant read: nothing moves")
        XCTAssertEqual(ChromeReplace.carried(from: old, in: grants, phoneKey: Data()), [], "no key to verify with: nothing moves")
        XCTAssertEqual(ChromeReplace.carried(from: try chrome("chr_1"), in: grants, phoneKey: phone), [], "an older planque lists no connections: nothing moves")
        // Today's planque grant read may carry no signature: nothing moves and the replace only unpairs.
        let unsigned = try (0..<3).map { try grant("u\($0)", sites: ["s\($0).example"], sig: nil) }
        let unsignedChrome = try chrome("chr_1", connections: (0..<3).map { ("u\($0)", "U", ["s\($0).example"]) })
        XCTAssertEqual(ChromeReplace.carried(from: unsignedChrome, in: unsigned, phoneKey: phone), [])
        let many = try (0..<60).map { try grant("m\($0)", sites: ["s\($0).example"], sig: try signed("m\($0)", sites: ["s\($0).example"])) }
        let manyChrome = try chrome("chr_1", connections: (0..<60).map { ("m\($0)", "M", ["s\($0).example"]) })
        XCTAssertEqual(ChromeReplace.carried(from: manyChrome, in: many, phoneKey: phone).count, 50, "the extension keeps at most 50 connections")
    }
}

/// A connection a re-pairing carried keeps no signature of its own; the Chrome's `paired_with` (the pairing as this phone signed it) vouches
/// for it, and a later re-pairing carries it again only when this phone verifies that, with its own key, for exactly its sites.
final class ChromePairedWithTests: XCTestCase {
    private let phone = P256.Signing.PrivateKey()
    private var phoneDER: Data { phone.publicKey.derRepresentation }
    private let keyHash = String(repeating: "a", count: 64)

    private func confirmation(phoneKey: Data? = nil, hash: String? = nil, name: String = "Mac",
                              carried: [(String, [String])] = [("g1", ["wikipedia.org"])]) -> ChromePairing.Confirmation {
        var c = ChromePairing.Confirmation(chromeKeySHA256: hash ?? keyHash, name: name, phoneKey: ChromePairing.base64URL(phoneKey ?? phoneDER), phoneProof: "pp")
        c.carried = carried.map { ChromePairing.Carried(grant: $0.0, sites: $0.1) }
        return c
    }

    /// planque's `paired_with` for a pairing this phone signed: alone, or beside KIFF's request.
    private func pairedWith(_ c: ChromePairing.Confirmation, signer: P256.Signing.PrivateKey? = nil, together: (String, String, String)? = nil,
                            tamper: Bool = false) throws -> PlanqueClient.Chrome.PairedWith {
        let bytes = try ChromePairing.signedBytes(pairing: "pr_1", c)
        let key = signer ?? phone
        let signature: P256.Signing.ECDSASignature
        if let (tenant, signedAt, kiff) = together {
            signature = try key.signature(for: RequestsTogether.message(tenant: tenant, signedAt: signedAt, RequestsTogether.hash(bytes), kiff))
        } else {
            signature = try key.signature(for: bytes)
        }
        let carried = (c.carried ?? []).map { "{\"grant\":\"\($0.grant)\",\"sites\":[" + $0.sites.map { "\"\($0)\"" }.joined(separator: ",") + "]}" }.joined(separator: ",")
        var json = "{\"body\":{\"pairing\":\"pr_1\",\"chrome_key_sha256\":\"\(c.chromeKeySHA256)\",\"name\":\"\(tamper ? "Other" : c.name)\",\"phone_key\":\"\(c.phoneKey)\",\"phone_proof\":\"pp\",\"carried\":[\(carried)]},\"phone_sig\":\"\(ChromePairing.base64URL(signature.rawRepresentation))\""
        if let (tenant, signedAt, kiff) = together { json += ",\"tenant\":\"\(tenant)\",\"signed_at\":\"\(signedAt)\",\"kiff_request_sha256\":\"\(kiff)\"" }
        json += "}"
        return try JSONDecoder().decode(PlanqueClient.Chrome.PairedWith.self, from: Data(json.utf8))
    }

    private func vouches(_ p: PlanqueClient.Chrome.PairedWith, key: Data? = nil, chromeKey: String? = nil, grant: String = "g1", sites: [String] = ["wikipedia.org"]) -> Bool {
        ChromePairing.pairingVouches(p, phoneKey: key ?? phoneDER, chromeKeySHA256: chromeKey, grant: grant, sites: sites)
    }

    func testAPairingThisPhoneSignedVouchesForTheConnectionItCarriedWithExactlyItsSites() throws {
        let alone = try pairedWith(confirmation())
        XCTAssertTrue(vouches(alone))
        XCTAssertTrue(vouches(alone, chromeKey: keyHash), "planque lists the Chrome's key, and the body was made for it")
        let kiff = String(repeating: "b", count: 64)
        XCTAssertTrue(vouches(try pairedWith(confirmation(), together: ("t_1", "2026-11-23T01:00:00Z", kiff))), "the one-fingerprint form")
    }

    func testAnythingElseVouchesForNothing() throws {
        let good = try pairedWith(confirmation())
        XCTAssertFalse(vouches(good, grant: "g2"), "a connection it didn't carry")
        XCTAssertFalse(vouches(good, sites: ["wikipedia.org", "evil.example"]), "more sites than it carried")
        XCTAssertFalse(vouches(good, sites: ["other.example"]), "other sites")
        XCTAssertFalse(vouches(good, sites: []), "no sites")
        XCTAssertFalse(vouches(good, chromeKey: String(repeating: "c", count: 64)), "made for another Chrome's key")
        XCTAssertFalse(vouches(good, key: P256.Signing.PrivateKey().publicKey.derRepresentation), "another phone's key")
        XCTAssertFalse(vouches(try pairedWith(confirmation(), tamper: true)), "a body changed after it was signed")
        let other = P256.Signing.PrivateKey()
        XCTAssertFalse(vouches(try pairedWith(confirmation(), signer: other)), "a signature by another key")
        XCTAssertFalse(vouches(try pairedWith(confirmation(phoneKey: other.publicKey.derRepresentation), signer: other)), "signed by another phone, named for it")
        XCTAssertFalse(vouches(try pairedWith(confirmation(carried: [("g1", ["wikipedia.org", "evil.example"])]))), "sites that differ from the connection's")
        // The one-fingerprint form: its three fields travel together, KIFF's hash is its own and 64 lower-case hex digits, never this body's own.
        let own = RequestsTogether.hash(try ChromePairing.signedBytes(pairing: "pr_1", confirmation()))
        XCTAssertFalse(vouches(try pairedWith(confirmation(), together: ("t_1", "2026-11-23T01:00:00Z", own))), "paired with itself")
        XCTAssertFalse(vouches(try pairedWith(confirmation(), together: ("t_1", "2026-11-23T01:00:00Z", "ABC"))), "not a hash")
        XCTAssertFalse(vouches(try pairedWith(confirmation(), together: ("", "2026-11-23T01:00:00Z", String(repeating: "b", count: 64)))), "no tenant")
    }

    /// A malformed `paired_with` is none, and the Chromes list still reads.
    func testAMalformedPairedWithIsNoneAndTheListStillReads() throws {
        let json = #"[{"chrome_id":"chr_1","name":"Mac","paired_with":{"body":"nope"}},{"chrome_id":"chr_2","name":"Win","key_sha256":"abc"}]"#
        let chromes = try JSONDecoder().decode([PlanqueClient.Chrome].self, from: Data(json.utf8))
        XCTAssertEqual(chromes.map(\.id), ["chr_1", "chr_2"])
        XCTAssertNil(chromes[0].pairedWith)
        XCTAssertEqual(chromes[1].keySHA256, "abc")
    }

    /// A connection carried before moves again only when the Chrome that holds it has a `paired_with` that vouches for it.
    func testACarriedConnectionMovesAgainOnlyWhenThePairingVouchesForIt() throws {
        func grant(_ id: String, carriedBy: String?, sites: [String] = ["wikipedia.org"], status: String = "connected") throws -> PlanqueClient.Grant {
            let list = "[" + sites.map { "\"\($0)\"" }.joined(separator: ",") + "]"
            let by = carriedBy.map { ",\"chrome_sites_carried\":\"\($0)\"" } ?? ""
            let json = "{\"id\":\"\(id)\",\"status\":\"\(status)\",\"text\":\"\",\"digest\":\"d\",\"events\":[],\"request\":{\"server\":\"s\",\"agent\":\"a\",\"tools\":[],\"browser\":{\"driver\":\"extension\",\"chrome\":\"chr_1\",\"sites\":\(list)}}\(by)}"
            return try JSONDecoder().decode(PlanqueClient.Grant.self, from: Data(json.utf8))
        }
        func chrome(_ pairedWith: PlanqueClient.Chrome.PairedWith?, sites: [String] = ["wikipedia.org"]) throws -> PlanqueClient.Chrome {
            var c = try JSONDecoder().decode(PlanqueClient.Chrome.self, from: Data(#"{"chrome_id":"chr_1","name":"Mac"}"#.utf8))
            c.connections = [.init(grant: "g1", name: "Wiki", sites: sites)]
            c.pairedWith = pairedWith
            return c
        }
        let vouching = try pairedWith(confirmation())
        XCTAssertEqual(ChromeReplace.carried(from: try chrome(vouching), in: [try grant("g1", carriedBy: "chr_1")], phoneKey: phoneDER),
                       [ChromePairing.Carried(grant: "g1", sites: ["wikipedia.org"])])
        XCTAssertEqual(ChromeReplace.carried(from: try chrome(nil), in: [try grant("g1", carriedBy: "chr_1")], phoneKey: phoneDER), [], "no pairing kept")
        XCTAssertEqual(ChromeReplace.carried(from: try chrome(vouching), in: [try grant("g1", carriedBy: "chr_other")], phoneKey: phoneDER), [], "carried by another Chrome")
        XCTAssertEqual(ChromeReplace.carried(from: try chrome(vouching), in: [try grant("g1", carriedBy: nil)], phoneKey: phoneDER), [], "no signature at all")
        XCTAssertEqual(ChromeReplace.carried(from: try chrome(vouching, sites: ["wikipedia.org", "evil.example"]), in: [try grant("g1", carriedBy: "chr_1", sites: ["wikipedia.org", "evil.example"])], phoneKey: phoneDER), [], "sites it didn't carry")
        XCTAssertEqual(ChromeReplace.carried(from: try chrome(vouching), in: [try grant("g1", carriedBy: "chr_1", status: "pending")], phoneKey: phoneDER), [], "not a live connection")
    }
}
