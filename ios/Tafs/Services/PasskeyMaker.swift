import AuthenticationServices
import UIKit

/// What a passkey's creation gives back, as the platform gave it: KIFF reads the credential from these bytes,
/// never from anything the app reads out of them.
struct MadePasskey: Equatable, Sendable {
    let clientDataJSON: Data
    let attestationObject: Data
}

/// Makes a passkey for the account at kiff.dev. Tests use a stub.
@MainActor
protocol PasskeyMaking {
    func create(challenge: Data, userName: String, userID: Data) async throws -> MadePasskey
}

/// The platform's passkey for kiff.dev, through ASAuthorization (KIFF's pairing contract, "Registering one"):
/// ES256, user verification required, attestation `none`. iOS asks for Face ID or Touch ID itself, and only
/// for an app kiff.dev vouches for (`webcredentials:kiff.dev`).
@MainActor
final class PasskeyMaker: NSObject, PasskeyMaking {
    enum MakeError: LocalizedError, Equatable {
        /// The person closed the sheet: nothing was made.
        case cancelled
        /// The platform answered without an attestation object, which KIFF needs.
        case noAttestation
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .cancelled: return nil
            case .noAttestation: return Words.Error.ours
            case .failed(let why): return why
            }
        }
    }

    private var once: ResumeOnce<MadePasskey>?
    private var controller: ASAuthorizationController?

    func create(challenge: Data, userName: String, userID: Data) async throws -> MadePasskey {
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: PasskeyRegistration.relyingParty)
        let request = provider.createCredentialRegistrationRequest(challenge: challenge, name: userName, userID: userID)
        request.userVerificationPreference = .required
        request.attestationPreference = .none
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<MadePasskey, Error>) in
            once = ResumeOnce(continuation)
            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            self.controller = controller
            controller.performRequests()
        }
    }

    fileprivate func finish(_ result: Result<MadePasskey, Error>) {
        once?.resume(result)
        once = nil
        controller = nil
    }
}

extension PasskeyMaker: ASAuthorizationControllerDelegate {
    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        let registration = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration
        let made = registration.flatMap { r in r.rawAttestationObject.map { MadePasskey(clientDataJSON: r.rawClientDataJSON, attestationObject: $0) } }
        MainActor.assumeIsolated {
            finish(made.map { .success($0) } ?? .failure(MakeError.noAttestation))
        }
    }

    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let cancelled = (error as? ASAuthorizationError)?.code == .canceled
        let why = error.localizedDescription
        MainActor.assumeIsolated {
            finish(.failure(cancelled ? MakeError.cancelled : MakeError.failed(why)))
        }
    }
}

extension PasskeyMaker: ASAuthorizationControllerPresentationContextProviding {
    nonisolated func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        MainActor.assumeIsolated { VendorSignIn.window() ?? ASPresentationAnchor() }
    }
}
