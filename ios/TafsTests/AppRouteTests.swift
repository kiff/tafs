import XCTest
@testable import Tafs

/// The app map (app/routes.json): every route opens its screen through `AppModel.open(route:)`,
/// and a path that names nothing, or a screen the person can't use, opens nothing.
@MainActor
final class AppRouteTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount); Keychain.delete(AppModel.tenantAccount); Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    /// Signed in to KIFF with Rae (depl_1, acting as rae-k3f9) on tafs cloud; by default an admin
    /// who manages Rae.
    private func model(capabilities: String = #"["members.manage","cards.manage","billing.manage","account.delete"]"#,
                       manages: Bool = true) async throws -> (AppModel, UUID) {
        StubProtocol.responses = [:]; StubProtocol.sent = []; StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(), kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/me"] = (200, #"{"tenant_id":"t_01","tenant_slug":"acme","capabilities":\#(capabilities)}"#)
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"rae-k3f9":{"use":true,"oversee":true,"manage":\#(manages),"groups":[]}}}"#)
        await model.useSession("synthetic-session", tenant: "t_01")
        await model.refreshPeople()
        return (model, try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id))
    }

    /// What the views are asked to show.
    private struct Requests: Equatable {
        var tab: Tab?
        var tafPage: AppModel.TafPageRequest?
        var accountPage: AppModel.AccountPage?
        var calendar = false
        var usage = false
        var halt = false
        var haltTaf: UUID?
        var coach = false
    }

    private func requests(_ model: AppModel) -> Requests {
        Requests(tab: model.requestedTab, tafPage: model.requestedTafPage, accountPage: model.requestedAccountPage,
                 calendar: model.calendarRequested, usage: model.usageRequested, halt: model.haltRequested,
                 haltTaf: model.haltRequestedTaf, coach: model.coachOpen)
    }

    private func clear(_ model: AppModel) {
        model.requestedTab = nil; model.requestedTafPage = nil; model.requestedAccountPage = nil
        model.calendarRequested = false; model.usageRequested = false
        model.haltRequested = false; model.haltRequestedTaf = nil; model.coachOpen = false
    }

    /// Each route's path, its `{taf}` filled with `taf`.
    private func path(_ route: AppRoute, taf: String) -> String {
        route.rawValue.replacingOccurrences(of: "{taf}", with: taf)
    }

    func testEveryRouteOpensItsScreen() async throws {
        let (model, rae) = try await model()
        XCTAssertTrue(model.manages(rae))
        XCTAssertNotNil(model.cloudTaf(for: rae))
        func taf(_ page: AppModel.TafPage) -> Requests {
            Requests(tab: .tafs, tafPage: AppModel.TafPageRequest(taf: rae, page: page))
        }
        func account(_ page: AppModel.AccountPage) -> Requests { Requests(tab: .account, accountPage: page) }
        let expected: [AppRoute: Requests] = [
            .tafs: Requests(tab: .tafs),
            .needsYou: Requests(tab: .needsYou),
            .record: Requests(tab: .record),
            .calendar: Requests(tab: .tafs, calendar: true),
            .coach: Requests(coach: true),
            .haltAll: Requests(halt: true),
            .account: Requests(tab: .account),
            .accountPeople: account(.people),
            .accountDevices: account(.devices),
            .accountApps: account(.apps),
            .accountUsage: Requests(usage: true),
            .accountUsageRequests: Requests(usage: true),
            .accountAlerts: account(.alerts),
            .accountTimeZone: account(.timeZone),
            .accountLanguage: Requests(tab: .account),
            .accountTheme: Requests(tab: .account),
            .accountDelete: account(.deleteAccount),
            .taf: taf(.chat),
            .tafActivity: taf(.activity),
            .tafCard: taf(.card),
            .tafSetup: taf(.setup),
            .tafSetupStartsWhen: taf(.startsWhen),
            .tafSetupResultsGoTo: taf(.resultsGoTo),
            .tafSetupVersions: taf(.versions),
            .tafWorkflow: taf(.workflow),
            .tafMemory: taf(.memory),
            .tafHalt: Requests(halt: true, haltTaf: rae),
            .tafShare: taf(.share),
        ]
        XCTAssertEqual(Set(expected.keys), Set(AppRoute.allCases), "every route has its expected screen")
        for route in AppRoute.allCases {
            clear(model)
            XCTAssertTrue(model.open(route: path(route, taf: "depl_1")), route.rawValue)
            XCTAssertEqual(requests(model), expected[route], route.rawValue)
        }
    }

    /// `{taf}` names a taf by tafs cloud's id, its KIFF agent or the app's own id.
    func testATafIsNamedByAnyOfItsIds() async throws {
        let (model, rae) = try await model()
        for name in ["depl_1", "rae-k3f9", rae.uuidString] {
            clear(model)
            XCTAssertTrue(model.open(route: "taf/\(name)/card"), name)
            XCTAssertEqual(model.requestedTafPage, AppModel.TafPageRequest(taf: rae, page: .card), name)
        }
    }

    func testAPathThatNamesNothingOpensNothing() async throws {
        let (model, _) = try await model()
        clear(model)
        let before = requests(model)
        for path in ["", "nowhere", "account/nowhere", "taf", "taf/", "taf/depl_1/nowhere", "/tafs", "tafs/"] {
            XCTAssertFalse(model.open(route: path), path)
            XCTAssertEqual(requests(model), before, path)
        }
    }

    func testATafTheAppDoesntHoldOpensNothing() async throws {
        let (model, _) = try await model()
        clear(model)
        let before = requests(model)
        for path in ["taf/depl_9", "taf/depl_9/card", "taf/\(UUID().uuidString)/setup", "taf/setup/activity"] {
            XCTAssertFalse(model.open(route: path), path)
            XCTAssertEqual(requests(model), before, path)
        }
    }

    /// A member who doesn't manage Rae, may not use Coach and may not delete the account.
    func testAScreenThePersonCantUseOpensNothing() async throws {
        let (model, rae) = try await model(capabilities: #"["cards.manage"]"#, manages: false)
        XCTAssertFalse(model.manages(rae))
        clear(model)
        let before = requests(model)
        for path in ["account/delete", "coach", "taf/depl_1/setup/versions", "taf/depl_1/setup/starts-when",
                     "taf/depl_1/halt", "taf/depl_1/share"] {
            XCTAssertFalse(model.open(route: path), path)
            XCTAssertEqual(requests(model), before, path)
        }
        XCTAssertTrue(model.open(route: "taf/depl_1/card"), "a taf they use still opens")
        XCTAssertTrue(model.open(route: "account/people"), "People & groups is for anyone signed in; edits are gated inside it")
        XCTAssertEqual(model.requestedAccountPage, .people)
    }

    /// The sample: its tafs' pages open; KIFF's screens and a manager's don't.
    func testTheSampleOpensOnlyItsOwnScreens() async throws {
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        let taf = try XCTUnwrap(model.tafs.first?.id)
        clear(model)
        let before = requests(model)
        for path in ["calendar", "coach", "account/devices", "account/usage", "account/delete",
                     "taf/\(taf.uuidString)/setup/versions", "taf/\(taf.uuidString)/halt", "taf/\(taf.uuidString)/workflow"] {
            XCTAssertFalse(model.open(route: path), path)
            XCTAssertEqual(requests(model), before, path)
        }
        XCTAssertTrue(model.open(route: "taf/\(taf.uuidString)/memory"))
        XCTAssertEqual(model.requestedTafPage, AppModel.TafPageRequest(taf: taf, page: .memory))
        XCTAssertTrue(model.open(route: "account/theme"))
        XCTAssertEqual(model.requestedTab, .account)
    }

    func testSignedOutOpensNothing() {
        let model = AppModel(signer: StubSigner())
        XCTAssertFalse(model.open(route: "tafs"))
        XCTAssertNil(model.requestedTab)
    }

    /// Each route's pattern, with a value in place of each of its own, matches that route and
    /// gives that value back.
    func testMatchRoundTripsEveryRoute() {
        for route in AppRoute.allCases {
            var path = route.rawValue
            var values: [String: String] = [:]
            for name in route.parameters {
                path = path.replacingOccurrences(of: "{\(name)}", with: "v-\(name)")
                values[name] = "v-\(name)"
            }
            let match = AppRoute.match(path)
            XCTAssertEqual(match?.route, route, path)
            XCTAssertEqual(match?.values, values, path)
        }
    }
}
