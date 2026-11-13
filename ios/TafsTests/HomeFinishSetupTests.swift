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

        // An item that isn't one is skipped, and the rest of Home still reads.
        let odd = await self.model(others: [], settingUp: #"[7,{"name":"Leads","kiff_agent":"leads-x"},{"kiff_agent":"no-name"}]"#)
        XCTAssertEqual(odd.settingUp.map(\.name), ["Leads"])
        XCTAssertTrue(odd.listLoaded, "Home still reads")

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

    /// Discard, for the person whose setup it was (kiff/tafs#905): its Cards are revoked in KIFF
    /// first, as before a removal, then tafs cloud is asked, signed with the approval key over the
    /// POST and its empty body, behind one Face ID; the row goes. Anyone else gets no Discard.
    func testDiscardRevokesTheCardsThenAsksTafsCloudSigned() async throws {
        let half = #"[{"name":"Invoicing day test","kiff_agent":"invoicing-x","color":2,"finish":{"taf":"taf-setup","session":"s7","words":"Finish setting up"},"discard":{"words":{"button":"Discard","title":"Discard Invoicing day test?","text":"Its Card ends.","yes":"Discard","no":"Keep"}}},{"name":"Leads","kiff_agent":"leads-x","discard":{"words":{"button":" "}}}]"#
        let repeated = HomeJSON.entry(agent: "invoicing-x", name: "Invoicing day test", id: nil, cards: ["c_inv"])
        let model = await model(others: [repeated], settingUp: half)
        let taf = try XCTUnwrap(model.settingUp.first)
        XCTAssertEqual(taf.discard?.yes, "Discard")
        XCTAssertEqual(taf.kiffCardIDs, ["c_inv"], "its Cards, from others' repeat")
        XCTAssertNil(model.settingUp.last?.discard, "no button, no Discard")
        // The Cards `discard` names win over others' repeat, as plain ids or {id} each.
        let named = await self.model(others: [repeated], settingUp: half.replacingOccurrences(of: #""no":"Keep"}}"#, with: #""no":"Keep"},"cards":[{"id":"c_named"}]}"#))
        XCTAssertEqual(named.settingUp.first?.kiffCardIDs, ["c_named"])

        StubProtocol.responses["POST /v1/me/mandates/c_inv/revoke"] = (200, "{}")
        StubProtocol.responses["POST /v1/home/setting-up/invoicing-x/discard"] = (200, #"{"discarded":true,"kiff_agent":"invoicing-x"}"#)
        // Read again after it, Home no longer lists it.
        StubProtocol.thenRespond["POST /v1/home/setting-up/invoicing-x/discard"] = ["GET /v1/home": (200, String(HomeJSON.list([]).dropLast()) + #","setting_up":[]}"#)]
        StubProtocol.sent = []
        let note = await model.discardSettingUp(taf.id)
        XCTAssertNil(note)
        let order = StubProtocol.sent.compactMap { r -> String? in
            guard r.httpMethod == "POST", let p = r.url?.path, ["/v1/me/mandates/c_inv/revoke", "/v1/home/setting-up/invoicing-x/discard"].contains(p) else { return nil }
            return p
        }
        XCTAssertEqual(order, ["/v1/me/mandates/c_inv/revoke", "/v1/home/setting-up/invoicing-x/discard"], "the Cards first")
        let discard = try XCTUnwrap(StubProtocol.sent.first { $0.url?.path == "/v1/home/setting-up/invoicing-x/discard" })
        XCTAssertTrue(discard.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasPrefix("t=") ?? false, "signed as a removal is")
        XCTAssertTrue((discard.httpBody ?? Data()).isEmpty, "the empty body it signs")
        XCTAssertFalse(model.settingUp.contains { $0.id == taf.id }, "the row goes")

        // KIFF still holds a Card in force: tafs cloud ends nothing, and says how many are left.
        let again = await self.model(others: [repeated], settingUp: half)
        StubProtocol.responses["POST /v1/me/mandates/c_inv/revoke"] = (502, #"{"error":"KIFF is busy"}"#)
        StubProtocol.responses["POST /v1/home/setting-up/invoicing-x/discard"] = (409, #"{"error":"cards still in force","code":"cards_in_force","cards":["c_inv"]}"#)
        let left = await again.discardSettingUp(try XCTUnwrap(again.settingUp.first).id)
        XCTAssertEqual(left, Words.Card.kiffDidNotRevokeCards(cardsLeft: "1"))
        XCTAssertEqual(again.settingUp.count, 2, "nothing ended")

        // A Card tafs cloud names that Home didn't: the next Discard revokes it too, never the same failure twice.
        StubProtocol.responses["POST /v1/home/setting-up/invoicing-x/discard"] = (409, #"{"error":"cards still in force","code":"cards_in_force","cards":["c_other"]}"#)
        _ = await again.discardSettingUp(try XCTUnwrap(again.settingUp.first).id)
        StubProtocol.responses["POST /v1/me/mandates/c_inv/revoke"] = (200, "{}")
        StubProtocol.responses["POST /v1/me/mandates/c_other/revoke"] = (200, "{}")
        StubProtocol.responses["POST /v1/home/setting-up/invoicing-x/discard"] = (200, #"{"discarded":true,"kiff_agent":"invoicing-x"}"#)
        StubProtocol.sent = []
        _ = await again.discardSettingUp(try XCTUnwrap(again.settingUp.first).id)
        XCTAssertFalse(StubProtocol.sent.filter { $0.url?.path == "/v1/me/mandates/c_other/revoke" }.isEmpty, "the Card tafs cloud named is revoked")

        // Refused once its Cards are revoked (the agent has none left), but Home no longer lists
        // it: that's done, never "Not done".
        let gone = await self.model(others: [repeated], settingUp: half)
        StubProtocol.responses["POST /v1/me/mandates/c_inv/revoke"] = (200, "{}")
        StubProtocol.responses["POST /v1/home/setting-up/invoicing-x/discard"] = (409, #"{"error":"it changed in the meantime"}"#)
        StubProtocol.thenRespond["POST /v1/home/setting-up/invoicing-x/discard"] = ["GET /v1/home": (200, String(HomeJSON.list([]).dropLast()) + #","setting_up":[]}"#)]
        let note = await gone.discardSettingUp(try XCTUnwrap(gone.settingUp.first).id)
        XCTAssertNil(note, "gone from Home is done")
        XCTAssertTrue(gone.settingUp.isEmpty)
    }
}
