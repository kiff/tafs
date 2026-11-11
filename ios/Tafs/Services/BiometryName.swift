import LocalAuthentication

// Its own file so the notification extension, which words a lost approval key with it
// (KIFFClient.approvalKeyLost), compiles it too (ios/project.yml, TafsNotify).
/// What this device asks for before it signs, in the words the owner knows (kiff/tafs#87):
/// Face ID on most iPhones, Touch ID on many iPads and Macs, else the passcode.
enum Biometry {
    /// "Face ID", "Touch ID" or "your passcode", for "Approve once with …".
    static let name: String = {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return Words.Approval.faceId
        case .touchID: return Words.Approval.touchId
        default: return Words.Approval.yourPasscode
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

extension Biometry {
    /// "Face ID" or "Touch ID" when the device has one, for the setup words.
    static var kind: String? { name == Words.Approval.yourPasscode ? nil : name }
}
