import CryptoKit
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
    /// The KIFF account signed in to, as KIFF says (kiff/tafs#73): what Settings shows.
    @Published private(set) var kiffAccount: KIFFClient.Account?
    /// Why the account's time zone didn't change, when it didn't.
    @Published private(set) var timeZoneError: String?

    /// Sets the account's time zone to this device's, so a Card's day ends at the owner's
    /// midnight (kiff/tafs#96): a signed setup call (kiff-cloud #1278), which KIFF takes from
    /// the account's owner or an admin. The account is read again after.
    func useThisDevicesTimeZone() async {
        guard mode == .kiff, let tenant = kiffTenant else { return }
        let gen = accountGeneration
        timeZoneError = nil
        let zone = TimeZone.current.identifier
        // The owner's act, behind the device's unlock, as KIFF asks of a phone.
        do { _ = try await signer.sign(Data("tafs.time-zone.v1|\(tenant)|\(zone)".utf8), reason: "Set your account's time zone to \(zone)") } catch { return }
        guard gen == accountGeneration else { return }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            _ = try await kiff.setTimeZone(zone)
            guard gen == accountGeneration else { return }
            if let account = try? await kiff.me(), gen == accountGeneration { kiffAccount = account }
        } catch {
            guard gen == accountGeneration else { return }
            timeZoneError = "The time zone didn't change: \(error.localizedDescription)"
        }
    }
    /// Whether this phone's KIFF session holds its signing key. A session from before
    /// the phone sent its key cannot answer: sign in again.
    var sessionHasKey = false
    private let signer: ApprovalSigning
    private let store: SampleStore?
    private let clock: () -> Date
    private var scriptPosition: [UUID: Int] = [:]
    private var templateOf: [UUID: String] = [:]
    private var holderIDs: [String: UUID] = [:]
    /// Each holder's colour, kept while it holds a Card (#91).
    private var holderColors: [String: Int] = [:]
    /// Whether tafs cloud's listing was read in this session: until then a holder it doesn't
    /// list may still be one of its tafs.
    private var cloudListed = false
    /// Whether the tafs shown are this account's: remembered, or read from KIFF (#91).
    @Published private(set) var listLoaded = false
    /// Signed in to KIFF and its tafs not shown yet: the first launch after a sign-in.
    var loadingList: Bool { mode == .kiff && !listLoaded }
    /// KIFF couldn't be read and no list was shown yet: "Couldn't load", never "No tafs yet".
    @Published private(set) var listUnread = false

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
         remembered: UserDefaults? = nil,
         clock: @escaping () -> Date = Date.init) {
        self.ownerName = ownerName
        self.signer = signer
        self.kiff = kiff
        self.cloud = cloud
        self.planque = planque
        self.vendorSignIn = vendorSignIn
        self.store = store
        self.remembered = remembered
        self.clock = clock
    }

    /// Restores where the owner left off: signed in to KIFF, or the saved sample.
    func restore() async {
        if let token = Keychain.load(Self.tokenAccount) {
            await resumeSession(token, tenant: Keychain.load(Self.tenantAccount), keyed: Keychain.load(Self.keyedAccount) == "1")
        } else if let snapshot = store?.load() {
            apply(snapshot)
            mode = .sample
        }
    }

    /// Goes on with a KIFF session kept from an earlier launch: the remembered list in the
    /// first frame (#91), then KIFF's.
    func resumeSession(_ token: String, tenant: String?, keyed: Bool) async {
        kiff.token = token
        cloud.token = token
        kiffTenant = tenant
        sessionHasKey = keyed
        showRememberedList()
        mode = .kiff
        await refresh()
        await syncPush()
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
    /// only with that key's signature. A connection iOS dropped while the app was away
    /// (`networkConnectionLost`) is tried again before it counts as a failure.
    func startSignIn() async throws -> KIFFClient.DeviceStart {
        signInNudged = false
        var tries = 0
        while true {
            do {
                return try await kiff.startDeviceSignIn(publicKey: signer.publicKeyDER, deviceName: "tafs on \(ThisDevice.kind)", platform: ThisDevice.platform)
            } catch where Self.transient(error) && tries < 2 {
                tries += 1
                try await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    /// How long a sign-in poll waits before asking KIFF again, at least; and how often it
    /// looks whether the app came back meanwhile.
    var signInSlice: UInt64 = 500_000_000
    /// The least time between two polls, in seconds, whatever KIFF advertises.
    var signInMinimumInterval = 2
    private var signInNudged = false

    /// The app came back to the foreground: the sign-in asks KIFF again at once, so an
    /// approval made in the browser signs the phone in without a wait.
    func nudgeSignIn() { signInNudged = true }

    /// Polls until the person approves the code in KIFF, then loads their Cards. It keeps
    /// polling through a dropped connection or a KIFF error until the code expires: iOS
    /// closes the app's sockets while the owner approves in the browser.
    func finishSignIn(_ start: KIFFClient.DeviceStart, startedAt: Date? = nil) async throws {
        var interval = UInt64(max(start.interval, signInMinimumInterval)) * 1_000_000_000
        let deadline = (startedAt ?? clock()).addingTimeInterval(TimeInterval(start.expiresIn))
        while clock() < deadline {
            var waited: UInt64 = 0
            while waited < interval && !signInNudged {
                try await Task.sleep(nanoseconds: min(signInSlice, interval - waited))
                waited += signInSlice
            }
            signInNudged = false
            do {
                let token = try await kiff.pollDeviceToken(deviceCode: start.deviceCode)
                await useSession(token.accessToken, tenant: token.tenant)
                return
            } catch KIFFClient.KIFFError.authorizationPending {
                continue
            } catch KIFFClient.KIFFError.slowDown {
                interval += 5_000_000_000
            } catch where Self.transient(error) {
                continue
            }
        }
        throw KIFFClient.KIFFError.expired
    }

    /// A failure worth trying again: the network, or KIFF briefly not answering.
    static func transient(_ error: Error) -> Bool {
        if let e = error as? URLError {
            return [.networkConnectionLost, .notConnectedToInternet, .timedOut, .cannotConnectToHost,
                    .cannotFindHost, .dnsLookupFailed, .internationalRoamingOff, .dataNotAllowed].contains(e.code)
        }
        if case KIFFClient.KIFFError.server(let status, _) = error { return status >= 500 || status == 429 }
        return false
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
        holderColors = [:]; cloudListed = false; listLoaded = false; listUnread = false
        remembered?.removeObject(forKey: Self.listKey) // an earlier session's tafs never show in this one
        unfinishedRemovals = []
        grants = []
        setupStream?.cancel(); setupStream = nil; setupLive = nil
        setupTaf = nil; setupThread = SetupThread(); setupAwaiting = nil; setupDrafts = [:]; answered = []; setupError = nil
        setupUnsent = []; setupQueued = nil; setupEnded = nil; setupChat = nil; setupFresh = false; setupChats = []; archivedSetupChats = []; setupSent = []
        chatStreams.values.forEach { $0.cancel() }; chatStreams = [:]; chatStates = [:]; kiffAccount = nil
        planque.deviceID = nil
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
        holderColors = [:]; cloudListed = false; listLoaded = false; listUnread = false
        remembered?.removeObject(forKey: Self.listKey)
        startAttempt = nil
        unfinishedRemovals = []
        grants = []
        setupStream?.cancel(); setupStream = nil; setupLive = nil
        setupTaf = nil; setupThread = SetupThread(); setupAwaiting = nil; setupDrafts = [:]; answered = []; setupError = nil
        setupUnsent = []; setupQueued = nil; setupEnded = nil; setupChat = nil; setupFresh = false; setupChats = []; archivedSetupChats = []; setupSent = []
        chatStreams.values.forEach { $0.cancel() }; chatStreams = [:]; chatStates = [:]; kiffAccount = nil
        remembered?.removeObject(forKey: Self.settledKey) // nothing of the chats stays on the phone (docs/data.md)
        planque.deviceID = nil
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
    /// A taf to select in the iPad's sidebar, asked for by "See it on…" (kiff/tafs#96); the
    /// split view selects it and clears this.
    @Published var requestedTaf: UUID?

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
        let gen = accountGeneration
        defer {
            refreshing = false
            // Read or not, this account's list is no longer loading (KIFF's error shows).
            if mode == .kiff, gen == accountGeneration { listLoaded = true }
        }
        do {
            // One list per refresh (#91): KIFF's Cards and held calls with tafs cloud's
            // listing, so a taf doesn't show as working first and as paused a moment later.
            async let cards = kiff.cards()
            async let calls = kiff.heldCalls()
            async let listing = cloudListing()
            async let activity = activityCalls()
            let (mandates, exceptions) = try await (cards, calls)
            let list = await listing
            let done = await activity
            guard gen == accountGeneration else { return }
            if let list {
                var byAgent: [String: TafsCloud.CloudTaf] = [:]
                for t in list.tafs { if let a = t.kiffAgent { byAgent[a] = t } }
                cloudTafs = byAgent
                cloudListed = true
                unfinishedRemovals = list.removing
            }
            show(mandates, exceptions)
            // What the tafs did, as KIFF records it (kiff/tafs#102), not only the answers given
            // on this device. A KIFF that doesn't let the phone read it leaves the record as it is.
            if let done { record = KIFFMapping.record(done, ids: holderIDs) }
            if let removing = list?.removing, !removing.isEmpty {
                await finishRemovals(removing, gen: gen)
                // Finishing waited: another account may be signed in now, with its own tafs.
                guard gen == accountGeneration else { return }
            }
            await refreshConnections()
            // Which account this is, once a sign-in: it doesn't change under it.
            if kiffAccount == nil {
                let gen = accountGeneration
                if let account = try? await kiff.me(), gen == accountGeneration { kiffAccount = account }
            }
        } catch KIFFClient.KIFFError.unauthorized {
            await signOut()
            lastError = KIFFClient.KIFFError.unauthorized.localizedDescription
        } catch {
            lastError = "Couldn't reach KIFF: \(error.localizedDescription)"
            if !listLoaded { listUnread = true }
        }
    }

    /// Shows what KIFF returned. A held call keeps its id across refreshes (it is derived
    /// from KIFF's id), so the lock screen notifies once and rows are not recreated.
    func show(_ mandates: [KIFFClient.Mandate], _ exceptions: [KIFFClient.CardException]) {
        let previous = Dictionary(tafs.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var next = KIFFMapping.tafs(from: mandates, ids: &holderIDs, colors: &holderColors, owner: ownerName)
        held = exceptions.map { e in
            let id = holderIDs[e.subject] ?? UUID()
            holderIDs[e.subject] = id
            return KIFFMapping.held(e, tafID: id)
        }
        let agentOf = Dictionary(holderIDs.map { ($0.value, $0.key) }, uniquingKeysWith: { a, _ in a })
        for i in next.indices {
            if let agent = agentOf[next[i].id], let c = cloudTafs[agent] {
                next[i].name = c.name
                next[i].state = c.paused ? .paused : .working
                next[i].now = c.paused ? "Paused" : c.nextRun.map { "Next run \($0.shortTime)" } ?? "Running"
            } else if next[i].state != .working {
                continue // a Card no longer active: stopped, whatever tafs cloud says
            } else if cloudListed {
                // An agent tafs cloud doesn't run (made through KIFF's API): it acts under its
                // Card when its own software calls, so it isn't one of the tafs working.
                next[i].state = .idle
            } else if let p = previous[next[i].id] {
                // tafs cloud not read yet: what was last known, rather than "working".
                next[i].name = p.name
                next[i].state = p.state == .waiting ? .idle : p.state
                next[i].now = p.now
            }
        }
        for i in next.indices where held.contains(where: { $0.tafID == next[i].id }) {
            next[i].state = .waiting
            next[i].now = "Waiting on you"
        }
        tafs = next
        listLoaded = true
        listUnread = false
        rememberList()
        notifyHeld()
    }

    private func cloudListing() async -> (tafs: [TafsCloud.CloudTaf], removing: [TafsCloud.PendingRemoval])? {
        try? await cloud.listing()
    }

    private func activityCalls() async -> [KIFFClient.ActivityCall]? {
        try? await kiff.activity()
    }

    // MARK: The remembered list (#91)

    /// The tafs last shown for this account, so the next launch shows them in its first
    /// frame. Names, states, colours and the Cards' summary: never a chat (docs/data.md).
    private struct RememberedList: Codable {
        var tenant: String?
        var ids: [String: UUID]
        var colors: [String: Int]
        var tafs: [Taf]
    }

    static let listKey = "tafs.list"

    private func rememberList() {
        guard mode == .kiff, let remembered else { return }
        let list = RememberedList(tenant: kiffTenant, ids: holderIDs, colors: holderColors, tafs: tafs)
        if let data = try? JSONEncoder().encode(list) { remembered.set(data, forKey: Self.listKey) }
    }

    /// Shows the remembered list when it is this account's.
    private func showRememberedList() {
        guard let data = remembered?.data(forKey: Self.listKey),
              let list = try? JSONDecoder().decode(RememberedList.self, from: data),
              list.tenant == kiffTenant else { return }
        holderIDs = list.ids
        holderColors = list.colors
        tafs = list.tafs
        listLoaded = true
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
                lastError = "The Card was not issued: it wasn't confirmed."
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
            case .confirm: return "Confirm with \(Biometry.name)"
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
            throw StartFailure(step: .confirm, message: a.draft == nil ? "Nothing was started: it wasn't confirmed." : "It wasn't confirmed; nothing more was done.")
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
            do { draft = try await kiff.submitDraft(agentID: a.agent, label: name, cards: cards) } catch {
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
            lastError = "Not sent: it wasn't confirmed."
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
        if call.kiffID != nil {
            // The paused run goes on with the answer (kiff/tafs#102): the chat shows it working
            // and then its reply, as after a message, and KIFF is read again for the Card's use.
            chatStates[call.tafID, default: ChatState()].awaiting = clock()
            Task { await refresh() }
        }
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
        let result: Result<TafsCloud.Thread, Error>
        do { result = .success(try await cloud.conversation(c.id)) } catch { result = .failure(error) }
        // An answer for a session that has since ended (signed out, another account) is dropped.
        guard gen == accountGeneration, mode == .kiff else { return }
        switch result {
        case .success(let thread):
            chats[tafID] = thread.messages
            chatErrors[tafID] = nil
            // A run seen at work here is over now: what it did reaches the Card and the record.
            if chatStates[tafID]?.working != nil, thread.working == nil { Task { await refresh() } }
            chatStates[tafID, default: ChatState()].working = thread.working
            chatStates[tafID]?.workingRun = thread.workingRun
            chatStates[tafID]?.steering = thread.steering
            settleChatAwaiting(tafID)
            settleSteering(tafID)
        case .failure(let error):
            chatErrors[tafID] = "The thread couldn't be loaded: \(error.localizedDescription)"
            return
        }
        await sendQueuedChatIfIdle(tafID)
    }

    /// Sends the owner's message to the taf. It shows at once; on KIFF the thread is then
    /// reloaded from tafs cloud, where the taf's answer arrives as it works.
    @discardableResult
    func sendChat(_ text: String, to tafID: UUID) async -> Bool { await deliverChat(text, to: tafID) == .sent }

    private func deliverChat(_ text: String, to tafID: UUID) async -> Delivery {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .notTried }
        guard text.count <= Self.chatLimit else {
            chatErrors[tafID] = "That's longer than \(Self.chatLimit) characters. Say it in fewer words."
            return .notTried
        }
        guard chatSending.insert(tafID).inserted else { return .notTried }
        defer { chatSending.remove(tafID) }
        let gen = accountGeneration
        /// After each wait: is this still the session the message was sent in?
        func current() -> Bool { gen == accountGeneration }
        chatErrors[tafID] = nil
        let pending = sampleMessage(.owner, text, session: "pending")
        chats[tafID, default: []].append(pending)

        if mode == .sample {
            try? await Task.sleep(nanoseconds: sampleReplyDelay)
            guard current(), mode == .sample else { return .notTried }
            chats[tafID, default: []].append(sampleMessage(.taf, sampleReply(to: text, tafID: tafID)))
            return .sent
        }
        guard let c = cloudTaf(for: tafID) else {
            chats[tafID]?.removeAll { $0 == pending }
            chatErrors[tafID] = "This taf isn't running on tafs cloud, so it can't be messaged."
            return .notTried
        }
        chatStates[tafID, default: ChatState()].ended = nil
        do {
            // Streamed, so the taf's words show as it writes them (#64); sent plainly
            // when the stream can't open.
            if let events = try await openStream(c.id, text: text) {
                guard current() else { return .notTried }
                chatStates[tafID, default: ChatState()].awaiting = clock()
                chatStates[tafID]?.live = LiveAnswer()
                chatStreamWorks = true
                chatStreams[tafID] = Task { await self.followChatStream(events, tafID: tafID, text: text, gen: gen) }
                return .sent
            }
            _ = try await cloud.sendMessage(c.id, text: text)
            guard current() else { return .notTried }
            chatStates[tafID, default: ChatState()].awaiting = clock()
            await loadChat(tafID)
            return .sent
        } catch let e as TafsCloud.CloudError where e.status == 429 {
            guard current() else { return .notTried }
            chats[tafID]?.removeAll { $0 == pending }
            keepUnsent(text, tafID, why: "That's a lot of messages in a few minutes. Give \(c.name) a moment, then try again.")
            return .kept
        } catch {
            guard current() else { return .notTried }
            chats[tafID]?.removeAll { $0 == pending }
            keepUnsent(text, tafID, why: error.localizedDescription)
            return .kept
        }
    }

    // MARK: A taf's chat, live (kiff/tafs#64)

    /// What a taf's chat holds besides its messages, as Setup's does (#58): the session
    /// at work, the answer as it streams, messages not sent or waiting, and how the
    /// last run ended.
    struct ChatState: Equatable {
        /// The session the taf is at work in, as tafs cloud says; nil when idle.
        var working: String?
        /// When the owner last wrote, until the taf answers: tafs cloud may not see the run yet.
        var awaiting: Date?
        var live: LiveAnswer?
        var unsent: [UnsentMessage] = []
        var queued: String?
        var ended: RunEnd?
        /// The run at work, as tafs cloud says, for writing to it.
        var workingRun: String?
        /// What the owner wrote to the latest run that it hasn't read, as kiff/agents says
        /// (kiff/agents#102): shown until the run reads it, and once it's over, offered
        /// to send again.
        var steering: TafsCloud.Steering?
        /// The unread messages of runs that are over, once dealt with here.
        var settled: Set<String> = []
        /// The run whose stream ended answered or failed: what it didn't read goes next.
        var sendNext: String?
    }
    @Published private(set) var chatStates: [UUID: ChatState] = [:]
    /// Whether a taf's chat has streamed here: its answers then show as written.
    @Published private(set) var chatStreamWorks = false
    private(set) var chatStreams: [UUID: Task<Void, Never>] = [:]

    func chatState(_ tafID: UUID) -> ChatState { chatStates[tafID] ?? ChatState() }

    /// The taf is at work on its chat: show it thinking, and offer Stop. In sample mode,
    /// until the sample answers.
    func chatThinking(_ tafID: UUID) -> Bool {
        guard mode == .kiff else { return awaitingReply(tafID) }
        let s = chatState(tafID)
        return s.working != nil || s.awaiting != nil || s.live != nil
    }

    private func keepUnsent(_ text: String, _ tafID: UUID, why: String) {
        chatStates[tafID, default: ChatState()].unsent.append(UnsentMessage(text: text, why: why, at: clock()))
    }

    /// The wait ends once tafs cloud says the taf is at work, once it answered after the
    /// owner wrote, or after two minutes with no sign.
    private func settleChatAwaiting(_ tafID: UUID) {
        guard let since = chatStates[tafID]?.awaiting else { return }
        let answered = chats[tafID]?.contains { $0.from == .taf && $0.at >= since } ?? false
        if chatStates[tafID]?.working != nil || answered || clock().timeIntervalSince(since) > 120 {
            chatStates[tafID]?.awaiting = nil
        }
    }

    /// Draws a taf's stream, as Setup's (#60): text into the live answer; at each tool
    /// call the turn's words are recorded, so the thread is reloaded and the answer
    /// starts again. A stream that drops leaves the thread to show the rest.
    private func followChatStream(_ events: AsyncThrowingStream<TafsCloud.StreamEvent, Error>, tafID: UUID, text: String, gen: Int) async {
        var ended: String?
        do {
            for try await e in events {
                guard gen == accountGeneration else { return }
                switch e {
                case .session(let session, _, let run):
                    chatStates[tafID]?.live?.session = session
                    chatStates[tafID]?.live?.run = run
                case .taken:
                    // What the owner wrote while it went is read: the thread has it now.
                    await loadChat(tafID)
                case .text(let t):
                    chatStates[tafID]?.live?.text += t
                case .activity(let tool):
                    await loadChat(tafID)
                    chatStates[tafID]?.live?.text = ""
                    chatStates[tafID]?.live?.activity = Self.tafActivityWords(tool)
                case .end(let status, _):
                    ended = status
                case .component, .broken:
                    break
                }
            }
        } catch {}
        guard gen == accountGeneration else { return }
        // The run is over or paused on a held call (kiff/tafs#102): KIFF is read at once, so the
        // held call shows under the answer that names it, not at the next refresh, and the
        // Card's use and the record follow what it did.
        if ended != nil { await refresh() }
        guard gen == accountGeneration else { return }
        // The run is over (or paused on a held call): the taf is no longer at work.
        if ended != nil { chatStates[tafID]?.awaiting = nil }
        if ended == "ERROR" { chatStates[tafID]?.ended = .failed(retry: text) }
        if ended == "CANCELLED" { chatStates[tafID]?.ended = .stopped }
        // A run that answered or failed never reads what it didn't take: it goes next.
        // One the owner stopped keeps it, not sent, for them to decide.
        if let run = chatStates[tafID]?.live?.run, ended == "COMPLETED" || ended == "ERROR" { chatStates[tafID]?.sendNext = run }
        // The recorded answer first, then the live one goes: no gap between them.
        await loadChat(tafID)
        guard gen == accountGeneration else { return }
        chatStates[tafID]?.live = nil
        await sendQueuedChatIfIdle(tafID)
    }

    /// What a taf is doing, in the owner's words, from the tool it calls (#64). Every
    /// tool that reaches the company's systems goes through KIFF.
    static func tafActivityWords(_ tool: String) -> String? {
        switch tool {
        case "memory": return "Reading its notes…"
        case "kiff_pending": return "Checking what waits for you…"
        default:
            // The tool's own name, past any server prefix ("stripe.create_refund",
            // "kiff__refund_order"), in words.
            let name = tool.components(separatedBy: "__").last?.split(separator: ".").last.map(String.init) ?? tool
            let words = name.split(whereSeparator: { $0 == "_" || $0 == "-" }).joined(separator: " ")
            return "Asking KIFF to run \(words.isEmpty ? tool : words)…"
        }
    }

    /// The composer's Send: now, or once the taf is done when it's at work. A second
    /// message while one waits joins it. True once the message is taken: sent, queued,
    /// or kept in the thread as not sent. False leaves it with the composer.
    @discardableResult
    func submitChat(_ text: String, to tafID: UUID) async -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        if mode == .kiff, chatThinking(tafID) || chatSending.contains(tafID) {
            // To the run at work, which reads it at its next step (kiff/agents#102). With
            // something queued already, this waits behind it, in order.
            let s = chatState(tafID)
            if s.queued == nil, let run = s.live?.run ?? s.workingRun, let c = cloudTaf(for: tafID) {
                do {
                    let status = try await cloud.steer(c.id, run: run, text: text)
                    // Shown at once, as the run now holds it; the next reload says the same.
                    let held = chatState(tafID).steering.flatMap { $0.run == run ? $0.untaken : nil } ?? []
                    chatStates[tafID, default: ChatState()].steering = TafsCloud.Steering(
                        run: run, status: status, untaken: held + [TafsCloud.Steering.Message(text: text, at: clock())])
                    return true
                } catch let e as TafsCloud.CloudError where e.status == 429 {
                    keepUnsent(text, tafID, why: "That's a lot of messages in a few minutes. Give \(c.name) a moment, then try again.")
                    return true
                } catch {
                    // Ended meanwhile (409), or not reached: it waits, and goes once the taf is done.
                }
            }
            let joined = chatStates[tafID]?.queued.map { $0 + "\n\n" + text } ?? text
            guard joined.count <= Self.chatLimit else { return false }
            chatStates[tafID, default: ChatState()].queued = joined
            return true
        }
        return await deliverChat(text, to: tafID) != .notTried
    }

    /// Takes back what waits to be sent, for the composer.
    func unqueueChat(_ tafID: UUID) -> String? {
        defer { chatStates[tafID]?.queued = nil }
        return chatStates[tafID]?.queued
    }

    /// Where the unread messages already dealt with are kept across launches, so one the
    /// owner deleted doesn't come back: their hashes, never their text. Nil keeps them in
    /// memory only (tests).
    private let remembered: UserDefaults?
    static let settledKey = "tafs.chat.settled"
    /// The most hashes kept: older runs are no longer the latest, so theirs don't show.
    static let settledKept = 500

    private func wasSettled(_ key: String, _ tafID: UUID) -> Bool {
        chatState(tafID).settled.contains(key) || (remembered?.stringArray(forKey: Self.settledKey) ?? []).contains(key)
    }

    private func rememberSettled(_ keys: [String], _ tafID: UUID) {
        chatStates[tafID, default: ChatState()].settled.formUnion(keys)
        guard let remembered else { return }
        let kept = (remembered.stringArray(forKey: Self.settledKey) ?? []) + keys
        remembered.set(Array(kept.suffix(Self.settledKept)), forKey: Self.settledKey)
    }

    /// What a run that's over never read, as kiff/agents says, dealt with once: after a
    /// streamed answer or failure it goes as the next message; otherwise (the owner
    /// stopped it, or no stream said how it ended) it shows as not sent, with Retry.
    private func settleSteering(_ tafID: UUID) {
        guard let s = chatStates[tafID]?.steering, s.over else { return }
        func key(_ m: TafsCloud.Steering.Message) -> String {
            let digest = SHA256.hash(data: Data("\(s.run)|\(m.at.timeIntervalSince1970)|\(m.text)".utf8))
            return digest.map { String(format: "%02x", $0) }.joined()
        }
        let fresh = s.untaken.filter { !wasSettled(key($0), tafID) }
        guard !fresh.isEmpty else { return }
        rememberSettled(fresh.map(key), tafID)
        if chatState(tafID).sendNext == s.run, s.status != "CANCELLED" {
            let texts = [chatState(tafID).queued].compactMap { $0 } + fresh.map(\.text)
            chatStates[tafID]?.queued = texts.joined(separator: "\n\n")
        } else {
            let why = s.status == "CANCELLED" ? "You stopped it before it read this." : "It wasn't read before the work ended."
            for m in fresh { keepUnsent(m.text, tafID, why: why) }
        }
    }

    private func sendQueuedChatIfIdle(_ tafID: UUID) async {
        guard let queued = chatStates[tafID]?.queued, !chatThinking(tafID), !chatSending.contains(tafID) else { return }
        chatStates[tafID]?.queued = nil
        await sendChat(queued, to: tafID)
    }

    /// Sends a message that didn't go through again. It stays where it was until it's
    /// sent or queued, as Setup's does.
    func retryChat(_ message: UnsentMessage, to tafID: UUID) async {
        guard !chatSending.contains(tafID), let at = chatStates[tafID]?.unsent.firstIndex(where: { $0.id == message.id }) else { return }
        chatStates[tafID]?.unsent.remove(at: at)
        // Not taken at all (too long to join the queue): back in its place. A send that
        // fails keeps it again itself.
        if !(await submitChat(message.text, to: tafID)) {
            chatStates[tafID]?.unsent.insert(message, at: min(at, chatState(tafID).unsent.count))
        }
    }

    func discardUnsentChat(_ message: UnsentMessage, from tafID: UUID) {
        chatStates[tafID]?.unsent.removeAll { $0.id == message.id }
    }

    /// Retry on a run that failed part-way: its message is sent again.
    func retryChatRun(_ tafID: UUID) async {
        guard case .failed(let text) = chatStates[tafID]?.ended else { return }
        chatStates[tafID]?.ended = nil
        await submitChat(text, to: tafID)
    }

    /// Stop, while the taf works on its chat: its current run is cancelled.
    func stopChat(_ tafID: UUID) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID) else { return }
        let s = chatState(tafID)
        guard let session = s.live?.session ?? s.working ?? chats[tafID]?.last(where: { $0.session != "pending" })?.session else { return }
        chatStates[tafID, default: ChatState()].awaiting = nil
        do {
            try await cloud.stop(c.id, session: session)
            chatStates[tafID]?.ended = .stopped
        } catch {
            chatErrors[tafID] = "\(c.name) didn't stop: \(error.localizedDescription)"
        }
        await loadChat(tafID)
    }

    /// One thing in a taf's chat, in the order it happened (#64): a message, a call KIFF
    /// holds for the owner, or a held call's outcome from the record.
    enum ChatItem: Identifiable, Equatable {
        case message(TafsCloud.ChatMessage)
        case held(HeldCall)
        case outcome(RecordEntry)
        var id: String {
            switch self {
            case .message(let m): return "m|" + m.id
            case .held(let h): return "h|" + h.id.uuidString
            case .outcome(let e): return "o|" + e.id.uuidString
            }
        }
        var at: Date {
            switch self {
            case .message(let m): return m.at
            case .held(let h): return h.heldAt
            case .outcome(let e): return e.at
            }
        }
    }

    /// A taf's chat as one timeline: its messages, the calls KIFF holds for the owner
    /// where they were held, and what became of earlier held calls (approved, refused)
    /// since the thread's first message, each as one line.
    func chatItems(_ tafID: UUID) -> [ChatItem] {
        let messages = chats[tafID] ?? []
        var items = messages.map(ChatItem.message) + heldCalls(for: tafID).map(ChatItem.held)
        if let first = messages.first?.at {
            items += entries(for: tafID).filter { ($0.kind == .approved || $0.kind == .refused) && $0.at >= first }.map(ChatItem.outcome)
        }
        return items.enumerated().sorted { a, b in
            a.element.at != b.element.at ? a.element.at < b.element.at : a.offset < b.offset
        }.map(\.element)
    }

    /// The held call waiting for the owner in a taf's chat: the latest.
    func chatWaiting(_ tafID: UUID) -> HeldCall? {
        heldCalls(for: tafID).max { $0.heldAt < $1.heldAt }
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
            // A stream shows what the taf does, and reloads the thread itself.
            if chatState(tafID).live != nil { continue }
            guard mode == .kiff, awaitingReply(tafID) || chatThinking(tafID),
                  let last = chats[tafID]?.last(where: { $0.from == .owner })?.id else { continue }
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

    /// A taf's connections: the grants made for its KIFF agent that are connected, or whose
    /// sign-in planque holds while Setup chooses the tools, so the owner can end it.
    func connections(for tafID: UUID) -> [PlanqueClient.Grant] {
        guard let agent = holderIDs.first(where: { $0.value == tafID })?.key else { return [] }
        return grants.filter { $0.request.agent == agent && ($0.connected || $0.holdsSignIn) }
    }

    /// Reads the grants. A read asks for no Face ID; it is signed so planque shows them only
    /// to the enrolled phone. It signs with its own signer and never touches
    /// `planque.signer`: it runs whenever the app comes back to the front, which is also
    /// when the owner returns from the vendor's sign-in, mid-way through a Face ID step that
    /// still needs its signer to confirm the sign-in.
    func refreshConnections() async {
        let gen = accountGeneration
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else { return }
        await loadDeviceID()
        guard gen == accountGeneration else { return }
        let signer = self.signer
        planque.tenant = tenant
        guard let list = try? await planque.grants(signedBy: { try signer.signConfirmed($0) }), gen == accountGeneration else { return }
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
        await loadDeviceID()
        do {
            _ = try await signer.sign(Data("tafs.planque.v1|\(tenant)".utf8), reason: "Keep your tools' sign-ins sealed")
        } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil; planque.signer = nil }
        guard await enrolPlanque(tenant: tenant, gen: gen) else { return false }
        await refreshConnections()
        return true
    }

    /// Reads this device's id in KIFF once per sign-in, so each signature planque gets
    /// names the device and planque asks KIFF about it (kiff/planque#10): any of the
    /// owner's devices then connects tools, not only the first phone. Unread (an older
    /// KIFF, no network), planque checks the key this phone enrolled with, as before.
    private func loadDeviceID() async {
        guard planque.deviceID == nil, mode == .kiff, sessionHasKey else { return }
        let gen = accountGeneration
        guard let id = try? await kiff.thisDeviceID(), gen == accountGeneration else { return }
        planque.deviceID = id
    }

    /// Enrols this phone with planque and hands it a fresh connector key, as a step the
    /// owner just confirmed with Face ID: the caller has set `kiff.setupSigner` and
    /// `planque.signer`. Sets `lastError` and returns false when it can't.
    private func enrolPlanque(tenant: String, gen: Int) async -> Bool {
        do {
            let key = try await kiff.mintConnectorKey()
            guard gen == accountGeneration else { return false }
            _ = try await planque.enrol(deviceKey: signer.publicKeyDER, connectorKey: key)
        } catch KIFFClient.KIFFError.server(403, _) {
            lastError = "Only an admin of this KIFF account can connect tools."
            return false
        } catch PlanqueClient.PlanqueError.server(409, _) {
            lastError = "Another device connects this account's tools for now. Connect them from that one."
            return false
        } catch {
            lastError = "Connecting tools isn't set up: \(error.localizedDescription)"
            return false
        }
        guard gen == accountGeneration else { return false }
        planqueSetUp(for: tenant)
        return true
    }

    /// An approval in the setup chat (contract 4, as amended on kiff/agents#91): one Face
    /// ID each. For a grant not yet connected, it approves planque's grant, then the owner
    /// signs in on the vendor's own page and the phone confirms the sign-in with the code
    /// it brought back. For a connected grant, it confirms the taf's draft: KIFF takes a
    /// Card only for a tool that is connected, so a draft comes only after its grant.
    /// Returns the grant, or nil with `lastError`.
    func connect(grantID: String, draftID: String? = nil) async -> PlanqueClient.Grant? {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else {
            lastError = "Start from Connected tools on this iPhone first."
            return nil
        }
        guard connecting == nil else { return nil }
        connecting = grantID
        vendorSignedIn = false
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
                lastError = "There's no connection with that id."
                return nil
            }
            grant = found
            if let draftID, found.connected { draft = try await kiff.draft(id: draftID) }
        } catch {
            lastError = "Couldn't read what to approve: \(error.localizedDescription)"
            return nil
        }
        if grant.connected {
            // The second approval (kiff/agents#91): the vendor is connected, and the
            // taf's Cards over its tools wait as a draft. One Face ID confirms them.
            guard let draft, !draft.confirmed else { return grant }
            do {
                _ = try await signer.sign(Data("tafs.cards.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8),
                                          reason: "Give \(draft.agentID) its Card")
            } catch { return nil }
            guard gen == accountGeneration else { return nil }
            kiff.setupSigner = { try signer.signConfirmed($0) }
            do {
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version)
            } catch {
                lastError = "KIFF didn't issue the taf's Cards: \(error.localizedDescription)"
                return nil
            }
            return grant
        }
        guard ["pending", "approved", "signed_in"].contains(grant.status) else {
            lastError = "This connection is \(grant.status.replacingOccurrences(of: "_", with: " "))\(grant.reason.map { ": \($0)" } ?? ".")"
            return nil
        }
        guard grant.digestMatches else {
            lastError = PlanqueClient.PlanqueError.digestMismatch.errorDescription
            return nil
        }

        do {
            _ = try await signer.sign(Data("tafs.connect.v1|\(tenant)|\(grant.id)|\(grant.digest)".utf8),
                                      reason: "Connect \(grant.vendor) to \(grant.request.agent)")
        } catch { return nil }
        guard gen == accountGeneration else { return nil }

        let connected: PlanqueClient.Grant
        do {
            let approved = try await planque.approve(grant)
            guard let signInURL = approved.signInURL else {
                // planque already holds the sign-in (contract 5): approving the tools Setup
                // chose from the vendor's list connected them, with no second sign-in.
                await refreshConnections()
                guard gen == accountGeneration else { return nil }
                if approved.status == "connected", let done = grants.first(where: { $0.id == grant.id && $0.connected }) {
                    return done
                }
                lastError = approved.status == "connected"
                    ? "\(grant.vendor) is connected, but the app couldn't read it back yet."
                    : "The connection came back \(approved.status) with no sign-in link. Nothing was connected."
                return nil
            }
            vendorSignedIn = true
            let back = try await vendorSignIn.signIn(at: signInURL)
            guard let signedIn = PlanqueClient.signedIn(from: back), signedIn.grant == grant.id, signedIn.tenant == tenant else {
                lastError = "The sign-in at \(grant.vendor) came back without its confirmation code. Nothing was connected."
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

    // MARK: Setup (kiff/agents#91)

    /// The account's Setup on tafs cloud, once started.
    @Published private(set) var setupTaf: TafsCloud.CloudTaf?
    /// Setup's thread: messages and components.
    @Published private(set) var setupThread = SetupThread() {
        didSet { startHandedOverTafs() }
    }
    @Published private(set) var setupSending = false
    @Published private(set) var startingSetup = false
    @Published var setupError: String?
    /// The drafts Setup's approvals name, read from KIFF so the owner sees the Cards.
    @Published private(set) var setupDrafts: [String: KIFFClient.AgentDraft] = [:]
    /// Components the owner answered on this phone, so their buttons go quiet.
    @Published private(set) var answered: Set<String> = []

    /// A message that didn't reach Setup: it stays in the thread, with why, to send
    /// again (kiff/tafs#58). Never lost to a failed send.
    struct UnsentMessage: Identifiable, Equatable {
        let id = UUID()
        let text: String
        let why: String
        let at: Date
    }
    @Published private(set) var setupUnsent: [UnsentMessage] = []
    /// What the owner wrote while Setup works: sent once it's done (kiff/tafs#58).
    @Published private(set) var setupQueued: String?
    /// How Setup's last run ended, when the owner should know: they stopped it, or it
    /// failed part-way, with the message that started it to send again.
    enum RunEnd: Equatable { case stopped, failed(retry: String) }
    @Published private(set) var setupEnded: RunEnd?

    /// The Setup chat shown (kiff/tafs#73): one the owner reopened or started here, or
    /// nil for the latest ones together, as before.
    @Published private(set) var setupChat: String?
    /// The owner asked for a new conversation: the next message starts one.
    @Published private(set) var setupFresh = false
    /// Setup's past chats, to reopen, newest first.
    @Published private(set) var setupChats: [TafsCloud.Chat] = []
    /// Setup's archived chats, under Archived (kiff/tafs#97).
    @Published private(set) var archivedSetupChats: [TafsCloud.Chat] = []
    /// What the owner sent that tafs cloud's thread doesn't have yet (kiff/tafs#82): shown
    /// at once, and kept across reloads until tafs cloud's copy arrives, so a tapped choice
    /// shows when it's tapped, and once. A reload while the send is on its way would
    /// otherwise drop it until tafs cloud has it.
    private var setupSent: [TafsCloud.ChatMessage] = []
    /// How long a sent message waits for tafs cloud's copy, and how far apart the phone's
    /// clock and tafs cloud's may be.
    static let sentKept: TimeInterval = 120

    /// Where Setup's next message goes.
    private var setupTarget: TafsCloud.ChatTarget {
        setupFresh ? .new : setupChat.map { TafsCloud.ChatTarget(session: $0) } ?? .live
    }

    /// A new Setup conversation: an empty chat, and the next message starts it.
    func newSetupChat() {
        setupChat = nil
        setupFresh = true
        setupThread = SetupThread()
        setupSent = []
        setupAwaiting = nil
        setupUnsent = []
        setupEnded = nil
        setupError = nil
    }

    /// Reopens one of Setup's chats: it's shown alone, and messages go on in it.
    func openSetupChat(_ session: String) async {
        setupChat = session
        setupFresh = false
        setupUnsent = []
        setupSent = []
        setupEnded = nil
        await loadSetupThread()
    }

    /// Reads Setup's past chats, for the list to reopen.
    func loadSetupChats() async {
        guard mode == .kiff, let s = setupTaf else { return }
        let gen = accountGeneration
        guard let chats = try? await cloud.chats(s.id), gen == accountGeneration else { return }
        setupChats = chats
    }

    /// Archives one of Setup's chats (kiff/tafs#82): tafs cloud keeps it out of the list
    /// and the thread from then on, on every phone. The one shown gives way to a new
    /// conversation. False, with why in `lastError`, when tafs cloud didn't take it.
    @discardableResult
    func archiveSetupChat(_ session: String) async -> Bool {
        guard mode == .kiff, let s = setupTaf else { return false }
        let gen = accountGeneration
        do {
            try await cloud.archiveChat(s.id, session: session)
        } catch {
            guard gen == accountGeneration else { return false }
            lastError = "Not archived: \(error.localizedDescription)"
            return false
        }
        guard gen == accountGeneration else { return false }
        setupChats.removeAll { $0.session == session }
        if setupChat == session || (setupChat == nil && setupThread.session == session) { newSetupChat() }
        return true
    }

    /// Reads Setup's archived chats, for Archived (kiff/tafs#97).
    func loadArchivedSetupChats() async {
        guard mode == .kiff, let s = setupTaf else { return }
        let gen = accountGeneration
        guard let chats = try? await cloud.archivedChats(s.id), gen == accountGeneration else { return }
        archivedSetupChats = chats
    }

    /// Puts an archived chat of Setup's back in the list (kiff/tafs#97). False, with why in
    /// `lastError`, when tafs cloud didn't take it.
    @discardableResult
    func unarchiveSetupChat(_ session: String) async -> Bool {
        guard mode == .kiff, let s = setupTaf else { return false }
        let gen = accountGeneration
        do {
            try await cloud.unarchiveChat(s.id, session: session)
        } catch {
            guard gen == accountGeneration else { return false }
            lastError = "Not put back: \(error.localizedDescription)"
            return false
        }
        guard gen == accountGeneration else { return false }
        archivedSetupChats.removeAll { $0.session == session }
        await loadSetupChats()
        return true
    }

    /// The chat a message went into is the one shown from then on, once a new one is started.
    private func settleSetupChat(_ session: String) {
        guard setupFresh else { return }
        setupChat = session
        setupFresh = false
    }

    /// Setup's KIFF agent: one per account.
    static let setupAgent = "setup"

    /// Reads whether the account has Setup, and its thread.
    func loadSetup() async {
        guard mode == .kiff else { return }
        let gen = accountGeneration
        guard let s = try? await cloud.setup(), gen == accountGeneration else { return }
        setupTaf = s
        if s != nil { await loadSetupThread() }
    }

    func loadSetupThread() async {
        guard mode == .kiff, let s = setupTaf else { return }
        // A new conversation has nothing to read until its first message is sent.
        if setupFresh { return }
        let gen = accountGeneration
        do {
            let thread = try await cloud.setupThread(s.id, session: setupChat)
            guard gen == accountGeneration else { return }
            setupThread = withSent(thread)
            setupError = nil
            settleSetupAwaiting()
        } catch {
            guard gen == accountGeneration else { return }
            setupError = "The conversation couldn't be loaded: \(error.localizedDescription)"
            return
        }
        // What an approval needs to be shown: planque's grant and KIFF's draft.
        let approvals = setupThread.components.map(\.component).filter { $0.type == .approval || $0.type == .signIn }
        if approvals.contains(where: { c in c.grant.map { g in !grants.contains { $0.id == g } } ?? false }) {
            await refreshConnections()
        }
        for c in approvals {
            guard let d = c.draft, setupDrafts[d]?.confirmed != true, let draft = try? await kiff.draft(id: d), gen == accountGeneration else { continue }
            setupDrafts[d] = draft
        }
        await sendQueuedIfIdle()
    }

    /// Starts connecting tools, once per account, behind one Face ID: planque gets this
    /// phone's key and a connector key, and Setup is started from a draft with no tools
    /// (kiff-cloud#1254), whose key tafs cloud claims. Done before the first message, so
    /// every grant Setup asks for can be read and shown.
    func startSetup() async -> Bool {
        guard mode == .kiff, sessionHasKey, let tenant = kiffTenant else {
            setupError = "Sign in with KIFF again: this sign-in can't sign for the phone."
            return false
        }
        if planqueReady, setupTaf != nil { return true }
        guard !startingSetup else { return false }
        startingSetup = true
        defer { startingSetup = false }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.setup.v1|\(tenant)".utf8), reason: "Connect your tools")
        } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil; planque.signer = nil }
        if !planqueReady {
            guard await enrolPlanque(tenant: tenant, gen: gen) else {
                setupError = lastError
                return false
            }
        }
        if setupTaf == nil {
            do {
                if let existing = try await cloud.setup() {
                    setupTaf = existing
                } else {
                    let draft = try await kiff.submitNoToolsDraft(agentID: Self.setupAgent, label: "Setup")
                    guard gen == accountGeneration, let secret = draft.claimSecret else {
                        setupError = "KIFF didn't answer as expected. Try again."
                        return false
                    }
                    setupTaf = try await cloud.startSetup(draftID: draft.id, claimSecret: secret)
                }
            } catch {
                guard gen == accountGeneration else { return false }
                setupError = "Couldn't start: \(error.localizedDescription)"
                return false
            }
        }
        await refreshConnections()
        await loadSetupThread()
        return true
    }

    /// tafs cloud's thread, with what the owner sent that it doesn't have yet (kiff/tafs#82).
    /// A sent message goes once tafs cloud has it (the same words, written about when it
    /// was sent), or after `sentKept`, when it either has it or the send says why not.
    private func withSent(_ thread: SetupThread) -> SetupThread {
        let now = clock()
        // One copy takes the place of one sent message, oldest first: two "Sí" sent close
        // together stay two until tafs cloud has both.
        var taken = Set<Int>()
        setupSent = setupSent.filter { sent in
            if now.timeIntervalSince(sent.at) > Self.sentKept { return false }
            let copy = thread.messages.indices.first { i in
                let m = thread.messages[i]
                return !taken.contains(i) && m.from == .owner && m.text == sent.text && m.at >= sent.at.addingTimeInterval(-Self.sentKept)
            }
            guard let copy else { return true }
            taken.insert(copy)
            return false
        }
        var out = thread
        out.messages += setupSent
        return out
    }

    /// Sends the owner's words to Setup; the thread is then reloaded as it answers.
    @discardableResult
    func sendSetup(_ text: String) async -> Bool { await deliverSetup(text) == .sent }

    /// What a send did with a message: sent it, kept it in the thread as not sent (it
    /// was tried and failed), or didn't try (nothing to send, too long, or busy).
    enum Delivery { case sent, kept, notTried }

    private func deliverSetup(_ text: String) async -> Delivery {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= Self.chatLimit, let s = setupTaf, !setupSending else { return .notTried }
        setupSending = true
        defer { setupSending = false }
        let gen = accountGeneration
        setupEnded = nil
        // Shown at once, the way it was sent (kiff/tafs#82), until tafs cloud's copy takes its place.
        // Stamped no earlier than the thread's latest item: Setup's messages carry tafs
        // cloud's time, so a phone clock a little behind would place it above them.
        let latest = setupThread.items.map(\.at).max().map { $0.addingTimeInterval(0.001) } ?? .distantPast
        let mine = TafsCloud.ChatMessage(at: max(clock(), latest), from: .owner, text: text, session: "pending")
        setupSent.append(mine)
        setupThread.messages.append(mine)
        do {
            // Streamed, so Setup's words show as it writes them (#60); sent plainly
            // when the stream can't open.
            if let events = try await openStream(s.id, text: text, to: setupTarget) {
                guard gen == accountGeneration else { return .notTried }
                setupAwaiting = clock()
                setupStreamWorks = true
                setupLive = LiveAnswer()
                setupStream = Task { await self.followSetupStream(events, text: text, gen: gen) }
                return .sent
            }
            let sent = try await cloud.sendMessage(s.id, text: text, to: setupTarget)
            settleSetupChat(sent.session)
            guard gen == accountGeneration else { return .notTried }
            setupAwaiting = clock()
            await loadSetupThread()
            return .sent
        } catch {
            guard gen == accountGeneration else { return .notTried }
            setupSent.removeAll { $0 == mine }
            setupThread.messages.removeAll { $0 == mine }
            setupUnsent.append(UnsentMessage(text: text, why: error.localizedDescription, at: clock()))
            return .kept
        }
    }

    /// The composer's Send: now, or once Setup is done when it's at work (kiff/tafs#58).
    /// A second message while one waits joins it. True once the message is taken: sent,
    /// queued, or kept in the thread as not sent. False leaves it with the composer
    /// (nothing to send, too long to join the queue, or no Setup).
    @discardableResult
    func submitSetup(_ text: String) async -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        if setupThinking || setupSending {
            let joined = setupQueued.map { $0 + "\n\n" + text } ?? text
            guard joined.count <= Self.chatLimit else { return false }
            setupQueued = joined
            return true
        }
        return await deliverSetup(text) != .notTried
    }

    /// Takes back what waits to be sent, for the composer.
    func unqueueSetup() -> String? {
        defer { setupQueued = nil }
        return setupQueued
    }

    private func sendQueuedIfIdle() async {
        guard let queued = setupQueued, !setupThinking, !setupSending else { return }
        setupQueued = nil
        await sendSetup(queued)
    }

    /// Sends a message that didn't go through again; an answer's card goes quiet again.
    /// It stays where it was until it's sent or queued: a send that fails puts it back
    /// with the new why, and one that can't start (too long to join the queue) leaves it.
    func retrySetup(_ message: UnsentMessage) async {
        guard !setupSending, let at = setupUnsent.firstIndex(where: { $0.id == message.id }) else { return }
        let answer = SetupThread.Answer(message.text)
        // The card was answered since: sending this too would answer it twice.
        if let answer, setupThread.answer(to: answer.id) != nil {
            setupUnsent.remove(at: at)
            return
        }
        setupUnsent.remove(at: at)
        let left = setupUnsent.count
        if let answer { answered.insert(answer.id) }
        if !(await submitSetup(message.text)) {
            // Not taken at all (too long to join the queue, or no Setup): back in its place.
            if let answer { answered.remove(answer.id) }
            setupUnsent.insert(message, at: min(at, setupUnsent.count))
        } else if setupUnsent.count > left, let answer {
            // Tried and kept again as not sent: its card can be answered again.
            answered.remove(answer.id)
        }
    }

    /// Drops a message that didn't go through; an answer's card can be answered again.
    func discardUnsent(_ message: UnsentMessage) {
        setupUnsent.removeAll { $0.id == message.id }
        if let answer = SetupThread.Answer(message.text) { answered.remove(answer.id) }
    }

    /// Retry on a run that failed part-way: its message is sent again.
    func retrySetupRun() async {
        guard case .failed(let text) = setupEnded else { return }
        setupEnded = nil
        await submitSetup(text)
    }

    /// Setup's answer as it is written (#60): its text so far in this turn, and what it is
    /// doing in plain words. Nil when nothing streams.
    struct LiveAnswer: Equatable {
        var text = ""
        var activity: String?
        /// The chat the message went into, as the stream says: what Stop stops.
        var session: String?
        /// The run answering it, as the stream says: what a message written meanwhile goes to.
        var run: String?
    }
    @Published private(set) var setupLive: LiveAnswer?
    /// Whether a stream has opened here: answers then show as written, not revealed.
    @Published private(set) var setupStreamWorks = false
    private(set) var setupStream: Task<Void, Never>?

    /// Opens a chat's stream: tafs cloud's, unless a test gives its own.
    var streamOpener: ((String, String) async throws -> AsyncThrowingStream<TafsCloud.StreamEvent, Error>)?

    /// Opens the streamed send, for Setup's chat and a taf's. Nil when the stream couldn't
    /// open and the plain send should take the message (no network to it, tafs cloud
    /// without the route, or failing); a refusal (the flood guard, the role) throws, as
    /// the plain send's would.
    private func openStream(_ id: String, text: String, to target: TafsCloud.ChatTarget = .live) async throws -> AsyncThrowingStream<TafsCloud.StreamEvent, Error>? {
        do {
            if let streamOpener { return try await streamOpener(id, text) }
            return try await cloud.streamMessage(id, text: text, to: target)
        } catch let e as TafsCloud.CloudError where (400..<500).contains(e.status) && e.status != 404 && e.status != 408 {
            throw e
        } catch {
            return nil
        }
    }

    /// Draws the stream: text into the live bubble; at each tool call or component the
    /// turn's words are recorded, so the thread is reloaded and the bubble starts again.
    /// A stream that drops leaves the conversation to show the rest, as it does without one.
    private func followSetupStream(_ events: AsyncThrowingStream<TafsCloud.StreamEvent, Error>, text: String, gen: Int) async {
        var ended: String?
        do {
            for try await e in events {
                guard gen == accountGeneration else { return }
                switch e {
                case .session(let session, _, _):
                    setupLive?.session = session
                    settleSetupChat(session)
                case .text(let t):
                    setupLive?.text += t
                case .activity(let tool):
                    await loadSetupThread()
                    setupLive?.text = ""
                    setupLive?.activity = Self.activityWords(tool)
                case .component:
                    await loadSetupThread()
                    setupLive?.text = ""
                case .end(let status, _):
                    ended = status
                case .broken, .taken:
                    break
                }
            }
        } catch {}
        guard gen == accountGeneration else { return }
        // The run is over: Setup is no longer at work, whatever it wrote.
        if ended != nil { setupAwaiting = nil }
        if ended == "ERROR" { setupEnded = .failed(retry: text) }
        if ended == "CANCELLED" { setupEnded = .stopped }
        // The recorded answer first, then the live one goes: no gap between them.
        await loadSetupThread()
        guard gen == accountGeneration else { return }
        setupLive = nil
        await sendQueuedIfIdle()
    }

    /// What Setup is doing, in the owner's words, from the tool it calls.
    static func activityWords(_ tool: String) -> String? {
        switch tool {
        case "planque": return "Working on the connection…"
        case "kiff_draft": return "Preparing the Card…"
        case "kiff_card", "kiff_pending": return "Checking KIFF…"
        case "show": return nil
        default: return "Working…"
        }
    }

    /// How often Setup's thread is reloaded while the chat is open, and for how long
    /// after the owner last wrote (100 × 3 s: five minutes, since setup steps take time).
    var setupPollInterval: UInt64 = 3_000_000_000
    var setupWorkingPollInterval: UInt64 = 1_000_000_000
    var setupPollBudget = 100

    /// When the owner last wrote, until Setup answers: tafs cloud may not see the run yet.
    @Published private(set) var setupAwaiting: Date?

    /// Setup is at work: show it thinking, and offer Stop (kiff/tafs#58).
    var setupThinking: Bool { setupThread.working != nil || setupAwaiting != nil || setupLive != nil }

    /// The wait ends once tafs cloud says Setup is at work (it then says when it stops),
    /// once Setup answered after the owner wrote, or after two minutes with no sign.
    private func settleSetupAwaiting() {
        guard let since = setupAwaiting else { return }
        let answered = setupThread.messages.contains { $0.from == .taf && $0.at >= since } ||
            setupThread.components.contains { $0.firstAt >= since }
        if setupThread.working != nil || answered || clock().timeIntervalSince(since) > 120 { setupAwaiting = nil }
    }

    /// Loads Setup's thread, then reloads it while the chat is open and the owner wrote
    /// recently. Ends when the task is cancelled (the view goes away).
    func followSetup() async {
        await loadSetup()
        var lastOwner: String?
        var polls = 0
        while !Task.isCancelled {
            // Every second while Setup works, so its words show as soon as it writes them.
            try? await Task.sleep(nanoseconds: setupThinking ? setupWorkingPollInterval : setupPollInterval)
            if Task.isCancelled { return }
            // A stream shows what Setup does, and reloads the thread itself.
            if setupLive != nil { continue }
            let mine = setupThread.messages.last(where: { $0.from == .owner })?.id
            if mine != lastOwner { lastOwner = mine; polls = 0 }
            guard polls < setupPollBudget else { continue }
            polls += 1
            await loadSetupThread()
        }
    }

    /// The plan's Stop, and the composer's while Setup works: Setup's current run is cancelled.
    func stopSetup() async {
        // A streamed message's own chat first: polling, which says what is working, waits
        // while it streams, and a new chat isn't the thread's latest yet.
        guard let s = setupTaf, let session = setupLive?.session ?? setupThread.working ?? setupThread.session else { return }
        setupAwaiting = nil
        do {
            try await cloud.stop(s.id, session: session)
            setupEnded = .stopped
        } catch {
            setupError = "It didn't stop: \(error.localizedDescription)"
        }
        await loadSetupThread()
    }

    /// Where a card stands (kiff/tafs#58): waiting for the owner, at work, or answered
    /// with its outcome.
    enum CardState: Equatable { case needsYou, working, done, declined, failed(String?) }

    /// An approval's or a sign-in's state, from planque's grant, KIFF's draft and the
    /// owner's answer in the thread.
    func setupCardState(_ c: SetupComponent) -> CardState {
        let grant = grants.first { $0.id == c.grant }
        if let g = c.grant, connecting == g { return .working }
        let answer = setupThread.answer(to: c.id)
        if c.type == .approval, approvalFinished(c) { return .done }
        if answer?.text == "declined" || answer?.text == "not now" { return .declined }
        if let answer, answer.failed { return .failed(answer.why) }
        if c.type == .signIn, answer != nil || grant?.connected == true || grant?.listed == true { return .done }
        if let grant, grant.status == "failed" || grant.status == "disconnected" { return .failed(grant.reason) }
        return .needsYou
    }

    /// Connected with its Cards confirmed, or connected first and listed (contract 5).
    func approvalFinished(_ c: SetupComponent) -> Bool {
        let grant = grants.first { $0.id == c.grant }
        let draft = c.draft.flatMap { setupDrafts[$0] }
        let cardsWaiting = draft.map { !$0.confirmed } ?? (c.draft != nil)
        return (grant?.connected == true && !cardsWaiting) || (grant?.listed == true && c.draft == nil)
    }

    /// The card waiting for the owner, if any (kiff/tafs#58): the latest approval they
    /// haven't answered, or a question or sign-in they haven't answered or written past.
    var setupWaiting: ShownComponent? {
        setupThread.asks.last { shown in
            let c = shown.component
            guard !answered.contains(c.id) else { return false }
            switch c.type {
            case .approval:
                return setupCardState(c) == .needsYou
            case .signIn:
                return setupCardState(c) == .needsYou && !wroteSince(shown.firstAt)
            default:
                return setupThread.answer(to: c.id) == nil && !wroteSince(shown.firstAt)
            }
        }
    }

    private func wroteSince(_ at: Date) -> Bool {
        setupThread.messages.contains { $0.from == .owner && $0.at > at }
    }

    /// The owner's answer to a component, as contract 1 says: "[<type> <id>] <answer>".
    /// An earlier answer to it that wasn't sent goes: one answer per card.
    func answer(_ component: SetupComponent, _ text: String) async {
        setupUnsent.removeAll { SetupThread.Answer($0.text)?.id == component.id }
        answered.insert(component.id)
        if !(await sendSetup("[\(component.type.rawValue) \(component.id)] \(text)")) { answered.remove(component.id) }
    }

    /// Approve on an approval (contract 4, one Face ID): the vendor's connection, then the
    /// taf's Cards when it names a draft. The answer says what happened, never more.
    func approve(_ component: SetupComponent) async {
        guard let grant = component.grant else { return }
        lastError = nil
        // Connected, or connected first and listed (contract 5): Setup reads what's next.
        if let done = await connect(grantID: grant, draftID: component.draft), done.connected || done.listed {
            if let e = lastError {
                await answer(component, "failed: \(e)")
            } else {
                await answer(component, vendorSignedIn ? "approved, signed in" : "approved")
            }
        } else if let e = lastError {
            await answer(component, "failed: \(e)")
        }
        await loadSetupThread()
    }

    /// Sign in again, for a grant whose sign-in was left: a new approval, one Face ID.
    func signInAgain(_ component: SetupComponent) async {
        guard let grant = component.grant else { return }
        lastError = nil
        if let done = await connect(grantID: grant), done.connected || done.listed {
            await answer(component, "signed in")
        } else if let e = lastError {
            await answer(component, "failed: \(e)")
        }
    }

    /// The taf a result hands over to, when this phone shows it.
    func taf(forAgent agent: String) -> UUID? { holderIDs[agent] }

    /// Whether the last connect opened the vendor's sign-in: the approval's answer says so.
    private var vendorSignedIn = false

    /// Results whose taf is being started, or failed to start (with why).
    @Published private(set) var startingTafs: Set<String> = []
    @Published private(set) var tafStartErrors: [String: String] = [:]

    /// Setup's hand-over starts its taf once, wherever the owner is (kiff/tafs#96): not
    /// only while the result card is on screen, so the taf reaches the list either way. One
    /// that didn't start waits for the card's Try again.
    private func startHandedOverTafs() {
        for c in setupThread.components.map(\.component) where c.type == .result {
            guard let taf = c.taf, taf.job != nil, taf.draft != nil else { continue }
            if cloudTafs[taf.agent] != nil || startingTafs.contains(c.id) || tafStartErrors[c.id] != nil { continue }
            Task { await startSetupTaf(c) }
        }
    }

    /// Starts the taf a result hands over to, once: tafs cloud stores it, and kiff/agents
    /// claims its key from Setup's draft. Then it shows with the account's other tafs.
    func startSetupTaf(_ component: SetupComponent) async {
        guard mode == .kiff, let taf = component.taf, let job = taf.job, let draft = taf.draft else { return }
        if cloudTafs[taf.agent] != nil || startingTafs.contains(component.id) { return }
        startingTafs.insert(component.id)
        defer { startingTafs.remove(component.id) }
        tafStartErrors[component.id] = nil
        let gen = accountGeneration
        var nameTaken = false
        do {
            _ = try await cloud.startFromSetup(name: taf.name, job: job, draftID: draft)
        } catch let e as TafsCloud.CloudError where e.status == 409 && e.message.contains("already have a taf named") {
            // Started before, by this phone or another, unless another taf has the name.
            nameTaken = true
        } catch {
            guard gen == accountGeneration else { return }
            tafStartErrors[component.id] = "\(taf.name) isn't started yet: \(error.localizedDescription)"
            return
        }
        guard gen == accountGeneration else { return }
        await refresh()
        // A 409 can also mean another taf has the name: say so rather than wait forever.
        if nameTaken, cloudTafs[taf.agent] == nil {
            tafStartErrors[component.id] = "\(taf.name) isn't started yet: the account already has a taf by that name."
        }
    }
}
