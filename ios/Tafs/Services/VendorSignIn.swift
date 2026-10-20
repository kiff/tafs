import AuthenticationServices
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// The vendor's own sign-in page, opened for a grant the owner approved. It ends at
/// planque, which sends the browser back to `tafs://planque/signed-in` with a one-time
/// code; only the session the app opened receives it. Tests use a stub.
@MainActor
protocol VendorSigningIn {
    /// Opens `url` and returns the URL it came back to, or throws if the owner closed it.
    func signIn(at url: URL) async throws -> URL
}

@MainActor
final class VendorSignIn: NSObject, VendorSigningIn {
    enum SignInError: LocalizedError, Equatable {
        case cancelled, noCallback, noWindow
        /// iOS wouldn't show the page (#72): its error, and what it was given, so a
        /// screenshot of the failure says which case it was.
        case notShown(code: Int, context: String)

        var errorDescription: String? {
            switch self {
            case .cancelled: return "The sign-in was closed."
            case .noCallback: return "The sign-in didn't come back to tafs."
            case .noWindow: return "tafs had no window on screen to open the sign-in over. Open tafs and try again."
            case .notShown(let code, let context):
                return "iOS didn't open the sign-in page (error \(code); \(context)). Try again with tafs open."
            }
        }
    }

    private var session: ASWebAuthenticationSession?
    private var anchor: ASPresentationAnchor?

    /// How long to wait for the app to be active before opening the page: just after
    /// Face ID, iOS gives the app back a moment later.
    var activeWait: TimeInterval = 2

    func signIn(at url: URL) async throws -> URL {
        await untilActive()
        do {
            return try await attempt(url)
        } catch let e as ASWebAuthenticationSessionError where e.code == .presentationContextInvalid {
            // iOS refused the window (error 3): once more, on the next turn, with the
            // window read again (#72).
            try? await Task.sleep(nanoseconds: 300_000_000)
            await untilActive()
            do {
                return try await attempt(url)
            } catch let e as ASWebAuthenticationSessionError where e.code == .presentationContextInvalid {
                throw SignInError.notShown(code: e.code.rawValue, context: Self.describe(anchor ?? Self.window()))
            }
        }
    }

    private func attempt(_ url: URL) async throws -> URL {
        guard let window = Self.window() else { throw SignInError.noWindow }
        anchor = window
        defer { session = nil }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            // A session that fails to start also calls its completion handler, so the
            // continuation is resumed by whichever comes first, and only once: resuming
            // twice is a crash (TestFlight 175, on tapping an approval).
            let once = ResumeOnce(continuation)
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "tafs") { callback, error in
                if let callback {
                    once.resume(.success(callback))
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    once.resume(.failure(SignInError.cancelled))
                } else {
                    once.resume(.failure(error ?? SignInError.noCallback))
                }
            }
            session.presentationContextProvider = self
            // Shares Safari's sign-in, so an owner already signed in to the vendor only
            // allows access. What binds the sign-in to this phone is planque's code.
            self.session = session
            if !session.start() { once.resume(.failure(SignInError.noCallback)) }
        }
    }

    /// Waits, at most `activeWait`, for a scene of the app to be active: the page can't
    /// open over an app iOS hasn't given back yet.
    private func untilActive() async {
        if Self.isActive() { return }
        let deadline = Date().addingTimeInterval(activeWait)
        while !Self.isActive(), Date() < deadline {
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    #if canImport(UIKit)
    private static func isActive() -> Bool { activeScene() != nil }

    private static func activeScene() -> UIWindowScene? {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
    }

    /// The app's own window on screen, the one iOS will present over: the active
    /// scene's key window, else a visible app window of a scene in the foreground.
    /// Never the keyboard's or another system window, and never a new empty window,
    /// which iOS always refuses (error 3).
    static func window() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.filter { $0.activationState == .foregroundActive }
        let foreground = scenes.filter { $0.activationState == .foregroundActive || $0.activationState == .foregroundInactive }
        if let key = active.compactMap(\.keyWindow).first(where: usable) { return key }
        return (active + foreground).flatMap(\.windows).first { usable($0) && $0.isKeyWindow }
            ?? (active + foreground).flatMap(\.windows).first(where: usable)
    }

    /// A window that's the app's own and on screen: shown, in a scene, with content,
    /// at the normal level (the keyboard's and alerts' windows sit above it).
    private static func usable(_ w: UIWindow) -> Bool {
        !w.isHidden && w.windowScene != nil && w.rootViewController != nil && w.windowLevel == .normal
    }

    /// What iOS was given, in a few words: for the error the owner sees.
    static func describe(_ w: UIWindow?) -> String {
        guard let w else { return "no window" }
        let state: String
        switch w.windowScene?.activationState {
        case .foregroundActive: state = "active"
        case .foregroundInactive: state = "inactive"
        case .background: state = "background"
        default: state = "no scene"
        }
        return "\(type(of: w)), \(w.isKeyWindow ? "key" : "not key"), \(w.isHidden ? "hidden" : "shown"), \(state)"
    }
    #else
    // The Mac (kiff/tafs#85): the app's key window, else a visible one.
    private static func isActive() -> Bool { NSApp.isActive }

    static func window() -> NSWindow? {
        NSApp.keyWindow ?? NSApp.windows.first { $0.isVisible && $0.canBecomeKey }
    }

    static func describe(_ w: NSWindow?) -> String {
        guard let w else { return "no window" }
        return "\(type(of: w)), \(w.isKeyWindow ? "key" : "not key"), \(w.isVisible ? "shown" : "hidden")"
    }
    #endif
}

// The session asks for its window on the main thread.
extension VendorSignIn: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        // Read when iOS asks: the window may have changed since the session was made.
        // `attempt` checked there was one, so the last fallback is that window.
        Self.window() ?? anchor ?? ASPresentationAnchor()
    }
}

/// Resumes a continuation once: later results are dropped.
final class ResumeOnce<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?

    init(_ continuation: CheckedContinuation<T, Error>) { self.continuation = continuation }

    /// Whether this was the first result, the one the continuation got.
    @discardableResult
    func resume(_ result: Result<T, Error>) -> Bool {
        lock.lock()
        let c = continuation
        continuation = nil
        lock.unlock()
        c?.resume(with: result)
        return c != nil
    }
}
