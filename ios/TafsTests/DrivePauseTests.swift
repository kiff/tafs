import SwiftUI
import UIKit
import XCTest
@testable import Tafs

/// A touch pauses the drive, and only a clear takeover ends it (docs/drive.md, "When the person touches
/// the app"): the iPhone's side, against DriveTests' screen, with tafs cloud stubbed.
@MainActor
final class DrivePauseTests: XCTestCase {
    private var window: UIWindow?
    private var drive: DriveSession { DriveSession.shared }
    private var targets: DriveTargets { DriveTargets.shared }
    private var id = ""
    private var stepResult: TafsCloud.DriveResult?

    override func setUp() async throws {
        try await super.setUp()
        StubProtocol.responses = [
            "POST /v1/coach/drive/pause": (200, "{}"),
            "POST /v1/coach/drive/resume": (200, "{}"),
            "POST /v1/coach/drive/stop": (200, "{}"),
        ]
        StubProtocol.dropped = [:]
        StubProtocol.sent = []
    }

    override func tearDown() async throws {
        drive.stop("stopped")
        // The caption's last words, then the overlay goes.
        await settle(2.8)
        window?.isHidden = true
        window = nil
        try await super.tearDown()
    }

    private func settle(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    private func show(_ probe: DriveTests.Probe) async throws {
        targets.reset()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let w = UIWindow(windowScene: scene)
        w.windowLevel = .normal + 1
        w.frame = scene.coordinateSpace.bounds
        w.rootViewController = UIHostingController(rootView: DriveTests.Screen(probe: probe))
        w.makeKeyAndVisible()
        window = w
        await settle(1.2)
    }

    /// A drive of its own for each test, its stream not followed: the test hands it its requests.
    private func begin() {
        id = "dr_" + String(UUID().uuidString.prefix(8))
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        drive.begin(TafsCloud.Drive(id: id, session: "s1", task: "Run Refunds"), cloud: cloud, follows: false)
    }

    private func tap(_ element: String) -> TafsCloud.DriveRequest {
        TafsCloud.DriveRequest(id: "r", tool: "app_act", action: "tap", line: "Tapping", element: element)
    }

    private func sent(_ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path == path }
    }

    private func middle(_ element: String) throws -> CGPoint {
        let frame = try XCTUnwrap(targets.shown(id: element)).frame
        return CGPoint(x: frame.midX, y: frame.midY)
    }

    /// The person's tap, as the watch on the window reports it.
    private func personTaps(_ p: CGPoint) {
        drive.personBegan(at: p)
        drive.personEnded(at: p)
    }

    // MARK: The pause

    func testATouchPausesAndEveryRequestIsRefusedUntilContinue() async throws {
        let probe = DriveTests.Probe()
        try await show(probe)
        begin()
        XCTAssertFalse(drive.paused)
        // On empty space: any touch pauses.
        personTaps(CGPoint(x: 4, y: 300))
        XCTAssertTrue(drive.paused)
        XCTAssertTrue(drive.isOn, "a touch pauses the drive, it doesn't end it")

        let refused = await drive.answer(tap("t.run"))
        XCTAssertEqual(refused.outcome, "refused")
        XCTAssertEqual(refused.line, "Paused")
        XCTAssertNil(refused.image, "the person is using the app: no screen goes with a refusal")
        XCTAssertEqual(probe.taps, 0)
        let look = await drive.answer(TafsCloud.DriveRequest(id: "l", tool: "app_look"))
        XCTAssertEqual(look.line, "Paused")

        await settle(0.5)
        XCTAssertEqual(sent("/v1/coach/drive/pause").count, 1)
        XCTAssertEqual(sent("/v1/coach/drive/resume").count, 0)

        drive.resume()
        XCTAssertFalse(drive.paused)
        let done = await drive.answer(tap("t.run"))
        XCTAssertEqual(done.line, "Tapped Run now")
        XCTAssertEqual(probe.taps, 1)
        await settle(0.5)
        XCTAssertEqual(sent("/v1/coach/drive/resume").count, 1)
        XCTAssertEqual(sent("/v1/coach/drive/stop").count, 0)
    }

