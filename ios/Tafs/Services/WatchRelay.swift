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
    /// Your tafs, each with its state in one line.
    func tafs() async throws -> [WatchTaf]
    /// Refuses a held call as its notification does: signed with the current key, never the
    /// approval key, and only once KIFF still holds it.
    func refuse(hold: String, now: Date) async -> NotificationRefuse.Outcome
    /// Halts a taf, or with none every taf, as the app and Siri do: no Face ID.
    func halt(taf: String?) async -> HaltAll.Outcome
}

/// Answers the watch's requests (docs/watch-app.md, "The relay"). It answers only the kinds
/// `WatchRequest` decodes, only when the request is complete and live, and only while the
/// phone is signed in: anything else is refused before any read or signature. Only Refuse
/// signs, with the current key, as from its notification. Coach isn't answered from the watch yet.
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
        case .tafs:
            do {
                return WatchReply(.ok, tafs: try await reading.tafs())
            } catch {
                return WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff)
            }
        case .refuse:
            switch await reading.refuse(hold: request.hold ?? "", now: now) {
            case .refused: return WatchReply(.ok, line: Words.Watch.refused)
            case .gone: return WatchReply(.answered, line: Words.Watch.alreadyAnswered)
            case .needsApp(let why): return WatchReply(.failed, line: why)
            }
        case .halt, .haltAll:
            switch await reading.halt(taf: request.kind == .haltAll ? nil : request.taf) {
            case .done(let said): return WatchReply(.ok, line: said)
            case .needsApp(let said): return WatchReply(.failed, line: said)
            }
        case .coach:
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
    let signer: ApprovalSigning
    let tenant: String?
    let keyed: Bool
    /// Between two reads of a halt still halting.
    let wait: () async -> Void

    init() {
        let token = Keychain.load(HoldNotification.sessionAccount)
        let kiff = KIFFClient()
        kiff.token = token
        self.init(kiff: kiff, cloud: TafsCloud(token: token), signer: ApprovalSigner(),
                  tenant: Keychain.load(AppModel.tenantAccount), keyed: Keychain.load(AppModel.keyedAccount) == "1")
    }

    init(kiff: KIFFClient, cloud: TafsCloud, signer: ApprovalSigning, tenant: String?, keyed: Bool,
         wait: @escaping () async -> Void = { try? await Task.sleep(nanoseconds: 1_000_000_000) }) {
        self.kiff = kiff
        self.cloud = cloud
        self.signer = signer
        self.tenant = tenant
        self.keyed = keyed
        self.wait = wait
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

    /// Each taf with its state: halted with the account, or on its own, then paused, then its
    /// next run as tafs cloud words it.
    func tafs() async throws -> [WatchTaf] {
        let listed = try await cloud.tafs()
        let all = try? await cloud.halted(nil)
        var tafs: [WatchTaf] = []
        for taf in listed {
            let halt = all?.state == .running ? (try? await cloud.halted(taf.id)) : all
            let line: String?
            switch halt?.state {
            case .halted: line = Words.Taf.State.halted
            case .halting: line = Words.Taf.Halt.halting
            case .running, nil: line = taf.paused ? Words.Taf.State.paused : taf.nextRunWords
            }
            tafs.append(WatchTaf(id: taf.id, name: taf.name, line: line, halted: halt.map { $0.state != .running } ?? false))
        }
        return tafs
    }

    /// `NotificationRefuse.refuseKept`, answering the watch: the same signed refusal, then the
    /// lock screen in step. The phone posts no notification of its own: the watch says why.
    func refuse(hold: String, now: Date) async -> NotificationRefuse.Outcome {
        let outcome = await NotificationRefuse.refuse(hold: hold, kiff: kiff, signer: signer, tenant: tenant, keyed: keyed, now: now)
        switch outcome {
        case .refused(let left):
            PushDelegate.removeAlerts(forHold: hold)
            if left == 0 { await LockScreen.endActivities() }
        case .gone:
            PushDelegate.removeAlerts(forHold: hold)
        case .needsApp:
            break
        }
        return outcome
    }

    func halt(taf: String?) async -> HaltAll.Outcome {
        guard let taf else { return await HaltAll.run(cloud: cloud, wait: wait) }
        return await HaltAll.runOne(taf, cloud: cloud, wait: wait)
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
