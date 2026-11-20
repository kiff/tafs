import XCTest
@testable import Tafs

/// Signing in with KIFF (kiff/tafs#58): the phone shows a code and polls while the owner
/// approves in the browser, where iOS drops the app's connections.
@MainActor
final class SignInTests: XCTestCase {
    private static let start = #"{"device_code":"dc_1","user_code":"WXYZ-1234","verification_uri":"https://app.kiff.dev/auth/device","verification_uri_complete":"https://app.kiff.dev/auth/device?code=WXYZ-1234","expires_in":600,"interval":5}"#
    private static let token = #"{"access_token":"kiff_dev_new","expires_in":7776000,"tenant":"t_01","subject":"u_1"}"#

    private func model(signer: ApprovalSigning = FaceIDSigner()) -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.dropped = [:]
        StubProtocol.sent = []
        Keychain.delete(AppModel.tokenAccount)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: nil, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: nil, session: session)
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session)
        let model = AppModel(signer: signer, kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: StubVendor())
        // What a new session loads.
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":null}"#)
        model.signInSlice = 1_000_000
        model.signInMinimumInterval = 0
        return model
    }

    private func start() throws -> KIFFClient.DeviceStart {
        try JSONDecoder().decode(KIFFClient.DeviceStart.self, from: Data(Self.start.utf8))
    }

    private func polls() -> Int {
        StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path == "/v1/auth/device/token" }.count
    }

    /// A2: a connection dropped while the owner approves in the browser, or KIFF briefly
    /// failing, doesn't end the sign-in: it polls on until the approval arrives.
    func testADroppedConnectionKeepsPolling() async throws {
        let model = model()
        let poll = "POST /v1/auth/device/token"
        StubProtocol.dropped[poll] = [.networkConnectionLost, .notConnectedToInternet]
        StubProtocol.responses[poll] = (400, #"{"error":"authorization_pending"}"#)
        StubProtocol.thenRespond[poll] = [poll: (503, #"{"error":"unavailable"}"#)]
        // The 503 is served, then the approval.
        let s = try start()
        let finished = Task { try await model.finishSignIn(s, startedAt: Date()) }
        // A poll is counted when it's sent, before its answer is chosen: once the fifth
        // is sent, the fourth was answered 503, so the approval can't pre-empt it.
        while polls() < 5 { try await Task.sleep(nanoseconds: 1_000_000) }
        StubProtocol.responses[poll] = (200, Self.token)
        try await finished.value
        XCTAssertEqual(model.mode, .kiff)
        XCTAssertEqual(model.kiff.token, "kiff_dev_new")
        XCTAssertGreaterThanOrEqual(polls(), 5, "two dropped, one pending, one 503, then the token")
        Keychain.delete(AppModel.tokenAccount)
    }

    /// An expired code still ends it, with the error the owner reads.
    func testAnExpiredCodeEndsIt() async throws {
        let model = model()
        StubProtocol.responses["POST /v1/auth/device/token"] = (400, #"{"error":"expired_token"}"#)
        do {
            try await model.finishSignIn(try start(), startedAt: Date())
            XCTFail("an expired code signs nothing in")
        } catch {
            XCTAssertEqual(error as? KIFFClient.KIFFError, .expired)
        }
        XCTAssertEqual(model.mode, .signedOut)
    }

    /// Back in the foreground, the phone asks KIFF at once rather than after the interval.
    func testComingBackPollsAtOnce() async throws {
        let model = model()
        model.signInMinimumInterval = 60
        StubProtocol.responses["POST /v1/auth/device/token"] = (200, Self.token)
        model.nudgeSignIn()
        let began = Date()
        try await model.finishSignIn(try start(), startedAt: Date())
        XCTAssertLessThan(Date().timeIntervalSince(began), 30, "it didn't wait the minute (a slow CI runner took 9 s)")
        XCTAssertEqual(polls(), 1)
        XCTAssertEqual(model.kiffTenant, "t_01")
        Keychain.delete(AppModel.tokenAccount)
    }

    /// A3: Try again starts a new sign-in, even when the first request after the app came
    /// back meets a connection iOS dropped.
    func testTryAgainGetsANewCode() async throws {
        let model = model()
        let begin = "POST /v1/auth/device/start"
        StubProtocol.dropped[begin] = [.networkConnectionLost]
        StubProtocol.responses[begin] = (200, Self.start)
        let s = try await model.startSignIn()
        XCTAssertEqual(s.userCode, "WXYZ-1234")
        XCTAssertEqual(StubProtocol.sent.filter { $0.url?.path == "/v1/auth/device/start" }.count, 2, "tried again once")

        // A failure that isn't the network's still ends it, for the owner to see.
        StubProtocol.responses[begin] = (400, #"{"error":"bad public key"}"#)
        do { _ = try await model.startSignIn(); XCTFail("a refusal isn't retried") } catch {
            XCTAssertEqual(error as? KIFFClient.KIFFError, .server(status: 400, message: "bad public key"))
        }
    }

    /// The agreement under the sign-in reads the line in its language and links the two names
    /// wherever that language puts them, not only in English's order.
    func testTheAgreementLinksTheTermsAndPrivacyWhereTheLineSaysSo() {
        let english = Legal.agreement()
        XCTAssertEqual(String(english.characters), "By signing in, you agree to KIFF's Terms, which cover tafs, and its Privacy policy.")
        let links = english.runs.compactMap { run in run.link.map { (String(english[run.range].characters), $0) } }
        XCTAssertEqual(links.map(\.0), ["Terms", "Privacy policy"])
        XCTAssertEqual(links.map(\.1), [Legal.terms, Legal.privacy])

        let reordered = Legal.agreement("\u{2} and \u{1} apply.")
        let order = reordered.runs.compactMap { $0.link }
        XCTAssertEqual(order, [Legal.privacy, Legal.terms])
        XCTAssertEqual(String(reordered.characters), "Privacy policy and Terms apply.")
    }

    /// Terms and Privacy open tafs's own pages, the ones Google's consent screen names, never
    /// KIFF's (`/terms#tafs`): in the app's language where kiff.dev serves it, else in English.
    func testTheLegalLinksOpenTafssOwnPagesInTheAppsLanguage() {
        XCTAssertEqual(Legal.page("terms", language: "en").absoluteString, "https://kiff.dev/tafs/terms")
        XCTAssertEqual(Legal.page("privacy", language: "en").absoluteString, "https://kiff.dev/tafs/privacy", "English has no /en/")
        for language in ["de", "es", "fr", "it", "nl", "pl", "pt"] {
            XCTAssertEqual(Legal.page("terms", language: language).absoluteString, "https://kiff.dev/\(language)/tafs/terms")
            XCTAssertEqual(Legal.page("privacy", language: language).absoluteString, "https://kiff.dev/\(language)/tafs/privacy")
        }
        XCTAssertEqual(Legal.page("terms", language: "ja").absoluteString, "https://kiff.dev/tafs/terms", "a language kiff.dev doesn't serve opens English")
        XCTAssertEqual(Legal.terms, Legal.page("terms", language: Legal.language))
        XCTAssertTrue(Words.languages.contains(Legal.language))
        XCTAssertTrue(Legal.translated.isSubset(of: Set(Words.languages)), "kiff.dev serves pages only in languages the app ships")
        XCTAssertEqual(Set(Words.languages).subtracting(["en"]).subtracting(Legal.translated), ["ko"], "every shipped language has its page but Korean, which opens English, marked, until kiff.dev serves it")
    }

    /// KIFF's own Terms and Privacy, the account's, beside tafs's: in the app's language where kiff.dev serves it.
    func testKiffsOwnLegalPagesOpenInTheAppsLanguage() {
        XCTAssertEqual(Legal.kiffPage("terms", language: "en").absoluteString, "https://kiff.dev/terms")
        XCTAssertEqual(Legal.kiffPage("privacy", language: "en").absoluteString, "https://kiff.dev/privacy")
        for language in ["de", "es", "fr", "it", "nl", "pl", "pt"] {
            XCTAssertEqual(Legal.kiffPage("terms", language: language).absoluteString, "https://kiff.dev/\(language)/terms")
            XCTAssertEqual(Legal.kiffPage("privacy", language: language).absoluteString, "https://kiff.dev/\(language)/privacy")
        }
        XCTAssertEqual(Legal.kiffPage("terms", language: "ja").absoluteString, "https://kiff.dev/terms")
        XCTAssertEqual(Legal.kiffTerms, Legal.kiffPage("terms", language: Legal.language))
        XCTAssertNotEqual(Legal.kiffTerms, Legal.terms, "KIFF's Terms and tafs's are two pages")
    }

    /// A line with any number of links puts each where the line's language says, in the order of its markers.
    func testALinkedLineLinksEachMarkerWhereverTheLanguagePutsIt() {
        let a = URL(string: "https://kiff.dev/terms")!, b = URL(string: "https://kiff.dev/tafs/terms")!
        let line = Legal.linked("Ich stimme \u{2} und \u{1} zu.", [("KIFF", a), ("tafs", b)])
        XCTAssertEqual(String(line.characters), "Ich stimme tafs und KIFF zu.")
        XCTAssertEqual(line.runs.compactMap { $0.link }, [b, a])
        XCTAssertEqual(String(Legal.linked("No link here.", [("x", a)]).characters), "No link here.")
    }

    /// The welcome's one tick: Continue opens the sign-in only once the terms are agreed to, and a second tap takes it back.
    func testContinueWaitsOnTheTermsTick() {
        var tick = ConsentTick()
        XCTAssertFalse(tick.canContinue, "nothing is agreed to before the tick")
        tick.toggle()
        XCTAssertTrue(tick.agreed)
        XCTAssertTrue(tick.canContinue)
        tick.toggle()
        XCTAssertFalse(tick.canContinue, "untapped, Continue waits again")
    }

    /// The welcome ships as it is until the end-to-end test with the owner.
    func testTheNewOnboardingShipsOff() {
        XCTAssertFalse(Onboarding.on)
    }

    /// A row says the page is in English only when it opens in English from another language.
    func testALegalRowSaysInEnglishOnlyWhenThePageIs() {
        XCTAssertEqual(Legal.label("Terms", language: "en"), "Terms")
        for language in Legal.translated {
            XCTAssertEqual(Legal.label("Conditions", language: language), "Conditions", "\(language) has its own page: no suffix")
        }
        for language in ["ja", "ko"] {
            let label = Legal.label("Terms", language: language)
            XCTAssertEqual(label, Words.Settings.legalInEnglish(label: "Terms"))
            XCTAssertTrue(label.hasPrefix("Terms"), "the row's own words first")
            XCTAssertNotEqual(label, "Terms")
        }
    }

    /// The pairing QR shows at once: its claim is signed with the device key, with no Face ID or Touch ID,
    /// as on Android and Windows, and a renewed code asks nothing either. The phone's approval, behind its
    /// own Face ID after comparing the check code, is what adds the device.
    func testThePairingClaimIsSignedWithNoPrompt() async throws {
        let signer = TwoKeySigner()
        let model = model(signer: signer)
        let first = try await model.startPairingSignIn()
        let renewed = try await model.startPairingSignIn()
        XCTAssertTrue(signer.currentPrompted.isEmpty, "no Face ID or Touch ID")
        XCTAssertTrue(signer.approvalPrompted.isEmpty)
        XCTAssertEqual(signer.current, [first, renewed].map { String(decoding: $0.offer.claimMessage, as: UTF8.self) })
        XCTAssertEqual(first.proof, "current")
    }

    /// An iPhone offers only "Sign in with KIFF": any phone can open KIFF's own sign-in. An iPad or a Mac
    /// offers both halves, "Sign in with your phone" beside it.
    func testOnlyAnIPhoneLeavesOutSignInWithYourPhone() {
        XCTAssertFalse(SignInView.offersPhone(isPhone: true))
        XCTAssertTrue(SignInView.offersPhone(isPhone: false))
    }

    /// The redesign's words are the shared keys, and the approve-pairing sheet puts the check code first:
    /// it, not the device's claimed name, binds the yes to the device in front of the person.
    func testTheSignInsWordsAndTheApprovalSheetsOrder() throws {
        XCTAssertEqual(Words.SignIn.withPhone, "Sign in with your phone")
        XCTAssertEqual(Words.SignIn.scanWithTafs, "Scan with tafs")
        XCTAssertEqual(Words.SignIn.connected, "You're connected.")
        XCTAssertEqual(Words.SignIn.connectedLine, "Let's build great things together.")
        XCTAssertEqual(Words.SignIn.letsGo, "Let's go")
        XCTAssertEqual(Words.Settings.Devices.pairClaims(name: "MacBook Pro"), "It calls itself MacBook Pro.")
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let sheet = try String(contentsOf: views.appendingPathComponent("PairComputerView.swift"), encoding: .utf8)
        let code = try XCTUnwrap(sheet.range(of: "Text(request.checkCode)"))
        let only = try XCTUnwrap(sheet.range(of: "Text(Words.Settings.Devices.pairAddOnly)"))
        let claim = try XCTUnwrap(sheet.range(of: "Text(Words.Settings.Devices.pairClaims(name: request.name))"))
        XCTAssertTrue(code.lowerBound < only.lowerBound && only.lowerBound < claim.lowerBound, "the code, then only this device, then its claimed name")
        XCTAssertFalse(sheet.contains("Words.Settings.Devices.pairNamed(device: request.name)"), "the name is never the heading")
        let signIn = try String(contentsOf: views.appendingPathComponent("SignInView.swift"), encoding: .utf8)
        XCTAssertTrue(signIn.contains("model.justConnected = true"), "either way ends on the celebration")
    }

    /// Account › Legal draws its two rows from `Legal`, with the same words as Android's.
    func testAccountsLegalRowsOpenTafssPages() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let source = try String(contentsOf: views.appendingPathComponent("SignInView.swift"), encoding: .utf8)
        XCTAssertTrue(source.contains("Link(Legal.label(Words.Settings.terms), destination: Legal.terms)"))
        XCTAssertTrue(source.contains("Link(Legal.label(Words.Settings.privacyPolicy), destination: Legal.privacy)"))
    }

    /// KIFF's approval page and a held call's page carry the app's language (`lang`), so they
    /// read as the app does whatever the browser's language; a link that names one keeps it.
    func testKIFFsPagesOpenInTheAppsLanguage() throws {
        let start = try JSONDecoder().decode(KIFFClient.DeviceStart.self, from: Data(Self.start.utf8))
        XCTAssertEqual(start.inAppLanguage("de").verificationURIComplete, "https://app.kiff.dev/auth/device?code=WXYZ-1234&lang=de")
        XCTAssertEqual(start.inAppLanguage("de").inAppLanguage("fr").verificationURIComplete, "https://app.kiff.dev/auth/device?code=WXYZ-1234&lang=de")
        XCTAssertEqual(start.inAppLanguage("de").userCode, start.userCode)
        let bare = KIFFClient.DeviceStart(deviceCode: "dc", userCode: "A", verificationURI: "https://app.kiff.dev/auth/device",
                                          verificationURIComplete: "https://app.kiff.dev/auth/device", expiresIn: 600, interval: 5)
        XCTAssertEqual(bare.inAppLanguage("pl").verificationURIComplete, "https://app.kiff.dev/auth/device?lang=pl")
        let client = KIFFClient(baseURL: URL(string: "https://api.test")!, webURL: URL(string: "https://app.test")!, token: nil)
        XCTAssertEqual(client.answerPage(for: "ce_1", language: "fr").absoluteString, "https://app.test/needs-you/ce_1?lang=fr")
    }

    /// One way at a time, as on every computer: the phone's pairing runs at first, Continue stops it and starts
    /// KIFF's code, and "Show the code" goes back. Never both, so two approvals can't race into two accounts.
    func testTheSignInRunsOneWayAtATime() {
        var ways = SignInWays()
        XCTAssertTrue(ways.phoneRuns)
        XCTAssertFalse(ways.kiffRuns)
        ways.continueWithKiff()
        XCTAssertTrue(ways.kiffRuns)
        XCTAssertFalse(ways.phoneRuns, "Continue stops the phone's pairing")
        ways.showPhone()
        XCTAssertTrue(ways.phoneRuns)
        XCTAssertFalse(ways.kiffRuns, "the QR again stops KIFF's code")
    }

    /// A session KIFF hands over just after the person stopped that way (Continue, or Show the code) is never
    /// kept: it's ended at KIFF with its own token, so only the way the person chose can land.
    func testASessionHandedOverAfterTheWayStoppedIsEndedAtKiff() async throws {
        let model = model()
        StubProtocol.responses["POST /v1/auth/device/logout"] = (200, "{}")
        let token = try JSONDecoder().decode(KIFFClient.DeviceToken.self, from: Data(Self.token.utf8))
        do {
            try await model.takeSession(token, stopped: true)
            XCTFail("a stopped way keeps nothing")
        } catch is CancellationError {}
        let logout = StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path == "/v1/auth/device/logout" }
        XCTAssertEqual(logout.count, 1, "ended at KIFF")
        XCTAssertEqual(logout.first?.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_new", "with its own token")
        XCTAssertEqual(model.mode, .signedOut)
        XCTAssertNil(model.kiff.token)
        XCTAssertNil(Keychain.load(AppModel.tokenAccount))
    }

    /// The way still running keeps its session, as before.
    func testASessionOnTheWayStillRunningIsKept() async throws {
        let model = model()
        let token = try JSONDecoder().decode(KIFFClient.DeviceToken.self, from: Data(Self.token.utf8))
        try await model.takeSession(token, stopped: false)
        XCTAssertEqual(model.mode, .kiff)
        XCTAssertEqual(model.kiff.token, "kiff_dev_new")
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.path == "/v1/auth/device/logout" })
        Keychain.delete(AppModel.tokenAccount)
    }
}
