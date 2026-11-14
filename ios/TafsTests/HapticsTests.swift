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
        Haptics.forgetPresses()
        Haptics.played = { [weak self] in self?.played.append($0) }
    }

    override func tearDown() {
        Haptics.played = nil
        UserDefaults.standard.removeObject(forKey: Haptics.key)
        super.tearDown()
    }

    /// A press and the action it starts tap once: a second tap this soon is the same one.
    func testAPressTapsOnce() async throws {
        Haptics.tap()
        Haptics.tap()
        XCTAssertEqual(played, ["tap"])
        try await Task.sleep(nanoseconds: 200_000_000)
        Haptics.tap()
        XCTAssertEqual(played, ["tap", "tap"], "a later press taps again")
    }

    /// Settings' switch turns all of it off on this device.
    func testTheSwitchTurnsEverythingOff() throws {
        UserDefaults.standard.set(false, forKey: Haptics.key)
        Haptics.tap(); Haptics.success(); Haptics.warning(); Haptics.step()
        XCTAssertEqual(played, [])
        UserDefaults.standard.set(true, forKey: Haptics.key)
        Haptics.tap(); Haptics.success(); Haptics.warning(); Haptics.step()
        XCTAssertEqual(played, ["tap", "success", "warning", "step"])
    }

    /// Once, in the shared style every app button goes through: Primary, Outline and plain all
    /// are `DriveButton`s, so every new button taps too. A failure said to the person warns.
    func testItsDoneOnceInTheSharedStyleAndOnFailures() throws {
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
        let model = AppModel(signer: FaceIDSigner())
        model.lastError = "A read in the background failed."
        XCTAssertEqual(played, [], "no press, no buzz")
        let source = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tafs/Design/Scaled.swift"), encoding: .utf8)
        XCTAssertTrue(source.contains("guard enabled, Date().timeIntervalSince(lastTap) < warnsWithin else { return }"))
    }

    /// A selection the person changes (a taf's tabs, the iPad's sidebar) ticks once it truly changed;
    /// the same one chosen again plays nothing.
    func testASelectionTicksOnlyWhenItChanges() {
        Haptics.select(1, 1)
        Haptics.select(Optional<HomeSelection>.none, nil)
        XCTAssertEqual(played, [])
        Haptics.select(0, 2)
        Haptics.select(Optional<HomeSelection>.none, .needsYou)
        XCTAssertEqual(played, ["step", "step"])
    }

    /// Which outcome plays which feel: a send that went through ticks, one kept unsent warns, one not
    /// tried plays nothing past the press; an approval on Coach's card that's done ticks, one that
    /// failed warns, and declining or waiting plays nothing.
    func testEachOutcomeItsFeel() {
        XCTAssertEqual(AppModel.Delivery.sent.feel, .success)
        XCTAssertEqual(AppModel.Delivery.kept.feel, .warning)
        XCTAssertNil(AppModel.Delivery.notTried.feel)

        XCTAssertEqual(AppModel.feel(approving: .done), .success)
        XCTAssertEqual(AppModel.feel(approving: .failed("planque said no")), .warning)
        XCTAssertEqual(AppModel.feel(approving: .failed(nil)), .warning)
        XCTAssertNil(AppModel.feel(approving: .declined))
        XCTAssertNil(AppModel.feel(approving: .working))
        XCTAssertNil(AppModel.feel(approving: .needsYou))

        Haptics.play(nil)
        XCTAssertEqual(played, [])
        Haptics.tap()
        Haptics.play(.success)
        Haptics.play(.warning)
        Haptics.play(.step)
        XCTAssertEqual(played, ["tap", "success", "warning", "step"])
    }

    /// One failure warns once, though it's said in two places (a card's note, then the thread read again).
    func testOneFailureWarnsOnce() {
        Haptics.tap()
        Haptics.warning()
        Haptics.warning()
        XCTAssertEqual(played, ["tap", "warning"])
    }

    /// Where each plays, as on Android 88: a taf's tabs and the iPad's sidebar tick as the person
    /// changes them; Coach's button taps on the bar and in the sidebar, as the bar's tabs do through
    /// `.drivablePlain`; an approval on Coach's card ticks or warns as it ends; a send ticks or warns.
    func testWhereItPlays() throws {
        let tafs = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        func source(_ path: String) throws -> String { try String(contentsOf: tafs.appendingPathComponent(path), encoding: .utf8) }
        XCTAssertTrue(try source("Views/TafDetailView.swift").contains("set: { Haptics.select(section, $0); section = $0 }"))
        XCTAssertTrue(try source("Views/HomeSplitView.swift").contains("set: { chosen = true; Haptics.select(selection, $0); selection = $0 }"))
        XCTAssertTrue(try source("Views/HomeSplitView.swift").contains("Button { Haptics.tap(); model.openCoach() }"))
        let bar = try source("Views/TafsBar.swift")
        XCTAssertTrue(bar.contains("Button { Haptics.tap(); model.openCoach() }"))
        XCTAssertTrue(bar.contains("Button { tab = item.tab } label:") && bar.contains(".buttonStyle(.drivablePlain)"), "each tab taps in DriveButton")
        XCTAssertTrue(try source("Views/SetupChatView.swift").contains("if approvingHere { Haptics.play(AppModel.feel(approving: now)) }"))
        XCTAssertEqual(try source("App/AppModel.swift").components(separatedBy: "Haptics.play(delivery.feel)").count - 1, 2, "a taf's chat and Coach's")
    }
}
