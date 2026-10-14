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
    /// The tafs tafs cloud runs for this account, by the KIFF agent they act as.
    @Published private(set) var cloudTafs: [String: TafsCloud.CloudTaf] = [:]
    @Published var lastError: String?

    let ownerName: String
    let kiff: KIFFClient
    let cloud: TafsCloud
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
         cloud: TafsCloud = TafsCloud(),
         store: SampleStore? = nil,
         clock: @escaping () -> Date = Date.init) {
        self.ownerName = ownerName
        self.signer = signer
        self.kiff = kiff
        self.cloud = cloud
        self.store = store
        self.clock = clock
    }

    /// Restores where the owner left off: signed in to KIFF, or the saved sample.
    func restore() async {
        if let token = Keychain.load(Self.tokenAccount) {
            kiff.token = token
            cloud.token = token
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
                cloud.token = token.accessToken
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
        cloud.token = nil
        cloudTafs = [:]
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
            show(mandates, exceptions)
            await refreshCloud()
        } catch KIFFClient.KIFFError.unauthorized {
            await signOut()
            lastError = KIFFClient.KIFFError.unauthorized.localizedDescription
        } catch {
            lastError = "Couldn't reach KIFF: \(error.localizedDescription)"
        }
    }

    /// Shows what KIFF returned. A held call keeps its id across refreshes (it is derived
    /// from KIFF's id), so the lock screen notifies once and rows are not recreated.
    func show(_ mandates: [KIFFClient.Mandate], _ exceptions: [KIFFClient.CardException]) {
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
    }

    // MARK: Sample mode

    func startSample() async {
        mode = .sample
        await loadSample()
        save()
    }

    // MARK: Starting a taf

    /// Tafs start on the phone in both modes: on KIFF, through `startOnKIFF`.
    var canStartTafs: Bool { mode != .signedOut }

    func draft(job: String) -> TafDraft { TafTemplates.draft(from: job) }

    /// Issues the Card (after Face ID) and starts the taf.
    @discardableResult
    func start(_ draft: TafDraft, confirm: Bool = true) async -> Taf? {
        if confirm {
            do {
                _ = try await signer.sign(answer: .approveOnce, heldID: "card-\(UUID().uuidString)", reason: "Issue \(draft.name)'s Card")
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

    // MARK: Starting a taf on KIFF

    /// The steps of starting a taf on KIFF, in order, for the progress list.
    enum StartStep: Int, CaseIterable, Comparable {
        case confirm, agent, cards, key, run
        static func < (a: StartStep, b: StartStep) -> Bool { a.rawValue < b.rawValue }
        var label: String {
            switch self {
            case .confirm: return "Confirm with Face ID"
            case .agent: return "Add the agent to KIFF"
            case .cards: return "Issue its Cards"
            case .key: return "Give it its gateway key"
            case .run: return "Start its first run"
            }
        }
    }

    struct StartFailure: Error, LocalizedError, Equatable {
        let step: StartStep
        let message: String
        var errorDescription: String? { message }
    }

    /// The account's connected tools, to choose from.
    func connectedTools() async throws -> [KIFFClient.Connection] {
        try await kiff.connections()
    }

    /// Starts a taf on KIFF: the owner confirms with Face ID, then the app adds the agent,
    /// issues one Card per tool, mints its gateway key and hands it to tafs cloud, which
    /// runs it on its job. The key is never kept on the phone. Returns the first run's session.
    func startOnKIFF(name: String, job: String, limits: [ToolLimit], agentID: String? = nil,
                     progress: (StartStep) -> Void = { _ in }) async throws -> String {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let agent = agentID ?? TafPlan.agentID(for: name)
        let cards: [KIFFClient.CardRequest]
        do { cards = try TafPlan.cards(agentID: agent, limits: limits) } catch {
            throw StartFailure(step: .confirm, message: error.localizedDescription)
        }

        progress(.confirm)
        do {
            _ = try await signer.sign(answer: .approveOnce, heldID: "start-\(agent)", reason: "Start \(name) with \(cards.count) Card\(cards.count == 1 ? "" : "s")")
        } catch {
            throw StartFailure(step: .confirm, message: "Nothing was started: Face ID did not confirm.")
        }

        progress(.agent)
        try await attempt(.agent) { try await kiff.createAgent(id: agent) }
        progress(.cards)
        try await attempt(.cards) { for card in cards { try await kiff.issueCard(card) } }
        progress(.key)
        let key = try await attempt(.key) { try await kiff.mintGatewayKey(agentID: agent, label: "tafs · \(name)") }
        progress(.run)
        let started = try await attempt(.run) { try await cloud.start(name: name, job: job, agentKey: key.plaintext) }
        cloudTafs[agent] = started.taf
        await refresh()
        return started.session
    }

    /// Runs one step of starting a taf; a failure says what is already in KIFF.
    private func attempt<T>(_ step: StartStep, _ work: () async throws -> T) async throws -> T {
        do { return try await work() } catch {
            throw StartFailure(step: step, message: Self.startMessage(step, error))
        }
    }

    private static func startMessage(_ step: StartStep, _ error: Error) -> String {
        if case KIFFClient.KIFFError.server(403, _) = error {
            switch step {
            case .key: return "The agent and its Cards are in KIFF, but only an admin can give it its gateway key. Ask an admin to start it, or to make you one."
            default: return "Your role on this KIFF account can't start tafs. An admin or an editor can."
            }
        }
        if step == .agent, case KIFFClient.KIFFError.server(409, _) = error {
            return "KIFF already has an agent with that name. Try again: the next one gets a new name."
        }
        let done: String
        switch step {
        case .confirm, .agent: done = "Nothing was started."
        case .cards: done = "The agent is in KIFF; some of its Cards may be too."
        case .key: done = "The agent and its Cards are in KIFF."
        case .run: done = "The agent, its Cards and its key are in KIFF, but tafs cloud didn't start it."
        }
        return "\(done) \(error.localizedDescription)"
    }

    /// Runs a taf now, outside its schedule. Returns the run's session.
    func runNow(_ cloudTaf: TafsCloud.CloudTaf) async -> String? {
        do { return try await cloud.runNow(cloudTaf.id) } catch {
            lastError = "The run didn't start: \(error.localizedDescription)"
            return nil
        }
    }

    /// Reads which tafs tafs cloud runs; KIFF stays the source of the Cards and held calls.
    func refreshCloud() async {
        guard mode == .kiff, let list = try? await cloud.tafs() else { return }
        var byAgent: [String: TafsCloud.CloudTaf] = [:]
        for t in list { if let a = t.kiffAgent { byAgent[a] = t } }
        cloudTafs = byAgent
        for i in tafs.indices {
            guard let holder = holderIDs.first(where: { $0.value == tafs[i].id })?.key, let c = byAgent[holder] else { continue }
            tafs[i].name = c.name
            if tafs[i].state != .waiting {
                tafs[i].state = c.paused ? .paused : .working
                tafs[i].now = c.paused ? "Paused" : c.nextRun.map { "Next run \($0.shortTime)" } ?? "Running"
            }
        }
    }

    /// The tafs cloud taf behind a taf, when there is one.
    func cloudTaf(for tafID: UUID) -> TafsCloud.CloudTaf? {
        holderIDs.first(where: { $0.value == tafID }).flatMap { cloudTafs[$0.key] }
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

    /// Whether the owner can answer `call` from this phone. KIFF's held calls wait until
    /// KIFF checks the phone's signature; until then they are answered in KIFF.
    func canAnswer(_ call: HeldCall) -> Bool {
        call.kiffID == nil || kiff.serverVerifiesSignatures
    }

    /// Where a held call from KIFF is answered while the phone cannot answer it.
    func answerPage(for call: HeldCall) -> URL? {
        call.kiffID.map(kiff.answerPage(for:))
    }

    func answer(_ call: HeldCall, with answer: Answer) async {
        guard held.contains(where: { same($0, call) }) else { return }
        guard canAnswer(call) else {
            lastError = "Answer this one in KIFF. Answering from the phone arrives once KIFF checks the phone's signature."
            return
        }
        if call.kiffID == nil, clock() > call.expiresAt {
            resolve(call)
            log(call.tafID, .refused, "Expired: \(call.proposed.summary)", "Nobody answered in time")
            save()
            return
        }
        let signature: String
        do {
            signature = try await signer.sign(answer: answer, heldID: call.kiffID ?? call.id.uuidString, reason: "Answer \(call.proposed.summary)")
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

    /// Removes `call`, matched by KIFF's id when it has one, so a refresh while the
    /// answer was on its way still removes the right hold.
    private func resolve(_ call: HeldCall) {
        held.removeAll { same($0, call) }
        notifyHeld()
    }

    private func same(_ a: HeldCall, _ b: HeldCall) -> Bool {
        if let id = b.kiffID { return a.kiffID == id }
        return a.id == b.id
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
