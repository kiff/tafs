import AuthenticationServices
import UIKit

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
    enum SignInError: Error { case cancelled, noCallback, noWindow }

    private var session: ASWebAuthenticationSession?
    private var anchor: ASPresentationAnchor?

    func signIn(at url: URL) async throws -> URL {
        // The window the page opens over, read now: right after Face ID the app may
        // have no key window for a moment, and a window that isn't on screen makes the
        // session fail to start.
        guard let window = Self.window() else { throw SignInError.noWindow }
        anchor = window
        defer { anchor = nil; session = nil }
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

    /// The app's window on screen: the key window of the active scene, else any window of
    /// a scene in the foreground.
    private static func window() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.filter { $0.activationState == .foregroundActive }
        let foreground = scenes.filter { $0.activationState == .foregroundActive || $0.activationState == .foregroundInactive }
        return active.compactMap(\.keyWindow).first
            ?? foreground.flatMap(\.windows).first { $0.isKeyWindow }
            ?? foreground.flatMap(\.windows).first
    }
}

// The session asks for its window on the main thread.
extension VendorSignIn: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor ?? Self.window() ?? ASPresentationAnchor()
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
