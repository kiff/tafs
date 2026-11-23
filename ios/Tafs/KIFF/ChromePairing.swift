import CryptoKit
import Foundation

/// The phone's side of pairing a Chrome (docs/chrome.md, "Pairing"): the code it shows, the check of the extension's proof,
/// and the confirmation it signs. The code's last 17 characters are a secret this phone makes and never sends: the
/// extension proves its key under it, and this phone proves its own key under it, so planque, which relays both, can't put
/// another key on either side. Nothing here asks for Face ID; `AppModel.confirmChrome` signs the body this builds.
enum ChromePairing {
    /// The code's characters: no 0, 1, I, L, O or U.
    static let alphabet = Array("23456789ABCDEFGHJKMNPQRSTVWXYZ")
    /// 17 characters of 30, about 83 bits: planque stores the extension's proof, so the secret must hold against an
    /// offline search of it.
    static let secretLength = 17

    static let pairLabel = "tafs-chrome/pair/v1"
    static let extensionProofLabel = "tafs-chrome/ext/v1"
    /// The extension's proof that it checks one Face ID's signature over the pairing and its KIFF device together (`one_touch`).
    static let oneTouchProofLabel = "tafs-chrome/ext-one-touch/v1"
    static let phoneProofLabel = "tafs-chrome/phone/v1"
    /// What the phone signs when the owner approves a browser grant: its sites, and each sensitive site allowed by name.
    static let sitesLabel = "tafs-chrome/sites/v1"
    static let sensitiveLabel = "tafs-chrome/sensitive/v1"

    /// A new secret, from the system's random source.
    static func newSecret() -> String {
        var generator = SystemRandomNumberGenerator()
        return String((0..<secretLength).map { _ in alphabet.randomElement(using: &generator)! })
    }

    /// The code as the phone shows it: the lookup planque gave, then the secret in groups of four
    /// ("K7QF-29XM-DPQR-STVW-XYZ2-3"). The extension takes it with or without the dashes.
    static func code(lookup: String, secret: String) -> String {
        var groups = [lookup]
        var rest = Substring(secret)
        while !rest.isEmpty {
            groups.append(String(rest.prefix(4)))
            rest = rest.dropFirst(4)
        }
        return groups.joined(separator: "-")
    }

