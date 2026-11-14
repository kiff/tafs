import UIKit

/// The device the app runs on, in the words the owner knows (kiff/tafs#85): KIFF lists
/// each signed-in device by this name, so the owner can tell their iPhone from their iPad
/// and their Mac.
@MainActor
enum ThisDevice {
    /// The Mac build (Mac Catalyst, docs/mac-app.md).
    nonisolated static var isMac: Bool {
        #if targetEnvironment(macCatalyst)
        true
        #else
        false
        #endif
    }

    /// "iPhone", "iPad" or "Mac". The model, not the idiom: an iPhone-only build on an iPad
    /// is still the owner's iPad.
    static var kind: String { kind(model: UIDevice.current.model, mac: isMac) }
    /// The platform KIFF records for this device's key.
    static var platform: String { platform(kind: kind) }
    /// What KIFF records the device as: its list draws the name from it, in the reader's language.
    static var kiffKind: String { kiffKind(kind: kind) }

    /// The Mac build reports an iPad's model, so the build decides before the model does.
    nonisolated static func kind(model: String, mac: Bool) -> String {
        if mac { return "Mac" }
        return model.hasPrefix("iPad") ? "iPad" : "iPhone"
    }

    nonisolated static func platform(kind: String) -> String {
        switch kind {
        case "Mac": "macos"
        case "iPad": "ipados"
        default: "ios"
        }
    }

    /// KIFF's kinds name phones and tablets; a computer is "other".
    nonisolated static func kiffKind(kind: String) -> String {
        switch kind {
        case "Mac": "other"
        case "iPad": "ipad"
        default: "iphone"
        }
    }
}
