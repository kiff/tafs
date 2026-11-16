import Foundation
#if canImport(WatchConnectivity) && !targetEnvironment(macCatalyst)
import WatchConnectivity
#endif

/// The watch's reads, made on the phone with the session it keeps (docs/watch-app.md). The
/// watch holds no session and no key: it asks, and the phone reads or does it.
@MainActor
protocol WatchReading {
    /// Whether the phone keeps a KIFF session to answer with.
    var signedIn: Bool { get }
    /// Needs you: tafs cloud's count, and KIFF's held calls in the phone's words.
    func needsYou(now: Date) async throws -> WatchNeedsYou
}

/// Answers the watch's requests (docs/watch-app.md, "The relay"). It answers only the kinds
/// `WatchRequest` decodes, only when the request is complete and live, and only while the
/// phone is signed in: anything else is refused before any read, and nothing here signs.
/// Refuse, Halt, Halt all and Coach are known and not answered from the watch yet.
@MainActor
enum WatchRelay {
    static func answer(_ data: Data, now: Date, reading: WatchReading) async -> WatchReply {
        guard let request = WatchMessage.decode(WatchRequest.self, from: data), request.isComplete else {
            return WatchReply(.refused)
        }
        // Live only: a request held up somewhere is never acted on late.
        guard abs(now.timeIntervalSince(request.made)) <= WatchRequest.maxAge else { return WatchReply(.stale) }
        guard reading.signedIn else { return WatchReply(.signedOut) }
        switch request.kind {
        case .needsYou:
            do {
                return WatchReply(.ok, needsYou: try await reading.needsYou(now: now))
            } catch {
                return WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff)
            }
        case .tafs, .refuse, .halt, .haltAll, .coach:
            return WatchReply(.notYet)
        }
    }
}

/// The reads with the session the app keeps in the Keychain, as a hold's notification is filled
/// in (`HoldNotification.fill`): the phone may be locked, with tafs not open.
@MainActor
struct KeptWatchReading: WatchReading {
    let kiff: KIFFClient
    let cloud: TafsCloud

    init() {
        let token = Keychain.load(HoldNotification.sessionAccount)
        let kiff = KIFFClient()
        kiff.token = token
        self.init(kiff: kiff, cloud: TafsCloud(token: token))
    }

    init(kiff: KIFFClient, cloud: TafsCloud) {
        self.kiff = kiff
        self.cloud = cloud
    }

    var signedIn: Bool { kiff.token != nil }

    func needsYou(now: Date) async throws -> WatchNeedsYou {
        async let home = try? cloud.home()
        async let tafs = try? cloud.tafs()
        async let account = try? kiff.me()
        let held = try await kiff.heldCalls()
        let names = await tafs ?? []
        let zone = await account?.timeZone.flatMap(TimeZone.init(identifier:)) ?? TimeZone(identifier: "UTC")!
        let calls = held
            .map { hold in (hold, KIFFMapping.held(hold, tafID: UUID())) }
            .sorted { $0.1.expiresAt < $1.1.expiresAt }
            .map { pair -> WatchNeedsYou.Call in
                let (hold, call) = pair
                // As the notification names it: the taf by its KIFF agent, Coach's own agent "setup".
                let taf = names.first { $0.kiffAgent == hold.subject }?.name ?? (hold.subject == "setup" ? Words.Coach.title : nil)
                let what = [call.proposed.summary, call.proposed.amount?.formatted].compactMap { $0 }.joined(separator: ", ")
                return WatchNeedsYou.Call(id: hold.id, taf: taf, what: what, reason: call.reason,
                                          answerBy: HoldNotification.time(call.expiresAt, in: zone))
            }
        let count = await home?.needsYou?.count ?? calls.count
        return WatchNeedsYou(count: max(count, calls.count), calls: calls, at: now)
    }
}

#if canImport(WatchConnectivity) && !targetEnvironment(macCatalyst)
/// The phone's end of WatchConnectivity. Started at launch, even a launch in the background
/// that the watch's message caused, so the watch is answered with tafs closed.
final class WatchLink: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = WatchLink()

    func start() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}
    func sessionDidBecomeInactive(_ session: WCSession) {}
    /// Another watch was paired: answer that one from now on.
    func sessionDidDeactivate(_ session: WCSession) { session.activate() }

    func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void) {
        let reply = Reply(replyHandler)
        Task { @MainActor in
            let answer = await WatchRelay.answer(messageData, now: Date(), reading: KeptWatchReading())
            reply.call(WatchMessage.encode(answer))
        }
    }

    /// WatchConnectivity's reply handler, handed to the main actor once the answer is ready.
    private struct Reply: @unchecked Sendable {
        let call: (Data) -> Void
        init(_ call: @escaping (Data) -> Void) { self.call = call }
    }
}
#endif
