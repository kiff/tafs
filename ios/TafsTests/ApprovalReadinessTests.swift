import LocalAuthentication
import XCTest
@testable import Tafs

/// "Set up approvals on this device" (kiff/tafs#423): each state iOS reports has its own
/// state and words, never one "not set up" for all of them.
final class ApprovalReadinessTests: XCTestCase {
    func testEachLAErrorMapsToItsState() {
        XCTAssertEqual(ApprovalReadiness.from(nil, biometry: .faceID), .ready)
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotEnrolled, biometry: .faceID), .noneEnrolled)
        XCTAssertEqual(ApprovalReadiness.from(.passcodeNotSet, biometry: .touchID), .noPasscode)
        // The same code for no Face ID at all and for Face ID turned off for tafs.
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotAvailable, biometry: .none), .unsupported)
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotAvailable, biometry: .faceID, mac: false), .offForTafs)
        XCTAssertEqual(ApprovalReadiness.from(.biometryLockout, biometry: .faceID), .unavailable(lockedOut: true))
        XCTAssertEqual(ApprovalReadiness.from(.invalidContext, biometry: .touchID), .unavailable(lockedOut: false))
    }

    /// A Mac has no Touch ID switch for one app, so it never sends the person to turn it on
    /// for tafs: Touch ID out of reach (the lid closed, the keyboard away) is "can't be used
    /// right now". The other states are the iPad's.
    func testAMacNeverSaysTouchIDIsOffForTafs() {
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotAvailable, biometry: .touchID, mac: true), .unavailable(lockedOut: false))
        XCTAssertFalse(ApprovalReadiness.from(.biometryNotAvailable, biometry: .touchID, mac: true).opensSettings)
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotAvailable, biometry: .none, mac: true), .unsupported)
        XCTAssertEqual(ApprovalReadiness.from(nil, biometry: .touchID, mac: true), .ready)
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotEnrolled, biometry: .touchID, mac: true), .noneEnrolled)
        XCTAssertEqual(ApprovalReadiness.from(.biometryLockout, biometry: .touchID, mac: true), .unavailable(lockedOut: true))
        // The iPhone and iPad keep theirs.
        XCTAssertEqual(ApprovalReadiness.from(.biometryNotAvailable, biometry: .touchID, mac: false), .offForTafs)
    }

    /// A Mac's Touch ID is set up in System Settings, whatever the device reports.
    func testAMacSaysWhereItsTouchIDIsSetUp() {
        XCTAssertEqual(ApprovalReadiness.path("Touch ID", mac: true), Words.Approval.SettingsPath.macTouchId)
        XCTAssertEqual(ApprovalReadiness.path(nil, mac: true), Words.Approval.SettingsPath.macTouchId)
        XCTAssertEqual(ApprovalReadiness.path("Touch ID", mac: false), Words.Approval.SettingsPath.touchId)
        XCTAssertEqual(ApprovalReadiness.path("Face ID", mac: false), Words.Approval.SettingsPath.faceId)
    }

    func testEachStateHasItsOwnWords() {
        let states: [ApprovalReadiness] = [.ready, .noneEnrolled, .noPasscode, .offForTafs, .unavailable(lockedOut: true), .unavailable(lockedOut: false), .unsupported]
        let words = states.map { $0.words(device: "iPhone", kind: "Face ID") }
        XCTAssertEqual(Set(words).count, states.count, words.joined(separator: "\n"))
        let short = states.dropFirst().map { $0.short(kind: "Face ID") }
        XCTAssertEqual(Set(short).count, short.count, short.joined(separator: "\n"))
        for line in words + short {
            XCTAssertFalse(line.localizedCaseInsensitiveContains("not set up"), line)
        }
        // None enrolled: where to set it up, in words, as Open Settings opens tafs' own.
        // On the Mac build, where these tests also run, it's the Mac's own path.
        let mac = Words.Approval.SettingsPath.macTouchId
        XCTAssertTrue(ApprovalReadiness.noneEnrolled.words(device: "iPhone", kind: "Face ID").contains(ThisDevice.isMac ? mac : "Settings → Face ID & Passcode"))
        XCTAssertTrue(ApprovalReadiness.noneEnrolled.words(device: "iPad", kind: "Touch ID").contains(ThisDevice.isMac ? mac : "Settings → Touch ID & Passcode"))
        XCTAssertTrue(ApprovalReadiness.path(nil, mac: false).contains("Touch ID & Passcode"))
        // Locked out: retry guidance. Unsupported: another device.
        XCTAssertTrue(ApprovalReadiness.unavailable(lockedOut: true).words(device: "iPhone", kind: "Face ID").contains("passcode"))
        XCTAssertTrue(ApprovalReadiness.unsupported.words(device: "iPad", kind: nil).contains("another"))
        // Open Settings only where tafs' own settings help: its Face ID switch.
        XCTAssertEqual(states.filter(\.opensSettings), [.offForTafs])
        // No passcode: a passcode first, then Face ID.
        let noPasscode = ApprovalReadiness.noPasscode.words(device: "iPhone", kind: "Face ID")
        XCTAssertTrue(noPasscode.contains("Set a passcode") && noPasscode.contains("then add Face ID"), noPasscode)
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

    func testTheSimulatorIsReady() throws {
        // The signer lets the Simulator's demo through, so its screens show the approvals.
        // A Mac's tests run on the Mac itself, which answers for its own Touch ID.
        #if !targetEnvironment(simulator)
        throw XCTSkip("Not a Simulator")
        #else
        XCTAssertTrue(ApprovalReadiness.current().isReady)
        #endif
    }

    /// Check again reads readiness once for every view: a version's notice clears and its
    /// Install enables together, with no trip to the background (kiff/tafs#423).
    @MainActor
    func testCheckAgainUnblocksTheNoticeAndTheButtonTogether() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(), kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        defer {
            Keychain.delete(AppModel.tokenAccount)
            Keychain.delete(AppModel.tenantAccount)
            Keychain.delete(AppModel.keyedAccount)
        }
        XCTAssertEqual(model.mode, .kiff)

        var device = ApprovalReadiness.unavailable(lockedOut: true)
        model.readApprovalReadiness = { device }
        model.checkApprovalReadiness()
        XCTAssertEqual(model.approvalReadiness, device)
        XCTAssertTrue(model.approvalBlocked)

        // The lockout clears; the notice's Check again reads it, and the button is free too.
        device = .ready
        model.checkApprovalReadiness()
        XCTAssertTrue(model.approvalReadiness.isReady)
        XCTAssertFalse(model.approvalBlocked)
    }

    /// Sample mode signs nothing for real: it is never blocked.
    @MainActor
    func testSampleModeIsNeverBlocked() async {
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        model.readApprovalReadiness = { .unsupported }
        model.checkApprovalReadiness()
        XCTAssertFalse(model.approvalBlocked)
    }

    /// A Mac's Touch ID prompt says "tafs is trying to …" itself, so it takes the Mac's line, which continues
    /// that sentence; an iPhone or iPad shows the reason alone, as a sentence.
    func testAMacsTouchIDReasonContinuesTheSystemsSentence() {
        XCTAssertEqual(PromptReason.pick(Words.Coach.Prompt.connectTools, mac: Words.Coach.Prompt.connectToolsMac, isMac: true), "connect your apps")
        XCTAssertEqual(PromptReason.pick(Words.Coach.Prompt.connectTools, mac: Words.Coach.Prompt.connectToolsMac, isMac: false), "Connect your apps")
        XCTAssertEqual(Words.Connect.Prompt.connectMac(vendor: "Stripe", agent: "Refunds"), "connect Stripe to Refunds")
        XCTAssertEqual(Words.Settings.Devices.signOutPromptMac(device: "Ana's Mac"), "sign Ana's Mac out of your account")
    }
}
