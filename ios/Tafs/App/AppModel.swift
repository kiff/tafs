import Foundation
import SwiftUI

/// The app's state: the tafs, what waits for the owner, and the record.
///
/// Two modes:
/// - **Sample**: sample tafs on this phone, with the Card checked here (`CardCheck`).
/// - **KIFF**: signed in with KIFF; Cards come from `/v1/me/mandates`, held calls
///   from `/v1/me/card-exceptions`, and KIFF decides every action.
@MainActor
final class AppModel: ObservableObject {
    enum Mode: String, Codable { case signedOut, sample, kiff }

    @Published private(set) var mode: Mode = .signedOut
    @Published private(set) var tafs: [Taf] = []
    @Published private(set) var held: [HeldCall] = []
    @Published private(set) var record: [RecordEntry] = []
    @Published private(set) var refreshing = false
    @Published var lastError: String?

    let ownerName: String
    let kiff: KIFFClient
    private let signer: ApprovalSigning
    private let store: SampleStore?
    private let clock: () -> Date
    private var scriptPosition: [UUID: Int] = [:]
    private var templateOf: [UUID: String] = [:]
    private var holderIDs: [String: UUID] = [:]

    /// Called whenever the held calls change, so the lock screen can follow.
    var onHeldChanged: (@MainActor ([HeldCall], [Taf]) -> Void)?

    init(ownerName: String = "You",
         signer: ApprovalSigning = ApprovalSigner(),
         kiff: KIFFClient = KIFFClient(),
         store: SampleStore? = nil,
         clock: @escaping () -> Date = Date.init) {
        self.ownerName = ownerName
        self.signer = signer
        self.kiff = kiff
        self.store = store
        self.clock = clock
    }

    /// Restores where the owner left off: signed in to KIFF, or the saved sample.
    func restore() async {
        if let token = Keychain.load(Self.tokenAccount) {
            kiff.token = token
            mode = .kiff
            await refresh()
        } else if let snapshot = store?.load() {
            apply(snapshot)
            mode = .sample
        }
    }

    // MARK: Reading

    var signedIn: Bool { mode != .signedOut }
    func taf(_ id: UUID) -> Taf? { tafs.first { $0.id == id } }
    func entries(for tafID: UUID) -> [RecordEntry] { record.filter { $0.tafID == tafID } }
    func heldCalls(for tafID: UUID) -> [HeldCall] { held.filter { $0.tafID == tafID } }
    var workingCount: Int { tafs.filter { $0.state == .working }.count }

    // MARK: Signing in with KIFF

    static let tokenAccount = "kiff.session"

    func startSignIn() async throws -> KIFFClient.DeviceStart {
        try await kiff.startDeviceSignIn()
    }

    /// Polls until the person approves the code in KIFF, then loads their Cards.
    func finishSignIn(_ start: KIFFClient.DeviceStart) async throws {
        var interval = UInt64(max(start.interval, 2))
        let deadline = clock().addingTimeInterval(TimeInterval(start.expiresIn))
        while clock() < deadline {
            try await Task.sleep(nanoseconds: interval * 1_000_000_000)
            do {
                let token = try await kiff.pollDeviceToken(deviceCode: start.deviceCode)
                Keychain.save(token.accessToken, for: Self.tokenAccount)
                kiff.token = token.accessToken
                tafs = []; held = []; record = []
                mode = .kiff
                await refresh()
                return
            } catch KIFFClient.KIFFError.authorizationPending {
                continue
            } catch KIFFClient.KIFFError.slowDown {
                interval += 5
            }
        }
        throw KIFFClient.KIFFError.expired
    }

    func signOut() async {
        if mode == .kiff { try? await kiff.signOut() }
        Keychain.delete(Self.tokenAccount)
        kiff.token = nil
        store?.clear()
        tafs = []; held = []; record = []
        scriptPosition = [:]; templateOf = [:]
        mode = .signedOut
        notifyHeld()
    }

    /// Reloads Cards and held calls from KIFF; in sample mode, plays the next sample actions.
    func refresh() async {
        guard mode == .kiff else { playNextDemoActions(); return }
        refreshing = true
        defer { refreshing = false }
        do {
            async let cards = kiff.cards()
            async let calls = kiff.heldCalls()
            let (mandates, exceptions) = try await (cards, calls)
            tafs = KIFFMapping.tafs(from: mandates, ids: &holderIDs, owner: ownerName)
            held = exceptions.map { e in
                let id = holderIDs[e.subject] ?? UUID()
                holderIDs[e.subject] = id
                return KIFFMapping.held(e, tafID: id)
            }
            for i in tafs.indices where held.contains(where: { $0.tafID == tafs[i].id }) {
                tafs[i].state = .waiting
                tafs[i].now = "Waiting on you"
            }
            notifyHeld()
        } catch KIFFClient.KIFFError.unauthorized {
            await signOut()
            lastError = KIFFClient.KIFFError.unauthorized.localizedDescription
        } catch {
            lastError = "Couldn't reach KIFF: \(error.localizedDescription)"
        }
    }

