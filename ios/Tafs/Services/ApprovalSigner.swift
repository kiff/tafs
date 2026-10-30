import CryptoKit
import Foundation
import LocalAuthentication

/// Signs an owner's answer. The app uses `ApprovalSigner`; tests use a stub, since
/// a real signature waits for a person at the Face ID prompt.
@MainActor
protocol ApprovalSigning {
    /// The public half of the signing key (P-256, SPKI DER). KIFF binds it to this
    /// phone's session when the owner approves the sign-in in their browser.
    var publicKeyDER: Data { get }
    /// Asks for Face ID (or the passcode), then signs `message`. Returns a base64 DER
    /// ECDSA signature over its SHA-256.
    func sign(_ message: Data, reason: String) async throws -> String
    /// As `sign`, for a step that grants authority (a Card, a connection, a trial, Resume,
    /// sharing): its prompt takes Face ID or Touch ID only, since the approval key signs under it.
    func sign(_ message: Data, reason: String, grants: Bool) async throws -> String
    /// Signs `message` without asking again: only for the requests of a step the owner
    /// has just confirmed with Face ID (starting a taf signs each of its setup calls).
    func signConfirmed(_ message: Data) throws -> String
    /// See the extension: one answer from its notification may count the unlock.
    func allowUnlockReuse()
    /// That answer is over, signed or not: the next one asks again.
    func endUnlockReuse()
    /// The public half of the approval key (P-256, SPKI DER), which signs only what grants
    /// authority (kiff/tafs#233); empty when this device holds none.
    var approvalPublicKeyDER: Data { get }
    /// Asks for Face ID or Touch ID, never the passcode, then signs `message` with the approval
    /// key. The key checks that biometric itself: code that skipped the prompt can't sign with it.
    func signApproval(_ message: Data, reason: String) async throws -> String
    /// Signs `message` with the approval key under the Face ID that just confirmed this step
    /// (`sign` or `signApproval`), without asking again. Past the step, it throws.
    func signApprovalConfirmed(_ message: Data) throws -> String
    /// Whether the approval key can no longer sign because the enrolled faces or fingerprints
    /// changed since it was made, found with no prompt. When so, a new key takes its place,
    /// which KIFF holds only after a new sign-in.
    func renewApprovalKeyIfBiometricsChanged() -> Bool
}

extension ApprovalSigning {
    /// The next Face ID or Touch ID may be the device unlock the owner gave seconds before:
    /// only for an answer chosen on its notification, where unlocking was for that answer
    /// (kiff/tafs#236, item 25). Signers that always ask ignore it.
    func allowUnlockReuse() {}
    func endUnlockReuse() {}
}

@MainActor
extension ApprovalSigning {
    /// Signers without an approval key sign everything with the one key they have.
    var approvalPublicKeyDER: Data { Data() }
    func renewApprovalKeyIfBiometricsChanged() -> Bool { false }
    func sign(_ message: Data, reason: String, grants: Bool) async throws -> String { try await sign(message, reason: reason) }
    func signApproval(_ message: Data, reason: String) async throws -> String { try await sign(message, reason: reason) }
    func signApprovalConfirmed(_ message: Data) throws -> String { try signConfirmed(message) }

    /// An answer in sample mode, where the phone checks the Card itself.
    func sign(answer: Answer, heldID: String, reason: String) async throws -> String {
        try await sign(Data("tafs.answer.v1|\(heldID)|\(answer.wireName)".utf8), reason: reason)
    }
}

/// Signs the owner's answer to a held call with a key that never leaves the iPhone.
///
/// On a device the key lives in the Secure Enclave. The Simulator has none, so there
/// it falls back to a software key, which is fine for a demo and never for production.
/// The key is kept across launches (the Keychain holds the Secure Enclave's handle, not
/// the key), so the key KIFF registered at sign-in is the one that signs. An answer to
/// KIFF counts only once KIFF verifies the signature against that key; a BFF never
/// vouches for an approval.
///
/// On an iPhone or iPad it holds two keys (kiff/tafs#233). The **current key** has no access
/// control: it signs what reads or takes authority away (Refuse, Halt all, Setup's and
/// planque's requests), which may run with no one at the screen. The **approval key** signs
/// only what grants authority (Approve, issuing a Card, starting a trial, Resume): in the
/// Secure Enclave its own access control requires the faces or fingerprints enrolled when it was
/// made, never the passcode (the screen lock never approves, as on Android), so the key checks
/// the prompt, not the app's code. One confirmed Face ID covers one confirmed step.
@MainActor
final class ApprovalSigner: ApprovalSigning {
    enum SignerError: Error { case notAuthenticated }

