import XCTest
@testable import Tafs

/// Coach's tools (kiff/tafs#206, docs/coach.md): what tafs cloud says is still to give, given
/// as it came behind one Face ID; Coach's held installs; and how its tasks went.
@MainActor
final class CoachToolsTests: XCTestCase {
    private func held(action: String, arguments: String, subject: String = "setup-acme") throws -> KIFFClient.CardException {
        let json = """
        {"id":"ce_9","proposal_id":"p9","subject":"\(subject)","action":"\(action)","entity_id":"call_9","title":"Install a version",
          "status":"held","breaches":[{"mandate_id":"coach-version-install","outcome":"limit_reached","quantity":"count","limit":0}],
          "arguments":\(arguments),"created_at":"2026-10-23T18:00:00Z","expires_at":"2099-10-23T18:10:00Z","accounting_version":1}
        """
        return try KIFFClient.decoder.decode(KIFFClient.CardException.self, from: Data(json.utf8))
    }

    func testAHeldInstallOfCoachsNamesItsTafAndVersion() throws {
        let call = KIFFMapping.held(try held(action: "version_install", arguments: #"{"taf":"taf-aaaa","n":2,"tafs_op":"op_1"}"#), tafID: UUID(), coach: "setup-acme")
        XCTAssertEqual(call.install, VersionInstall(taf: "taf-aaaa", n: 2))
        XCTAssertNil(KIFFMapping.held(try held(action: "taf_task", arguments: #"{"taf":"taf-aaaa","text":"go"}"#), tafID: UUID(), coach: "setup-acme").install)
        XCTAssertNil(KIFFMapping.held(try held(action: "version_install", arguments: #"{"taf":"taf-aaaa","n":"two"}"#), tafID: UUID(), coach: "setup-acme").install,
                     "no version to install: an ordinary held call")
    }

    func testAnotherAgentsVersionInstallIsAnOrdinaryHeldCall() throws {
        let args = #"{"taf":"taf-aaaa","n":2,"tafs_op":"op_1"}"#
        XCTAssertNil(KIFFMapping.held(try held(action: "version_install", arguments: args, subject: "refunds-acme"), tafID: UUID(), coach: "setup-acme").install,
                     "only Coach's own call installs")
        XCTAssertNil(KIFFMapping.held(try held(action: "version_install", arguments: args), tafID: UUID(), coach: nil).install,
                     "before the app gave Coach its tools, the name isn't known to be its connection")
        XCTAssertNil(KIFFMapping.held(try held(action: "vendor.version_install", arguments: args), tafID: UUID(), coach: "setup-acme").install)
    }

    // MARK: Coach's tools step (docs/coach.md › Coach's tools step)

    private static let setupJSON = #"{"id":"taf-setup","name":"Setup","job":"setup","kiff_agent":"setup-acme","status":"active","next_run":null,"last_run":null}"#

    /// The batch and the Cards' draft as tafs cloud writes them: the spacing and the order of
    /// the keys are its own, and the phone sends these bytes.
    private static let batch = #"{ "connections":[{"name":"taf_check","url":"https://cloud.test/v1/mcp","tool":"taf_check","mode":"verify","control":true}],  "z":1 }"#
    private static let cards = #"{ "label":"Coach",  "agent_id":"setup-acme","cards":[{"id":"coach-checks","on_exceed":"refuse"}] }"#

    private static let step = #"""
    {"title":"Gib Coach seine Werkzeuge","intro":"Coach braucht sie, nur du kannst unterschreiben, auf deinem Telefon.",
     "groups":[{"name":"Deine tafs beobachten","line":"Bis zu 500 am Tag.",
                "tools":[{"name":"Prüfen","does":"Prüft, was mit einem taf nicht stimmt. Bis zu 200 am Tag.","why":"Liest nur."},{"name":7}]},
               {"name":"In der App helfen","line":"Solange du fährst.","tools":[]}],
     "details":"Alle zwölf Werkzeuge","ask":"Coach diese Werkzeuge geben? Du unterschreibst seine Card auf deinem Telefon.",
     "give":"Werkzeuge geben","not_now":"Nicht jetzt","row":"Coach fehlen noch Werkzeuge"}
    """#

    private static func read(state: String, connect: Bool = true, card: Bool = true, taken: String? = nil, step: Bool = true) -> String {
        var tools = #""state":"\#(state)""#
        if let taken { tools += #","taken":"\#(taken)""# }
        if connect { tools += #","connect":{"body":\#(batch),"lines":["Prüfen, was mit einem taf nicht stimmt"]}"# }
        if card { tools += #","card":{"body":\#(cards),"card_title":"Coachs Cards","cards":[{"title":"Prüfungen","lines":["200 am Tag"]},{"nothing":true}]}"# }
        if step { tools += #","coach_tools_step":\#(Self.step)"# }
        return #"{"setup":\#(setupJSON),"tools":{\#(tools)}}"#
    }

    /// A signed-in phone whose Coach is started, with tafs cloud answering `tools` as given.
    private func model(_ setup: String, signer: FaceIDSigner = FaceIDSigner()) async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: StubVendor())
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, setup)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[]}"#)
        StubProtocol.responses["POST /v1/connections/batch"] = (201, "{}")
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_c","agent_id":"setup-acme","status":"confirmed","version":"v1"}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        await model.loadSetup()
        model.readApprovalReadiness = { .ready }
        model.checkApprovalReadiness()
        StubProtocol.sent = []
        return model
    }

    private func sent(_ method: String, _ host: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.host == host && $0.url?.path == path }
    }

    /// tafs cloud's `tools` read: the state, the step's words, KIFF's words for the Cards, and
    /// the two bodies as their own bytes. A part the app can't read is left out, never the read.
    func testTheToolsReadKeepsTheBodiesAsTafsCloudWroteThem() throws {
        let tools = try XCTUnwrap(TafsCloud.CoachTools.read(Data(Self.read(state: "to_give").utf8)))
        XCTAssertTrue(tools.toGive)
        XCTAssertFalse(tools.given)
        XCTAssertEqual(tools.connect?.body, Data(Self.batch.utf8), "the batch's bytes, spacing and order as written")
        XCTAssertEqual(tools.connect?.lines, ["Prüfen, was mit einem taf nicht stimmt"])
        XCTAssertEqual(tools.card?.body, Data(Self.cards.utf8), "the Cards' draft's bytes as written")
        XCTAssertEqual(tools.card?.cardTitle, "Coachs Cards")
        XCTAssertEqual(tools.card?.cards, [TafsCloud.CoachTools.CardDraft.Words(title: "Prüfungen", lines: ["200 am Tag"])],
                       "a Card with no words the app can read is left out")
        let step = try XCTUnwrap(tools.step)
        XCTAssertEqual(step.title, "Gib Coach seine Werkzeuge")
        XCTAssertEqual(step.groups.map(\.name), ["Deine tafs beobachten", "In der App helfen"])
        XCTAssertEqual(step.groups[0].tools.map(\.name), ["Prüfen"], "a tool the app can't read is left out")
        XCTAssertEqual(step.groups[0].tools[0].why, "Liest nur.")
        XCTAssertEqual(step.give, "Werkzeuge geben")
        XCTAssertEqual(step.notNow, "Nicht jetzt")
        XCTAssertEqual(step.row, "Coach fehlen noch Werkzeuge")

        // Given, taken and a tafs cloud from before `tools`.
        let given = try XCTUnwrap(TafsCloud.CoachTools.read(Data(#"{"setup":null,"tools":{"state":"given"}}"#.utf8)))
        XCTAssertTrue(given.given)
        XCTAssertNil(given.connect)
        XCTAssertNil(given.card)
        let taken = try XCTUnwrap(TafsCloud.CoachTools.read(Data(Self.read(state: "taken", connect: false, card: false, taken: "run_stop").utf8)))
        XCTAssertEqual(taken.taken, "run_stop")
        XCTAssertNil(TafsCloud.CoachTools.read(Data(#"{"setup":null}"#.utf8)), "an older tafs cloud says nothing of the tools")
        XCTAssertEqual(TafsCloud.CoachTools.read(Data(#"{"tools":{"state":7}}"#.utf8))?.state, "unknown", "a state it can't read is unknown")
    }

    /// The row and the sheet's buttons read tafs cloud's words; the app's own stand in only
    /// where tafs cloud sends none.
    func testTheStepsWordsAreTafsCloudsAndTheAppsOnlyWithoutThem() throws {
        let said = try XCTUnwrap(TafsCloud.CoachTools.read(Data(Self.read(state: "to_give").utf8)))
        XCTAssertEqual(CoachToolsRow.words(said), "Coach fehlen noch Werkzeuge")
        XCTAssertEqual(CoachToolsSheet.title(said), "Gib Coach seine Werkzeuge")
        let before = try XCTUnwrap(TafsCloud.CoachTools.read(Data(Self.read(state: "to_give", step: false).utf8)))
        XCTAssertNil(before.step)
        XCTAssertEqual(CoachToolsRow.words(before), Words.Coach.Tools.title)
        XCTAssertEqual(CoachToolsSheet.said("  ", Words.Common.notNow), Words.Common.notNow, "a blank line is no line")
    }

    /// Opening Coach reads its tools and gives nothing: no Face ID, nothing sent to KIFF.
    func testReadingCoachsToolsGivesNothing() async throws {
        let signer = FaceIDSigner()
        let model = await model(Self.read(state: "to_give"), signer: signer)
        XCTAssertEqual(model.coachTools?.toGive, true)
        XCTAssertFalse(model.coachToolsAsked, "only a first Start asks; after that the row does")
        await model.loadCoachTools()
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(sent("POST", "mcp.test", "/v1/connections/batch").isEmpty)
        XCTAssertTrue(sent("POST", "api.test", "/v1/me/agent-drafts").isEmpty)
    }

    /// Give Coach its tools: one Face ID, then tafs cloud's batch to the gateway and its Cards'
    /// draft to KIFF, each signed and sent as the bytes that came, in that order.
    func testGivingSendsTafsCloudsBatchThenItsCardsBehindOneFaceID() async throws {
        let signer = FaceIDSigner()
        let model = await model(Self.read(state: "to_give"), signer: signer)
        // Once both are in, tafs cloud says given.
        StubProtocol.thenRespond["POST /v1/me/agent-drafts"] = ["GET /v1/setup": (200, #"{"setup":\#(Self.setupJSON),"tools":{"state":"given"}}"#)]

        let given = await model.giveCoachTools()
        XCTAssertTrue(given, model.coachToolsError ?? "")
        XCTAssertEqual(signer.prompts, ["tafs.coach-tools.v1|t_01"], "one confirmation")
        let batch = try XCTUnwrap(sent("POST", "mcp.test", "/v1/connections/batch").first)
        XCTAssertEqual(batch.httpBody, Data(Self.batch.utf8), "the batch as it came")
        XCTAssertNotNil(batch.value(forHTTPHeaderField: "KIFF-Device-Signature"), "signed, as the gateway asks of a phone")
        let draft = try XCTUnwrap(sent("POST", "api.test", "/v1/me/agent-drafts").first)
        XCTAssertEqual(draft.httpBody, Data(Self.cards.utf8), "the Cards' draft as it came")
        XCTAssertNotNil(draft.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(sent("POST", "mcp.test", "/v1/connections/batch").count, 1)
        XCTAssertEqual(sent("POST", "api.test", "/v1/me/agent-drafts").count, 1)
        let order = StubProtocol.sent.filter { $0.httpMethod == "POST" }.compactMap { $0.url?.path }
        XCTAssertEqual(order, ["/v1/connections/batch", "/v1/me/agent-drafts"], "the connections first: a Card names what's connected")
        XCTAssertEqual(model.coachTools?.given, true)
        XCTAssertNil(model.coachToolsError)
        XCTAssertFalse(model.coachToolsAsked)
        // The app's own list of tools and Cards is gone: nothing else is sent to the gateway.
        XCTAssertTrue(sent("POST", "mcp.test", "/v1/connections").isEmpty)
    }

    /// The Cards come once every tool is connected: where tafs cloud sent the batch alone, the
    /// draft is read after it and given in the same step, behind the same one Face ID.
    func testTheCardsAreReadAfterTheBatchWhenTheyDidntComeWithIt() async throws {
        let signer = FaceIDSigner()
        let model = await model(Self.read(state: "to_give", card: false), signer: signer)
        StubProtocol.thenRespond["POST /v1/connections/batch"] = ["GET /v1/setup": (200, Self.read(state: "to_give", connect: false))]
        StubProtocol.thenRespond["POST /v1/me/agent-drafts"] = ["GET /v1/setup": (200, #"{"setup":\#(Self.setupJSON),"tools":{"state":"given"}}"#)]

        let given = await model.giveCoachTools()
        XCTAssertTrue(given, model.coachToolsError ?? "")
        XCTAssertEqual(signer.prompts.count, 1, "the person confirms once")
        XCTAssertEqual(sent("POST", "api.test", "/v1/me/agent-drafts").first?.httpBody, Data(Self.cards.utf8))
    }

    /// Cancelled at Face ID: nothing is sent, the tools stay to give, and the screen says so.
    func testACancelledGiveSendsNothing() async throws {
        let signer = FaceIDSigner()
        let model = await model(Self.read(state: "to_give"), signer: signer)
        signer.refuse = true
        let given = await model.giveCoachTools()
        XCTAssertFalse(given)
        XCTAssertTrue(sent("POST", "mcp.test", "/v1/connections/batch").isEmpty)
        XCTAssertTrue(sent("POST", "api.test", "/v1/me/agent-drafts").isEmpty)
        XCTAssertEqual(model.coachTools?.toGive, true)
        XCTAssertNotNil(model.coachToolsError)
    }

    /// A name a vendor's tool holds stops it before anything is asked or sent, and says which.
    /// Tools already given ask and send nothing either.
    func testTakenAndGivenAskAndSendNothing() async throws {
        let signer = FaceIDSigner()
        let taken = await model(Self.read(state: "taken", connect: false, card: false, taken: "run_stop"), signer: signer)
        let gave = await taken.giveCoachTools()
        XCTAssertFalse(gave)
        XCTAssertEqual(taken.coachToolsError, Words.Coach.Taken.named(name: "run_stop"))
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(StubProtocol.sent.filter { $0.httpMethod == "POST" }.isEmpty)

        let given = await model(#"{"setup":\#(Self.setupJSON),"tools":{"state":"given"}}"#, signer: signer)
        let already = await given.giveCoachTools()
        XCTAssertTrue(already)
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(StubProtocol.sent.filter { $0.httpMethod == "POST" }.isEmpty)
    }

    /// A phone that can't approve yet gives nothing and is asked nothing.
    func testAPhoneThatCantApproveYetGivesNothing() async throws {
        let signer = FaceIDSigner()
        let model = await model(Self.read(state: "to_give"), signer: signer)
        model.readApprovalReadiness = { .unavailable(lockedOut: true) }
        model.checkApprovalReadiness()
        let given = await model.giveCoachTools()
        XCTAssertFalse(given)
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(StubProtocol.sent.filter { $0.httpMethod == "POST" }.isEmpty)
    }

    /// Not now puts the step off: nothing is sent, the tools stay to give, and the row is
    /// still there to open it again.
    func testNotNowKeepsTheToolsToGive() async throws {
        let signer = FaceIDSigner()
        let model = await model(Self.read(state: "to_give"), signer: signer)
        model.coachToolsAsked = true
        model.notNowCoachTools()
        XCTAssertFalse(model.coachToolsAsked)
        XCTAssertEqual(model.coachTools?.toGive, true, "the row shows while they're to give")
        XCTAssertTrue(signer.prompts.isEmpty)
        XCTAssertTrue(StubProtocol.sent.filter { $0.httpMethod == "POST" }.isEmpty)
    }

    /// The gateway takes the batch from a phone only signed, over exactly its bytes.
    func testTheBatchIsSignedOnTheGatewayOverItsOwnBytes() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!,
                              token: "kiff_dev_x", session: URLSession(configuration: config))
        var signed: [String] = []
        kiff.tenant = "t1"
        kiff.setupSigner = { message in signed.append(String(decoding: message, as: UTF8.self)); return "sig" }
        StubProtocol.responses["POST /v1/connections/batch"] = (201, "{}")
        try await kiff.connectBatch(Data(Self.batch.utf8))
        let request = try XCTUnwrap(StubProtocol.sent.first { $0.url?.path == "/v1/connections/batch" })
        XCTAssertEqual(request.url?.host, "mcp.test", "the gateway takes connections")
        XCTAssertNotNil(request.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(request.httpBody, Data(Self.batch.utf8))
        XCTAssertEqual(signed.count, 1)
        XCTAssertTrue(signed[0].hasPrefix("kiff.request.v1\nt1\nPOST\n"), signed[0])
        XCTAssertTrue(signed[0].contains("\n/v1/connections/batch\n"))
    }

    func testCoachsChatShowsHowItsTasksWentAndLeavesOutOneItCantRead() async throws {
        StubProtocol.responses = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        XCTAssertEqual(cloud.mcpURL.absoluteString, "https://cloud.test/v1/mcp")
        StubProtocol.responses["GET /v1/tafs/setup_1/conversation"] = (200, """
        {"messages":[],"coach_lines":[{"at":"2026-10-23T18:00:00Z","taf":"Refunds","said":"Refunded order 42."},{"at":"never","taf":"Refunds","said":"x"}]}
        """)
        let thread = try await cloud.setupThread("setup_1")
        XCTAssertEqual(thread.coachLines.map(\.said), ["Refunded order 42."])
        XCTAssertEqual(thread.coachLines.first?.taf, "Refunds")
    }
}
