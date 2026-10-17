import XCTest
@testable import Tafs

/// TestFlight 175 crashed on tapping an approval: a sign-in session that failed to start
/// resumed its continuation twice, once from its completion handler and once from start().
final class VendorSignInTests: XCTestCase {
    func testOnlyTheFirstResultResumes() async throws {
        let url = try await withCheckedThrowingContinuation { (c: CheckedContinuation<URL, Error>) in
            let once = ResumeOnce(c)
            XCTAssertTrue(once.resume(.success(URL(string: "tafs://planque/signed-in?confirm=a")!)))
            XCTAssertFalse(once.resume(.failure(VendorSignIn.SignInError.noCallback)))
        }
        XCTAssertEqual(url.absoluteString, "tafs://planque/signed-in?confirm=a")
    }

    func testAFailureFirstIsTheResult() async {
        do {
            _ = try await withCheckedThrowingContinuation { (c: CheckedContinuation<URL, Error>) in
                let once = ResumeOnce(c)
                once.resume(.failure(VendorSignIn.SignInError.noCallback))
                once.resume(.success(URL(string: "tafs://late")!))
            }
            XCTFail("the late success should be dropped")
        } catch {
            XCTAssertEqual(error as? VendorSignIn.SignInError, .noCallback)
        }
    }
}
