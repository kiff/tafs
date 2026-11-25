import Foundation
import WatchConnectivity

/// Asks the iPhone. The watch holds no session and no key (docs/watch-app.md): every read and
/// answer is the phone's, sent live with a reply and never queued.
@MainActor
protocol PhoneAsking {
    /// The phone's reply, or nil when the phone isn't in reach or didn't answer.
    func ask(_ request: WatchRequest) async -> WatchReply?
    /// The phone's reply, or why there's none, each in its own words (row 44, D).
    func reach(_ request: WatchRequest) async -> PhoneReach
}

extension PhoneAsking {
    /// A phone that only answers or doesn't (the tests' phones): no answer is "not in reach", as before.
    func reach(_ request: WatchRequest) async -> PhoneReach {
        guard let reply = await ask(request) else { return .failed(.notReachable) }
        return .reply(reply)
    }
}

/// Why an ask got no reply, so the wrist says which, not "not in reach" for all of them (row 44, D; as Wear OS's
/// tafs#1588 tells them apart).
enum PhoneFailure: Error, Equatable, Sendable {
    /// The phone isn't in reach: the watch can't find it.
    case notReachable
    /// The phone was asked and didn't answer within `PhoneWait.limit`.
    case noAnswer
    /// tafs isn't on the paired iPhone.
    case noApp
    /// The link itself failed, or the reply couldn't be read.
    case link

    /// What the watch says, with Try again under it.
    var line: String {
        switch self {
        case .notReachable: return Words.Watch.phoneNotReachable
        case .noAnswer: return Words.Watch.phoneNoAnswer
        case .noApp: return Words.Watch.phoneNoApp
        case .link: return Words.Watch.phoneLinkFailed
        }
    }

    /// What a screen that tries again by itself says (docs/watch-app.md, "Linking tafs"): one calm line while the phone is
    /// out of reach, slow or the link is down, with no Try again; tafs missing from the phone is said as it is, since only
    /// installing it helps.
    var waiting: String { self == .noApp ? Words.Watch.phoneNoApp : Words.Watch.linking }

    /// The send itself failed, so nothing was answered and a read may go once more. Not the limit: it's used up.
    var sendFailed: Bool { self == .notReachable || self == .link }
}

/// The phone's reply, or why there's none.
enum PhoneReach: Equatable, Sendable {
    case reply(WatchReply)
    case failed(PhoneFailure)

    var reply: WatchReply? {
        if case .reply(let reply) = self { return reply }
        return nil
    }
}

/// One ask's way to the phone, apart from WatchConnectivity so it can be tested (`PhoneRouteTests`), so a phone that's
/// there isn't called unreachable (row 44, D; Wear OS's `PhoneRoute`, tafs#1588):
/// - just launched, the session may still be activating: it's waited for, 2 s at most;
/// - the link can say "not reachable" for a moment after the app starts or wakes: it's looked at once more after half a
///   second before the phone counts as out of reach;
/// - a read whose send failed goes once more. A write is sent once: a failed send may still have arrived, so the person
///   decides whether to try again, under the same id;
/// - all of it inside the one `PhoneWait.limit`.
@MainActor
struct PhoneRoute {
    /// How long the link is given to come back before the phone counts as out of reach.
    static let secondLook: TimeInterval = 0.5

    var activated: () -> Bool
    /// tafs is on the paired iPhone.
    var installed: () -> Bool
    var reachable: () -> Bool
    /// One send, within the given seconds: the reply's bytes, or why not.
    var send: (Data, TimeInterval) async -> Result<Data, PhoneFailure>
    var pause: (TimeInterval) async -> Void = { seconds in try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
    var now: () -> Date = Date.init

    func reach(_ request: WatchRequest) async -> PhoneReach {
        let deadline = now().addingTimeInterval(PhoneWait.limit)
        for _ in 0..<20 where !activated() { await pause(0.1) }
        guard activated() else { return .failed(.notReachable) }
        guard installed() else { return .failed(.noApp) }
        let data = WatchMessage.encode(request)
        for attempt in 1...2 {
            if !reachable() {
                await pause(Self.secondLook)
                guard reachable() else { return .failed(.notReachable) }
            }
            let left = deadline.timeIntervalSince(now())
            guard left > 0 else { return .failed(.noAnswer) }
            switch await send(data, left) {
            case .success(let bytes):
                // A reply that can't be read (a phone from before, or damaged on the way) is the link's failure, not
                // the phone's absence; it isn't sent again, since the phone did answer.
                guard let reply = WatchMessage.decode(WatchReply.self, from: bytes) else { return .failed(.link) }
                return .reply(reply)
            case .failure(let why):
                guard attempt == 1, request.kind.isRead, why.sendFailed else { return .failed(why) }
            }
        }
        return .failed(.link)
    }
}

/// The watch's end of WatchConnectivity.
final class PhoneLink: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = PhoneLink()

