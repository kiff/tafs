import LocalAuthentication

/// What this device asks for before it signs, in the words the owner knows (kiff/tafs#87):
/// Face ID on most iPhones, Touch ID on many iPads and Macs, else the passcode.
enum Biometry {
    /// "Face ID", "Touch ID" or "your passcode", for "Approve once with …".
    static let name: String = {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        default: return "your passcode"
        }
    }()

    /// The SF Symbol for it.
    static let symbol: String = {
        switch name {
        case "Face ID": return "faceid"
        case "Touch ID": return "touchid"
        default: return "lock"
        }
    }()
}
