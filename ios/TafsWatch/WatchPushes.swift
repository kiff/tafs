import Foundation

/// What the phone hands the watch on its own (docs/watch-app.md, "Kept and pushed"): tafs cloud pushed the iPhone, the iPhone
/// read the change with its session and passed it on with WatchConnectivity's queued `transferUserInfo`. The watch takes it the
/// moment it arrives, whether a screen is on or not, so the thread on the wrist moves with the phone's and with no ask of its
/// own. A screen that shows what a push changes listens: Coach's thread, a taf's thread, the Inbox.
@MainActor
protocol WatchPushListening: AnyObject {
    func heard(_ push: WatchPush)
}

@MainActor
final class WatchPushes {
    static let shared = WatchPushes()

    private struct Listener {
        weak var value: WatchPushListening?
    }

    private var listeners: [Listener] = []

    /// Listens for as long as the listener lives: nothing to undo when it goes.
    func listen(_ listener: WatchPushListening) {
        listeners.removeAll { $0.value == nil || $0.value === listener }
        listeners.append(Listener(value: listener))
    }

    func deliver(_ push: WatchPush) {
        listeners.removeAll { $0.value == nil }
        for listener in listeners { listener.value?.heard(push) }
    }

    /// How many listen now: for the tests.
    var count: Int { listeners.filter { $0.value != nil }.count }
}
