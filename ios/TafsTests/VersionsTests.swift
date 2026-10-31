import XCTest
@testable import Tafs

/// A taf's versions on its page (kiff/tafs#107): listed like app builds, tried with KIFF
/// deciding each write, and made live with one Face ID.
@MainActor
final class VersionsTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    /// A model signed in on this phone (account t_01) whose one taf is Rae, depl_1 on tafs cloud.
    private func kiffModel(signer: ApprovalSigning) async -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud)
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x", tenant: "t_01")
        let rae = model.tafs.first { $0.name == "Rae" }
        return (model, try! XCTUnwrap(rae?.id))
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    private static let begun = #"{"switching":{"n":2,"draft":"d_02","at":"2026-10-20T08:00:00Z"}}"#
    private static let plainSwitch = #"{"switching":{"n":1,"at":"2026-10-20T08:00:00Z"}}"#

    private static let twoVersions = #"{"versions":[{"n":1,"by":"setup","created":"2026-10-19T09:00:00Z","digest":"d1","live":true},{"n":2,"note":"Asks for the order number first","by":"setup","created":"2026-10-20T07:00:00.5Z","digest":"d2","live":false,"draft":"d_02"}]}"#

    /// Coach mode (kiff/tafs#138): opened on a taf, a new conversation takes that taf as its
    /// context; closed and opened again elsewhere, it's as it was; New conversation is on no
    /// taf, even on that taf's page (item 36), and the taf is one tap away.
    func testCoachModeOpensOnTheTafOnScreenAndKeepsAConversation() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        let cloudID = try XCTUnwrap(model.cloudTaf(for: id)?.id)
        model.newSetupChat()
        model.tafOnScreen = id
        model.openCoach()
        XCTAssertTrue(model.coachOpen)
        XCTAssertEqual(model.setupImproving?.id, cloudID, "a new conversation takes the taf on screen")

        model.coachOpen = false
        model.tafOnScreen = nil
        model.openCoach()
        XCTAssertEqual(model.setupImproving?.id, cloudID, "closing kept it, and ✦ reopens it")

        model.newCoachConversation()
        XCTAssertNil(model.setupImproving, "a new one anywhere else has no taf in mind")

        // On the taf's page, New conversation is still on no taf: "I want a taf that…" there
        // made the open taf's next version (kiff/tafs#138, item 36).
        model.tafOnScreen = id
        model.newCoachConversation()
        XCTAssertNil(model.setupImproving, "New conversation on a taf's page is on no taf")
        model.improveTaf(id)
        XCTAssertEqual(model.setupImproving?.id, cloudID, "its offer improves the taf on screen")
    }

    /// Start a new taf opens Coach mode in a new conversation on no taf, even from a taf's
    /// page (kiff/tafs#138, item 59): the old form is gone.
    func testStartANewTafIsANewCoachConversation() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        model.improveTaf(id)
        model.tafOnScreen = id
        model.startNewTaf()
        XCTAssertTrue(model.coachOpen)
        XCTAssertNil(model.setupImproving, "a new taf, not the one on screen")
        XCTAssertTrue(model.setupThread.messages.isEmpty)
    }

    func testLoadsATafsVersions() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        await model.loadVersions(id)
        let list = try XCTUnwrap(model.versions[id])
        XCTAssertEqual(list.map(\.n), [1, 2])
        XCTAssertNil(list[0].note, "a version with no note still loads")
        XCTAssertTrue(list[0].live)
        XCTAssertEqual(list[1].draft, "d_02")
        XCTAssertNil(model.versionErrors[id])
        XCTAssertNil(model.switches[id])

        // A switch that didn't finish shows, to finish or cancel.
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, #"{"versions":[],"switching":{"n":2,"draft":"d_02","at":"2026-10-20T08:00:00Z"}}"#)
        await model.loadVersions(id)
        XCTAssertEqual(model.switches[id]?.n, 2)
    }

    func testAVersionKiffAgentsWroteNamesNoOne() {
        let at = Date(timeIntervalSince1970: 0)
        let upgrade = TafsCloud.Version(n: 3, note: "Its rules were updated", by: nil, created: at, live: true, draft: nil)
        XCTAssertFalse(VersionsPanel.byline(upgrade).contains("By"))
        let setup = TafsCloud.Version(n: 2, note: nil, by: "setup", created: at, live: false, draft: "d_02")
        XCTAssertTrue(VersionsPanel.byline(setup).hasPrefix("By Coach mode · "))
        XCTAssertTrue(VersionsPanel.byline(setup).hasSuffix(" · brings a new Card"))
    }

    /// A version that brings a Card revision: the switch begins (nothing of the taf runs
    /// from then), one Face ID confirms its draft in KIFF, the version shown, and only then
    /// is it made live.
    func testUsingAVersionConfirmsItsCardThenSwitches() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"confirmed","version":"v7"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/live"] = (200, #"{"live":2}"#)
        await model.loadVersions(id)
        let v2 = try XCTUnwrap(model.versions[id]?.last)

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        XCTAssertEqual(signer.signed.first, "tafs.cards.v1|t_01|d_02|v7")
        let confirm = try XCTUnwrap(sent("POST", "/v1/me/agent-drafts/d_02/confirm").first)
        XCTAssertNotNil(confirm.value(forHTTPHeaderField: "KIFF-Device-Signature"))
        XCTAssertEqual(sent("POST", "/v1/tafs/depl_1/versions/2/live").count, 1)
        let order = StubProtocol.sent.compactMap(\.url?.path)
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: "/v1/tafs/depl_1/versions/2/switch")),
                          try XCTUnwrap(order.firstIndex(of: "/v1/me/agent-drafts/d_02/confirm")), "the switch begun before the Card changes")
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: "/v1/me/agent-drafts/d_02/confirm")),
                          try XCTUnwrap(order.firstIndex(of: "/v1/tafs/depl_1/versions/2/live")), "the Card first, then the switch")
        XCTAssertNil(model.kiff.setupSigner)
        XCTAssertNil(model.switchingVersion[id])
        // Its Card changed the taf's limits: the list is read again once it's live, so the
        // taf's page shows the new Card without reopening the app.
        let live = try XCTUnwrap(order.lastIndex(of: "/v1/tafs/depl_1/versions/2/live"))
        XCTAssertTrue(order[live...].contains("/v1/home"), "the list read again after the install")
    }

    /// Back to a version with the taf's current Cards: one Face ID approves the switch itself.
    func testGoingBackToAVersionWithoutACardAsksOnce() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/switch"] = (200, Self.plainSwitch)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/live"] = (200, #"{"live":1}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v1 = TafsCloud.Version(n: 1, note: nil, by: "setup", created: Date(), live: false, draft: nil)

        let done = await model.useVersion(v1, of: id)
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        XCTAssertEqual(signer.signed, ["tafs.version.v1|t_01|depl_1|1"])
        XCTAssertTrue(StubProtocol.sent.filter { $0.url?.path.hasPrefix("/v1/me/agent-drafts") ?? false }.isEmpty)
        XCTAssertEqual(sent("POST", "/v1/tafs/depl_1/versions/1/live").count, 1)
        // The Card didn't change, so there's no need to read the list again.
        let order = StubProtocol.sent.compactMap(\.url?.path)
        let live = try XCTUnwrap(order.lastIndex(of: "/v1/tafs/depl_1/versions/1/live"))
        XCTAssertFalse(order[live...].contains("/v1/home"))
    }

    /// A draft already claimed has its Cards: the switch is approved, not the draft again.
    func testAClaimedDraftIsNotConfirmedAgain() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"claimed","version":"v7"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/live"] = (200, #"{"live":2}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        XCTAssertEqual(signer.signed, ["tafs.version.v1|t_01|depl_1|2"])
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
    }

    /// A version that brings a Card with nothing shown: nothing begins, nothing is signed.
    func testNothingShownNothingSigned() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        let done = await model.useVersion(v2, of: id)
        XCTAssertFalse(done)
        XCTAssertTrue(signer.signed.isEmpty)
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions/2/live").isEmpty)
    }

    /// The Card changed between the screen and the tap (kiff/tafs#115): the new one goes on
    /// screen, the switch is dropped, and nothing is signed or confirmed.
    func testACardChangedSinceShownIsNotSigned() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v8"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/switching"] = (200, #"{"cancelled":true}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertTrue(signer.signed.isEmpty, "no Face ID over terms that weren't shown")
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
        XCTAssertEqual(sent("DELETE", "/v1/tafs/depl_1/switching").count, 1)
        XCTAssertEqual(model.changedVersionCards[id]?.version, "v8")
        XCTAssertTrue(model.versionErrors[id]?.contains("changed since it was shown") ?? false)
    }

    /// The Card a version brings reads now → after.
    func testAVersionsCardReadsNowAndAfter() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7","cards":[{"revises":"m_refund","current":{"grants":[{"action":"create_refund","per_action_max":[{"quantity":"sum(amount)","max":500,"unit":"EUR","scale":2}]}]},"grants":[{"action":"create_refund","per_action_max":[{"quantity":"sum(amount)","max":2000,"unit":"EUR","scale":2}]}]},{"grants":[{"action":"retrieve_charge"}]}]}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")
        let draft = await model.versionCard(v2, of: id)
        let cards = try XCTUnwrap(draft?.cards)
        XCTAssertEqual(cards.count, 2)
        XCTAssertEqual(cards[0].revises, "m_refund")
        XCTAssertEqual(cards[0].current?.lines.count, 1)
        XCTAssertNotEqual(cards[0].current?.lines, cards[0].lines, "now and after differ")
        XCTAssertNil(cards[1].current, "a new Card has no now")
    }

    /// Coach's thread with one improvement result of Rae's, answered by tafs cloud with `answer`
    /// (none: not answered yet).
    private func coachThread(_ model: AppModel, answer: String?) async {
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":{"id":"taf-setup","name":"Coach","job":"setup","kiff_agent":"setup","status":"active","next_run":null,"last_run":null}}"#)
        let answered = answer.map { #","answer":\#($0)"# } ?? ""
        StubProtocol.responses["GET /v1/tafs/taf-setup/conversation"] = (200, #"{"messages":[],"components":[{"at":"2026-10-31T10:00:06.000Z","session":"s1","run":"r1","seq":6,"component":{"type":"result","id":"v2","text":"Ready as version 2.","taf":{"agent":"rae-k3f9","name":"Rae","job":"refunds","improves":"depl_1","note":"Asks first.","draft":"d_02"}}\#(answered)}]}"#)
        await model.loadSetup()
    }

    /// An improvement's version is tafs cloud's: it makes it in the hand-over and answers the
    /// card with it (kiff/tafs#391). The phone makes none and nothing goes live; it reads the
    /// taf's Versions once, so the version is there to try.
    func testAnImprovementsVersionIsTafsCloudsAndThePhoneOnlyShowsIt() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        StubProtocol.sent = []
        await coachThread(model, answer: #"{"state":"answered","by":"tafs cloud","outcome":{"version":2}}"#)
        XCTAssertEqual(model.setupThread.improvement("v2"), .made(2))
        XCTAssertEqual(model.improvedTaf(model.setupThread.components[0].component), id)
        for _ in 0..<50 where model.versions[id] == nil { await Task.yield(); try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertNotNil(model.versions[id], "the taf's Versions read again")
        await coachThread(model, answer: #"{"state":"answered","by":"tafs cloud","outcome":{"version":2}}"#)
        for _ in 0..<5 { await Task.yield() }
        XCTAssertEqual(sent("GET", "/v1/tafs/depl_1/versions").count, 1, "once per version made")
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions").isEmpty, "the phone makes no version")
        XCTAssertTrue(sent("POST", "/v1/tafs").isEmpty, "an improvement never starts a new taf")
    }

    /// One tafs cloud couldn't make says why, as a new taf's hand-over does; one not yet
    /// answered is being prepared.
    func testAnImprovementTafsCloudCouldntMakeSaysWhyAndOneNotYetAnsweredIsPreparing() async throws {
        let (model, _) = await kiffModel(signer: StubSigner())
        await coachThread(model, answer: nil)
        XCTAssertEqual(model.setupThread.improvement("v2"), .preparing)
        await coachThread(model, answer: #"{"state":"answered","by":"tafs cloud","outcome":{"failed":"You can't manage this taf's Cards."}}"#)
        XCTAssertEqual(model.setupThread.improvement("v2"), .failed("You can't manage this taf's Cards."))
        XCTAssertEqual(SetupThread.notImproved("Refunds", "You can't manage this taf's Cards."), "Refunds' new version wasn't made: You can't manage this taf's Cards.")
        XCTAssertEqual(SetupThread.notImproved("Rae", "no draft"), "Rae's new version wasn't made: no draft.")
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions").isEmpty)
    }

    /// A Card reads as unchanged only when KIFF says its full terms are kept (kiff/tafs#138):
    /// the same lines aren't enough, since they leave terms out (policy, expiry, scopes).
    func testOnlyKIFFSaysACardIsUnchanged() throws {
        let terms = #""grants":[{"action":"refund","per_action_max":[{"quantity":"sum(amount)","max":500}]}],"current":{"grants":[{"action":"refund","per_action_max":[{"quantity":"sum(amount)","max":500}]}]}"#
        func card(_ extra: String) throws -> KIFFClient.DraftCard {
            try JSONDecoder().decode(KIFFClient.DraftCard.self, from: Data(#"{"id":"card_rae","revises":"card_rae",\#(extra)\#(terms)}"#.utf8))
        }
        XCTAssertTrue(try card(#""unchanged":true,"#).isUnchanged)
        XCTAssertFalse(try card("").isUnchanged, "the same lines alone read now → after")
        let fresh = try JSONDecoder().decode(KIFFClient.DraftCard.self, from: Data(#"{"id":"card_read","unchanged":true,"grants":[{"action":"retrieve_charge"}]}"#.utf8))
        XCTAssertFalse(fresh.isUnchanged, "a new Card isn't unchanged")
    }

    /// A connection none of whose tools is on the taf's Card any more is left over
    /// (kiff/tafs#138): the owner's broken read_payment after version 3 moved its Card to
    /// read_payment_by_id. One with a tool still on the Card isn't, and nothing is said
    /// before the Card is read.
    func testAConnectionTheCardNoLongerUsesIsLeftOver() {
        let card = ["create_refund", "read_payment_by_id", "list_stripe_accounts"]
        XCTAssertTrue(AppModel.leftOver(tools: ["read_payment"], onCard: card))
        XCTAssertFalse(AppModel.leftOver(tools: ["read_payment_by_id", "list_stripe_accounts"], onCard: card))
        XCTAssertFalse(AppModel.leftOver(tools: ["create_refund", "read_payment"], onCard: card), "one tool still on the Card")
        XCTAssertFalse(AppModel.leftOver(tools: ["read_payment"], onCard: []), "the Card not read yet")
    }

    /// Going back to a version that didn't hold a Card (kiff/tafs#107): KIFF's restore draft
    /// lists it as retired, with its terms now, and the sheet reads them as it does a Card.
    func testARestoreDraftReadsTheCardsItRetires() throws {
        let json = #"{"id":"drf_restore1","agent_id":"rae-k3f9","status":"draft","version":"v9","revision":true,"cards":[{"id":"card_rae","revises":"card_rae","grants":[{"action":"refund","per_action_max":[{"quantity":"sum(amount)","max":500}]}],"current":{"grants":[{"action":"refund","per_action_max":[{"quantity":"sum(amount)","max":1000}]}]}}],"retires":[{"id":"card_read","revision":2,"current":{"grants":[{"action":"retrieve_charge"}],"aggregates":[{"quantity":"count","limit":200,"window":"calendar_day"}]}}]}"#
        let d = try JSONDecoder().decode(KIFFClient.AgentDraft.self, from: Data(json.utf8))
        let retired = try XCTUnwrap(d.retires?.first)
        XCTAssertEqual(retired.id, "card_read")
        XCTAssertFalse(retired.current?.lines.isEmpty ?? true, "its terms now, to read before confirming")
        // A draft from before retires decodes with none.
        let plain = try JSONDecoder().decode(KIFFClient.AgentDraft.self, from: Data(#"{"id":"d","agent_id":"a","status":"draft","version":"v"}"#.utf8))
        XCTAssertNil(plain.retires)
    }

    /// Going back (kiff/tafs#122): tafs cloud prepares a draft restoring the Cards the
    /// version ran under; the owner reads that one, and Face ID confirms exactly it.
    func testGoingBackReadsAndConfirmsTheRestoreDraft() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/switch"] = (200, #"{"switching":{"n":1,"draft":"drf_restore1","at":"2026-10-20T08:00:00Z"}}"#)
        StubProtocol.responses["GET /v1/me/agent-drafts/drf_restore1"] = (200, #"{"id":"drf_restore1","agent_id":"rae-k3f9","status":"draft","version":"v9","revision":true}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/drf_restore1/confirm"] = (200, #"{"id":"drf_restore1","agent_id":"rae-k3f9","status":"confirmed","version":"v9"}"#)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/live"] = (200, #"{"live":1}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v1 = TafsCloud.Version(n: 1, note: nil, by: "setup", created: Date(), live: false, draft: nil)

        let sw = await model.prepareSwitch(v1, of: id)
        XCTAssertEqual(sw?.draft, "drf_restore1")
        let shown = await model.versionCard(v1, of: id)
        XCTAssertEqual(shown?.id, "drf_restore1", "the sheet reads the draft the switch waits for")
        let done = await model.useVersion(v1, of: id, shownDraft: shown?.version)
        XCTAssertTrue(done, model.versionErrors[id] ?? "")
        // One Face ID signs exactly the restore draft shown; its confirmation then signs
        // KIFF's request (signConfirmed, no second Face ID): the signer records both.
        XCTAssertEqual(signer.signed.count, 2, "\(signer.signed)")
        XCTAssertEqual(signer.signed.first, "tafs.cards.v1|t_01|drf_restore1|v9")
        XCTAssertTrue(signer.signed.last?.hasPrefix("kiff.request.v1\nt_01\nPOST\n/v1/me/agent-drafts/drf_restore1/confirm\n") ?? false, "\(signer.signed)")
        XCTAssertEqual(sent("POST", "/v1/me/agent-drafts/drf_restore1/confirm").count, 1)
    }

    /// A run in the way of an install is named, with one step that stops it and installs
    /// (kiff/tafs#206).
    func testARunInTheWayIsNamedAndStoppedInOneStep() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (409, #"{"error":"a run of this agent is going or waiting on its owner","blocking":[{"run":"r1","session":"s1","status":"PAUSED","trigger":"owner"}]}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")
        let blocked = await model.prepareSwitch(v2, of: id)
        XCTAssertNil(blocked)
        XCTAssertEqual(model.blockedInstalls[id], 2)
        XCTAssertEqual(model.versionErrors[id], "Version 2 can't go live while a task waits on your answer. Stop it and install, or wait until it's done.")

        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        let sw = await model.prepareSwitch(v2, of: id, stopBlocking: true)
        XCTAssertNotNil(sw)
        let body = try XCTUnwrap(sent("POST", "/v1/tafs/depl_1/versions/2/switch").last?.httpBody)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: Bool], ["redraft": false, "stop_blocking": true])
        XCTAssertNil(model.blockedInstalls[id])
        XCTAssertNil(model.versionErrors[id])
    }

    /// KIFF refuses a Card that went stale at confirm: the owner is asked to draft it again.
    func testAStaleCardIsDraftedAgain() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (409, #"{"error":"the agent's Cards changed since this draft was made; draft it again","reason":"card_revised"}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")
        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertEqual(model.staleVersionCards[id], 2)
        XCTAssertTrue(model.versionErrors[id]?.contains("Draft it again") ?? false)

        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, #"{"switching":{"n":2,"draft":"drf_again1","at":"2026-10-20T08:00:00Z"}}"#)
        let again = await model.prepareSwitch(v2, of: id, redraft: true)
        XCTAssertEqual(again?.draft, "drf_again1")
        let body = try XCTUnwrap(sent("POST", "/v1/tafs/depl_1/versions/2/switch").last?.httpBody)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: Bool], ["redraft": true, "stop_blocking": false])
        XCTAssertNil(model.staleVersionCards[id])
    }

    /// Another conflict at confirm isn't a stale Card: only KIFF's reason card_revised offers
    /// to draft it again, not the words of its message.
    func testAnotherConflictAtConfirmIsntAStaleCard() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (409, #"{"error":"the agent's Cards changed since this draft was made; draft it again"}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")
        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertNil(model.staleVersionCards[id])
        XCTAssertTrue(model.versionErrors[id]?.contains("didn't finish") ?? false)
    }

    /// No Card to change, and the owner backs out at Face ID: the switch it began is dropped.
    func testBackingOutOfAPlainSwitchDropsIt() async throws {
        let (model, id) = await kiffModel(signer: BackingOutSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/switch"] = (200, Self.plainSwitch)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/switching"] = (200, #"{"cancelled":true}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v1 = TafsCloud.Version(n: 1, note: nil, by: "setup", created: Date(), live: false, draft: nil)
        let done = await model.useVersion(v1, of: id)
        XCTAssertFalse(done)
        XCTAssertEqual(sent("DELETE", "/v1/tafs/depl_1/switching").count, 1)
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions/1/live").isEmpty)
    }

    /// The owner backs out at Face ID: the switch is dropped, so the taf runs again, and
    /// nothing reaches KIFF.
    func testBackingOutAtFaceIDDropsTheSwitch() async throws {
        let (model, id) = await kiffModel(signer: BackingOutSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/switching"] = (200, #"{"cancelled":true}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/versions"] = (200, Self.twoVersions)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertEqual(sent("DELETE", "/v1/tafs/depl_1/switching").count, 1)
        XCTAssertTrue(sent("POST", "/v1/me/agent-drafts/d_02/confirm").isEmpty)
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions/2/live").isEmpty)
        XCTAssertNil(model.switches[id])
    }

    /// KIFF doesn't confirm the Card: the switch stays begun (the taf doesn't run), and the
    /// page says it didn't finish, to finish or cancel.
    func testASwitchThatStopsHalfWayStaysToFinish() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (200, Self.begun)
        StubProtocol.responses["GET /v1/me/agent-drafts/d_02"] = (200, #"{"id":"d_02","agent_id":"rae-k3f9","status":"draft","version":"v7"}"#)
        StubProtocol.responses["POST /v1/me/agent-drafts/d_02/confirm"] = (502, #"{"error":"try again"}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertEqual(model.switches[id]?.n, 2)
        XCTAssertTrue(model.versionErrors[id]?.contains("didn't finish") ?? false, model.versionErrors[id] ?? "")
        XCTAssertTrue(sent("POST", "/v1/tafs/depl_1/versions/2/live").isEmpty)
        XCTAssertTrue(sent("DELETE", "/v1/tafs/depl_1/switching").isEmpty, "a Card that may be confirmed is never dropped from here")
    }

    /// A run of the taf is going: the switch doesn't begin, and Face ID isn't asked.
    func testNoSwitchWhileARunGoes() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/2/switch"] = (409, #"{"error":"a run of this agent is going or waiting on its owner: answer it, stop it or let it finish, then switch"}"#)
        let v2 = TafsCloud.Version(n: 2, note: nil, by: "setup", created: Date(), live: false, draft: "d_02")

        let done = await model.useVersion(v2, of: id, shownDraft: "v7")
        XCTAssertFalse(done)
        XCTAssertTrue(signer.signed.isEmpty)
        XCTAssertTrue(model.versionErrors[id]?.contains("answer it, stop it or let it finish") ?? false)
    }

    /// tafs cloud refuses the switch while the Card isn't confirmed: the page says so.
    func testARefusedSwitchSaysWhy() async throws {
        let (model, id) = await kiffModel(signer: StubSigner())
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/switch"] = (200, Self.plainSwitch)
        StubProtocol.responses["POST /v1/tafs/depl_1/versions/1/live"] = (409, #"{"error":"confirm this version's Card first"}"#)
        let v1 = TafsCloud.Version(n: 1, note: nil, by: "setup", created: Date(), live: false, draft: nil)
        let done = await model.useVersion(v1, of: id)
        XCTAssertFalse(done)
        XCTAssertTrue(model.versionErrors[id]?.contains("confirm this version's Card first") ?? false)
    }

    func testTheLiveVersionIsNotSwitchedAgain() async throws {
        let signer = RecordingSigner()
        let (model, id) = await kiffModel(signer: signer)
        let live = TafsCloud.Version(n: 1, note: nil, by: "setup", created: Date(), live: true, draft: nil)
        let done = await model.useVersion(live, of: id)
        XCTAssertFalse(done)
        XCTAssertTrue(signer.signed.isEmpty)
    }
}

/// The owner backs out at the Face ID prompt.
struct BackingOutSigner: ApprovalSigning {
    struct Cancelled: Error {}
    var publicKeyDER: Data { Data([0x30, 0x59]) }
    func sign(_ message: Data, reason: String) async throws -> String { throw Cancelled() }
    func signConfirmed(_ message: Data) throws -> String { throw Cancelled() }
}
