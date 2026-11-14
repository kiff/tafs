import XCTest
@testable import Tafs

/// A computer's pairing code (docs/desktop-client.md §5.1 and §5.2), read strictly before anything is
/// shown or signed. The keys and check codes are fixed values worked out apart from the app.
final class PairingTests: XCTestCase {
    static let pub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE6op8SeGpe6FWBj-IKF78IfKz3rZ3i4DBGW-7dTNpAlJVKt2--h_QoKnPerk5HxF2kXOn_nOsp1RrtwSVQqTtIg"
    static let approvalPub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAElt8DMH6ZOAJCRDnJghUJ3QQ_6Gt9F4d6vhLyrmvHj6BXPiGa0lQWKaRmRDGURuKpNNEIPUEaqmc0j6H2E6mwDA"
    static let nonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    static let now = PairingRequest.time("2026-11-14T19:00:00Z")!

    private func code(_ change: (inout [String: Any]) -> Void = { _ in }) -> String {
        var fields: [String: Any] = ["v": "kiff.pair.v1", "pub": Self.pub, "approval_pub": Self.approvalPub, "name": "tafs on this Windows PC",
                                     "platform": "windows", "kind": "hello", "nonce": Self.nonce, "exp": "2026-11-14T19:04:00Z"]
        change(&fields)
        return String(decoding: try! JSONSerialization.data(withJSONObject: fields), as: UTF8.self)
    }

    private func read(_ text: String) -> Result<PairingRequest, PairingRequest.Problem> { PairingRequest.read(text, now: Self.now) }

    func testACodeIsReadAndItsCheckCodeCoversBothKeys() throws {
        let request = try read(code()).get()
        XCTAssertEqual(request.name, "tafs on this Windows PC")
        XCTAssertEqual(request.checkCode, "XFI4MD", "base32(sha256(pub_der ‖ approval_pub_der)), first 6")

        let passkey = try read(code { $0["kind"] = "passkey"; $0["approval_pub"] = nil }).get()
        XCTAssertNil(passkey.approvalDER)
        XCTAssertEqual(passkey.checkCode, "F2ULUM", "no approval key: the device key's alone")
    }

    /// The vector every implementation asserts literally: the P-256 public keys of private keys 1 and 2
    /// (the curve's G and 2G), so anyone can rebuild them, with the codes worked out apart from any app.
    func testTheSharedVector() throws {
        let g = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q"
        let twoG = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfPJ7GI0DT36KUjgDBLUaw8CJaeJ38hs1pgtI_EdmmXgHd1UQ247QQCk9msafdDDbun2t5jzpgimeBLedInhz0Q"
        XCTAssertEqual(try read(code { $0["pub"] = g; $0["approval_pub"] = twoG }).get().checkCode, "H6SJNU")
        XCTAssertEqual(try read(code { $0["pub"] = g; $0["approval_pub"] = nil; $0["kind"] = "passkey" }).get().checkCode, "LTJFF6")
    }

