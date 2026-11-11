import XCTest
@testable import Tafs

/// Home with no taf leads with the jobs a taf can take, as tafs cloud lists them
/// (`GET /v1/recipes`, docs/ios-app.md): its line, the recipes by category, a sheet with the
/// recipe's Card, and one button that hands it to Coach. Every word is tafs cloud's.
@MainActor
final class HomeRecipesTests: XCTestCase {
    private static let setupJSON = #"{"id":"taf-setup","name":"Setup","job":"setup","kiff_agent":"setup","status":"active","next_run":null,"last_run":null}"#

    /// What tafs cloud answers for an account with no taf, in German: Home shown.
    private static let shown = #"""
    {"categories":[{"id":"money","title":"Geld"},{"id":"errands","title":"Erledigungen"},{"id":"customers","title":"Kunden"}],
     "recipes":[
      {"id":"refunds","title":"Erstattungen","category":"money","headline":"Erstattungen, ohne dass du sie abtippst","today":"Heute: jede einzeln, von Hand.","line":"Erstattet bis 40 EUR.","say":"Erstatte Kunden bis 40 EUR.",
       "card":{"allows":["Erstattungen bis 40 EUR"],"holds":["Erstattungen über 40 EUR"],"never":[]}},
      {"id":"orders","title":"Bestellungen","category":"customers","headline":"","today":"","line":"Beantwortet Fragen zu Bestellungen.","say":"Beantworte Fragen zu Bestellungen.",
       "card":{"allows":[],"holds":["Antworten an neue Kunden"],"never":["Preise ändern"]}}],
     "filtered":true,"shown":true,"suggestions":{"show":true,"reason":"","words":""},
     "home":{"shown":true,"words":"Was soll ein taf für dich übernehmen?"},
     "labels":{"allows":"Macht es selbst","holds":"Fragt dich zuerst","never":"Macht es nie","hand":"An Coach übergeben"}}
    """#

    /// What it answers once nothing is listed: Home's empty line alone.
    private static let notShown = #"""
    {"categories":[],"recipes":[],"filtered":true,"shown":false,"suggestions":{"show":true,"reason":"live_taf","words":"…"},
     "home":{"shown":false,"words":"Noch keine tafs. Verbinde eine App, um deinen ersten taf einzurichten."},
     "labels":{"allows":"Macht es selbst","holds":"Fragt dich zuerst","never":"Macht es nie","hand":"An Coach übergeben"}}
    """#

    private func model(signer: FaceIDSigner = FaceIDSigner(), recipes: String? = shown) async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        Keychain.delete(AppModel.planqueAccount)
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
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":null}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        StubProtocol.responses["GET /v1/accounts/t_01/grants"] = (200, #"{"grants":[]}"#)
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[]}"#)
        // This person may use Coach.
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"setup":{"use":true,"oversee":true,"manage":true,"groups":[]}}}"#)
        if let recipes { StubProtocol.responses["GET /v1/recipes"] = (200, recipes) }
        await model.useSession("kiff_dev_x", tenant: "t_01")
        await model.refreshPeople()
        await model.loadRecipes()
        // This phone can approve, unless a test says otherwise.
        model.readApprovalReadiness = { .ready }
        model.checkApprovalReadiness()
        return model
    }

    private func body(_ request: URLRequest?) -> [String: Any] {
        guard let data = request?.httpBody, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return json
    }

    private func sent(_ method: String, _ host: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.host == host && $0.url?.path == path }
    }

    /// What Start answers, as SetupTests has it.
    private func serveStart() {
        StubProtocol.responses["POST /v1/keys"] = (201, #"{"id":"key_c","plaintext":"kiff_live_connector","roles":["connector"]}"#)
        StubProtocol.responses["POST /v1/accounts/t_01/enrolment"] = (201, #"{"tenant":"t_01","device":"ab12"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts"] = (201, #"{"id":"drf_s","agent_id":"setup","status":"confirmed","version":"v1","claim_secret":"kiff_claim_x","no_tools":true,"cards":[]}"#)
        StubProtocol.responses["POST /v1/setup"] = (200, #"{"setup":\#(Self.setupJSON)}"#)
        StubProtocol.responses["POST /v1/tafs/taf-setup/messages/stream"] = (200, """
        event: session
        data: {"session":"s1","new_session":true}

