import XCTest
@testable import Tafs

/// Coach's connect card: the phone builds planque's approve body from what the card showed (the
/// digest, and KIFF's profile words' digests when words were shown) and signs tafs cloud's body only
/// when it's that. Another profile, other words, words left out or a field added are refused before
/// any Face ID, so nothing goes to planque (`SetupTests.testAMessageThatIsntThisCardsIsRefusedBeforeAnyPrompt`
/// walks the refusal through the model). As Android's (tafs#1285).
@MainActor
final class ApproveBodyCheckTests: XCTestCase {
    private let shown = PlanqueClient.ApproveBody.KIFFProfile(profileSHA256: "ab", wordsSHA256: "cd")

    private func ask(_ body: String) throws -> TafsCloud.ApprovalDecision.Ask {
        try KIFFClient.decoder.decode(TafsCloud.ApprovalDecision.Ask.self, from: Data(
            #"{"id":"ask_1","step":"connect","message":"x","reason":"r","planque":{"grant":"g_01","body":\#(body)}}"#.utf8))
    }

    private func expected(_ body: String, shownProfile: PlanqueClient.ApproveBody.KIFFProfile?) throws -> String? {
        AppModel.expectedApprovalMessage(try ask(body), component: try SetupTests.component(#"{"type":"approval","id":"a1","grant":"g_01"}"#),
                                         tenant: "t_01", shownDigest: "d1", shownDraft: nil, shownProfile: shownProfile)
    }

    /// The body the phone builds from what was shown is signed: with words shown, and with none.
    func testTheBodyBuiltFromWhatWasShownIsSigned() throws {
        XCTAssertEqual(try expected(#"{"digest":"d1","kiff_profile":{"profile_sha256":"ab","words_sha256":"cd"}}"#, shownProfile: shown),
                       "tafs.connect.v1|t_01|g_01|d1")
        XCTAssertEqual(try expected(#"{"digest":"d1"}"#, shownProfile: nil), "tafs.connect.v1|t_01|g_01|d1")
        XCTAssertEqual(try expected(#"{"digest":"d1","kiff_profile":null}"#, shownProfile: nil), "tafs.connect.v1|t_01|g_01|d1")
        XCTAssertEqual(AppModel.shownConnectBody("d1", shown), PlanqueClient.ApproveBody(digest: "d1", kiffProfile: shown))
    }

    /// Another profile's digest than the one whose words were shown.
    func testADifferentProfileDigestIsRefused() throws {
        XCTAssertNil(try expected(#"{"digest":"d1","kiff_profile":{"profile_sha256":"ff","words_sha256":"cd"}}"#, shownProfile: shown))
    }

    /// Other words' digest than the words shown.
    func testADifferentWordsDigestIsRefused() throws {
        XCTAssertNil(try expected(#"{"digest":"d1","kiff_profile":{"profile_sha256":"ab","words_sha256":"ff"}}"#, shownProfile: shown))
    }

    /// Words were shown, but tafs cloud's body leaves them out; and words in a body for a card that showed none.
    func testAMissingOrUnshownKIFFProfileIsRefused() throws {
        XCTAssertNil(try expected(#"{"digest":"d1"}"#, shownProfile: shown))
        XCTAssertNil(try expected(#"{"digest":"d1","kiff_profile":{"profile_sha256":"ab","words_sha256":"cd"}}"#, shownProfile: nil))
    }

    /// A field beyond the digest and `kiff_profile`, or beyond `kiff_profile`'s two digests: decoding would
    /// drop it, so it's caught as it's read, and the body is refused.
    func testAnExtraFieldIsRefused() throws {
        XCTAssertTrue(try ask(#"{"digest":"d1","scopes":"all"}"#).planque?.extraFields == true)
        XCTAssertNil(try expected(#"{"digest":"d1","scopes":"all"}"#, shownProfile: nil))
        XCTAssertNil(try expected(#"{"digest":"d1","kiff_profile":{"profile_sha256":"ab","words_sha256":"cd","more":"x"}}"#, shownProfile: shown))
        XCTAssertFalse(try ask(#"{"digest":"d1","kiff_profile":{"profile_sha256":"ab","words_sha256":"cd"}}"#).planque?.extraFields ?? true)
    }
}
