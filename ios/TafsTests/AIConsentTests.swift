import XCTest
@testable import Tafs

/// The AI-data consent (docs/accounts.md, "Your data and the AI"): tafs cloud's words, drawn as
/// sent; Allow sends the version read; Not now closes the screen until a refused turn asks again;
/// Withdraw from Account; and a refused turn, from any route, brings the screen up there and then.
@MainActor
final class AIConsentTests: XCTestCase {
    private static func answer(given: Bool, version: String = "1") -> String {
        #"{"consent":{"given":\#(given),\#(given ? #""at":"2026-11-13T09:00:00Z","# : "")"version":"\#(version)","words":{"title":"Your data and the AI","lines":["tafs uses Anthropic's Claude AI.","What's never sent: the passwords and keys your connected apps use."],"allow":"Allow","not_now":"Not now","withdraw":"Withdraw","given":"AI use: allowed"}}}"#
    }
    private static let refused = #"{"error":"Allow AI use first: Coach and your tafs need it to answer.","code":"ai_consent_required","consent":\#(String(answer(given: false).dropFirst(11).dropLast()))}"#

    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private func signedIn() async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me/ai-consent"] = (200, Self.answer(given: false))
        model.watchAIConsent()
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return model
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    func testTheConsentReadsAsTafsCloudSendsItAndAScreenNeedsItsWords() throws {
        struct A: Decodable { let consent: TafsCloud.AIConsent }
        let c = try JSONDecoder().decode(A.self, from: Data(Self.answer(given: true).utf8)).consent
        XCTAssertTrue(c.given)
        XCTAssertEqual(c.version, "1")
        XCTAssertEqual(c.words.notNow, "Not now")
        XCTAssertEqual(c.words.given, "AI use: allowed")
        XCTAssertTrue(c.drawable)
        let bare = TafsCloud.AIConsent(given: false, version: "1", words: .init(title: " ", lines: ["x"], allow: "Allow", notNow: "Not now", withdraw: "", given: ""))
        XCTAssertFalse(bare.drawable, "no title: no screen, and no words of the app's own")
        XCTAssertFalse(TafsCloud.AIConsent(given: false, version: "1", words: .init(title: "T", lines: ["x"], allow: "Allow", notNow: " ", withdraw: "", given: "")).drawable,
                       "no Not now: no screen, as on Android")
        XCTAssertFalse(TafsCloud.AIConsent(given: false, version: "1", words: .init(title: "T", lines: [], allow: "Allow", notNow: "", withdraw: "", given: "")).drawable)
    }

    /// After sign-in, a consent not given puts its screen up. Not now closes it, and a refresh
    /// doesn't put it up again; a turn tafs cloud refuses does, from any route.
    func testNotNowClosesTheScreenUntilARefusedTurnAsksAgain() async throws {
        let model = await signedIn()
        XCTAssertEqual(model.aiConsentAsked?.version, "1", "asked after sign-in")
        model.notNowAI()
        XCTAssertNil(model.aiConsentAsked)
        await model.refresh()
        XCTAssertNil(model.aiConsentAsked, "a refresh doesn't ask again after Not now")
        XCTAssertTrue(sent("POST", "/v1/me/ai-consent").isEmpty, "Not now sends nothing")

        // Any refusal for want of it: the screen comes up, with the words sent with the refusal.
        let error = TafsCloud.refusal(403, Data(Self.refused.utf8))
        XCTAssertEqual(error.code, "ai_consent_required")
        XCTAssertEqual(error.consent?.words.allow, "Allow")
        XCTAssertEqual(error.errorDescription, "Allow AI use first: Coach and your tafs need it to answer.")
        let shown = expectation(description: "the screen")
        Task { @MainActor in
            for _ in 0..<50 where model.aiConsentAsked == nil { try? await Task.sleep(nanoseconds: 20_000_000) }
            shown.fulfill()
        }
        await fulfillment(of: [shown], timeout: 2)
        XCTAssertEqual(model.aiConsentAsked?.version, "1", "a refused turn asks again")
        // A consent that doesn't read leaves the rest of the refusal as it was.
        let odd = TafsCloud.refusal(403, Data(#"{"error":"no","code":"ai_consent_required","consent":{"given":false}}"#.utf8))
        XCTAssertNil(odd.consent)
        XCTAssertEqual(odd.code, "ai_consent_required")
    }

    /// Allow sends the version on the screen and closes it; an older screen's yes is refused by
    /// tafs cloud, and the new words show in its place.
    func testAllowSendsTheVersionReadAndANewerOneTakesItsPlace() async throws {
        let model = await signedIn()
        StubProtocol.responses["POST /v1/me/ai-consent"] = (200, Self.answer(given: true))
        await model.allowAI()
        let body = try XCTUnwrap(sent("POST", "/v1/me/ai-consent").last?.httpBody)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: String], ["version": "1"])
        XCTAssertNil(model.aiConsentAsked)
        XCTAssertEqual(model.aiConsent?.given, true)

        model.consentRequired(try XCTUnwrap(model.aiConsent).withGiven(false))
        let newer = #"{"error":"the consent's words changed: read them again","consent":\#(String(Self.answer(given: false, version: "2").dropFirst(11).dropLast()))}"#
        StubProtocol.responses["POST /v1/me/ai-consent"] = (409, newer)
        await model.allowAI()
        XCTAssertEqual(model.aiConsentAsked?.version, "2", "the newer words, to read and allow")
        XCTAssertNotNil(model.aiConsentError)
    }

