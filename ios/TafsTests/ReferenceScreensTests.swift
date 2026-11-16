import XCTest
@testable import Tafs

/// The iPad reference set's five screens over the sample (docs/ios-app.md, Launch arguments): each a
/// `-screen` case, with what tafs cloud and KIFF would send kept as sample data, read only for a
/// screenshot over the sample.
@MainActor
final class ReferenceScreensTests: XCTestCase {
    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: "screenshots")
        UserDefaults.standard.removeObject(forKey: "screen")
        UserDefaults.standard.removeObject(forKey: "reduceMotion")
        super.tearDown()
    }

    /// `-reduceMotion YES` moves the app as Reduce Motion does, only with `-screenshots YES`, and the UI tests pass
    /// it: an endless animation kept the app from going idle, and XCTest waited a minute before each step.
    func testReduceMotionIsForScreenshotsAndTheUITestsPassIt() throws {
        let defaults = UserDefaults.standard
        defaults.set(true, forKey: "reduceMotion")
        defaults.set(false, forKey: "screenshots")
        XCTAssertFalse(Screenshots.reduceMotion, "only for a screenshot or a UI test")
        defaults.set(true, forKey: "screenshots")
        XCTAssertTrue(Screenshots.reduceMotion)
        XCTAssertTrue(Motion.reduced)
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let uiTests = try String(contentsOf: root.appendingPathComponent("TafsUITests/TafsUITestCase.swift"), encoding: .utf8)
        XCTAssertTrue(uiTests.contains(#"app.launchArguments += ["-reduceMotion", "YES"]"#))
        let app = try String(contentsOf: root.appendingPathComponent("Tafs/App/TafsApp.swift"), encoding: .utf8)
        XCTAssertTrue(app.contains("if Screenshots.reduceMotion { UIView.setAnimationsEnabled(false) }"))
    }

    /// The samples ship in the app and read as tafs cloud's own answers would.
    func testTheSamplesReadAsTafsCloudsAnswers() throws {
        let apps = try XCTUnwrap(SampleScreens.connectApps, "Samples/connect-apps.json is in the app and reads")
        let parts = ConnectRow.split(apps.apps)
        XCTAssertEqual(parts.yours.map(\.displayName), ["Gmail", "Stripe"], "two of the person's own first")
        XCTAssertEqual(parts.discover.count, 4)
        XCTAssertTrue(apps.byKey)

        let gallery = try XCTUnwrap(SampleScreens.gallery, "Samples/gallery.json is in the app and reads")
        XCTAssertEqual(gallery.categories.map(\.id), ["money", "customers", "operations"])
        XCTAssertEqual(gallery.recipes.count, 5)
        XCTAssertEqual(gallery.words.title, "New taf")
        XCTAssertFalse(gallery.words.ownWords.isEmpty, "the row that opens Coach with nothing in it")

        let devices = SampleScreens.devices(now: Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertEqual(devices.count, 3)
        XCTAssertEqual(devices.filter(\.thisDevice).count, 1, "this device, and two others to sign out")
    }

    /// Each `-screen` opens its screen only with `-screenshots YES`.
    func testEachScreenIsAScreenshotsOnly() {
        let defaults = UserDefaults.standard
        defaults.set(false, forKey: "screenshots")
        defaults.set("usage", forKey: "screen")
        XCTAssertNil(Screenshots.sidebarScreen, "only for a screenshot")
        defaults.set(true, forKey: "screenshots")
        XCTAssertEqual(Screenshots.sidebarScreen, .usage)
        defaults.set("connect-apps", forKey: "screen")
        XCTAssertEqual(Screenshots.sidebarScreen, .connectApps)
        defaults.set("coach", forKey: "screen")
        XCTAssertNil(Screenshots.sidebarScreen)
        XCTAssertTrue(Screenshots.coach)
        defaults.set("devices", forKey: "screen")
        XCTAssertFalse(Screenshots.coach)
    }

    /// Over the sample, the samples load only for a screenshot; a real account is untouched.
    func testTheSamplesLoadOnlyForAScreenshotOverTheSample() async {
        let defaults = UserDefaults.standard
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        defaults.set(false, forKey: "screenshots")
        await model.loadConnectApps()
        await model.loadGallery()
        await model.refreshDevices()
        XCTAssertNil(model.connectApps)
        XCTAssertNil(model.gallery)
        XCTAssertTrue(model.devices.isEmpty)

        defaults.set(true, forKey: "screenshots")
        await model.loadConnectApps()
        await model.loadGallery()
        await model.refreshDevices()
        XCTAssertNotNil(model.connectApps)
        XCTAssertNotNil(model.gallery)
        XCTAssertEqual(model.devices.count, 3)
        await model.loadConnectApps("mail")
        XCTAssertNotNil(model.connectApps, "a search over the sample keeps what's shown")
    }

    /// Where each screen opens: the iPad's sidebar item, Devices in Account, + New's gallery, Coach's panel.
    func testWhereEachScreenOpens() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let split = try String(contentsOf: root.appendingPathComponent("Views/HomeSplitView.swift"), encoding: .utf8)
        XCTAssertTrue(split.contains("if let screen = Screenshots.sidebarScreen { opened = screen }"))
        XCTAssertTrue(split.contains("Screenshots.screen == \"new-taf\", showingGallery == nil"))
        let account = try String(contentsOf: root.appendingPathComponent("Views/SignInView.swift"), encoding: .utf8)
        XCTAssertTrue(account.contains(".navigationDestination(isPresented: $openDevices) { DevicesView()"))
        let app = try String(contentsOf: root.appendingPathComponent("App/TafsApp.swift"), encoding: .utf8)
        XCTAssertTrue(app.contains("if coachAvailable || Screenshots.coach {"))
    }

    /// The second pair: one app's connections by taf, and Coach mid-conversation.
    func testOneAppsConnectionsAndCoachMidConversation() async throws {
        let grants = try XCTUnwrap(SampleScreens.grants, "Samples/grants.json is in the app and reads")
        XCTAssertEqual(grants.count, 3)
        XCTAssertTrue(grants.allSatisfy(\.rowFromPlanque), "each with planque's health and actions")
        let thread = try XCTUnwrap(SampleScreens.coachThread, "Samples/coach-chat.json reads as tafs cloud's conversation")
        XCTAssertEqual(thread.messages.count, 4)

        let defaults = UserDefaults.standard
        defaults.set(true, forKey: "screenshots")
        defaults.set("connect-app", forKey: "screen")
        XCTAssertEqual(Screenshots.sidebarScreen, .connectApps)
        defaults.set("coach-chat", forKey: "screen")
        XCTAssertTrue(Screenshots.coach)

        defaults.set("connect-app", forKey: "screen")
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        await model.loadConnectApps()
        let stripe = try XCTUnwrap(model.connectApps?.apps.first { $0.entry == "stripe@3" })
        XCTAssertEqual(model.connectionGroups(of: stripe).tafs.map(\.taf).sorted(),
                       [TafTemplates.chargebacks.name, TafTemplates.refunds.name].sorted(), "Stripe's connections, each under its taf")

        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let connect = try String(contentsOf: root.appendingPathComponent("Views/ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertTrue(connect.contains(".navigationDestination(isPresented: $openingApp) {"))
    }

    /// Coach mid-conversation over the sample: the sample's thread, in Coach open.
    func testCoachMidConversationOpensOnTheSamplesThread() async throws {
        UserDefaults.standard.set(true, forKey: "screenshots")
        UserDefaults.standard.set("coach-chat", forKey: "screen")
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        XCTAssertTrue(model.coachOpen)
        XCTAssertEqual(model.setupThread.messages.count, 4)
        // The sample starts no Coach, so the chat draws its thread for this screen alone, not the empty intro
        // (the reference run drew Coach empty, as for `coach`).
        XCTAssertFalse(model.setupStarted)
        XCTAssertTrue(Screenshots.coachChat)
        UserDefaults.standard.set("coach", forKey: "screen")
        XCTAssertFalse(Screenshots.coachChat, "`coach` stays empty")
        UserDefaults.standard.set(false, forKey: "screenshots")
        UserDefaults.standard.set("coach-chat", forKey: "screen")
        XCTAssertFalse(Screenshots.coachChat, "only for a screenshot")
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let chat = try String(contentsOf: root.appendingPathComponent("Views/SetupChatView.swift"), encoding: .utf8)
        XCTAssertTrue(chat.contains("if !model.setupStarted && !Screenshots.coachChat {"))
    }
}