    func testTheBodyIsTheCodesFieldsAsSent() throws {
        let request = try read(code()).get()
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: request.body) as? [String: String])
        XCTAssertEqual(body, ["v": "kiff.pair.v1", "pub": Self.pub, "approval_pub": Self.approvalPub, "name": "tafs on this Windows PC",
                              "platform": "windows", "kind": "hello", "nonce": Self.nonce, "exp": "2026-11-14T19:04:00Z"])
        XCTAssertEqual(request.body, request.body, "the same bytes each time, so the signature covers what's sent")
        let passkey = try read(code { $0["kind"] = "passkey"; $0["approval_pub"] = nil }).get()
        XCTAssertNil((try JSONSerialization.jsonObject(with: passkey.body) as? [String: String])?["approval_pub"])
    }

    func testAnythingElseIsRefusedWithWhy() {
        XCTAssertEqual(read("https://example.com"), .failure(.notAPairingCode))
        XCTAssertEqual(read(code { $0["v"] = "kiff.pair.v2" }), .failure(.notAPairingCode))
        XCTAssertEqual(read(code { $0["nonce"] = nil }), .failure(.notAPairingCode))

        XCTAssertEqual(read(code { $0["exp"] = "2026-11-14T18:59:59Z" }), .failure(.expired))
        XCTAssertEqual(read(code { $0["exp"] = "2026-11-14T19:10:00Z" }), .failure(.badField), "more than 5 minutes ahead")
        XCTAssertEqual(read(code { $0["exp"] = "soon" }), .failure(.badField))
        XCTAssertEqual(read(code { $0["platform"] = "amiga" }), .failure(.badField))
        XCTAssertEqual(read(code { $0["kind"] = "usb" }), .failure(.badField))
        XCTAssertEqual(read(code { $0["name"] = "" }), .failure(.badField))
        XCTAssertEqual(read(code { $0["name"] = String(repeating: "a", count: 65) }), .failure(.badField))
        XCTAssertEqual(read(code { $0["name"] = "tafs\non this PC" }), .failure(.badField), "no control characters")
        XCTAssertEqual(read(code { $0["nonce"] = "AAEC" }), .failure(.badField), "32 bytes")
        XCTAssertEqual(read(code { $0["tenant"] = "t_other" }), .failure(.badField), "a field KIFF doesn't take isn't signed")

        XCTAssertEqual(read(code { $0["approval_pub"] = nil }), .failure(.badKeys), "hello needs its approval key")
        XCTAssertEqual(read(code { $0["kind"] = "passkey" }), .failure(.badKeys), "a passkey computer holds none")
        XCTAssertEqual(read(code { $0["approval_pub"] = Self.pub }), .failure(.badKeys), "the two keys differ")
        XCTAssertEqual(read(code { $0["pub"] = Self.nonce }), .failure(.badKeys), "not a P-256 key")
        XCTAssertEqual(read(code { $0["approval_pub"] = 7 }), .failure(.badKeys))
    }

    /// `key_store` (desktop-client.md §5.1): only "software", only on a passkey computer, and sent on as read.
    func testASoftwareKeyedComputerIsReadAsOne() throws {
        let software = try read(code { $0["kind"] = "passkey"; $0["approval_pub"] = nil; $0["key_store"] = "software" }).get()
        XCTAssertTrue(software.softwareKey)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: software.body) as? [String: String])?["key_store"], "software")
        XCTAssertEqual(software.checkCode, "F2ULUM", "the check code covers the keys only")
        XCTAssertFalse(try read(code()).get().softwareKey)
        XCTAssertNil((try JSONSerialization.jsonObject(with: try read(code()).get().body) as? [String: String])?["key_store"], "omitted otherwise")

        XCTAssertEqual(read(code { $0["key_store"] = "software" }), .failure(.badField), "a hello computer's key is in hardware")
        XCTAssertEqual(read(code { $0["kind"] = "passkey"; $0["approval_pub"] = nil; $0["key_store"] = "hardware" }), .failure(.badField))
        XCTAssertEqual(read(code { $0["kind"] = "passkey"; $0["approval_pub"] = nil; $0["key_store"] = true }), .failure(.badField))
    }

    func testTheEncodings() {
        XCTAssertEqual(PairingRequest.base32(Data("foobar".utf8)), "MZXW6YTBOI", "RFC 4648's own example, unpadded")
        XCTAssertEqual(PairingRequest.base64url("-_8"), Data([0xFB, 0xFF]))
        XCTAssertEqual(PairingRequest.base64url("-_8="), Data([0xFB, 0xFF]), "padding is taken too")
        XCTAssertNil(PairingRequest.base64url("a+b/"), "base64url only")
        XCTAssertNotNil(PairingRequest.time("2026-11-14T19:04:00.250Z"))
    }
}
