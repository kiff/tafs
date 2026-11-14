import UIKit

/// The device the app runs on, in the words the owner knows (kiff/tafs#85): KIFF lists
/// each signed-in device by this name, so the owner can tell their iPhone from their iPad.
@MainActor
enum ThisDevice {
    /// "iPhone" or "iPad". The model, not the idiom: an iPhone-only build on an iPad is
    /// still the owner's iPad.
    static var kind: String { UIDevice.current.model.hasPrefix("iPad") ? "iPad" : "iPhone" }
    /// The platform KIFF records for this device's key.
    static var platform: String { kind == "iPad" ? "ipados" : "ios" }
    /// What KIFF records the device as: its list draws the name from it, in the reader's language.
    static var kiffKind: String { kind == "iPad" ? "ipad" : "iphone" }
}
