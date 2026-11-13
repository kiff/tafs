import XCTest
@testable import Tafs

/// One rule for the app's haptics, as on Android: a light tap on every button press, done once
/// in the shared button styles; a success tick when an approval or a send goes through; a warning
/// tick when something is refused or fails; a step tick on stops. Settings' Haptics turns it off.
@MainActor
final class HapticsTests: XCTestCase {
    private var played: [String] = []

    override func setUp() {
        super.setUp()
        played = []
        UserDefaults.standard.removeObject(forKey: Haptics.key)
        Haptics.played = { [weak self] in self?.played.append($0) }
    }

    override func tearDown() {
        Haptics.played = nil
        UserDefaults.standard.removeObject(forKey: Haptics.key)
        super.tearDown()
    }

    /// A press and the action it starts tap once: a second tap this soon is the same one.
    func testAPressTapsOnce() async throws {
        try await Task.sleep(nanoseconds: 200_000_000)
        Haptics.tap()
        Haptics.tap()
        XCTAssertEqual(played, ["tap"])
        try await Task.sleep(nanoseconds: 200_000_000)
        Haptics.tap()
        XCTAssertEqual(played, ["tap", "tap"], "a later press taps again")
    }

    /// Settings' switch turns all of it off on this device.
    func testTheSwitchTurnsEverythingOff() async throws {
        try await Task.sleep(nanoseconds: 200_000_000)
        UserDefaults.standard.set(false, forKey: Haptics.key)
        Haptics.tap(); Haptics.success(); Haptics.warning(); Haptics.step()
        XCTAssertEqual(played, [])
        UserDefaults.standard.set(true, forKey: Haptics.key)
        Haptics.tap(); Haptics.success(); Haptics.warning(); Haptics.step()
        XCTAssertEqual(played, ["tap", "success", "warning", "step"])
    }

    /// Once, in the shared style every app button goes through: Primary, Outline and plain all
    /// are `DriveButton`s, so every new button taps too. A failure said to the person warns.
    func testItsDoneOnceInTheSharedStyleAndOnFailures() async throws {
        // Past the last test's tap, so this press is a new one.
        try await Task.sleep(nanoseconds: 200_000_000)
        let tafs = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        func source(_ path: String) throws -> String { try String(contentsOf: tafs.appendingPathComponent(path), encoding: .utf8) }
        XCTAssertTrue(try source("Drive/DriveTargets.swift").contains("action: { Haptics.tap(); configuration.trigger() }"))
        let components = try source("Design/Components.swift")
        XCTAssertTrue(components.contains("DriveButton(inner: PrimaryLook())"))
        XCTAssertTrue(components.contains("DriveButton(inner: OutlineLook(color: color))"))
        XCTAssertTrue(try source("Drive/DriveTargets.swift").contains("DriveButton(inner: PlainInner())"))

        let model = AppModel(signer: FaceIDSigner())
        Haptics.tap()
        model.lastError = "It didn't go through."
        XCTAssertEqual(played, ["tap", "warning"], "after the person's press")
        model.lastError = "It didn't go through."
        XCTAssertEqual(played, ["tap", "warning"], "the same words again aren't a new failure")
    }

    /// A failure nobody acted for (a background read) never buzzes: a warning plays only within
    /// `warnsWithin` of the person's own press.
    func testABackgroundFailureDoesntWarn() throws {
        XCTAssertEqual(Haptics.warnsWithin, 15)
        let source = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tafs/Design/Scaled.swift"), encoding: .utf8)
        XCTAssertTrue(source.contains("guard enabled, Date().timeIntervalSince(lastTap) < warnsWithin else { return }"))
    }
}