        event: text
        data: {"text":"Gern."}

        event: end
        data: {"status":"COMPLETED","stop":"answered"}


        """)
    }

    /// The read carries Home's line, the sheet's labels, and each recipe's headline, what it
    /// costs today and its Card; a tafs cloud from before them still reads, with none.
    func testTheRecipesReadCarriesHomeTheLabelsAndEachRecipesCard() throws {
        let read = try JSONDecoder().decode(TafsCloud.Recipes.self, from: Data(Self.shown.utf8))
        XCTAssertEqual(read.home, TafsCloud.Recipes.Home(shown: true, words: "Was soll ein taf für dich übernehmen?"))
        XCTAssertEqual(read.labels, TafsCloud.Recipes.Labels(allows: "Macht es selbst", holds: "Fragt dich zuerst", never: "Macht es nie", hand: "An Coach übergeben"))
        let refunds = try XCTUnwrap(read.recipes.first)
        XCTAssertEqual(refunds.headline, "Erstattungen, ohne dass du sie abtippst")
        XCTAssertEqual(refunds.today, "Heute: jede einzeln, von Hand.")
        XCTAssertEqual(refunds.card, TafsCloud.Recipes.Card(allows: ["Erstattungen bis 40 EUR"], holds: ["Erstattungen über 40 EUR"], never: []))
        // The problem it ends leads; a recipe with no headline leads with its title.
        XCTAssertEqual(refunds.lead, "Erstattungen, ohne dass du sie abtippst")
        XCTAssertEqual(read.recipes[1].lead, "Bestellungen")
        // Coach's start list names each job as the gallery and Home do, never by its title alone.
        XCTAssertEqual(SetupChatView.recipeName(refunds), "Erstattungen, ohne dass du sie abtippst")
        XCTAssertEqual(SetupChatView.recipeName(read.recipes[1]), "Bestellungen")

        let old = try JSONDecoder().decode(TafsCloud.Recipes.self, from: Data(#"{"categories":[{"id":"money","title":"Money"}],"recipes":[{"id":"refunds","title":"Refunds","category":"money","line":"Refunds up to 40 EUR.","say":"Refund customers."}],"shown":true}"#.utf8))
        XCTAssertNil(old.home)
        XCTAssertNil(old.labels)
        XCTAssertEqual(old.recipes.first?.card, TafsCloud.Recipes.Card())
        XCTAssertFalse(old.homeListsRecipes, "nothing says to list them on Home")
        XCTAssertNil(old.homeWords)
        XCTAssertNil(old.handWords)
    }

    /// Home with no taf lists the recipes where tafs cloud says so, under its line, by
    /// category in its order, a category with none left out; a category picked narrows the
    /// list, and one that's no longer listed narrows nothing.
    func testHomeWithNoTafListsTheRecipesWhereTafsCloudSaysSo() async throws {
        let model = await model()
        XCTAssertTrue(model.listLoaded)
        XCTAssertTrue(model.tafs.isEmpty)
        XCTAssertTrue(model.homeListsRecipes)
        let recipes = try XCTUnwrap(model.recipes)
        XCTAssertEqual(recipes.homeWords, "Was soll ein taf für dich übernehmen?")
        XCTAssertEqual(recipes.groups(picked: nil).map(\.category.title), ["Geld", "Kunden"])
        XCTAssertEqual(recipes.groups(picked: "customers").map { $0.recipes.map(\.id) }, [["orders"]])
        XCTAssertEqual(recipes.groups(picked: "gone").map(\.category.id), ["money", "customers"])
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Where tafs cloud doesn't show them, Home has its empty line alone, in tafs cloud's
    /// words; and with nothing read, nothing of tafs cloud's is shown at all.
    func testHomeNotShownIsTafsCloudsLineAlone() async throws {
        let model = await model(recipes: Self.notShown)
        XCTAssertFalse(model.homeListsRecipes)
        XCTAssertEqual(model.recipes?.homeWords, "Noch keine tafs. Verbinde eine App, um deinen ersten taf einzurichten.")
        XCTAssertTrue(model.recipes?.byCategory.isEmpty ?? false)

        // `home.shown` with no recipe to list, or without the sheet's words, lists none.
        var none = try JSONDecoder().decode(TafsCloud.Recipes.self, from: Data(Self.shown.utf8))
        none.recipes = []
        XCTAssertFalse(none.homeListsRecipes)
        var unlabelled = try JSONDecoder().decode(TafsCloud.Recipes.self, from: Data(Self.shown.utf8))
        unlabelled.labels = nil
        XCTAssertFalse(unlabelled.homeListsRecipes)

        // Not read: the app falls back to its own line, and lists nothing.
        let unread = await self.model(recipes: nil)
        XCTAssertNil(unread.recipes)
        XCTAssertFalse(unread.homeListsRecipes)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// The sheet's zones are the recipe's Card, in order, titled by tafs cloud's labels: a
    /// zone its Card leaves empty isn't drawn.
    func testAnEmptyZoneIsLeftOutOfTheSheet() throws {
        let read = try JSONDecoder().decode(TafsCloud.Recipes.self, from: Data(Self.shown.utf8))
        let refunds = read.zones(of: read.recipes[0])
        XCTAssertEqual(refunds.map(\.id), ["allows", "holds"], "it never does nothing: no third zone")
        XCTAssertEqual(refunds.map(\.title), ["Macht es selbst", "Fragt dich zuerst"])
        XCTAssertEqual(refunds.map(\.lines), [["Erstattungen bis 40 EUR"], ["Erstattungen über 40 EUR"]])
        let orders = read.zones(of: read.recipes[1])
        XCTAssertEqual(orders.map(\.id), ["holds", "never"], "it does nothing on its own: no first zone")
        XCTAssertEqual(orders.map(\.title), ["Fragt dich zuerst", "Macht es nie"])
        XCTAssertTrue(read.zones(of: TafsCloud.Recipes.Recipe(id: "x", title: "X")).isEmpty)
        XCTAssertEqual(read.handWords, "An Coach übergeben")
    }

    /// Hand it to Coach opens a new Coach conversation with the recipe's words in the box.
    /// Nothing is sent, and nothing is asked of the person: no Face ID yet.
    func testHandItToCoachFillsTheBoxAndSendsNothing() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer)
        XCTAssertTrue(model.mayUseCoach)
        let recipe = try XCTUnwrap(model.recipes?.recipes.first)
        model.setupDraft = "something typed before"
        StubProtocol.sent = []
        model.handRecipeToCoach(recipe)
        XCTAssertEqual(model.setupDraft, "Erstatte Kunden bis 40 EUR.")
        XCTAssertTrue(model.coachOpen)
        XCTAssertTrue(model.setupFresh, "a new conversation")
        XCTAssertTrue(model.setupHanded)
        XCTAssertFalse(model.setupStarted, "Coach isn't started by handing it words")
        XCTAssertTrue(StubProtocol.sent.isEmpty, "nothing is sent")
        XCTAssertTrue(signer.prompts.isEmpty, "and no Face ID is asked for")
        XCTAssertTrue(model.setupThread.messages.isEmpty)
        // Another new conversation is no longer the handed one.
        model.newCoachConversation()
        XCTAssertFalse(model.setupHanded)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Someone who may not use Coach hands nothing over.
    func testSomeoneWhoMayNotUseCoachHandsNothingOver() async throws {
        let model = await model()
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"setup":{"use":false,"oversee":false,"manage":false,"groups":[]}}}"#)
        await model.refreshPeople()
        XCTAssertFalse(model.mayUseCoach)
        let recipe = try XCTUnwrap(model.recipes?.recipes.first)
        model.handRecipeToCoach(recipe)
        XCTAssertFalse(model.coachOpen)
        XCTAssertEqual(model.setupDraft, "")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// Words first: on a phone where Coach isn't started, Send starts it behind one Face ID,
    /// and then sends the words. One prompt, not two: the same unlock signs the phone's
    /// enrolment and the draft.
    func testSendOnAnUnstartedPhoneStartsCoachWithOneFaceIDThenSends() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer)
        serveStart()
        let recipe = try XCTUnwrap(model.recipes?.recipes.first)
        model.handRecipeToCoach(recipe)
        XCTAssertFalse(model.setupStarted)
        StubProtocol.sent = []

        let taken = await model.submitSetup(model.setupDraft)
        XCTAssertTrue(taken, model.setupError ?? "")
        await model.setupStream?.value
        XCTAssertEqual(signer.prompts, ["tafs.setup.v1|t_01"], "one Face ID on a first Start")
        XCTAssertTrue(model.setupStarted)
        XCTAssertEqual(sent("POST", "cloud.test", "/v1/setup").count, 1)
        let message = sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages/stream")
        XCTAssertEqual(message.count, 1, "sent once, after Start")
        XCTAssertEqual(body(message.first)["text"] as? String, "Erstatte Kunden bis 40 EUR.")
        // Start came first.
        let order = StubProtocol.sent.compactMap { $0.url?.path }
        let started = try XCTUnwrap(order.firstIndex(of: "/v1/setup"))
        let said = try XCTUnwrap(order.firstIndex(of: "/v1/tafs/taf-setup/messages/stream"))
        XCTAssertLessThan(started, said)
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A Start that's cancelled sends nothing: the message isn't taken, so the composer keeps
    /// the words, and nothing shows as sent.
    func testACancelledStartKeepsTheWordsAndSendsNothing() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer)
        serveStart()
        let recipe = try XCTUnwrap(model.recipes?.recipes.first)
        model.handRecipeToCoach(recipe)
        signer.refuse = true
        StubProtocol.sent = []

        let taken = await model.submitSetup(model.setupDraft)
        XCTAssertFalse(taken, "not taken: the composer puts the words back")
        XCTAssertEqual(model.setupDraft, "Erstatte Kunden bis 40 EUR.")
        XCTAssertFalse(model.setupStarted)
        XCTAssertTrue(model.setupHanded, "the box stays, with the words")
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/setup").isEmpty)
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/tafs/taf-setup/messages/stream").isEmpty)
        XCTAssertTrue(model.setupThread.messages.isEmpty, "nothing shows as sent")
        // Nothing to send starts nothing either.
        signer.refuse = false
        let empty = await model.submitSetup("   ")
        XCTAssertFalse(empty)
        XCTAssertTrue(signer.prompts.isEmpty, "an empty box asks for no Face ID")
        Keychain.delete(AppModel.planqueAccount)
    }

    /// A phone that can't approve yet starts nothing with handed words: Send takes nothing,
    /// asks for no Face ID and sends nothing, and the words stay for when it can. Once it can,
    /// the same Send starts Coach behind its one prompt.
    func testAPhoneThatCantApproveYetKeepsTheHandedWordsAndStartsNothing() async throws {
        let signer = FaceIDSigner()
        let model = await model(signer: signer)
        serveStart()
        let recipe = try XCTUnwrap(model.recipes?.recipes.first)
        var device = ApprovalReadiness.unavailable(lockedOut: true)
        model.readApprovalReadiness = { device }
        model.checkApprovalReadiness()
        XCTAssertTrue(model.approvalBlocked)

        model.handRecipeToCoach(recipe)
        XCTAssertEqual(model.setupDraft, "Erstatte Kunden bis 40 EUR.", "the words are kept")
        StubProtocol.sent = []
        let taken = await model.submitSetup(model.setupDraft)
        XCTAssertFalse(taken, "not taken: the words stay")
        XCTAssertTrue(signer.prompts.isEmpty, "a phone that can't approve is asked nothing")
        XCTAssertFalse(model.setupStarted)
        XCTAssertEqual(model.setupDraft, "Erstatte Kunden bis 40 EUR.")
        XCTAssertTrue(model.setupHanded)
        XCTAssertTrue(sent("POST", "cloud.test", "/v1/setup").isEmpty)
        XCTAssertTrue(model.setupThread.messages.isEmpty, "nothing shows as sent")

        // The phone can approve again: the words are still there, and Send starts Coach.
        device = .ready
        model.checkApprovalReadiness()
        XCTAssertFalse(model.approvalBlocked)
        let started = await model.submitSetup(model.setupDraft)
        XCTAssertTrue(started, model.setupError ?? "")
        await model.setupStream?.value
        XCTAssertEqual(signer.prompts, ["tafs.setup.v1|t_01"])
        Keychain.delete(AppModel.planqueAccount)
    }
}
