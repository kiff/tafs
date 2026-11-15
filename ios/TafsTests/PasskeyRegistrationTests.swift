import XCTest
@testable import Tafs

/// Registering a passkey at kiff.dev, checked against the vector both apps read
/// (`vectors/passkey-registration.json`), so the iPhone and iPad and Android can't drift.
final class PasskeyRegistrationTests: XCTestCase {
    private func vector() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("vectors/passkey-registration.json")
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
        let v = try vector()
        let inputs = try XCTUnwrap(v["inputs"] as? [String: String])
        let expected = try XCTUnwrap(v["expected"] as? [String: Any])
        let tenant = try XCTUnwrap(inputs["tenant"]), subject = try XCTUnwrap(inputs["subject"]), issued = try XCTUnwrap(inputs["issued_at"])
        XCTAssertEqual(PasskeyRegistration.message(tenant: tenant, subject: subject, issuedAt: issued), expected["message"] as? String)
        let challenge = PasskeyRegistration.challenge(tenant: tenant, subject: subject, issuedAt: issued)
        XCTAssertEqual(challenge, hex(try XCTUnwrap(expected["challenge_sha256_hex"] as? String)))
        XCTAssertEqual(PasskeyRegistration.base64url(challenge), expected["challenge"] as? String, "as clientDataJSON carries it")
    }

    /// What the platform is asked for: kiff.dev, the subject's UTF-8 bytes as the user, ES256 only.
    func testTheCreateRequestIsTheVectors() throws {
        let v = try vector()
        let inputs = try XCTUnwrap(v["inputs"] as? [String: String])
        let create = try XCTUnwrap((v["expected"] as? [String: Any])?["create"] as? [String: Any])
        XCTAssertEqual(PasskeyRegistration.relyingParty, create["rp_id"] as? String)
        XCTAssertEqual(PasskeyRegistration.base64url(PasskeyRegistration.userID(subject: try XCTUnwrap(inputs["subject"]))), create["user_id"] as? String)
        XCTAssertEqual(inputs["email"], create["user_name"] as? String, "the person's email names the passkey's user")
        XCTAssertEqual(PasskeyRegistration.algorithm, create["algorithm"] as? Int)
    }

    /// The body is the creation's answer as the platform gave it, base64url, and never the old fields.
    func testTheBodyIsTheVectors() throws {
        let v = try vector()
        let inputs = try XCTUnwrap(v["inputs"] as? [String: String])
        let response = try XCTUnwrap(v["creation_response"] as? [String: String])
        let body = try XCTUnwrap(PasskeyRegistration.body(name: try XCTUnwrap(inputs["name"]), issuedAt: try XCTUnwrap(inputs["issued_at"]),
                                                          clientDataJSON: hex(try XCTUnwrap(response["client_data_json_hex"])),
                                                          attestationObject: hex(try XCTUnwrap(response["attestation_object_hex"]))))
        let sent = try XCTUnwrap(try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: String])
        XCTAssertEqual(sent, try XCTUnwrap(v["register_body"] as? [String: String]))
        for field in try XCTUnwrap(v["never_in_body"] as? [String]) {
            XCTAssertNil(sent[field], "\(field): KIFF refuses a body with the old fields too")
        }
    }

    /// A name is 1 to 64 characters once trimmed, counted as KIFF counts them, with no control character inside.
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
