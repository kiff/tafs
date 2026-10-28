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
    /// The account's other agents with Cards, the ones tafs doesn't run (made through KIFF's
    /// API): listed apart, never counted as working.
    @Published private(set) var otherAgents: [Taf] = []
    @Published private(set) var held: [HeldCall] = []
    @Published private(set) var record: [RecordEntry] = []
    @Published private(set) var refreshing = false
    /// The tafs tafs cloud runs for this account, by the KIFF agent they act as.
    @Published private(set) var cloudTafs: [String: TafsCloud.CloudTaf] = [:]
    @Published var lastError: String?

    let ownerName: String
    /// Speaking a message instead of typing it, in any chat (kiff/tafs#208): one at a time.
    let dictation = Dictation()
    let kiff: KIFFClient
    let cloud: TafsCloud
    /// planque, which keeps each taf's vendor sign-ins (kiff/planque, #51).
    let planque: PlanqueClient
    /// The vendor's sign-in page, opened for a connection the owner approved.
    private let vendorSignIn: VendorSigningIn
    /// The KIFF account this phone signed in to; part of every signed answer.
    var kiffTenant: String?
    /// The KIFF account signed in to, as KIFF says (kiff/tafs#73): what Settings shows.
    @Published private(set) var kiffAccount: KIFFClient.Account? {
        // Every time the app shows is in the account's time zone (kiff/tafs#211, 6.11).
        didSet { AccountTime.use(kiffAccount.map { $0.timeZone ?? "UTC" }) }
    }
    /// Why the account's time zone didn't change, when it didn't.
    @Published private(set) var timeZoneError: String?
    /// The account's usage this billing period, once read.
    @Published private(set) var usage: KIFFClient.Usage?
    /// The period's counted tasks, newest first: the first page.
    @Published private(set) var countedTasks: [KIFFClient.CountedTask] = []
    /// Why usage couldn't be read.
    @Published private(set) var usageError: String?
    /// The account's groups, its people and what KIFF says this person may do with each taf,
    /// once read: the app shows what KIFF says and decides nothing itself.
    @Published private(set) var groups: [KIFFClient.Group] = []
    @Published private(set) var people: [KIFFClient.Person] = []
    @Published private(set) var access: [String: KIFFClient.Access] = [:]
    /// Why they couldn't be read, or a change didn't go through.
    @Published private(set) var peopleError: String?
    /// Buying Solo and task packs through the App Store.
    let purchases: Purchases
    /// What happened to the last purchase, request or answer, when there's something to say.
    @Published private(set) var tasksMessage: String?
    /// Each taf's counted tasks this period, newest first: the first page.
    @Published private(set) var tafTasks: [UUID: [KIFFClient.CountedTask]] = [:]
    /// The period's AI use from chats and Setup (kiff/tafs#193), from tafs cloud; nil until read.
    @Published private(set) var aiUse: TafsCloud.AIUse?

    /// Sets the account's time zone to this device's, so a Card's day ends at the owner's
    /// midnight (kiff/tafs#96): a signed setup call, which KIFF takes from
    /// the account's owner or an admin. The account is read again after.
    /// The device's time zone, offered once when the account's differs (kiff/tafs#206 6.11):
    /// "Use Europe/Paris for times in tafs?". Never changed without the owner: "Not now" is
    /// remembered for that zone and account, and a new zone asks again.
    @Published private(set) var timeZoneOffer: String?

    private func offerTimeZone(_ account: KIFFClient.Account) {
        let device = TimeZone.current.identifier
        guard (account.timeZone ?? "UTC") != device else { timeZoneOffer = nil; return }
        guard remembered?.string(forKey: declinedZoneKey) != device else { return }
        timeZoneOffer = device
    }

    /// The owner's answer to the time zone offer: yes sets it (one Face ID), no is remembered.
    func answerTimeZoneOffer(use: Bool) async {
        guard let zone = timeZoneOffer else { return }
        timeZoneOffer = nil
        if use { await useThisDevicesTimeZone() } else { remembered?.set(zone, forKey: declinedZoneKey) }
    }

    private var declinedZoneKey: String { "tafs.timeZone.declined|\(kiffTenant ?? "")" }

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
    /// Whether KIFF holds this phone's approval key for the session (kiff/tafs#233): then
    /// what grants authority is signed by it, under Face ID the key itself checks.
    private(set) var sessionHasApprovalKey = false
    /// KIFF holds an approval key for the session that isn't this phone's (it changed after
    /// registering, or another was registered): approving waits for a new sign-in.
    private(set) var approvalKeyLost = false
    private let signer: ApprovalSigning
    private let store: SampleStore?
    private let clock: () -> Date
    private var scriptPosition: [UUID: Int] = [:]
    private var templateOf: [UUID: String] = [:]
    private var holderIDs: [String: UUID] = [:]
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
    /// The owner answered the offer of alerts made right after signing in, here: it isn't
    /// made again (Needs you still offers them).
    @Published private(set) var alertsOffered = false
    static let alertsOfferedKey = "tafs.alerts.offered"

    /// Alerts are offered up front, once, right after signing in, before the owner gives a taf
    /// work (the 755 retest): on the iPhone the first held call came with no alert, because
    /// alerts were offered only in Needs you, afterwards.
    var offerAlertsUpFront: Bool {
        // Not yet known (nil) counts as off: a phone that never allowed notifications has no
        // push token, so tafs cloud is never asked. Once the tafs are in, not mid-sign-in.
        mode == .kiff && listLoaded && sessionHasKey && canAnswerHolds != false && (alertsReady == false || (alertsReady == nil && pushToken == nil)) && !alertsOffered
            && remembered?.bool(forKey: Self.alertsOfferedKey) != true
    }

    /// The owner turned alerts on from the offer, or said not now.
    func answerAlertsOffer() {
        alertsOffered = true
        remembered?.set(true, forKey: Self.alertsOfferedKey)
    }
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
         purchases: Purchases? = nil,
         clock: @escaping () -> Date = Date.init) {
        self.purchases = purchases ?? Purchases()
        self.ownerName = ownerName
        self.signer = signer
        self.kiff = kiff
        self.cloud = cloud
        self.planque = planque
        self.vendorSignIn = vendorSignIn
        self.store = store
        self.remembered = remembered
        self.clock = clock
        // Each purchase goes to KIFF for this account before the App Store is told it's done.
        self.purchases.record = { [weak self] signed, requestID in
            guard let self, self.mode == .kiff else { throw KIFFClient.KIFFError.unauthorized }
            try await self.kiff.recordPurchase(signedTransaction: signed, requestID: requestID)
        }
        self.purchases.recorded = { [weak self] in await self?.refreshUsage() }
        // KIFF wants the approval key this phone doesn't hold (kiff/tafs#233).
        self.kiff.approvalKeyRequired = { [weak self] in self?.lostApprovalKey() }
        // The device's id, read when a signature needs it and it isn't known (kiff/planque#12).
        self.planque.deviceLoader = { [weak self] in
            guard let self, self.mode == .kiff, self.sessionHasKey else { return nil }
            return try? await self.kiff.thisDeviceID()
        }
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
        // The approval key counts only while it's the one KIFF registered: a key made anew
        // since (the passcode turned off and on, the Keychain item lost) registers again,
        // and KIFF's answer says whether it may.
        forgetApprovalKey()
        let key = signer.approvalPublicKeyDER
        if keyed, !key.isEmpty, KIFFClient.sameKey(Keychain.load(Self.approvalKeyedAccount), key) {
            useApprovalKey()
        }
        showRememberedList()
        mode = .kiff
        await refresh()
        // A session from before the approval key registers it once (kiff/tafs#233).
        await registerApprovalKey()
        await syncPush()
        // A purchase KIFF didn't record before the app closed goes now.
        await purchases.sendUnfinished()
    }

    // MARK: Reading

    var signedIn: Bool { mode != .signedOut }
    func taf(_ id: UUID) -> Taf? {
        if let trying = trialTaf(id) { return trying }
        return tafs.first { $0.id == id } ?? otherAgents.first { $0.id == id }
    }
    func entries(for tafID: UUID) -> [RecordEntry] { record.filter { $0.tafID == tafID } }
    func heldCalls(for tafID: UUID) -> [HeldCall] { held.filter { $0.tafID == tafID } }

    /// The tafs as listed: one waiting on the owner first, then the one used or at work most
    /// recently (its latest call, or the latest message in its chat), so the taf the owner just
    /// worked with is at the top, not somewhere they have to scroll to (the 755 retest).
    /// Tafs with nothing yet keep tafs cloud's order, after the rest.
    /// The order is settled when the list changes or the app comes back, not on every reload:
    /// it moved while the owner looked at it (the 789 retest).
    var listedTafs: [Taf] { Self.inOrder(tafs, listOrder, fallback: Self.byRecent(tafs, record: record, held: held, chats: chats)) }

    /// The list in two (kiff/tafs#306): the tafs this person manages, and those shared with
    /// them to use, each in the list's order. Which is which is KIFF's answer; a taf whose
    /// answer isn't in yet counts as theirs, so nothing moves into "Shared with you" and back.
    var tafSections: (own: [Taf], shared: [Taf]) {
        let listed = listedTafs
        return (listed.filter { !sharedWithMe($0.id) }, listed.filter { sharedWithMe($0.id) })
    }

    /// Whether a taf was shared with this person to use, not theirs to manage.
    func sharedWithMe(_ tafID: UUID) -> Bool {
        mode == .kiff && Self.sharedWithMe(kiffAgent(for: tafID).flatMap { access[$0] })
    }

    /// KIFF's answer says use without manage; no answer yet is not "shared".
    nonisolated static func sharedWithMe(_ answer: KIFFClient.Access?) -> Bool {
        answer.map { !$0.manage } ?? false
    }

    /// The order the list was last settled in, by taf id.
    @Published private(set) var listOrder: [UUID] = []
    /// Whether the order was settled with KIFF's record read this session: the first list
    /// comes before it, so the taf used last wasn't known yet (the 870 test: the iPad opened
    /// Refunds, with Refunds 4, the one used last, at the bottom).
    private var orderHasRecord = false

    /// Settles the list's order now: when its tafs change, and when the app comes back.
    func settleListOrder() {
        listOrder = Self.byRecent(tafs, record: record, held: held, chats: chats).map(\.id)
    }

    /// tafs in a settled order; a taf the order doesn't know yet comes after, as `fallback` has it.
    nonisolated static func inOrder(_ tafs: [Taf], _ order: [UUID], fallback: [Taf]) -> [Taf] {
        guard !order.isEmpty else { return fallback }
        let place = Dictionary(order.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        let known = tafs.filter { place[$0.id] != nil }.sorted { place[$0.id]! < place[$1.id]! }
        return known + fallback.filter { place[$0.id] == nil }
    }

    nonisolated static func byRecent(_ tafs: [Taf], record: [RecordEntry], held: [HeldCall],
                                     chats: [UUID: [TafsCloud.ChatMessage]]) -> [Taf] {
        var latest: [UUID: Date] = [:]
        for e in record { latest[e.tafID] = max(latest[e.tafID] ?? .distantPast, e.at) }
        for (id, messages) in chats { if let at = messages.last?.at { latest[id] = max(latest[id] ?? .distantPast, at) } }
        let waiting = Set(held.map(\.tafID))
        return tafs.enumerated().sorted { a, b in
            let wa = waiting.contains(a.element.id), wb = waiting.contains(b.element.id)
            if wa != wb { return wa }
            let la = latest[a.element.id] ?? .distantPast, lb = latest[b.element.id] ?? .distantPast
            return la != lb ? la > lb : a.offset < b.offset
        }.map(\.element)
    }

    var workingCount: Int { tafs.filter { $0.state == .working }.count }

    /// Whether KIFF still holds this Record entry's call for an answer: a held call with its
    /// proposal is in Needs you. Before KIFF's list is read, a held entry counts as waiting.
    func isWaiting(_ entry: RecordEntry) -> Bool {
        guard mode == .kiff, listLoaded else { return true }
        return held.contains { call in
            (call.proposalID ?? call.kiffID).map(KIFFMapping.recordID) == entry.id
        }
    }

    /// Held calls KIFF said this person may not answer, by KIFF's reason: their card says so
    /// instead of offering buttons that can't work (the 789 retest), and keeps Refuse when
    /// only approving is refused.
    @Published private(set) var unanswerable: [UUID: String] = [:]

    /// Why KIFF didn't take an answer, in words, never its code (the 789 retest: "self_review").
    nonisolated static func answerRefusedWords(_ error: Error) -> String {
        if case KIFFClient.KIFFError.server(_, let message, let reason) = error,
           let words = refusalWords(message) ?? reason.flatMap(refusalWords) {
            return words
        }
        return "KIFF didn't take the answer: \(error.localizedDescription)"
    }

    /// KIFF's reason this person may never answer the call, which stays so: not a refusal
    /// that a moment later could take ("hold_check_unavailable"), nor any other error.
    nonisolated static func lastingRefusal(_ error: Error) -> String? {
        guard case KIFFClient.KIFFError.server(_, let message, let reason) = error else { return nil }
        return [message, reason].compactMap { $0 }.first { $0 != "hold_check_unavailable" && refusalWords($0) != nil }
    }

    /// KIFF refuses only this person's approval: they asked for the call, and a refusal
    /// grants nothing, so they may still refuse it. Every other reason takes all the answers.
    nonisolated static func refusesOnly(_ code: String) -> Bool { code == "self_review" }

    /// KIFF's reasons a person may not answer a held call, in words.
    nonisolated static func refusalWords(_ code: String) -> String? {
        switch code {
        case "self_review": return "You asked for this, so someone else on the account approves it. You can still refuse it."
        case "separation_of_duties_role": return "Only an approver answers held calls on this account."
        case "separation_of_duties_key": return "Held calls are answered by an approver signed in, not by a key."
        case "card_changer": return "You changed the Card this was held against, so another approver answers it."
        case "hold_check_unavailable": return "KIFF couldn't check who may answer just now. Try again."
        case "role_forbidden": return "Your role on this account doesn't answer held calls. Someone who approves on it does."
        default: return nil
        }
    }

    // MARK: Signing in with KIFF

    /// Moves on with every sign-in and sign-out, so work for one session (and its key)
    /// never outlives it, even work still running when it changes.
    private(set) var accountGeneration = 0

    static let tokenAccount = "kiff.session"
    static let tenantAccount = "kiff.tenant"
    static let keyedAccount = "kiff.session-keyed"
    static let approvalKeyedAccount = "kiff.session-approval-keyed"

    /// Starts signing in with this phone's signing key, so KIFF accepts its answers
    /// only with that key's signature. A connection iOS dropped while the app was away
    /// (`networkConnectionLost`) is tried again before it counts as a failure.
    func startSignIn() async throws -> KIFFClient.DeviceStart {
        signInNudged = false
        var tries = 0
        while true {
            do {
                // A Mac without a Secure Enclave holds no key (kiff/tafs#85): it signs in
                // without one, and KIFF takes a platform only with a key.
                let key = signer.publicKeyDER
                let approvalKey = signer.approvalPublicKeyDER
                return try await kiff.startDeviceSignIn(publicKey: key.isEmpty ? nil : key,
                                                        approvalPublicKey: key.isEmpty || approvalKey.isEmpty ? nil : approvalKey,
                                                        deviceName: "tafs on \(ThisDevice.kind)",
                                                        platform: key.isEmpty ? nil : ThisDevice.platform)
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
        if case KIFFClient.KIFFError.server(let status, _, _) = error { return status >= 500 || status == 429 }
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
        Keychain.delete(Self.approvalKeyedAccount)
        forgetApprovalKey()
        kiff.token = token
        cloud.token = token
        holderIDs = [:]; halts = [:]; chats = [:]; chatErrors = [:]; memories = [:]; memoryErrors = [:]; versions = [:]; versionErrors = [:]; openTrials = [:]; trialChats = [:]; trialErrors = [:]; switches = [:]; changedVersionCards = [:]; staleVersionCards = [:]; improvedVersions = [:]; improveErrors = [:]
        listLoaded = false; listUnread = false
        remembered?.removeObject(forKey: Self.listKey) // an earlier session's tafs never show in this one
        unfinishedRemovals = []
        doorsOffered = []
        grants = []
        grantsReadFor = []
        refusedRequests = []
        setupStream?.cancel(); setupStream = nil; setupLive = nil
        setupTaf = nil; setupThread = SetupThread(); setupAwaiting = nil; setupDrafts = [:]; answered = []; setupError = nil
        setupUnsent = []; setupDraft = ""; setupQueued = nil; setupAnswersWaiting = []; cardNotes = [:]; setupEnded = nil; setupChat = nil; setupFresh = false; setupChats = []; archivedSetupChats = []; setupSent = []; chatSent = [:]
        chatStreams.values.forEach { $0.cancel() }; chatStreams = [:]; chatStates = [:]; kiffAccount = nil; chatFiles = [:]; fileNotices = [:]; Self.removeOpenedFiles()
        usage = nil; countedTasks = []; usageError = nil; aiUse = nil; tafTasks = [:]; tasksMessage = nil
        groups = []; people = []; access = [:]; peopleError = nil
        planque.deviceID = nil
        accountGeneration += 1
        tafs = []; listOrder = []; orderHasRecord = false; otherAgents = []; held = []; record = []; decided = [:]; devices = []; unreachable = nil; retries = 0; retryingRefresh = false; retrying?.cancel()
        alertsReady = nil
        canAnswerHolds = nil
        mode = .kiff
        // Its own task: switching to .kiff takes the sign-in screen away, which cancels the
        // task that signed in, and a cancelled refresh would leave the list loaded and empty
        // ("No tafs yet") until the owner pulled to refresh.
        await Task { [self] in
            await refresh()
            await registerApprovalKey()
            await syncPush()
            await purchases.sendUnfinished()
        }.value
    }

    /// Makes sure KIFF holds this phone's approval key for the session (kiff/tafs#233): sent
    /// at sign-in, or, for a session from before, registered once, signed by the current key.
    /// It counts only once KIFF says so: registered now, or the session has one already
    /// (409). A KIFF without the route yet (404), or a failure, leaves the current key
    /// signing everything, as before, and the next launch tries again.
    func registerApprovalKey() async {
        guard mode == .kiff, sessionHasKey, !sessionHasApprovalKey, !approvalKeyLost, let tenant = kiffTenant else { return }
        let key = signer.approvalPublicKeyDER
        guard !key.isEmpty else { return }
        let gen = accountGeneration
        let signer = self.signer
        kiff.tenant = tenant
        let held: String?
        do {
            held = try await kiff.registerApprovalKey(key, signer: { try signer.signConfirmed($0) })
        } catch KIFFClient.KIFFError.approvalKeyExists(let fingerprint) {
            held = fingerprint // the key sent at sign-in, or another
        } catch {
            return
        }
        guard gen == accountGeneration else { return }
        // KIFF's fingerprint says which key it holds: only ours lets this phone approve.
        guard KIFFClient.sameKey(held, key) else {
            Keychain.delete(Self.approvalKeyedAccount)
            approvalKeyLost = true
            return
        }
        Keychain.save(KIFFClient.fingerprint(key), for: Self.approvalKeyedAccount)
        useApprovalKey()
    }

    /// From now on, what grants authority is signed by the approval key.
    private func useApprovalKey() {
        sessionHasApprovalKey = true
        approvalKeyLost = false
        let signer = self.signer
        kiff.approvalSigner = { try signer.signApprovalConfirmed($0) }
    }

    private func forgetApprovalKey() {
        sessionHasApprovalKey = false
        approvalKeyLost = false
        kiff.approvalSigner = nil
    }

    /// KIFF answered that an act needs the approval key this phone doesn't hold: approving
    /// waits for a new sign-in, and says so.
    private func lostApprovalKey() {
        sessionHasApprovalKey = false
        approvalKeyLost = true
        kiff.approvalSigner = nil
        Keychain.delete(Self.approvalKeyedAccount)
    }

    // MARK: People, groups and sharing

    /// Whether this person may make and change groups (KIFF's members.manage).
    var canManagePeople: Bool { kiffAccount?.capabilities?.contains("members.manage") ?? false }

    /// Whether this person may answer held calls (KIFF's holds.answer): a viewer may not, so
    /// their card says who answers instead of offering buttons KIFF refuses (the 870 test:
    /// "role_forbidden"). An account from before roles lists no capabilities, and may.
    var mayAnswerHolds: Bool { Self.allows(kiffAccount?.capabilities, "holds.answer") }

    /// Whether this person may delete the account (KIFF's account.delete): it's offered only then.
    var mayDeleteAccount: Bool { Self.allows(kiffAccount?.capabilities, "account.delete") }

    nonisolated static func allows(_ capabilities: [String]?, _ capability: String) -> Bool {
        capabilities.map { $0.contains(capability) } ?? true
    }

    /// The account this person is in, by its name, where they can always see it (the 870 test:
    /// signed in to their own empty account, the app didn't say so). Nil outside KIFF.
    var accountLabel: String? { mode == .kiff ? kiffAccount?.name : nil }

    /// This person, as KIFF lists the account's people.
    var me: KIFFClient.Person? { people.first { $0.you == true } }

    /// Whether this person may change a group's people: members.manage, or its manager.
    func managesGroup(_ group: KIFFClient.Group) -> Bool {
        canManagePeople || group.members.contains { $0.subject == me?.subject && $0.isManager }
    }

    func person(_ subject: String) -> KIFFClient.Person? { people.first { $0.subject == subject } }

    /// The taf a KIFF agent is, by its name; nil for an agent the app doesn't list.
    func tafName(agent: String) -> String? {
        holderIDs[agent].flatMap { taf($0)?.name }
    }

    /// Reads the account's groups, its people and this person's access to each taf.
    func refreshPeople() async {
        guard mode == .kiff else { groups = []; people = []; access = [:]; return }
        let gen = accountGeneration
        do {
            async let g = kiff.groups()
            async let p = kiff.people()
            async let a = kiff.myAccess()
            let (groups, people, access) = try await (g, p, a)
            guard gen == accountGeneration else { return }
            self.groups = groups.sorted { ($0.everyone == true ? 0 : 1, $0.name.lowercased()) < ($1.everyone == true ? 0 : 1, $1.name.lowercased()) }
            self.people = people.filter(\.active).sorted { $0.label.lowercased() < $1.label.lowercased() }
            self.access = access
            peopleError = nil
        } catch {
            guard gen == accountGeneration else { return }
            peopleError = "People and groups couldn't be read: \(error.localizedDescription)"
        }
    }

    // MARK: Devices

    /// The devices signed in as this person on the account (Settings, Devices).
    @Published private(set) var devices: [KIFFClient.Device] = []
    @Published private(set) var devicesError: String?

    func refreshDevices() async {
        guard mode == .kiff, let tenant = kiffTenant else { devices = []; return }
        let gen = accountGeneration
        kiff.tenant = tenant
        do {
            let list = try await kiff.devices()
            guard gen == accountGeneration else { return }
            devices = list
            devicesError = nil
        } catch {
            guard gen == accountGeneration else { return }
            devicesError = "Couldn't load your devices: \(error.localizedDescription)"
        }
    }

    /// Signs another device out of the account, behind Face ID. This device signs out with
    /// Sign out instead. False when it didn't go through.
    @discardableResult
    func removeDevice(_ device: KIFFClient.Device) async -> Bool {
        guard mode == .kiff, let tenant = kiffTenant, !device.thisDevice else { return false }
        let gen = accountGeneration
        let what = "Sign \(device.name ?? "this device") out of your account"
        do { _ = try await signer.sign(Data("tafs.devices.v1|\(tenant)|\(device.id)".utf8), reason: what) } catch { return false }
        guard gen == accountGeneration else { return false }
        kiff.tenant = tenant
        do {
            try await kiff.removeDevice(device.id)
        } catch {
            guard gen == accountGeneration else { return false }
            devicesError = "\(what) didn't go through: \(error.localizedDescription)"
            return false
        }
        await refreshDevices()
        return true
    }

    /// Makes one change to groups or sharing, behind Face ID as KIFF asks of a phone, then
    /// reads them again. `what` is said at the prompt. False when it didn't go through.
    @discardableResult
    func changePeople(_ what: String, _ change: @MainActor (KIFFClient) async throws -> Void) async -> Bool {
        guard mode == .kiff, let tenant = kiffTenant else { return false }
        let gen = accountGeneration
        peopleError = nil
        do { _ = try await signer.sign(Data("tafs.people.v1|\(tenant)|\(what)".utf8), reason: what) } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            try await change(kiff)
        } catch {
            guard gen == accountGeneration else { return false }
            peopleError = "\(what) didn't go through: \(error.localizedDescription)"
            await refreshPeople()
            return false
        }
        await refreshPeople()
        return true
    }

    func signOut() async {
        if mode == .kiff, let pushToken { try? await cloud.unregisterPush(deviceToken: pushToken) }
        if mode == .kiff { try? await kiff.signOut() }
        Keychain.delete(Self.tokenAccount)
        Keychain.delete(Self.tenantAccount)
        Keychain.delete(Self.keyedAccount)
        Keychain.delete(Self.approvalKeyedAccount)
        forgetApprovalKey()
        kiff.token = nil
        cloud.token = nil
        cloudTafs = [:]; halts = [:]
        chats = [:]; chatErrors = [:]; memories = [:]; memoryErrors = [:]; versions = [:]; versionErrors = [:]; openTrials = [:]; trialChats = [:]; trialErrors = [:]; switches = [:]; changedVersionCards = [:]; staleVersionCards = [:]; improvedVersions = [:]; improveErrors = [:]
        holderIDs = [:]
        listLoaded = false; listUnread = false
        remembered?.removeObject(forKey: Self.listKey)
        unfinishedRemovals = []
        doorsOffered = []
        grants = []
        grantsReadFor = []
        refusedRequests = []
        setupStream?.cancel(); setupStream = nil; setupLive = nil
        setupTaf = nil; setupThread = SetupThread(); setupAwaiting = nil; setupDrafts = [:]; answered = []; setupError = nil
        setupUnsent = []; setupDraft = ""; setupQueued = nil; setupAnswersWaiting = []; cardNotes = [:]; setupEnded = nil; setupChat = nil; setupFresh = false; setupChats = []; archivedSetupChats = []; setupSent = []; chatSent = [:]
        chatStreams.values.forEach { $0.cancel() }; chatStreams = [:]; chatStates = [:]; kiffAccount = nil; chatFiles = [:]; fileNotices = [:]; Self.removeOpenedFiles()
        usage = nil; countedTasks = []; usageError = nil; aiUse = nil; tafTasks = [:]; tasksMessage = nil
        groups = []; people = []; access = [:]; peopleError = nil
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
        tafs = []; listOrder = []; orderHasRecord = false; held = []; record = []; decided = [:]; devices = []; unreachable = nil; retries = 0; retryingRefresh = false; retrying?.cancel()
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
    /// The iPad's sidebar is on screen: opening a trial, or ending one, selects its row there.
    /// Kept here as well as in the environment, which a sheet's or a pushed screen's work
    /// didn't always see: Start trying pushed the trial beside a sidebar still showing the
    /// taf (the 755 retest).
    var sidebarShown = false

    /// Usage, asked for by a tapped alert (a member's request for tasks, kiff/tafs#215): the
    /// root view shows it on its own screen and clears this.
    @Published var usageRequested = false

    /// Halt all, asked for by a tapped halt alert or by "Halt all tafs" when it couldn't
    /// finish without the app (kiff/tafs#210): the root view shows it on its own screen.
    @Published var haltRequested = false
    /// The taf whose halt alert was tapped: its own halt opens, not every taf's (kiff/tafs#236, item 21).
    @Published var haltRequestedTaf: UUID?

    /// A hold opened from its notification, with the answer chosen there (kiff/tafs#210):
    /// Needs you shows it and takes this. Approve is sent only once Face ID confirms it.
    struct HoldRequest: Equatable {
        let kiffID: String
        let answer: String?
    }
    @Published var requestedHold: HoldRequest?

    /// Opens where a tapped alert points.
    func open(_ destination: String) {
        switch destination {
        case "needs-you": requestedTab = .needsYou
        case "usage": usageRequested = true
        case "halt": haltRequestedTaf = nil; haltRequested = true
        case let d where d.hasPrefix("halt:"):
            let cloudID = String(d.dropFirst("halt:".count))
            haltRequestedTaf = cloudTafs.first { $0.value.id == cloudID }.flatMap { holderIDs[$0.key] }
            haltRequested = true
        // A Coach task's run ended (kiff/tafs#210): its line is in Coach's chat.
        case "coach": if mode == .kiff { coachOpen = true }
        default:
            if let hold = AlertAnswers.request(from: destination) {
                requestedTab = .needsYou
                requestedHold = hold
            }
        }
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

    /// KIFF's alert target for the account (kiff/tafs#206), set up on a step the owner has
    /// just confirmed with Face ID, with no prompt of its own: a paused run then hears the
    /// owner's answer whether or not a phone turned alerts on. Called with `kiff.setupSigner`
    /// set. Best effort: what doesn't finish is done at the next signed step, and the app asks
    /// a run to go on after each answer anyway.
    private func keepKIFFAlerts(tenant: String, gen: Int) async {
        guard canAnswerHolds != false else { return }
        if pendingHandoff == nil {
            if (try? await cloud.alertsKept()) != false { return } // kept already, or unknown: not now
            guard gen == accountGeneration,
                  let target = try? await kiff.registerAlertTarget(url: cloud.alertsURL),
                  gen == accountGeneration else { return }
            // Kept before anything else can fail: KIFF already signs with this secret.
            pendingHandoff = AlertHandoff(tenant: tenant, target: target)
        }
        guard let handoff = pendingHandoff, (try? await cloud.keepAlerts(handoff.target)) != nil,
              gen == accountGeneration else { return }
        pendingHandoff = nil
    }

    /// Gives Coach its tools (kiff/tafs#206), inside a step the owner just confirmed with Face
    /// ID, so no new prompt: each of the seven tools on tafs cloud's server connected in KIFF
    /// as the account's control server, one at a time; then Coach's Cards, which name them.
    /// A connection there already counts only when it's this server's control connection: a
    /// name another server holds stops it before any Card, since the Cards grant by name. Each
    /// request is signed just before it's sent. Done once per Coach (its Setup agent) and
    /// server on this phone: a new Coach gets its Cards at the next confirmed step.
    private func keepCoachTools(tenant: String, gen: Int) async {
        guard kiff.setupSigner != nil, let agent = setupTaf?.kiffAgent, !agent.isEmpty else { return }
        let doneKey = coachToolsKey(tenant: tenant, agent: agent)
        guard !UserDefaults.standard.bool(forKey: doneKey) else { return }
        do {
            let plan = KIFFClient.coachToolsPlan(try await kiff.connections(), mcp: cloud.mcpURL)
            guard gen == accountGeneration else { return }
            if let taken = plan.taken {
                lastError = "Coach can't have its tools yet: another tool in KIFF is named \(taken). Rename or remove it in KIFF."
                return
            }
            for tool in plan.missing {
                guard gen == accountGeneration else { return }
                try await kiff.connectCoachTool(tool.name, readOnly: tool.readOnly, mcp: cloud.mcpURL)
            }
            guard gen == accountGeneration else { return }
            try await kiff.submitCoachCards(agentID: agent)
            UserDefaults.standard.set(true, forKey: doneKey)
        } catch {
            // Not now: Coach does without its tools until the next confirmed step.
        }
    }

    /// Where this phone remembers Coach's tools are given: per account, Coach and server.
    private func coachToolsKey(tenant: String, agent: String) -> String {
        Self.coachToolsKey(tenant: tenant, agent: agent, mcp: cloud.mcpURL)
    }

    static func coachToolsKey(tenant: String, agent: String, mcp: URL) -> String {
        "coachTools.\(tenant).\(agent).\(mcp.absoluteString)"
    }

    /// The account's Coach, once this phone gave it its tools; nil before. Only its held
    /// version_install is an install (kiff/tafs#206).
    private var coachWithTools: String? {
        guard let tenant = kiffTenant, let agent = setupTaf?.kiffAgent, !agent.isEmpty,
              UserDefaults.standard.bool(forKey: coachToolsKey(tenant: tenant, agent: agent)) else { return nil }
        return agent
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
        guard let status = try? await cloud.registerPush(deviceToken: token, environment: Self.pushEnvironment, sound: alertSound, target: handoff?.target),
              gen == accountGeneration else { return }
        if handoff != nil { pendingHandoff = nil }
        applyPush(status)
    }

    /// Sound for alerts on this device (Settings, Notifications & haptics; the 901 test). Off,
    /// tafs cloud sends this device's alerts quietly: they still arrive, with no sound or banner.
    var alertSound: Bool {
        get { UserDefaults.standard.object(forKey: Self.alertSoundKey) as? Bool ?? true }
        set {
            objectWillChange.send()
            UserDefaults.standard.set(newValue, forKey: Self.alertSoundKey)
            Task { await syncPush() }
        }
    }
    static let alertSoundKey = "alertSound"

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
            lastError = "This \(ThisDevice.kind) didn't give tafs a push token. Try again in a moment."
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
            } catch KIFFClient.KIFFError.server(403, let message, _) {
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
            let status = try await cloud.registerPush(deviceToken: token, environment: Self.pushEnvironment, sound: alertSound, target: handoff?.target)
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
    /// KIFF's account, read only while it isn't known yet; nil when it can't be read.
    private func accountIfUnknown() async -> KIFFClient.Account? {
        guard kiffAccount == nil else { return nil }
        return try? await kiff.me()
    }

    /// How long to wait between reads for a hold an alert announced. Tests shorten it.
    var holdAlertRetry: [UInt64] = [1_500_000_000, 4_000_000_000]

    /// A hold's alert showed with tafs open: KIFF is read, and read again a little later while
    /// its list of held calls doesn't have the hold yet, so Needs you never says nothing waits
    /// under an alert that says something does (the 755 retest).
    func refreshUntilHeld(_ hold: String) async {
        await refresh()
        for wait in holdAlertRetry {
            guard mode == .kiff, !held.contains(where: { $0.kiffID == hold }) else { return }
            try? await Task.sleep(nanoseconds: wait)
            await refresh()
        }
    }

    /// Reads KIFF again a moment after the next hold expires, so an expired hold leaves Needs
    /// you, the chat and the lock screen then, not at the next refresh (the 789 retest).
    private var expiryRead: Task<Void, Never>?
    private func readAgainAtExpiry() {
        expiryRead?.cancel()
        guard mode == .kiff, let next = held.map(\.expiresAt).filter({ $0 > clock() }).min() else { return }
        let wait = max(1, next.timeIntervalSince(clock()) + 2)
        expiryRead = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.refresh()
        }
    }

    /// Draws the chat again once a held card's wait for the taf's words is over
    /// (`heldWaitsForWords`), so it shows then even if no words came.
    private var heldShowing: Task<Void, Never>?
    private func showHeldAfterWaiting() {
        heldShowing?.cancel()
        let now = clock()
        guard let next = held.map({ $0.heldAt.addingTimeInterval(Self.heldWaitsForWords) }).filter({ $0 > now }).min() else { return }
        let wait = next.timeIntervalSince(now) + 0.5
        heldShowing = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.objectWillChange.send()
        }
    }

    /// What a refresh the app made by itself couldn't do (the 930 test), said quietly where
    /// the account's name is, never as an alert: "Can't reach KIFF". The app tries again by
    /// itself; nil once a refresh goes through.
    @Published private(set) var unreachable: String?
    /// Whether the app is still trying again by itself; after a few tries it waits for the
    /// next return to the app or a pull to refresh.
    @Published private(set) var retryingRefresh = false
    private var retrying: Task<Void, Never>?
    private var retries = 0
    /// The waits between tries, then no more until the person comes back or pulls.
    static let retryWaits: [UInt64] = [2, 10, 30, 60, 120]

    /// Words true to what failed: the network, or KIFF answering with an error.
    nonisolated static func refreshWords(_ error: Error) -> String {
        let network: Set<URLError.Code> = [.networkConnectionLost, .notConnectedToInternet, .timedOut, .cannotConnectToHost,
                                           .cannotFindHost, .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff,
                                           .secureConnectionFailed]
        if let e = error as? URLError, network.contains(e.code) { return "Can't reach KIFF" }
        return "Couldn't load from KIFF"
    }

    /// A refresh failed: say so quietly, and refresh again a moment later, then less often,
    /// and stop after a few tries.
    private func refreshFailed(_ words: String, gen: Int) {
        guard gen == accountGeneration else { return }
        unreachable = words
        retrying?.cancel()
        guard retries < Self.retryWaits.count else { retryingRefresh = false; return }
        let seconds = Self.retryWaits[retries]
        retries += 1
        retryingRefresh = true
        retrying = Task { [weak self] in
            try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            guard let self, !Task.isCancelled, self.accountGeneration == gen, self.unreachable != nil else { return }
            await self.refresh(retry: true)
        }
    }

    /// `retry`: the app's own try again. Any other refresh (back in the app, a pull) starts
    /// the tries over.
    func refresh(retry: Bool = false) async {
        guard mode == .kiff else { playNextDemoActions(); return }
        if !retry { retries = 0 }
        refreshing = true
        let gen = accountGeneration
        // Dropped before its answer: the list wasn't read, so it isn't shown as loaded (and
        // empty). The next refresh reads it.
        var cancelled = false
        defer {
            refreshing = false
            // Read or not, this account's list is no longer loading (KIFF's error shows).
            if mode == .kiff, gen == accountGeneration, !cancelled { listLoaded = true }
        }
        do {
            // One list per refresh (#91), made by tafs cloud from its tafs and KIFF's Cards
            // and held calls. The held calls themselves are read from KIFF here: the owner
            // answers what KIFF holds, as KIFF sent it.
            async let home = cloud.home()
            async let calls = kiff.heldCalls()
            async let activity = activityCalls()
            async let events = try? cloud.events()
            // Which account this is, once a sign-in: it doesn't change under it. Read beside
            // the list and set before it's shown: its time zone is the one the list's times
            // are drawn in (kiff/tafs#211, 6.11).
            async let me = accountIfUnknown()
            let exceptions = try await calls
            let list = try await home
            let done = await activity
            let happened = await events
            let account = await me
            // KIFF and tafs cloud answered: nothing to say, and no retry waiting.
            if gen == accountGeneration {
                unreachable = nil
                retries = 0
                retryingRefresh = false
                retrying?.cancel()
                retrying = nil
            }
            // The account read began under this generation: a sign-out or another sign-in
            // meanwhile moves it on, and the old account (and its zone) is dropped here.
            guard gen == accountGeneration else { return }
            if kiffAccount == nil, let account {
                kiffAccount = account
                offerTimeZone(account)
            }
            show(list, exceptions)
            // What the tafs did, as KIFF records it (kiff/tafs#102), not only the answers given
            // on this device. A KIFF that doesn't let the phone read it leaves the record as it is.
            if let done {
                record = withDecisions(KIFFMapping.record(done, ids: holderIDs, vendors: toolVendors))
                await nameRecordLines(gen: gen)
                // The first record of the session settles the order again: the taf used last
                // comes first from the start, not only after the app comes back.
                if gen == accountGeneration, !orderHasRecord {
                    orderHasRecord = true
                    settleListOrder()
                    rememberList()
                }
            }
            // What happened in tafs itself sits beside KIFF's calls (kiff/tafs#236, items 15 and 19).
            if let happened {
                record = (record.filter { $0.kind != .inTafs } + inTafs(happened)).sorted { $0.at > $1.at }
            }
            if !list.removing.isEmpty {
                await finishRemovals(list.removing, gen: gen)
                // Finishing waited: another account may be signed in now, with its own tafs.
                guard gen == accountGeneration else { return }
            }
            await refreshConnections()
        } catch KIFFClient.KIFFError.unauthorized {
            await signOut()
            lastError = KIFFClient.KIFFError.unauthorized.localizedDescription
        } catch is CancellationError {
            // A refresh the app itself dropped (a newer one, a view that went away): not an
            // error to show (the Mac's "Couldn't reach KIFF: cancelled", kiff/tafs#85).
            cancelled = true
        } catch let error as URLError where error.code == .cancelled {
            cancelled = true
        } catch is TafsCloud.CloudError {
            // A refresh is the app's own doing, so it never shows as "Not done" (the 930 test).
            refreshFailed("Couldn't load your tafs", gen: gen)
            if !listLoaded { listUnread = true }
        } catch {
            refreshFailed(Self.refreshWords(error), gen: gen)
            if !listLoaded { listUnread = true }
        }
    }

    /// Shows tafs cloud's list as it is, and KIFF's held calls. A taf keeps its id for its
    /// agent and a held call its id from KIFF's, so the lock screen notifies once and rows
    /// are not recreated.
    func show(_ home: TafsCloud.Home, _ exceptions: [KIFFClient.CardException]) {
        var running: [String: TafsCloud.CloudTaf] = [:]
        for t in home.tafs { if let c = t.cloudTaf { running[t.kiffAgent] = c } }
        cloudTafs = running
        unfinishedRemovals = home.removing
        doorsOffered = home.doorsOffered ?? []
        let before = Set(tafs.map(\.id))
        tafs = home.tafs.map { KIFFMapping.taf($0, id: holderID($0.kiffAgent), owner: ownerName) }
        if Set(tafs.map(\.id)) != before || listOrder.isEmpty { settleListOrder() }
        otherAgents = home.others.map { KIFFMapping.taf($0, id: holderID($0.kiffAgent), owner: ownerName) }
        // Each open trial is its own entry in the menu, under its taf (kiff/tafs#173).
        for t in home.tafs where t.id != nil { setTrial(t.trial, of: holderID(t.kiffAgent)) }
        let coach = coachWithTools
        held = exceptions.map { KIFFMapping.held($0, tafID: holderID($0.subject), coach: coach) }
        unanswerable = unanswerable.filter { id, _ in held.contains { $0.id == id } }
        readAgainAtExpiry()
        showHeldAfterWaiting()
        listLoaded = true
        listUnread = false
        rememberList()
        notifyHeld()
    }

    /// The app's id for a KIFF agent's taf, the same for the whole session.
    private func holderID(_ agent: String) -> UUID {
        if let id = holderIDs[agent] { return id }
        let id = UUID()
        holderIDs[agent] = id
        return id
    }

    /// tafs cloud's events as Activity entries, in the owner's words: one a taf, and one for
    /// each taf when it was all of them.
    func inTafs(_ events: [TafsCloud.AccountEvent]) -> [RecordEntry] {
        events.flatMap { e -> [RecordEntry] in
            let ids: [UUID] = e.taf.map { id in cloudTafs.first { $0.value.id == id }.flatMap { holderIDs[$0.key] }.map { [$0] } ?? [] }
                ?? tafs.map(\.id)
            guard let (title, detail) = Self.eventWords(e) else { return [] }
            return ids.map { taf in
                RecordEntry(id: KIFFMapping.recordID("event|\(e.kind)|\(e.at.timeIntervalSince1970)|\(taf)"), tafID: taf,
                            kind: .inTafs, title: title, detail: detail, at: e.at)
            }
        }
    }

    static func eventWords(_ e: TafsCloud.AccountEvent) -> (String, String)? {
        let door = e.app.map(DoorNames.label) ?? "a door"
        let who = e.from ?? "Someone"
        switch e.kind {
        case "halted": return (e.taf == nil ? "Halted with all your tafs" : "Halted", "Nothing of it runs until you resume it")
        case "resumed": return (e.taf == nil ? "Resumed with all your tafs" : "Resumed", "It works again as before the halt")
        case "knocked": return ("\(who) wrote by \(door)", "Not paired, so nothing was done")
        case "knock_done": return ("You had it act on \(who)'s message", "By \(door), in your words")
        case "knock_ignored": return ("You ignored \(who)'s message", "By \(door): nothing was done")
        case "paired": return ("You paired \(who)", "Their messages by \(door) give it work from now on")
        case "door_removed": return ("You removed its \(door) door", "Its address no longer reaches it")
        default: return nil
        }
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
        var tafs: [Taf]
        var others: [Taf]?
        /// The list's order as last settled, so the next launch's first frame has the taf
        /// used last first (the 870 test).
        var order: [UUID]?
    }

    static let listKey = "tafs.list"

    private func rememberList() {
        guard mode == .kiff, let remembered else { return }
        let list = RememberedList(tenant: kiffTenant, ids: holderIDs, tafs: tafs, others: otherAgents, order: listOrder)
        if let data = try? JSONEncoder().encode(list) { remembered.set(data, forKey: Self.listKey) }
    }

    /// Shows the remembered list when it is this account's.
    private func showRememberedList() {
        guard let data = remembered?.data(forKey: Self.listKey),
              let list = try? JSONDecoder().decode(RememberedList.self, from: data),
              list.tenant == kiffTenant else { return }
        holderIDs = list.ids
        tafs = list.tafs
        listOrder = list.order ?? []
        otherAgents = list.others ?? []
        listLoaded = true
    }

    // MARK: Sample mode

    /// `-listSettlesOnReturn`: the app came back, and the sample's list settles with its last
    /// taf at the top.
    func settleSampleOnReturn() {
        guard Screenshots.listSettlesOnReturn, let last = listOrder.last else { return }
        listOrder = [last] + listOrder.dropLast()
    }

    func startSample() async {
        mode = .sample
        await loadSample()
        save()
        if Screenshots.listSettlesOnReturn { settleListOrder() }
        if Screenshots.purchases {
            // The purchase screens at the App Store's prices: the sample's usage, which has the
            // store, is otherwise read only when Usage opens.
            purchases.showSample(Screenshots.products)
            await refreshUsage()
        }
    }

    // MARK: Starting a taf

    /// Signed in, a new taf starts in Coach mode (`startNewTaf()`); the sample starts from its form.
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

    /// The doors tafs cloud offers this account (docs/doors.md): none, and no taf shows Doors.
    @Published private(set) var doorsOffered: [String] = []

    /// The tafs cloud taf behind a taf, when there is one.
    func cloudTaf(for tafID: UUID) -> TafsCloud.CloudTaf? {
        // A trial's chat is its taf's, on tafs cloud (kiff/tafs#173).
        let tafID = trialChats[tafID] ?? tafID
        return holderIDs.first(where: { $0.value == tafID }).flatMap { cloudTafs[$0.key] }
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

    /// The answer as KIFF's signed answer message, kiff.answer.v1, names it.
    nonisolated static func kiffAnswer(_ answer: Answer) -> String {
        switch answer {
        case .approveOnce: return "approve-once"
        case .refuse, .nextStep: return "reject"
        case .extraRoom: return "change-card"
        }
    }

    /// Calls KIFF allowed today that the vendor refused, which still count against the
    /// taf's Card totals (kiff/tafs#211, 6.13), newest first.
    func refusedToday(_ tafID: UUID) -> [RecordEntry] {
        entries(for: tafID).filter { $0.refusedByTool == true && AccountTime.calendar.isDateInToday($0.at) }
    }

    /// Whether this person may give a Card extra room (KIFF's cards.manage): Extra room on
    /// a hold is offered only to them. A KIFF that doesn't say leaves it to KIFF to decide.
    var canManageCards: Bool { Self.managesCards(kiffAccount?.capabilities) }

    /// Whether this person manages the taf, as KIFF says for it: its Card, versions, doors,
    /// halting it and removing it. Without KIFF's answer for it (not read yet, or failed),
    /// nothing is shown: an Editor manages only the tafs they made, which the role alone
    /// can't say (review on #275).
    /// The app shows only what they can do (the 805 retest: a Viewer saw Halt, Versions,
    /// Revoke and the doors' controls, each refused only once tapped).
    func manages(_ tafID: UUID) -> Bool {
        Self.manages(kiffAgent(for: tafID).flatMap { access[$0] })
    }

    /// Who issued the taf's Card, as this person may see it: its manager sees who they know
    /// issued it; someone who only uses the taf sees the account, never "You" (the 870 test).
    func issuer(of taf: Taf) -> String {
        mode != .kiff || manages(taf.id) ? taf.card.issuedBy : (accountLabel ?? "The account")
    }

    nonisolated static func manages(_ access: KIFFClient.Access?) -> Bool {
        access?.manage ?? false
    }

    /// Whether this person may use Coach: as KIFF answers for Setup, an account admin by default.
    var mayUseCoach: Bool { Self.mayUseCoach(setup: access[Self.setupAgent], capabilities: kiffAccount?.capabilities) }

    nonisolated static func mayUseCoach(setup: KIFFClient.Access?, capabilities: [String]?) -> Bool {
        setup?.use ?? (capabilities?.contains("members.manage") ?? false)
    }

    nonisolated static func managesCards(_ capabilities: [String]?) -> Bool {
        capabilities.map { $0.contains("cards.manage") } ?? true
    }

    /// Where a held call from KIFF is answered while the phone cannot answer it.
    func answerPage(for call: HeldCall) -> URL? {
        call.kiffID.map(kiff.answerPage(for:))
    }

    /// `confirmed`: the owner just confirmed this step with Face ID (installing the version
    /// Coach asked for), so the answer is signed without asking again. `installed`: the
    /// version Coach's held call names is live already. `fromNotification`: the answer was
    /// chosen on its notification, so the unlock that opened the app for it counts as its
    /// Face ID or Touch ID (kiff/tafs#236, item 25), for this answer only: it ends here,
    /// signed or not, and the next approval anywhere asks again.
    func answer(_ call: HeldCall, with answer: Answer, confirmed: Bool = false, installed: Bool = false,
                fromNotification: Bool = false) async {
        if fromNotification { signer.allowUnlockReuse() }
        defer { if fromNotification { signer.endUnlockReuse() } }
        guard held.contains(where: { same($0, call) }) else { return }
        // Approving Coach's install is installing it (kiff/tafs#206): the switch and its
        // Card come first, then this answer, so tafs never hears of a half-done install.
        if call.install != nil, answer == .approveOnce, !confirmed, !installed {
            lastError = "Install it from its card: the version goes live, then Coach hears."
            return
        }
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
        // Approving, or raising a Card, needs the approval key KIFF holds: without it, only a
        // new sign-in lets this phone approve (kiff/tafs#233).
        if approvalKeyLost, call.kiffID != nil, answer == .approveOnce || answer == .extraRoom {
            lastError = KIFFClient.approvalKeyLost
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
                                                       answer: Self.kiffAnswer(answer),
                                                       nextStep: nextStep, accountingVersion: version, signedAt: signedAt)
                // Approving, or raising a Card, grants authority: the approval key signs it,
                // under the Face ID it checks itself, once KIFF holds it (kiff/tafs#233).
                let grants = sessionHasApprovalKey && (answer == .approveOnce || answer == .extraRoom)
                if grants {
                    signature = confirmed ? try signer.signApprovalConfirmed(message) : try await signer.signApproval(message, reason: reason)
                } else {
                    signature = confirmed ? try signer.signConfirmed(message) : try await signer.sign(message, reason: reason)
                }
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
                case .extraRoom: try await kiff.changeCard(kiffID, signature: signed)
                }
            } catch KIFFClient.KIFFError.approvalKeyRequired {
                lastError = KIFFClient.approvalKeyLost
                return
            } catch {
                lastError = Self.answerRefusedWords(error)
                if let code = Self.lastingRefusal(error) { unanswerable[call.id] = code }
                return
            }
        }

        resolve(call)
        if let kiffID = call.kiffID {
            // The paused run goes on with the answer (kiff/tafs#102): the chat shows it working
            // and then its reply, as after a message, and KIFF is read again for the Card's use.
            chatStates[call.tafID, default: ChatState()].awaiting = clock()
            // Asked to go on now, not when KIFF's alert reaches it (kiff/tafs#206). Best
            // effort: the alert and the hold's expiry still continue it.
            let taf = cloudTaf(for: call.tafID)
            Task {
                if let taf { _ = try? await cloud.continueHeld(taf.id, exception: kiffID) }
                await refresh()
            }
        }
        // The owner's decision stays in the conversation where the card was, at once and
        // in its history (kiff/tafs#138): "Refused by you · 22:41 · Refund €150.00". For any
        // held call, a taf's or another agent's with a Card; only a taf's line changes too.
        let i = tafs.firstIndex(where: { $0.id == call.tafID })
        let when = clock().shortTime
        switch answer {
        case .approveOnce:
            if call.kiffID == nil, let i { CardCheck.draw(call.proposed, on: &tafs[i]) }
            decide(call, .approved, "Approved once by you · \(when) · \(call.proposed.summary)",
                   amountLine(call.proposed, "approved once by \(ownerName)"), signature: signature)
            if let i { tafs[i].now = call.proposed.summary }
        case .nextStep(let step):
            decide(call, .refused, "Refused by you · \(when), with a next step · \(call.proposed.summary)",
                   "Refused, with a next step for the taf: \(step)", signature: signature)
            if let i { tafs[i].now = step }
        case .extraRoom:
            decide(call, .approved, "Extra room by you · \(when) · \(call.proposed.summary)",
                   "Its Card got the room this call needed, until that total resets", signature: signature)
            if let i { tafs[i].now = call.proposed.summary }
        case .refuse:
            decide(call, .refused, "Refused by you · \(when) · \(call.proposed.summary)", call.reason, signature: signature)
            if let i { tafs[i].now = "Refused · \(call.proposed.summary)" }
        }
        if let i, tafs[i].state == .waiting, !held.contains(where: { $0.tafID == call.tafID }) {
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
        let detail = dropped.isEmpty ? "No new action goes through" : "No new action goes through. \(dropped.count) waiting action(s) refused."
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

    // MARK: Halt

    /// Each halt as last read, by the taf's tafs cloud id, "" for the
    /// whole account.
    @Published private(set) var halts: [String: TafsCloud.Halt] = [:]

    /// The halt of a taf, or with none of the whole account, as last read: running until read.
    func haltState(_ tafID: UUID?) -> TafsCloud.Halt {
        haltKey(tafID).flatMap { halts[$0] } ?? .running
    }

    /// tafs cloud's id for a taf, "" for the account; nil for a taf tafs cloud doesn't run.
    private func haltKey(_ tafID: UUID?) -> String? {
        guard let tafID else { return "" }
        return cloudTaf(for: tafID)?.id
    }

    /// Halts a taf, or with none every taf: one tap, no Face ID, since it only takes authority
    /// away. tafs cloud halts it in kiff/agents and KIFF at once; it reads halted only once
    /// kiff/agents, KIFF's gateway and planque all hold it, and halting until then.
    func halt(_ tafID: UUID?) async {
        guard mode == .kiff, let key = haltKey(tafID) else { return }
        let gen = accountGeneration
        do {
            let h = try await cloud.halt(key.isEmpty ? nil : key)
            guard gen == accountGeneration else { return }
            halts[key] = h
            // The list reads it halted at once, not at its next read (kiff/tafs#236, item 13).
            await refresh()
        } catch {
            lastError = "tafs couldn't halt \(tafID == nil ? "your tafs" : "it"): \(error.localizedDescription). Try again."
        }
    }

    // MARK: Usage (kiff/tafs#184)

    /// Reads the period's usage: from KIFF signed in, a made-up month in the sample.
    func refreshUsage() async {
        switch mode {
        case .signedOut:
            usage = nil; countedTasks = []; aiUse = nil
        case .sample:
            usage = Self.sampleUsage(now: clock()); countedTasks = []; usageError = nil
            aiUse = .init(usedPct: 30, asksAtPct: 100, paused: false)
        case .kiff:
            let gen = accountGeneration
            do {
                let read = try await kiff.usage()
                let page = try? await kiff.countedTasks()
                // AI use is tafs cloud's; the rest of the screen doesn't wait on it.
                let ai = try? await cloud.aiUse()
                guard gen == accountGeneration else { return }
                usage = read; countedTasks = page?.tasks ?? []; usageError = nil; aiUse = ai
                if let store = read.store { await purchases.load([store.soloProduct] + store.packs.map(\.appleProduct)) }
            } catch {
                guard gen == accountGeneration else { return }
                usageError = "Usage couldn't be read: \(error.localizedDescription)"
            }
        }
    }

    // MARK: Tasks and plans

    /// Buys Solo through the App Store, for this account.
    func subscribeSolo() async {
        guard let store = usage?.store else { return }
        await buy(store.soloProduct, request: nil)
    }

    /// Buys a pack through the App Store; with a member's request, it answers it.
    func buyPack(_ pack: KIFFClient.Usage.Store.Pack, answering request: KIFFClient.PackRequest? = nil) async {
        await buy(pack.appleProduct, request: request)
    }

    private func buy(_ product: String, request: KIFFClient.PackRequest?) async {
        tasksMessage = nil
        guard mode == .kiff, let token = usage?.store?.appAccountToken else {
            tasksMessage = "Only the account's admin adds tasks."
            return
        }
        switch await purchases.buy(product, accountToken: token, requestID: request?.id) {
        case .done: tasksMessage = nil
        case .cancelled: break
        case .pending: tasksMessage = "The purchase is waiting for approval. Your tasks are added once it's done."
        case .notRecorded: tasksMessage = "Bought. KIFF hasn't added it yet: tafs tries again when it opens."
        case .notAdded(let why), .needsSupport(let why): tasksMessage = why
        case .failed(let why): tasksMessage = "The purchase didn't go through: \(why)"
        }
    }

    /// Brings back this Apple account's purchases, and sends each to KIFF.
    func restorePurchases() async {
        tasksMessage = nil
        guard mode == .kiff else { return }
        switch await purchases.restore() {
        case .done: tasksMessage = "Purchases restored."
        case .notRecorded: tasksMessage = "Restored. KIFF hasn't added everything yet: tafs tries again when it opens."
        case .notAdded(let why), .needsSupport(let why): tasksMessage = why
        case .failed(let why): tasksMessage = "Purchases couldn't be restored: \(why)"
        default: break
        }
        await refreshUsage()
    }

    /// Asks the account's admins for a pack.
    func askForPack(_ pack: KIFFClient.Usage.Store.Pack, reason: String) async -> Bool {
        tasksMessage = nil
        guard mode == .kiff else { return false }
        do {
            _ = try await kiff.askForPack(pack.id, reason: reason.trimmingCharacters(in: .whitespacesAndNewlines))
            await refreshUsage()
            return true
        } catch {
            tasksMessage = "The request didn't go: \(error.localizedDescription)"
            return false
        }
    }

    /// Takes back this person's request.
    func withdrawRequest(_ request: KIFFClient.PackRequest) async {
        tasksMessage = nil
        guard mode == .kiff else { return }
        do {
            _ = try await kiff.withdrawRequest(request.id)
        } catch {
            tasksMessage = "The request wasn't withdrawn: \(error.localizedDescription)"
        }
        await refreshUsage()
    }

    /// Declines a member's request, with a note: the admin's answer, behind Face ID.
    func declineRequest(_ request: KIFFClient.PackRequest, note: String) async -> Bool {
        tasksMessage = nil
        guard mode == .kiff, let tenant = kiffTenant else { return false }
        guard sessionHasKey else {
            tasksMessage = "Sign in with KIFF again: this sign-in can't sign for the phone."
            return false
        }
        let gen = accountGeneration
        do { _ = try await signer.sign(Data("tafs.decline-request.v1|\(tenant)|\(request.id)".utf8), reason: "Decline the request") } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            _ = try await kiff.declineRequest(request.id, note: note.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            guard gen == accountGeneration else { return false }
            tasksMessage = "The request wasn't declined: \(error.localizedDescription)"
            await refreshUsage()
            return false
        }
        await refreshUsage()
        return true
    }

    /// The KIFF agent a taf acts as: what KIFF's usage counts it by.
    func kiffAgent(for tafID: UUID) -> String? {
        holderIDs.first(where: { $0.value == tafID })?.key
    }

    /// The taf's part of the period, from KIFF's `by_agent`; nil until usage
    /// is read. A taf KIFF doesn't list counted nothing.
    func tafUsage(for tafID: UUID) -> KIFFClient.Usage.AgentCount? {
        guard let u = usage else { return nil }
        guard let agent = kiffAgent(for: tafID) else { return .init(agent: "", counted: 0) }
        return u.tasks.byAgent?.first { $0.agent == agent } ?? .init(agent: agent, counted: 0)
    }

    /// The taf's counted tasks this period, from KIFF's `by_agent`; nil until usage is read.
    func countedTasks(for tafID: UUID) -> Int? {
        tafUsage(for: tafID)?.counted
    }

    /// Reads the period's usage if it isn't yet, and the taf's own counted tasks.
    func refreshTafUsage(_ tafID: UUID) async {
        if usage == nil { await refreshUsage() }
        guard mode == .kiff, let agent = kiffAgent(for: tafID) else { return }
        let gen = accountGeneration
        guard let page = try? await kiff.countedTasks(agent: agent), gen == accountGeneration else { return }
        tafTasks[tafID] = page.tasks
    }

    /// The Record entry of a counted task, by KIFF's call key (the usage read's `record`).
    func recordEntry(forCall key: String) -> RecordEntry? {
        record.first { $0.entityID == key }
    }

    /// The taf acting as a KIFF agent, by its name; the agent's id when tafs doesn't know it.
    func tafName(forAgent agent: String) -> String {
        cloudTafs[agent]?.name ?? holderIDs[agent].flatMap { taf($0)?.name } ?? agent
    }

    /// The sample's month: a trial two days in.
    static func sampleUsage(now: Date) -> KIFFClient.Usage {
        var free = KIFFClient.Usage.Free()
        free.refused = 3; free.failed = 1; free.read = 42; free.trial = 2
        return KIFFClient.Usage(
            plan: "trial",
            period: .init(start: now.addingTimeInterval(-2 * 86_400), end: now.addingTimeInterval(5 * 86_400)),
            tasks: .init(counted: 14, included: 25, packs: 0, left: 11, byAgent: [], free: free),
            actions: .init(counted: 0, included: 500),
            trial: .init(endsAt: now.addingTimeInterval(5 * 86_400), daysLeft: 5, tasksLeft: 11, actionsLeft: 500),
            // The purchase screens' screenshots, as an admin sees them.
            canAddTasks: Screenshots.purchases ? true : nil, store: Screenshots.purchases ? Screenshots.store : nil)
    }

    func refreshHalt(_ tafID: UUID?) async {
        guard mode == .kiff, let key = haltKey(tafID) else { return }
        let gen = accountGeneration
        guard let h = try? await cloud.halted(key.isEmpty ? nil : key), gen == accountGeneration else { return }
        halts[key] = h
    }

    /// Resumes a halted taf, or with none every taf, behind Face ID: KIFF first, signed by
    /// this phone, since it gives authority back; then tafs cloud, which lets kiff/agents run
    /// it only once KIFF says it is resumed. What it was doing before the halt doesn't come back.
    @discardableResult
    func resumeHalt(_ tafID: UUID?) async -> Bool {
        guard mode == .kiff, let key = haltKey(tafID) else { return false }
        guard sessionHasKey, let tenant = kiffTenant else {
            lastError = "Sign in with KIFF again: this sign-in can't sign for the phone."
            return false
        }
        let agent: String?
        if let tafID {
            guard let a = cloudTaf(for: tafID)?.kiffAgent, !a.isEmpty else { return false }
            agent = a
        } else {
            agent = nil
        }
        let name = tafID.flatMap { taf($0)?.name }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.resume.v1|\(tenant)|\(agent ?? "*")".utf8),
                                      reason: name.map { "Resume \($0)" } ?? "Resume all your tafs")
        } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            try await kiff.resume(agent: agent)
        } catch {
            lastError = "KIFF didn't resume \(name ?? "your tafs"): \(error.localizedDescription)"
            return false
        }
        do {
            let h = try await cloud.releaseHalt(key.isEmpty ? nil : key)
            guard gen == accountGeneration else { return false }
            halts[key] = h
            await refresh()
            return true
        } catch {
            lastError = "KIFF resumed \(name ?? "your tafs"), but tafs cloud didn't yet: \(error.localizedDescription). Try again."
            return false
        }
    }

    // MARK: Deleting the account

    /// Why deleting the account stopped, for its sheet; nil when nothing went wrong.
    @Published var deleteAccountError: String?

    /// Deletes the account everywhere, behind Face ID (kiff/tafs#185, App Store Review
    /// Guideline 5.1.1(v)): tafs cloud erases every taf and what it and kiff/agents keep; then
    /// planque forgets the account's connections; then KIFF, last, since it ends this phone's
    /// session. Then the app signs out. Every step can be asked again, so when one fails the
    /// owner tries again and it goes on from there. True once the account is deleted.
    @discardableResult
    func deleteAccount() async -> Bool {
        deleteAccountError = nil
        guard mode == .kiff else { return false }
        guard sessionHasKey, let tenant = kiffTenant else {
            deleteAccountError = "Sign in with KIFF again: this sign-in can't sign for the phone."
            return false
        }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.delete-account.v1|\(tenant)".utf8), reason: "Delete your account")
        } catch { return false }
        guard gen == accountGeneration else { return false }
        do {
            let r = try await cloud.deleteAccount()
            guard r.done else {
                deleteAccountError = "tafs couldn't erase everything yet. Your KIFF account isn't deleted. Try again."
                return false
            }
        } catch let error as TafsCloud.CloudError where error.status == 403 {
            deleteAccountError = "Only the account's owner can delete it."
            return false
        } catch {
            deleteAccountError = "tafs couldn't erase your tafs: \(error.localizedDescription). Your KIFF account isn't deleted. Try again."
            return false
        }
        let signer = self.signer
        await loadDeviceID()
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil; planque.signer = nil }
        do {
            try await planque.eraseAccount()
        } catch {
            deleteAccountError = "Your tafs are erased, but your connections aren't yet: \(error.localizedDescription). Try again."
            return false
        }
        do {
            try await kiff.deleteAccount()
        } catch {
            deleteAccountError = "Your tafs and connections are erased, but KIFF didn't delete the account: \(error.localizedDescription). Try again."
            return false
        }
        await signOut()
        return true
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

    // MARK: Versions (kiff/tafs#107)

    /// Each taf's versions, oldest first, as tafs cloud keeps them: like app builds, any one
    /// can be live.
    @Published private(set) var versions: [UUID: [TafsCloud.Version]] = [:]
    @Published var versionErrors: [UUID: String] = [:]

    /// The version being made live on a taf, while this phone works on it.
    @Published private(set) var switchingVersion: [UUID: Int] = [:]
    /// A switch under way on each taf, as tafs cloud keeps it: one that didn't finish shows
    /// here, to finish or cancel. Nothing of the taf runs meanwhile.
    @Published private(set) var switches: [UUID: TafsCloud.Switching] = [:]

    /// Reloads a taf's versions from tafs cloud.
    func loadVersions(_ tafID: UUID) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID) else { return }
        let gen = accountGeneration
        let result: Result<TafsCloud.VersionState, Error>
        do { result = .success(try await cloud.versionState(c.id)) } catch { result = .failure(error) }
        guard gen == accountGeneration, mode == .kiff else { return }
        switch result {
        case .success(let state):
            versions[tafID] = state.versions
            switches[tafID] = state.switching
            versionErrors[tafID] = nil
        case .failure(let error): versionErrors[tafID] = "Its versions couldn't be loaded: \(error.localizedDescription)"
        }
    }

    /// Makes a version live, forward or back, with one Face ID. When the version brings a
    /// Card revision, the switch begins first, so nothing of the taf runs from then until
    /// it ends; then that Face ID signs the draft's confirmation, which KIFF verifies, as
    /// connecting does; then the version goes live. A switch that stops half way stays
    /// begun (the taf doesn't run), and tafs cloud ends it once KIFF has the Card, or this
    /// phone finishes or cancels it. Otherwise it only asks for Face ID:
    /// nobody checks that signature, so it is the owner's presence, never a record. Such a
    /// switch changes no authority; KIFF decides every call under the live Cards.
    /// The Card a version brings, as the owner reads it before Face ID (kiff/tafs#107):
    /// its draft, each revised Card with its terms now. Nil with `versionErrors` when it
    /// can't be read.
    func versionCard(_ version: TafsCloud.Version, of tafID: UUID) async -> KIFFClient.AgentDraft? {
        guard mode == .kiff, let tenant = kiffTenant, let id = switchDraft(version, of: tafID) else { return nil }
        let gen = accountGeneration
        kiff.tenant = tenant
        do {
            let draft = try await kiff.draft(id: id)
            return gen == accountGeneration ? draft : nil
        } catch {
            guard gen == accountGeneration else { return nil }
            versionErrors[tafID] = "Couldn't read this version's Card: \(error.localizedDescription)"
            return nil
        }
    }

    /// The Card draft a switch to `version` waits for: the one tafs cloud prepared when it
    /// began (kiff/tafs#122), else the version's own.
    func switchDraft(_ version: TafsCloud.Version, of tafID: UUID) -> String? {
        if let sw = switches[tafID], sw.n == version.n { return sw.draft }
        return version.draft
    }

    /// Versions whose Card KIFF refused at confirm because a Card moved on since it was
    /// drafted: the owner drafts it again, and reads it before any Face ID.
    @Published private(set) var staleVersionCards: [UUID: Int] = [:]

    /// Begins the switch to a version (kiff/tafs#122): tafs cloud prepares the Card draft it
    /// waits for, which the owner then reads, and from here nothing of the taf runs. With
    /// `redraft`, its Cards are drafted again. Nil with `versionErrors` when it can't begin.
    func prepareSwitch(_ version: TafsCloud.Version, of tafID: UUID, redraft: Bool = false, stopBlocking: Bool = false) async -> TafsCloud.Switching? {
        guard mode == .kiff, let c = cloudTaf(for: tafID), !version.live else { return nil }
        let gen = accountGeneration
        versionErrors[tafID] = nil
        blockedInstalls[tafID] = nil
        do {
            let sw = try await cloud.beginSwitch(c.id, version: version.n, redraft: redraft, stopBlocking: stopBlocking)
            guard gen == accountGeneration else { return nil }
            switches[tafID] = sw
            staleVersionCards[tafID] = nil
            return sw
        } catch let error as TafsCloud.CloudError where error.status == 409 && !error.blocking.isEmpty {
            guard gen == accountGeneration else { return nil }
            // Named, with the one step that clears it (kiff/tafs#206).
            blockedInstalls[tafID] = version.n
            versionErrors[tafID] = Self.blockedWords(version.n, error.blocking, stopping: stopBlocking)
            return nil
        } catch {
            guard gen == accountGeneration else { return nil }
            versionErrors[tafID] = "Version \(version.n) can't go live now: \(error.localizedDescription)"
            return nil
        }
    }

    /// The version whose install a run is in the way of, by taf: the panel offers "Stop it
    /// and install" (kiff/tafs#206).
    @Published private(set) var blockedInstalls: [UUID: Int] = [:]

    /// Which run is in the way of installing version n, in the owner's words.
    static func blockedWords(_ n: Int, _ runs: [TafsCloud.BlockingRun], stopping: Bool) -> String {
        let waiting = runs.contains { $0.status == "PAUSED" }
        let what: String
        switch (runs.count, waiting) {
        case (1, true): what = "a task waits on your answer"
        case (1, false): what = runs[0].trigger == "schedule" ? "its scheduled run is at work" : "a task is at work"
        case (_, true): what = "\(runs.count) tasks are going, one waiting on your answer"
        default: what = "\(runs.count) tasks are at work"
        }
        return stopping
            ? "Version \(n) isn't installed yet: \(what) and hasn't stopped. Try again in a moment."
            : "Version \(n) can't go live while \(what). Stop it and install, or wait until it's done."
    }

    /// A version's Card that changed between the screen and the tap (kiff/tafs#115): put
    /// on screen to read again; nothing was signed.
    @Published private(set) var changedVersionCards: [UUID: KIFFClient.AgentDraft] = [:]

    /// `shownDraft` is the version of the draft the owner's screen showed: Face ID signs
    /// exactly that, and a version that brings a Card with nothing shown signs nothing.
    /// The taf and version Coach's held install names (kiff/tafs#206), with its switch begun
    /// (what's in the way stopped first) and the Card draft it waits for: the owner reads that
    /// Card before the one Face ID. Nil, with `versionErrors` or `lastError`, when it can't begin.
    func beginCoachInstall(_ call: HeldCall) async -> CoachInstall? {
        guard let ask = call.install else { return nil }
        let match = cloudTafs.first { $0.value.id == ask.taf } ?? cloudTafs.first { $0.value.name.caseInsensitiveCompare(ask.taf) == .orderedSame }
        guard let agent = match?.key, let tafID = holderIDs[agent] else {
            lastError = "Coach asked to install a version of a taf that isn't here."
            return nil
        }
        await loadVersions(tafID)
        guard let version = versions[tafID]?.first(where: { $0.n == ask.n }) else {
            lastError = "\(match?.value.name ?? "That taf") has no version \(ask.n)."
            return nil
        }
        if version.live {
            // Live already: approving only tells Coach so.
            await answer(call, with: .approveOnce, installed: true)
            return nil
        }
        guard let sw = await prepareSwitch(version, of: tafID, stopBlocking: true) else { return nil }
        return CoachInstall(call: call, tafID: tafID, version: version, hasCard: sw.draft != nil)
    }

    /// Coach's held install, ready for the owner (kiff/tafs#206).
    struct CoachInstall: Identifiable {
        let call: HeldCall
        let tafID: UUID
        let version: TafsCloud.Version
        /// The version brings a Card change, read before the Face ID.
        let hasCard: Bool
        var id: UUID { call.id }
    }

    /// `approving`: the held version_install of Coach's this install answers (kiff/tafs#206):
    /// approved once the version is live, signed with the same Face ID.
    @discardableResult
    func useVersion(_ version: TafsCloud.Version, of tafID: UUID, shownDraft: String? = nil, approving: HeldCall? = nil) async -> Bool {
        guard mode == .kiff, let tenant = kiffTenant, let c = cloudTaf(for: tafID), let taf = taf(tafID) else { return false }
        guard !version.live, switchingVersion[tafID] == nil else { return false }
        switchingVersion[tafID] = version.n
        defer { switchingVersion[tafID] = nil }
        versionErrors[tafID] = nil
        let gen = accountGeneration
        let signer = self.signer
        kiff.tenant = tenant
        defer { kiff.setupSigner = nil }

        var draft: KIFFClient.AgentDraft?
        changedVersionCards[tafID] = nil
        // Begun before any Card changes, with the draft tafs cloud prepared: from here
        // nothing of the taf runs. Again for a switch under way is the same switch.
        let sw: TafsCloud.Switching
        do { sw = try await cloud.beginSwitch(c.id, version: version.n, stopBlocking: approving != nil) } catch {
            guard gen == accountGeneration else { return false }
            versionErrors[tafID] = "Version \(version.n) can't go live now: \(error.localizedDescription)"
            return false
        }
        guard gen == accountGeneration else { return false }
        switches[tafID] = sw
        if let id = sw.draft {
            // Nothing shown, nothing signed.
            guard shownDraft != nil else { return false }
            guard sessionHasKey else {
                versionErrors[tafID] = "Start from Connected tools on this \(ThisDevice.kind) first."
                return false
            }
            do { draft = try await kiff.draft(id: id) } catch {
                guard gen == accountGeneration else { return false }
                versionErrors[tafID] = "Couldn't read this version's Card: \(error.localizedDescription)"
                return false
            }
        }
        guard gen == accountGeneration else { return false }
        // The Card changed since it was shown: the new one goes on screen, the switch is
        // dropped, and the owner reads it again before any Face ID.
        if let draft, draft.version != shownDraft, !draft.confirmed, draft.status != "claimed" {
            changedVersionCards[tafID] = draft
            await cancelSwitch(tafID)
            versionErrors[tafID] = "What you're asked to approve changed since it was shown. Read it again, then approve."
            return false
        }
        // A claimed draft is later than confirmed, its Cards issued all the same.
        if let draft, !draft.confirmed, draft.status != "claimed" {
            do {
                _ = try await signer.sign(Data("tafs.cards.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8),
                                          reason: "Use version \(version.n) of \(taf.name), with its Card")
            } catch {
                // The owner backed out before the Card changed: the taf runs again as it was.
                guard gen == accountGeneration else { return false }
                await cancelSwitch(tafID)
                return false
            }
            guard gen == accountGeneration else { return false }
            kiff.setupSigner = { try signer.signConfirmed($0) }
            do {
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version)
            } catch {
                guard gen == accountGeneration else { return false }
                if case KIFFClient.KIFFError.server(409, _, "card_revised") = error {
                    // A Card moved on since it was drafted (KIFF's reason):
                    // drafted again, it's read again.
                    staleVersionCards[tafID] = version.n
                    versionErrors[tafID] = "Version \(version.n)'s Card is out of date: a Card changed since it was drafted. Draft it again, then read it before you approve."
                } else {
                    versionErrors[tafID] = "Installing version \(version.n) didn't finish: KIFF didn't confirm its Card (\(error.localizedDescription)). Your taf doesn't run until you finish or cancel it."
                }
                return false
            }
            await keepKIFFAlerts(tenant: tenant, gen: gen)
            await keepCoachTools(tenant: tenant, gen: gen)
        } else {
            do {
                _ = try await signer.sign(Data("tafs.version.v1|\(tenant)|\(c.id)|\(version.n)".utf8),
                                          reason: "Use version \(version.n) of \(taf.name)")
            } catch {
                // Backed out: the switch it began is dropped, and the taf runs again as it was.
                guard gen == accountGeneration else { return false }
                await cancelSwitch(tafID)
                return false
            }
        }
        guard gen == accountGeneration else { return false }
        do {
            try await cloud.makeLive(c.id, version: version.n)
        } catch {
            guard gen == accountGeneration else { return false }
            versionErrors[tafID] = sw.draft == nil
                ? "Version \(version.n) isn't live: \(error.localizedDescription)"
                : "Installing version \(version.n) didn't finish (\(error.localizedDescription)). Your taf doesn't run until you finish or cancel it."
            return false
        }
        await loadVersions(tafID)
        guard gen == accountGeneration else { return false }
        // Live: now Coach's held call is approved, with the Face ID just given.
        if let approving { await answer(approving, with: .approveOnce, confirmed: true) }
        return gen == accountGeneration
    }

    /// Drops a taf's switch under way, while its Card isn't confirmed: the live version
    /// stays and the taf runs again. Once its Card is confirmed, tafs cloud ends the switch
    /// instead. Either way the versions are read again.
    func cancelSwitch(_ tafID: UUID) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID) else { return }
        let gen = accountGeneration
        do { try await cloud.cancelSwitch(c.id) } catch {
            guard gen == accountGeneration else { return }
            versionErrors[tafID] = "The switch wasn't cancelled: \(error.localizedDescription)"
        }
        await loadVersions(tafID)
    }

    // MARK: Trying a version (kiff/tafs#173)

    /// Each taf's open trial: the owner chats with one version under a trial Card of its
    /// own, while the live version keeps its schedule, Card and budget.
    @Published private(set) var openTrials: [UUID: TafsCloud.OpenTrial] = [:]
    /// Each open trial's chat, by its own id, to its taf. The trial is its own entry in
    /// the main menu, and its chat is the taf's chat view on that id: one universal chat.
    @Published private(set) var trialChats: [UUID: UUID] = [:]
    @Published var trialErrors: [UUID: String] = [:]
    /// The tafs whose trial is starting, installing or ending, so one tap does one thing.
    @Published private(set) var trialBusy: Set<UUID> = []

    /// The id of a taf's open trial's chat; nil when it has none.
    func trialChat(of tafID: UUID) -> UUID? {
        trialChats.first { $0.value == tafID }?.key
    }

    /// The taf a trial's chat belongs to, when `id` is one.
    func tafOfTrialChat(_ id: UUID) -> UUID? { trialChats[id] }

    /// A trial's chat drawn as its taf, named as the menu names it: "Refunds · Trying v2".
    /// A request the app itself dropped (the screen went away): nothing to tell the owner.
    static func cancelled(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private func trialTaf(_ id: UUID) -> Taf? {
        guard let tafID = trialChats[id], let trial = openTrials[tafID],
              var taf = tafs.first(where: { $0.id == tafID }) else { return nil }
        taf.id = id
        taf.name = "\(taf.name) · v\(trial.n)"
        taf.badge = trial.label ?? "Trial"
        taf.now = [trial.title ?? "Trying version \(trial.n)", trial.ends.map { "ends \($0.shown(date: .abbreviated, time: .omitted)) if unused" }]
            .compactMap { $0 }.joined(separator: " · ")
        return taf
    }

    /// Reads a taf's open trial. A trial that ended elsewhere (Install, Go back, a week
    /// unused) leaves the menu here too.
    func loadTrial(_ tafID: UUID) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID) else { return }
        let gen = accountGeneration
        do {
            let open = try await cloud.openTrial(c.id)
            guard gen == accountGeneration else { return }
            setTrial(open, of: tafID)
        } catch {
            guard gen == accountGeneration, !Self.cancelled(error) else { return }
            trialErrors[tafID] = "The trial couldn't be read: \(error.localizedDescription)"
        }
    }

    private func setTrial(_ open: TafsCloud.OpenTrial?, of tafID: UUID) {
        openTrials[tafID] = open
        let chat = trialChat(of: tafID)
        if open == nil, let chat {
            trialChats[chat] = nil
            chats[chat] = nil; chatStates[chat] = nil; chatErrors[chat] = nil; chatSent[chat] = nil
        } else if open != nil, chat == nil {
            trialChats[UUID()] = tafID
        }
    }

    /// The Card a trial of version n runs under, as the owner reads it before Face ID: the
    /// draft tafs cloud prepares for it. Nil with `trialErrors` when it can't be read.
    func trialCard(_ version: TafsCloud.Version, of tafID: UUID) async -> KIFFClient.AgentDraft? {
        guard mode == .kiff, let tenant = kiffTenant, let c = cloudTaf(for: tafID) else { return nil }
        let gen = accountGeneration
        kiff.tenant = tenant
        do {
            let id = try await cloud.trialDraft(c.id, version: version.n)
            let draft = try await kiff.draft(id: id)
            return gen == accountGeneration ? draft : nil
        } catch {
            guard gen == accountGeneration else { return nil }
            trialErrors[tafID] = "Version \(version.n)'s trial Card couldn't be read: \(error.localizedDescription)"
            return nil
        }
    }

    /// Starts trying version n (kiff/tafs#173): tafs cloud gives the draft of its trial
    /// Card, the owner read it (`shownDraft`, its version), one Face ID confirms it in KIFF
    /// as a trial, and the trial opens. Returns its chat's id.
    func startTrying(_ version: TafsCloud.Version, of tafID: UUID, shownDraft: String) async -> UUID? {
        guard mode == .kiff, let tenant = kiffTenant, let c = cloudTaf(for: tafID), let taf = taf(tafID) else { return nil }
        guard !version.live, trialBusy.insert(tafID).inserted else { return nil }
        defer { trialBusy.remove(tafID) }
        trialErrors[tafID] = nil
        guard sessionHasKey else {
            trialErrors[tafID] = "Start from Connected tools on this iPhone first."
            return nil
        }
        let gen = accountGeneration
        let signer = self.signer
        kiff.tenant = tenant
        defer { kiff.setupSigner = nil }
        let draft: KIFFClient.AgentDraft
        do {
            let id = try await cloud.trialDraft(c.id, version: version.n)
            draft = try await kiff.draft(id: id)
        } catch {
            guard gen == accountGeneration else { return nil }
            trialErrors[tafID] = "Version \(version.n) can't be tried now: \(error.localizedDescription)"
            return nil
        }
        guard gen == accountGeneration else { return nil }
        // What the owner read is what Face ID signs: changed since, it's read again.
        guard draft.version == shownDraft else {
            changedVersionCards[tafID] = draft
            trialErrors[tafID] = "What you're asked to approve changed since it was shown. Read it again, then start."
            return nil
        }
        do {
            _ = try await signer.sign(Data("tafs.trial.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8),
                                      reason: "Try version \(version.n) of \(taf.name), with its own Card")
        } catch { return nil }
        guard gen == accountGeneration else { return nil }
        kiff.setupSigner = { try signer.signConfirmed($0) }
        do {
            let confirmed = try await kiff.confirmDraftAsTrial(id: draft.id, version: draft.version)
            // Only a KIFF that runs trials answers "trying"; anything else is never taken
            // for a trial.
            guard confirmed.status == "trying" else {
                guard gen == accountGeneration else { return nil }
                trialErrors[tafID] = confirmed.status == "confirmed"
                    ? "KIFF didn't start a trial: it issued these Cards as the taf's own. Check its Card tab, or install the version you had."
                    : "KIFF didn't start a trial for this version (it answered \(confirmed.status)). Nothing runs under it."
                await loadVersions(tafID)
                return nil
            }
            let open = try await cloud.openTrial(c.id, version: version.n, draft: draft.id)
            guard gen == accountGeneration else { return nil }
            setTrial(open, of: tafID)
            return trialChat(of: tafID)
        } catch {
            guard gen == accountGeneration else { return nil }
            trialErrors[tafID] = "Trying version \(version.n) didn't start: \(error.localizedDescription)"
            return nil
        }
    }

    /// Install (kiff/tafs#173): one Face ID confirms the trial's Card live in KIFF, then the
    /// version goes live and the trial ends, its chat deleted.
    @discardableResult
    func installTrial(_ tafID: UUID) async -> Bool {
        guard mode == .kiff, let tenant = kiffTenant, let c = cloudTaf(for: tafID), let taf = taf(tafID),
              let open = openTrials[tafID] else { return false }
        guard trialBusy.insert(tafID).inserted else { return false }
        defer { trialBusy.remove(tafID) }
        trialErrors[tafID] = nil
        let gen = accountGeneration
        let signer = self.signer
        kiff.tenant = tenant
        defer { kiff.setupSigner = nil }
        do {
            let draft = try await kiff.draft(id: open.draft)
            guard gen == accountGeneration else { return false }
            if !draft.confirmed {
                do {
                    _ = try await signer.sign(Data("tafs.cards.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8),
                                              reason: "Install version \(open.n) of \(taf.name), with its Card")
                } catch { return false }
                guard gen == accountGeneration else { return false }
                kiff.setupSigner = { try signer.signConfirmed($0) }
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version)
            }
            _ = try await cloud.installTrial(c.id)
        } catch {
            guard gen == accountGeneration else { return false }
            trialErrors[tafID] = "Installing version \(open.n) didn't finish: \(error.localizedDescription)"
            return false
        }
        guard gen == accountGeneration else { return false }
        setTrial(nil, of: tafID)
        await loadVersions(tafID)
        return true
    }

    /// Go back (kiff/tafs#173): KIFF ends the trial's Cards, the trial's chat is deleted, and
    /// the live version stays as it was. It takes authority away, so no Face ID.
    @discardableResult
    func goBackFromTrial(_ tafID: UUID) async -> Bool {
        guard mode == .kiff, let c = cloudTaf(for: tafID), openTrials[tafID] != nil else { return false }
        guard trialBusy.insert(tafID).inserted else { return false }
        defer { trialBusy.remove(tafID) }
        trialErrors[tafID] = nil
        let gen = accountGeneration
        guard let tenant = kiffTenant, let open = openTrials[tafID] else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            try await kiff.endTrial(draft: open.draft)
            _ = try await cloud.endTrial(c.id)
        } catch {
            guard gen == accountGeneration else { return false }
            trialErrors[tafID] = "The trial didn't end: \(error.localizedDescription)"
            return false
        }
        guard gen == accountGeneration else { return false }
        setTrial(nil, of: tafID)
        await loadVersions(tafID)
        return true
    }

    // MARK: Chat

    /// Each taf's thread, oldest first: the owner's messages and the taf's replies.
    @Published private(set) var chats: [UUID: [TafsCloud.ChatMessage]] = [:]
    /// The tafs a message is on its way to, so one is sent at a time.
    @Published private(set) var chatSending: Set<UUID> = []
    @Published var chatErrors: [UUID: String] = [:]
    /// How long a sample taf takes to answer; tests set it to zero, and the UI tests slow it.
    var sampleReplyDelay: UInt64 = Screenshots.sampleReplySeconds.map { UInt64($0 * 1_000_000_000) } ?? 700_000_000

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
        do {
            result = .success(trialChats[tafID] != nil ? try await cloud.trialConversation(c.id) : try await cloud.conversation(c.id))
        } catch { result = .failure(error) }
        // An answer for a session that has since ended (signed out, another account) is dropped.
        guard gen == accountGeneration, mode == .kiff else { return }
        switch result {
        case .success(let thread):
            // What the owner sent stays on screen until the thread has it (kiff/tafs#138, 48).
            let (messages, waiting) = SentMessages.merge(thread.messages, sent: chatSent[tafID] ?? [], now: clock())
            chatSent[tafID] = waiting.isEmpty ? nil : waiting
            chats[tafID] = messages
            chatErrors[tafID] = nil
            // A run seen at work here is over now: what it did reaches the Card and the record.
            if chatStates[tafID]?.working != nil, thread.working == nil { Task { await refresh() } }
            // A stopped session that tafs cloud no longer has at work is done with.
            if let stopped = chatStates[tafID]?.stopped, thread.working != stopped { chatStates[tafID]?.stopped = nil }
            chatStates[tafID, default: ChatState()].working = thread.working
            chatStates[tafID]?.workingRun = thread.workingRun
            chatStates[tafID]?.steering = thread.steering
            // The thread is the source: tafs cloud says when the latest run stopped at a cap, and a
            // run at work, or one that answered since, is past it.
            chatStates[tafID]?.capped = thread.working == nil ? thread.capped?.stop : nil
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

    /// `files` go with the message (kiff/tafs#209): a message may be files alone. One with
    /// files that doesn't go is left with the composer, files and all, rather than kept unsent.
    private func deliverChat(_ text: String, to tafID: UUID, files: [TafsCloud.AttachmentRef] = []) async -> Delivery {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !files.isEmpty else { return .notTried }
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
        let latest = chats[tafID]?.map(\.at).max()
        let pending = TafsCloud.ChatMessage(at: SentMessages.stamp(after: latest, now: clock()), from: .owner, text: text, session: "pending",
                                            attachments: files)
        chats[tafID, default: []].append(pending)
        if mode != .sample { chatSent[tafID, default: []].append(pending) }

        if mode == .sample {
            // The sample answers as a taf does (kiff/tafs#285): it thinks, takes a step, then
            // writes, into the live answer; then the answer is the thread's, with its work.
            let reply = sampleReply(to: text, tafID: tafID)
            let started = clock()
            let beat = sampleReplyDelay / 2
            func wait() async -> Bool {
                if beat > 0 { try? await Task.sleep(nanoseconds: beat) }
                return current() && mode == .sample
            }
            chatStates[tafID, default: ChatState()].live = LiveAnswer(started: started)
            if beat > 0 {
                for line in ["Looking at what I did lately, ", "so the answer names each one."] {
                    guard await wait() else { return .notTried }
                    chatStates[tafID]?.live?.thinking += line
                }
                guard await wait() else { return .notTried }
                chatStates[tafID]?.live?.began("memory", words: tafActivityWords("memory"), call: "sample-1")
                guard await wait() else { return .notTried }
                chatStates[tafID]?.live?.ended("sample-1", .done)
                for word in reply.split(separator: " ", omittingEmptySubsequences: false) {
                    try? await Task.sleep(nanoseconds: beat / 12)
                    guard current(), mode == .sample else { return .notTried }
                    let t = chatStates[tafID]?.live?.text ?? ""
                    chatStates[tafID]?.live?.text = t.isEmpty ? String(word) : t + " " + word
                    chatStates[tafID]?.live?.thinking = ""
                }
            }
            guard current(), mode == .sample else { return .notTried }
            let ms = Int(clock().timeIntervalSince(started) * 1000)
            var answer = sampleMessage(.taf, reply)
            if beat > 0 { answer.work = TafsCloud.Work(ms: ms, steps: [.init(tool: "memory")]) }
            // The message shown while it worked gives way to the thread's stored copy, under
            // another id, as tafs cloud's reload does at a run's end (the 859 test's blank chat).
            if let i = chats[tafID]?.firstIndex(of: pending) {
                chats[tafID]?[i] = TafsCloud.ChatMessage(at: pending.at, from: .owner, text: text, session: "sample", attachments: files)
            }
            chats[tafID, default: []].append(answer)
            chatStates[tafID]?.live = nil
            return .sent
        }
        guard let c = cloudTaf(for: tafID) else {
            chats[tafID]?.removeAll { $0 == pending }
            chatSent[tafID]?.removeAll { $0 == pending }
            chatErrors[tafID] = "This taf isn't running on tafs cloud, so it can't be messaged."
            return .notTried
        }
        chatStates[tafID, default: ChatState()].ended = nil
        chatStates[tafID]?.wentThrough = []
        chatStates[tafID]?.capped = nil
        do {
            // Streamed, so the taf's words show as it writes them (#64); sent plainly
            // when the stream can't open.
            // A trial's turn runs in the background, and its chat reloads as it goes.
            if trialChats[tafID] == nil, let events = try await openStream(c.id, text: text, files: files) {
                guard current() else { return .notTried }
                chatStates[tafID, default: ChatState()].awaiting = clock()
                chatStates[tafID]?.live = LiveAnswer(started: clock())
                chatStreamWorks = true
                chatStreams[tafID] = Task { await self.followChatStream(events, tafID: tafID, text: text, gen: gen) }
                clearBusyUnsent(tafID)
                return .sent
            }
            if trialChats[tafID] != nil {
                _ = try await cloud.trialMessage(c.id, text: text)
            } else {
                _ = try await cloud.sendMessage(c.id, text: text, files: files)
            }
            guard current() else { return .notTried }
            chatStates[tafID, default: ChatState()].awaiting = clock()
            clearBusyUnsent(tafID)
            await loadChat(tafID)
            return .sent
        } catch let e as TafsCloud.CloudError where e.status == 429 {
            guard current() else { return .notTried }
            chats[tafID]?.removeAll { $0 == pending }
            chatSent[tafID]?.removeAll { $0 == pending }
            if !files.isEmpty {
                chatErrors[tafID] = Self.busyWords(c.name)
                return .notTried
            }
            keepUnsent(text, tafID, why: Self.busyWords(c.name), busy: true)
            return .kept
        } catch {
            guard current() else { return .notTried }
            chats[tafID]?.removeAll { $0 == pending }
            chatSent[tafID]?.removeAll { $0 == pending }
            if !files.isEmpty {
                chatErrors[tafID] = error.localizedDescription
                return .notTried
            }
            keepUnsent(text, tafID, why: error.localizedDescription)
            return .kept
        }
    }

    // MARK: Attachments (kiff/tafs#209)

    /// The files picked for each chat's next message, as they go up: a taf's chat by the taf's
    /// id, Coach's by `setupFiles`. They go with the message, and are cleared once it's sent.
    @Published private(set) var chatFiles: [String: [PendingFile]] = [:]
    /// Why a file wasn't added, or a message with files didn't go, by chat.
    @Published var fileNotices: [String: String] = [:]
    static let setupFiles = "setup"

    /// Where a chat's files go on tafs cloud: Coach's Setup, or the taf. Nil where nothing can
    /// be attached: sample mode, a trial, or Setup improving a taf, whose first message tafs
    /// cloud writes.
    func filesTarget(_ key: String) -> String? {
        guard mode == .kiff else { return nil }
        if key == Self.setupFiles { return setupImproving == nil ? setupTaf?.id : nil }
        guard let tafID = UUID(uuidString: key), trialChats[tafID] == nil else { return nil }
        return cloudTaf(for: tafID)?.id
    }

    func files(_ key: String) -> [PendingFile] { chatFiles[key] ?? [] }

    /// Adds a file to the chat's next message and uploads it: declared to tafs cloud, which
    /// checks its type and size, then its bytes straight to storage. One that can't go says
    /// why in the tray, and is left out of the message.
    func attach(_ file: PreparedFile, to key: String) async {
        guard let target = filesTarget(key) else { return }
        fileNotices[key] = nil
        guard files(key).count < FilePrep.perMessage else {
            fileNotices[key] = "A message carries at most \(FilePrep.perMessage) files."
            return
        }
        let pending = PendingFile(id: UUID(), name: file.name, mediaType: file.mediaType, thumbnail: file.thumbnail, state: .uploading)
        chatFiles[key, default: []].append(pending)
        let gen = accountGeneration
        let state: PendingFile.State
        do {
            let (ref, upload) = try await cloud.attach(target, mediaType: file.mediaType, name: file.name, size: file.data.count,
                                                       sha256: FilePrep.sha256(file.data))
            try await cloud.upload(file.data, to: upload)
            state = .ready(ref)
        } catch {
            state = .failed(error.localizedDescription)
        }
        guard gen == accountGeneration, let i = chatFiles[key]?.firstIndex(where: { $0.id == pending.id }) else { return }
        chatFiles[key]?[i].state = state
    }

    /// A file picked that can't be attached: said in the tray, never uploaded.
    func refuseFile(_ problem: Error, in key: String) {
        fileNotices[key] = problem.localizedDescription
    }

    func removeFile(_ id: UUID, from key: String) {
        chatFiles[key]?.removeAll { $0.id == id }
        if chatFiles[key]?.isEmpty == true { chatFiles[key] = nil }
    }

    /// Whether the chat's files are all up: a message waits for the ones still going.
    func filesUploading(_ key: String) -> Bool {
        files(key).contains { $0.state == .uploading }
    }

    /// Whether Send waits: while any file is still going up, words or not, so a message never
    /// goes without a file just attached; and while files are ready but the taf (or Coach) is
    /// at work, since files go with a message of their own, never into a run.
    func holdsSend(_ key: String, thinking: Bool) -> Bool {
        filesUploading(key) || (thinking && files(key).contains { $0.ref != nil })
    }

    /// The chat's ready files, taken for the message being sent. Ones that failed stay in the tray.
    func takeFiles(_ key: String) -> [PendingFile] {
        let ready = files(key).filter { $0.ref != nil }
        chatFiles[key] = files(key).filter { $0.ref == nil }
        if chatFiles[key]?.isEmpty == true { chatFiles[key] = nil }
        return ready
    }

    /// Puts back files a message didn't take, ahead of any picked since.
    func putBackFiles(_ taken: [PendingFile], _ key: String) {
        guard !taken.isEmpty else { return }
        chatFiles[key] = taken + files(key)
    }

    /// Where files opened full size are copied: removed on sign-out and on an account switch,
    /// so nothing a person attached stays on the phone after them (docs/data.md).
    static let openedFiles = FileManager.default.temporaryDirectory.appendingPathComponent("attachments", isDirectory: true)

    static func removeOpenedFiles() {
        try? FileManager.default.removeItem(at: openedFiles)
    }

    /// A file the person attached, downloaded to open full size: a temporary copy under its
    /// own name, removed by the system. Nil when it couldn't be read; the notice says why.
    func openFile(_ ref: TafsCloud.AttachmentRef, in key: String) async -> URL? {
        let target = key == Self.setupFiles ? setupTaf?.id : UUID(uuidString: key).flatMap { cloudTaf(for: $0)?.id }
        guard mode == .kiff, let target else { return nil }
        do {
            let data = try await cloud.attachment(target, file: ref.id)
            let dir = Self.openedFiles.appendingPathComponent(ref.id, isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let url = dir.appendingPathComponent((ref.name as NSString).lastPathComponent)
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            fileNotices[key] = "\(ref.name) couldn't be opened: \(error.localizedDescription)"
            return nil
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
        /// What the failed run did before it stopped (kiff/agents#249), said with Retry.
        var wentThrough: [TafsCloud.DoneCall] = []
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
        /// Where each held call was made in the chat (kiff/tafs#206): the item it came after,
        /// by its held call's key (`heldKey`). Its card, and what became of it, stay there.
        var anchors: [String: String] = [:]
        /// Each held call's key (`heldKey`) by KIFF's decision id, once KIFF was read: the call
        /// stays placed after the owner answers it and it's no longer held.
        var heldKeys: [String: String] = [:]
        /// The session the owner stopped: tafs cloud may still say it's at work until the run
        /// reaches its next step, but here it's over at once (the 755 retest).
        var stopped: String?
        /// The latest run stopped at its cost or turn cap (`cost_cap`, `turn_cap`): the run
        /// writes nothing for it, so the chat says why (the 793 retest: it showed "…").
        var capped: String?
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
        let working = s.working.flatMap { $0 == s.stopped ? nil : $0 }
        return working != nil || s.awaiting != nil || s.live != nil
    }

    private func keepUnsent(_ text: String, _ tafID: UUID, why: String, busy: Bool = false) {
        chatStates[tafID, default: ChatState()].unsent.append(UnsentMessage(text: text, why: why, at: clock(), busy: busy))
    }

    /// Why a message wasn't sent when the owner wrote too many too fast: the runtime's limit
    /// of 10 in 10 minutes for each person (kiff/agents#220).
    static func busyWords(_ name: String) -> String {
        "That's a lot of messages in a few minutes. Give \(name) a moment, then try again."
    }

    /// A message went through: one kept only because the owner wrote too fast is over too
    /// (the 755 retest), so its warning doesn't stay under everything that follows.
    private func clearBusyUnsent(_ tafID: UUID) {
        chatStates[tafID]?.unsent.removeAll(where: \.busy)
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
        var didBefore: [TafsCloud.DoneCall] = []
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
                    // Writing again: the tool call it waited on is over (kiff/tafs#206 6.6), and
                    // what it thought led here.
                    chatStates[tafID]?.live?.activity = nil
                    chatStates[tafID]?.live?.thinking = ""
                case .thinking(let t):
                    chatStates[tafID]?.live?.thinking += t
                case .activity(let tool, let step, let call):
                    // The words so far are done; the answer goes on below them. Nothing is reloaded.
                    if let t = chatStates[tafID]?.live?.text, !t.isEmpty {
                        chatStates[tafID]?.live?.parts.append(.text(t))
                        chatStates[tafID]?.live?.text = ""
                    }
                    let words = step ?? tafActivityWords(tool)
                    chatStates[tafID]?.live?.activity = words
                    chatStates[tafID]?.live?.began(tool, words: words, call: call)
                case .step(let call, let status):
                    chatStates[tafID]?.live?.ended(call, LiveStep.Status(stream: status))
                case .said:
                    // A run picked back up wrote a message: the thread has it.
                    await loadChat(tafID)
                case .part:
                    // It went on after the owner answered a held call: what it did before shows
                    // with the answer it wrote then, not again here as new (the 859 test).
                    chatStates[tafID]?.live?.newPart()
                case .held(let exception, let invocation):
                    chatStates[tafID]?.live?.ended(nil, .held)
                    chatStates[tafID]?.live?.parts.append(.held(exception: exception, invocation: invocation))
                    // KIFF is read for the card, where the call was made.
                    Task {
                        await self.refresh()
                        if let call = self.heldCalls(for: tafID).first(where: { $0.kiffID == exception }) {
                            self.chatStates[tafID]?.heldKeys[exception] = Self.heldKey(call)
                        }
                    }
                case .end(let status, let stop, let done):
                    ended = status
                    didBefore = done
                    chatStates[tafID]?.live?.activity = nil
                    if Self.capStops.contains(stop) { chatStates[tafID]?.capped = stop }
                case .component, .broken:
                    break
                }
            }
        } catch {}
        // Stopped by the owner: Stop has cleared the answer and reloads the thread itself, and a
        // reload from a cancelled task would only fail as "cancelled".
        guard gen == accountGeneration, !Task.isCancelled else { return }
        // Nothing is at work once the stream is over, whatever comes next (kiff/tafs#206 6.6).
        chatStates[tafID]?.live?.activity = nil
        // The run is over or paused on a held call (kiff/tafs#102): KIFF is read at once, so the
        // held call shows under the answer that names it, not at the next refresh, and the
        // Card's use and the record follow what it did.
        if ended != nil { await refresh() }
        guard gen == accountGeneration else { return }
        // The run is over (or paused on a held call): the taf is no longer at work.
        if ended != nil { chatStates[tafID]?.awaiting = nil }
        if ended == "ERROR" {
            chatStates[tafID]?.ended = .failed(retry: text)
            chatStates[tafID]?.wentThrough = didBefore
        }
        if ended == "CANCELLED" { chatStates[tafID]?.ended = .stopped }
        // A run that answered or failed never reads what it didn't take: it goes next.
        // One the owner stopped keeps it, not sent, for them to decide.
        if let run = chatStates[tafID]?.live?.run, ended == "COMPLETED" || ended == "ERROR" { chatStates[tafID]?.sendNext = run }
        // The recorded answer first, then the live one goes: no gap between them. Each held
        // call keeps its place: after what the taf wrote before it (kiff/tafs#206).
        await loadChat(tafID)
        guard gen == accountGeneration else { return }
        if let live = chatStates[tafID]?.live { anchorHeld(live, tafID: tafID) }
        chatStates[tafID]?.live = nil
        await sendQueuedChatIfIdle(tafID)
    }

    /// What a taf is doing, in the owner's words: a connected tool by the title its
    /// connection gives it ("Reading List payments (charges)…"), never its name in KIFF,
    /// which ends in four letters of its grant (kiff/tafs#236, item 7).
    func tafActivityWords(_ tool: String) -> String? {
        if let title = toolTitle(tool) { return "Asking KIFF: \(title)…" }
        return Self.tafActivityWords(tool)
    }

    /// A connected tool's title, as its connection gives it, by its name in KIFF.
    func toolTitle(_ name: String) -> String? {
        let title = grants.lazy.flatMap(\.request.tools).first(where: { $0.action == name })?.title
        return title?.isEmpty == false ? title : nil
    }

    /// What a taf is doing, in the owner's words, from the tool it calls (#64). Every
    /// tool that reaches the company's systems goes through KIFF.
    static func tafActivityWords(_ tool: String) -> String? {
        switch tool {
        case "memory": return "Reading its notes…"
        case "kiff_pending": return "Checking what waits for you…"
        default:
            // The tool's own name in words, past any prefix (kiff/tafs#138, item 52).
            return "Asking KIFF to run \(KIFFMapping.toolWords(tool))…"
        }
    }

    /// The composer's Send: now, or once the taf is done when it's at work. A second
    /// message while one waits joins it. True once the message is taken: sent, queued,
    /// or kept in the thread as not sent. False leaves it with the composer.
    @discardableResult
    func submitChat(_ text: String, to tafID: UUID, files: [TafsCloud.AttachmentRef] = []) async -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // A message with files goes as a message of its own, never into the run at work
        // (kiff/tafs#209): the composer sends it once the taf is done.
        if !files.isEmpty { return await deliverChat(text, to: tafID, files: files) == .sent }
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
                    clearBusyUnsent(tafID)
                    return true
                } catch let e as TafsCloud.CloudError where e.status == 429 {
                    keepUnsent(text, tafID, why: Self.busyWords(c.name), busy: true)
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

    /// The stops at a cap of the run's own: it ends with no words for it.
    nonisolated static let capStops: Set<String> = ["cost_cap", "turn_cap"]

    /// What the chat says when its latest run stopped at a cap, never "…" (the 793 retest).
    nonisolated static func capWords(_ stop: String, taf: String) -> String? {
        switch stop {
        case "cost_cap": return "\(taf) stopped: this task would cost more than its limit for one task. Ask for something narrower, or someone who manages \(taf) can raise the limit."
        case "turn_cap": return "\(taf) stopped: this task took more steps than its limit for one task. Ask for something narrower."
        default: return nil
        }
    }

    /// What a run that failed part-way says (kiff/agents#249): that it stopped, and each
    /// call that went through before it did, from the run's own record, never its words,
    /// with the amount KIFF recorded for it once its Record line is read.
    func failedWords(_ tafID: UUID, taf: String) -> String {
        Self.failedWords(taf: taf, done: chatState(tafID).wentThrough.map { c in
            let line = c.invocation.flatMap(recordEntry(forCall:))
            return (toolTitle(c.tool) ?? KIFFMapping.toolWords(c.tool), c.resource, line?.amountText)
        })
    }

    nonisolated static func failedWords(taf: String, done: [(words: String, resource: String?, amount: String?)]) -> String {
        guard !done.isEmpty else { return "\(taf) stopped part-way, before it finished." }
        let calls = done.map { c in
            let on = c.resource.map { "\(c.words) on \($0)" } ?? c.words
            return c.amount.map { "\(on), \($0)" } ?? on
        }
        let these = calls.count == 1 ? "this went through" : "these went through"
        return "\(taf) stopped part-way, after \(these): \(calls.joined(separator: "; "))."
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
            // Stopped at once, as the owner sees it (the 755 retest): the answer being written
            // goes, and the chat is ready for the next message. The run itself ends at its
            // next step, and nothing it would have done after that is done.
            chatStates[tafID]?.ended = .stopped
            chatStates[tafID]?.stopped = session
            chatStreams[tafID]?.cancel()
            chatStreams[tafID] = nil
            chatStates[tafID]?.live = nil
        } catch {
            chatErrors[tafID] = "\(c.name) didn't stop: \(error.localizedDescription)"
        }
        await loadChat(tafID)
    }

    /// One thing in a taf's chat, in the order it happened (#64): a message, a call KIFF
    /// holds for the owner, a held call's outcome from the record, or the taf's words as
    /// they stream.
    enum ChatItem: Identifiable, Equatable {
        case message(TafsCloud.ChatMessage)
        case held(HeldCall)
        case outcome(RecordEntry)
        /// Words of the answer streaming now (kiff/tafs#206), by their place in it.
        case streaming(id: String, TafsCloud.ChatMessage)
        var id: String {
            switch self {
            case .message(let m): return "m|" + m.id
            // A held call and what became of it are one row, which changes in place
            // (kiff/tafs#206): the card, then "Refused by you · …".
            case .held(let h): return "k|" + AppModel.heldKey(h)
            case .outcome(let e): return "k|" + e.id.uuidString
            case .streaming(let id, _): return id
            }
        }
        var at: Date {
            switch self {
            case .message(let m): return m.at
            case .held(let h): return h.heldAt
            // When the owner answered it: after the taf's "waiting for your approval" and before
            // what it did next, so the chat reads in the order it happened (the 755 retest).
            // Without the answer's time, where its held card was: when the call was made.
            case .outcome(let e): return e.answer?.at ?? e.madeAt ?? e.at
            case .streaming(_, let m): return m.at
            }
        }
    }

    /// A held call's key in the chat: the id KIFF's record gives the call (its proposal's),
    /// which the owner's answer and KIFF's outcome are filed under too.
    nonisolated static func heldKey(_ h: HeldCall) -> String {
        ((h.proposalID ?? h.kiffID).map(KIFFMapping.recordID) ?? h.id).uuidString
    }

    /// A taf's chat as one timeline: its messages, the calls KIFF holds for the owner
    /// where they were held, and what became of earlier held calls (approved, refused)
    /// since the thread's first message, each as one line. A held call stays where the
    /// taf made it (kiff/tafs#206): after the words it wrote before it, never by clock
    /// alone. The answer streaming now comes last, in the order it came.
    func chatItems(_ tafID: UUID) -> [ChatItem] {
        let messages = chats[tafID] ?? []
        let held = heldCalls(for: tafID)
        var items = messages.map(ChatItem.message) + held.map(ChatItem.held)
        if let first = messages.first?.at {
            // What became of a call the owner approved shows too, done or not (kiff/tafs#115).
            items += entries(for: tafID).filter {
                ($0.kind == .approved || $0.kind == .refused || ($0.ownerApproved == true && [.notDone, .sending, .unknown].contains($0.kind))) && $0.at >= first
            }.map(ChatItem.outcome)
        }
        let state = chatState(tafID)
        let live = state.live
        // The held calls of the answer streaming now are placed in it, not here.
        let streamingKeys = Set((live?.parts ?? []).compactMap { part -> String? in
            guard case .held(let exception, _) = part else { return nil }
            return held.first { $0.kiffID == exception }.map(Self.heldKey) ?? state.heldKeys[exception]
        })
        if !streamingKeys.isEmpty {
            items.removeAll { if case .held(let h) = $0 { return streamingKeys.contains(Self.heldKey(h)) }; if case .outcome(let e) = $0 { return streamingKeys.contains(e.id.uuidString) }; return false }
        }
        var timeline = Self.placed(items.enumerated().sorted { a, b in
            a.element.at != b.element.at ? a.element.at < b.element.at : a.offset < b.offset
        }.map(\.element), anchors: state.anchors)
        if let live { timeline += streamingItems(live, held: held, tafID: tafID) }
        return Self.withheldUntilWords(Self.heldAfterItsWords(timeline), now: clock())
    }

    /// How long a held call's card waits in the chat for the taf's words that explain it (the
    /// 793 retest): KIFF's list brought the card at once, under the owner's message with no
    /// words, and the words came about 30 s later. Needs you and the alert show it at once.
    nonisolated static let heldWaitsForWords: TimeInterval = 45

    /// The chat without the held calls still waiting for the taf's words: one made after the
    /// owner's last message, while the taf has written nothing since that message, for at
    /// most `wait` after it was held. Then it shows, words or not.
    nonisolated static func withheldUntilWords(_ items: [ChatItem], now: Date, wait: TimeInterval = heldWaitsForWords) -> [ChatItem] {
        guard let owner = items.lastIndex(where: { if case .message(let m) = $0 { return m.from == .owner }; return false }) else { return items }
        let said = items[(owner + 1)...].contains { item in
            switch item {
            case .message(let m): return m.from == .taf && m.kind != .kickoff
            case .streaming: return true
            default: return false
            }
        }
        guard !said else { return items }
        return items.enumerated().filter { i, item in
            guard i > owner, case .held(let call) = item else { return true }
            return now.timeIntervalSince(call.heldAt) >= wait
        }.map(\.element)
    }

    /// A held call's card comes after the taf's words that ask for it (the 789 retest): the
    /// call is held before the taf writes "waiting for your approval", so by time alone the card
    /// sat right under the owner's message, above the words that explain it. It moves past
    /// that turn's words only, those in the session of the words right after it. An answered
    /// one already goes by when it was answered.
    nonisolated static func heldAfterItsWords(_ items: [ChatItem]) -> [ChatItem] {
        func isCard(_ item: ChatItem) -> Bool {
            switch item {
            case .held: return true
            case .outcome(let e): return e.answer?.at == nil
            default: return false
            }
        }
        /// The session of the taf's words, nil for anything else.
        func tafWords(_ item: ChatItem) -> String? {
            switch item {
            case .message(let m): return m.from == .taf && m.kind != .kickoff ? m.session : nil
            case .streaming(_, let m): return m.session
            default: return nil
            }
        }
        var out = items
        var i = 0
        while i < out.count {
            guard isCard(out[i]) else { i += 1; continue }
            // Only past the words of the turn right after it: another run's (a scheduled
            // one's) words, in another session, stay below the card.
            let session = i + 1 < out.count ? tafWords(out[i + 1]) : nil
            var j = i
            while j + 1 < out.count, let next = tafWords(out[j + 1]), next == session {
                out.swapAt(j, j + 1)
                j += 1
            }
            i = j + 1
        }
        return out
    }

    /// Moves each held call, and what became of it, to just after the item it was made
    /// after, when that item is in the chat; the rest keep their place by time.
    nonisolated static func placed(_ items: [ChatItem], anchors: [String: String]) -> [ChatItem] {
        guard !anchors.isEmpty else { return items }
        func key(_ item: ChatItem) -> String? {
            switch item {
            case .held(let h): return heldKey(h)
            // An answered call goes where it was answered, by time, not where it was held.
            case .outcome(let e): return e.answer?.at == nil ? e.id.uuidString : nil
            default: return nil
            }
        }
        let present = Set(items.map(\.id))
        let moving = items.filter { key($0).flatMap { anchors[$0] }.map(present.contains) ?? false }
        guard !moving.isEmpty else { return items }
        let movingIDs = Set(moving.map(\.id))
        var out: [ChatItem] = []
        for item in items where !movingIDs.contains(item.id) {
            out.append(item)
            // Each held call made after this item, in the order they were made.
            var after = item.id
            for m in moving where key(m).flatMap({ anchors[$0] }) == after {
                out.append(m)
                after = m.id
            }
        }
        return out
    }

    /// Whether words of the answer streaming now are in the thread already (kiff/tafs#214,
    /// item 9). A reload while it streams (the taf read what the owner wrote meanwhile, or
    /// Setup's thread after a tool call) brings the turns recorded so far, and the live copy
    /// would show the same words twice. The recorded message wins: it's the one that stays.
    /// Only the live session's messages from about when it started count (two minutes for
    /// the device's clock against tafs cloud's), so an earlier answer with the same words
    /// doesn't hide this one.
    nonisolated static func alreadyRecorded(_ words: String, session: String?, started: Date,
                                            in messages: [TafsCloud.ChatMessage]) -> Bool {
        let w = words.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let session, !w.isEmpty else { return false }
        let since = started.addingTimeInterval(-120)
        // A recorded turn can hold more than the live words: its message grows as it's written.
        return messages.contains {
            $0.from == .taf && $0.session == session && $0.at >= since
                && $0.text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix(w)
        }
    }

    /// The answer streaming now as chat items, in the order it came: its words up to each
    /// tool call, each held call's card where it was made, and the words being written.
    /// Words the thread already holds show there, once.
    private func streamingItems(_ live: LiveAnswer, held: [HeldCall], tafID: UUID) -> [ChatItem] {
        let run = live.run ?? "live"
        let recorded = chats[tafID] ?? []
        var out: [ChatItem] = []
        var n = 0
        func words(_ t: String) {
            defer { n += 1 }
            if Self.alreadyRecorded(t, session: live.session, started: live.started, in: recorded) { return }
            out.append(.streaming(id: "s|\(run)|\(n)", TafsCloud.ChatMessage(at: live.started, from: .taf, text: t, session: live.session ?? "live")))
        }
        for part in live.parts {
            switch part {
            case .text(let t): words(t)
            case .held(let exception, _):
                let call = held.first { $0.kiffID == exception }
                guard let key = call.map(Self.heldKey) ?? chatState(tafID).heldKeys[exception] else { continue }
                if let answered = entries(for: tafID).first(where: { $0.id.uuidString == key && $0.kind != .held }) {
                    out.append(.outcome(answered))
                } else if let call {
                    out.append(.held(call))
                }
            }
        }
        if !live.text.isEmpty { words(live.text) }
        return out
    }

    /// Once a streamed answer is recorded: notes where each of its held calls was made,
    /// after the recorded words that came before it, or after the owner's message when
    /// it came first (kiff/tafs#206). The answer's words are the session's taf messages
    /// after the owner's last, in order.
    private func anchorHeld(_ live: LiveAnswer, tafID: UUID) {
        guard live.parts.contains(where: { if case .held = $0 { return true }; return false }) else { return }
        let messages = (chats[tafID] ?? []).filter { $0.session == live.session }
        let lastOwner = messages.lastIndex { $0.from == .owner }
        let answer = messages[(lastOwner.map { $0 + 1 } ?? 0)...].filter { $0.from == .taf }
        var before: String? = lastOwner.map { "m|" + messages[$0].id }
        var said = 0
        let held = heldCalls(for: tafID)
        for part in live.parts {
            switch part {
            case .text:
                if said < answer.count { before = "m|" + answer[answer.startIndex + said].id }
                said += 1
            case .held(let exception, _):
                let key = held.first { $0.kiffID == exception }.map(Self.heldKey) ?? chatState(tafID).heldKeys[exception]
                guard let key, let before else { continue }
                chatStates[tafID]?.anchors[key] = before
            }
        }
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
        await resumeChat(tafID)
        var waitingFor: String?
        var polls = 0
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: chatPollInterval)
            if Task.isCancelled { return }
            // A stream shows what the taf does, and reloads the thread itself.
            if chatState(tafID).live != nil { continue }
            await resumeChat(tafID)
            if chatState(tafID).live != nil { continue }
            guard mode == .kiff, awaitingReply(tafID) || chatThinking(tafID),
                  let last = chats[tafID]?.last(where: { $0.from == .owner })?.id else { continue }
            if last != waitingFor { waitingFor = last; polls = 0 }
            guard polls < chatPollBudget else { continue }
            polls += 1
            await loadChat(tafID)
        }
    }

    /// Picks back up a run of the person's that tafs cloud says is at work while nothing here
    /// follows it (kiff/tafs#285): they left the chat, or the app, and came back. Its steps
    /// show as they happen and its end reloads the thread, as a message's stream does. Once
    /// per run: one whose stream can't open is left to the reloads.
    func resumeChat(_ tafID: UUID) async {
        let s = chatState(tafID)
        guard mode == .kiff, trialChats[tafID] == nil, s.live == nil, !chatSending.contains(tafID),
              let run = s.workingRun, let session = s.working, session != s.stopped,
              resumedRuns.insert(run).inserted, let c = cloudTaf(for: tafID) else { return }
        let gen = accountGeneration
        let asked = chats[tafID]?.last(where: { $0.from == .owner })
        let events: AsyncThrowingStream<TafsCloud.StreamEvent, Error>
        do {
            if let resumeOpener { events = try await resumeOpener(c.id, run) } else { events = try await cloud.resumeRun(c.id, run: run) }
        } catch {
            return
        }
        guard gen == accountGeneration, chatState(tafID).live == nil else { return }
        var live = LiveAnswer(started: asked?.at ?? clock())
        live.run = run
        live.session = session
        chatStates[tafID, default: ChatState()].live = live
        chatStreams[tafID] = Task { await self.followChatStream(events, tafID: tafID, text: asked?.text ?? "", gen: gen) }
    }

    /// The runs picked back up here, each once.
    private var resumedRuns: Set<String> = []
    /// Opens a run's stream again: tafs cloud's, unless a test gives its own.
    var resumeOpener: ((String, String) async throws -> AsyncThrowingStream<TafsCloud.StreamEvent, Error>)?

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
        if Screenshots.enabled, Screenshots.screen == "card", let i = tafs.firstIndex(where: { $0.name == "Refunds" }) {
            tafs[i].kiffCards = Screenshots.cards(tafs[i])
        }
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

    private func notifyHeld() { onHeldChanged?(held, tafs + otherAgents) }

    private func amountLine(_ p: ProposedAction, _ tail: String) -> String {
        if let amount = p.amount { return "\(amount.formatted), \(tail)" }
        return tail.prefix(1).uppercased() + tail.dropFirst()
    }

    private func log(_ tafID: UUID, _ kind: RecordEntry.Kind, _ title: String, _ detail: String, signature: String? = nil) {
        record.insert(RecordEntry(tafID: tafID, kind: kind, title: title, detail: detail, at: clock(), signature: signature), at: 0)
    }

    /// The owner's answers to KIFF's held calls, by their record id, until KIFF records the
    /// outcome (kiff/tafs#138): a refresh just after an answer still reads the call as held.
    private var decided: [UUID: RecordEntry] = [:]

    /// Records the owner's answer to a held call where the call was held, under the id KIFF's
    /// record gives it (its proposal's), so it folds the card in place, a refresh doesn't lose
    /// it, and once KIFF records the outcome the two are one line (kiff/tafs#138, item 51).
    private func decide(_ call: HeldCall, _ kind: RecordEntry.Kind, _ title: String, _ detail: String, signature: String?) {
        let id = (call.proposalID ?? call.kiffID).map(KIFFMapping.recordID) ?? UUID()
        var entry = RecordEntry(id: id, tafID: call.tafID, kind: kind, title: title, detail: detail, at: call.heldAt, signature: signature)
        if kind == .approved { entry.ownerApproved = true }
        record.removeAll { $0.id == id }
        record.insert(entry, at: 0)
        if call.kiffID != nil { decided[id] = entry }
    }

    /// What each Record line acted on (kiff/tafs#167): the call_refs of each taf with a line
    /// KIFF hashed a resource for, read from tafs cloud. Best effort: a taf whose calls can't
    /// be read keeps its lines without the payment.
    private func nameRecordLines(gen: Int) async {
        let tafIDs = Set(record.filter { $0.onHash != nil && $0.onValue == nil }.map(\.tafID))
        let cloudIDs = tafIDs.compactMap { cloudTaf(for: $0)?.id }
        guard !cloudIDs.isEmpty else { return }
        var refs: [String: String] = [:]
        for id in cloudIDs {
            guard let list = try? await cloud.calls(id), gen == accountGeneration else { continue }
            for r in list { refs[r.invocation] = r.value }
        }
        guard gen == accountGeneration, !refs.isEmpty else { return }
        record = KIFFMapping.naming(record, refs: refs)
    }

    /// KIFF's record with the owner's answers it doesn't have yet: an answered call KIFF
    /// still lists as held, or not yet at all, reads as answered. Once KIFF has the outcome,
    /// its record carries the call and the answer is let go (review on #160): a bridge until
    /// KIFF catches up, not a second record.
    func withDecisions(_ kiff: [RecordEntry]) -> [RecordEntry] {
        let (out, settled) = Self.merge(kiff, decided: decided)
        settled.forEach { decided[$0] = nil }
        return out
    }

    /// The record to show, and the answers KIFF now has an outcome for.
    static func merge(_ kiff: [RecordEntry], decided: [UUID: RecordEntry]) -> (record: [RecordEntry], settled: Set<UUID>) {
        var out = kiff
        var settled: Set<UUID> = []
        for (id, mine) in decided {
            if let i = out.firstIndex(where: { $0.id == id }) {
                if out[i].kind == .held {
                    out[i] = mine
                } else if out[i].kind == mine.kind {
                    // KIFF's outcome says what the owner decided: the line keeps the owner's
                    // words for it ("Refused by you · 08:56 · …"), so it doesn't change on
                    // screen (kiff/tafs#206).
                    out[i].title = mine.title
                } else {
                    settled.insert(id)
                }
            } else {
                out.append(mine)
            }
        }
        return (out.sorted { $0.at > $1.at }, settled)
    }

    // MARK: Connections (planque, #51)

    /// The account's planque grants: connections, and connections waiting for the owner.
    @Published private(set) var grants: [PlanqueClient.Grant] = []

    /// The vendor of each connected tool, by its name in KIFF: what a refusal names.
    private var toolVendors: [String: String] {
        Dictionary(grants.filter(\.connected).flatMap { g in (g.tools ?? []).map { ($0, g.vendor) } }, uniquingKeysWith: { first, _ in first })
    }
    /// Approvals refused for the request itself (kiff/tafs#138): "<card id>|<grant digest>".
    /// Sent again unchanged, they'd fail the same way: Setup has to change the request.
    @Published private(set) var refusedRequests: Set<String> = []
    /// Set by connect when its failure is the request's own, not the network's or a sign-in's.
    private var failureIsTheRequests = false
    /// The approval cards whose grant was read since the app first saw them (kiff/tafs#138):
    /// a grant read before a new approval of it says nothing about that approval.
    private var grantsReadFor: Set<String> = []
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

    /// A connection none of whose tools is on the live version's Card (kiff/tafs#138): left
    /// over after a version moved its Card to a fixed tool. The taf doesn't use it now, but an
    /// older version may, so going back could need it. Unknown while the Card isn't read.
    func leftOver(_ grant: PlanqueClient.Grant, of tafID: UUID) -> Bool {
        guard grant.connected, let rules = taf(tafID)?.card.rules else { return false }
        return Self.leftOver(tools: grant.request.tools.filter(\.tafs).map(\.action), onCard: rules.map(\.action))
    }

    /// Whether none of a connection's tools is among the Card's actions; never with no Card read.
    static func leftOver(tools: [String], onCard: [String]) -> Bool {
        guard !onCard.isEmpty else { return false }
        return !tools.contains { onCard.contains($0) }
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
        let cards = Set(setupThread.components.map(\.component).filter { $0.type == .approval }.map(\.id))
        guard let list = try? await planque.grants(signedBy: { try signer.signConfirmed($0) }), gen == accountGeneration else { return }
        grants = list
        grantsReadFor.formUnion(cards)
    }

    /// Sets this phone up with planque, once per account, behind one Face ID: planque gets
    /// the phone's key, and a KIFF key that connects tools and does nothing else, minted
    /// with a signed setup call and never kept on the phone. Done before a
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
        } catch KIFFClient.KIFFError.server(403, _, _) {
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
    /// `shown` is the grant's digest as the owner's screen showed it, and `shownDraft` the
    /// draft's version: Face ID signs exactly that (kiff/tafs#115). Terms that changed since
    /// are put on screen and not signed, and the owner approves again; nothing shown, nothing
    /// signed.
    func connect(grantID: String, shown: String?, draftID: String? = nil, shownDraft: String? = nil) async -> PlanqueClient.Grant? {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else {
            lastError = "Start from Connected tools on this \(ThisDevice.kind) first."
            return nil
        }
        quietStop = nil
        guard connecting == nil else {
            quietStop = "Another approval is going through. Approve this one once it's done."
            return nil
        }
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
            guard draft.version == shownDraft else {
                setupDrafts[draft.id] = draft
                termsChanged()
                return nil
            }
            do {
                _ = try await signer.sign(Data("tafs.cards.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8),
                                          reason: "Give \(draft.agentID) its Card")
            } catch {
                quietStop = Self.notConfirmed
                return nil
            }
            guard gen == accountGeneration else { return nil }
            kiff.setupSigner = { try signer.signConfirmed($0) }
            do {
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version)
            } catch {
                lastError = "KIFF didn't issue the taf's Cards: \(error.localizedDescription)"
                return nil
            }
            await keepKIFFAlerts(tenant: tenant, gen: gen)
            await keepCoachTools(tenant: tenant, gen: gen)
            return grant
        }
        guard ["pending", "approved", "signed_in"].contains(grant.status) else {
            failureIsTheRequests = grant.status == "failed"
            lastError = "This connection is \(grant.status.replacingOccurrences(of: "_", with: " "))\(grant.reason.map { ": \($0)" } ?? ".")"
            return nil
        }
        guard grant.digestMatches else {
            lastError = PlanqueClient.PlanqueError.digestMismatch.errorDescription
            return nil
        }
        guard grant.digest == shown else {
            if let i = grants.firstIndex(where: { $0.id == grant.id }) { grants[i] = grant } else { grants.append(grant) }
            termsChanged()
            return nil
        }

        do {
            _ = try await signer.sign(Data("tafs.connect.v1|\(tenant)|\(grant.id)|\(grant.digest)".utf8),
                                      reason: "Connect \(grant.vendor) to \(grant.request.agent)")
        } catch {
            quietStop = Self.notConfirmed
            return nil
        }
        guard gen == accountGeneration else { return nil }

        let connected: PlanqueClient.Grant
        do {
            let approved = try await planque.approve(grant)
            guard let signInURL = approved.signInURL else {
                // planque already holds the sign-in (contract 5): approving the tools Setup
                // chose from the vendor's list connected them, with no second sign-in.
                guard approved.status == "connected" else {
                    await refreshConnections()
                    lastError = "The connection came back \(approved.status) with no sign-in link. Nothing was connected."
                    return nil
                }
                // planque said connected: that's the answer (kiff/tafs#245). The list is read
                // again for the card, a few times while planque's own read catches up; the
                // approval counts as done either way, and is never asked for twice.
                for wait in [0, 0.5, 1.5] as [Double] {
                    if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
                    await refreshConnections()
                    guard gen == accountGeneration else { return nil }
                    if let done = grants.first(where: { $0.id == grant.id && $0.connected }) { return done }
                }
                return grant.marked(connected: true)
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
            failureIsTheRequests = Self.refusesTheRequest(error)
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
        /// Not sent only because the owner wrote too many too fast.
        var busy = false
    }
    @Published private(set) var setupUnsent: [UnsentMessage] = []
    /// What the owner is typing to Coach, not sent yet (kiff/tafs#245, item 3): kept here, not in the
    /// chat's view, so closing Coach mode, opening it again or switching tabs keeps it, as a
    /// taf's chat does. Only Send or the owner clears it; a new account starts empty.
    @Published var setupDraft = ""
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
    /// The same for each taf's chat (kiff/tafs#138, item 48): one rule for both chats.
    private var chatSent: [UUID: [TafsCloud.ChatMessage]] = [:]

    /// Where Setup's next message goes.
    private var setupTarget: TafsCloud.ChatTarget {
        setupFresh ? .new : setupChat.map { TafsCloud.ChatTarget(session: $0) } ?? .live
    }

    /// The taf a new Setup conversation improves (kiff/tafs#107), by its id in tafs cloud:
    /// its first message goes to tafs cloud's improve route, which puts the taf's context in
    /// front. Nil for any other conversation.
    @Published private(set) var setupImproving: TafsCloud.CloudTaf?

    // MARK: Coach mode (kiff/tafs#138)

    /// Coach mode is open: Setup's conversation, over whatever is on screen.
    @Published var coachOpen = false
    /// The taf whose page is on screen, the context Coach mode opens with; nil elsewhere.
    @Published var tafOnScreen: UUID?

    /// Opens Coach mode. Closing never ends a conversation, so one in progress reopens as
    /// it was; a new one, not yet written in, takes the taf on screen as its context.
    func openCoach() {
        if setupFresh && setupThread.messages.isEmpty && setupUnsent.isEmpty, let taf = tafOnScreen {
            improveTaf(taf)
        }
        coachOpen = true
    }

    /// A new conversation in Coach mode is on no taf (kiff/tafs#138, item 36): the owner
    /// asked for a new taf in one opened on a taf's page, and got that taf's next version.
    /// The taf on screen is offered in it, to improve with one tap.
    func newCoachConversation() {
        newSetupChat()
    }

    /// Starting a new taf is a conversation (kiff/tafs#138, item 59): Coach mode opens in a
    /// new one, on no taf. The old form, which turned on money tools with no limit, is gone.
    func startNewTaf() {
        newCoachConversation()
        coachOpen = true
    }

    /// A new Setup conversation on one taf, to improve it (kiff/tafs#107).
    func improveTaf(_ tafID: UUID) {
        newSetupChat()
        setupImproving = cloudTaf(for: tafID)
    }

    /// A new Setup conversation: an empty chat, and the next message starts it.
    func newSetupChat() {
        setupImproving = nil
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
        setupImproving = nil
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
        // A grant not read yet, or read before an approval of it appeared: Setup can ask to
        // approve more on a grant the app already holds (a read tool after the sign-in), and
        // the grant as read before doesn't answer that (kiff/tafs#138).
        if approvals.contains(where: { c in
            guard let g = c.grant else { return false }
            guard c.type == .approval, setupThread.answer(to: c.id) == nil else { return !grants.contains { $0.id == g } }
            // Not read yet, read before this card, or read as it was before the card (item 3
            // on kiff/tafs#236): read again, for the first minute of the card.
            return !grants.contains { $0.id == g } || !grantsReadFor.contains(c.id)
                || (!changedSince(c) && staleRetry(c))
        }) {
            await refreshConnections()
        }
        for c in approvals {
            guard let d = c.draft, setupDrafts[d]?.confirmed != true, let draft = try? await kiff.draft(id: d), gen == accountGeneration else { continue }
            setupDrafts[d] = draft
        }
        await sendQueuedIfIdle()
    }

    /// Starts connecting tools, once per account, behind one Face ID: planque gets this
    /// phone's key and a connector key, and Setup is started from a draft with no tools,
    /// whose key tafs cloud claims. Done before the first message, so every grant Setup
    /// asks for can be read and shown.
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
        await keepKIFFAlerts(tenant: tenant, gen: gen)
        await keepCoachTools(tenant: tenant, gen: gen)
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

    /// tafs cloud's thread, with what the owner sent that it doesn't have yet, by the rule
    /// both chats share (SentMessages).
    private func withSent(_ thread: SetupThread) -> SetupThread {
        let (messages, waiting) = SentMessages.merge(thread.messages, sent: setupSent, now: clock())
        setupSent = waiting
        var out = thread
        out.messages = messages
        return out
    }

    /// Sends the owner's words to Setup; the thread is then reloaded as it answers.
    @discardableResult
    func sendSetup(_ text: String) async -> Bool { await deliverSetup(text) == .sent }

    /// What a send did with a message: sent it, kept it in the thread as not sent (it
    /// was tried and failed), or didn't try (nothing to send, too long, or busy).
    enum Delivery { case sent, kept, notTried }

    private func deliverSetup(_ text: String, files: [TafsCloud.AttachmentRef] = []) async -> Delivery {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !files.isEmpty, text.count <= Self.chatLimit, let s = setupTaf, !setupSending else { return .notTried }
        // An improvement's first message carries no files: tafs cloud adds the taf's context to it.
        if !files.isEmpty, setupImproving != nil { return .notTried }
        setupSending = true
        defer { setupSending = false }
        let gen = accountGeneration
        setupEnded = nil
        // Shown at once, the way it was sent (kiff/tafs#82), until tafs cloud's copy takes its place.
        // Stamped no earlier than the thread's latest item: Setup's messages carry tafs
        // cloud's time, so a phone clock a little behind would place it above them.
        let mine = TafsCloud.ChatMessage(at: SentMessages.stamp(after: setupThread.items.map(\.at).max(), now: clock()),
                                         from: .owner, text: text, session: "pending", attachments: files)
        setupSent.append(mine)
        setupThread.messages.append(mine)
        do {
            // An improvement's first message goes through tafs cloud, which adds the taf's
            // context (kiff/tafs#107); the conversation goes on as any other.
            if let improving = setupImproving {
                // tafs cloud answers once it has read the taf's context from KIFF: Setup is
                // at work from the send, so the dots show under the message (kiff/tafs#138, 37).
                setupAwaiting = clock()
                let sent: TafsCloud.Sent
                do {
                    sent = try await cloud.improve(improving.id, text: text)
                } catch {
                    setupAwaiting = nil
                    throw error
                }
                guard gen == accountGeneration else { return .notTried }
                setupImproving = nil
                settleSetupChat(sent.session)
                await loadSetupThread()
                return .sent
            }
            // Streamed, so Setup's words show as it writes them (#60); sent plainly
            // when the stream can't open.
            if let events = try await openStream(s.id, text: text, to: setupTarget, files: files) {
                guard gen == accountGeneration else { return .notTried }
                setupAwaiting = clock()
                setupStreamWorks = true
                setupLiveBefore = Set(setupThread.items.map(\.id))
                setupAnsweredCard = SetupThread.Answer(text)?.id
                setupLive = LiveAnswer(started: clock())
                setupStream = Task { await self.followSetupStream(events, text: text, gen: gen) }
                return .sent
            }
            let sent = try await cloud.sendMessage(s.id, text: text, to: setupTarget, files: files)
            settleSetupChat(sent.session)
            guard gen == accountGeneration else { return .notTried }
            setupAwaiting = clock()
            await loadSetupThread()
            return .sent
        } catch {
            guard gen == accountGeneration else { return .notTried }
            setupSent.removeAll { $0 == mine }
            setupThread.messages.removeAll { $0 == mine }
            if !files.isEmpty {
                // Left with the composer, files and all.
                fileNotices[Self.setupFiles] = error.localizedDescription
                return .notTried
            }
            setupUnsent.append(UnsentMessage(text: text, why: error.localizedDescription, at: clock()))
            return .kept
        }
    }

    /// The composer's Send: now, or once Setup is done when it's at work (kiff/tafs#58).
    /// A second message while one waits joins it. True once the message is taken: sent,
    /// queued, or kept in the thread as not sent. False leaves it with the composer
    /// (nothing to send, too long to join the queue, or no Setup).
    @discardableResult
    func submitSetup(_ text: String, files: [TafsCloud.AttachmentRef] = []) async -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // A message with files goes as a message of its own (kiff/tafs#209): the composer sends it once Setup is done.
        if !files.isEmpty { return await deliverSetup(text, files: files) == .sent }
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
        guard !setupSending else { return }
        // A card's answer first: it's what Setup asked for (kiff/tafs#236, item 2).
        if let line = setupAnswersWaiting.first {
            setupAnswersWaiting.removeFirst()
            let id = SetupThread.Answer(line)?.id
            switch await deliverSetup(line) {
            case .sent:
                if let id { cardNotes[id] = nil }
            case .kept:
                if let id { answered.remove(id); cardNotes[id] = nil }
            case .notTried:
                setupAnswersWaiting.insert(line, at: 0)
            }
            return
        }
        guard !setupThinking, let queued = setupQueued else { return }
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
        /// A taf's answer so far, in the order it came (kiff/tafs#206): its words up to each
        /// tool call, and where KIFF held a call. `text` is the part being written now. The
        /// answer only grows: nothing shown is redrawn while it streams.
        var parts: [ReplyPart] = []
        /// When it started: the time its words are shown under.
        var started = Date()
        /// The summary so far of what it's thinking now (kiff/tafs#285); cleared once it writes.
        var thinking = ""
        /// The tools it called in this answer, in order, and how each went (kiff/tafs#285).
        var steps: [LiveStep] = []

        /// A tool call begins: shown as a step, in its words. Show draws in the chat itself.
        mutating func began(_ tool: String, words: String?, call: String?) {
            guard tool != "show" else { return }
            // A call the stream sends again (a run picked back up can replay its steps) keeps
            // the status it has: a finished step never goes back to running (the 859 test).
            if let call, steps.contains(where: { $0.call == call }) { return }
            steps.append(LiveStep(call: call, tool: tool, words: words))
        }

        /// A run went on after a pause: its answer so far is the thread's, with its work, and
        /// what follows starts afresh.
        mutating func newPart() {
            text = ""; parts = []; steps = []; activity = nil; thinking = ""
        }

        /// A call ended: the step it began, or the latest still running when the stream
        /// didn't say which (a held call).
        mutating func ended(_ call: String?, _ status: LiveStep.Status) {
            let i = call.flatMap { c in steps.lastIndex { $0.call == c } } ?? steps.lastIndex { $0.status == .running }
            if let i { steps[i].status = status }
        }
    }

    /// One tool call of an answer as it streams (kiff/tafs#285).
    struct LiveStep: Equatable {
        enum Status: Equatable {
            case running, done, error, held
            /// Refused back unmade: an act sent beside a read, so nothing reached KIFF or
            /// the tool. Not a failure; the taf reads first and makes it again.
            case notMade

            /// A `step` event's status: "done", "error" or "not_made".
            init(stream: String) {
                switch stream {
                case "error": self = .error
                case "not_made": self = .notMade
                default: self = .done
                }
            }
        }
        let call: String?
        let tool: String
        /// What it's doing, in the owner's words; nil shows the tool's own.
        let words: String?
        var status: Status = .running
    }

    /// A finished piece of a taf's streamed answer.
    enum ReplyPart: Equatable {
        case text(String)
        /// KIFF held the call made here: its decision's id and the call's.
        case held(exception: String, invocation: String)
    }
    @Published private(set) var setupLive: LiveAnswer?
    /// The thread's items when the answer streaming now was asked for: what's new since is
    /// this answer's, and its work block goes before the first of them, as in a taf's chat
    /// (the 870 test).
    private(set) var setupLiveBefore: Set<String> = []
    /// The card whose answer started the answer streaming now: the chat pins it to the top,
    /// as a typed message is (the 870 test).
    @Published private(set) var setupAnsweredCard: String?
    /// Whether a stream has opened here: answers then show as written, not revealed.
    @Published private(set) var setupStreamWorks = false
    private(set) var setupStream: Task<Void, Never>?

    /// Opens a chat's stream: tafs cloud's, unless a test gives its own.
    var streamOpener: ((String, String) async throws -> AsyncThrowingStream<TafsCloud.StreamEvent, Error>)?

    /// Opens the streamed send, for Setup's chat and a taf's. Nil when the stream couldn't
    /// open and the plain send should take the message (no network to it, tafs cloud
    /// without the route, or failing); a refusal (the flood guard, the role) throws, as
    /// the plain send's would.
    private func openStream(_ id: String, text: String, to target: TafsCloud.ChatTarget = .live,
                            files: [TafsCloud.AttachmentRef] = []) async throws -> AsyncThrowingStream<TafsCloud.StreamEvent, Error>? {
        do {
            if let streamOpener { return try await streamOpener(id, text) }
            return try await cloud.streamMessage(id, text: text, to: target, files: files)
        } catch let e as TafsCloud.CloudError where (400..<500).contains(e.status) && e.status != 404 && e.status != 408 {
            throw e
        } catch {
            return nil
        }
    }

    /// Setup's words being written now, unless the thread holds them already (kiff/tafs#214,
    /// item 9): `loadSetupThread` sets the thread before it reads the grants and drafts its
    /// cards need, and until the stream clears its bubble the same words would show twice.
    var setupLiveText: String {
        guard let live = setupLive else { return "" }
        return Self.alreadyRecorded(live.text, session: live.session, started: live.started, in: setupThread.messages) ? "" : live.text
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
                    setupLive?.thinking = ""
                case .thinking(let t):
                    setupLive?.thinking += t
                case .activity(let tool, let step, let call):
                    // The step shows at once; the thread then picks up the words written so far.
                    let words = step ?? Self.activityWords(tool)
                    setupLive?.activity = words
                    setupLive?.began(tool, words: words, call: call)
                    await loadSetupThread()
                    setupLive?.text = ""
                case .step(let call, let status):
                    setupLive?.ended(call, LiveStep.Status(stream: status))
                case .component, .said:
                    await loadSetupThread()
                    setupLive?.text = ""
                case .part:
                    // It went on after a pause: what came before is the thread's, with its work,
                    // and the steps start again (the 870 test, as in a taf's chat).
                    await loadSetupThread()
                    setupLive?.newPart()
                    setupLiveBefore = Set(setupThread.items.map(\.id))
                case .end(let status, _, _):
                    ended = status
                case .held:
                    setupLive?.ended(nil, .held)
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
    nonisolated static func activityWords(_ tool: String) -> String? {
        // By its own name, past any prefix the run records it under ("kiff__kiff_draft").
        switch tool.components(separatedBy: "__").last ?? tool {
        case "planque": return "Working on the connection…"
        case "kiff_draft": return "Preparing the Card…"
        case "kiff_card", "kiff_pending": return "Checking KIFF…"
        case "tafs_state": return "Reading your tafs…"
        case "taf_run": return "Reading what a taf did…"
        case "taf_task": return "Handing the taf its task…"
        case "run_stop": return "Stopping a run…"
        case "taf_pause": return "Pausing or resuming a taf…"
        case "version_try": return "Trying the new version…"
        case "version_install": return "Installing the new version…"
        case "memory": return "Reading its notes…"
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

    /// Connected with its Cards confirmed, or connected first and listed (contract 5), as
    /// planque says after this approval appeared; or the owner's answer to it says so.
    func approvalFinished(_ c: SetupComponent) -> Bool {
        let grant = grants.first { $0.id == c.grant }
        let readSince = grantsReadFor.contains(c.id)
        // planque failed the grant after the owner approved (at connect, or later): the
        // card says so with planque's reason, whatever the answer said.
        if readSince, let grant, grant.status == "failed" || grant.status == "disconnected" { return false }
        if let a = setupThread.answer(to: c.id), !a.failed, a.text.hasPrefix("approved") { return true }
        guard readSince else { return false }
        // Read after the card appeared, but changed before it: Setup can show an approval in
        // the same turn it changes the grant, and a read between the two still finds the
        // grant as it was (kiff/tafs#236, item 3). Its state then says nothing of this card.
        guard changedSince(c) else { return false }
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

    /// Whether a card whose grant reads as before it is young enough to read again: Setup
    /// changes the grant within moments of showing it, so a minute covers it.
    private func staleRetry(_ c: SetupComponent) -> Bool {
        guard let shown = setupThread.components.first(where: { $0.id == c.id }) else { return false }
        return clock().timeIntervalSince(shown.firstAt) < 60
    }

    /// Whether planque changed the card's grant after the card first appeared. Asked only of a
    /// later approval of a grant the thread asked about before: the first one's grant is new
    /// with it, and Setup changes a grant it shows again (kiff/tafs#236, item 3).
    func changedSince(_ c: SetupComponent) -> Bool {
        guard let g = c.grant, let grant = grants.first(where: { $0.id == g }),
              let shown = setupThread.components.first(where: { $0.id == c.id }) else { return false }
        let askedBefore = setupThread.components.contains {
            $0.id != c.id && $0.component.type == .approval && $0.component.grant == g && $0.firstAt < shown.firstAt
        }
        guard askedBefore else { return true }
        // No events (an older planque): its state is all there is to go by.
        guard let changed = grant.changedAt else { return true }
        // planque writes seconds and more; tafs cloud's time is the same clock to the second.
        return changed >= shown.firstAt.addingTimeInterval(-1)
    }

    private func wroteSince(_ at: Date) -> Bool {
        setupThread.messages.contains { $0.from == .owner && $0.at > at }
    }

    /// The owner's answer to a component, as contract 1 says: "[<type> <id>] <answer>".
    /// An earlier answer to it that wasn't sent goes: one answer per card.
    func answer(_ component: SetupComponent, _ text: String) async {
        setupUnsent.removeAll { SetupThread.Answer($0.text)?.id == component.id }
        answered.insert(component.id)
        cardNotes[component.id] = nil
        let line = "[\(component.type.rawValue) \(component.id)] \(text)"
        // A message on its way: the answer waits for it, and the card says so (kiff/tafs#236,
        // item 2). It used to be dropped without a word.
        if setupSending {
            setupAnswersWaiting.removeAll { SetupThread.Answer($0)?.id == component.id }
            setupAnswersWaiting.append(line)
            cardNotes[component.id] = "Sent once your last message has gone."
            return
        }
        switch await deliverSetup(line) {
        case .sent: break
        // Kept in the thread as not sent, with why and Retry.
        case .kept: answered.remove(component.id)
        case .notTried:
            answered.remove(component.id)
            cardNotes[component.id] = "Your answer couldn't be sent. Tap it again."
        }
    }

    /// Answers to cards given while Setup was at work, sent in order once it's done.
    @Published private(set) var setupAnswersWaiting: [String] = []

    /// Approve on an approval (contract 4, one Face ID): the vendor's connection, then the
    /// taf's Cards when it names a draft. The answer says what happened, never more.
    /// Approves what the card showed: the grant's digest and the draft's version as rendered.
    func approve(_ component: SetupComponent, shown: String?, shownDraft: String?) async {
        guard let grant = component.grant else { return }
        lastError = nil
        shownTermsChanged = false
        failureIsTheRequests = false
        cardNotes[component.id] = nil
        // Connected, or connected first and listed (contract 5): Setup reads what's next.
        let done = await connect(grantID: grant, shown: shown, draftID: component.draft, shownDraft: shownDraft)
        // Changed terms aren't a failure to tell Setup: the owner reads them and approves again.
        if shownTermsChanged {
            cardNotes[component.id] = lastError
            lastError = nil
            return
        }
        if let done, done.connected || done.listed {
            if let e = lastError {
                await answer(component, "failed: \(e)")
            } else {
                await answer(component, vendorSignedIn ? "approved, signed in" : "approved")
            }
        } else if let e = lastError {
            if failureIsTheRequests, let shown {
                // The request itself was refused (kiff/tafs#138): Setup changes it before
                // asking again, and the card doesn't offer to send it unchanged.
                refusedRequests.insert("\(component.id)|\(shown)")
                await answer(component, "failed: \(e)")
            } else {
                await answer(component, "failed: \(e)")
            }
        } else if done == nil {
            // Nothing happened that Setup should hear of: the card says why (kiff/tafs#236).
            cardNotes[component.id] = quietStop ?? "Nothing was approved. Approve again."
        }
        await loadSetupThread()
    }

    /// Whether this approval, as it reads now, was refused for the request itself: sent
    /// again unchanged it fails the same way (kiff/tafs#138). A grant planque failed can't
    /// be approved again either.
    func requestRefused(_ c: SetupComponent) -> Bool {
        guard let g = c.grant, let grant = grants.first(where: { $0.id == g }) else { return false }
        return grant.status == "failed" || refusedRequests.contains("\(c.id)|\(grant.digest)")
    }

    /// A refusal of the request, not of the moment: planque or KIFF answered 4xx (a tool
    /// name already in use, a field it won't take). Not a sign-in that lapsed (401, 403),
    /// a timeout or too many calls.
    static func refusesTheRequest(_ error: Error) -> Bool {
        guard case PlanqueClient.PlanqueError.server(let status, _) = error else { return false }
        return (400..<500).contains(status) && ![401, 403, 408, 429].contains(status)
    }

    /// Sign in again, for a grant whose sign-in was left: a new approval, one Face ID.
    func signInAgain(_ component: SetupComponent) async {
        guard let grant = component.grant else { return }
        lastError = nil
        shownTermsChanged = false
        // The sign-in card shows no terms: it approves the grant as the app last showed it.
        cardNotes[component.id] = nil
        let done = await connect(grantID: grant, shown: grants.first(where: { $0.id == grant })?.digest)
        if shownTermsChanged {
            cardNotes[component.id] = lastError
            lastError = nil
            return
        }
        if let done, done.connected || done.listed {
            await answer(component, "signed in")
        } else if let e = lastError {
            await answer(component, "failed: \(e)")
        } else if done == nil {
            cardNotes[component.id] = quietStop ?? "Nothing was signed in. Try again."
        }
    }

    /// The taf a result hands over to, when this phone shows it.
    func taf(forAgent agent: String) -> UUID? { holderIDs[agent] }

    /// Whether the last connect opened the vendor's sign-in: the approval's answer says so.
    private var vendorSignedIn = false

    /// Why the last connect stopped before anything happened that Setup should hear of: the
    /// owner reads it on the card, which offers Approve again (kiff/tafs#236, item 1).
    private var quietStop: String?
    static var notConfirmed: String { "\(Biometry.name) didn't confirm, so nothing was approved. Approve again when you're ready." }

    /// A line on a card whose tap ended without reaching Setup: why, in the owner's words
    /// (kiff/tafs#236). A tap always shows something: the vendor's sign-in, Setup's answer, or this.
    @Published private(set) var cardNotes: [String: String] = [:]

    /// Whether the last approval found terms other than those shown (kiff/tafs#115).
    @Published private(set) var shownTermsChanged = false

    /// The terms changed between the screen and the tap: the new ones are on screen now,
    /// nothing was signed, and the owner approves again.
    private func termsChanged() {
        shownTermsChanged = true
        lastError = "What you're asked to approve changed since it was shown. Read it again, then approve."
    }

    /// Results whose taf is being started, or failed to start (with why).
    @Published private(set) var startingTafs: Set<String> = []
    @Published private(set) var tafStartErrors: [String: String] = [:]

    /// Setup's hand-over starts its taf once, wherever the owner is (kiff/tafs#96): not
    /// only while the result card is on screen, so the taf reaches the list either way. One
    /// that didn't start waits for the card's Try again; one whose failure Setup was told of
    /// (kiff/tafs#183) is Setup's to hand over again.
    private func startHandedOverTafs() {
        for c in setupThread.components.map(\.component) where c.type == .result {
            // An improvement makes the taf's next version, never a new taf (kiff/tafs#107).
            if c.taf?.improves != nil {
                let key = improvementKey(c)
                if improvedVersions[key] == nil, !makingVersions.contains(key), improveErrors[key] == nil {
                    Task { await makeImprovedVersion(c) }
                }
                continue
            }
            guard let taf = c.taf, taf.job != nil, taf.draft != nil else { continue }
            if cloudTafs[taf.agent] != nil || startingTafs.contains(c.id) || tafStartErrors[c.id] != nil { continue }
            if startFailureTold(c) { continue }
            // A result Setup handed over again after a failed start (kiff/tafs#183) starts by
            // itself once per agent; one that fails again waits for the owner's Try again, so a
            // refusal that isn't about the name can't loop with no one in it.
            let again = setupThread.components.contains {
                $0.component.id != c.id && $0.component.taf?.agent == taf.agent && startFailureTold($0.component)
            }
            if again {
                if restartedAgents.contains(taf.agent) {
                    tafStartErrors[c.id] = "\(taf.name) isn't started yet: its start failed before. Try again when you're ready."
                    continue
                }
                restartedAgents.insert(taf.agent)
            }
            Task { await startSetupTaf(c) }
        }
    }

    /// The version each improvement result made, by `improvementKey`, and those being made
    /// or that failed (with why).
    @Published private(set) var improvedVersions: [String: Int] = [:]
    @Published private(set) var makingVersions: Set<String> = []
    @Published private(set) var improveErrors: [String: String] = [:]

    /// An improvement result, by its card and what it carries (its draft, else its note). The
    /// card's id is Setup's to pick and comes back in another conversation ("result"): keyed by
    /// the id alone, a new conversation's card showed the version an earlier one made, and
    /// its own was never made (the 755 retest: "See version 2" when 2 was already live).
    func improvementKey(_ component: SetupComponent) -> String {
        let taf = component.taf
        let what = taf?.draft ?? SHA256.hash(data: Data((taf?.note ?? "").utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
        return "\(component.id)|\(what)"
    }

    /// Makes the version an improvement result hands over, once: tafs cloud builds it from
    /// Setup's words and keeps one per result, so a repeat gives the same version. Nothing
    /// goes live: the owner tries it and switches from the taf's Versions.
    func makeImprovedVersion(_ component: SetupComponent) async {
        guard mode == .kiff, let taf = component.taf, let named = taf.improves else { return }
        let key = improvementKey(component)
        // The taf it improves, by its tafs id, or by its agent's when Coach changed a taf it
        // set up or found in this conversation (kiff/tafs#247).
        let improves = cloudTafs.values.contains(where: { $0.id == named }) ? named : (cloudTafs[named]?.id ?? cloudTafs[taf.agent]?.id ?? named)
        guard !makingVersions.contains(key) else { return }
        makingVersions.insert(key)
        defer { makingVersions.remove(key) }
        improveErrors[key] = nil
        let gen = accountGeneration
        do {
            let n = try await cloud.createVersion(improves, note: taf.note ?? "", notes: taf.notes, draft: taf.draft, from: key, agent: taf.agent, tryWith: taf.`try`)
            guard gen == accountGeneration else { return }
            improvedVersions[key] = n
            if let tafID = self.taf(forAgent: taf.agent) { await loadVersions(tafID) }
        } catch {
            guard gen == accountGeneration else { return }
            improveErrors[key] = "\(taf.name)'s new version isn't ready: \(error.localizedDescription)"
        }
    }

    /// Starts the taf a result hands over to, once: tafs cloud stores it, and kiff/agents
    /// claims its key from Setup's draft. Then it shows with the account's other tafs.
    func startSetupTaf(_ component: SetupComponent) async {
        guard mode == .kiff, let taf = component.taf, taf.improves == nil, let job = taf.job, let draft = taf.draft else { return }
        if cloudTafs[taf.agent] != nil || startingTafs.contains(component.id) { return }
        startingTafs.insert(component.id)
        defer { startingTafs.remove(component.id) }
        tafStartErrors[component.id] = nil
        let gen = accountGeneration
        var nameTaken = false
        do {
            _ = try await cloud.startFromSetup(name: taf.name, job: job, draftID: draft, unscheduled: taf.unscheduled == true)
        } catch let e as TafsCloud.CloudError where e.status == 409 && e.message.contains("already have a taf named") {
            // Started before, by this phone or another, unless another taf has the name.
            nameTaken = true
        } catch {
            guard gen == accountGeneration else { return }
            tafStartErrors[component.id] = "\(taf.name) isn't started yet: \(error.localizedDescription)"
            // tafs cloud refused it (a 4xx): trying the same again fails the same way, so
            // Setup hears of it (kiff/tafs#183). A server error or a lost connection may pass:
            // the card's Try again stays, and Setup isn't told.
            // Not a 401: signing in again fixes it, as time does a 429.
            if let e = error as? TafsCloud.CloudError, (400..<500).contains(e.status), ![401, 429].contains(e.status) {
                await tellSetupStartFailed(component, why: e.message)
            }
            return
        }
        guard gen == accountGeneration else { return }
        await refresh()
        // A 409 can also mean another taf has the name: say so rather than wait forever.
        if nameTaken, cloudTafs[taf.agent] == nil {
            tafStartErrors[component.id] = "\(taf.name) isn't started yet: the account already has a taf by that name."
            await tellSetupStartFailed(component, why: "the account already has a taf named \(taf.name)")
        }
    }

    /// The results this phone told Setup didn't start, before the thread shows the answer.
    @Published private(set) var toldStartFailures: Set<String> = []
    /// The agents whose re-handed-over result already started by itself once (kiff/tafs#183).
    private var restartedAgents: Set<String> = []
    /// The results whose failure is being sent to Setup now, so it's sent once.
    private var tellingStartFailures: Set<String> = []

    /// Whether Setup was told this hand-over failed (kiff/tafs#183): it then shows a new
    /// result, and the card waits for it rather than offering the same start again.
    func startFailureTold(_ component: SetupComponent) -> Bool {
        setupThread.answer(to: component.id)?.failed == true || toldStartFailures.contains(component.id)
    }

    /// Tells Setup, once, that the taf its result handed over wasn't created and why
    /// (kiff/tafs#183), as the answer to that result: its draft is confirmed and unclaimed,
    /// so Setup hands it over again, under a new name when the name was taken.
    private func tellSetupStartFailed(_ component: SetupComponent, why: String) async {
        guard !startFailureTold(component), !tellingStartFailures.contains(component.id) else { return }
        tellingStartFailures.insert(component.id)
        defer { tellingStartFailures.remove(component.id) }
        await answer(component, "failed: the taf wasn't created: \(why)")
        // Told only once it went: a send that failed leaves Try again on the card, and the
        // next failure tries to tell Setup again.
        if answered.contains(component.id) { toldStartFailures.insert(component.id) }
    }
}

/// The app's tabs: the tafs, the calls waiting on the owner, and the record.
enum Tab: Hashable { case tafs, needsYou, record }

/// CI's screenshot run: `-screenshots YES -tab needs-you` opens the sample on a tab,
/// without asking for notifications.
extension AppModel {
    /// The words the owner is likely to say, so dictation spells them right (kiff/tafs#208):
    /// the account's taf names, the connected tools' names, the people it knows of, the packs
    /// and plans. Read each time dictation starts, so it follows the account's changes.
    var dictationHints: [String] {
        var words = ["tafs", "Coach", "KIFF", "Solo", "Team", "Company", ownerName]
        for taf in tafs {
            words.append(taf.name)
            words.append(contentsOf: taf.tools)
        }
        words.append(contentsOf: grants.map { KIFFMapping.vendorWords($0.vendor) })
        words.append(contentsOf: usage?.store?.packs.map(\.name) ?? [])
        return Dictation.cleaned(words)
    }
}

enum Screenshots {
    static var enabled: Bool { UserDefaults.standard.bool(forKey: "screenshots") }
    /// `-screen taf` opens a taf's page, `-screen card` its Cards; `-screen new` the new-taf flow; `-screen solo` and
    /// `-screen add-tasks` the purchase screens, for the products in App Store Connect.
    static var screen: String? { UserDefaults.standard.string(forKey: "screen") }
    /// `-pack rayo`: the add-tasks screen with that pack alone, one screenshot a product.
    static var pack: String? { UserDefaults.standard.string(forKey: "pack") }
    /// `-narrow YES`: tafs laid out at Slide Over's width on an iPad, compact (kiff/tafs#210).
    static var narrow: Bool { UserDefaults.standard.bool(forKey: "narrow") }
    /// `-sampleReplySeconds 20`: the sample taf thinks, works and writes slowly enough for a UI
    /// test to see each part on a slow simulator.
    static var sampleReplySeconds: Double? {
        let s = UserDefaults.standard.double(forKey: "sampleReplySeconds")
        return enabled && s > 0 ? s : nil
    }
    /// `-listSettlesOnReturn YES`: the list opens on its first taf, as for a person, and each
    /// time the app comes back its last taf moves to the top, as when another was used
    /// meanwhile: for a UI test that a pick stays open when the list settles (the 901 test).
    static var listSettlesOnReturn: Bool { enabled && UserDefaults.standard.bool(forKey: "listSettlesOnReturn") }

    /// The store as KIFF lists it and the App Store prices it (Tafs.storekit's), for the
    /// purchase screens' screenshots.
    static let packs: [(id: String, name: String, tasks: Int, price: String)] = [
        ("pico", "Pico", 12, "€4.99"), ("vonk", "Vonk", 30, "€9.99"), ("rayo", "Rayo", 70, "€19.99"),
        ("forte", "Forte", 200, "€49.99"), ("maxi", "Maxi", 450, "€99.99"),
    ]
    /// `-screen card`: the Refunds taf's Cards as KIFF would list them (kiff/tafs#211), a live
    /// one with its total, a read-only one with its calls, and a test one.
    static func cards(_ taf: Taf) -> [TafCard] {
        let refunds = TafCard(id: "sample-refunds", title: "Refunds · Stripe", rules: Array(taf.card.rules.prefix(2)),
                              total: CardTotal(limit: .usd(2_000), used: .usd(480), window: .day))
        let reads = TafCard(id: "sample-reads", title: "Read Stripe",
                            rules: [CardRule(action: "list_charges", label: "List charges", countPerWindow: 200),
                                    CardRule(action: "get_charge", label: "Look up a charge")],
                            calls: CardTotal(limit: 200, used: 20, window: .day))
        var test = refunds
        test.id = "sample-test"
        test.title = "Refunds · Stripe test"
        test.environment = "test"
        test.live = false
        return [refunds, reads, test]
    }

    /// Whether this run's screenshot is a purchase screen.
    static var purchases: Bool { enabled && (screen == "solo" || screen == "add-tasks") }
    static let soloProduct = "bot.tafs.solo.monthly"
    static var store: KIFFClient.Usage.Store {
        let shown = packs.filter { pack == nil || $0.id == pack }
        return .init(appAccountToken: UUID(), soloProduct: soloProduct, packs: shown.map {
            .init(id: $0.id, name: $0.name, line: "\($0.name) · \($0.tasks) tasks", tasks: $0.tasks, months: 12, appleProduct: "bot.tafs.pack.\($0.id)")
        })
    }
    static var products: [StoreProduct] {
        packs.map { StoreProduct(id: "bot.tafs.pack.\($0.id)", displayName: $0.name, displayPrice: $0.price) } +
            [StoreProduct(id: soloProduct, displayName: "Solo", displayPrice: "€10.99", subscription: .init(period: "month", freeTrial: "1 week"))]
    }
    static var tab: Tab {
        switch UserDefaults.standard.string(forKey: "tab") {
        case "needs-you": return .needsYou
        case "record": return .record
        default: return .tafs
        }
    }
}