    // MARK: Sample mode

    func startSample() async {
        mode = .sample
        await loadSample()
        save()
    }

    // MARK: Starting a taf

    /// Tafs are started on the phone in sample mode; on KIFF they arrive with the tafs runtime.
    var canStartTafs: Bool { mode == .sample }

    func draft(job: String) -> TafDraft { TafTemplates.draft(from: job) }

    /// Issues the Card (after Face ID) and starts the taf.
    @discardableResult
    func start(_ draft: TafDraft, confirm: Bool = true) async -> Taf? {
        if confirm {
            do {
                _ = try await signer.sign(answer: .approveOnce, heldID: UUID(), reason: "Issue \(draft.name)'s Card")
            } catch {
                lastError = "The Card was not issued: Face ID did not confirm."
                return nil
            }
        }
        let expires = Calendar.current.date(byAdding: .month, value: 3, to: clock()) ?? clock()
        let taf = Taf(
            name: draft.name, job: draft.job, tools: draft.tools,
            colorIndex: tafs.count,
            card: Card(rules: draft.rules, issuedBy: ownerName, expires: expires),
            state: .working, now: "Starting"
        )
        tafs.insert(taf, at: 0)
        if let key = draft.templateKey { templateOf[taf.id] = key }
        log(taf.id, .started, "\(taf.name) started", "Card issued by \(ownerName)")
        save()
        return taf
    }

    // MARK: A taf acts (sample mode)

    /// Runs `proposed` through the taf's Card: allowed actions happen, others wait or are refused.
    func propose(_ proposed: ProposedAction, by tafID: UUID) {
        guard let i = tafs.firstIndex(where: { $0.id == tafID }) else { return }
        switch CardCheck.decide(proposed, for: tafs[i]) {
        case .allowed:
            CardCheck.draw(proposed, on: &tafs[i])
            tafs[i].state = .working
            tafs[i].now = proposed.summary
            log(tafID, .done, proposed.summary, amountLine(proposed, "within the Card"))
        case .held(let reason):
            let now = clock()
            var call = HeldCall(tafID: tafID, proposed: proposed, reason: reason, heldAt: now,
                                expiresAt: now.addingTimeInterval(10 * 60))
            call.nextSteps = tafs[i].card.rules.first { $0.action == proposed.action }?.nextSteps ?? []
            held.insert(call, at: 0)
            tafs[i].state = .waiting
            tafs[i].now = "Waiting on you · \(proposed.summary)"
            log(tafID, .held, "Held: \(proposed.summary)", reason)
            notifyHeld()
        case .refused(let reason):
            tafs[i].now = "Refused · \(proposed.summary)"
            log(tafID, .refused, "Refused: \(proposed.summary)", reason)
        }
        save()
    }

    /// Plays the next scripted action of every working taf, so the sample shows the Card at work.
    func playNextDemoActions() {
        guard mode != .kiff else { return }
        for taf in tafs where taf.state == .working {
            guard let script = TafTemplates.template(templateOf[taf.id])?.script, !script.isEmpty else { continue }
            let position = scriptPosition[taf.id, default: 0]
            guard position < script.count else { continue }
            scriptPosition[taf.id] = position + 1
            propose(script[position], by: taf.id)
        }
    }

    // MARK: The owner answers