    /// HMAC-SHA256 under the secret, over label ‖ 0x00 ‖ data.
    static func hmac(_ secret: String, _ label: String, _ data: Data) -> Data {
        var message = Data(label.utf8)
        message.append(0)
        message.append(data)
        return Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: Data(secret.utf8))))
    }

    /// Whether `proof` is the extension's proof of `key` (its SPKI DER), and of its KIFF offer's text, under our secret.
    /// A wrong one means another extension used the code's lookup, or planque changed either: nothing is paired.
    static func extensionProofHolds(secret: String, key: Data, proof: String, kiffOffer: String = "") -> Bool {
        guard let given = base64URLDecoded(proof) else { return false }
        // The proof covers the KIFF device's offer too, when the extension sends one: planque can swap neither key.
        let expected = hmac(secret, extensionProofLabel, key + Data(kiffOffer.utf8))
        return given.count == expected.count && zip(given, expected).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    /// Whether the extension's `one_touch` flag is its own ("Your Chrome" 5): the same HMAC as `extensionProofHolds`, under its
    /// own label, so only someone with the code's secret could add it. Only then does the phone sign the pairing and the
    /// extension's KIFF device as one; planque, which relays the flag, can't turn it on.
    static func oneTouchProofHolds(secret: String, key: Data, proof: String, kiffOffer: String = "") -> Bool {
        guard let given = base64URLDecoded(proof) else { return false }
        let expected = hmac(secret, oneTouchProofLabel, key + Data(kiffOffer.utf8))
        return given.count == expected.count && zip(given, expected).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    /// The SHA-256 of the extension's SPKI DER, in lowercase hex, as the signed body names it.
    static func chromeKeyHash(_ key: Data) -> String {
        SHA256.hash(data: key).map { String(format: "%02x", $0) }.joined()
    }

    /// This phone's proof of its key and the extension's under the secret: HMAC(secret, phone label ‖ phone key ‖ sha256(chrome key)).
    static func phoneProof(secret: String, phoneKey: Data, chromeKey: Data) -> String {
        var data = phoneKey
        data.append(contentsOf: SHA256.hash(data: chromeKey))
        return base64URL(hmac(secret, phoneProofLabel, data))
    }

    /// A connection a re-pairing moves to the new Chrome, with the sites the owner approved for it ("Your Chrome" 1). It is
    /// inside what the phone signs for the extension, so planque can't add, drop or change one.
    struct Carried: Codable, Equatable {
        let grant: String
        let sites: [String]
    }

    /// What the phone signs to confirm the pairing, and sends with its signature.
    struct Confirmation: Encodable, Equatable {
        let chromeKeySHA256: String
        let name: String
        let phoneKey: String
        let phoneProof: String
        var phoneSig = ""
        /// The Chrome this one replaces (planque's field, on the confirm's own signature) and the connections that move to
        /// it. Both absent for a Chrome that replaces nothing: the pairing is then exactly today's.
        var replaces: String? = nil
        var carried: [Carried]? = nil
        /// One Face ID for both acts ("Your Chrome" 5): `phone_sig` is then the approval key's `kiff.requests.v1` signature, and
        /// these are what the extension rebuilds it from, beside its own pairing message: the account, the one `signed_at`, and
        /// the hash of KIFF's request for its view-only device. All three or none; none for the two signatures of an older
        /// extension. Not inside the pairing's signed bytes, which stay exactly today's.
        var tenant: String? = nil
        var signedAt: String? = nil
        var kiffRequestSHA256: String? = nil

        enum CodingKeys: String, CodingKey {
            case chromeKeySHA256 = "chrome_key_sha256", name, phoneKey = "phone_key", phoneProof = "phone_proof", phoneSig = "phone_sig", replaces, carried
            case tenant, signedAt = "signed_at", kiffRequestSHA256 = "kiff_request_sha256"
        }
    }

    static func confirmation(secret: String, chromeKey: Data, name: String, phoneKey: Data) -> Confirmation {
        Confirmation(chromeKeySHA256: chromeKeyHash(chromeKey), name: name, phoneKey: base64URL(phoneKey),
                     phoneProof: phoneProof(secret: secret, phoneKey: phoneKey, chromeKey: chromeKey))
    }

    /// The bytes the phone signs for `confirmation` of pairing `pairing`: label ‖ 0x00 ‖ JCS of
    /// `{pairing, chrome_key_sha256, name, phone_key, phone_proof}`, and `carried` (`[{grant, sites}]`, in the order sent)
    /// when the pairing carries connections; without any, exactly today's body.
    static func signedBytes(pairing: String, _ c: Confirmation) throws -> Data {
        var fields: [String: JCS.Value] = [
            "pairing": .string(pairing),
            "chrome_key_sha256": .string(c.chromeKeySHA256),
            "name": .string(c.name),
            "phone_key": .string(c.phoneKey),
            "phone_proof": .string(c.phoneProof),
        ]
        if let carried = c.carried, !carried.isEmpty {
            fields["carried"] = .array(carried.map { .object(["grant": .string($0.grant), "sites": .array($0.sites.map { .string($0) })]) })
        }
        let body: JCS.Value = .object(fields)
        var out = Data(pairLabel.utf8)
        out.append(0)
        out.append(try JCS.canonical(body))
        return out
    }

    /// The bytes the phone signs for a browser grant's sites on one paired Chrome: label ‖ 0x00 ‖ JCS of
    /// `{chrome_id, grant, sites}`. The extension runs a step only on sites inside them (`protocol.checkSites`).
    static func sitesBytes(chromeID: String, grant: String, sites: [String]) throws -> Data {
        try labelled(sitesLabel, .object(["chrome_id": .string(chromeID), "grant": .string(grant), "sites": .array(sites.map { .string($0) })]))
    }

    /// The bytes the phone signs to allow one sensitive site for one grant on one paired Chrome: label ‖ 0x00 ‖ JCS of
    /// `{chrome_id, grant, site}`. Without it, the extension never opens that site (`protocol.checkAllowances`).
    static func sensitiveBytes(chromeID: String, grant: String, site: String) throws -> Data {
        try labelled(sensitiveLabel, .object(["chrome_id": .string(chromeID), "grant": .string(grant), "site": .string(site)]))
    }

    /// The signature `chrome_sites_sig` carries when the owner approves a grant that uses their own Chrome: `sign` signs the
    /// bytes of `{chrome_id, grant, sites}` (with the approval key, under the Face ID of the approval) and the answer
    /// goes as raw r‖s. Nil for a grant that doesn't use the owner's Chrome. Refused when the grant names no Chrome or no
    /// sites: nothing is signed over an open list, and planque never approves such a grant.
    static func sitesSignature(of browser: PlanqueClient.Grant.Request.Browser?, grant: String, sign: (Data) throws -> String) throws -> String? {
        guard let browser, browser.driver == "extension" else { return nil }
        guard let chrome = browser.chrome, !chrome.isEmpty, let sites = browser.sites, !sites.isEmpty else {
            throw PlanqueClient.PlanqueError.digestMismatch
        }
        return try rawSignature(fromDER: try sign(try sitesBytes(chromeID: chrome, grant: grant, sites: sites)))
    }

    /// The bytes the phone signs for `PUT …/grants/{grant}/chrome`: `{chrome_id, grant, sites}` for the Chrome the connection moves
    /// **to**, over the sites it already has (the ones the owner approved), signed with the approval key under a Face ID and sent
    /// as raw r‖s (`rawSignature(fromDER:)`). Refused when there is no Chrome or no sites: nothing is signed over an open list.
    static func switchBytes(to chromeID: String, grant: String, sites: [String]) throws -> Data {
        guard !chromeID.isEmpty, !sites.isEmpty else { throw PlanqueClient.PlanqueError.digestMismatch }
        return try sitesBytes(chromeID: chromeID, grant: grant, sites: sites)
    }

    /// Whether a Chrome's `paired_with` (the re-pairing that carried connections to it, as this phone signed it) **vouches for** one
    /// connection: it was signed by this phone's own approval key (`phoneKey`, SPKI DER; the body names the same key), its body names
    /// the connection with **exactly** these sites, its signature holds (alone, `tafs-chrome/pair/v1`; or beside KIFF's request under
    /// one fingerprint, `kiff.requests.v1`, never paired with itself), and, when planque lists the Chrome's key (`chromeKeySHA256`),
    /// the body was made for that key. Anything else vouches for nothing, and the connection ends with the old Chrome as before.
    /// The same rule planque applies (`ChromePairedWith.vouches`), checked here with the phone's own key.
    static func pairingVouches(_ pairedWith: PlanqueClient.Chrome.PairedWith, phoneKey: Data, chromeKeySHA256: String?,
                               grant: String, sites: [String]) -> Bool {
        let body = pairedWith.body
        guard !phoneKey.isEmpty, body.phoneKey == base64URL(phoneKey), !sites.isEmpty,
              body.carried.contains(where: { $0.grant == grant && $0.sites == sites }) else { return false }
        if let chromeKeySHA256, !chromeKeySHA256.isEmpty, body.chromeKeySHA256 != chromeKeySHA256 { return false }
        guard let key = try? P256.Signing.PublicKey(derRepresentation: phoneKey),
              let raw = base64URLDecoded(pairedWith.phoneSig),
              let signature = try? P256.Signing.ECDSASignature(rawRepresentation: raw),
              let own = try? signedBytes(pairing: body.pairing, body) else { return false }
        let together = [pairedWith.tenant, pairedWith.signedAt, pairedWith.kiffRequestSHA256].compactMap { $0 }.filter { !$0.isEmpty }
        if together.isEmpty { return key.isValidSignature(signature, for: own) }
        guard together.count == 3, let tenant = pairedWith.tenant, let signedAt = pairedWith.signedAt, let kiff = pairedWith.kiffRequestSHA256,
              kiff.count == 64, kiff.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) else { return false }
        let ownHash = RequestsTogether.hash(own)
        guard ownHash != kiff else { return false }
        return key.isValidSignature(signature, for: RequestsTogether.message(tenant: tenant, signedAt: signedAt, ownHash, kiff))
    }

    /// The bytes of a pairing body as `paired_with` carries it: the same label ‖ 0x00 ‖ JCS as the pairing the phone signed.
    private static func signedBytes(pairing: String, _ body: PlanqueClient.Chrome.PairedWith.Body) throws -> Data {
        let carried: JCS.Value = .array(body.carried.map { .object(["grant": .string($0.grant), "sites": .array($0.sites.map { .string($0) })]) })
        let value: JCS.Value = .object([
            "pairing": .string(pairing), "chrome_key_sha256": .string(body.chromeKeySHA256), "name": .string(body.name),
            "phone_key": .string(body.phoneKey), "phone_proof": .string(body.phoneProof), "carried": carried,
        ])
        var out = Data(pairLabel.utf8)
        out.append(0)
        out.append(try JCS.canonical(value))
        return out
    }

    /// Whether `signature` (raw r‖s, base64url) is this phone's own signature, by `publicKeyDER` (its approval key), over `sites` for
    /// `grant` on the Chrome `chromeID`: what the phone signed at approval, and the only copy of a connection's sites it carries
    /// to a new Chrome ("Your Chrome" 1). False for anything else, never an error.
    static func sitesSignatureHolds(_ signature: String, publicKeyDER: Data, chromeID: String, grant: String, sites: [String]) -> Bool {
        guard !sites.isEmpty, let raw = base64URLDecoded(signature), let bytes = try? sitesBytes(chromeID: chromeID, grant: grant, sites: sites),
              let key = try? P256.Signing.PublicKey(derRepresentation: publicKeyDER),
              let sig = try? P256.Signing.ECDSASignature(rawRepresentation: raw) else { return false }
        return key.isValidSignature(sig, for: bytes)
    }

    private static func labelled(_ label: String, _ body: JCS.Value) throws -> Data {
        var out = Data(label.utf8)
        out.append(0)
        out.append(try JCS.canonical(body))
        return out
    }

    /// The sites the extension blocks unless the phone allows them one by one (chrome/src/sites.js `SENSITIVE`,
    /// vectors/chrome-sensitive.json): each a site and its subdomains. The approval card marks a grant's sensitive sites
    /// from this, and signs an allowance only for one the owner ticked.
    static let sensitiveSites: [String] = [
        "1password.com",
        "lastpass.com",
        "bitwarden.com",
        "dashlane.com",
        "keepersecurity.com",
        "nordpass.com",
        "proton.me",
        "coinbase.com",
        "binance.com",
        "kraken.com",
        "metamask.io",
        "blockchain.com",
        "crypto.com",
        "ledger.com",
        "bitstamp.net",
        "paypal.com",
        "wise.com",
        "revolut.com",
        "n26.com",
        "venmo.com",
        "cash.app",
        "klarna.com",
        "payoneer.com",
        "ameli.fr",
        "nhs.uk",
        "mychart.com",
        "healthcare.gov"
    ]
    /// A host with one of these labels names a bank, a government or a health account.
    static let sensitiveLabels = try! NSRegularExpression(pattern: #"(^|[.-])(bank|banking|banque|bancaire|banca|banco|bancario|sparkasse|volksbank|bausparkasse|credit-?union|tresor|impots|irs|hmrc|gov|gouv|gob|bund|gc|nic|health|sante|gesundheit|salud|saude|patient|medicare|medicaid)([.-]|$)"#)

    /// Whether the extension treats `host` as sensitive, as `sites.sensitive` does.
    static func isSensitive(_ host: String) -> Bool {
        let host = host.lowercased()
        if sensitiveSites.contains(where: { host == $0 || host.hasSuffix("." + $0) }) { return true }
        return sensitiveLabels.firstMatch(in: host, range: NSRange(host.startIndex..., in: host)) != nil
    }

    /// A signature as the contract sends it: raw r‖s, base64url without padding, from the base64 DER the signer gives.
    static func rawSignature(fromDER base64: String) throws -> String {
        guard let der = Data(base64Encoded: base64) else { throw CryptoKitError.incorrectParameterSize }
        return base64URL(try P256.Signing.ECDSASignature(derRepresentation: der).rawRepresentation)
    }

    /// A name for the Chrome: the owner's, trimmed, at most 40 characters; the browser's own words when they gave none.
    static func name(_ typed: String, browser: String) -> String {
        let trimmed = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return String((trimmed.isEmpty ? browser : trimmed).prefix(40))
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func base64URLDecoded(_ text: String) -> Data? {
        guard text.allSatisfy({ $0.isLetter && $0.isASCII || $0.isNumber && $0.isASCII || $0 == "-" || $0 == "_" }) else { return nil }
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        return Data(base64Encoded: s)
    }
}
