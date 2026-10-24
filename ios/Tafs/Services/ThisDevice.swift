#if canImport(UIKit)
import UIKit
#endif

/// The device the app runs on, in the words the owner knows (kiff/tafs#85): KIFF lists
/// each signed-in device by this name, so the owner can tell their iPhone from their iPad.
@MainActor
enum ThisDevice {
    #if os(macOS)
    static var kind: String { "Mac" }
    static var platform: String { "macos" }
    #else
    /// "iPhone" or "iPad". The model, not the idiom: an iPhone-only build on an iPad is
    /// still the owner's iPad.
    static var kind: String { UIDevice.current.model.hasPrefix("iPad") ? "iPad" : "iPhone" }
    /// The platform KIFF records for this device's key.
    static var platform: String { kind == "iPad" ? "ipados" : "ios" }
    #endif
}
