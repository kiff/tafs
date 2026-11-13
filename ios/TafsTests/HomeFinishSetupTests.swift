import XCTest
@testable import Tafs

/// Another agent whose setup stopped partway (docs/tafs.md): Coach drafted it and its Card is
/// signed, but no taf was made. Home shows it by the name tafs cloud sends, with tafs cloud's
/// words in place of what it's doing, and a tap opens Coach's chat where it stopped.
@MainActor
final class HomeFinishSetupTests: XCTestCase {
    private static let coach = #"{"id":"taf-setup","name":"Setup","job":"setup","kiff_agent":"setup","status":"active","next_run":null,"last_run":null}"#
    private static let finish = #","finish":{"taf":"taf-setup","session":"s7","words":"Finish setting up"}"#

    /// An entry of the home list, with `extra` before its closing brace.
    private static func other(_ extra: String = finish) -> String {
        let entry = HomeJSON.entry(agent: "invoicing-day-test-x", name: "Invoicing day test", id: nil)
        return String(entry.dropLast()) + extra + "}"
    }

    private func model(others: [String], settingUp: String? = nil, capabilities: String = #"["members.manage","cards.manage"]"#) async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: FaceIDSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        let home = HomeJSON.list([], others: others)
        StubProtocol.responses["GET /v1/home"] = (200, settingUp.map { String(home.dropLast()) + #","setting_up":\#($0)}"# } ?? home)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t_01","tenant_slug":"acme","capabilities":\#(capabilities)}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":\#(Self.coach)}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation/s7"] = (200, """
        {"messages":[{"at":"2026-11-13T09:00:00.000Z","from":"owner","kind":"message","text":"Invoicing day test","session":"s7"}],"components":[]}
        """)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        await model.loadSetup()
        return model
    }

    /// The row reads tafs cloud's name and words, and keeps the chat; a finish with no chat or no
    /// words is none, and one that isn't an object never fails the list.
    func testTheRowReadsTafsCloudsNameAndWords() throws {
        func read(_ extra: String) throws -> TafsCloud.HomeTaf {
            try JSONDecoder().decode(TafsCloud.HomeTaf.self, from: Data(Self.other(extra).utf8))
        }
        let taf = KIFFMapping.taf(try read(Self.finish), id: UUID(), owner: "You")
        XCTAssertEqual(taf.name, "Invoicing day test", "by its name, never the agent's id")
        XCTAssertEqual(taf.now, "Finish setting up")
        XCTAssertEqual(taf.finish?.session, "s7")
        let noChat = KIFFMapping.taf(try read(#","finish":{"taf":"taf-setup","session":"","words":"Finish setting up"}"#), id: UUID(), owner: "You")
        XCTAssertNil(noChat.finish)
        XCTAssertEqual(noChat.now, Words.Taf.Now.actingUnderKiff, "as before")
        XCTAssertNil(KIFFMapping.taf(try read(#","finish":"soon""#), id: UUID(), owner: "You").finish, "not one, and the list still reads")
        XCTAssertNil(KIFFMapping.taf(try read(""), id: UUID(), owner: "You").finish)
    }

    /// A tap opens Coach on the chat where it stopped: the app reads that chat, which is what
    /// makes the taf at tafs cloud.
    func testFinishSettingUpOpensCoachWhereItStopped() async throws {
        let model = await model(others: [Self.other()])
        let taf = try XCTUnwrap(model.otherAgents.first)
        XCTAssertTrue(model.canFinishSetup(taf.finish))
        StubProtocol.sent = []
        await model.finishSetup(try XCTUnwrap(taf.finish))
        XCTAssertTrue(model.coachOpen)
        XCTAssertEqual(model.setupThread.messages.map(\.text), ["Invoicing day test"])
        XCTAssertTrue(StubProtocol.sent.contains { $0.httpMethod == "GET" && $0.url?.path == "/v1/tafs/taf-setup/conversation/s7" })
    }

    /// Only Coach's own chat, for someone who may use Coach: anything else opens as any other
    /// agent's row.
    func testAnotherChatOrSomeoneWhoMayNotUseCoachOpensAsBefore() async throws {
        let model = await model(others: [Self.other()])
        XCTAssertFalse(model.canFinishSetup(TafsCloud.HomeTaf.Finish(taf: "taf-other", session: "s7", words: "Finish setting up")), "not Coach's chat")
        XCTAssertFalse(model.canFinishSetup(nil))
        let member = await self.model(others: [Self.other()], capabilities: #"["cards.manage"]"#)
        XCTAssertFalse(member.canFinishSetup(member.otherAgents.first?.finish), "may not use Coach")

        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        for file in ["HomeView.swift", "HomeSplitView.swift"] {
            let source = try String(contentsOf: views.appendingPathComponent(file), encoding: .utf8)
            XCTAssertTrue(source.contains("if let finish = taf.finish, model.canFinishSetup(finish) {"), file)
            XCTAssertTrue(source.contains("Button { Task { await model.finishSetup(finish) } }"), file)
        }
    }

    /// Home lists tafs only (docs/tafs.md): `setting_up` is drawn among the tafs, each by its name,
    /// with no state, so never "Ready". `others`, which then only repeats them for older apps,
    /// isn't drawn, so no KIFF agent shows. A tafs cloud from before it still has `others` drawn.
    func testATafBeingSetUpIsAmongTheTafsAndKIFFsAgentsNeverShow() async throws {
        let half = #"[{"name":"Invoicing day test","kiff_agent":"invoicing-x","color":2,"finish":{"taf":"taf-setup","session":"s7","words":"Finish setting up"}},{"name":"Leads","kiff_agent":"leads-x","color":3}]"#
        let model = await model(others: [Self.other(), HomeJSON.entry(agent: "kiff-ops", name: "ops-bot", id: nil)], settingUp: half)
        XCTAssertEqual(model.settingUp.map(\.name), ["Invoicing day test", "Leads"])
        XCTAssertTrue(model.settingUp.allSatisfy(\.settingUp))
        XCTAssertEqual(model.settingUp.first?.now, "Finish setting up")
        XCTAssertTrue(model.canFinishSetup(model.settingUp.first?.finish), "its starter finishes it in Coach")
        XCTAssertNil(model.settingUp.last?.finish, "anyone else sees the name alone")
        XCTAssertEqual(model.settingUp.last?.now, "", "no state: never Ready")
        XCTAssertTrue(model.otherAgents.isEmpty, "others isn't drawn beside setting_up: no KIFF agent shows")

        let before = await self.model(others: [Self.other()])
        XCTAssertTrue(before.settingUp.isEmpty)
        XCTAssertEqual(before.otherAgents.map(\.name), ["Invoicing day test"], "a tafs cloud from before it: others, as before")

        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        for file in ["HomeView.swift", "HomeSplitView.swift"] {
            XCTAssertTrue(try String(contentsOf: views.appendingPathComponent(file), encoding: .utf8).contains("ForEach(model.settingUp)"), file)
        }
        let home = try String(contentsOf: views.appendingPathComponent("HomeView.swift"), encoding: .utf8)
        XCTAssertTrue(home.contains("} else if !taf.settingUp, !(taf.attention && taf.state == .working) {"), "no state chip")
    }
}
