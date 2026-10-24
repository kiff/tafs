import Foundation

/// Where the app is asked to open from outside a view: Siri's "Halt all tafs" when it
/// couldn't finish (kiff/tafs#210), or a notification's action. One asked for before the
/// app is ready waits here until it is.
@MainActor
final class Routes {
    static let shared = Routes()

    var onOpen: ((String) -> Void)? {
        didSet { if let onOpen, let waiting = pending { pending = nil; onOpen(waiting) } }
    }
    private(set) var pending: String?

    func open(_ destination: String) {
        if let onOpen { onOpen(destination) } else { pending = destination }
    }
}
