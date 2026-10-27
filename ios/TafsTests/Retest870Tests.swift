import XCTest
@testable import Tafs

/// The owner's 870 test, as a viewer on another account (kiff/tafs#294): the app offers only
/// what the person may do, and says which account they're in.
final class Retest870Tests: XCTestCase {
    /// A viewer isn't offered Approve or Refuse (KIFF answered "role_forbidden"), nor Delete
    /// account. An account from before roles lists no capabilities, and keeps both.
    func testOnlyWhatTheRoleAllowsIsOffered() {
        let viewer = ["read", "personal"]
        XCTAssertFalse(AppModel.allows(viewer, "holds.answer"))
        XCTAssertFalse(AppModel.allows(viewer, "account.delete"))
        XCTAssertTrue(AppModel.allows(["read", "holds.answer"], "holds.answer"))
        XCTAssertTrue(AppModel.allows(nil, "holds.answer"), "an account from before roles")
        XCTAssertTrue(AppModel.allows(nil, "account.delete"))
    }

    /// KIFF's refusal is said in words, never as its code.
    func testARoleRefusalIsSaidInWords() throws {
        let words = try XCTUnwrap(AppModel.refusalWords("role_forbidden"))
        XCTAssertFalse(words.contains("role_forbidden"))
        XCTAssertTrue(words.contains("approves"), words)
    }

    /// Outside KIFF there's no account to name, and the Card keeps who issued it.
    @MainActor
    func testTheSampleNamesNoAccount() async throws {
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        XCTAssertNil(model.accountLabel)
        let taf = try XCTUnwrap(model.tafs.first)
        XCTAssertEqual(model.issuer(of: taf), taf.card.issuedBy)
    }
}
