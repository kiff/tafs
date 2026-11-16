import Foundation
import WatchConnectivity

/// Asks the iPhone. The watch holds no session and no key (docs/watch-app.md): every read and
/// answer is the phone's, sent live with a reply and never queued.
@MainActor
protocol PhoneAsking {
    /// The phone's reply, or nil when the phone isn't in reach or didn't answer.
    func ask(_ request: WatchRequest) async -> WatchReply?
}

/// The watch's end of WatchConnectivity.
final class PhoneLink: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = PhoneLink()

    func start() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}
}

// In an extension, so the class itself isn't main-actor isolated: WatchConnectivity calls its
// delegate methods on a queue of its own.
extension PhoneLink: PhoneAsking {
    @MainActor
    func ask(_ request: WatchRequest) async -> WatchReply? {
        let session = WCSession.default
        // Just launched: the session may still be activating. Wait for it a moment.
        for _ in 0..<20 where session.activationState != .activated {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        guard session.activationState == .activated, session.isReachable else { return nil }
        let data = WatchMessage.encode(request)
        return await withCheckedContinuation { (done: CheckedContinuation<WatchReply?, Never>) in
            session.sendMessageData(data, replyHandler: { @Sendable reply in
                done.resume(returning: WatchMessage.decode(WatchReply.self, from: reply))
            }, errorHandler: { @Sendable _ in
                done.resume(returning: nil)
            })
        }
    }
}
