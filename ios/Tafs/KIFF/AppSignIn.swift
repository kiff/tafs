import CryptoKit
import Security
import Foundation

/// Signing in inside the app (docs/onboarding.md; kiff/cloud's `api/internal/appsignin/README.md`): KIFF's
/// `/oauth/authorize` in the system's own sheet, with PKCE (RFC 7636, RFC 8252), then the code exchanged at
/// `/v1/auth/app/token` for the device flow's own session. Only the code that comes back with this sign-in's `state`
/// is taken, and only this app, which holds the verifier, can exchange it.
enum AppSignIn {
    /// The tafs apps' one client at KIFF: first party, so the sheet has no consent page.
    static let clientID = "tafs-app"
    /// The exact redirect KIFF sends the code to: a private-use scheme (RFC 8252 §7.1), which PKCE makes safe.
    static let callbackScheme = "dev.kiff.tafs"
    static let redirectURI = "\(callbackScheme):/oauth"
    /// The languages KIFF's sign-in page is written in; any other opens it in English.
    static let pageLanguages: Set<String> = ["en", "de", "fr", "es", "it", "pt", "nl", "pl"]

    /// A PKCE pair: the verifier stays in the app, the challenge goes in the sheet's address.
    struct PKCE: Equatable {
        let verifier: String
        /// BASE64URL(SHA256(verifier)), sent with `code_challenge_method=S256`; `plain` is never used.
        var challenge: String { AppSignIn.base64URL(Data(SHA256.hash(data: Data(verifier.utf8)))) }

        /// 32 random bytes: a 43-character verifier, within RFC 7636's 43 to 128.
        static func make(random: (Int) -> Data = AppSignIn.randomBytes) -> PKCE {
            PKCE(verifier: AppSignIn.base64URL(random(32)))
        }
    }

    /// The sheet's `state`: 32 random bytes, well over the 128 bits KIFF asks for.
    static func makeState(random: (Int) -> Data = randomBytes) -> String { base64URL(random(32)) }

    /// The address the sheet opens: KIFF's authorize with this sign-in's challenge and state, in the app's language
    /// where KIFF's page is written in it.
    static func authorizeURL(base: URL, pkce: PKCE, state: String, language: String, loginHint: String? = nil) -> URL {
        var parts = URLComponents(url: base.appendingPathComponent("oauth").appendingPathComponent("authorize"),
                                  resolvingAgainstBaseURL: false)!
        var items = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "code_challenge", value: pkce.challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
        ]
        if pageLanguages.contains(language) { items.append(URLQueryItem(name: "ui_locales", value: language)) }
        if let loginHint, !loginHint.isEmpty { items.append(URLQueryItem(name: "login_hint", value: loginHint)) }
        parts.queryItems = items
        return parts.url!
    }

    /// How the sheet ended, read from where KIFF sent it back.
    enum Failure: Error, Equatable {
        /// The person closed the sheet or declined (`access_denied`): nothing to say, the welcome is as it was.
        case closed
        /// The redirect's `state` isn't this sign-in's: its code is never taken.
        case wrongState
        /// KIFF couldn't finish just then (`temporarily_unavailable`).
        case unavailable
        /// Anything else KIFF named (`invalid_request`, `unsupported_client`), or a redirect without a code.
        case refused(String)
    }

    /// The code from KIFF's redirect, once its `state` is checked; or how the sheet ended. A redirect whose `state`
    /// isn't this sign-in's is refused before anything else is read from it, an error included.
    static func code(from callback: URL, state: String) throws -> String {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        guard callback.scheme == callbackScheme, value("state") == state else { throw Failure.wrongState }
        if let error = value("error") {
            switch error {
            case "access_denied": throw Failure.closed
            case "temporarily_unavailable": throw Failure.unavailable
            default: throw Failure.refused(error)
            }
        }
        guard let code = value("code"), !code.isEmpty else { throw Failure.refused("no_code") }
        return code
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(status == errSecSuccess)
        return Data(bytes)
    }
}

extension AppSignIn.Failure: LocalizedError {
    /// Closing the sheet says nothing (the welcome is as it was); anything else, that signing in didn't work.
    var errorDescription: String? { Words.SignIn.didntWork }
}