    /// A step whose dot was showing when the person touched dispatched nothing: it answers Paused.
    func testAStepUnderWayStopsBeforeItActs() async throws {
        let probe = DriveTests.Probe()
        try await show(probe)
        begin()
        let request = tap("t.run")
        let step = Task { self.stepResult = await self.drive.run(request) }
        await settle(0.1)
        drive.personBegan(at: CGPoint(x: 4, y: 300))
        await step.value
        let result = try XCTUnwrap(stepResult)
        XCTAssertEqual(result.outcome, "refused")
        XCTAssertEqual(result.line, "Paused")
        XCTAssertEqual(probe.taps, 0)
    }

    /// The app's own pause never waits for tafs cloud: a pause that didn't arrive is posted again.
    func testAPauseThatDidntArriveIsPostedAgain() async throws {
        try await show(DriveTests.Probe())
        StubProtocol.dropped["POST /v1/coach/drive/pause"] = [.networkConnectionLost]
        begin()
        personTaps(CGPoint(x: 4, y: 300))
        let refused = await drive.answer(tap("t.run"))
        XCTAssertEqual(refused.line, "Paused")
        await settle(1.8)
        XCTAssertEqual(sent("/v1/coach/drive/pause").count, 2)
        XCTAssertTrue(drive.paused)
        XCTAssertTrue(drive.isOn)
    }