    func answer(_ call: HeldCall, with answer: Answer) async {
        guard held.contains(where: { $0.id == call.id }) else { return }
        if call.kiffID == nil, clock() > call.expiresAt {
            resolve(call)
            log(call.tafID, .refused, "Expired: \(call.proposed.summary)", "Nobody answered in time")
            save()
            return
        }
        let signature: String
        do {
            signature = try await signer.sign(answer: answer, heldID: call.id, reason: "Answer \(call.proposed.summary)")
        } catch {
            lastError = "Not sent: Face ID did not confirm."
            return
        }

        if let kiffID = call.kiffID {
            do {
                switch answer {
                case .approveOnce: try await kiff.approveOnce(kiffID)
                case .refuse: try await kiff.reject(kiffID)
                case .nextStep(let step): try await kiff.reject(kiffID, nextStep: step)
                }
            } catch {
                lastError = "KIFF didn't take the answer: \(error.localizedDescription)"
                return
            }
        }

        resolve(call)
        guard let i = tafs.firstIndex(where: { $0.id == call.tafID }) else { return }
        switch answer {
        case .approveOnce:
            if call.kiffID == nil { CardCheck.draw(call.proposed, on: &tafs[i]) }
            log(call.tafID, .approved, call.proposed.summary, amountLine(call.proposed, "approved once by \(ownerName)"), signature: signature)
            tafs[i].now = call.proposed.summary
        case .nextStep(let step):
            log(call.tafID, .refused, "\(step): \(call.proposed.summary)", "Refused, with a next step for the taf", signature: signature)
            tafs[i].now = step
        case .refuse:
            log(call.tafID, .refused, "Refused by you: \(call.proposed.summary)", call.reason, signature: signature)
            tafs[i].now = "Refused · \(call.proposed.summary)"
        }
        if tafs[i].state == .waiting, !held.contains(where: { $0.tafID == call.tafID }) {
            tafs[i].state = .working
        }
        save()
    }

    // MARK: Stopping

    func revoke(_ tafID: UUID) async {
        guard let i = tafs.firstIndex(where: { $0.id == tafID }) else { return }
        if mode == .kiff {
            do {
                for cardID in tafs[i].kiffCardIDs { try await kiff.revoke(cardID: cardID) }
            } catch {
                lastError = "KIFF didn't revoke the Card: \(error.localizedDescription)"
                return
            }
        }
        guard let j = tafs.firstIndex(where: { $0.id == tafID }) else { return }
        tafs[j].card.revoked = true
        tafs[j].state = .revoked
        tafs[j].now = "Stopped: Card revoked"
        let dropped = held.filter { $0.tafID == tafID }
        held.removeAll { $0.tafID == tafID }
        let detail = dropped.isEmpty ? "Stopped at once" : "Stopped at once. \(dropped.count) waiting action(s) refused."
        log(tafID, .revoked, "You revoked \(tafs[j].name)'s Card", detail)
        notifyHeld()
        save()
    }

    func togglePause(_ tafID: UUID) {
        guard mode == .sample, let i = tafs.firstIndex(where: { $0.id == tafID }), tafs[i].state != .revoked else { return }
        tafs[i].state = tafs[i].state == .paused ? .working : .paused
        tafs[i].now = tafs[i].state == .paused ? "Paused by you" : "Back to work"
        save()
    }

    // MARK: Sample data

    /// Fills the app with sample tafs and a morning of work. Labelled as sample in the UI.
    func loadSample() async {
        guard tafs.isEmpty else { return }
        for key in ["chargebacks", "deploys", "access", "billpay", "refunds"] {
            guard let t = TafTemplates.template(key) else { continue }
            let draft = TafDraft(name: t.name, job: t.job, tools: t.tools, rules: t.rules, templateKey: t.key)
            await start(draft, confirm: false)
        }
        // Two rounds: a few actions within the Cards, and two held for the owner.
        // Pulling to refresh plays the next round.
        playNextDemoActions()
        playNextDemoActions()
    }

    // MARK: Saving the sample

    private func save() {
        guard mode == .sample, let store else { return }
        store.save(SampleStore.Snapshot(tafs: tafs, held: held, record: record,
                                        scriptPosition: scriptPosition, templateOf: templateOf))
    }

    private func apply(_ s: SampleStore.Snapshot) {
        tafs = s.tafs; held = s.held; record = s.record
        scriptPosition = s.scriptPosition; templateOf = s.templateOf
        notifyHeld()
    }

    // MARK: Helpers

    private func resolve(_ call: HeldCall) {
        held.removeAll { $0.id == call.id }
        notifyHeld()
    }

    private func notifyHeld() { onHeldChanged?(held, tafs) }

    private func amountLine(_ p: ProposedAction, _ tail: String) -> String {
        if let amount = p.amount { return "\(amount.formatted), \(tail)" }
        return tail.prefix(1).uppercased() + tail.dropFirst()
    }

    private func log(_ tafID: UUID, _ kind: RecordEntry.Kind, _ title: String, _ detail: String, signature: String? = nil) {
        record.insert(RecordEntry(tafID: tafID, kind: kind, title: title, detail: detail, at: clock(), signature: signature), at: 0)
    }
}
