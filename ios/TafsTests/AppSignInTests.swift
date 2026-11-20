import XCTest
@testable import Tafs

/// Signing in inside the app (docs/onboarding.md; kiff/cloud's `api/internal/appsignin/README.md`): KIFF's sign-in
/// in the system's sheet with PKCE, then the device flow's own session.
@MainActor
final class AppSignInTests: XCTestCase {
    private static let token = #"{"access_token":"kiff_app_new","token_type":"Bearer","expires_in":7776000,"tenant":"t_01","subject":"u_1","approval_mode":"key"}"#

    /// KIFF's sheet, played by the test: it reads the sign-in's own `state` from the address it was opened at, and
    /// comes back with a code, an error, or another state.
    @MainActor
    final class Sheet: VendorSigningIn {
        enum Answer { case code(String), error(String), otherState, closed }
        var answer: Answer = .code("c_1")
        var opened: [URL] = []

        func signIn(at url: URL) async throws -> URL {
            opened.append(url)
            let state = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "state" }?.value ?? ""
            switch answer {
            case .code(let code): return URL(string: "dev.kiff.tafs:/oauth?code=\(code)&state=\(state)")!
            case .error(let error): return URL(string: "dev.kiff.tafs:/oauth?error=\(error)&state=\(state)")!
            case .otherState: return URL(string: "dev.kiff.tafs:/oauth?code=c_1&state=someone-else")!
            case .closed: throw VendorSignIn.SignInError.cancelled
            }
        }
    }

    private func model() -> AppModel {
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
        let model = AppModel(signer: FaceIDSigner(), kiff: kiff, cloud: cloud, planque: planque, vendorSignIn: StubVendor())
        // What a new session loads.
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.empty)
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[]}"#)
        StubProtocol.responses["GET /v1/me/devices"] = (200, #"{"devices":[{"id":"dev_phone01","this_device":true}]}"#)
        StubProtocol.responses["GET /v1/setup"] = (200, #"{"setup":null}"#)
        StubProtocol.responses["GET /v1/auth/app/terms"] = (200, #"{"terms_version":"2026-11-20"}"#)
        return model
    }

    private func tokenRequests() -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path == "/v1/auth/app/token" }
    }

    // MARK: PKCE and the sheet's address

    /// RFC 7636 Appendix B: the S256 challenge of its verifier.
    func testTheChallengeIsS256OfTheVerifier() {
        let pkce = AppSignIn.PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        XCTAssertEqual(pkce.challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    /// A verifier and a state are 32 random bytes each: 43 base64url characters, none of `+/=`, never the same twice.
    func testTheVerifierAndStateAreRandomAndURLSafe() {
        let a = AppSignIn.PKCE.make(), b = AppSignIn.PKCE.make()
        XCTAssertEqual(a.verifier.count, 43, "within RFC 7636's 43 to 128")
        XCTAssertNotEqual(a.verifier, b.verifier)
        let state = AppSignIn.makeState()
        XCTAssertEqual(state.count, 43)
        XCTAssertNotEqual(state, AppSignIn.makeState())
        for s in [a.verifier, a.challenge, state] {
            XCTAssertNil(s.rangeOfCharacter(from: CharacterSet(charactersIn: "+/=")), s)
        }
    }

    /// The sheet opens KIFF's authorize for the tafs apps' client, its exact redirect, S256 and the state, in the app's
    /// language where KIFF's page has it; never `plain`, never a scope.
    func testTheSheetOpensKiffsAuthorizeWithPKCE() throws {
        let pkce = AppSignIn.PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        let url = AppSignIn.authorizeURL(base: URL(string: "https://api.kiff.dev")!, pkce: pkce, state: "s_1", language: "fr")
        let parts = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(parts.scheme, "https")
        XCTAssertEqual(parts.host, "api.kiff.dev")
        XCTAssertEqual(parts.path, "/oauth/authorize")
        let q = Dictionary(uniqueKeysWithValues: (parts.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(q, ["response_type": "code", "client_id": "tafs-app", "redirect_uri": "dev.kiff.tafs:/oauth",
                           "code_challenge": "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "code_challenge_method": "S256",
                           "state": "s_1", "ui_locales": "fr"])
        let japanese = AppSignIn.authorizeURL(base: URL(string: "https://api.kiff.dev")!, pkce: pkce, state: "s_1", language: "ja")
        XCTAssertFalse(japanese.absoluteString.contains("ui_locales"), "a language KIFF's page doesn't have opens it in English")
        let hinted = AppSignIn.authorizeURL(base: URL(string: "https://api.kiff.dev")!, pkce: pkce, state: "s_1", language: "en",
                                            loginHint: "a@b.example")
        XCTAssertTrue(hinted.absoluteString.contains("login_hint=a@b.example"))
    }

    // MARK: What comes back

    /// Only a redirect to the app's own scheme with this sign-in's state gives its code; anything else is refused
    /// before its code or error is read.
    func testOnlyThisSignInsStateGivesTheCode() throws {
        XCTAssertEqual(try AppSignIn.code(from: URL(string: "dev.kiff.tafs:/oauth?code=c_1&state=s_1")!, state: "s_1"), "c_1")
        XCTAssertThrowsError(try AppSignIn.code(from: URL(string: "dev.kiff.tafs:/oauth?code=c_1&state=s_2")!, state: "s_1")) {
            XCTAssertEqual($0 as? AppSignIn.Failure, .wrongState)
        }
        XCTAssertThrowsError(try AppSignIn.code(from: URL(string: "dev.kiff.tafs:/oauth?code=c_1")!, state: "s_1")) {
            XCTAssertEqual($0 as? AppSignIn.Failure, .wrongState, "no state is never this sign-in's")
        }
        XCTAssertThrowsError(try AppSignIn.code(from: URL(string: "dev.kiff.tafs:/oauth?error=access_denied&state=s_2")!, state: "s_1")) {
            XCTAssertEqual($0 as? AppSignIn.Failure, .wrongState, "another sign-in's error is not this one's")
        }
        XCTAssertThrowsError(try AppSignIn.code(from: URL(string: "tafs://oauth?code=c_1&state=s_1")!, state: "s_1")) {
            XCTAssertEqual($0 as? AppSignIn.Failure, .wrongState, "only KIFF's own redirect")
        }
    }

    /// KIFF's errors on the redirect, each its own way: closed says nothing, unavailable can be tried again.
    func testKiffsRedirectErrorsAreTold() {
        func failure(_ query: String) -> AppSignIn.Failure? {
            do { _ = try AppSignIn.code(from: URL(string: "dev.kiff.tafs:/oauth?\(query)&state=s_1")!, state: "s_1"); return nil }
            catch { return error as? AppSignIn.Failure }
        }
        XCTAssertEqual(failure("error=access_denied"), .closed)
        XCTAssertEqual(failure("error=temporarily_unavailable"), .unavailable)
        XCTAssertEqual(failure("error=unsupported_client"), .refused("unsupported_client"))
        XCTAssertEqual(failure("error=invalid_request&error_description=missing%20state"), .refused("invalid_request"))
        XCTAssertEqual(failure("code="), .refused("no_code"))
        XCTAssertEqual(AppSignIn.Failure.unavailable.localizedDescription, Words.SignIn.didntWork)
    }

    // MARK: Signing in

    /// The whole way: the terms version in force, the sheet, then the code, the verifier, the redirect and this device's
    /// keys to KIFF's app token route, and the device flow's session taken as any sign-in's.
    func testSigningInExchangesTheCodeWithTheVerifierAndTheTermsVersion() async throws {
        let model = model()
        StubProtocol.responses["POST /v1/auth/app/token"] = (200, Self.token)
        let sheet = Sheet()
        try await model.signInInApp(sheet: sheet)

        XCTAssertEqual(model.mode, .kiff)
        XCTAssertEqual(model.kiff.token, "kiff_app_new")
        let opened = try XCTUnwrap(sheet.opened.first)
        let challenge = try XCTUnwrap(URLComponents(url: opened, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "code_challenge" }?.value)
        let request = try XCTUnwrap(tokenRequests().first)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: String])
        XCTAssertEqual(body["client_id"], "tafs-app")
        XCTAssertEqual(body["code"], "c_1")
        XCTAssertEqual(body["redirect_uri"], "dev.kiff.tafs:/oauth")
        XCTAssertEqual(body["terms_version"], "2026-11-20", "the consent goes with the session, in the same step")
        let verifier = try XCTUnwrap(body["code_verifier"])
        XCTAssertEqual(AppSignIn.PKCE(verifier: verifier).challenge, challenge, "the verifier is the one behind the sheet's challenge")
        XCTAssertNotNil(body["kind"], "the device's own fields, as device/start takes them")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"), "no session yet")
    }

    /// The version read when the welcome showed its tick is the one sent, even if KIFF has another by the sign-in.
    func testTheVersionSentIsTheOneShownWithTheTick() async throws {
        let model = model()
        StubProtocol.responses["POST /v1/auth/app/token"] = (200, Self.token)
        StubProtocol.responses["GET /v1/auth/app/terms"] = (200, #"{"terms_version":"2026-12-01"}"#)
        try await model.signInInApp(termsVersion: "2026-11-20", sheet: Sheet())
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: tokenRequests().first?.httpBody ?? Data()) as? [String: String])
        XCTAssertEqual(body["terms_version"], "2026-11-20", "what the person saw; KIFF answers terms_outdated if it changed")
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.path == "/v1/auth/app/terms" }, "not read again")
    }

    /// Closing the sheet asks KIFF for nothing more: no code is exchanged, and no session made.
    func testClosingTheSheetSignsNothingIn() async {
        let model = model()
        let sheet = Sheet()
        sheet.answer = .closed
        do { try await model.signInInApp(sheet: sheet); XCTFail("closed") } catch {
            XCTAssertEqual(error as? AppSignIn.Failure, .closed)
        }
        XCTAssertTrue(tokenRequests().isEmpty)
        XCTAssertNotEqual(model.mode, .kiff)
    }

    /// A redirect with another state never has its code exchanged.
    func testAnotherStatesCodeIsNeverExchanged() async {
        let model = model()
        let sheet = Sheet()
        sheet.answer = .otherState
        do { try await model.signInInApp(sheet: sheet); XCTFail("wrong state") } catch {
            XCTAssertEqual(error as? AppSignIn.Failure, .wrongState)
        }
        XCTAssertTrue(tokenRequests().isEmpty)
    }

    /// Terms that changed since the tick: KIFF answers 409 `terms_outdated`, and the welcome asks for the tick again.
    func testChangedTermsAskForTheTickAgain() async {
        let model = model()
        StubProtocol.responses["POST /v1/auth/app/token"] = (409, #"{"error":"terms_outdated","error_description":"terms changed"}"#)
        do { try await model.signInInApp(sheet: Sheet()); XCTFail("outdated") } catch {
            XCTAssertEqual(error as? KIFFClient.KIFFError, .termsOutdated)
        }
        XCTAssertNotEqual(model.mode, .kiff)
    }

    /// KIFF answers `terms_outdated` before spending the code: ticked again within its minute, the same code goes again
    /// with the new version, and the sheet isn't opened a second time.
    func testATickAgainSendsTheKeptCodeWithTheNewVersion() async throws {
        let model = model()
        let sheet = Sheet()
        StubProtocol.responses["POST /v1/auth/app/token"] = (409, #"{"error":"terms_outdated"}"#)
        do { try await model.signInInApp(termsVersion: "2026-11-14", sheet: sheet); XCTFail("outdated") } catch {
            XCTAssertEqual(error as? KIFFClient.KIFFError, .termsOutdated)
        }
        XCTAssertEqual(model.keptAppCode?.code, "c_1")
        StubProtocol.responses["POST /v1/auth/app/token"] = (200, Self.token)
        try await model.signInInApp(termsVersion: "2026-11-20", sheet: sheet)

        XCTAssertEqual(sheet.opened.count, 1, "the sheet isn't opened twice")
        let bodies = tokenRequests().compactMap { try? JSONSerialization.jsonObject(with: $0.httpBody ?? Data()) as? [String: String] }
        XCTAssertEqual(bodies.map { $0["code"] }, ["c_1", "c_1"])
        XCTAssertEqual(bodies.map { $0["terms_version"] }, ["2026-11-14", "2026-11-20"])
        XCTAssertEqual(bodies[0]["code_verifier"], bodies[1]["code_verifier"])
        XCTAssertNil(model.keptAppCode, "used once")
        XCTAssertEqual(model.mode, .kiff)
    }

    /// Any other failure keeps nothing: a spent or wrong code is never sent again.
    func testOnlyChangedTermsKeepTheCode() async {
        let model = model()
        StubProtocol.responses["POST /v1/auth/app/token"] = (400, #"{"error":"invalid_grant"}"#)
        try? await model.signInInApp(termsVersion: "2026-11-20", sheet: Sheet())
        XCTAssertNil(model.keptAppCode)
    }

    /// A used or expired code (`invalid_grant`) is a plain failure, never a session.
    func testAnExpiredCodeIsAFailure() async {
        let model = model()
        StubProtocol.responses["POST /v1/auth/app/token"] = (400, #"{"error":"invalid_grant"}"#)
        do { try await model.signInInApp(sheet: Sheet()); XCTFail("invalid grant") } catch {
            XCTAssertEqual(error as? KIFFClient.KIFFError, .server(status: 400, message: "invalid_grant"))
        }
        XCTAssertNotEqual(model.mode, .kiff)
    }
}