    /// A drive tafs cloud doesn't know to pause (another person's, one that's gone, or a tafs cloud
    /// without the route) ends as a takeover, as a touch did before pauses.
    func testAPauseTafsCloudDoesntKnowEndsTheDriveAsATakeover() async throws {
        try await show(DriveTests.Probe())
        StubProtocol.responses["POST /v1/coach/drive/pause"] = (404, #"{"error":"not found"}"#)
        begin()
        personTaps(CGPoint(x: 4, y: 300))
        await settle(0.6)
        XCTAssertFalse(drive.isOn)
        XCTAssertEqual(drive.caption, "You took over, so Coach let you carry on")
        let stop = try XCTUnwrap(sent("/v1/coach/drive/stop").first)
        XCTAssertEqual(stop.url?.path, "/v1/coach/drive/stop")
    }

    /// A drive tafs cloud had already ended (the account halted while it was paused) ends here with
    /// tafs cloud's words, on the pause's or the Continue's answer.
    func testAnEndTafsCloudAnswersWithEndsTheDrive() async throws {
        try await show(DriveTests.Probe())
        StubProtocol.responses["POST /v1/coach/drive/resume"] = (200, #"{"resumed":false,"ended":{"why":"halted","words":"The account is halted, so Coach stopped"}}"#)
        begin()
        personTaps(CGPoint(x: 4, y: 300))
        await settle(0.5)
        XCTAssertTrue(drive.isOn)
        drive.resume()
        await settle(0.6)
        XCTAssertFalse(drive.isOn)
        XCTAssertEqual(drive.caption, "The account is halted, so Coach stopped")
        XCTAssertEqual(sent("/v1/coach/drive/stop").count, 0, "tafs cloud ended it: nothing to stop")
    }

    // MARK: A clear takeover

    func testTheThirdTapOnAControlIsATakeover() async throws {
        let probe = DriveTests.Probe()
        try await show(probe)
        begin()
        let run = try middle("t.run")
        // The tap that paused counts as the first.
        personTaps(run)
        XCTAssertEqual(drive.controlTaps, 1)
        personTaps(run)
        XCTAssertTrue(drive.isOn)
        personTaps(run)
        XCTAssertFalse(drive.isOn)
        XCTAssertEqual(drive.caption, "You took over, so Coach let you carry on")
        XCTAssertEqual(DriveLines.ended["owner_took_over"], "You took over, so Coach let you carry on")
        await settle(0.5)
        XCTAssertEqual(sent("/v1/coach/drive/stop").count, 1)
    }

    /// Empty space, a scroll, and a field (which only takes the focus) aren't taps on a control.
    func testOnlyTapsOnControlsCount() async throws {
        try await show(DriveTests.Probe())
        begin()
        let run = try middle("t.run")
        for _ in 0..<4 { personTaps(CGPoint(x: 4, y: 300)) }
        // A finger that went down on a control and moved away scrolled.
        for _ in 0..<4 {
            drive.personBegan(at: run)
            drive.personEnded(at: CGPoint(x: run.x, y: run.y - 80))
        }
        let field = try XCTUnwrap(targets.shown().first { $0.entry.role == .textField })
        for _ in 0..<4 { personTaps(CGPoint(x: field.frame.midX, y: field.frame.midY)) }
        XCTAssertEqual(drive.controlTaps, 0)
        XCTAssertTrue(drive.isOn)
        XCTAssertTrue(drive.paused)
    }

    func testTheCountStartsAgainAtContinue() async throws {
        try await show(DriveTests.Probe())
        begin()
        let run = try middle("t.run")
        personTaps(run)
        personTaps(run)
        drive.resume()
        XCTAssertEqual(drive.controlTaps, 0)
        personTaps(run)
        personTaps(run)
        XCTAssertEqual(drive.controlTaps, 2)
        XCTAssertTrue(drive.isOn)
    }

    /// The person's back (the bar's button, or the edge swipe) leaves a navigation stack shallower once
    /// the touch settles: a takeover. A sheet that closes isn't one.
    func testThePersonsBackIsATakeoverAndAClosedSheetIsNot() async throws {
        let probe = DriveTests.Probe()
        try await show(probe)
        probe.sheet = true
        await settle(1.0)
        begin()
        drive.personBegan(at: CGPoint(x: 4, y: 300))
        probe.sheet = false
        drive.personEnded(at: CGPoint(x: 4, y: 300))
        await settle(1.4)
        XCTAssertTrue(drive.isOn, "closing a sheet only pauses")
        XCTAssertTrue(drive.paused)

        probe.path = [1]
        await settle(1.0)
        drive.personBegan(at: CGPoint(x: 20, y: 60))
        probe.path = []
        drive.personEnded(at: CGPoint(x: 20, y: 60))
        await settle(1.4)
        XCTAssertFalse(drive.isOn)
        XCTAssertEqual(drive.caption, "You took over, so Coach let you carry on")
    }

    // MARK: Continue, and who takes a touch

    /// Continue puts the keyboard away first, and the field keeps its text: nothing the keyboard commits
    /// later reads as the person's typing.
    func testContinuePutsTheKeyboardAwayAndKeepsTheText() async throws {
        let probe = DriveTests.Probe()
        try await show(probe)
        begin()
        let focused = await drive.run(tap("field.limit"))
        XCTAssertEqual(focused.outcome, "done", focused.line)
        let typed = await drive.run(TafsCloud.DriveRequest(id: "t", tool: "app_act", action: "type", line: "Typing", text: "40"))
        XCTAssertEqual(typed.outcome, "done", typed.line)
        XCTAssertNotNil(DriveFields.focused())

        personTaps(CGPoint(x: 4, y: 300))
        drive.resume()
        await settle(0.4)
        XCTAssertNil(DriveFields.focused())
        XCTAssertEqual(probe.text, "40")
        XCTAssertTrue(drive.isOn, "what the keyboard did as it went isn't the person's typing")
        XCTAssertFalse(drive.paused)
    }

    /// Every window of the app is watched while a drive is on, and the overlay takes no touch but on its
    /// own Continue and Stop.
    func testTheAppsWindowsAreWatchedAndTheOverlayTakesOnlyItsButtons() async throws {
        try await show(DriveTests.Probe())
        let window = try XCTUnwrap(window)
        XCTAssertFalse(DriveTouches.watched(window))
        begin()
        XCTAssertTrue(DriveTouches.watched(window))
        let watch = try XCTUnwrap(window.gestureRecognizers?.compactMap { $0 as? DriveTouchWatch }.first)
        XCTAssertFalse(watch.cancelsTouchesInView)
        XCTAssertFalse(watch.delaysTouchesBegan)
        XCTAssertFalse(watch.delaysTouchesEnded)

        await settle(0.6)
        let middle = CGPoint(x: window.bounds.midX, y: window.bounds.midY)
        XCTAssertNil(DriveOverlay.shared.hit(middle), "a touch on the app goes to the app")
        XCTAssertFalse(DriveOverlay.shared.takes(middle))
        let stop = DriveOverlay.shared.stopFrame
        XCTAssertFalse(stop.isNull)
        XCTAssertTrue(DriveOverlay.shared.takes(CGPoint(x: stop.midX, y: stop.midY)))
        XCTAssertTrue(DriveOverlay.shared.continueFrame.isNull, "Continue shows only while paused")

        personTaps(CGPoint(x: 4, y: 300))
        await settle(0.6)
        let goOn = DriveOverlay.shared.continueFrame
        XCTAssertFalse(goOn.isNull)
        XCTAssertTrue(DriveOverlay.shared.takes(CGPoint(x: goOn.midX, y: goOn.midY)))

        drive.stop("stopped")
        XCTAssertFalse(DriveTouches.watched(window))
    }
}
