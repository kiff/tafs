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
    /// planque, which keeps each taf's vendor sign-ins (kiff/planque, #51).
    let planque: PlanqueClient
    /// The vendor's sign-in page, opened for a connection the owner approved.
    private let vendorSignIn: VendorSigningIn
    /// The KIFF account this phone signed in to; part of every signed answer.
    var kiffTenant: String?
    /// Whether this phone's KIFF session holds its signing key. A session from before
    /// the phone sent its key cannot answer: sign in again.
    var sessionHasKey = false
    private let signer: ApprovalSigning
    private let store: SampleStore?
    private let clock: () -> Date
    private var scriptPosition: [UUID: Int] = [:]
    private var templateOf: [UUID: String] = [:]
    private var holderIDs: [String: UUID] = [:]

    /// Called whenever the held calls change, so the lock screen can follow.
    var onHeldChanged: (@MainActor ([HeldCall], [Taf]) -> Void)?

    /// This phone's push token (hex), once iOS gives it (`pushTokenArrived`).
    @Published private(set) var pushToken: String?
    /// Whether tafs cloud can alert this account's phones about held calls while tafs is
    /// closed; nil until tafs cloud has said.
    @Published private(set) var alertsReady: Bool?
    @Published private(set) var turningOnAlerts = false
    /// Asks iOS for a push token; the app delegate hands it to `pushTokenArrived`.
    var requestPushToken: (@MainActor () -> Void)?
    /// Asks the owner to allow notifications. Returns whether they are allowed.
    var askNotificationPermission: (@MainActor () async -> Bool)?
    /// Which APNs this build's token belongs to: Xcode's builds use the sandbox.
    static var pushEnvironment: String {
        #if DEBUG
        return "sandbox"
        #else
        return "production"
        #endif
    }

    init(ownerName: String = "You",
         signer: ApprovalSigning = ApprovalSigner(),
         kiff: KIFFClient = KIFFClient(),
         cloud: TafsCloud = TafsCloud(),
         planque: PlanqueClient = PlanqueClient(),
         vendorSignIn: VendorSigningIn = VendorSignIn(),
         store: SampleStore? = nil,
         clock: @escaping () -> Date = Date.init) {
        self.ownerName = ownerName
        self.signer = signer
        self.kiff = kiff
        self.cloud = cloud
        self.planque = planque
        self.vendorSignIn = vendorSignIn
        self.store = store
        self.clock = clock
    }

    /// Restores where the owner left off: signed in to KIFF, or the saved sample.
    func restore() async {
        if let token = Keychain.load(Self.tokenAccount) {
            kiff.token = token
            cloud.token = token
            kiffTenant = Keychain.load(Self.tenantAccount)
            sessionHasKey = Keychain.load(Self.keyedAccount) == "1"
            mode = .kiff
            await refresh()
            await syncPush()
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
    static let tenantAccount = "kiff.tenant"
    static let keyedAccount = "kiff.session-keyed"

    /// Starts signing in with this phone's signing key, so KIFF accepts its answers
    /// only with that key's signature.
    func startSignIn() async throws -> KIFFClient.DeviceStart {
        try await kiff.startDeviceSignIn(publicKey: signer.publicKeyDER, deviceName: "tafs on iPhone")
    }

    /// Polls until the person approves the code in KIFF, then loads their Cards.
    func finishSignIn(_ start: KIFFClient.DeviceStart) async throws {
        var interval = UInt64(max(start.interval, 2))
        let deadline = clock().addingTimeInterval(TimeInterval(start.expiresIn))
        while clock() < deadline {
            try await Task.sleep(nanoseconds: interval * 1_000_000_000)
            do {
                let token = try await kiff.pollDeviceToken(deviceCode: start.deviceCode)
                await useSession(token.accessToken, tenant: token.tenant)
                return
            } catch KIFFClient.KIFFError.authorizationPending {
                continue
            } catch KIFFClient.KIFFError.slowDown {
                interval += 5
            }
        }
        throw KIFFClient.KIFFError.expired
    }

    /// Starts using a KIFF session: keeps it in the Keychain, clears what belonged to any
    /// earlier session, and loads this one's Cards and tafs. A session from the device-flow
    /// sign-in names its account (`tenant`) and holds this phone's signing key.
    func useSession(_ token: String, tenant: String? = nil) async {
        Keychain.save(token, for: Self.tokenAccount)
        if let tenant {
            Keychain.save(tenant, for: Self.tenantAccount)
            Keychain.save("1", for: Self.keyedAccount)
        } else {
            Keychain.delete(Self.tenantAccount)
            Keychain.delete(Self.keyedAccount)
        }
        kiffTenant = tenant
        sessionHasKey = tenant != nil
        kiff.token = token
        cloud.token = token
        startAttempt = nil
        holderIDs = [:]; chats = [:]; chatErrors = [:]; memories = [:]; memoryErrors = [:]
        unfinishedRemovals = []
        grants = []
        accountGeneration += 1
        tafs = []; held = []; record = []
        alertsReady = nil
        canAnswerHolds = nil
        mode = .kiff
        await refresh()
        await syncPush()
    }

    func signOut() async {
        if mode == .kiff, let pushToken { try? await cloud.unregisterPush(deviceToken: pushToken) }
        if mode == .kiff { try? await kiff.signOut() }
        Keychain.delete(Self.tokenAccount)
        Keychain.delete(Self.tenantAccount)
        Keychain.delete(Self.keyedAccount)
        kiff.token = nil
        cloud.token = nil
        cloudTafs = [:]
        chats = [:]; chatErrors = [:]; memories = [:]; memoryErrors = [:]
        holderIDs = [:]
        startAttempt = nil
        unfinishedRemovals = []
        grants = []
        accountGeneration += 1
        kiffTenant = nil
        sessionHasKey = false
        alertsReady = nil
        canAnswerHolds = nil
        handoffInMemory = nil
        Keychain.delete(Self.alertHandoffAccount)
        store?.clear()
        tafs = []; held = []; record = []
        scriptPosition = [:]; templateOf = [:]
        mode = .signedOut
        notifyHeld()
    }

    // MARK: Alerts while tafs is closed

    /// A tab to show, asked for by a tapped alert; the root view shows it and clears this.
    @Published var requestedTab: Tab?

    /// Opens where a tapped alert points.
    func open(_ destination: String) {
        if destination == "needs-you" { requestedTab = .needsYou }
    }

    static let alertHandoffAccount = "kiff.alert-handoff"

    /// A KIFF alert target whose secret tafs cloud hasn't confirmed yet. Registering a target
    /// replaces the account's one before, and KIFF shows the new secret only once: until tafs
    /// cloud has it, KIFF's alerts fail its check. So the secret is kept here, in the
    /// Keychain, and handed over again until tafs cloud takes it.
    private struct AlertHandoff: Codable {
        let tenant: String
        let target: KIFFClient.AlertTarget
    }

    /// Held in memory as well: the Keychain keeps it across a relaunch, but may refuse a
    /// write (an unsigned build), and the hand-over must still be retried while the app runs.
    private var handoffInMemory: AlertHandoff?

    private var pendingHandoff: AlertHandoff? {
        get {
            if let h = handoffInMemory { return h.tenant == kiffTenant ? h : nil }
            guard let json = Keychain.load(Self.alertHandoffAccount),
                  let h = try? JSONDecoder().decode(AlertHandoff.self, from: Data(json.utf8)), h.tenant == kiffTenant else { return nil }
            return h
        }
        set {
            handoffInMemory = newValue
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                Keychain.save(String(decoding: data, as: UTF8.self), for: Self.alertHandoffAccount)
            } else {
                Keychain.delete(Self.alertHandoffAccount)
            }
        }
    }

    /// iOS gave this phone a push token (at launch, and whenever it changes).
    func pushTokenArrived(_ token: Data) async {
        pushToken = token.map { String(format: "%02x", $0) }.joined()
        await syncPush()
    }

    /// Tells tafs cloud this phone's token again (tokens change), with a secret it hasn't
    /// confirmed yet if there is one, and learns whether the account's alerts are set up.
    func syncPush() async {
        guard mode == .kiff, let token = pushToken else { return }
        let gen = accountGeneration
        let handoff = pendingHandoff
        guard let status = try? await cloud.registerPush(deviceToken: token, environment: Self.pushEnvironment, target: handoff?.target),
              gen == accountGeneration else { return }
        if handoff != nil { pendingHandoff = nil }
        applyPush(status)
    }

    private func applyPush(_ status: TafsCloud.PushStatus) {
        canAnswerHolds = status.answers
        alertsReady = status.alerts && pendingHandoff == nil
    }

    /// Whether this person may answer held calls, as tafs cloud last said; only their phones
    /// are alerted. nil until known.
    @Published private(set) var canAnswerHolds: Bool?

    /// Turns on alerts for the account's held calls, so they reach this phone with tafs closed:
    /// the owner allows notifications and confirms with Face ID; the phone points the
    /// account's KIFF alert target at tafs cloud (a signed setup call) and hands tafs cloud
    /// the target's signing secret with its push token. A secret tafs cloud didn't take last
    /// time is handed over again first, without registering a new one. True once done.
    @discardableResult
    func turnOnAlerts() async -> Bool {
        guard mode == .kiff, sessionHasKey, let tenant = kiffTenant else {
            lastError = "Sign in with KIFF again to turn on alerts: this sign-in can't sign for the phone."
            return false
        }
        guard !turningOnAlerts else { return false }
        turningOnAlerts = true
        defer { turningOnAlerts = false }
        let gen = accountGeneration

        guard await askNotificationPermission?() ?? false else {
            lastError = "Allow notifications for tafs in Settings, then try again."
            return false
        }
        if pushToken == nil { requestPushToken?() }
        for _ in 0..<40 where pushToken == nil { try? await Task.sleep(nanoseconds: 250_000_000) }
        guard let token = pushToken else {
            lastError = "The iPhone didn't give tafs a push token. Try again in a moment."
            return false
        }

        var handoff = pendingHandoff
        if handoff == nil {
            do {
                _ = try await signer.sign(Data("tafs.alerts.v1|\(tenant)".utf8), reason: "Get an alert when a taf needs you")
            } catch { return false }
            guard gen == accountGeneration else { return false }
            let signer = self.signer
            kiff.tenant = tenant
            kiff.setupSigner = { try signer.signConfirmed($0) }
            defer { kiff.setupSigner = nil }
            do {
                let target = try await kiff.registerAlertTarget(url: cloud.alertsURL)
                guard gen == accountGeneration else { return false }
                // Kept before anything else can fail: KIFF already signs with this secret.
                handoff = AlertHandoff(tenant: tenant, target: target)
                pendingHandoff = handoff
            } catch KIFFClient.KIFFError.server(403, let message) {
                lastError = message.contains("phone_alert_url_forbidden") || message.contains("app backend")
                    ? "KIFF doesn't send alerts to tafs yet on this account."
                    : "Your role on this KIFF account can't turn on alerts."
                return false
            } catch {
                lastError = "Alerts aren't on: \(error.localizedDescription)"
                return false
            }
        }
        do {
            let status = try await cloud.registerPush(deviceToken: token, environment: Self.pushEnvironment, target: handoff?.target)
            guard gen == accountGeneration else { return false }
            pendingHandoff = nil
            applyPush(status)
            return status.alerts
        } catch {
            lastError = "Alerts are almost on: tafs cloud didn't answer (\(error.localizedDescription)). Tap Turn on alerts again."
            alertsReady = false
            return false
        }
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
            await refreshConnections()
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
        case confirm, cards, run
        static func < (a: StartStep, b: StartStep) -> Bool { a.rawValue < b.rawValue }
        var label: String {
            switch self {
            case .confirm: return "Confirm with Face ID"
            case .cards: return "Issue its Cards in KIFF"
            case .run: return "Hand it to tafs cloud and start its first run"
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

    /// A start in progress. Kept when a step fails, so trying again resumes it: the same
    /// draft, without submitting it twice. Its claim secret lives here only in memory, and
    /// only until tafs cloud has claimed the key with it.
    struct StartAttempt: Equatable {
        let name: String
        let job: String
        let agent: String
        var cards: [KIFFClient.CardRequest] = []
        /// The KIFF draft: the agent and its Cards, confirmed by submitting it.
        var draft: KIFFClient.AgentDraft?
        /// tafs cloud may have the taf: its answer was lost, or it holds the name.
        var cloudUnknown = false
        /// The signed-in session this attempt belongs to (`accountGeneration`).
        var account = 0
    }

    /// Moves on with every sign-in and sign-out, so an attempt (and its key) never
    /// outlives the session that made it, even one still running when it changes.
    private(set) var accountGeneration = 0

    struct StartResult: Equatable {
        let taf: TafsCloud.CloudTaf
        /// The first run's session; nil when the taf exists but its first run didn't start.
        let session: String?
    }

    @Published private(set) var startAttempt: StartAttempt?

    /// Starts a taf on KIFF: the owner confirms with Face ID, then the app submits one KIFF
    /// draft, the new agent and one Card per tool, signed by the phone (kiff-cloud #1217).
    /// Submitted by someone who may issue Cards and give keys, KIFF confirms it at once and
    /// issues the Cards. tafs cloud then claims the agent's key with the draft's one-time
    /// secret and runs the taf: the key never reaches the phone. Calling it again after a
    /// failure resumes the same attempt.
    func startOnKIFF(name: String, job: String, limits: [ToolLimit], agentID: String? = nil,
                     progress: (StartStep) -> Void = { _ in }) async throws -> StartResult {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        var a: StartAttempt
        let gen = accountGeneration
        if let previous = startAttempt, previous.name == name, previous.job == job, previous.account == gen {
            a = previous
        } else {
            a = StartAttempt(name: name, job: job, agent: agentID ?? TafPlan.agentID(for: name), account: gen)
        }
        var cards: [KIFFClient.CardRequest]
        do { cards = try TafPlan.cards(agentID: a.agent, limits: limits) } catch {
            throw StartFailure(step: .confirm, message: error.localizedDescription)
        }

        progress(.confirm)
        do {
            _ = try await signer.sign(answer: .approveOnce, heldID: "start-\(a.agent)", reason: "Start \(name) with \(cards.count) Card\(cards.count == 1 ? "" : "s")")
        } catch {
            throw StartFailure(step: .confirm, message: a.draft == nil ? "Nothing was started: Face ID did not confirm." : "Face ID did not confirm; nothing more was done.")
        }
        guard accountGeneration == gen else { throw Self.signedOut(.confirm) }
        // The owner just confirmed with Face ID: a phone session bound to its key signs the
        // draft, as KIFF requires of a phone's setup calls (kiff-cloud #1225).
        if sessionHasKey, let tenant = kiffTenant {
            let signer = self.signer
            kiff.tenant = tenant
            kiff.setupSigner = { try signer.signConfirmed($0) }
        }
        defer { kiff.setupSigner = nil }
        startAttempt = a
        defer { if startAttempt != nil, accountGeneration == gen { startAttempt = a } }

        // The plan changed since the last try: that draft is discarded, which revokes its
        // Cards, and a new agent starts, since a draft's agent must hold no Cards yet.
        if let old = a.draft, cards != a.cards {
            progress(.cards)
            try await attempt(.cards) { try await kiff.discardDraft(id: old.id) }
            guard accountGeneration == gen else { throw Self.signedOut(.cards) }
            a = StartAttempt(name: name, job: job, agent: TafPlan.agentID(for: name), account: gen)
            do { cards = try TafPlan.cards(agentID: a.agent, limits: limits) } catch {
                throw StartFailure(step: .confirm, message: error.localizedDescription)
            }
            startAttempt = a
        }
        a.cards = cards

        if a.draft == nil {
            progress(.cards)
            let draft: KIFFClient.AgentDraft
            do { draft = try await kiff.submitDraft(agentID: a.agent, label: "tafs · \(name)", cards: cards) } catch {
                // The agent's name is taken in KIFF: the next try starts over with a new one.
                if case KIFFClient.KIFFError.server(409, _) = error { startAttempt = nil }
                throw StartFailure(step: .cards, message: Self.startMessage(.cards, error))
            }
            guard accountGeneration == gen else { throw Self.signedOut(.cards) }
            a.draft = draft
        }
        guard let submitted = a.draft, let secret = submitted.claimSecret else {
            startAttempt = nil
            throw StartFailure(step: .cards, message: "KIFF didn't return the draft's claim secret. Nothing is running; start again.")
        }
        if !submitted.confirmed {
            // An editor's draft waits for someone who may issue Cards and give keys.
            let fresh = (try? await kiff.draft(id: submitted.id)) ?? submitted
            guard accountGeneration == gen else { throw Self.signedOut(.cards) }
            if ["failed", "discarded", "discarding", "rolling_back", "claimed"].contains(fresh.status) {
                startAttempt = nil
                throw StartFailure(step: .cards, message: "KIFF couldn't confirm this taf's Cards\(fresh.reason.map { ": \($0)" } ?? ""). Nothing is running; start again.")
            }
            guard fresh.status == "confirmed" else {
                throw StartFailure(step: .cards, message: "Your role can't give an agent its key, so the Cards wait for an admin to confirm them in KIFF. Once they do, tap Start again.")
            }
            a.draft = KIFFClient.AgentDraft(id: fresh.id, agentID: fresh.agentID, status: fresh.status, version: fresh.version,
                                            claimSecret: secret, cardIDs: fresh.cardIDs, reason: fresh.reason)
        }

        // From here on, the draft as KIFF last described it: for an editor's draft only the
        // confirmed one names the Cards that cleanup must revoke.
        let draft = a.draft ?? submitted

        progress(.run)
        guard accountGeneration == gen else { throw Self.signedOut(.run) }
        if a.cloudUnknown, let existing = await cloudTaf(agent: a.agent) {
            return finish(existing, session: nil, &a)
        }
        // The lookup above waited: the secret goes to tafs cloud only under the session that made it.
        guard accountGeneration == gen else { throw Self.signedOut(.run) }
        do {
            let started = try await cloud.start(name: name, job: job, draftID: draft.id, claimSecret: secret)
            return finish(started.taf, session: started.session, &a)
        } catch let e as TafsCloud.CloudError where (400..<500).contains(e.status) && e.status != 408 && e.status != 409 && e.status != 429 {
            // Refused outright: tafs cloud runs nothing. Its Cards go, so no agent is left
            // holding authority that nothing runs.
            let dropped = await dropDraft(draft)
            startAttempt = nil
            throw StartFailure(step: .run, message: dropped
                ? "tafs cloud refused to run it, so its Cards were taken back: \(e.message)"
                : "tafs cloud refused to run it, and its Cards couldn't all be taken back; revoke them in KIFF: \(e.message)")
        } catch {
            // Lost, busy, or the name is held: the taf may exist or still appear.
            if let existing = await cloudTaf(agent: a.agent) { return finish(existing, session: nil, &a) }
            a.cloudUnknown = true
            throw StartFailure(step: .run, message: "Its Cards are in KIFF, and tafs cloud may have the taf already: it didn't confirm (\(error.localizedDescription)). Try again in a minute: the app checks for it before starting another.")
        }
    }

    /// Takes a draft's Cards back: discarding it while nobody claimed it, else revoking them.
    /// True only when KIFF confirmed they are gone.
    private func dropDraft(_ draft: KIFFClient.AgentDraft) async -> Bool {
        do { try await kiff.discardDraft(id: draft.id); return true } catch {
            // Claimed, so it can't be discarded: revoke its Cards one by one. KIFF names them
            // only once the draft is confirmed, so ask when this copy doesn't.
            var ids = draft.cardIDs ?? []
            if ids.isEmpty, let fresh = try? await kiff.draft(id: draft.id) { ids = fresh.cardIDs ?? [] }
            guard !ids.isEmpty else { return false }
            var all = true
            for id in ids {
                do { try await kiff.revoke(cardID: id) } catch { all = false }
            }
            return all
        }
    }

    private func finish(_ taf: TafsCloud.CloudTaf, session: String?, _ a: inout StartAttempt) -> StartResult {
        a.draft = nil
        startAttempt = nil
        if a.account == accountGeneration { cloudTafs[a.agent] = taf }
        Task { await refresh() }
        return StartResult(taf: taf, session: session)
    }

    /// The account's tafs cloud taf acting as `agent`, if tafs cloud lists one.
    private func cloudTaf(agent: String) async -> TafsCloud.CloudTaf? {
        (try? await cloud.tafs())?.first { $0.kiffAgent == agent }
    }

    private static func signedOut(_ step: StartStep) -> StartFailure {
        StartFailure(step: step, message: "You signed out or changed accounts while it was starting; nothing more was done.")
    }

    /// Forgets a failed start, so the next one is a new agent.
    func abandonStart() { startAttempt = nil }

    /// Runs one step of starting a taf; a failure says what is already in KIFF.
    private func attempt<T>(_ step: StartStep, _ work: () async throws -> T) async throws -> T {
        do { return try await work() } catch let failure as StartFailure { throw failure } catch {
            throw StartFailure(step: step, message: Self.startMessage(step, error))
        }
    }

    private static func startMessage(_ step: StartStep, _ error: Error) -> String {
        if case KIFFClient.KIFFError.server(403, _) = error {
            return "Your role on this KIFF account can't start tafs. An admin or an editor can."
        }
        if step == .cards, case KIFFClient.KIFFError.server(409, _) = error {
            return "KIFF already has an agent with that name. Try again: the next one gets a new name."
        }
        let done: String
        switch step {
        case .confirm, .cards: done = "Nothing was started."
        case .run: done = "Its Cards are in KIFF."
        }
        return "\(done) \(error.localizedDescription)"
    }

    // MARK: Runs

    /// The tafs whose Run now request is on its way, so a second tap starts nothing.
    @Published private(set) var runsInFlight: Set<String> = []

    /// Runs a taf now, outside its schedule. Returns the run's session; nil when it didn't
    /// start, or when a run request for it is already on its way.
    func runNow(_ cloudTaf: TafsCloud.CloudTaf) async -> String? {
        guard runsInFlight.insert(cloudTaf.id).inserted else { return nil }
        defer { runsInFlight.remove(cloudTaf.id) }
        do { return try await cloud.runNow(cloudTaf.id) } catch {
            lastError = "The run didn't start: \(error.localizedDescription)"
            return nil
        }
    }

    /// A taf's latest run, as its page shows it.
    struct RunReport: Equatable {
        var session: String?
        var at: Date?
        /// "running" while it works, "idle" once it has stopped.
        var status: String?
        var text: String = ""
        var error: String?
        var finished: Bool { status != nil && status != "running" }
    }

    @Published private(set) var reports: [String: RunReport] = [:]

    /// Loads the taf's latest run and, once it has stopped, its report.
    func loadReport(_ cloudTaf: TafsCloud.CloudTaf) async {
        do {
            let runs = try await cloud.runs(cloudTaf.id)
            guard let latest = runs.first(where: { $0.session != nil }), let session = latest.session else {
                reports[cloudTaf.id] = RunReport(at: runs.first?.at, error: runs.first?.error)
                return
            }
            let r = try await cloud.report(cloudTaf.id, session: session)
            reports[cloudTaf.id] = RunReport(session: session, at: latest.at, status: r.status, text: r.text)
        } catch {
            var r = reports[cloudTaf.id] ?? RunReport()
            r.error = "Its runs couldn't be loaded: \(error.localizedDescription)"
            reports[cloudTaf.id] = r
        }
    }

    /// Removals tafs cloud began and didn't finish (a step failed, or the answer was lost).
    /// Archived tafs aren't listed, so the app finishes these itself on each refresh.
    @Published private(set) var unfinishedRemovals: [TafsCloud.PendingRemoval] = []

    /// Reads which tafs tafs cloud runs; KIFF stays the source of the Cards and held calls.
    func refreshCloud() async {
        let gen = accountGeneration
        guard mode == .kiff, let listing = try? await cloud.listing(), gen == accountGeneration else { return }
        let list = listing.tafs, removing = listing.removing
        unfinishedRemovals = removing
        if !removing.isEmpty {
            await finishRemovals(removing, gen: gen)
            // Finishing waited: another account may be signed in now, with its own tafs.
            guard gen == accountGeneration else { return }
        }
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

    /// Whether the owner can answer `call` from this phone. A held call from KIFF is
    /// answered here only when KIFF checks this phone's signature on it: the session holds
    /// the phone's key, and the hold says which version of it the owner saw.
    func canAnswer(_ call: HeldCall) -> Bool {
        call.kiffID == nil || (kiff.serverVerifiesSignatures && sessionHasKey && kiffTenant != nil && call.accountingVersion != nil)
    }

    /// Where a held call from KIFF is answered while the phone cannot answer it.
    func answerPage(for call: HeldCall) -> URL? {
        call.kiffID.map(kiff.answerPage(for:))
    }

    func answer(_ call: HeldCall, with answer: Answer) async {
        guard held.contains(where: { same($0, call) }) else { return }
        guard canAnswer(call) else {
            lastError = kiff.serverVerifiesSignatures && !sessionHasKey
                ? "Sign out and in again to answer from this phone, so KIFF knows its key. Or answer this one in KIFF."
                : "Answer this one in KIFF. Answering from the phone arrives once KIFF checks the phone's signature."
            return
        }
        if call.kiffID == nil, clock() > call.expiresAt {
            resolve(call)
            log(call.tafID, .refused, "Expired: \(call.proposed.summary)", "Nobody answered in time")
            save()
            return
        }
        let reason = "Answer \(call.proposed.summary)"
        let signature: String
        var signed: KIFFClient.AnswerSignature?
        do {
            if let kiffID = call.kiffID, let tenant = kiffTenant, let version = call.accountingVersion {
                // KIFF's message: this hold, this answer, the hold's version, now.
                let signedAt = ISO8601DateFormatter().string(from: clock())
                var nextStep: String?
                if case .nextStep(let step) = answer { nextStep = step }
                let message = KIFFClient.answerMessage(tenant: tenant, exceptionID: kiffID,
                                                       answer: answer == .approveOnce ? "approve-once" : "reject",
                                                       nextStep: nextStep, accountingVersion: version, signedAt: signedAt)
                signature = try await signer.sign(message, reason: reason)
                signed = KIFFClient.AnswerSignature(signedAt: signedAt, value: signature)
            } else {
                signature = try await signer.sign(answer: answer, heldID: call.id.uuidString, reason: reason)
            }
        } catch {
            lastError = "Not sent: Face ID did not confirm."
            return
        }

        if let kiffID = call.kiffID {
            do {
                switch answer {
                case .approveOnce: try await kiff.approveOnce(kiffID, signature: signed)
                case .refuse: try await kiff.reject(kiffID, signature: signed)
                case .nextStep(let step): try await kiff.reject(kiffID, nextStep: step, signature: signed)
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

    /// Removes a taf for good, after Face ID: KIFF revokes its Cards first, so it can't act
    /// whatever happens next; then tafs cloud stops it, deletes its runs, chats, memory and
    /// key, and frees its name. Returns what to tell the owner, nil when nothing was done.
    func removeTaf(_ tafID: UUID) async -> String? {
        guard mode == .kiff, let taf = taf(tafID), let c = cloudTaf(for: tafID) else { return nil }
        // The session this removal belongs to: checked after every wait, so nothing of it
        // goes out under another account's sign-in.
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.remove.v1|\(c.id)".utf8), reason: "Remove \(taf.name) for good")
        } catch { return nil }
        guard gen == accountGeneration else { return nil }

        var cardsLeft = 0
        if !taf.card.revoked {
            for cardID in taf.kiffCardIDs {
                guard gen == accountGeneration else { return nil }
                do { try await kiff.revoke(cardID: cardID) } catch { cardsLeft += 1 }
            }
        }
        guard gen == accountGeneration else { return nil }
        let cards = cardsLeft == 0
            ? "Its Cards are revoked in KIFF."
            : "KIFF didn't revoke \(cardsLeft) of its Cards: revoke them in KIFF."

        let removal: TafsCloud.Removal
        do { removal = try await cloud.remove(c.id) } catch {
            guard gen == accountGeneration else { return nil }
            return "\(cards) tafs cloud didn't answer (\(error.localizedDescription)). If it began, tafs finishes it the next time it refreshes; if not, tap Remove again."
        }
        guard gen == accountGeneration else { return nil }
        if cardsLeft == 0, let i = tafs.firstIndex(where: { $0.id == tafID }) {
            tafs[i].card.revoked = true
            tafs[i].state = .revoked
            tafs[i].now = "Removed"
        }
        guard removal.done else {
            let failed = removal.failed.map(Self.removalStep).joined(separator: ", ")
            return "\(cards) Not finished: its \(failed) couldn't be removed. tafs tries again each time it refreshes."
        }
        if let agent = holderIDs.first(where: { $0.value == tafID })?.key { cloudTafs[agent] = nil }
        log(tafID, .revoked, "You removed \(taf.name)", "Schedule, memory and key deleted; name free")
        save()
        return "\(cards) Its schedule, chats, run reports, memory and key are deleted, and the name \(taf.name) is free. KIFF's receipts stay."
    }

    /// Finishes removals tafs cloud lists as begun. They were confirmed with Face ID when
    /// they began; finishing only deletes what's left of a taf already stopped.
    private func finishRemovals(_ removing: [TafsCloud.PendingRemoval], gen: Int) async {
        var still = removing
        for r in removing {
            guard gen == accountGeneration else { return }
            if let done = try? await cloud.remove(r.id), done.done { still.removeAll { $0.id == r.id } }
        }
        if gen == accountGeneration { unfinishedRemovals = still }
    }

    private static func removalStep(_ step: String) -> String {
        switch step {
        case "schedule": return "schedule"
        case "history": return "runs and chats"
        case "memory": return "memory"
        case "key": return "key"
        case "name": return "name"
        default: return step
        }
    }

    /// Pauses or resumes a real taf in tafs cloud: no run starts while it is paused.
    /// Pausing doesn't touch its Cards; revoke a Card to stop its authority.
    func setPaused(_ tafID: UUID, _ paused: Bool) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID),
              let agent = holderIDs.first(where: { $0.value == tafID })?.key else { return }
        let gen = accountGeneration
        do {
            let updated = try await cloud.setPaused(c.id, paused)
            guard gen == accountGeneration else { return }
            cloudTafs[agent] = updated
            if let i = tafs.firstIndex(where: { $0.id == tafID }), tafs[i].state != .waiting, tafs[i].state != .revoked {
                tafs[i].state = updated.paused ? .paused : .working
                tafs[i].now = updated.paused ? "Paused by you" : updated.nextRun.map { "Next run \($0.shortTime)" } ?? "Back to work"
            }
        } catch {
            lastError = "tafs cloud didn't \(paused ? "pause" : "resume") it: \(error.localizedDescription)"
        }
    }

    func togglePause(_ tafID: UUID) {
        guard mode == .sample, let i = tafs.firstIndex(where: { $0.id == tafID }), tafs[i].state != .revoked else { return }
        tafs[i].state = tafs[i].state == .paused ? .working : .paused
        tafs[i].now = tafs[i].state == .paused ? "Paused by you" : "Back to work"
        save()
    }

    // MARK: Memory

    /// What each taf remembers between its runs and chats, newest first.
    @Published private(set) var memories: [UUID: [TafsCloud.Note]] = [:]
    @Published var memoryErrors: [UUID: String] = [:]

    /// Reloads what a taf remembers. On KIFF it comes from tafs cloud; in sample mode it
    /// lives here.
    func loadMemory(_ tafID: UUID) async {
        if mode == .sample {
            if memories[tafID] == nil, let taf = taf(tafID) { memories[tafID] = Self.sampleNotes(taf, at: clock()) }
            return
        }
        guard let c = cloudTaf(for: tafID) else { return }
        let gen = accountGeneration
        let result: Result<[TafsCloud.Note], Error>
        do { result = .success(try await cloud.memory(c.id)) } catch { result = .failure(error) }
        guard gen == accountGeneration, mode == .kiff else { return }
        switch result {
        case .success(let notes): memories[tafID] = notes; memoryErrors[tafID] = nil
        case .failure(let error): memoryErrors[tafID] = "Its notes couldn't be loaded: \(error.localizedDescription)"
        }
    }

    /// Forgets one note. It goes from the list at once, and comes back if tafs cloud refuses.
    /// Only that note comes back: another Forget may have finished meanwhile, and its
    /// note must stay gone.
    @discardableResult
    func forget(_ note: TafsCloud.Note, from tafID: UUID) async -> Bool {
        guard let before = memories[tafID], before.contains(note) else { return false }
        memories[tafID] = before.filter { $0.id != note.id }
        memoryErrors[tafID] = nil
        if mode == .sample { return true }
        guard let c = cloudTaf(for: tafID) else { putBack(note, in: tafID); return false }
        let gen = accountGeneration
        do {
            try await cloud.forget(c.id, note: note.id)
            return gen == accountGeneration
        } catch let e as TafsCloud.CloudError where e.status == 404 {
            return gen == accountGeneration // already gone
        } catch {
            guard gen == accountGeneration else { return false }
            putBack(note, in: tafID)
            memoryErrors[tafID] = "It wasn't forgotten: \(error.localizedDescription)"
            return false
        }
    }

    /// Puts one note back in the list as it is now, newest first.
    private func putBack(_ note: TafsCloud.Note, in tafID: UUID) {
        var notes = memories[tafID] ?? []
        guard !notes.contains(where: { $0.id == note.id }) else { return }
        notes.append(note)
        notes.sort { $0.updatedAt > $1.updatedAt }
        memories[tafID] = notes
    }

    /// A sample taf's notes: the kind of thing a real one writes down as it learns.
    private static func sampleNotes(_ taf: Taf, at now: Date) -> [TafsCloud.Note] {
        let notes: [(String, String, TimeInterval)] = [
            ("/how-you-like-it.md", "You'd rather I explain a decision in one line than in a paragraph.", 3_600),
            ("/customers/regulars.md", "Customers who write every week get a reply the same morning.", 86_400),
            ("/from-the-owner.md", "Ask before anything unusual, even when it fits the Card.", 3 * 86_400),
        ]
        return notes.enumerated().map { i, n in
            TafsCloud.Note(id: "sample-\(taf.id.uuidString)-\(i)", path: n.0, content: n.1, updatedAt: now.addingTimeInterval(-n.2))
        }
    }

    // MARK: Chat

    /// Each taf's thread, oldest first: the owner's messages and the taf's replies.
    @Published private(set) var chats: [UUID: [TafsCloud.ChatMessage]] = [:]
    /// The tafs a message is on its way to, so one is sent at a time.
    @Published private(set) var chatSending: Set<UUID> = []
    @Published var chatErrors: [UUID: String] = [:]
    /// How long a sample taf takes to answer; tests set it to zero.
    var sampleReplyDelay: UInt64 = 700_000_000

    static let chatLimit = 2000

    /// A taf can chat in sample mode, and on KIFF once tafs cloud runs it.
    func canChat(_ tafID: UUID) -> Bool {
        mode == .sample || cloudTaf(for: tafID) != nil
    }

    /// Reloads a taf's thread. On KIFF it comes from tafs cloud; in sample mode it lives here.
    func loadChat(_ tafID: UUID) async {
        if mode == .sample {
            if chats[tafID] == nil, let taf = taf(tafID) { chats[tafID] = [sampleMessage(.taf, Self.sampleHello(taf))] }
            return
        }
        guard let c = cloudTaf(for: tafID) else { return }
        let gen = accountGeneration
        let result: Result<[TafsCloud.ChatMessage], Error>
        do { result = .success(try await cloud.conversation(c.id)) } catch { result = .failure(error) }
        // An answer for a session that has since ended (signed out, another account) is dropped.
        guard gen == accountGeneration, mode == .kiff else { return }
        switch result {
        case .success(let messages): chats[tafID] = messages; chatErrors[tafID] = nil
        case .failure(let error): chatErrors[tafID] = "The thread couldn't be loaded: \(error.localizedDescription)"
        }
    }

    /// Sends the owner's message to the taf. It shows at once; on KIFF the thread is then
    /// reloaded from tafs cloud, where the taf's answer arrives as it works.
    @discardableResult
    func sendChat(_ text: String, to tafID: UUID) async -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        guard text.count <= Self.chatLimit else {
            chatErrors[tafID] = "That's longer than \(Self.chatLimit) characters. Say it in fewer words."
            return false
        }
        guard chatSending.insert(tafID).inserted else { return false }
        defer { chatSending.remove(tafID) }
        let gen = accountGeneration
        /// After each wait: is this still the session the message was sent in?
        func current() -> Bool { gen == accountGeneration }
        chatErrors[tafID] = nil
        let pending = sampleMessage(.owner, text, session: "pending")
        chats[tafID, default: []].append(pending)

        if mode == .sample {
            try? await Task.sleep(nanoseconds: sampleReplyDelay)
            guard current(), mode == .sample else { return false }
            chats[tafID, default: []].append(sampleMessage(.taf, sampleReply(to: text, tafID: tafID)))
            return true
        }
        guard let c = cloudTaf(for: tafID) else {
            chats[tafID]?.removeAll { $0 == pending }
            chatErrors[tafID] = "This taf isn't running on tafs cloud, so it can't be messaged."
            return false
        }
        do {
            _ = try await cloud.sendMessage(c.id, text: text)
            guard current() else { return false }
            await loadChat(tafID)
            return true
        } catch let e as TafsCloud.CloudError where e.status == 429 {
            guard current() else { return false }
            chats[tafID]?.removeAll { $0 == pending }
            chatErrors[tafID] = "That's a lot of messages in a few minutes. Give \(c.name) a moment, then try again."
            return false
        } catch {
            guard current() else { return false }
            chats[tafID]?.removeAll { $0 == pending }
            chatErrors[tafID] = "It wasn't sent: \(error.localizedDescription)"
            return false
        }
    }

    /// How often a thread is reloaded while the taf owes an answer, and how many times for
    /// one unanswered message (30 × 4 s = two minutes). Tests shorten both.
    var chatPollInterval: UInt64 = 4_000_000_000
    var chatPollBudget = 30

    /// Loads a taf's thread, then reloads it while the taf owes an answer: at most
    /// `chatPollBudget` times for each unanswered message, starting again only when the
    /// owner sends another. Ends when the task is cancelled (the view goes away).
    func followChat(_ tafID: UUID) async {
        await loadChat(tafID)
        var waitingFor: String?
        var polls = 0
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: chatPollInterval)
            if Task.isCancelled { return }
            guard mode == .kiff, awaitingReply(tafID), let last = chats[tafID]?.last(where: { $0.from == .owner })?.id else { continue }
            if last != waitingFor { waitingFor = last; polls = 0 }
            guard polls < chatPollBudget else { continue }
            polls += 1
            await loadChat(tafID)
        }
    }

    /// True while the taf hasn't answered the owner's last message yet.
    func awaitingReply(_ tafID: UUID) -> Bool {
        chats[tafID]?.last?.from == .owner
    }

    private func sampleMessage(_ from: TafsCloud.ChatMessage.From, _ text: String, session: String = "sample") -> TafsCloud.ChatMessage {
        TafsCloud.ChatMessage(at: clock(), from: from, text: text, session: session)
    }

    private static func sampleHello(_ taf: Taf) -> String {
        "Hi, I'm \(taf.name). I'm on it: \(taf.now.lowercased()). Ask me what I did, or tell me what to do differently. I stay inside my Card either way."
    }

    /// A sample taf answers from its own record: what it did, what waits, or a pause.
    private func sampleReply(to text: String, tafID: UUID) -> String {
        let lower = text.lowercased()
        guard let taf = taf(tafID) else { return "I'm not here any more." }
        if ["stop", "pause", "hold off"].contains(where: { lower.contains($0) }) {
            if taf.state != .paused { togglePause(tafID) }
            return "Paused. Nothing more goes out until you tell me to carry on."
        }
        if ["go", "carry on", "resume", "continue"].contains(where: { lower == $0 || lower.hasPrefix($0 + " ") }) {
            if taf.state == .paused { togglePause(tafID) }
            return "Back to work."
        }
        if ["more", "raise", "limit", "bigger", "increase"].contains(where: { lower.contains($0) }) {
            return "I can't give myself more room: only you can, by changing my Card. Anything over it waits for you in Needs you."
        }
        let done = entries(for: tafID).prefix(3).map { "• " + $0.title }
        let waiting = heldCalls(for: tafID).count
        var lines: [String] = []
        lines.append(done.isEmpty ? "Nothing done yet today." : "Lately:\n" + done.joined(separator: "\n"))
        if waiting > 0 { lines.append("\(waiting) thing\(waiting == 1 ? " waits" : "s wait") on you in Needs you.") }
        lines.append("(Sample reply. Signed in with KIFF, your taf answers for real.)")
        return lines.joined(separator: "\n\n")
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
        if let refunds = tafs.first(where: { $0.name == "Refunds" }) {
            chats[refunds.id] = [
                sampleMessage(.taf, Self.sampleHello(refunds)),
                sampleMessage(.owner, "How did this morning go?"),
                sampleMessage(.taf, sampleReply(to: "How did this morning go?", tafID: refunds.id)),
            ]
        }
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

    // MARK: Connections (planque, #51)

    /// The account's planque grants: connections, and connections waiting for the owner.
    @Published private(set) var grants: [PlanqueClient.Grant] = []
    /// The grant being connected now, so a second tap starts nothing.
    @Published private(set) var connecting: String?
    /// The account this phone enrolled with planque, kept in the Keychain across launches.
    static let planqueAccount = "planque.enrolled"
    /// The account this phone enrolled with planque, as last read or saved.
    private var planqueTenant: String?

    /// Whether this phone is set up with planque for the signed-in account.
    var planqueReady: Bool {
        guard mode == .kiff, let tenant = kiffTenant else { return false }
        if planqueTenant == nil { planqueTenant = Keychain.load(Self.planqueAccount) }
        return planqueTenant == tenant
    }

    /// Records that this phone is enrolled with planque for `tenant`.
    func planqueSetUp(for tenant: String) {
        planqueTenant = tenant
        Keychain.save(tenant, for: Self.planqueAccount)
    }

    /// A taf's connections: the grants made for its KIFF agent, connected now.
    func connections(for tafID: UUID) -> [PlanqueClient.Grant] {
        guard let agent = holderIDs.first(where: { $0.value == tafID })?.key else { return [] }
        return grants.filter { $0.request.agent == agent && $0.connected }
    }

    /// Reads the grants. A read asks for no Face ID; it is signed so planque shows them only
    /// to the enrolled phone.
    func refreshConnections() async {
        let gen = accountGeneration
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else { return }
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { planque.signer = nil }
        guard let list = try? await planque.grants(), gen == accountGeneration else { return }
        grants = list
    }

    /// Sets this phone up with planque, once per account, behind one Face ID: planque gets
    /// the phone's key, and a KIFF key that connects tools and does nothing else (kiff-cloud
    /// #1251), minted with a signed setup call and never kept on the phone. Done before a
    /// connection is asked for, so every grant is read and shown before it is approved.
    func setUpPlanque() async -> Bool {
        guard mode == .kiff, sessionHasKey, let tenant = kiffTenant else {
            lastError = "Sign in with KIFF again: this sign-in can't sign for the phone."
            return false
        }
        if planqueReady { return true }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.planque.v1|\(tenant)".utf8), reason: "Let planque keep your tools' sign-ins")
        } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil; planque.signer = nil }
        do {
            let key = try await kiff.mintConnectorKey()
            guard gen == accountGeneration else { return false }
            _ = try await planque.enrol(deviceKey: signer.publicKeyDER, connectorKey: key)
        } catch KIFFClient.KIFFError.server(403, _) {
            lastError = "Only an admin of this KIFF account can set up planque."
            return false
        } catch PlanqueClient.PlanqueError.server(409, _) {
            lastError = "Another iPhone is set up with planque for this account. Connect tools from that one."
            return false
        } catch {
            lastError = "planque isn't set up: \(error.localizedDescription)"
            return false
        }
        planqueSetUp(for: tenant)
        await refreshConnections()
        return true
    }

    /// Connects a vendor to a taf. The owner has read the grant (`grants`); one Face ID
    /// approves it and, when the setup agent submitted the taf's Cards as a draft, that
    /// draft too. Then the owner signs in on the vendor's own page, and the phone confirms
    /// the sign-in with the code it brought back. The draft is confirmed last, so a taf's
    /// Cards go live only with its tools. Returns the connected grant, or nil with
    /// `lastError` set.
    func connect(grantID: String, draftID: String? = nil) async -> PlanqueClient.Grant? {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else {
            lastError = "Set up planque on this iPhone first."
            return nil
        }
        guard connecting == nil else { return nil }
        connecting = grantID
        defer { connecting = nil }
        let gen = accountGeneration
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        kiff.tenant = tenant
        defer { planque.signer = nil; kiff.setupSigner = nil }

        // What the owner approves, read as it is now.
        let grant: PlanqueClient.Grant
        var draft: KIFFClient.AgentDraft?
        do {
            guard let found = try await planque.grants().first(where: { $0.id == grantID }) else {
                lastError = "planque has no connection with that id."
                return nil
            }
            grant = found
            if let draftID { draft = try await kiff.draft(id: draftID) }
        } catch {
            lastError = "Couldn't read what to approve: \(error.localizedDescription)"
            return nil
        }
        if grant.connected { return grant }
        guard ["pending", "approved", "signed_in"].contains(grant.status) else {
            lastError = "This connection is \(grant.status.replacingOccurrences(of: "_", with: " "))\(grant.reason.map { ": \($0)" } ?? ".")"
            return nil
        }
        guard grant.digestMatches else {
            lastError = PlanqueClient.PlanqueError.digestMismatch.errorDescription
            return nil
        }

        do {
            _ = try await signer.sign(Data("tafs.connect.v1|\(tenant)|\(grant.id)|\(grant.digest)|\(draft?.version ?? "")".utf8),
                                      reason: "Connect \(grant.vendor) to \(grant.request.agent)")
        } catch { return nil }
        guard gen == accountGeneration else { return nil }

        let connected: PlanqueClient.Grant
        do {
            let approved = try await planque.approve(grant)
            let back = try await vendorSignIn.signIn(at: approved.signInURL)
            guard let signedIn = PlanqueClient.signedIn(from: back), signedIn.grant == grant.id, signedIn.tenant == tenant else {
                lastError = "The sign-in at \(grant.vendor) came back without planque's code. Nothing was connected."
                return nil
            }
            guard gen == accountGeneration else { return nil }
            connected = try await planque.confirm(grantID: grant.id, code: signedIn.code)
        } catch VendorSignIn.SignInError.cancelled {
            lastError = "The sign-in was closed. Nothing was connected."
            await refreshConnections()
            return nil
        } catch {
            lastError = "Not connected: \(error.localizedDescription)"
            await refreshConnections()
            return nil
        }
        if let draft, !draft.confirmed {
            kiff.setupSigner = { try signer.signConfirmed($0) }
            do {
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version)
            } catch {
                lastError = "\(grant.vendor) is connected, but KIFF didn't issue the taf's Cards: \(error.localizedDescription)"
            }
        }
        await refreshConnections()
        return connected
    }

    /// Disconnects a vendor: planque removes its tools from KIFF and revokes the sign-in at
    /// the vendor. Asks for Face ID.
    func disconnect(grantID: String) async -> Bool {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant, let grant = grants.first(where: { $0.id == grantID }) else { return false }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.disconnect.v1|\(tenant)|\(grantID)".utf8), reason: "Disconnect \(grant.vendor)")
        } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { planque.signer = nil }
        do {
            _ = try await planque.disconnect(grantID: grantID)
        } catch {
            lastError = "Not disconnected: \(error.localizedDescription)"
            return false
        }
        await refreshConnections()
        return true
    }
}
