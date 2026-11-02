import CryptoKit
import XCTest
@testable import Tafs

/// KIFF's words for a connection's profile, checked against KIFF's golden vectors byte for
/// byte, and the approval that signs both digests. The files in `ProfileVectors/` are KIFF's
/// `kiff.profile.v1` / `kiff.words.v1` vectors, copied as they are: each profile, and KIFF's
/// answer for it.
@MainActor
final class ProfileWordsTests: XCTestCase {
    static let vectors = ["gmail-drafts", "holded-invoices", "notion-pages", "slack-messages", "stripe-refunds"]

    private func vector(_ name: String) throws -> (profile: JCS.Value, expected: Data) {
        let bundle = Bundle(for: Self.self)
        let profile = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: name, withExtension: "json"), name))
        let expected = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: name + ".expected", withExtension: "json"), name))
        return (try JCS.parse(profile), expected)
    }

    /// Each vector: the profile's digest is KIFF's, its words check, in order, verbatim.
    func testTheGoldenVectors() throws {
        for name in Self.vectors {
            let (profile, expected) = try vector(name)
            let answer = try JCS.parse(expected)
            XCTAssertEqual(try ProfileWords.digest(profile), answer["profile_sha256"]?.string, name)
            let words = try ProfileWords.checked(expected, profile: profile)
            XCTAssertEqual(words.wordsSHA256, answer["words_sha256"]?.string, name)
            XCTAssertEqual(words.lines.map(\.text), answer["words"]?["lines"]?.array?.compactMap { $0["text"]?.string }, name)
            XCTAssertFalse(words.lines.isEmpty, name)
            XCTAssertEqual(words.lines.map(\.op), answer["words"]?["lines"]?.array?.map { $0["op"]?.string }, "\(name): a line about the whole connection has no op")
        }
    }

    /// Words for another profile, or words changed after KIFF rendered them, are refused.
    func testWordsForAnotherProfileOrChangedAreRefused() throws {
        let (stripe, stripeWords) = try vector("stripe-refunds")
        let (notion, _) = try vector("notion-pages")
        XCTAssertThrowsError(try ProfileWords.checked(stripeWords, profile: notion)) {
            XCTAssertEqual($0 as? ProfileWords.Failure, .otherProfile)
        }
        let changed = Data(String(decoding: stripeWords, as: UTF8.self)
            .replacingOccurrences(of: "Refund a payment: moves money", with: "Refund a payment: reads only").utf8)
        XCTAssertThrowsError(try ProfileWords.checked(changed, profile: stripe)) {
            XCTAssertEqual($0 as? ProfileWords.Failure, .wordsChanged)
        }
        let otherVersion = Data(String(decoding: stripeWords, as: UTF8.self).replacingOccurrences(of: "kiff.words.v1", with: "kiff.words.v2").utf8)
        XCTAssertThrowsError(try ProfileWords.checked(otherVersion, profile: stripe)) {
            XCTAssertEqual($0 as? ProfileWords.Failure, .notWords)
        }
    }

    /// The whole flow: the grant's profile as planque sent it, KIFF's words read with the
    /// person's session for exactly that profile, and the approve body carrying both digests.
    /// Nothing is approved before the words are read.
    func testApprovingSignsBothDigests() async throws {
        let (profile, expected) = try vector("stripe-refunds")
        let profileJSON = String(decoding: try JCS.canonical(profile), as: UTF8.self)
        let request = #"{"agent":"refunds","grant":"g_09","server":"https://mcp.stripe.com","tenant":"t_01","tools":[],"v":"planque.grant.v1","profile":\#(profileJSON)}"#
        let digest = SHA256.hash(data: try JCS.canonical(Data(request.utf8))).map { String(format: "%02x", $0) }.joined()

        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session)
        planque.tenant = "t_01"
        planque.signer = { _ in "sig" }
        planque.deviceID = "dev_phone01"
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[{"id":"g_09","status":"pending","text":"t","digest":"\#(digest)","request":\#(request),"events":[]}]}"#)
        StubProtocol.responses["POST /v1/profiles/words"] = (200, String(decoding: expected, as: UTF8.self))
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_09/approve"] = (200, #"{"status":"approved","sign_in_url":"https://access.stripe.com/a"}"#)

        let grants = try await planque.grants()
        let grant = try XCTUnwrap(grants.first)
        XCTAssertTrue(grant.digestMatches)
        XCTAssertEqual(grant.profile, profile)

        do { _ = try await planque.approve(grant); XCTFail("approved before the words were read") } catch {
            XCTAssertEqual(error as? PlanqueClient.PlanqueError, .wordsUnread)
        }
        XCTAssertTrue(StubProtocol.sent.filter { $0.url?.path.hasSuffix("/approve") ?? false }.isEmpty)

        let words = try await kiff.profileWords(for: profile)
        let read = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/profiles/words" })
        XCTAssertEqual(read.httpBody, try JCS.canonical(profile), "the profile goes to KIFF exactly as shown")
        XCTAssertEqual(read.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_x", "with the person's own session")

        _ = try await planque.approve(grant, words: words)
        let sent = try XCTUnwrap(StubProtocol.sent.last { $0.url?.path == "/v1/accounts/t_01/grants/g_09/approve" })
        let body = try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(sent.httpBody)) as? [String: Any])
        let signed = try XCTUnwrap(body["kiff_profile"] as? [String: String])
        let answer = try JCS.parse(expected)
        XCTAssertEqual(body["digest"] as? String, digest)
        XCTAssertEqual(signed["profile_sha256"], answer["profile_sha256"]?.string)
        XCTAssertEqual(signed["words_sha256"], answer["words_sha256"]?.string)
    }

    /// Lines KIFF can't vouch for are the ones shown in amber.
    func testCautionKinds() {
        for kind in ["missing", "probation", "unpinned"] {
            XCTAssertTrue(ProfileWords.Line(op: nil, kind: kind, text: "t").caution, kind)
        }
        for kind in ["does", "outside", "scope", "counts", "checks", "no_effect"] {
            XCTAssertFalse(ProfileWords.Line(op: "x", kind: kind, text: "t").caution, kind)
        }
    }
}
