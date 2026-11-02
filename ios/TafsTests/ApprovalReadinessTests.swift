import LocalAuthentication
import XCTest
@testable import Tafs

/// "Set up approvals on this device" (kiff/tafs#423): each state iOS reports has its own
/// state and words, never one "not set up" for all of them.
final class ApprovalReadinessTests: XCTestCase {
    func testEachLAErrorMapsToItsState() {
        XCTAssertEqual(ApprovalReadiness.from(nil, biometry: .faceID), .ready)
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotEnrolled, biometry: .faceID), .noneEnrolled)
        XCTAssertEqual(ApprovalReadiness.from(.passcodeNotSet, biometry: .touchID), .noneEnrolled)
        // The same code for no Face ID at all and for Face ID turned off for tafs.
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotAvailable, biometry: .none), .unsupported)
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotAvailable, biometry: .faceID), .offForTafs)
        XCTAssertEqual(ApprovalReadiness.from(.biometryLockout, biometry: .faceID), .unavailable(lockedOut: true))
        XCTAssertEqual(ApprovalReadiness.from(.invalidContext, biometry: .touchID), .unavailable(lockedOut: false))
    }

    func testEachStateHasItsOwnWords() {
        let states: [ApprovalReadiness] = [.ready, .noneEnrolled, .offForTafs, .unavailable(lockedOut: true), .unavailable(lockedOut: false), .unsupported]
        let words = states.map { $0.words(device: "iPhone", kind: "Face ID") }
        XCTAssertEqual(Set(words).count, states.count, words.joined(separator: "\n"))
        let short = states.dropFirst().map { $0.short(kind: "Face ID") }
        XCTAssertEqual(Set(short).count, short.count, short.joined(separator: "\n"))
        for line in words + short {
            XCTAssertFalse(line.localizedCaseInsensitiveContains("not set up"), line)
        }
        // None enrolled: where to set it up, in words, as Open Settings opens tafs' own.
        XCTAssertTrue(ApprovalReadiness.noneEnrolled.words(device: "iPhone", kind: "Face ID").contains("Settings → Face ID & Passcode"))
        XCTAssertTrue(ApprovalReadiness.noneEnrolled.words(device: "iPad", kind: "Touch ID").contains("Settings → Touch ID & Passcode"))
        XCTAssertTrue(ApprovalReadiness.path(nil).contains("Touch ID & Passcode"))
        // Locked out: retry guidance. Unsupported: another device.
        XCTAssertTrue(ApprovalReadiness.unavailable(lockedOut: true).words(device: "iPhone", kind: "Face ID").contains("passcode"))
        XCTAssertTrue(ApprovalReadiness.unsupported.words(device: "iPad", kind: nil).contains("another"))
        // Open Settings only where tafs' settings help.
        XCTAssertEqual(states.filter(\.opensSettings), [.noneEnrolled, .offForTafs])
    }

    func testAnApprovalSaysCancelledOrNotRecognizedAndKeepsTheRequest() {
        let cancelled = ApprovalReadiness.notApproved(LAError(.userCancel), kind: "Face ID")
        XCTAssertTrue(cancelled.hasPrefix("Cancelled"), cancelled)
        XCTAssertTrue(cancelled.contains("The request is kept"), cancelled)
        let failed = ApprovalReadiness.notApproved(LAError(.authenticationFailed), kind: "Face ID")
        XCTAssertTrue(failed.contains("didn't recognise you"), failed)
        XCTAssertTrue(failed.contains("The request is kept"), failed)
        XCTAssertNotNil(ApprovalReadiness.cancelled(LAError(.systemCancel)))
        XCTAssertNil(ApprovalReadiness.cancelled(LAError(.authenticationFailed)))
        XCTAssertNil(ApprovalReadiness.cancelled(NSError(domain: "x", code: 1)))
    }

    func testTheSimulatorIsReady() {
        // The signer lets the Simulator's demo through, so its screens show the approvals.
        XCTAssertTrue(ApprovalReadiness.current().isReady)
    }
}
