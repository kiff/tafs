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
        // WatchConnectivity can neither reply nor fail (seen on the simulator, possible on a watch): the watch would wait on
        // its send screen with no words. Past the limit it's "Your phone isn't in reach", and a Send tried again goes under
        // the same id, which tafs cloud takes once.
        let reply = await PhoneWait.first { (answer: @escaping @Sendable (WatchReply?) -> Void) in
            session.sendMessageData(data, replyHandler: { @Sendable reply in
                answer(WatchMessage.decode(WatchReply.self, from: reply))
            }, errorHandler: { @Sendable _ in
                answer(nil)
            })
        }
        // Whatever was asked, a signed-out phone drops the account's zone (agentgabo on #1590).
        WatchAccountZone.heard(reply)
        return reply
    }
}

/// The first of the phone's reply, its error and a time limit: one answer, at most `limit` seconds after asking.
enum PhoneWait {
    /// Longer than the phone's own reads take (tafs cloud answers an ask in seconds), short enough that the person isn't left
    /// waiting on a screen with no words.
    static let limit: TimeInterval = 20

    @MainActor
    static func first<T: Sendable>(within limit: TimeInterval = PhoneWait.limit,
                                   _ start: (@escaping @Sendable (T?) -> Void) -> Void) async -> T? {
        await withCheckedContinuation { (done: CheckedContinuation<T?, Never>) in
            let once = Once(done)
            start { once.resume($0) }
            DispatchQueue.main.asyncAfter(deadline: .now() + limit) { once.resume(nil) }
        }
    }

    /// Resumes its continuation once, whichever of the reply, the error and the limit comes first.
    private final class Once<T: Sendable>: @unchecked Sendable {
        private let lock = NSLock()
        private var done: CheckedContinuation<T?, Never>?

        init(_ done: CheckedContinuation<T?, Never>) { self.done = done }

        func resume(_ value: T?) {
            lock.lock()
            let waiting = done
            done = nil
            lock.unlock()
            waiting?.resume(returning: value)
        }
    }
}
