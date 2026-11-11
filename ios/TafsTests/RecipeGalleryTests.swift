import XCTest
@testable import Tafs

/// Every ready-made job, reachable at any time (`GET /v1/recipes/gallery`, docs/ios-app.md): + New's
/// gallery, the strip under the tafs list, each job's apps with their logos from tafs cloud only,
/// and a colour per category. tafs cloud decides; the app draws.
@MainActor
final class RecipeGalleryTests: XCTestCase {
    private static let gallery = #"""
    {"categories":[{"id":"money","title":"Money","color":{"light":"#107758","dark":"#4CC38A"}},
                   {"id":"customers","title":"Customers","color":{"light":"#B0127A","dark":"pink"}},
                   {"id":"errands","title":"Errands"}],
     "recipes":[
      {"id":"refunds-within-a-limit","title":"Refunds within a limit","category":"money","headline":"No refund waits overnight.","line":"Answers refund requests.","say":"Handle refund requests.",
       "card":{"allows":["Refunds up to 150 EUR each"],"holds":[],"never":["A payout"]},
       "apps":[{"name":"Gorgias","logo":"/v1/logos/3f9c0a1b2c3d4e5f60718293a4b5c6d7"},{"name":"Stripe"},{"logo":"/v1/logos/abc"}],
       "connected":false,"running":true},
      {"id":"chase-overdue-invoices","title":"Chase overdue invoices","category":"money","headline":"Get paid without chasing.","line":"Reminds customers.","say":"Chase my invoices.",
       "apps":[{"name":"QuickBooks"}],"connected":true,"running":false},
      {"id":"answer-questions-about-orders","title":"Answer questions about orders","category":"customers","headline":"Stop answering \"where is my order?\"","say":"Answer order questions."}],
     "strip":{"shown":true,"title":"More jobs to hand off","ids":["chase-overdue-invoices","gone-since","answer-questions-about-orders"]},
     "words":{"title":"New taf","own_words":"Say it in your own words","own_words_line":"Tell Coach the job, and it builds the taf with you."},
     "labels":{"allows":"Does on its own","holds":"Asks you first","never":"Never does","hand":"Hand it to Coach"}}
    """#

    private func read(_ json: String = gallery) throws -> TafsCloud.Gallery {
        try JSONDecoder().decode(TafsCloud.Gallery.self, from: Data(json.utf8))
    }

    /// The gallery reads as tafs cloud sends it: categories with their colours, each job with its
    /// apps, whether they're connected and whether one runs, the strip, the words and the labels.
    func testTheGalleryReadsAsTafsCloudSendsIt() throws {
        let g = try read()
        XCTAssertEqual(g.categories.map(\.id), ["money", "customers", "errands"])
        XCTAssertEqual(g.categories[0].color, TafsCloud.Recipes.Tint(light: 0x107758, dark: 0x4CC38A))
        XCTAssertNil(g.categories[1].color, "a colour the app can't read is no colour, never a guess")
        XCTAssertNil(g.categories[2].color, "a tafs cloud from before colours")
        let refunds = try XCTUnwrap(g.recipes.first)
        XCTAssertEqual(refunds.apps, [.init(name: "Gorgias", logo: "/v1/logos/3f9c0a1b2c3d4e5f60718293a4b5c6d7"), .init(name: "Stripe")],
                       "an app with no name is left out; the rest still read")
        XCTAssertTrue(refunds.running)
        XCTAssertFalse(refunds.connected)
        XCTAssertTrue(g.recipes[1].connected)
        XCTAssertEqual(g.recipes[2].apps, [], "a job with no apps sent has none")
        XCTAssertEqual(g.words.title, "New taf")
        XCTAssertEqual(g.words.ownWords, "Say it in your own words")
        XCTAssertEqual(g.labels?.hand, "Hand it to Coach")
    }

    /// The strip draws tafs cloud's ids in its order; one the gallery doesn't list is skipped,
    /// and a strip tafs cloud doesn't show draws nothing.
    func testTheStripDrawsTafsCloudsIdsInOrder() throws {
        let g = try read()
        XCTAssertEqual(g.stripRecipes.map(\.id), ["chase-overdue-invoices", "answer-questions-about-orders"])
        XCTAssertEqual(g.strip.title, "More jobs to hand off")
        var hidden = g
        hidden.strip.shown = false
        XCTAssertTrue(hidden.stripRecipes.isEmpty)
        let none = try read(#"{"categories":[],"recipes":[]}"#)
        XCTAssertTrue(none.stripRecipes.isEmpty, "no strip sent, none shown")
        XCTAssertNil(none.labels)
    }

    /// The gallery draws as Home's list does, its sheet with the gallery's words, else those
    /// `/v1/recipes` sent.
    func testTheGalleryDrawsAsHomesListWithItsSheetWords() throws {
        let g = try read()
        let recipes = g.asRecipes()
        XCTAssertEqual(recipes.byCategory.map(\.category.id), ["money", "customers"], "a category with no job isn't shown")
        XCTAssertEqual(recipes.handWords, "Hand it to Coach")
        XCTAssertEqual(recipes.zones(of: g.recipes[0]).map(\.title), ["Does on its own", "Never does"])
        let without = try read(#"{"categories":[{"id":"money","title":"Money"}],"recipes":[{"id":"r","title":"R","category":"money"}]}"#)
        XCTAssertNil(without.asRecipes().handWords)
        XCTAssertEqual(without.asRecipes(labels: .init(hand: "An Coach übergeben")).handWords, "An Coach übergeben")
        XCTAssertEqual(g.category(of: g.recipes[0])?.id, "money")
    }

    /// A logo loads only from tafs cloud, by the path it sent: an absolute URL, another host,
    /// another path or a path that climbs out loads nothing, so the phone never asks anyone else.
    func testALogoLoadsOnlyFromTafsCloud() {
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "x")
        XCTAssertEqual(cloud.logoURL("/v1/logos/3f9c0a1b2c3d4e5f60718293a4b5c6d7")?.absoluteString,
                       "https://cloud.test/v1/logos/3f9c0a1b2c3d4e5f60718293a4b5c6d7")
        for bad in ["https://logos.example/stripe.svg", "//logos.example/v1/logos/abc", "/v1/logos/", "/v1/logos/../tafs",
                    "/v1/logos/abc?x=https://logos.example", "/v1/tafs", "v1/logos/abc", "/v1/logos/a/b", ""] {
            XCTAssertNil(cloud.logoURL(bad), bad)
        }
        XCTAssertNil(cloud.logoURL(nil))
    }

    /// A colour is `#RRGGBB` and only that.
    func testAColourIsHashAndSixHexDigits() {
        XCTAssertEqual(TafsCloud.Recipes.Tint.hex("#107758"), 0x107758)
        XCTAssertEqual(TafsCloud.Recipes.Tint.hex("#a98bff"), 0xA98BFF)
        for bad in ["107758", "#10775", "#1077588", "#10775G", "red", ""] { XCTAssertNil(TafsCloud.Recipes.Tint.hex(bad), bad) }
    }

    /// `/v1/recipes` keeps reading as before, with the new fields where tafs cloud adds them.
    func testRecipesFromBeforeTheGalleryStillRead() throws {
        let old = try JSONDecoder().decode(TafsCloud.Recipes.self, from: Data(#"""
        {"categories":[{"id":"money","title":"Money"}],"recipes":[{"id":"r","title":"R","category":"money","say":"S"}]}
        """#.utf8))
        XCTAssertEqual(old.recipes.first?.apps, [])
        XCTAssertEqual(old.recipes.first?.running, false)
        XCTAssertNil(old.categories.first?.color)
    }

    /// Connect apps reads each app's logo path; planque's own `icon` URL is never what's drawn.
    func testConnectAppsReadsTheLogoPath() throws {
        let apps = try JSONDecoder().decode(TafsCloud.ConnectApps.self, from: Data(#"""
        {"apps":[{"entry":"connector:composio:stripe","title":"Stripe","logo":"/v1/logos/abc123","state":"available"},
                 {"entry":"connector:composio:gmail","title":"Gmail","state":"connected"}]}
        """#.utf8))
        XCTAssertEqual(apps.apps.map(\.logo), ["/v1/logos/abc123", nil])
    }

    /// The model reads the gallery; a tafs cloud without the route (404) leaves none, so + New
    /// opens Coach as before; a read that fails otherwise keeps what was read.
    func testTheModelReadsTheGalleryAndATafsCloudWithoutItLeavesNone() async throws {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, token: "kiff_dev_x", session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session)
        let model = AppModel(signer: StubSigner(), kiff: kiff, cloud: cloud)
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/recipes/gallery"] = (200, Self.gallery)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        await model.loadGallery()
        XCTAssertEqual(model.gallery?.recipes.count, 3)

        StubProtocol.responses["GET /v1/recipes/gallery"] = (500, #"{"error":"busy"}"#)
        await model.loadGallery()
        XCTAssertEqual(model.gallery?.recipes.count, 3, "a failed read keeps what was read")

        StubProtocol.responses["GET /v1/recipes/gallery"] = (404, #"{"error":"not found"}"#)
        await model.loadGallery()
        XCTAssertNil(model.gallery, "a tafs cloud without the gallery: + New opens Coach as before")
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
    }
}