    private static let keyAccount = "tafs.signing-key"
    /// The approval key's handle. "v2": biometrics only. The key under the first name also took
    /// the passcode; a key's access control can't change, so each phone makes a new one, and
    /// KIFF holds it after one new sign-in.
    private static let approvalKeyAccount = "tafs.approval-key.v2"
    private static let looseApprovalKeyAccount = "tafs.approval-key"
    /// The enrolled biometrics when the approval key was made (`evaluatedPolicyDomainState`):
    /// when they differ, the key no longer signs.
    private static let approvalDomainAccount = "tafs.approval-key.domain"

    private let enclaveKey: SecureEnclave.P256.Signing.PrivateKey?
    private let softwareKey: P256.Signing.PrivateKey?
    /// The approval key: in the Secure Enclave, its handle (the Keychain keeps it); or, in
    /// the Simulator, a software key.
    private var approvalHandle: Data?
    private var approvalPublic: Data
    private let softwareApprovalKey: P256.Signing.PrivateKey?
    /// The Face ID that confirmed the current step, and when: the approval key signs under
    /// it, without asking again, until the step is over.
    private var step: (context: LAContext, at: Date)?
    /// How long one confirmed Face ID covers a step's signatures.
    static let stepWindow: TimeInterval = 120
    /// Set for one answer chosen on its notification: its unlock, seconds ago, counts. It
    /// ends with that answer, whether it signed or stopped before (`endUnlockReuse`).
    private(set) var reusesUnlock = false

    /// How long a device unlock counts for that one answer.
    static let unlockReuse: TimeInterval = 10

    func allowUnlockReuse() { reusesUnlock = true }
    func endUnlockReuse() { reusesUnlock = false }

    init() {
        let saved = Keychain.load(Self.keyAccount).flatMap { Data(base64Encoded: $0) }
        var enclave: SecureEnclave.P256.Signing.PrivateKey?
        var software: P256.Signing.PrivateKey?
        if SecureEnclave.isAvailable {
            if let saved, let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: saved) {
                enclave = key
            } else if let key = try? SecureEnclave.P256.Signing.PrivateKey() {
                Keychain.save(key.dataRepresentation.base64EncodedString(), for: Self.keyAccount)
                enclave = key
            }
        }
        #if os(macOS)
        // A Mac without a Secure Enclave holds no key at all: it never signs with a
        // software key, and approves on the owner's iPhone (kiff/tafs#85).
        let softwareAllowed = false
        #else
        // The Simulator has no Secure Enclave.
        let softwareAllowed = true
        #endif
        if enclave == nil, softwareAllowed {
            if let saved, let key = try? P256.Signing.PrivateKey(rawRepresentation: saved) {
                software = key
            } else {
                let key = P256.Signing.PrivateKey()
                Keychain.save(key.rawRepresentation.base64EncodedString(), for: Self.keyAccount)
                software = key
            }
        }
        enclaveKey = enclave
        softwareKey = software

