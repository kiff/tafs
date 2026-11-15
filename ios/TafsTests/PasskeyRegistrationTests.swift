import XCTest
@testable import Tafs

/// Registering a passkey at kiff.dev, checked against the vector both apps read
/// (`contracts/vectors/passkey-register.json`), so the iPhone and iPad and Android can't drift.
final class PasskeyRegistrationTests: XCTestCase {
    private func vector() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("contracts/vectors/passkey-register.json")
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private func hex(_ s: String) -> Data {
        var data = Data()
        var index = s.startIndex
        while index < s.endIndex {
            let next = s.index(index, offsetBy: 2)
            data.append(UInt8(s[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    /// The challenge is sha256 of `kiff.passkey.register.v1`, the account, the person and the moment.
    func testTheChallengeIsTheVectors() throws {
        let c = try XCTUnwrap(try vector()["challenge"] as? [String: String])
        let tenant = try XCTUnwrap(c["tenant"]), subject = try XCTUnwrap(c["subject"]), issued = try XCTUnwrap(c["issued_at"])
        XCTAssertEqual(PasskeyRegistration.message(tenant: tenant, subject: subject, issuedAt: issued), c["message"])
        let challenge = PasskeyRegistration.challenge(tenant: tenant, subject: subject, issuedAt: issued)
        XCTAssertEqual(challenge, hex(try XCTUnwrap(c["message_sha256_hex"])))
        XCTAssertEqual(PasskeyRegistration.base64url(challenge), c["challenge_base64url"], "as clientDataJSON carries it")
    }

    /// What the platform is asked for: kiff.dev, the subject's bytes as the user, ES256 only.
    func testTheCreateRequestIsTheVectors() throws {
        let create = try XCTUnwrap(try vector()["create"] as? [String: Any])
        let subject = try XCTUnwrap((try vector()["challenge"] as? [String: String])?["subject"])
        XCTAssertEqual(PasskeyRegistration.relyingParty, create["rp_id"] as? String)
        XCTAssertEqual(PasskeyRegistration.base64url(PasskeyRegistration.userID(subject: subject)), create["user_id_base64url"] as? String)
        XCTAssertEqual(PasskeyRegistration.algorithm, create["algorithm"] as? Int)
    }

    /// The body is the creation's answer as the platform gave it, base64url, and never the old fields.
    func testTheBodyIsTheVectors() throws {
        let b = try XCTUnwrap(try vector()["body"] as? [String: Any])
        let body = try XCTUnwrap(PasskeyRegistration.body(name: try XCTUnwrap(b["name"] as? String),
                                                          issuedAt: try XCTUnwrap(b["issued_at"] as? String),
                                                          clientDataJSON: hex(try XCTUnwrap(b["client_data_json_hex"] as? String)),
                                                          attestationObject: hex(try XCTUnwrap(b["attestation_object_hex"] as? String))))
        let sent = try XCTUnwrap(try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: String])
        XCTAssertEqual(sent, try XCTUnwrap(b["expected"] as? [String: String]))
        for field in try XCTUnwrap(b["never_sent"] as? [String]) {
            XCTAssertNil(sent[field], "\(field): KIFF refuses a body with the old fields too")
        }
    }

    /// A name is 1 to 64 characters once trimmed, counted as KIFF counts them.
    func testANameIsOneToSixtyFourCharacters() throws {
        let n = try XCTUnwrap(try vector()["name"] as? [String: Any])
        XCTAssertEqual(PasskeyRegistration.longestName, n["longest"] as? Int)
        for pair in try XCTUnwrap(n["accepted"] as? [[String]]) {
            XCTAssertEqual(PasskeyRegistration.name(pair[0]), pair[1], pair[0])
        }
        for refused in try XCTUnwrap(n["refused"] as? [String]) {
            XCTAssertNil(PasskeyRegistration.name(refused), refused)
        }
    }

    /// `issued_at` is RFC 3339 in UTC, to the second, and KIFF takes it within ten minutes of its clock.
    func testIssuedAtIsRFC3339InUTC() throws {
        let i = try XCTUnwrap(try vector()["issued_at"] as? [String: Any])
        let date = Date(timeIntervalSince1970: TimeInterval(try XCTUnwrap(i["date_unix"] as? Int)))
        XCTAssertEqual(PasskeyRegistration.issuedAt(date), i["rfc3339"] as? String)
        XCTAssertEqual(PasskeyRegistration.issuedWithin, TimeInterval(try XCTUnwrap(i["within_seconds"] as? Int)))
    }
}
