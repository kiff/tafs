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
    enum SignInError: Error { case cancelled, noCallback }

    private var session: ASWebAuthenticationSession?

    func signIn(at url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "tafs") { callback, error in
                if let callback {
                    continuation.resume(returning: callback)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(throwing: SignInError.cancelled)
                } else {
                    continuation.resume(throwing: error ?? SignInError.noCallback)
                }
            }
            session.presentationContextProvider = self
            // Shares Safari's sign-in, so an owner already signed in to the vendor only
            // allows access. What binds the sign-in to this phone is planque's code.
            self.session = session
            if !session.start() { continuation.resume(throwing: SignInError.noCallback) }
        }
    }
}

// The session asks for its window on the main thread.
extension VendorSignIn: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
    }
}