        var handle: Data?
        var approvalPublic = Data()
        var softwareApproval: P256.Signing.PrivateKey?
        #if os(iOS)
        let savedApproval = Keychain.load(Self.approvalKeyAccount).flatMap { Data(base64Encoded: $0) }
        // The key that also took the passcode is never used again.
        Keychain.delete(Self.looseApprovalKeyAccount)
        if SecureEnclave.isAvailable {
            if let savedApproval, let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: savedApproval) {
                handle = savedApproval
                approvalPublic = key.publicKey.derRepresentation
            } else if let key = Self.makeApprovalKey() {
                handle = key.dataRepresentation
                approvalPublic = key.publicKey.derRepresentation
            }
        } else {
            // The Simulator: a software key, registered the same way.
            let key = savedApproval.flatMap { try? P256.Signing.PrivateKey(rawRepresentation: $0) } ?? {
                let key = P256.Signing.PrivateKey()
                Keychain.save(key.rawRepresentation.base64EncodedString(), for: Self.approvalKeyAccount)
                return key
            }()
            softwareApproval = key
            approvalPublic = key.publicKey.derRepresentation
        }
        #endif
        approvalHandle = handle
        self.approvalPublic = approvalPublic
        softwareApprovalKey = softwareApproval
    }

    #if os(iOS)
    /// The approval key's access control: the faces or fingerprints enrolled now, and nothing
    /// else. A new enrollment ends the key, and the passcode never unlocks it.
    nonisolated static let approvalKeyFlags: SecAccessControlCreateFlags = [.privateKeyUsage, .biometryCurrentSet]

    /// A new approval key in the Secure Enclave, kept with the biometrics it was made under;
    /// none on a phone with no passcode, or no Face ID or Touch ID enrolled.
    private static func makeApprovalKey() -> SecureEnclave.P256.Signing.PrivateKey? {
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, approvalKeyFlags, nil),
              let key = try? SecureEnclave.P256.Signing.PrivateKey(accessControl: access) else { return nil }
        Keychain.save(key.dataRepresentation.base64EncodedString(), for: approvalKeyAccount)
        if let domain = biometricsDomain() { Keychain.save(domain.base64EncodedString(), for: approvalDomainAccount) }
        return key
    }

    /// The enrolled faces or fingerprints, as an opaque value that changes when they do; nil
    /// when none are enrolled.
    private static func biometricsDomain() -> Data? {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return nil }
        return context.evaluatedPolicyDomainState
    }
    #endif

    func renewApprovalKeyIfBiometricsChanged() -> Bool {
        #if os(iOS)
        guard approvalHandle != nil, SecureEnclave.isAvailable else { return false }
        let made = Keychain.load(Self.approvalDomainAccount).flatMap { Data(base64Encoded: $0) }
        let now = Self.biometricsDomain()
        guard made != now else { return false }
        // A key made under other biometrics, or with none enrolled now: it can't sign again.
        Keychain.delete(Self.approvalKeyAccount)
        Keychain.delete(Self.approvalDomainAccount)
        endStep()
        if let key = Self.makeApprovalKey() {
            approvalHandle = key.dataRepresentation
            approvalPublic = key.publicKey.derRepresentation
        } else {
            approvalHandle = nil
            approvalPublic = Data()
        }
        return true
        #else
        return false
        #endif
    }

    var approvalPublicKeyDER: Data { approvalPublic }

    var usesSecureEnclave: Bool { enclaveKey != nil }

    var publicKeyDER: Data {
        enclaveKey?.publicKey.derRepresentation ?? softwareKey?.publicKey.derRepresentation ?? Data()
    }

    func sign(_ message: Data, reason: String) async throws -> String {
        try await authenticate(reason: reason)
        return try signConfirmed(message)
    }

    func sign(_ message: Data, reason: String, grants: Bool) async throws -> String {
        try await authenticate(reason: reason, biometricsOnly: grants)
        return try signConfirmed(message)
    }

    func signApproval(_ message: Data, reason: String) async throws -> String {
        try await authenticate(reason: reason, biometricsOnly: true)
        return try signApprovalConfirmed(message)
    }

    func signApprovalConfirmed(_ message: Data) throws -> String {
        guard let step, Date().timeIntervalSince(step.at) < Self.stepWindow else {
            endStep()
            throw SignerError.notAuthenticated
        }
        if let approvalHandle {
            // The key itself checks the Face ID this context holds.
            let key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: approvalHandle, authenticationContext: step.context)
            return try key.signature(for: message).derRepresentation.base64EncodedString()
        }
        if let softwareApprovalKey {
            return try softwareApprovalKey.signature(for: message).derRepresentation.base64EncodedString()
        }
        throw SignerError.notAuthenticated
    }

    /// The step is over: its Face ID covers no more signatures.
    private func endStep() {
        step?.context.invalidate()
        step = nil
    }

    func signConfirmed(_ message: Data) throws -> String {
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

    /// `biometricsOnly`: an approval's prompt, which offers no passcode; the device key's
    /// takes either, as Android's signing key does.
    private func authenticate(reason: String, biometricsOnly: Bool = false) async throws {
        let policy: LAPolicy = biometricsOnly ? .deviceOwnerAuthenticationWithBiometrics : .deviceOwnerAuthentication
        let context = LAContext()
        if reusesUnlock {
            reusesUnlock = false
            context.touchIDAuthenticationAllowableReuseDuration = Self.unlockReuse
        }
        var error: NSError?
        guard context.canEvaluatePolicy(policy, error: &error) else {
            #if targetEnvironment(simulator)
            endStep()
            step = (context, Date())
            return // No Face ID enrolled in the Simulator: let the demo through.
            #else
            throw SignerError.notAuthenticated
            #endif
        }
        let ok = try await context.evaluatePolicy(policy, localizedReason: reason)
        if !ok { throw SignerError.notAuthenticated }
        // This Face ID confirms a new step: the last one's is over.
        endStep()
        step = (context, Date())
    }
}
