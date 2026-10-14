import CryptoKit
import Foundation
import LocalAuthentication

/// Signs an owner's answer. The app uses `ApprovalSigner`; tests use a stub, since
/// a real signature waits for a person at the Face ID prompt.
@MainActor
protocol ApprovalSigning {
    func sign(answer: Answer, heldID: UUID, reason: String) async throws -> String
}

/// Signs the owner's answer to a held call with a key that never leaves the iPhone.
///
/// On a device the key lives in the Secure Enclave. The Simulator has none, so there
/// it falls back to an in-memory software key, which is fine for a demo and never
/// for production. KIFF verifies the signature against the device's registered
/// public key (the approvals RFC); a BFF never vouches for an approval.
@MainActor
final class ApprovalSigner: ApprovalSigning {
    enum SignerError: Error { case notAuthenticated }

    private let enclaveKey: SecureEnclave.P256.Signing.PrivateKey?
    private let softwareKey: P256.Signing.PrivateKey?

    init() {
        if SecureEnclave.isAvailable, let key = try? SecureEnclave.P256.Signing.PrivateKey() {
            enclaveKey = key
            softwareKey = nil
        } else {
            enclaveKey = nil
            softwareKey = P256.Signing.PrivateKey()
        }
    }

    var usesSecureEnclave: Bool { enclaveKey != nil }

    /// Asks for Face ID (or the passcode), then signs `answer` for `heldID`.
    /// Returns the signature, base64.
    func sign(answer: Answer, heldID: UUID, reason: String) async throws -> String {
        try await authenticate(reason: reason)
        let message = Data("tafs.answer.v1|\(heldID.uuidString)|\(answer.wireName)".utf8)
        let signature: Data
        if let enclaveKey {
            signature = try enclaveKey.signature(for: message).derRepresentation
        } else if let softwareKey {
            signature = try softwareKey.signature(for: message).derRepresentation
        } else {
            throw SignerError.notAuthenticated
        }
        return signature.base64EncodedString()
    }

    private func authenticate(reason: String) async throws {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            #if targetEnvironment(simulator)
            return // No Face ID enrolled in the Simulator: let the demo through.
            #else
            throw SignerError.notAuthenticated
            #endif
        }
        let ok = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        if !ok { throw SignerError.notAuthenticated }
    }
}