    /// Withdraw, from Account: tafs cloud's answer is kept, and the screen isn't put up by a refresh.
    func testWithdrawKeepsTafsCloudsAnswer() async throws {
        let model = await signedIn()
        model.notNowAI()
        StubProtocol.responses["DELETE /v1/me/ai-consent"] = (200, Self.answer(given: false))
        await model.withdrawAI()
        XCTAssertEqual(sent("DELETE", "/v1/me/ai-consent").count, 1)
        XCTAssertEqual(model.aiConsent?.given, false)
        XCTAssertNil(model.aiConsentAsked)
    }

    /// A tafs cloud without the route asks nothing, and Account shows no row.
    func testATafsCloudWithoutTheRouteAsksNothing() async throws {
        let model = await signedIn()
        model.notNowAI()
        StubProtocol.responses["GET /v1/me/ai-consent"] = (404, #"{"error":"not found"}"#)
        let fresh = await signedInAgain(model)
        XCTAssertNil(fresh.aiConsent)
        XCTAssertNil(fresh.aiConsentAsked)
    }

    private func signedInAgain(_ model: AppModel) async -> AppModel {
        await model.useSession("kiff_dev_y", tenant: "t_02")
        return model
    }

    /// A card's answer refused for want of the consent stays to send again: the card isn't marked
    /// answered, gets no "didn't reach" note of its own (the screen says why), and a questions
    /// card keeps what was chosen, since nothing clears it.
    func testARefusedCardAnswerStaysToSendAgain() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let model = try String(contentsOf: views.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let from = try XCTUnwrap(model.range(of: "func answer(_ component: SetupComponent, _ text: String) async {"))
        let to = try XCTUnwrap(model.range(of: "nonisolated static func answerValue("))
        let answer = model[from.lowerBound..<to.lowerBound]
        let consent = try XCTUnwrap(answer.range(of: "where e.code == TafsCloud.aiConsentRequired {"))
        let next = try XCTUnwrap(answer[consent.upperBound...].range(of: "} catch {"))
        let branch = answer[consent.upperBound..<next.lowerBound]
        XCTAssertTrue(branch.contains("answered.remove(ref)"), "not marked answered")
        XCTAssertFalse(branch.contains("cardNotes"), "no note of its own: the screen says why")
        let card = try String(contentsOf: views.appendingPathComponent("Views/QuestionsCard.swift"), encoding: .utf8)
        XCTAssertEqual(card.components(separatedBy: "selection = ").count, 1, "nothing clears what was chosen")
    }

    /// A message refused for want of the consent goes back in the composer, in Coach and in a
    /// taf's chat, whether it was a new message or one to the run at work: no Not sent row, and
    /// no error line of its own, since the screen says why.
    func testARefusedMessageGoesBackInTheComposer() throws {
        let file = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tafs/App/AppModel.swift"), encoding: .utf8)
        let branches = file.components(separatedBy: "where e.code == TafsCloud.aiConsentRequired {").dropFirst()
        XCTAssertEqual(branches.count, 5, "Coach and a taf's chat, each sent and steered, and a card's answer")
        for branch in branches {
            let body = branch.components(separatedBy: "} catch").first ?? ""
            XCTAssertFalse(body.contains("Unsent"), "no Not sent row: \(body)")
            XCTAssertFalse(body.contains("Errors[") || body.contains("setupError"), "no error line of its own: \(body)")
        }
    }

    /// Every word on the screen and in Account's row is tafs cloud's.
    func testTheScreenHasNoWordsOfTheAppsOwn() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let file = try String(contentsOf: views.appendingPathComponent("AIConsentView.swift"), encoding: .utf8)
        XCTAssertFalse(file.contains("Words."), "no line of the app's own")
        XCTAssertTrue(file.contains("consent.words.allow"))
        XCTAssertTrue(file.contains("consent.words.notNow"))
    }
}

private extension TafsCloud.AIConsent {
    func withGiven(_ given: Bool) -> TafsCloud.AIConsent { TafsCloud.AIConsent(given: given, at: at, version: version, words: words) }
}