    func start() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Started: the phone's last word on its account (the application context) is heard first, so a watch whose phone
    /// signed out or changed account while it was away wipes before anything else.
    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        guard activationState == .activated, let push = WatchPush.from(session.receivedApplicationContext) else { return }
        Task { @MainActor in Self.account(push) }
    }

    /// The phone signed in or out (`WatchLink.account`), as the latest application context.
    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        guard let push = WatchPush.from(applicationContext) else { return }
        Task { @MainActor in Self.account(push) }
    }

    /// The phone's push (`WatchPush`), queued and delivered in order, even with the app put away: read here, on
    /// WatchConnectivity's queue, and handed to what's listening on the main actor. Whose it is comes first: an account
    /// change is applied when it's the newest heard; a data push is used only when it's the kept account's, and otherwise
    /// dropped, since it may have waited on the watch since before a switch.
    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let push = WatchPush.from(userInfo) else { return }
        Task { @MainActor in
            if push.kind == .account { return Self.account(push) }
            guard WatchAccountMark.shared.ours(push.mark) else { return }
            // A conversation deleted on the phone: its kept copy goes now, whether or not a screen shows it.
            if push.kind == .forget { WatchThreads.shared.drop(push.taf ?? WatchThreads.coach) }
            WatchAccountZone.heard(push.reply)
            WatchPushes.shared.deliver(push)
        }
    }

    /// The phone signed in or out (`WatchAccountMark.heardAccount`): it may wipe, never confirm, and an older change than
    /// the last one heard is ignored.
    @MainActor
    static func account(_ push: WatchPush) {
        guard push.kind == .account else { return }
        WatchAccountMark.shared.heardAccount(push.mark, at: push.at)
        if push.mark == nil { WatchAccountZone.forget() }
    }

    /// WatchConnectivity's error, as the wrist words it.
    static func failure(_ error: Error) -> PhoneFailure {
        switch (error as? WCError)?.code {
        case .notReachable?, .deviceNotPaired?, .sessionNotActivated?, .sessionInactive?: return .notReachable
        case .companionAppNotInstalled?, .watchAppNotInstalled?: return .noApp
        case .messageReplyTimedOut?: return .noAnswer
        default: return .link
        }
    }
}

// In an extension, so the class itself isn't main-actor isolated: WatchConnectivity calls its
// delegate methods on a queue of its own.
extension PhoneLink: PhoneAsking {
    @MainActor
    func ask(_ request: WatchRequest) async -> WatchReply? {
        await reach(request).reply
    }

    @MainActor
    func reach(_ request: WatchRequest) async -> PhoneReach {
        let session = WCSession.default
        let route = PhoneRoute(
            activated: { session.activationState == .activated },
            installed: { session.isCompanionAppInstalled },
            reachable: { session.isReachable },
            send: { data, within in
                // WatchConnectivity can neither reply nor fail (seen on the simulator, possible on a watch): the watch
                // would wait on its send screen with no words. Past the limit it's "Your phone didn't answer", and a Send
                // tried again goes under the same id, which tafs cloud takes once.
                let answer: Result<Data, PhoneFailure>? = await PhoneWait.first(within: within) { answer in
                    session.sendMessageData(data, replyHandler: { @Sendable reply in
                        answer(.success(reply))
                    }, errorHandler: { @Sendable error in
                        answer(.failure(PhoneLink.failure(error)))
                    })
                }
                return answer ?? .failure(.noAnswer)
            })
        // The person's acts go with the account mark this watch holds, so a phone signed in as someone else since does
        // nothing with them (WatchRequest.Kind.carriesMark).
        var request = request
        if request.kind.carriesMark, let kept = WatchAccountMark.shared.kept, WatchMark.isMark(kept) { request.mark = kept }
        let reached = await route.reach(request)
        // Whose answer it is, before anything in it is used: another account's wipes what was kept (`WatchAccountMark`).
        WatchAccountMark.shared.heard(reply: reached.reply)
        // Whatever was asked, a signed-out phone drops the account's zone.
        WatchAccountZone.heard(reached.reply)
        // And what the watch kept so it opens at once (row 44, D).
        if reached.reply?.outcome == .signedOut {
            WatchCache.shared.clear()
            WatchThreads.shared.clear()
        }
        return reached
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

/// The watch's own recording, to the phone and its text back (row 44, A; `WatchVoice`, behind its switch). The file goes
/// over `transferFile`, queued and delivered even while tafs isn't open on the phone; `transcribe` then waits for its text,
/// longer than a read since it covers the file's way and the recognizer. A transfer still on its way is called off when the
/// recording is thrown away, so the phone never gets it.
extension PhoneLink: VoiceSending {
    /// The file's way, the phone waiting for it, and the recognizer, for 30 s of speech.
    static let transcribeLimit: TimeInterval = 45

    @MainActor
    func transcribe(_ file: URL, id: String) async -> WatchReply? {
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return nil }
        let transfer = session.transferFile(file, metadata: [WatchVoice.metadataKey: id])
        PhoneLink.transfers[id] = transfer
        defer { PhoneLink.transfers[id] = nil }
        let data = WatchMessage.encode(WatchRequest(.transcribe, made: Date(), message: id))
        let reply = await PhoneWait.first(within: Self.transcribeLimit) { (answer: @escaping @Sendable (WatchReply?) -> Void) in
            session.sendMessageData(data, replyHandler: { @Sendable reply in
                answer(WatchMessage.decode(WatchReply.self, from: reply))
            }, errorHandler: { @Sendable _ in
                answer(nil)
            })
        }
        if reply == nil { transfer.cancel() }
        WatchAccountZone.heard(reply)
        return reply
    }

    @MainActor
    func cancelTranscribe(_ id: String) {
        PhoneLink.transfers.removeValue(forKey: id)?.cancel()
    }

    /// Each recording's transfer while its text is asked for.
    @MainActor private static var transfers: [String: WCSessionFileTransfer] = [:]
}
