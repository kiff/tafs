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
    @Published private(set) var phoneAlerts: TafsCloud.PhoneAlerts?
    @Published private(set) var phoneAlertsBusy = false
    @Published private(set) var phoneAlertsError: String?
    @Published private(set) var timeZones: [TafsCloud.TimeZoneChoice] = []
    private var timeZoneQuery = ""
    @Published private(set) var tafSetups: [UUID: TafsCloud.SetupView] = [:]
    @Published private(set) var tafSetupErrors: [UUID: String] = [:]
    @Published private(set) var activityPages: [String: SourcePages<TafsCloud.ActivityPage>] = [:]
    @Published private(set) var runPages: [String: SourcePages<TafsCloud.RunsPage>] = [:]
    /// The Record's search box words, as tafs cloud says them; nil until read.
    @Published private(set) var recordHint: String?
    /// The Record searched across the person's tafs; nil while nothing is searched.
    @Published private(set) var recordSearch: RecordSearch?
    /// The case open from a search: every call about the same thing, oldest first.
    @Published private(set) var recordCase: TafsCloud.RecordCase?
    /// Why the case couldn't be read, in words.
    @Published private(set) var recordCaseError: String?
    @Published private(set) var cardUnitExceptions: [TafsCloud.CardUnitException] = []
    /// The steps tafs handed the person, oldest first, as tafs cloud lists them on Home (docs/api.md
    /// › Your step), less those this phone just answered.
    @Published private(set) var yourSteps: [TafsCloud.YourStep] = []
    /// Coach's chats waiting on the owner, as Home lists them (docs/api.md › Coach waiting).
    @Published private(set) var coachWaitingListed: [TafsCloud.CoachWaiting] = []
    /// Coach's waiting chats as Needs you draws them: Home's, less the chat open in Coach now (the
    /// person is in it), and less those being set aside, so a swipe that took never springs back
    /// while tafs cloud answers (as Android 94 does). Neither shows as a row nor counts; a refusal
    /// brings the row back.
    var coachWaiting: [TafsCloud.CoachWaiting] {
        let open = coachChatOnScreen
        return coachWaitingListed.filter { $0.session != open && !settingAside.contains($0.session) }
    }
    /// The Coach chat open on screen: Coach is open, on a chat that has a session; nil otherwise.
    var coachChatOnScreen: String? {
        guard coachOpen else { return nil }
        return setupChat ?? (setupFresh ? nil : setupThread.session)
    }
    /// Of Home's waiting chats, those Needs you leaves out: open on screen, or being set aside.
    private var coachWaitingHidden: Int { coachWaitingListed.count - coachWaiting.count }
    /// Home's "Set all aside" (`coach_waiting_clear`), as tafs cloud sent it; nil when it sent none.
    @Published private(set) var coachWaitingClear: TafsCloud.CoachWaitingClear?
    /// The Coach chats being set aside now: one call per chat at a time, so a swipe that fires
    /// again, or a second tap, sends nothing until tafs cloud answers (Android 86 sent 11 in 3 s).
    @Published private(set) var settingAside: Set<String> = []
    /// The Coach chats set aside on this phone: Coach never opens one by itself again, so a new
    /// job never goes on in an ended session. Kept in memory only (docs/data.md).
    private var setAsideChats: Set<String> = []
    /// Steps answered here and accepted by KIFF that Home may still list until the run hears the
    /// answer: kept out of Needs you and its count, and forgotten once Home stops listing them.
    private var answeredSteps: Set<String> = []
    /// Steps whose answer is being signed or sent, so a second tap sends nothing.
    @Published private(set) var answeringSteps: Set<String> = []
    /// A step opened from its notification: Needs you brings it into view and clears this.
    @Published var requestedStep: String?
    /// Home's count of what waits for the person, from tafs cloud (kiff/tafs#469 item 1).
    @Published private(set) var homeNeedsYou: TafsCloud.NeedsYou?
    /// Home's line as tafs cloud counted it, in its words; nil when it sent none.
    @Published private(set) var homeHeadline: String?
    /// Of tafs cloud's count, the steps answered here that it still counts.
    private var answeredStillCounted = 0

    /// What waits for the person: tafs cloud's count, or, when it sent none, the app's own
    /// count of held calls, currency requests, steps and "Did this happen?" calls.
    var needsYouCount: Int {
        homeNeedsYou.map { max(0, $0.count - answeredStillCounted - coachWaitingHidden) }
            ?? held.count + cardUnitExceptions.count + yourSteps.count + unproven.count + coachWaiting.count
    }

    /// Home's line under the working count, in tafs cloud's words when it sent them.
    var needsYouWords: String {
        if answeredStillCounted == 0, coachWaitingHidden == 0, let words = homeNeedsYou?.words { return words }
        let n = needsYouCount
        return n == 0 ? Words.NeedsYou.nothing : Words.NeedsYou.count(count: n)
    }
    @Published private(set) var dismissingCardUnits: Set<String> = []
    private var presentationGeneration = 0

    private var alertsEnabledKey: String { "alerts.enabled|\(kiffTenant ?? "")" }
    private var alertKindsKey: String { "alerts.kinds|\(kiffTenant ?? "")" }
    private var savedAlertKinds: [String: Bool]? { UserDefaults.standard.dictionary(forKey: alertKindsKey) as? [String: Bool] }

    func loadPhoneAlerts() async {
        guard mode == .kiff, let token = pushToken else { return }
        let gen = accountGeneration
        do {
            let choices = try await cloud.phoneAlerts(token)
            guard gen == accountGeneration else { return }
            phoneAlerts = choices; alertsReady = choices.enabled; phoneAlertsError = nil
        } catch { if gen == accountGeneration { phoneAlertsError = error.localizedDescription } }
    }
    func turnOffAlerts() async {
        guard !phoneAlertsBusy, let token = pushToken else { return }
        let gen = accountGeneration
        phoneAlertsBusy = true
        defer { if gen == accountGeneration { phoneAlertsBusy = false } }
        do {
            try await cloud.unregisterPhone(token, environment: Self.pushEnvironment)
            guard gen == accountGeneration else { return }
            UserDefaults.standard.set(false, forKey: alertsEnabledKey)
            alertsReady = false
            phoneAlerts = nil
            await loadPhoneAlerts()
        } catch { if gen == accountGeneration { phoneAlertsError = error.localizedDescription } }
    }
    func setAlertKind(_ id: String, enabled: Bool) async {
        guard !phoneAlertsBusy, phoneAlerts?.enabled == true, let token = pushToken, let choices = phoneAlerts?.kinds, choices.contains(where: { $0.id == id }) else { return }
        let gen = accountGeneration
        phoneAlertsBusy = true
        defer { if gen == accountGeneration { phoneAlertsBusy = false } }
        var kinds = Dictionary(uniqueKeysWithValues: choices.map { ($0.id, $0.enabled) })
        kinds[id] = enabled
        do {
            _ = try await cloud.registerPush(deviceToken: token, environment: Self.pushEnvironment, sound: alertSound, kinds: kinds)
            guard gen == accountGeneration else { return }
            UserDefaults.standard.set(kinds, forKey: alertKindsKey)
            await loadPhoneAlerts()
        } catch { if gen == accountGeneration { phoneAlertsError = error.localizedDescription } }
    }
    func loadTimeZones(_ query: String) async {
        timeZoneQuery = query
        let gen = accountGeneration
        do {
            let choices = try await cloud.timeZones(query)
            if gen == accountGeneration && timeZoneQuery == query { timeZones = choices }
        } catch { if gen == accountGeneration { timeZoneError = error.localizedDescription } }
    }
    func loadTafSetup(_ id: UUID) async {
        guard let taf = cloudTaf(for: id) else { return }
        let gen = accountGeneration, presentation = presentationGeneration
        do {
            let signer = self.signer
            planque.tenant = kiffTenant
            let proof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) })
            let view = try await cloud.setupView(taf.id, proof: proof)
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            tafSetups[id] = view; tafSetupErrors[id] = nil
        } catch { if gen == accountGeneration, presentation == presentationGeneration { tafSetupErrors[id] = error.localizedDescription } }
    }
    func activityKey(_ id: UUID, _ filter: String, _ selection: String = "") -> String { "\(id):\(filter):\(selection)" }
    func loadActivityPage(_ id: UUID, filter: String, more: Bool = false, selection: String = "") async {
        guard let taf = cloudTaf(for: id) else { return }
        let key = activityKey(id, filter, selection)
        var cache = activityPages[key] ?? SourcePages()
        guard !cache.loading else {
            if more { cache.moreWhenReady = true; activityPages[key] = cache }
            return
        }
        let cursor = more ? cache.pages.last?.next_cursor : nil
        if more && cursor == nil { return }
        let gen = accountGeneration, presentation = presentationGeneration
        cache.loading = true; cache.moreWhenReady = false; cache.error = nil; activityPages[key] = cache
        do {
            let (page, tag) = try await cloud.activityPage(taf.id, filter: filter, cursor: cursor, etag: more ? nil : cache.etag, selection: selection)
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            if let page { cache.pages = more ? cache.pages + [page] : [page] }
            if !more { cache.etag = tag }
            let follow = activityPages[key]?.moreWhenReady == true
            cache.loading = false; cache.moreWhenReady = false; activityPages[key] = cache
            if follow { await loadActivityPage(id, filter: filter, more: true, selection: selection) }
        } catch {
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            cache.loading = false
            if more, let e = error as? TafsCloud.CloudError, e.status == 409 {
                activityPages[key] = SourcePages()
                await loadActivityPage(id, filter: filter, selection: selection)
            } else { cache.error = error.localizedDescription; activityPages[key] = cache }
        }
    }
    func runKey(_ id: UUID, _ all: Bool) -> String { "\(id):\(all)" }
    func loadRunPage(_ id: UUID, all: Bool = false, more: Bool = false) async {
        guard let taf = cloudTaf(for: id) else { return }
        let key = runKey(id, all)
        var cache = runPages[key] ?? SourcePages()
        guard !cache.loading else {
            if more { cache.moreWhenReady = true; runPages[key] = cache }
            return
        }
        let cursor = more ? cache.pages.last?.next_cursor : nil
        if more && cursor == nil { return }
        let gen = accountGeneration, presentation = presentationGeneration
        cache.loading = true; cache.moreWhenReady = false; cache.error = nil; runPages[key] = cache
        do {
            let (page, tag) = try await cloud.runsPage(taf.id, cursor: cursor, etag: more ? nil : cache.etag, limit: all ? 20 : 3)
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            if let page { cache.pages = more ? cache.pages + [page] : [page] }
            if !more { cache.etag = tag }
        } catch { cache.error = error.localizedDescription }
        guard gen == accountGeneration, presentation == presentationGeneration else { return }
        let follow = cache.error == nil && runPages[key]?.moreWhenReady == true
        cache.loading = false; cache.moreWhenReady = false; runPages[key] = cache
        if follow { await loadRunPage(id, all: all, more: true) }
    }
    func loadAllMemory(_ id: UUID) async {
        guard let taf = cloudTaf(for: id) else { return }
        let gen = accountGeneration
        do {
            let notes = try await cloud.memory(taf.id, all: true)
            if gen == accountGeneration { memories[id] = notes; memoryErrors[id] = nil }
        } catch { if gen == accountGeneration { memoryErrors[id] = error.localizedDescription } }
    }

    enum Mode: String, Codable { case signedOut, sample, kiff }

    @Published private(set) var mode: Mode = .signedOut
    /// Whether this device can approve (kiff/tafs#423), one value for every approval's button,
    /// notice and disabled state: Check again anywhere updates them all at once. Read again
    /// when the app comes back.
    @Published private(set) var approvalReadiness: ApprovalReadiness = ApprovalReadiness.current()
    /// How readiness is read: this device's, or a test's.
    var readApprovalReadiness: () -> ApprovalReadiness = { ApprovalReadiness.current() }
    @Published private(set) var tafs: [Taf] = []
    /// The account's other agents with Cards, the ones tafs doesn't run (made through KIFF's
    /// API): listed apart, never counted as working.
    @Published private(set) var otherAgents: [Taf] = []
    /// The tafs whose setup stopped partway, listed among the tafs (`setting_up`).
    @Published private(set) var settingUp: [Taf] = []
    /// The Cards tafs cloud said were still in force when a Discard was refused (409
    /// `cards_in_force`), by agent: the next Discard revokes them too, so it never fails the
    /// same way twice.
    private var discardCardsLeft: [String: [String]] = [:]
    @Published private(set) var held: [HeldCall] = []
    @Published private(set) var record: [RecordEntry] = []
    @Published private(set) var refreshing = false
    /// The tafs tafs cloud runs for this account, by the KIFF agent they act as.
    @Published private(set) var cloudTafs: [String: TafsCloud.CloudTaf] = [:]
    @Published var lastError: String? {
        // The app's recent errors, in its own words, for a drive's diagnostics (docs/drive.md).
        didSet {
            lastErrorFrom = nil
            if let lastError, lastError != oldValue {
                DriveDiagnostics.shared.error(lastError)
                // Something refused or failed, said to the person: a warning tick (Haptics).
                Haptics.warning()
            }
        }
    }
    /// What the last error came from, when a screen shows only its own: set with `fail`,
    /// cleared by any other error, so no screen reads an error's words to know whose it is.
    enum ErrorFrom { case disconnect, knob }
    @Published private(set) var lastErrorFrom: ErrorFrom?

    func fail(_ words: String, from: ErrorFrom) {
        lastError = words
        lastErrorFrom = from
    }

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
    /// The calendar (kiff/tafs#534): what it shows, kept here so a rotation or a new layout keeps
    /// it; and the last read, for the selected month's grid.
    @Published var calendarState: CalendarState?
    @Published private(set) var calendarRead: TafsCloud.CalendarRead?
    @Published private(set) var calendarError: String?
    /// KIFF's usage read, once read: what the store sells (`store`), who bills the plan, and
    /// members' requests. The balance and every line about it are tafs cloud's (`balance`).
    @Published private(set) var usage: KIFFClient.Usage?
    /// Why KIFF's usage read failed.
    @Published private(set) var usageError: String?
    /// The account's balance now, from tafs cloud (pricing phase 2): Plan & usage, Settings'
    /// Plan card and Home. Nil until read, or from a tafs cloud without the read (404).
    @Published private(set) var balance: TafsCloud.Balance?
    /// Why the balance couldn't be read, in tafs cloud's words when it gives them.
    @Published private(set) var balanceError: String?
    /// A raise went through but the balance couldn't be read after it: what `balance` says
    /// is from before, so no other raise is offered until it's read.
    @Published private(set) var balanceStale = false
    /// A raise is under way, from the tap until it's over: a second tap starts nothing.
    @Published private(set) var raising = false
    /// The account's groups, its people and what KIFF says this person may do with each taf,
    /// once read: the app shows what KIFF says and decides nothing itself.
    @Published private(set) var groups: [KIFFClient.Group] = []
    @Published private(set) var people: [KIFFClient.Person] = []
    @Published private(set) var access: [String: KIFFClient.Access] = [:]
    /// Why they couldn't be read, or a change didn't go through.
    @Published private(set) var peopleError: String?
    /// Buying Solo and packs of credits through the App Store.
    let purchases: Purchases
    /// What happened to the last purchase, request, raise or answer, when there's something to say.
    @Published private(set) var planMessage: String?
    /// The credits the tafs used, read only from tafs cloud (pricing phase 1): this period and
    /// the one before, each nil until read or where tafs cloud has no such read.
    @Published private(set) var credits: TafsCloud.Credits?
    @Published private(set) var previousCredits: TafsCloud.Credits?
    /// One taf's credits and runs, by its tafs cloud id and period (`CreditsKey`).
    @Published private(set) var tafCredits: [CreditsKey: TafsCloud.TafCredits] = [:]
    struct CreditsKey: Hashable { let taf: String; let previous: Bool }
    /// Why a credits read failed, in tafs cloud's words when it gives them (a 503 when KIFF
    /// can't be read): this period's, the one before's, and a taf's runs, by key.
    @Published private(set) var creditsError: String?
    @Published private(set) var previousCreditsError: String?
    @Published private(set) var tafCreditsError: [CreditsKey: String] = [:]

    /// Sets the account's time zone to this device's, so a Card's day ends at the owner's
    /// midnight (kiff/tafs#96): a signed setup call, which KIFF takes from
    /// the account's owner or an admin. The account is read again after.
    /// The device's time zone, offered once when the account's differs (kiff/tafs#206 6.11):
    /// "Use Europe/Paris for times in tafs?". Never changed without the owner: "Not now" is
    /// remembered for that zone and account, and a new zone asks again.
    @Published private(set) var timeZoneOffer: String?

    /// tafs cloud set the account's zone from this phone's (`time_zone` on `GET /v1/setup`): the
    /// account reads it, and nothing is asked.
    private func tookTimeZone(_ zone: String?) {
        guard let zone, var account = kiffAccount else { return }
        account.timeZone = zone
        kiffAccount = account
        if timeZoneOffer == zone || TimeZone.current.identifier == zone { timeZoneOffer = nil }
    }

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

    func useThisDevicesTimeZone() async { await setAccountTimeZone(TimeZone.current.identifier) }

    func setAccountTimeZone(_ zone: String) async {
        guard mode == .kiff, let tenant = kiffTenant else { return }
        let gen = accountGeneration
        timeZoneError = nil
        // The owner's act, behind the device's unlock, as KIFF asks of a phone.
        do { _ = try await signer.sign(Data("tafs.time-zone.v1|\(tenant)|\(zone)".utf8), reason: Words.Settings.TimeZone.prompt(zone: "\(zone)")) } catch { return }
        guard gen == accountGeneration else { return }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        var confirmed = false
        do {
            _ = try await kiff.setTimeZone(zone)
            guard gen == accountGeneration else { return }
            confirmed = true
            let account = try await kiff.me()
            guard gen == accountGeneration else { return }
            kiffAccount = account
            presentationGeneration += 1
            activityPages = [:]; runPages = [:]; tafSetups = [:]; knobs = [:]; works = [:]
            try await cloud.syncTimeZone()
            guard gen == accountGeneration else { return }
        } catch {
            guard gen == accountGeneration else { return }
            timeZoneError = confirmed ? Words.Settings.accountZoneChangedSchedulesCould(why: "\(error.localizedDescription)") : Words.Settings.TimeZone.notChanged(why: "\(error.localizedDescription)")
        }
        if confirmed, gen == accountGeneration {
            await refresh()
            if let id = tafOnScreen { await loadTafSetup(id); await loadKnob(id) }
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
    @Published private(set) var approvalKeyLost = false
    /// A re-link waiting for the person's browser: the code to check there, and the page.
    struct Relink: Equatable { let userCode: String; let url: URL }
    @Published private(set) var relink: Relink?
    private var relinkTask: Task<Void, Never>?
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
    var onHeldChanged: (@MainActor ([HeldCall], [Taf], [UUID: String]) -> Void)?

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

    init(ownerName: String = Words.Common.you,
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
    /// A taf newly waiting on the owner still goes to the top at once.
    var listedTafs: [Taf] {
        let settled = Self.inOrder(tafs, listOrder, fallback: Self.byRecent(tafs, record: record, held: held, chats: chats, signedOut: signedOutTafs))
        return Self.waitingFirst(settled, waiting: waitingOnOwner.subtracting(waitingWhenSettled))
    }

    /// The tafs waiting on the owner when the order was last settled: already placed by it.
    private var waitingWhenSettled: Set<UUID> = []

    /// The tafs waiting on the owner: a call held for an answer, or a connection its vendor
    /// signed out, to sign in to again.
    var waitingOnOwner: Set<UUID> { Set(held.map(\.tafID)).union(signedOutTafs) }

    /// The tafs with a connection their vendor signed out.
    private var signedOutTafs: Set<UUID> { Set(tafs.map(\.id).filter { signedOut($0) != nil }) }

    /// The tafs newly waiting on the owner first, each part in the order it had.
    nonisolated static func waitingFirst(_ tafs: [Taf], waiting: Set<UUID>) -> [Taf] {
        tafs.filter { waiting.contains($0.id) } + tafs.filter { !waiting.contains($0.id) }
    }

    /// The list in two (kiff/tafs#306): the tafs this person manages, and those shared with
    /// them to use, each in the list's order. Which is which is KIFF's answer; a taf whose
    /// answer isn't in yet counts as theirs, so nothing moves into "Shared with you" and back.
    /// Whether a taf belongs in the iPad's sidebar now (the owner's layout): it has an item in Needs you (a held
    /// call, a step it handed over, a knock at its door), or it ran or changed in the last 24 hours, as tafs cloud says. The
    /// rest are in the tafs list behind the mascot.
    func relevantNow(_ taf: Taf) -> Bool {
        let cloud = cloudTaf(for: taf.id)
        return Self.relevantNow(held: !heldCalls(for: taf.id).isEmpty,
                                step: cloud.map { c in yourSteps.contains { $0.taf?.id == c.id } } ?? false,
                                knock: (taf.doors ?? []).contains { $0.knocks > 0 },
                                lastRun: cloud?.lastRun, lastChange: cloud?.lastChange, now: clock())
    }

    nonisolated static func relevantNow(held: Bool, step: Bool, knock: Bool, lastRun: Date?, lastChange: Date? = nil, now: Date) -> Bool {
        if held || step || knock { return true }
        // A run, or a change (a version, its Card, a setting: tafs cloud's `last_change`), in the last 24 hours.
        return [lastRun, lastChange].contains { $0.map { now.timeIntervalSince($0) < 24 * 60 * 60 } ?? false }
    }

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
        listOrder = Self.byRecent(tafs, record: record, held: held, chats: chats, signedOut: signedOutTafs).map(\.id)
        waitingWhenSettled = waitingOnOwner
    }

    /// tafs in a settled order; a taf the order doesn't know yet comes after, as `fallback` has it.
    nonisolated static func inOrder(_ tafs: [Taf], _ order: [UUID], fallback: [Taf]) -> [Taf] {
        guard !order.isEmpty else { return fallback }
        let place = Dictionary(order.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        let known = tafs.filter { place[$0.id] != nil }.sorted { place[$0.id]! < place[$1.id]! }
        return known + fallback.filter { place[$0.id] == nil }
    }

    nonisolated static func byRecent(_ tafs: [Taf], record: [RecordEntry], held: [HeldCall],
                                     chats: [UUID: [TafsCloud.ChatMessage]], signedOut: Set<UUID> = []) -> [Taf] {
        var latest: [UUID: Date] = [:]
        for e in record { latest[e.tafID] = max(latest[e.tafID] ?? .distantPast, e.at) }
        for (id, messages) in chats { if let at = messages.last?.at { latest[id] = max(latest[id] ?? .distantPast, at) } }
        let waiting = Set(held.map(\.tafID)).union(signedOut)
        return tafs.enumerated().sorted { a, b in
            let wa = waiting.contains(a.element.id), wb = waiting.contains(b.element.id)
            if wa != wb { return wa }
            let la = latest[a.element.id] ?? .distantPast, lb = latest[b.element.id] ?? .distantPast
            return la != lb ? la > lb : a.offset < b.offset
        }.map(\.element)
    }

    var workingCount: Int { tafs.filter { $0.state == .working }.count }

    /// Home's line: tafs cloud's words as sent, or, when it sent none, the app's own count of
    /// the tafs working.
    var workingWords: String { homeHeadline ?? Words.Home.working(count: workingCount) }

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
        return Words.NeedsYou.Refused.other(why: "\(error.localizedDescription)")
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
        case "self_review": return Words.NeedsYou.Refused.selfReview
        case "separation_of_duties_role": return Words.NeedsYou.Refused.approverOnly
        case "separation_of_duties_key": return Words.NeedsYou.Refused.signedInApprover
        case "card_changer": return Words.NeedsYou.Refused.cardChanger
        case "hold_check_unavailable": return Words.NeedsYou.Refused.checkUnavailable
        case "role_forbidden": return Words.NeedsYou.Refused.role
        default: return nil
        }
    }

    // MARK: Signing in with KIFF

    /// Moves on with every sign-in and sign-out, so work for one session (and its key)
    /// never outlives it, even work still running when it changes.
    private(set) var accountGeneration = 0 {
        didSet {
            phoneAlerts = nil; phoneAlertsBusy = false; phoneAlertsError = nil
            tafSetups = [:]; tafSetupErrors = [:]; connectedAgain = [:]; activityPages = [:]; runPages = [:]; timeZones = []
            recordHint = nil; recordSearch = nil; recordCase = nil; recordCaseError = nil
            recordTyping?.cancel(); recordTyping = nil
            cardUnitExceptions = []; dismissingCardUnits = []; setupContextTafID = nil; homeNeedsYou = nil; homeHeadline = nil
            yourSteps = []; answeredSteps = []; answeringSteps = []; requestedStep = nil; answeredStillCounted = 0; coachWaitingListed = []
            coachWaitingClear = nil; settingAside = []; setAsideChats = []
            haltErrors = [:]
            alertsOffered = false
            remembered?.removeObject(forKey: Self.alertsOfferedKey)
        }
    }

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
                // A device without a Secure Enclave holds no key: it signs in without one,
                // and KIFF takes a platform only with a key.
                let key = signer.publicKeyDER
                let approvalKey = signer.approvalPublicKeyDER
                return try await kiff.startDeviceSignIn(publicKey: key.isEmpty ? nil : key,
                                                        approvalPublicKey: key.isEmpty || approvalKey.isEmpty ? nil : approvalKey,
                                                        deviceName: Words.Settings.Devices.thisDeviceName(device: "\(ThisDevice.kind)"),
                                                        platform: key.isEmpty ? nil : ThisDevice.platform,
                                                        kind: ThisDevice.kiffKind)
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
        doorModels = [:]; chatKnocks = [:]; works = [:]; workErrors = [:]; sendingAgain = []; knobs = [:]; knobChanges = [:]; eventSubscriptionReceipts = [:]; eventApprovals = []; runNowWords = [:]
        holderIDs = [:]; halts = [:]; chats = [:]; chatErrors = [:]; memories = [:]; memoryErrors = [:]; versions = [:]; versionErrors = [:]; openTrials = [:]; trialChats = [:]; trialErrors = [:]; switches = [:]; changedVersionCards = [:]; staleVersionCards = [:]; versionsRead = []
        listLoaded = false; listUnread = false
        remembered?.removeObject(forKey: Self.listKey) // an earlier session's tafs never show in this one
        unfinishedRemovals = []
        doorsOffered = []
        grants = []
        connectApps = nil; connectStart = nil; connectAppsQuery = ""; connectAppsRequest = nil
        clearingRemoved = false; clearNote = nil
        grantsReadFor = []
        refusedRequests = []
        setupStream?.cancel(); setupStream = nil; setupLive = nil
        setupTaf = nil; setupThread = SetupThread(); setupAwaiting = nil; setupDrafts = [:]; answered = []; setupError = nil
        setupUnsent = []; setupDraft = ""; cardNotes = [:]; setupSettled = []; setupEnded = nil; setupChat = nil; setupHanded = false; coachStart = nil; coachTools = nil; coachToolsAsked = false; coachToolsError = nil; setupFresh = false; setupChats = []; archivedSetupChats = []; setupSent = []; chatSent = [:]; recipes = nil; gallery = nil; suggestions = nil; suggestionsBusy = false; suggestionsError = nil; calendarState = nil; calendarRead = nil; calendarError = nil
        chatStreams.values.forEach { $0.cancel() }; chatStreams = [:]; chatStates = [:]; kiffAccount = nil; chatFiles = [:]; fileNotices = [:]; Self.removeOpenedFiles()
        usage = nil; usageError = nil; balance = nil; balanceError = nil; balanceStale = false; planMessage = nil; credits = nil; previousCredits = nil; tafCredits = [:]; creditsError = nil; previousCreditsError = nil; tafCreditsError = [:]
        groups = []; people = []; access = [:]; peopleError = nil
        planque.deviceID = nil
        forgetAIConsent()
        accountGeneration += 1
        tafs = []; listOrder = []; waitingWhenSettled = []; orderHasRecord = false; otherAgents = []; held = []; record = []; decided = [:]; devices = []; unreachable = nil; retries = 0; retryingRefresh = false; retrying?.cancel()
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

    /// This phone has an approval key that KIFF doesn't hold for the session: what that key signs,
    /// KIFF can't check. A signer with no approval key signs everything with its one key.
    var approvalKeyUnheld: Bool { !signer.approvalPublicKeyDER.isEmpty && !sessionHasApprovalKey }

    /// What Start says then, the line both apps use: KIFF didn't accept the key, try again; from
    /// the second refusal in a row, where to write too. A key KIFF holds another of keeps the lost
    /// key's words.
    private func approvalKeyRefused() -> String {
        if approvalKeyLost { return KIFFClient.approvalKeyLost }
        approvalKeyRefusals += 1
        return approvalKeyRefusals >= 2 ? Words.Approval.keyNotAccepted + "\n" + Words.Approval.keyNotAcceptedSupport : Words.Approval.keyNotAccepted
    }
    /// How many Starts in a row KIFF didn't take this device's approval key on: a key KIFF takes,
    /// or a new session, starts again from none.
    private(set) var approvalKeyRefusals = 0

    /// From now on, what grants authority is signed by the approval key.
    private func useApprovalKey() {
        approvalKeyRefusals = 0
        sessionHasApprovalKey = true
        approvalKeyLost = false
        let signer = self.signer
        kiff.approvalSigner = { try signer.signApprovalConfirmed($0) }
    }

    private func forgetApprovalKey() {
        approvalKeyRefusals = 0
        cancelRelink()
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

    /// Lets this phone approve again after its faces or fingerprints changed, with no
    /// sign-out (kiff/tafs#233, case 2). The new approval key signs KIFF's re-link message
    /// under Face ID or Touch ID, the device key signs the request, and the person approves
    /// the new key in their browser; the session, its alerts and its list stay. Returns the
    /// page to open, or nil when it couldn't start (`lastError` says why).
    @discardableResult
    func startRelink() async -> URL? {
        guard mode == .kiff, approvalKeyLost, sessionHasKey, relink == nil, let tenant = kiffTenant else { return nil }
        let key = signer.approvalPublicKeyDER
        guard !key.isEmpty else {
            lastError = Words.Approval.addFaceIdFirst(device: "\(ThisDevice.kind)")
            return nil
        }
        let gen = accountGeneration
        let signedAt = ISO8601DateFormatter().string(from: clock())
        let message = KIFFClient.relinkMessage(tenant: tenant, deviceKey: signer.publicKeyDER, newKey: key, signedAt: signedAt)
        let proof: String
        do {
            proof = try await signer.signApproval(message, reason: Words.Approval.approveAgainOnDevice(device: "\(ThisDevice.kind)"))
        } catch {
            lastError = KIFFClient.notRecognized
            return nil
        }
        guard gen == accountGeneration else { return nil }
        let signer = self.signer
        kiff.tenant = tenant
        let start: KIFFClient.DeviceStart
        do {
            start = try await kiff.startRelink(approvalPublicKey: key, signedAt: signedAt, signature: proof,
                                               signer: { try signer.signConfirmed($0) })
        } catch KIFFClient.KIFFError.server(404, _, _) {
            lastError = Words.Kiff.kiffCanNotDoSign(device: "\(ThisDevice.kind)")
            return nil
        } catch {
            lastError = Words.SignIn.couldNotStart(why: "\(error.localizedDescription)")
            return nil
        }
        guard gen == accountGeneration, let url = URL(string: start.verificationURIComplete) else { return nil }
        relink = Relink(userCode: start.userCode, url: url)
        relinkTask = Task { [weak self] in await self?.finishRelink(start, key: key, generation: gen) }
        return url
    }

    /// Polls a re-link until the browser approves it, or it expires or is cancelled.
    private func finishRelink(_ start: KIFFClient.DeviceStart, key: Data, generation gen: Int) async {
        defer { if gen == accountGeneration { relink = nil; relinkTask = nil } }
        var interval = UInt64(max(start.interval, signInMinimumInterval)) * 1_000_000_000
        let deadline = clock().addingTimeInterval(TimeInterval(start.expiresIn))
        while clock() < deadline {
            var waited: UInt64 = 0
            while waited < interval && !signInNudged {
                guard (try? await Task.sleep(nanoseconds: min(signInSlice, interval - waited))) != nil else { return }
                waited += signInSlice
            }
            signInNudged = false
            guard !Task.isCancelled, gen == accountGeneration else { return }
            do {
                let held = try await kiff.pollRelink(deviceCode: start.deviceCode)
                guard gen == accountGeneration else { return }
                // KIFF's fingerprint says which key it holds now: only ours lets this phone approve.
                guard KIFFClient.sameKey(held, key) else {
                    lastError = KIFFClient.approvalKeyLost
                    return
                }
                Keychain.save(KIFFClient.fingerprint(key), for: Self.approvalKeyedAccount)
                useApprovalKey()
                return
            } catch KIFFClient.KIFFError.authorizationPending {
                continue
            } catch KIFFClient.KIFFError.slowDown {
                interval += 5_000_000_000
            } catch KIFFClient.KIFFError.expired {
                break
            } catch where Self.transient(error) {
                continue
            } catch {
                lastError = Words.SignIn.couldNotFinish(why: "\(error.localizedDescription)")
                return
            }
        }
        lastError = Words.SignIn.Relink.codeExpired
    }

    /// The person gave up waiting for the browser: nothing changes, and a new re-link
    /// can start (KIFF lets the last one go when it does).
    func cancelRelink() {
        relinkTask?.cancel()
        relinkTask = nil
        relink = nil
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

    /// Whether this person manages the account's billing (KIFF's billing.manage), as Android
    /// reads it: they buy and raise, and answer members' requests; anyone else asks an admin.
    /// Not KIFF's usage `can_add_tasks`, which it has sent as false since credits.
    var managesBilling: Bool { Self.managesBilling(kiffAccount?.capabilities) }

    nonisolated static func managesBilling(_ capabilities: [String]?) -> Bool { allows(capabilities, "billing.manage") }

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

    /// Reads this person's access to each taf, on its own: one who may not read the account's
    /// people still manages the tafs they made. Kept as it was when it can't be read.
    func refreshAccess(gen: Int) async {
        guard mode == .kiff else { access = [:]; return }
        guard let access = try? await kiff.myAccess(), gen == accountGeneration else { return }
        self.access = access
    }

    /// Reads the account's groups, its people and this person's access to each taf.
    func refreshPeople() async {
        guard mode == .kiff else { groups = []; people = []; access = [:]; return }
        let gen = accountGeneration
        await refreshAccess(gen: gen)
        do {
            async let g = kiff.groups()
            async let p = kiff.people()
            let (groups, people) = try await (g, p)
            guard gen == accountGeneration else { return }
            self.groups = groups.sorted { ($0.everyone == true ? 0 : 1, $0.name.lowercased()) < ($1.everyone == true ? 0 : 1, $1.name.lowercased()) }
            self.people = people.filter(\.active).sorted { $0.label.lowercased() < $1.label.lowercased() }
            peopleError = nil
        } catch {
            guard gen == accountGeneration else { return }
            peopleError = Words.People.notRead(why: "\(error.localizedDescription)")
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
            devicesError = Words.Settings.Devices.notLoaded(why: "\(error.localizedDescription)")
        }
    }

    /// Signs another device out of the account, behind Face ID. This device signs out with
    /// Sign out instead. False when it didn't go through.
    @discardableResult
    func removeDevice(_ device: KIFFClient.Device) async -> Bool {
        guard mode == .kiff, let tenant = kiffTenant, !device.thisDevice else { return false }
        let gen = accountGeneration
        let what = Words.Settings.Devices.signOutPrompt(device: device.name ?? Words.Settings.Devices.thisDevice)
        do { _ = try await signer.sign(Data("tafs.devices.v1|\(tenant)|\(device.id)".utf8), reason: what) } catch { return false }
        guard gen == accountGeneration else { return false }
        kiff.tenant = tenant
        do {
            try await kiff.removeDevice(device.id)
        } catch {
            guard gen == accountGeneration else { return false }
            devicesError = Words.Common.didntGoThrough(what: "\(what)", why: "\(error.localizedDescription)")
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
        do { _ = try await signer.sign(Data("tafs.people.v1|\(tenant)|\(what)".utf8), reason: what, grants: true) } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            try await change(kiff)
        } catch {
            guard gen == accountGeneration else { return false }
            // Read again first: a reread that works clears the error, so the reason is set
            // after it, or the owner never sees why the change failed.
            await refreshPeople()
            guard gen == accountGeneration else { return false }
            peopleError = Words.Common.didntGoThrough(what: "\(what)", why: "\(error.localizedDescription)")
            return false
        }
        await refreshPeople()
        return true
    }

    /// Why the last sign-out didn't happen: KIFF didn't end this session.
    @Published var signOutError: String?

    /// Signs this device out: the session ends at KIFF first, so a device shown as signed out never
    /// holds a session KIFF still accepts. When KIFF doesn't confirm, nothing changes here and
    /// `signOutError` says so; false then. A session KIFF already ended (401) counts as ended.
    @discardableResult
    func signOut() async -> Bool {
        signOutError = nil
        await DriveSession.shared.stopAndWait("signed_out")
        if mode == .kiff {
            if alertsReady == true { UserDefaults.standard.set(true, forKey: alertsEnabledKey) }
            // Alerts are unregistered with the session, so before KIFF ends it; registered again
            // at once when KIFF doesn't, so a device still signed in never goes without them.
            if let pushToken { try? await cloud.unregisterPush(deviceToken: pushToken) }
            do {
                try await kiff.signOut()
            } catch KIFFClient.KIFFError.unauthorized {
                // KIFF had already ended it.
            } catch {
                signOutError = Words.SignIn.notSignedOutDevice(why: "\(error.localizedDescription)", device: "\(ThisDevice.kind)")
                await syncPush()
                return false
            }
        }
        forgetSession()
        return true
    }

    /// Forgets this device's session and everything read with it, without asking KIFF: for a
    /// session KIFF has already ended (a 401, the account deleted), and after `signOut`.
    func forgetSession() {
        DriveSession.shared.stop("signed_out")
        Keychain.delete(Self.tokenAccount)
        Keychain.delete(Self.tenantAccount)
        Keychain.delete(Self.keyedAccount)
        Keychain.delete(Self.approvalKeyedAccount)
        forgetApprovalKey()
        kiff.token = nil
        cloud.token = nil
        cloudTafs = [:]; halts = [:]
        chats = [:]; chatErrors = [:]; memories = [:]; memoryErrors = [:]; versions = [:]; versionErrors = [:]; openTrials = [:]; trialChats = [:]; trialErrors = [:]; switches = [:]; changedVersionCards = [:]; staleVersionCards = [:]; versionsRead = []
        holderIDs = [:]
        doorModels = [:]; chatKnocks = [:]; works = [:]; workErrors = [:]; sendingAgain = []; knobs = [:]; knobChanges = [:]; eventSubscriptionReceipts = [:]; eventApprovals = []; runNowWords = [:]
        listLoaded = false; listUnread = false
        remembered?.removeObject(forKey: Self.listKey)
        unfinishedRemovals = []
        doorsOffered = []
        grants = []
        connectApps = nil; connectStart = nil; connectAppsQuery = ""; connectAppsRequest = nil
        clearingRemoved = false; clearNote = nil
        grantsReadFor = []
        refusedRequests = []
        setupStream?.cancel(); setupStream = nil; setupLive = nil
        setupTaf = nil; setupThread = SetupThread(); setupAwaiting = nil; setupDrafts = [:]; answered = []; setupError = nil
        setupUnsent = []; setupDraft = ""; cardNotes = [:]; setupSettled = []; setupEnded = nil; setupChat = nil; setupHanded = false; coachStart = nil; coachTools = nil; coachToolsAsked = false; coachToolsError = nil; setupFresh = false; setupChats = []; archivedSetupChats = []; setupSent = []; chatSent = [:]; recipes = nil; gallery = nil; suggestions = nil; suggestionsBusy = false; suggestionsError = nil; calendarState = nil; calendarRead = nil; calendarError = nil
        chatStreams.values.forEach { $0.cancel() }; chatStreams = [:]; chatStates = [:]; kiffAccount = nil; chatFiles = [:]; fileNotices = [:]; Self.removeOpenedFiles()
        usage = nil; usageError = nil; balance = nil; balanceError = nil; balanceStale = false; planMessage = nil; credits = nil; previousCredits = nil; tafCredits = [:]; creditsError = nil; previousCreditsError = nil; tafCreditsError = [:]
        groups = []; people = []; access = [:]; peopleError = nil
        remembered?.removeObject(forKey: Self.settledKey) // nothing of the chats stays on the phone (docs/data.md)
        planque.deviceID = nil
        forgetAIConsent()
        accountGeneration += 1
        kiffTenant = nil
        sessionHasKey = false
        alertsReady = nil
        canAnswerHolds = nil
        handoffInMemory = nil
        Keychain.delete(Self.alertHandoffAccount)
        store?.clear()
        tafs = []; listOrder = []; waitingWhenSettled = []; orderHasRecord = false; held = []; record = []; decided = [:]; devices = []; unreachable = nil; retries = 0; retryingRefresh = false; retrying?.cancel()
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
    /// "Allow this from now on", open on a held call (docs/allow-from-now-on.md).
    @Published var allow: AllowSheet?

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
        // A step a taf handed its owner: Needs you, where "Done" and "Skip it" are, brings it into view.
        case let d where d.hasPrefix("step:"):
            requestedTab = .needsYou
            requestedStep = String(d.dropFirst("step:".count))
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

    // MARK: Coach's tools (docs/coach.md › Coach's tools step)

    /// Reads Coach's tools as KIFF has them, from tafs cloud: what's still to give, if anything.
    /// A read that fails leaves what was read.
    func loadCoachTools() async {
        guard mode == .kiff, setupTaf != nil else { return }
        let gen = accountGeneration
        guard let read = try? await cloud.setupRead(), gen == accountGeneration else { return }
        showCoachTools(read.tools)
    }

    /// What tafs cloud said of Coach's tools. Once they're given, a held `version_install` of
    /// Coach's is an install: what's held is read again so it shows as one.
    private func showCoachTools(_ tools: TafsCloud.CoachTools?) {
        let wasGiven = coachTools?.given == true
        coachTools = tools
        if tools?.given == true {
            coachToolsAsked = false
            if !wasGiven, !held.isEmpty { Task { await refresh() } }
        }
    }

    /// The tools step put off: the row above the conversation opens it again.
    func notNowCoachTools() { coachToolsAsked = false }

    /// Gives Coach its tools, as tafs cloud says they're still to give (`GET /v1/setup`'s
    /// `tools`): one Face ID, then the batch of connections, sent as it came and signed, then
    /// Coach's Cards' draft, sent as it came. The phone builds neither: tafs cloud reads KIFF
    /// each time, so every phone of the account agrees. A name a vendor's tool holds stops it
    /// before anything is asked or sent, and says which. True once nothing's left to give.
    func giveCoachTools() async -> Bool {
        // Before Coach exists, its tools are given by starting it: one Face ID for both.
        if setupTaf == nil, coachStart?.startable == true {
            guard !givingCoachTools else { return false }
            givingCoachTools = true
            defer { givingCoachTools = false }
            coachToolsError = nil
            setupError = nil
            let started = await startSetup()
            // Said on the step the person tapped from, where they're looking.
            if !started { coachToolsError = setupError ?? lastError }
            return started && coachTools?.toGive != true
        }
        guard mode == .kiff, sessionHasKey, let tenant = kiffTenant, setupTaf != nil else { return false }
        guard !givingCoachTools, !approvalBlocked else { return false }
        givingCoachTools = true
        defer { givingCoachTools = false }
        let gen = accountGeneration
        coachToolsError = nil
        let tools: TafsCloud.CoachTools
        do {
            guard let read = try await cloud.setupRead().tools else { return false }
            tools = read
        } catch {
            if gen == accountGeneration { coachToolsError = Words.Coach.toolsNotRead(why: error.localizedDescription) }
            return false
        }
        guard gen == accountGeneration else { return false }
        showCoachTools(tools)
        switch tools.state {
        case "given": return true
        case "taken":
            coachToolsError = tools.taken.map { Words.Coach.Taken.named(name: "\($0)") } ?? Words.Coach.Taken.unnamed
            return false
        case "to_give": break
        default:
            coachToolsError = Words.Coach.toolsUnknown
            return false
        }
        guard tools.connect != nil || tools.card != nil || tools.together != nil else { return false }
        // The one confirmation: it gives Coach its Cards, and the connections go under it.
        do {
            _ = try await signer.sign(Data("tafs.coach-tools.v1|\(tenant)".utf8), reason: Words.Coach.Prompt.giveCard, grants: true)
        } catch {
            if gen == accountGeneration { coachToolsError = ApprovalReadiness.notApproved(error, kind: Biometry.kind) }
            return false
        }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            // tafs cloud's pair (kiff/tafs#881): the batch and Coach's Cards under this one Face ID,
            // signed once by the approval key. Coach exists, so nothing is claimed after it.
            if let pair = tools.together, let connect = pair.connect, let draft = pair.draft, !approvalKeyUnheld {
                _ = try await sendTogether(connect, draft, tenant: tenant, gen: gen)
                guard gen == accountGeneration else { return false }
                await loadCoachTools()
                return coachTools?.given == true
            }
            if let connect = tools.connect { try await kiff.connectBatch(connect.body) }
            guard gen == accountGeneration else { return false }
            // tafs cloud drafts the Cards over tools KIFF holds: where it sent none with the
            // batch, they come once the batch is in, so they're read again and given in the
            // same step, and the person confirms once.
            var card = tools.card
            if card == nil, tools.connect != nil { card = try await cloud.setupRead().tools?.card }
            guard gen == accountGeneration else { return false }
            if let card { try await kiff.submitDraft(card.body) }
        } catch {
            if gen == accountGeneration { coachToolsError = Words.Coach.toolsNotGiven(why: error.localizedDescription) }
            return false
        }
        guard gen == accountGeneration else { return false }
        await loadCoachTools()
        return coachTools?.given == true
    }

    /// The account's Coach, once tafs cloud says its tools are given; nil before. Only its held
    /// version_install is an install (kiff/tafs#206).
    private var coachWithTools: String? {
        guard coachTools?.given == true, let agent = setupTaf?.kiffAgent, !agent.isEmpty else { return nil }
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
        if UserDefaults.standard.object(forKey: alertsEnabledKey) as? Bool == false {
            alertsReady = false
            if let handoff, (try? await cloud.keepAlerts(handoff.target)) != nil, gen == accountGeneration { pendingHandoff = nil }
            return
        }
        if UserDefaults.standard.object(forKey: alertsEnabledKey) as? Bool != true && handoff == nil {
            // A different account must not inherit this phone's earlier alert consent.
            let previous = try? await cloud.phoneAlerts(token)
            guard gen == accountGeneration else { return }
            guard previous?.enabled == true else { alertsReady = false; return }
            UserDefaults.standard.set(true, forKey: alertsEnabledKey)
        }
        guard let status = try? await cloud.registerPush(deviceToken: token, environment: Self.pushEnvironment, sound: alertSound, target: handoff?.target, kinds: savedAlertKinds) else {
            if gen == accountGeneration { alertsReady = false }
            return
        }
        guard gen == accountGeneration else { return }
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
    /// the owner allows notifications, with no Face ID; the phone points the
    /// account's KIFF alert target at tafs cloud (a signed setup call) and hands tafs cloud
    /// the target's signing secret with its push token. A secret tafs cloud didn't take last
    /// time is handed over again first, without registering a new one. True once done.
    @discardableResult
    func turnOnAlerts() async -> Bool {
        guard mode == .kiff, sessionHasKey, let tenant = kiffTenant else {
            lastError = Words.Alert.signInAgain
            return false
        }
        guard !turningOnAlerts else { return false }
        turningOnAlerts = true
        defer { turningOnAlerts = false }
        let gen = accountGeneration

        guard await askNotificationPermission?() ?? false else {
            lastError = Words.Alert.allowNotifications
            return false
        }
        if pushToken == nil { requestPushToken?() }
        for _ in 0..<40 where pushToken == nil { try? await Task.sleep(nanoseconds: 250_000_000) }
        guard let token = pushToken else {
            lastError = Words.Alert.noPushTokenDevice(device: "\(ThisDevice.kind)")
            return false
        }

        var handoff = pendingHandoff
        if handoff == nil {
            // No Face ID here (`AlertsTests`): the alert target is a signed setup call KIFF takes
            // with the device key, which signs with no prompt, as Refuse and Halt all do. It grants
            // no authority (`KIFFClient.grantsAuthority`), so the approval key never signs it; a
            // prompt here only confirmed a signature nothing checks, before any job had started.
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
                    ? Words.Alert.kiffDoesntSend
                    : Words.Alert.roleCant
                return false
            } catch {
                lastError = Words.Alert.notOn(why: "\(error.localizedDescription)")
                return false
            }
        }
        do {
            let status = try await cloud.registerPush(deviceToken: token, environment: Self.pushEnvironment, sound: alertSound, target: handoff?.target, kinds: savedAlertKinds)
            guard gen == accountGeneration else { return false }
            pendingHandoff = nil
            UserDefaults.standard.set(true, forKey: alertsEnabledKey)
            applyPush(status)
            await loadPhoneAlerts()
            return status.alerts
        } catch {
            lastError = Words.Alert.alertsAlmostTafsCloudDid(why: "\(error.localizedDescription)")
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
        if let e = error as? URLError, network.contains(e.code) { return Words.Home.cantReachKiff }
        return Words.Home.couldntLoadFromKiff
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
        // Faces or fingerprints changed since the approval key was made: it can't sign again,
        // so the phone says so now, not after a tap. Its new key counts after a new sign-in.
        if signer.renewApprovalKeyIfBiometricsChanged() { lostApprovalKey() }
        refreshing = true
        let gen = accountGeneration
        let presentation = presentationGeneration
        // Dropped before its answer: the list wasn't read, so it isn't shown as loaded (and
        // empty). The next refresh reads it.
        var cancelled = false
        defer {
            refreshing = false
            DriveWaits.emit("refreshed")
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
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
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
            if kiffAccount == nil, var account {
                // A new account still on UTC: tafs cloud takes this phone's zone, with no question,
                // when it can (docs/api.md). Asked only then, so it costs nothing afterwards.
                if (account.timeZone ?? "UTC") == "UTC", TimeZone.current.identifier != "UTC",
                   let zone = try? await cloud.setupRead().timeZone, gen == accountGeneration {
                    account.timeZone = zone
                }
                guard gen == accountGeneration else { return }
                kiffAccount = account
                offerTimeZone(account)
            }
            show(list, exceptions)
            // An account with no taf: Home lists the recipes, where tafs cloud says so, so they're
            // read with each refresh that finds none, not only when Coach opens (docs/ios-app.md).
            if tafs.isEmpty {
                await loadRecipes()
                guard gen == accountGeneration else { return }
            }
            // The account's halt, read here when no taf runs: its row reads it for itself, and
            // without a taf there was no row. A halt outlives the tafs it was made over, and its
            // row is the only place to resume from (docs/approvals.md, Halting).
            if cloudTafs.isEmpty, canManageCards {
                await refreshHalt(nil)
                guard gen == accountGeneration else { return }
            }
            // The gallery, with each refresh: + New opens it, and the strip under the list
            // leaves out what the account runs now.
            await loadGallery()
            guard gen == accountGeneration else { return }
            // The AI-data consent: its screen comes up after sign-in while it isn't given.
            await loadAIConsent()
            guard gen == accountGeneration else { return }
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
            // What this person may do with each taf: whether they manage it decides its Doors,
            // dial, Versions and Halt, so it's read with the list, not only from People.
            await refreshAccess(gen: gen)
            await refreshConnections()
            // The balance, for Settings' Plan card and Home's line: tafs cloud's read alone, quietly.
            await refreshPlan(gen: gen)
        } catch KIFFClient.KIFFError.unauthorized {
            forgetSession()
            lastError = KIFFClient.KIFFError.unauthorized.localizedDescription
        } catch is CancellationError {
            // A refresh the app itself dropped (a newer one, a view that went away): not an
            // error to show ("Couldn't reach KIFF: cancelled").
            cancelled = true
        } catch let error as URLError where error.code == .cancelled {
            cancelled = true
        } catch is TafsCloud.CloudError {
            // A refresh is the app's own doing, so it never shows as "Not done" (the 930 test).
            refreshFailed(Words.Home.couldntLoadTafs, gen: gen)
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
        cardUnitExceptions = home.cardUnitExceptions ?? []
        showSteps(home.yourSteps ?? [])
        // A chat being set aside isn't drawn again by a read meanwhile, as on Android: `coachWaiting`
        // leaves it out until tafs cloud answers, and a refusal brings it back.
        coachWaitingListed = (home.coachWaiting ?? []).filter { $0.usable }
        coachWaitingClear = home.coachWaitingClear.flatMap { $0.usable ? $0 : nil }
        homeNeedsYou = home.needsYou
        homeHeadline = home.headlineWords
        var running: [String: TafsCloud.CloudTaf] = [:]
        for t in home.tafs { if let c = t.cloudTaf { running[t.kiffAgent] = c } }
        cloudTafs = running
        unfinishedRemovals = home.removing
        doorsOffered = home.doorsOffered ?? []
        let before = Set(tafs.map(\.id))
        tafs = home.tafs.map { KIFFMapping.taf($0, id: holderID($0.kiffAgent), owner: ownerName) }
        if Set(tafs.map(\.id)) != before || listOrder.isEmpty { settleListOrder() }
        if let half = home.settingUp {
            // Home lists tafs only: a half-made taf among them, and `others`, which only repeats
            // these for older apps, isn't drawn. KIFF's own agents never show here.
            settingUp = half.filter { !$0.name.isEmpty }.map { s in
                var t = Taf(id: holderID(s.kiffAgent.isEmpty ? "setting-up:" + s.name : s.kiffAgent), name: s.name, job: "", tools: [],
                            colorIndex: s.color, card: Card(rules: [], issuedBy: ownerName))
                t.settingUp = true
                t.finish = s.finish
                t.discard = s.discard
                // Its Cards, to revoke on Discard: as `discard` names them, else from `others`' repeat of it.
                t.kiffCardIDs = s.discardCards ?? home.others.first { $0.kiffAgent == s.kiffAgent && !s.kiffAgent.isEmpty }?.card.ids ?? []
                t.now = s.finish?.words ?? ""
                return t
            }
            otherAgents = []
        } else {
            settingUp = []
            otherAgents = home.others.map { KIFFMapping.taf($0, id: holderID($0.kiffAgent), owner: ownerName) }
        }
        // Each open trial is its own entry in the menu, under its taf (kiff/tafs#173).
        for t in home.tafs where t.id != nil { setTrial(t.trial, of: holderID(t.kiffAgent)) }
        let coach = coachWithTools
        held = exceptions.filter { $0.kind != "card_unit" }.map { KIFFMapping.held($0, tafID: holderID($0.subject), coach: coach) }
        unanswerable = unanswerable.filter { id, _ in held.contains { $0.id == id } }
        readAgainAtExpiry()
        showHeldAfterWaiting()
        listLoaded = true
        listUnread = false
        rememberList()
        notifyHeld()
    }

    /// The app's id for a KIFF agent's taf, the same for the whole session.
    /// Who holds a call, as the owner reads it: its taf's name, or **Coach** for a call from
    /// Coach's own agent, which no taf in the list is; nil when neither.
    func holderName(_ tafID: UUID) -> String? {
        if let name = taf(tafID)?.name { return name }
        return holderIDs[Self.setupAgent] == tafID ? Words.Coach.title : nil
    }

    /// Whether `tafID` is Coach's own agent.
    func isCoach(_ tafID: UUID) -> Bool { holderIDs[Self.setupAgent] == tafID }

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
        let door = e.app.map(DoorNames.label) ?? Words.Record.Event.aDoor
        let who = e.from ?? Words.Record.Event.someone
        switch e.kind {
        case "halted": return (e.taf == nil ? Words.Record.Event.haltedAll : Words.Record.Event.halted, Words.Record.Event.haltedDetail)
        case "resumed": return (e.taf == nil ? Words.Record.Event.resumedAll : Words.Record.Event.resumed, Words.Record.Event.resumedDetail)
        case "knocked": return (Words.Record.Event.knocked(who: "\(who)", door: "\(door)"), Words.Record.Event.knockedDetail)
        case "knock_done": return (Words.Record.Event.knockDone(who: "\(who)"), Words.Record.Event.knockDoneDetail(door: "\(door)"))
        case "knock_ignored": return (Words.Record.Event.knockIgnored(who: "\(who)"), Words.Record.Event.knockIgnoredDetail(door: "\(door)"))
        case "paired": return (Words.Record.Event.paired(who: "\(who)"), Words.Record.Event.pairedDetail(door: "\(door)"))
        case "door_removed": return (Words.Record.Event.doorRemoved(door: "\(door)"), Words.Record.Event.doorRemovedDetail)
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

    /// Signed in, a new taf starts in Coach mode (`startNewTaf()`). The sample has no form for
    /// one (its old form is gone, as on Android), so it has no + either.
    var canStartTafs: Bool { mode == .kiff }

    /// Starts one of the sample's tafs with its Card: the sample's own data, and its tests.
    @discardableResult
    func start(_ draft: TafDraft, confirm: Bool = true) async -> Taf? {
        if confirm {
            do {
                _ = try await signer.sign(answer: .approveOnce, heldID: "card-\(UUID().uuidString)", reason: Words.Card.issuePrompt(taf: "\(draft.name)"))
            } catch {
                lastError = Words.Card.cardNotIssued + ApprovalReadiness.notApproved(error, kind: Biometry.kind)
                return nil
            }
        }
        let expires = Calendar.current.date(byAdding: .month, value: 3, to: clock()) ?? clock()
        let taf = Taf(
            name: draft.name, job: draft.job, tools: draft.tools,
            colorIndex: tafs.count,
            card: Card(rules: draft.rules, issuedBy: ownerName, expires: expires),
            state: .working, now: Words.Taf.Now.starting
        )
        tafs.insert(taf, at: 0)
        if let key = draft.templateKey { templateOf[taf.id] = key }
        log(taf.id, .started, Words.Record.started(taf: taf.name), Words.Record.cardIssuedBy(owner: "\(ownerName)"))
        save()
        return taf
    }

    // MARK: Runs

    /// The tafs whose Run now request is on its way, so a second tap starts nothing.
    @Published private(set) var runsInFlight: Set<String> = []
    /// A Run now that started no run, by taf: tafs cloud's words for it (its check found nothing
    /// new), shown under the button until the next one.
    @Published private(set) var runNowWords: [String: String] = [:]

    /// Runs a taf now, outside its schedule. Returns the run's session; nil when it didn't
    /// start, or when a run request for it is already on its way.
    /// "Did this happen?": the money calls whose outcome KIFF can't prove, newest first.
    @Published private(set) var unproven: [KIFFClient.Unproven] = []

    /// Reads "Did this happen?" from KIFF, for someone who answers held calls.
    func loadUnproven() async {
        guard mode == .kiff, mayAnswerHolds else { return }
        let gen = accountGeneration
        do {
            let calls = try await kiff.didThisHappen()
            guard gen == accountGeneration else { return }
            unproven = calls
        } catch {
            // Read again on the next refresh; nothing is answered from a stale list.
        }
    }

    /// The owner's answer behind Face ID, signed like an approval: "No" gives the Card its
    /// room back, "Yes" keeps it counted. The first answer stands.
    func answerHappened(_ call: KIFFClient.Unproven, happened: Bool) async -> Bool {
        guard mode == .kiff, sessionHasKey, let tenant = kiffTenant else {
            lastError = Words.SignIn.againToSign
            return false
        }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.happened.v1|\(tenant)|\(call.proposal)|\(happened ? "yes" : "no")".utf8),
                                      reason: happened ? Words.NeedsYou.Prompt.happened(title: "\(call.title)") : Words.NeedsYou.Prompt.didNotHappen(title: "\(call.title)"), grants: true)
        } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            try await kiff.answerHappened(call.proposal, happened: happened)
        } catch {
            lastError = Words.NeedsYou.Refused.other(why: "\(error.localizedDescription)")
            return false
        }
        unproven.removeAll { $0.proposal == call.proposal }
        await loadUnproven()
        return true
    }

    /// Connect apps as tafs cloud last listed them.
    @Published private(set) var connectApps: TafsCloud.ConnectApps?
    /// The connection started from the list, waiting for its approval sheet.
    @Published var connectStart: TafsCloud.ConnectStart?

    /// Clearing removed tafs' connections is on its way, and how it went, under them in Connect apps.
    @Published private(set) var clearingRemoved = false
    @Published private(set) var clearNote: String?

    /// The search Connect apps last listed, for its next page.
    private var connectAppsQuery = ""
    /// Only the newest search may replace its page, even if an older request ignores cancellation.
    private var connectAppsRequest: UUID?

    /// Reads Connect apps' first page, filtered by what the owner typed.
    func loadConnectApps(_ q: String = "") async {
        guard mode == .kiff else { return }
        // The account's connections too, so a connected app lists each one, with its taf and
        // Disconnect.
        if q.isEmpty { await refreshConnections() }
        let gen = accountGeneration
        let request = UUID()
        connectAppsRequest = request
        // Invalidate pagination for the preceding search immediately.
        if connectAppsQuery != q { connectApps = nil }
        connectAppsQuery = q
        do {
            // This phone's signed grants read, as Setup sends it: tafs cloud then says which
            // removed taf's connections Clear may end.
            let signer = self.signer
            planque.tenant = kiffTenant
            let proof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) })
            let page = try await cloud.connectApps(q, proof: proof)
            guard gen == accountGeneration, request == connectAppsRequest, !Task.isCancelled else { return }
            connectApps = page
        } catch {
            guard gen == accountGeneration, request == connectAppsRequest, !Task.isCancelled else { return }
            lastError = Words.Connect.appsNotRead(why: "\(error.localizedDescription)")
        }
    }

    /// Adds Connect apps' next page, for the same search; an app listed already isn't again.
    func loadMoreConnectApps() async {
        guard mode == .kiff, let shown = connectApps, let cursor = shown.nextCursor else { return }
        let gen = accountGeneration
        let request = connectAppsRequest
        let q = connectAppsQuery
        do {
            let page = try await cloud.connectApps(q, provider: shown.provider, cursor: cursor)
            guard gen == accountGeneration, request == connectAppsRequest,
                  q == connectAppsQuery, connectApps?.nextCursor == cursor else { return }
            var merged = page
            let seen = Set(shown.apps.map(\.entry))
            merged.apps = shown.apps + page.apps.filter { !seen.contains($0.entry) }
            merged.byKey = shown.byKey || page.byKey
            // The removed tafs stay as the first page read them, with its proof: a further page only adds apps.
            merged.removedTafs = shown.removedTafs
            connectApps = merged
        } catch {
            guard gen == accountGeneration, request == connectAppsRequest else { return }
            lastError = Words.Connect.moreAppsCouldNotListed(why: "\(error.localizedDescription)")
        }
    }

    /// The tafs an app can be connected for: on tafs cloud, and managed by this person.
    var connectableTafs: [Taf] {
        tafs.filter { cloudTaf(for: $0.id) != nil && manages($0.id) }
    }

    /// Starts connecting an app from the list for one taf; its approval opens as a sheet over
    /// the list.
    func startConnect(_ app: TafsCloud.ConnectApps.App, for tafID: UUID) async {
        guard let c = cloudTaf(for: tafID) else {
            lastError = Words.Connect.tafNotTafsCloudApps
            return
        }
        let gen = accountGeneration
        do {
            let start = try await cloud.startConnect(app.entry, taf: c.id)
            guard gen == accountGeneration else { return }
            await refreshConnections()
            guard gen == accountGeneration else { return }
            connectStart = start
        } catch {
            guard gen == accountGeneration else { return }
            lastError = Words.Connect.couldNotConnected(vendor: "\(app.name ?? (app.vendor.isEmpty ? app.title : app.vendor))", why: "\(error.localizedDescription)")
        }
    }

    // MARK: The Slack door

    /// The taf's Slack bot connections a Slack door can listen through: connected, its own
    /// agent's, marked by planque's catalog entry.
    func slackBots(for tafID: UUID) -> [PlanqueClient.Grant] {
        guard let agent = kiffAgent(for: tafID) else { return [] }
        return grants.filter { $0.slackBot && $0.connected && $0.request.agent == agent }
    }

    /// The channels a Slack bot connection can see, read through planque.
    func slackChannels(_ grantID: String) async -> [PlanqueClient.Channel]? {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else { return nil }
        let signer = self.signer
        planque.tenant = tenant
        do { return try await planque.channels(grantID: grantID, signedBy: { try signer.signConfirmed($0) }) } catch {
            lastError = Words.Door.slackChannelsCouldNotRead(why: "\(error.localizedDescription)")
            return nil
        }
    }

    /// A Slack door on one channel, in the agreed order: planque subscribes the channel,
    /// approved with Face ID, then tafs cloud makes the door. A door that can't be made takes
    /// the subscription back, so nothing listens without a door.
    func addSlackDoor(_ tafID: UUID, doors: DoorsModel, app: TafsCloud.DoorApp, grant: PlanqueClient.Grant, channel: PlanqueClient.Channel) async -> Bool {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant, let account = grant.account else {
            lastError = Words.Connect.startConnectedToolsFirst(device: "\(ThisDevice.kind)")
            return false
        }
        let where_ = channel.name.isEmpty ? channel.id : "#" + channel.name
        do {
            _ = try await signer.sign(Data("tafs.door.v1|\(tenant)|\(grant.id)|\(channel.id)".utf8),
                                      reason: Words.Door.Slack.letRead(taf: taf(tafID)?.name ?? Words.Door.Slack.theTaf, place: where_), grants: true)
        } catch {
            quietStop = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
            return false
        }
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { planque.signer = nil }
        let trigger: String
        do {
            trigger = try await planque.subscribe(grantID: grant.id, account: account, slug: PlanqueClient.slackMessageTrigger, channels: [channel.id])
        } catch {
            lastError = Words.Door.slackDidNotSubscribe(`where`: "\(where_)", why: "\(error.localizedDescription)")
            return false
        }
        if await doors.addSlack(app, grant: grant.id, channel: channel.id, trigger: trigger) != nil { return true }
        try? await planque.unsubscribe(grantID: grant.id, trigger: trigger)
        return false
    }

    /// Removes a Slack door in the reverse order: planque's subscription first, signed by
    /// this phone, then the door. One with no subscription named is only the door.
    func removeSlackDoor(_ door: TafsCloud.Door, doors: DoorsModel) async {
        if let grant = door.grant, let trigger = door.trigger {
            guard planqueReady, sessionHasKey, let tenant = kiffTenant else {
                lastError = Words.Door.startConnectedToolsFirstSlack(device: "\(ThisDevice.kind)")
                return
            }
            let signer = self.signer
            planque.tenant = tenant
            planque.signer = { try signer.signConfirmed($0) }
            defer { planque.signer = nil }
            do { try await planque.unsubscribe(grantID: grant, trigger: trigger) } catch {
                lastError = Words.Door.slackSubscriptionNotStoppedDoor(why: "\(error.localizedDescription)")
                return
            }
        }
        await doors.remove(door)
    }

    /// Gives an API-key grant its key, as shown, behind Face ID. A grant with a profile needs
    /// KIFF's words for it shown, as approving does.
    /// tafs cloud's words for a key's sheet (docs/secure-connection.md), read with this phone's
    /// signed grants read, as Connect apps is. Nil when they can't be read: the sheet then
    /// draws the app's own lines.
    func connectionSheet(grant: String) async -> TafsCloud.ConnectionSheet? {
        let signer = self.signer
        planque.tenant = kiffTenant
        let proof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) })
        return try? await cloud.connectionSheet(grant: grant, proof: proof)
    }

    func giveKey(grantID: String, shown: String?, key: String) async -> Bool {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else {
            lastError = Words.Connect.startConnectedToolsFirst(device: "\(ThisDevice.kind)")
            return false
        }
        guard let grant = grants.first(where: { $0.id == grantID }), grant.digestMatches, grant.digest == shown else {
            lastError = PlanqueClient.PlanqueError.digestMismatch.errorDescription
            return false
        }
        let words = shownWords(for: grant)
        if grant.profile != nil, words == nil {
            lastError = PlanqueClient.PlanqueError.wordsUnread.errorDescription
            return false
        }
        guard !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        do {
            _ = try await signer.sign(Data("tafs.connect.v1|\(tenant)|\(grant.id)|\(grant.digest)".utf8),
                                      reason: giveKeyPrompt(grant), grants: true)
        } catch {
            quietStop = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
            return false
        }
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { planque.signer = nil }
        do {
            _ = try await planque.giveKey(grant, key: key, words: words)
        } catch {
            lastError = Words.Connect.keyNotGiven(why: "\(error.localizedDescription)")
            return false
        }
        await refreshConnections()
        return true
    }

    /// Each taf's dial as tafs cloud last sent it (`GET /v1/tafs/{id}/knob`), by its id here.
    @Published private(set) var knobs: [UUID: TafsCloud.Knob] = [:]

    /// Reads the taf's dial from tafs cloud.
    func loadKnob(_ tafID: UUID) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID) else { return }
        let gen = accountGeneration, presentation = presentationGeneration
        do {
            var knob = try await cloud.knob(c.id)
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            if let pending = knob.pending {
                do { knob = try await cloud.knob(c.id, version: pending.version) }
                catch let error as TafsCloud.CloudError where error.status == 404 {
                    // It was installed or replaced while reading: refresh its current state.
                    knob = try await cloud.knob(c.id)
                }
            }
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            knobs[tafID] = knob
            if lastErrorFrom == .knob { lastError = nil }
            // Its change is live now: nothing left to switch to.
            if let made = knobChanges[tafID], let live = knob.version, live >= made.version { knobChanges[tafID] = nil }
        } catch {
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            fail(Words.Knob.dialCouldNotRead(why: "\(error.localizedDescription)"), from: .knob)
        }
    }

    /// The face was tapped on a stop whose `tap` says it does something: a door opens or
    /// closes through the doors API, and Run now starts a run, each as today, with no new
    /// version. Fixed size choices make a pending version; setup belongs to Coach.
    func pressKnob(_ tafID: UUID, _ item: TafsCloud.Knob.Item) async {
        guard mode == .kiff, manages(tafID), let c = cloudTaf(for: tafID) else { return }
        if item.ok == "approve" {
            _ = await approveKnobEvent(tafID, item: item)
            return
        } else if item.ok == "door", let door = item.door {
            do { _ = try await cloud.setDoorOpen(c.id, door: door, open: !item.on) } catch {
                lastError = item.on ? Words.Door.notClosed(why: error.localizedDescription) : Words.Door.notOpened(why: error.localizedDescription)
            }
        } else if item.ok == "run" {
            _ = await runNow(c)
        } else if item.ok == "remove_follow_up", let followUp = item.followUp {
            do { try await cloud.removeFollowUp(c.id, followUp: followUp) }
            catch { lastError = Words.Knob.followUpNotRemoved(why: "\(error.localizedDescription)") }
        } else {
            return
        }
        await loadKnob(tafID)
    }

    /// A subscription already signed for at planque but not yet acknowledged by tafs cloud.
    /// It is only a retry receipt: the dial's approval state always comes from the server.
    private struct EventSubscriptionReceipt {
        let version: Int
        let approval: TafsCloud.Knob.Item.Approval
        let subscription: String
    }
    private var eventSubscriptionReceipts: [UUID: [String: EventSubscriptionReceipt]] = [:]
    private var eventApprovals: Set<UUID> = []

    @discardableResult
    func approveKnobEvent(_ tafID: UUID, item: TafsCloud.Knob.Item) async -> Bool {
        guard mode == .kiff, manages(tafID), planqueReady, sessionHasKey,
              let tenant = kiffTenant, let taf = cloudTaf(for: tafID),
              let version = knobs[tafID]?.version, item.kind == "app_event", item.ok == "approve",
              let approval = item.approval, approval.state == "needed",
              knobs[tafID]?.wakes.first(where: { $0.id == item.id }) == item else { return false }
        let gen = accountGeneration
        guard eventApprovals.insert(tafID).inserted else { return false }
        defer { if gen == accountGeneration { eventApprovals.remove(tafID) } }
        lastError = nil
        do {
            // Read the live definition before asking the owner. A draft or stale button
            // must not subscribe different filters. The POST checks the version again.
            let live = try await cloud.knob(taf.id)
            guard gen == accountGeneration else { return false }
            guard live.version == version,
                  let current = live.wakes.first(where: { $0.id == item.id }),
                  let currentApproval = current.approval,
                  currentApproval.grant == approval.grant, currentApproval.event == approval.event,
                  currentApproval.config == approval.config else {
                lastError = Words.Knob.eventChangedReadDialAgain
                return false
            }
            if currentApproval.state == "approved" {
                knobs[tafID] = live
                eventSubscriptionReceipts[tafID]?[item.id] = nil
                return true
            }
            guard currentApproval.state == "needed", current.ok == "approve" else { return false }
            let receipt: EventSubscriptionReceipt
            if let kept = eventSubscriptionReceipts[tafID]?[item.id], kept.version == version, kept.approval == approval {
                receipt = kept
            } else {
                await refreshConnections()
                guard gen == accountGeneration else { return false }
                guard let grant = grants.first(where: { $0.id == approval.grant && $0.connected && $0.request.agent == taf.kiffAgent }),
                      let account = grant.account, !account.isEmpty else {
                    lastError = Words.Knob.connectFirst
                    return false
                }
                guard !approvalBlocked else {
                    lastError = approvalReadiness.words(device: ThisDevice.kind, kind: Biometry.name)
                    return false
                }
                let signer = self.signer
                let filters = try JSONEncoder().encode(approval.config)
                let hash = SHA256.hash(data: filters).map { String(format: "%02x", $0) }.joined()
                quietStop = nil
                do {
                    _ = try await signer.sign(Data("tafs.event.v1|\(tenant)|\(taf.id)|\(version)|\(item.id)|\(approval.grant)|\(approval.event)|\(hash)".utf8),
                                              reason: item.words, grants: true)
                } catch {
                    guard gen == accountGeneration else { return false }
                    quietStop = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
                    return false
                }
                guard gen == accountGeneration else { return false }
                planque.tenant = tenant
                let subscription = try await planque.subscribe(grantID: approval.grant, account: account,
                    slug: approval.event, config: approval.config, signedBy: { [self] bytes in
                        guard gen == accountGeneration else { throw CancellationError() }
                        return try signer.signConfirmed(bytes)
                    })
                guard gen == accountGeneration else { return false }
                receipt = EventSubscriptionReceipt(version: version, approval: approval, subscription: subscription)
                eventSubscriptionReceipts[tafID, default: [:]][item.id] = receipt
            }
            let updated = try await cloud.recordKnobSubscription(taf.id, trigger: item.id,
                subscription: receipt.subscription, version: receipt.version)
            guard gen == accountGeneration else { return false }
            knobs[tafID] = updated
            guard updated.wakes.first(where: { $0.id == item.id })?.approval?.subscription == receipt.subscription,
                  updated.wakes.first(where: { $0.id == item.id })?.approval?.state == "approved" else {
                lastError = Words.Knob.subscriptionMadeDialHasNot
                return false
            }
            eventSubscriptionReceipts[tafID]?[item.id] = nil
            return true
        } catch {
            guard gen == accountGeneration else { return false }
            if error is CancellationError { return false }
            lastError = error.localizedDescription
            return false
        }
    }

    func requestDetail(_ tafID: UUID, run: String) async throws -> TafsCloud.RequestDetail {
        guard mode == .kiff, let taf = cloudTaf(for: tafID) else { throw CancellationError() }
        let generation = accountGeneration
        let detail = try await cloud.request(taf.id, run: run)
        guard generation == accountGeneration else { throw CancellationError() }
        return detail
    }

    /// A fixed size offered by cloud, including all other settings. No app-specific presets.
    func adjustKnob(_ tafID: UUID, item: TafsCloud.Knob.Item, direction: Int) async -> Bool {
        guard manages(tafID), let knob = knobs[tafID],
              knob.acceptsChoices(waitingFor: knobChanges[tafID]?.version),
              let current = TafsCloud.Knob.Ring.allCases.flatMap({ knob.items($0) }).first(where: { $0.id == item.id }),
              current == item, let choice = current.size?.choice(moving: direction) else { return false }
        guard await changeKnob(tafID, { $0 = choice.setting }) else { return false }
        await loadKnob(tafID)
        return true
    }

    /// The dial's last change on each taf, its next version, until the owner switches to it.
    @Published var knobChanges: [UUID: TafsCloud.Knob.Change] = [:]

    /// Changes one thing of the taf's dial: its live `setting` copied, changed, and sent whole
    /// (`PUT …/knob`). tafs cloud makes the next version; nothing runs from it until the
    /// owner switches to it in Versions.
    func changeKnob(_ tafID: UUID, _ change: (inout TafsCloud.Knob.Setting) -> Void) async -> Bool {
        guard mode == .kiff, manages(tafID), let c = cloudTaf(for: tafID), let setting = knobs[tafID]?.setting else { return false }
        var changed = setting
        change(&changed)
        guard changed != setting else { return false }
        let gen = accountGeneration
        do {
            let made = try await cloud.putKnob(c.id, changed)
            guard gen == accountGeneration else { return false }
            knobChanges[tafID] = made
            return true
        } catch {
            guard gen == accountGeneration else { return false }
            lastError = Words.Knob.dialDidNotChange(why: "\(error.localizedDescription)")
            return false
        }
    }

    /// Runs a taf now, outside its schedule. The run's session; nil when it didn't start, when a
    /// Run now for it is on its way, or when its check started no run, which is no failure:
    /// then `runNowWords` holds tafs cloud's words for it, and nothing is said wrong.
    func runNow(_ cloudTaf: TafsCloud.CloudTaf) async -> String? {
        guard runsInFlight.insert(cloudTaf.id).inserted else { return nil }
        defer { runsInFlight.remove(cloudTaf.id) }
        runNowWords[cloudTaf.id] = nil
        let gen = accountGeneration
        do {
            let run = try await cloud.runNow(cloudTaf.id)
            if gen == accountGeneration, let words = run.words { runNowWords[cloudTaf.id] = words }
            return run.session
        } catch let e as TafsCloud.CloudError where Self.waitsForCredits(e) != nil {
            lastError = Self.waitsForCredits(e)
            return nil
        } catch {
            lastError = Words.Taf.runNotStarted(why: "\(error.localizedDescription)")
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
            r.error = Words.Report.runsNotLoaded(why: "\(error.localizedDescription)")
            reports[cloudTaf.id] = r
        }
    }

    /// Removals tafs cloud began and didn't finish (a step failed, or the answer was lost).
    /// Archived tafs aren't listed, so the app finishes these itself on each refresh.
    @Published private(set) var unfinishedRemovals: [TafsCloud.PendingRemoval] = []

    /// The doors tafs cloud offers this account (docs/doors.md): none, and no taf shows Doors.
    @Published private(set) var doorsOffered: [String] = []

    /// The taf here whose tafs cloud id is `cloudID`, when the list has it.
    func tafID(cloud cloudID: String) -> UUID? {
        cloudTafs.first { $0.value.id == cloudID }.flatMap { holderIDs[$0.key] }
    }

    /// The taf behind tafs cloud's id, as the calendar names it, when this person has it listed.
    func tafID(forCloud id: String) -> UUID? {
        holderIDs.first(where: { cloudTafs[$0.key]?.id == id })?.value
    }

    /// Reads the calendar for the selected month's grid (`CalendarMath.range`): one read draws its
    /// day, week and month and the navigator's marks. Another account's read never lands here.
    func loadCalendar(_ state: CalendarState) async {
        let range = CalendarMath.range(state.selected)
        // A screenshot over the sample (`-screen calendar`): no KIFF account to read, so an empty
        // range rather than an error under the header.
        if Screenshots.calendar != nil, mode != .kiff {
            calendarRead = TafsCloud.CalendarRead(from: range.from, to: range.to)
            calendarError = nil
            return
        }
        let gen = accountGeneration
        calendarError = nil
        do {
            let read = try await cloud.calendar(from: range.from, to: range.to)
            guard gen == accountGeneration else { return }
            calendarRead = read
            calendarError = nil
        } catch {
            // A read the calendar cancelled (another range, the view gone) isn't an error.
            guard gen == accountGeneration, !(error is CancellationError), (error as? URLError)?.code != .cancelled else { return }
            calendarError = Words.Home.calendarNotRead(why: "\(error.localizedDescription)")
        }
    }

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
            log(tafID, .done, proposed.summary, amountLine(proposed, Words.Card.withinCard))
        case .held(let reason):
            let now = clock()
            var call = HeldCall(tafID: tafID, proposed: proposed, reason: reason, heldAt: now,
                                expiresAt: now.addingTimeInterval(10 * 60))
            call.nextSteps = tafs[i].card.rules.first { $0.action == proposed.action }?.nextSteps ?? []
            held.insert(call, at: 0)
            tafs[i].state = .waiting
            tafs[i].now = Words.Taf.Now.waitingOn(summary: "\(proposed.summary)")
            log(tafID, .held, Words.Record.held(summary: "\(proposed.summary)"), reason)
            notifyHeld()
        case .refused(let reason):
            tafs[i].now = Words.Taf.Now.refused(summary: proposed.summary)
            log(tafID, .refused, Words.Record.refused(summary: "\(proposed.summary)"), reason)
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

    // MARK: Allow this from now on

    /// "Allow this from now on", open on `call`: tafs cloud's answer, KIFF's draft as this
    /// phone read it, and whether it's what was offered.
    struct AllowSheet: Identifiable, Equatable {
        let call: HeldCall
        var answer: TafsCloud.AllowAnswer?
        var draft: KIFFClient.AgentDraft?
        var matches = false
        var busy = false
        var error: String?
        /// "Let this one go out too": the held call goes with the same signature.
        var sendHeld = true
        /// The rounded figure was asked for: the sheet no longer offers to round.
        var rounded = false
        var id: UUID { call.id }
    }

    /// "Allow this from now on" on `call`: for someone who manages Cards, on a hold a Card
    /// change can lift, from KIFF, once the service says the feature is live. Decided from
    /// the held call and the account's capabilities, with no request.
    func offersAllow(_ call: HeldCall) -> Bool {
        mode == .kiff && Self.allowIsLive(kiffAccount?.capabilities) && canManageCards && cloudTaf(for: call.tafID) != nil && HoldAllow.allowable(call)
    }

    /// Whether "Allow this from now on" is live: the account's capabilities name `holds.allow`.
    /// The service says when, and the app never guesses: a list without it, or no list at all,
    /// shows no button, so nobody taps into a route that isn't there yet.
    nonisolated static func allowIsLive(_ capabilities: [String]?) -> Bool {
        capabilities?.contains("holds.allow") ?? false
    }

    /// "Allow this from now on" tapped: the sheet opens, and tafs cloud makes the offer now.
    func openAllow(_ call: HeldCall) {
        allow = AllowSheet(call: call, busy: true)
        Task { await loadAllow(round: false) }
    }

    /// Round up to the figure the offer named: tafs cloud makes the rounded draft, which is
    /// shown and checked the same way. The app never rounds.
    func roundAllow() {
        guard let open = allow, !open.busy else { return }
        allow?.busy = true
        allow?.error = nil
        Task { await loadAllow(round: true) }
    }

    /// tafs cloud's offer for the open sheet's held call, then KIFF's draft, read by this
    /// phone. A late answer for a call whose sheet is no longer open changes nothing, and
    /// rounding up keeps the person's switch.
    @discardableResult
    func loadAllow(round: Bool) async -> Bool {
        guard let open = allow else { return false }
        let call = open.call
        guard let taf = cloudTaf(for: call.tafID)?.id, call.kiffID != nil else {
            allow?.busy = false
            allow?.error = Words.NeedsYou.answerNotTaken
            return false
        }
        let gen = accountGeneration
        do {
            let read = try await HoldAllow.read(cloud: cloud, kiff: kiff, taf: taf, call: call, round: round)
            guard gen == accountGeneration, var now = allow, now.call.id == call.id else { return false }
            now.answer = read.answer
            now.draft = read.draft
            now.matches = read.matches
            now.busy = false
            now.error = nil
            if !round { now.sendHeld = read.answer.offer?.sendHeld ?? true }
            now.rounded = round
            allow = now
            return read.matches
        } catch {
            guard gen == accountGeneration, allow?.call.id == call.id else { return false }
            allow?.busy = false
            allow?.error = Words.Version.cardNotRead(why: "\(error.localizedDescription)")
            return false
        }
    }

    /// One signature: KIFF's draft as this phone read it, confirmed with the approval key,
    /// with the held call when the switch is on, so KIFF lets it go under the same signature.
    /// With it off, the Card changes and the call still waits for its own answer.
    @discardableResult
    func confirmAllow() async -> Bool {
        guard let open = allow, let draft = open.draft, open.matches, !open.busy, !draft.confirmed, let tenant = kiffTenant else { return false }
        var hold: (id: String, version: Int)?
        if open.sendHeld {
            // The call goes only as the person saw it: without its version, nothing is signed.
            guard let id = open.call.kiffID, let version = open.call.accountingVersion else {
                allow?.error = Words.NeedsYou.answerNotTaken
                return false
            }
            hold = (id, version)
        }
        let gen = accountGeneration
        allow?.busy = true
        allow?.error = nil
        let signer = self.signer
        do {
            _ = try await signer.sign(Data("tafs.cards.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8), reason: Words.NeedsYou.allowTitle, grants: true)
        } catch {
            if gen == accountGeneration, allow?.call.id == open.call.id {
                allow?.busy = false
                quietStop = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
            }
            return false
        }
        guard gen == accountGeneration else { return false }
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            if let hold {
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version, hold: hold.id, accountingVersion: hold.version)
            } else {
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version)
            }
        } catch {
            if gen == accountGeneration, allow?.call.id == open.call.id {
                allow?.busy = false
                allow?.error = Words.Version.cardNotRead(why: "\(error.localizedDescription)")
            }
            return false
        }
        guard gen == accountGeneration else { return false }
        allow = nil
        // The Card changed, and with the switch on the call went: Needs you and the taf read again.
        await refresh()
        return true
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
    /// What of the taf's live Cards' totals is from calls the tool refused, in KIFF's words:
    /// they count until KIFF can confirm nothing moved.
    func pendingLines(_ tafID: UUID) -> [String] {
        (taf(tafID)?.shownCards ?? []).filter(\.countsForReal).flatMap(\.pending)
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
        mode != .kiff || manages(taf.id) ? taf.card.issuedBy : (accountLabel ?? Words.Card.issuedByAccount)
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
        call.kiffID.map { kiff.answerPage(for: $0) }
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
            lastError = Words.NeedsYou.installFromCard
            return
        }
        guard canAnswer(call) else {
            lastError = kiff.serverVerifiesSignatures && !sessionHasKey
                ? Words.NeedsYou.signInAgainToAnswer
                : Words.NeedsYou.answerInKiff
            return
        }
        if call.kiffID == nil, clock() > call.expiresAt {
            resolve(call)
            log(call.tafID, .refused, Words.Record.expired(summary: "\(call.proposed.summary)"), Words.Record.expiredDetail)
            save()
            return
        }
        // Approving, or raising a Card, needs the approval key KIFF holds: without it, only a
        // new sign-in lets this phone approve (kiff/tafs#233).
        if approvalKeyLost, call.kiffID != nil, answer == .approveOnce || answer == .extraRoom {
            lastError = KIFFClient.approvalKeyLost
            return
        }
        let reason = Words.NeedsYou.Prompt.answer(summary: call.proposed.summary)
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
            // Closed by the person: cancelled, and the request stays in Needs you.
            if let words = ApprovalReadiness.cancelled(error) {
                lastError = words
                return
            }
            // A prompt closed without a recognized face or finger, while the key is still good.
            lastError = sessionHasApprovalKey && (answer == .approveOnce || answer == .extraRoom)
                ? KIFFClient.notRecognized : Words.Common.notSentUnconfirmed
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
            decide(call, .approved, Words.Record.approvedOnce(at: "\(when)", summary: "\(call.proposed.summary)"),
                   amountLine(call.proposed, Words.Receipt.approvedOnceByLower(owner: "\(ownerName)")), signature: signature)
            if let i { tafs[i].now = call.proposed.summary }
        case .nextStep(let step):
            decide(call, .refused, Words.Record.refusedNextStep(at: "\(when)", summary: "\(call.proposed.summary)"),
                   Words.Record.refusedNextStepDetail(step: "\(step)"), signature: signature)
            if let i { tafs[i].now = step }
        case .extraRoom:
            decide(call, .approved, Words.Record.extraRoom(at: "\(when)", summary: "\(call.proposed.summary)"),
                   Words.Card.cardGotRoomCallNeeded, signature: signature)
            if let i { tafs[i].now = call.proposed.summary }
        case .refuse:
            decide(call, .refused, Words.Record.refusedByYou(at: "\(when)", summary: "\(call.proposed.summary)"), call.reason, signature: signature)
            if let i { tafs[i].now = Words.Taf.Now.refused(summary: call.proposed.summary) }
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
                lastError = Words.Card.revokeFailed(why: "\(error.localizedDescription)")
                return
            }
        }
        guard let j = tafs.firstIndex(where: { $0.id == tafID }) else { return }
        tafs[j].card.revoked = true
        tafs[j].state = .revoked
        tafs[j].now = Words.Taf.Now.cardRevoked
        let dropped = held.filter { $0.tafID == tafID }
        held.removeAll { $0.tafID == tafID }
        let detail = dropped.isEmpty ? Words.Record.Revoked.noNewAction : Words.Record.Revoked.refusedWaiting(count: dropped.count)
        log(tafID, .revoked, Words.Record.Revoked.title(taf: "\(tafs[j].name)"), detail)
        notifyHeld()
        save()
    }

    /// Removes a taf for good, after Face ID: KIFF revokes its Cards first, so it can't act
    /// whatever happens next; then tafs cloud stops it, deletes its runs, chats, memory and
    /// key, and frees its name. Returns what to tell the owner, nil when nothing was done.
    ///
    /// The approval key signs the DELETE itself (`kiff.request.v1` over `DELETE /v1/tafs/{id}`
    /// and its empty body, sent as `KIFF-Device-Signature`), behind Face ID or Touch ID each
    /// time, as a drive's start is: tafs cloud asks KIFF whether this phone signed it.
    func removeTaf(_ tafID: UUID) async -> String? {
        guard mode == .kiff, let taf = taf(tafID), let c = cloudTaf(for: tafID) else { return nil }
        guard let tenant = kiffTenant else { return Words.Error.signInFirstOnDevice(device: "\(ThisDevice.kind)") }
        // The session this removal belongs to: checked after every wait, so nothing of it
        // goes out under another account's sign-in.
        let gen = accountGeneration
        let signedAt = SigningTime.next()
        let message = KIFFClient.requestMessage(tenant: tenant, method: "DELETE", uri: cloud.tafPath(c.id), body: Data(), signedAt: signedAt)
        let signature: String
        do {
            signature = try await signer.signApproval(message, reason: Words.Taf.Remove.prompt(taf: "\(taf.name)"))
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
        // Its connections go with it, ended by tafs cloud as part of the removal: every status,
        // a waiting or failed one too, and one another taf still uses stays. The phone no longer
        // disconnects them itself, which signed one more request each and missed those.
        let revoked = cardsLeft == 0
            ? Words.Taf.Remove.cardsRevoked
            : Words.Card.kiffDidNotRevokeCards(cardsLeft: "\(cardsLeft)")
        var cards = revoked

        let removal: TafsCloud.Removal
        do { removal = try await cloud.remove(c.id, signature: "t=\(signedAt),v1=\(signature)") } catch {
            guard gen == accountGeneration else { return nil }
            return Words.Taf.Remove.cloudDidNotAnswer(cards: "\(cards)", why: "\(error.localizedDescription)")
        }
        guard gen == accountGeneration else { return nil }
        if cardsLeft == 0, let i = tafs.firstIndex(where: { $0.id == tafID }) {
            tafs[i].card.revoked = true
            tafs[i].state = .revoked
            tafs[i].now = Words.Taf.Now.removed
        }
        // What tafs cloud did with its connections: ended (said once), or not (named among the
        // steps left). The list of connections is read again either way, with no signature.
        cards = Self.removalCards(revoked, removal)
        await refreshConnections()
        guard gen == accountGeneration else { return nil }
        guard removal.done else {
            let failed = removal.failed.map(Self.removalStep).joined(separator: ", ")
            return Words.Taf.Remove.notFinishedAfter(cards: "\(cards)", failed: "\(failed)")
        }
        if let agent = holderIDs.first(where: { $0.value == tafID })?.key { cloudTafs[agent] = nil }
        log(tafID, .revoked, Words.Record.Removed.title(taf: "\(taf.name)"), Words.Record.Removed.detail)
        save()
        return Words.Connect.scheduleChatsRunReportsMemory(cards: "\(cards)", taf: "\(taf.name)")
    }

    /// Discards a taf being set up (Home's Discard, for the person whose setup it was): its Cards
    /// are revoked in KIFF first, as before a removal, then tafs cloud is asked, signed as a
    /// removal is (`kiff.request.v1` over `POST` its discard path and the empty body, with the
    /// approval key, behind Face ID). Home is read again: the item is gone. Nil once done;
    /// else what to tell the person.
    func discardSettingUp(_ tafID: UUID) async -> String? {
        guard mode == .kiff, let taf = settingUp.first(where: { $0.id == tafID }), let words = taf.discard,
              let agent = holderIDs.first(where: { $0.value == tafID })?.key, !agent.hasPrefix("setting-up:") else { return nil }
        guard let tenant = kiffTenant else { return Words.Error.signInFirstOnDevice(device: "\(ThisDevice.kind)") }
        let gen = accountGeneration
        let signedAt = SigningTime.next()
        let message = KIFFClient.requestMessage(tenant: tenant, method: "POST", uri: cloud.discardPath(agent), body: Data(), signedAt: signedAt)
        let signature: String
        do {
            signature = try await signer.signApproval(message, reason: words.title.isEmpty ? words.button : words.title)
        } catch { return nil }
        guard gen == accountGeneration else { return nil }
        var cardsLeft = 0
        // Home's Cards, and any a refused Discard named that Home didn't.
        var cardIDs = taf.kiffCardIDs
        for id in discardCardsLeft[agent] ?? [] where !cardIDs.contains(id) { cardIDs.append(id) }
        for cardID in cardIDs {
            guard gen == accountGeneration else { return nil }
            do { try await kiff.revoke(cardID: cardID) } catch { cardsLeft += 1 }
        }
        guard gen == accountGeneration else { return nil }
        do {
            try await cloud.discardSettingUp(agent, signature: "t=\(signedAt),v1=\(signature)")
        } catch let refused as TafsCloud.CloudError where refused.code == "cards_in_force" {
            // KIFF still holds some of its Cards in force, so nothing ended: how many are left.
            // The ones tafs cloud named are kept, so Discard again revokes them too, and Home is
            // read again: it never fails the same way twice.
            guard gen == accountGeneration else { return nil }
            if !refused.cards.isEmpty { discardCardsLeft[agent] = refused.cards }
            await refresh()
            return Words.Card.kiffDidNotRevokeCards(cardsLeft: "\(max(refused.cards.count, cardsLeft, 1))")
        } catch {
            guard gen == accountGeneration else { return nil }
            return (error as? TafsCloud.CloudError).map { $0.words ?? $0.message } ?? error.localizedDescription
        }
        guard gen == accountGeneration else { return nil }
        discardCardsLeft[agent] = nil
        settingUp.removeAll { $0.id == tafID }
        await refresh()
        // tafs cloud took it only once KIFF held none of its Cards in force: none is left to say.
        return nil
    }

    /// What removing this taf takes with it, in tafs cloud's words, for the confirmation before
    /// Face ID: which apps go with it, and which stay for another taf. Reading it ends nothing.
    /// None when tafs cloud has no such words (an older one, an answer that didn't come, the
    /// account changed meanwhile): the confirmation then says only that the taf goes.
    func removalWords(_ tafID: UUID) async -> String? {
        guard mode == .kiff, let c = cloudTaf(for: tafID) else { return nil }
        let gen = accountGeneration
        guard let preview = try? await cloud.removalPreview(c.id), gen == accountGeneration else { return nil }
        return preview.words
    }

    /// The Remove confirmation's message: what always goes, then tafs cloud's words for the
    /// apps that go with it or stay, when it sent some.
    nonisolated static func removeBody(_ body: String, goes words: String?) -> String {
        guard let words, !words.isEmpty else { return body }
        return body + "\n\n" + words
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

    /// The removal's first words: what became of its Cards, then "Its access to apps is
    /// removed." only when tafs cloud says it ended its connections.
    nonisolated static func removalCards(_ revoked: String, _ removal: TafsCloud.Removal) -> String {
        revoked + (removal.removed.contains("connections") ? Words.Connect.connectionsDisconnected : "")
    }

    nonisolated static func removalStep(_ step: String) -> String {
        switch step {
        case "connections": return Words.Taf.Remove.stepConnections
        case "schedule": return Words.Taf.Remove.stepSchedule
        case "history": return Words.Taf.Remove.stepHistory
        case "memory": return Words.Taf.Remove.stepMemory
        case "key": return Words.Taf.Remove.stepKey
        case "name": return Words.Taf.Remove.stepName
        default: return step
        }
    }

    /// Pauses or resumes a real taf in tafs cloud: no run starts while it is paused.
    /// Pausing doesn't touch its Cards; revoke a Card to stop its authority.
    func setPaused(_ tafID: UUID, _ paused: Bool) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID),
              let agent = holderIDs.first(where: { $0.value == tafID })?.key else { return }
        let gen = accountGeneration
        lastError = nil
        do {
            let updated = try await cloud.setPaused(c.id, paused)
            guard gen == accountGeneration else { return }
            cloudTafs[agent] = updated
            if let i = tafs.firstIndex(where: { $0.id == tafID }), tafs[i].state != .waiting, tafs[i].state != .revoked {
                tafs[i].state = updated.paused ? .paused : .working
                tafs[i].now = updated.paused ? Words.Taf.Now.pausedByYou : updated.nextRun.map { Words.Taf.Now.nextRun(time: "\($0.shortTime)") } ?? Words.Taf.Now.backToWork
            }
        } catch let error as TafsCloud.CloudError where error.status == 502 && paused {
            // Its own schedule is paused, but a start may still come: say so plainly.
            guard gen == accountGeneration else { return }
            lastError = Self.notEveryStartPaused
        } catch let error as TafsCloud.CloudError {
            guard gen == accountGeneration else { return }
            lastError = error.words ?? error.message
        } catch {
            guard gen == accountGeneration else { return }
            lastError = paused ? Words.Taf.Schedule.notPaused(why: error.localizedDescription) : Words.Taf.Schedule.notResumed(why: error.localizedDescription)
        }
    }

    static let notEveryStartPaused = Words.Setup.notEveryPaused

    /// The places tafs cloud delivers its results to itself, set at once (`PUT …/results`).
    @discardableResult
    func setResultPlaces(_ tafID: UUID, _ places: [String]) async -> Bool {
        guard mode == .kiff, manages(tafID), !places.isEmpty, let c = cloudTaf(for: tafID),
              let agent = holderIDs.first(where: { $0.value == tafID })?.key else { return false }
        let gen = accountGeneration
        do {
            let updated = try await cloud.putResults(c.id, places: places)
            guard gen == accountGeneration else { return false }
            cloudTafs[agent] = updated
            await loadKnob(tafID)
            return true
        } catch {
            guard gen == accountGeneration else { return false }
            lastError = Words.Setup.resultsNotChanged(why: "\(error.localizedDescription)")
            return false
        }
    }

    /// A follow-up the taf set itself, removed before it fires.
    func removeFollowUp(_ tafID: UUID, _ followUp: String) async {
        guard mode == .kiff, manages(tafID), let c = cloudTaf(for: tafID) else { return }
        do { try await cloud.removeFollowUp(c.id, followUp: followUp) }
        catch { lastError = Words.Knob.followUpNotRemoved(why: "\(error.localizedDescription)") }
        await loadKnob(tafID)
    }

    func togglePause(_ tafID: UUID) {
        guard mode == .sample, let i = tafs.firstIndex(where: { $0.id == tafID }), tafs[i].state != .revoked else { return }
        tafs[i].state = tafs[i].state == .paused ? .working : .paused
        tafs[i].now = tafs[i].state == .paused ? Words.Taf.Now.pausedByYou : Words.Taf.Now.backToWork
        save()
    }

    // MARK: Halt

    /// Each halt as last read, by the taf's tafs cloud id, "" for the
    /// whole account.
    @Published private(set) var halts: [String: TafsCloud.Halt] = [:]
    @Published private(set) var haltErrors: [String: String] = [:]
    var resumeConfirmationWait: @MainActor () async throws -> Void = { try await Task.sleep(nanoseconds: 10_000_000_000) }

    func haltError(_ tafID: UUID?) -> String? { haltKey(tafID).flatMap { haltErrors[$0] } }

    private func recordHaltError(_ key: String, _ words: String) {
        haltErrors[key] = words
        lastError = words
    }

    /// Whether Home offers the account's halt: to someone who manages Cards, while a taf runs,
    /// and whenever the account is halted or halting, tafs or not, since that row is where it
    /// is resumed from.
    var showsAccountHalt: Bool {
        mode == .kiff && canManageCards && (!cloudTafs.isEmpty || haltState(nil).state != .running)
    }

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
        haltErrors[key] = nil
        let gen = accountGeneration
        do {
            let h = try await cloud.halt(key.isEmpty ? nil : key)
            guard gen == accountGeneration else { return }
            halts[key] = h
            // The list reads it halted at once, not at its next read (kiff/tafs#236, item 13).
            await refresh()
        } catch {
            if gen == accountGeneration { recordHaltError(key, tafID == nil ? Words.Taf.Halt.notHaltedAll(why: error.localizedDescription) : Words.Taf.Halt.notHalted(why: error.localizedDescription)) }
        }
    }

    // MARK: Plan & usage (pricing phase 2, docs/accounts.md › Credits and plans)

    /// Reads Plan & usage: the balance and this period's credits from tafs cloud, KIFF's usage
    /// read for the store and the requests, and the period before's credits again once it has
    /// been read. Each read stands alone: one that fails keeps what was read, and says why.
    func refreshUsage() async {
        switch mode {
        case .signedOut:
            usage = nil; balance = nil; balanceStale = false; credits = nil; previousCredits = nil; tafCredits = [:]
            creditsError = nil; previousCreditsError = nil; tafCreditsError = [:]; balanceError = nil
        case .sample:
            usage = Self.sampleUsage(); usageError = nil
            balance = Self.sampleBalance; balanceError = nil
            credits = Self.sampleCredits(now: clock())
            if previousCredits != nil { previousCredits = Self.sampleCredits(now: clock(), previous: true) }
        case .kiff:
            let gen = accountGeneration
            let againPrevious = previousCredits != nil || previousCreditsError != nil
            async let balanceRead = balanceResult()
            async let creditsRead = creditsResult(previous: false)
            async let previousRead = previousResult(if: againPrevious)
            let usageRead: Result<KIFFClient.Usage, Error>
            do { usageRead = .success(try await kiff.usage()) } catch { usageRead = .failure(error) }
            let (b, c, p) = await (balanceRead, creditsRead, previousRead)
            guard gen == accountGeneration else { return }
            applyBalance(b)
            applyCredits(c)
            if let p { applyPreviousCredits(p) }
            switch usageRead {
            case .success(let read):
                usage = read; usageError = nil
                if let store = read.store { await purchases.load(store.products) }
            case .failure(let error):
                usageError = Words.Billing.planCouldNotReadKiff(why: "\(error.localizedDescription)")
            }
        }
    }

    /// Reads the period before's credits: when it's first asked for, and again on each refresh
    /// once it has been (never kept for good).
    func readPreviousCredits() async {
        switch mode {
        case .signedOut: previousCredits = nil
        case .sample: previousCredits = Self.sampleCredits(now: clock(), previous: true)
        case .kiff:
            let gen = accountGeneration
            let read = await creditsResult(previous: true)
            guard gen == accountGeneration else { return }
            applyPreviousCredits(read)
        }
    }

    private func applyPreviousCredits(_ read: Result<TafsCloud.Credits?, Error>) {
        switch read {
        case .success(let c): previousCredits = c; previousCreditsError = nil
        case .failure(let e): previousCreditsError = Self.creditsWords(e)
        }
    }

    /// The period before's read, only when `read`: once it has been read, never kept for good.
    private func previousResult(if read: Bool) async -> Result<TafsCloud.Credits?, Error>? {
        guard read else { return nil }
        return await creditsResult(previous: true)
    }

    /// A credits read, kept with its failure: nil for a tafs cloud without it (404).
    private func creditsResult(previous: Bool) async -> Result<TafsCloud.Credits?, Error> {
        do { return .success(try await cloud.credits(previous: previous)) } catch { return .failure(error) }
    }

    /// This period's read: a tafs cloud without it shows no panel; a failure keeps what was
    /// read, and says why in tafs cloud's words.
    private func applyCredits(_ read: Result<TafsCloud.Credits?, Error>) {
        switch read {
        case .success(let c): credits = c; creditsError = nil
        case .failure(let e): creditsError = Self.creditsWords(e)
        }
    }

    /// Why credits couldn't be read: tafs cloud's words when it gives them.
    static func creditsWords(_ e: Error, what: String = Words.Usage.credits) -> String {
        (e as? TafsCloud.CloudError)?.words ?? Words.Common.couldNotReadWhy(what: "\(what)", why: "\(e.localizedDescription)")
    }

    /// Reads one taf's credits and its newest runs in a period; with `more`, the next page,
    /// added after the runs already read.
    func readTafCredits(_ taf: String, previous: Bool = false, more: Bool = false) async {
        guard mode == .kiff else { return }
        let key = CreditsKey(taf: taf, previous: previous)
        let after = more ? tafCredits[key]?.next : nil
        if more, after == nil { return }
        let gen = accountGeneration
        let read: TafsCloud.TafCredits
        do {
            guard let got = try await cloud.credits(taf: taf, previous: previous, after: after) else {
                guard gen == accountGeneration else { return }
                tafCreditsError[key] = nil
                return
            }
            read = got
        } catch {
            guard gen == accountGeneration else { return }
            tafCreditsError[key] = Self.creditsWords(error, what: more ? Words.Usage.moreRuns : Words.Work.itsRuns)
            return
        }
        guard gen == accountGeneration else { return }
        tafCreditsError[key] = nil
        if more, var known = tafCredits[key] {
            let seen = Set(known.items.map(\.run))
            known.items += read.items.filter { !seen.contains($0.run) }
            known.next = read.next
            tafCredits[key] = known
        } else {
            tafCredits[key] = read
        }
    }

    /// The sample's credits: its tafs' words, as tafs cloud writes them.
    static func sampleCredits(now: Date, previous: Bool = false) -> TafsCloud.Credits {
        if previous {
            return TafsCloud.Credits(period: nil, credits: 2310, runs: 126, words: Words.Sample.Credits.lastPeriod,
                                     tafs: [.init(taf: "sample-refunds", credits: 1480, runs: 74, words: Words.Sample.Credits.refundsLast),
                                            .init(taf: "sample-invoices", credits: 830, runs: 52, words: Words.Sample.Credits.invoicesLast)],
                                     note: Self.sampleCreditsNote)
        }
        return TafsCloud.Credits(period: nil, credits: 1240, runs: 69, words: Words.Sample.Credits.thisPeriod,
                                 tafs: [.init(taf: "sample-refunds", credits: 820, runs: 41, words: Words.Sample.Credits.refundsThis),
                                        .init(taf: "sample-invoices", credits: 420, runs: 28, words: Words.Sample.Credits.invoicesThis)],
                                 notCounted: .init(runs: 9, words: Words.Sample.Credits.notCounted,
                                                   lines: [.init(why: "coach", runs: 6, words: Words.Sample.Credits.notCountedCoach),
                                                           .init(why: "trial", runs: 2, words: Words.Sample.Credits.notCountedTrials),
                                                           .init(why: "failed", runs: 1, words: Words.Sample.Credits.notCountedFailed)]),
                                 note: Self.sampleCreditsNote)
    }

    static let sampleCreditsNote = Words.Sample.Credits.note

    /// The sample's balance, as tafs cloud words it, the same as Android's: Solo, about half the
    /// period's credits left.
    static let sampleBalance = TafsCloud.Balance(
        balance: .init(state: "ok", meterPct: 48, words: Words.Sample.Credits.balanceWords,
                       detail: Words.Sample.Credits.balanceDetail),
        planLine: Words.Sample.Credits.planLine, planTone: "ok", homeLine: nil)

    /// A balance read, kept with its failure: nil for a tafs cloud without it (404).
    private func balanceResult() async -> Result<TafsCloud.Balance?, Error> {
        do { return .success(try await cloud.balance()) } catch { return .failure(error) }
    }

    /// A read that works is the balance now, and a raise's stale state is over. One that fails
    /// keeps what was read, and says why in tafs cloud's words.
    private func applyBalance(_ read: Result<TafsCloud.Balance?, Error>) {
        switch read {
        case .success(let b): balance = b; balanceError = nil; balanceStale = false
        case .failure(let e): balanceError = Self.creditsWords(e, what: Words.Usage.theBalance)
        }
    }

    /// The balance alone, for Settings' Plan card and Home's line, with each refresh: quietly,
    /// a failure keeps what was read.
    private func refreshPlan(gen: Int) async {
        let read = await balanceResult()
        guard gen == accountGeneration else { return }
        applyBalance(read)
    }

    /// The balance read again, signing nothing: after a raise whose read failed.
    func readBalance() async {
        guard mode == .kiff else { return }
        let gen = accountGeneration
        let read = await balanceResult()
        guard gen == accountGeneration else { return }
        applyBalance(read)
    }

    /// The codes a chat, Coach or Run now is refused with while the tafs wait for credits.
    static let creditsCodes: Set<String> = ["credits_used_up", "limit_reached", "no_plan"]

    /// tafs cloud's words for a refusal because the tafs wait for credits (409 with a credits
    /// code); nil for any other error.
    static func waitsForCredits(_ e: TafsCloud.CloudError) -> String? {
        guard e.status == 409, let code = e.code, creditsCodes.contains(code) else { return nil }
        return e.words ?? e.message
    }

    // MARK: Buying, raising the limit, and asking an admin

    /// Buys Solo through the App Store, for this account.
    func subscribeSolo() async {
        guard let store = usage?.store else { return }
        await buy(store.soloProduct, request: nil)
    }

    /// Buys a pack of credits through the App Store; with a member's request, it answers it.
    func buyPack(_ pack: KIFFClient.Usage.Store.Pack, answering request: KIFFClient.PackRequest? = nil) async {
        await buy(pack.appleProduct, request: request)
    }

    private func buy(_ product: String, request: KIFFClient.PackRequest?) async {
        planMessage = nil
        guard mode == .kiff, let token = usage?.store?.appAccountToken else {
            planMessage = Words.Billing.onlySomeoneWhoManagesAccount
            return
        }
        switch await purchases.buy(product, accountToken: token, requestID: request?.id) {
        case .done: planMessage = nil
        case .cancelled, .nothingToRestore: break
        case .pending: planMessage = Words.Billing.pendingCredits
        case .notRecorded: planMessage = Words.Billing.boughtNotRecorded
        case .notAdded(let why), .needsSupport(let why): planMessage = why
        case .failed(let why): planMessage = Words.Billing.notBought(why: "\(why)")
        }
        await refreshUsage()
    }

    /// The packs Plan & usage lists (docs/accounts.md): the First month pack first, only while
    /// tafs cloud says it can be bought, then the packs that can be bought again.
    var offeredPacks: [KIFFClient.Usage.Store.Pack] {
        usage?.store?.offered(firstMonth: balance?.balance.buy?.firstMonthReady == true) ?? []
    }

    /// Whether Plan & usage shows the Credits panel: tafs cloud's word, and yes where it
    /// doesn't say or the balance isn't read.
    var showsCreditsPanel: Bool { balance?.balance.creditsPanel != false }

    /// Brings back this Apple account's purchases, and sends each to KIFF. One that finds
    /// nothing says so.
    func restorePurchases() async {
        planMessage = nil
        guard mode == .kiff else { return }
        switch await purchases.restore() {
        case .done: planMessage = Words.Billing.restored
        case .nothingToRestore: planMessage = Words.Billing.nothingToRestore
        case .notRecorded: planMessage = Words.Billing.restoredNotRecorded
        case .notAdded(let why), .needsSupport(let why): planMessage = why
        case .failed(let why): planMessage = Words.Billing.notRestored(why: "\(why)")
        default: break
        }
        await refreshUsage()
    }

    /// Asks the account's admins, with a reason: for a raise of the spend limit, or for a pack.
    func askAdmin(raise: Bool, pack: KIFFClient.Usage.Store.Pack? = nil, reason: String) async -> Bool {
        planMessage = nil
        guard mode == .kiff else { return false }
        do {
            _ = try await kiff.askAdmin(raise: raise, pack: raise ? nil : pack?.id,
                                        reason: reason.trimmingCharacters(in: .whitespacesAndNewlines))
            await refreshUsage()
            return true
        } catch {
            planMessage = Words.Billing.Request.notSent(why: "\(error.localizedDescription)")
            return false
        }
    }

    /// Takes back this person's request.
    func withdrawRequest(_ request: KIFFClient.PackRequest) async {
        planMessage = nil
        guard mode == .kiff else { return }
        do {
            _ = try await kiff.withdrawRequest(request.id)
        } catch {
            planMessage = Words.Billing.Request.notWithdrawn(why: "\(error.localizedDescription)")
        }
        await refreshUsage()
    }

    /// Raises the spend limit one step (Company), behind Face ID: the act of someone who
    /// manages billing, which KIFF takes only signed by this phone, and a replayed signature is
    /// refused, so one tap is one step. Then the balance is read again, so the screen says what
    /// it is now. Nil once raised; else why not, empty when the prompt was closed.
    func raiseLimit() async -> String? {
        guard mode == .kiff, let tenant = kiffTenant else { return Words.Usage.RaiseLimit.signInFirst }
        guard sessionHasKey else { return Words.SignIn.againToSignDevice(device: "\(ThisDevice.kind)") }
        // The last raise went through, and the balance it changed hasn't been read since:
        // another tap would raise it again for a balance the screen no longer shows.
        guard !balanceStale else { return Self.readBalanceFirst }
        guard let from = balance?.balance.raise?.fromCents else { return Self.noRaise }
        // One raise at a time, from the tap: a fast second tap asks for no second Face ID.
        guard !raising else { return "" }
        raising = true
        defer { raising = false }
        let gen = accountGeneration
        do { _ = try await signer.sign(Data("tafs.raise-limit.v1|\(tenant)|\(from)".utf8), reason: Words.Usage.RaiseLimit.prompt) } catch { return "" }
        guard gen == accountGeneration else { return "" }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            try await kiff.raiseLimit(from: from)
        } catch let e as KIFFClient.KIFFError where Self.kiffCode(e) == "limit_changed" {
            // Someone raised it meanwhile, or this screen was stale: read the balance again, which
            // offers the raise from the limit it is now.
            guard gen == accountGeneration else { return "" }
            await readBalance()
            return Self.limitChanged
        } catch let e as KIFFClient.KIFFError where Self.kiffCode(e) == "limit_ceiling" {
            guard gen == accountGeneration else { return "" }
            return Self.limitCeiling
        } catch {
            guard gen == accountGeneration else { return "" }
            return Words.Usage.RaiseLimit.notRaised(why: "\(error.localizedDescription)")
        }
        // A switch while the PUT was out: its answer is the old account's, and changes nothing here.
        guard gen == accountGeneration else { return "" }
        // It went through. Until the balance is read again, the screen says so and offers only a read.
        balanceStale = true
        await readBalance()
        await refreshUsage()
        return nil
    }

    static let readBalanceFirst = Words.Card.limitRaisedReadBalanceAgain
    static let noRaise = Words.Card.limitCanNotRaisedRight
    static let limitChanged = Words.Usage.limitChanged
    static let limitCeiling = Words.Usage.limitCeiling

    /// KIFF's code for a 409 refusal of a raise (`limit_changed`, `limit_ceiling`), as its
    /// `reason` or its `error`.
    static func kiffCode(_ e: KIFFClient.KIFFError) -> String? {
        guard case .server(409, let message, let reason) = e else { return nil }
        return reason ?? message
    }

    /// A member's request for a raise, answered by raising the limit: why not, if it wasn't.
    func answerRaise() async {
        planMessage = nil
        if let why = await raiseLimit(), !why.isEmpty { planMessage = why }
    }

    /// Declines a member's request, with a note: the admin's answer, behind Face ID.
    func declineRequest(_ request: KIFFClient.PackRequest, note: String) async -> Bool {
        planMessage = nil
        guard mode == .kiff, let tenant = kiffTenant else { return false }
        guard sessionHasKey else {
            planMessage = Words.SignIn.againToSign
            return false
        }
        let gen = accountGeneration
        do { _ = try await signer.sign(Data("tafs.decline-request.v1|\(tenant)|\(request.id)".utf8), reason: Words.Billing.Request.declinePrompt) } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            _ = try await kiff.declineRequest(request.id, note: note.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            guard gen == accountGeneration else { return false }
            planMessage = Words.Billing.Request.notDeclined(why: "\(error.localizedDescription)")
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

    /// The Record entry of a call, by KIFF's call key.
    func recordEntry(forCall key: String) -> RecordEntry? {
        record.first { $0.entityID == key }
    }

    /// The sample's KIFF read: no store, nothing billed, no requests; the purchase screens'
    /// screenshots get the store, as an admin sees it.
    static func sampleUsage() -> KIFFClient.Usage {
        KIFFClient.Usage(store: Screenshots.purchases ? Screenshots.store : nil)
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
        haltErrors[key] = nil
        guard sessionHasKey, let tenant = kiffTenant else {
            recordHaltError(key, Words.SignIn.againToSign)
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
            try await ResumeConfirmation.confirm(signer: signer, message: Data("tafs.resume.v1|\(tenant)|\(agent ?? "*")".utf8),
                reason: name.map { Words.Taf.Halt.resumePrompt(taf: "\($0)") } ?? Words.Taf.Halt.resumeAllPrompt, wait: resumeConfirmationWait)
        } catch ResumeConfirmation.Failure.timedOut {
            if gen == accountGeneration { recordHaltError(key, Words.Approval.biometryTimedOut(biometry: Biometry.name)) }
            return false
        } catch {
            if gen == accountGeneration { recordHaltError(key, ApprovalReadiness.cancelled(error) ?? Words.Taf.Halt.notResumedUnconfirmed) }
            return false
        }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil }
        do {
            try await kiff.resume(agent: agent)
        } catch {
            if gen == accountGeneration { recordHaltError(key, name.map { Words.Taf.Halt.kiffNotResumed(taf: $0, why: error.localizedDescription) } ?? Words.Taf.Halt.kiffNotResumedAll(why: error.localizedDescription)) }
            return false
        }
        guard gen == accountGeneration else { return false }
        do {
            let h = try await cloud.releaseHalt(key.isEmpty ? nil : key)
            guard gen == accountGeneration else { return false }
            halts[key] = h
            await refresh()
            return true
        } catch {
            if gen == accountGeneration { recordHaltError(key, name.map { Words.Taf.Halt.cloudNotResumed(taf: $0, why: error.localizedDescription) } ?? Words.Taf.Halt.cloudNotResumedAll(why: error.localizedDescription)) }
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
            deleteAccountError = Words.SignIn.againToSign
            return false
        }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.delete-account.v1|\(tenant)".utf8), reason: Words.Settings.DeleteAccount.prompt)
        } catch { return false }
        guard gen == accountGeneration else { return false }
        do {
            let r = try await cloud.deleteAccount()
            guard r.done else {
                deleteAccountError = Words.Settings.DeleteAccount.notErasedYet
                return false
            }
        } catch let error as TafsCloud.CloudError where error.status == 403 {
            deleteAccountError = Words.Settings.DeleteAccount.ownerOnly
            return false
        } catch {
            deleteAccountError = Words.Settings.DeleteAccount.notErased(why: "\(error.localizedDescription)")
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
            deleteAccountError = Words.Settings.DeleteAccount.connectionsLeft(why: "\(error.localizedDescription)")
            return false
        }
        do {
            try await kiff.deleteAccount()
        } catch {
            deleteAccountError = Words.Settings.DeleteAccount.kiffNotDeleted(why: "\(error.localizedDescription)")
            return false
        }
        // KIFF deleted the account, and its sessions with it.
        forgetSession()
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
        case .failure(let error): memoryErrors[tafID] = Words.Taf.Memory.notLoaded(why: "\(error.localizedDescription)")
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
            memoryErrors[tafID] = Words.Taf.Memory.notForgotten(why: "\(error.localizedDescription)")
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
            ("/how-you-like-it.md", Words.Sample.Memory.explain, 3_600),
            ("/customers/regulars.md", Words.Sample.Memory.regulars, 86_400),
            ("/from-the-owner.md", Words.Sample.Memory.askFirst, 3 * 86_400),
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
        case .failure(let error): versionErrors[tafID] = Words.Version.notLoaded(why: "\(error.localizedDescription)")
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
            versionErrors[tafID] = Words.Version.cardNotRead(why: "\(error.localizedDescription)")
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
            // Named, in tafs cloud's words, with the one step that clears it (kiff/tafs#206).
            blockedInstalls[tafID] = version.n
            versionErrors[tafID] = error.words ?? Words.Version.cantGoLive(n: "\(version.n)", why: "\(error.message)")
            return nil
        } catch {
            guard gen == accountGeneration else { return nil }
            versionErrors[tafID] = Words.Version.cantGoLive(n: "\(version.n)", why: "\(error.localizedDescription)")
            return nil
        }
    }

    /// The version whose install a run is in the way of, by taf: the panel offers "Stop it
    /// and install" (kiff/tafs#206).
    @Published private(set) var blockedInstalls: [UUID: Int] = [:]

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
            lastError = Words.Version.coachUnknownTaf
            return nil
        }
        await loadVersions(tafID)
        guard let version = versions[tafID]?.first(where: { $0.n == ask.n }) else {
            lastError = Words.Version.noSuch(taf: match?.value.name ?? Words.Version.thatTaf, n: "\(ask.n)")
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
            versionErrors[tafID] = Words.Version.cantGoLive(n: "\(version.n)", why: "\(error.localizedDescription)")
            return false
        }
        guard gen == accountGeneration else { return false }
        switches[tafID] = sw
        if let id = sw.draft {
            // Nothing shown, nothing signed.
            guard shownDraft != nil else { return false }
            guard sessionHasKey else {
                versionErrors[tafID] = Words.Connect.startConnectedToolsFirst(device: "\(ThisDevice.kind)")
                return false
            }
            do { draft = try await kiff.draft(id: id) } catch {
                guard gen == accountGeneration else { return false }
                versionErrors[tafID] = Words.Version.cardNotRead(why: "\(error.localizedDescription)")
                return false
            }
        }
        guard gen == accountGeneration else { return false }
        // The Card changed since it was shown: the new one goes on screen, the switch is
        // dropped, and the owner reads it again before any Face ID.
        if let draft, draft.version != shownDraft, !draft.confirmed, draft.status != "claimed" {
            changedVersionCards[tafID] = draft
            await cancelSwitch(tafID)
            versionErrors[tafID] = Words.Version.outOfDateCard
            return false
        }
        // A claimed draft is later than confirmed, its Cards issued all the same.
        if let draft, !draft.confirmed, draft.status != "claimed" {
            do {
                _ = try await signer.sign(Data("tafs.cards.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8),
                                          reason: Words.Version.Prompt.useWithCard(n: "\(version.n)", taf: "\(taf.name)"), grants: true)
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
                    versionErrors[tafID] = Words.Version.cardOutOfDate(n: "\(version.n)")
                } else {
                    versionErrors[tafID] = Words.Version.installUnconfirmed(n: "\(version.n)", why: "\(error.localizedDescription)")
                }
                return false
            }
            await keepKIFFAlerts(tenant: tenant, gen: gen)
            // Coach's tools are never given inside another confirmation: still to give, their
            // own step is asked (docs/coach.md › Coach's tools step).
            if gen == accountGeneration, coachTools?.toGive == true { coachToolsAsked = true }
        } else {
            do {
                _ = try await signer.sign(Data("tafs.version.v1|\(tenant)|\(c.id)|\(version.n)".utf8),
                                          reason: Words.Version.Prompt.use(n: "\(version.n)", taf: "\(taf.name)"))
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
                ? Words.Version.notLive(n: "\(version.n)", why: "\(error.localizedDescription)")
                : Words.Version.installUnfinished(n: "\(version.n)", why: "\(error.localizedDescription)")
            return false
        }
        await loadVersions(tafID)
        guard gen == accountGeneration else { return false }
        // A version that brought a Card changed the taf's limits: the list is read again, so
        // its page shows the new Card at once, not after the app is reopened.
        if sw.draft != nil {
            await refresh()
            guard gen == accountGeneration else { return false }
        }
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
            versionErrors[tafID] = Words.Version.switchNotCancelled(why: "\(error.localizedDescription)")
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
        taf.badge = trial.label ?? Words.Version.Trial.badge
        taf.now = [trial.title ?? Words.Version.Trial.trying(n: "\(trial.n)"), trial.ends.map { Words.Version.Trial.endsIfUnused(date: "\($0.shown(date: .abbreviated, time: .omitted))") }]
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
            trialErrors[tafID] = Words.Version.Trial.notRead(why: "\(error.localizedDescription)")
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
            trialErrors[tafID] = Words.Version.Trial.cardNotRead(n: "\(version.n)", why: "\(error.localizedDescription)")
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
            trialErrors[tafID] = Words.Connect.startConnectedToolsIphoneFirst
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
            trialErrors[tafID] = Words.Version.Trial.cantTry(n: "\(version.n)", why: "\(error.localizedDescription)")
            return nil
        }
        guard gen == accountGeneration else { return nil }
        // What the owner read is what Face ID signs: changed since, it's read again.
        guard draft.version == shownDraft else {
            changedVersionCards[tafID] = draft
            trialErrors[tafID] = Words.Version.Trial.changed
            return nil
        }
        do {
            _ = try await signer.sign(Data("tafs.trial.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8),
                                      reason: Words.Version.Trial.prompt(n: "\(version.n)", taf: "\(taf.name)"), grants: true)
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
                    ? Words.Version.NotATrial.confirmed
                    : Words.Version.NotATrial.other(status: "\(confirmed.status)")
                await loadVersions(tafID)
                return nil
            }
            let open = try await cloud.openTrial(c.id, version: version.n, draft: draft.id)
            guard gen == accountGeneration else { return nil }
            setTrial(open, of: tafID)
            return trialChat(of: tafID)
        } catch {
            guard gen == accountGeneration else { return nil }
            trialErrors[tafID] = Words.Version.Trial.notStarted(n: "\(version.n)", why: "\(error.localizedDescription)")
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
                                              reason: Words.Version.Prompt.install(n: "\(open.n)", taf: "\(taf.name)"), grants: true)
                } catch { return false }
                guard gen == accountGeneration else { return false }
                kiff.setupSigner = { try signer.signConfirmed($0) }
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version)
            }
            _ = try await cloud.installTrial(c.id)
        } catch {
            guard gen == accountGeneration else { return false }
            trialErrors[tafID] = Words.Version.Trial.installUnfinished(n: "\(open.n)", why: "\(error.localizedDescription)")
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
            trialErrors[tafID] = Words.Version.Trial.notEnded(why: "\(error.localizedDescription)")
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
            chatStates[tafID]?.askCoach = thread.askCoach
            // The thread is the source: tafs cloud says when the latest run stopped at a cap, and a
            // run at work, or one that answered since, is past it.
            chatStates[tafID]?.capped = thread.working == nil ? thread.capped?.stop : nil
            settleChatAwaiting(tafID)
            settleSteering(tafID)
        case .failure(let error):
            chatErrors[tafID] = Words.Chat.threadNotLoaded(why: "\(error.localizedDescription)")
            return
        }
    }

    /// Sends the owner's message to the taf. It shows at once; on KIFF the thread is then
    /// reloaded from tafs cloud, where the taf's answer arrives as it works.
    @discardableResult
    func sendChat(_ text: String, to tafID: UUID) async -> Bool { await deliverChat(text, to: tafID) == .sent }

    /// `files` go with the message (kiff/tafs#209): a message may be files alone. One with
    /// files that doesn't go is left with the composer, files and all, rather than kept unsent.
    /// A send that went through ticks (Haptics), whichever way it went; one kept unsent warns.
    private func deliverChat(_ text: String, to tafID: UUID, files: [TafsCloud.AttachmentRef] = []) async -> Delivery {
        let delivery = await deliverChatOnce(text, to: tafID, files: files)
        Haptics.play(delivery.feel)
        return delivery
    }

    private func deliverChatOnce(_ text: String, to tafID: UUID, files: [TafsCloud.AttachmentRef] = []) async -> Delivery {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !files.isEmpty else { return .notTried }
        guard text.count <= Self.chatLimit else {
            chatErrors[tafID] = Words.Chat.tooLong(count: Self.chatLimit)
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
                for line in [Words.Sample.Reply.lookingLately, Words.NeedsYou.answerNamesEach] {
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
            chats[tafID]?.removeAll { $0.id == pending.id }
            chatSent[tafID]?.removeAll { $0.id == pending.id }
            chatErrors[tafID] = Words.Chat.notOnCloud
            return .notTried
        }
        chatStates[tafID, default: ChatState()].ended = nil
        chatStates[tafID]?.wentThrough = []
        chatStates[tafID]?.failedWhy = nil
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
                let sent = pending.id
                chatStreams[tafID] = Task { await self.followChatStream(events, tafID: tafID, text: text, gen: gen, sent: sent) }
                clearBusyUnsent(tafID)
                return .sent
            }
            if trialChats[tafID] != nil {
                _ = try await cloud.trialMessage(c.id, text: text)
            } else {
                let sent = try await cloud.sendMessage(c.id, text: text, files: files)
                if current(), let id = sent.message?.id { markSent(pending.id, serverID: id, in: tafID) }
            }
            guard current() else { return .notTried }
            chatStates[tafID, default: ChatState()].awaiting = clock()
            clearBusyUnsent(tafID)
            await loadChat(tafID)
            return .sent
        } catch let e as TafsCloud.CloudError where e.status == 429 && e.words == nil {
            guard current() else { return .notTried }
            chats[tafID]?.removeAll { $0.id == pending.id }
            chatSent[tafID]?.removeAll { $0.id == pending.id }
            if !files.isEmpty {
                chatErrors[tafID] = Self.busyWords(c.name)
                return .notTried
            }
            keepUnsent(text, tafID, why: Self.busyWords(c.name), busy: true)
            return .kept
        } catch let e as TafsCloud.CloudError where e.code == TafsCloud.aiConsentRequired {
            // Refused for want of the AI-data consent: its screen says why, and the message goes
            // back in the composer with its files, with no Not sent row, to send once it's allowed.
            guard current() else { return .notTried }
            chats[tafID]?.removeAll { $0.id == pending.id }
            chatSent[tafID]?.removeAll { $0.id == pending.id }
            return .notTried
        } catch let e as TafsCloud.CloudError where Self.waitsForCredits(e) != nil {
            // The tafs wait for credits: tafs cloud's words, and the message stays in the
            // composer, with no Retry, since sending again changes nothing until they go on.
            guard current() else { return .notTried }
            chats[tafID]?.removeAll { $0.id == pending.id }
            chatSent[tafID]?.removeAll { $0.id == pending.id }
            chatErrors[tafID] = Self.waitsForCredits(e)
            return .notTried
        } catch {
            guard current() else { return .notTried }
            chats[tafID]?.removeAll { $0.id == pending.id }
            chatSent[tafID]?.removeAll { $0.id == pending.id }
            if !files.isEmpty {
                chatErrors[tafID] = error.localizedDescription
                return .notTried
            }
            keepUnsent(text, tafID, why: error.localizedDescription, halted: Self.haltOf(error))
            return .kept
        }
    }

    /// The message on its way takes tafs cloud's id for it, from its send or its stream: the
    /// thread's copy is then found by that id alone (`SentMessages.merge`), never by its words.
    private func markSent(_ localID: String, serverID: String, in tafID: UUID) {
        func mark(_ m: inout TafsCloud.ChatMessage) { if m.id == localID, m.serverID == nil { m.serverID = serverID } }
        if var list = chats[tafID] { for i in list.indices { mark(&list[i]) }; chats[tafID] = list }
        if var list = chatSent[tafID] { for i in list.indices { mark(&list[i]) }; chatSent[tafID] = list }
    }

    /// Coach's message on its way takes tafs cloud's id, as a taf's does (`markSent`).
    private func markSetupSent(_ localID: String, serverID: String) {
        func mark(_ m: inout TafsCloud.ChatMessage) { if m.id == localID, m.serverID == nil { m.serverID = serverID } }
        for i in setupSent.indices { mark(&setupSent[i]) }
        for i in setupThread.messages.indices { mark(&setupThread.messages[i]) }
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
            fileNotices[key] = Words.Chat.messageCarriesMostFiles(perMessage: "\(FilePrep.perMessage)")
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
        // The last export's files too: a person's chats never stay on the phone after them.
        try? FileManager.default.removeItem(at: exportedFiles)
    }

    /// Where Export my chats writes its two files (kiff/tafs#424): removed with the opened files.
    static let exportedFiles = FileManager.default.temporaryDirectory.appendingPathComponent("export", isDirectory: true)

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
            fileNotices[key] = Words.Chat.File.notOpened(file: "\(ref.name)", why: "\(error.localizedDescription)")
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
        /// The taf's ask_coach cards, as its thread has them (kiff/tafs#476, gap 15).
        var askCoach: [TafsCloud.AskCoachCard] = []
        /// What waits in this chat to go once the taf is done: tafs cloud holds it
        /// (docs/chat.md, `hold`), and sends it as the person's next message. The app keeps no
        /// queue of its own.
        var queued: String? { steering?.held?.text }
        var ended: RunEnd?
        /// What the failed run did before it stopped (kiff/agents#249), said with Retry.
        var wentThrough: [TafsCloud.DoneCall] = []
        /// Why the failed run stopped, in tafs cloud's words, when its `end` sends them
        /// (`ai_unavailable`): said as they are, with Retry.
        var failedWhy: String?
        /// The run at work, as tafs cloud says, for writing to it.
        var workingRun: String?
        /// What the owner wrote to the latest run that it hasn't read, as kiff/agents says
        /// (kiff/agents#102): shown until the run reads it, and once it's over, offered
        /// to send again.
        var steering: TafsCloud.Steering?
        /// The unread messages of runs that are over, once dealt with here.
        var settled: Set<String> = []
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

    private func keepUnsent(_ text: String, _ tafID: UUID, why: String, busy: Bool = false, settle: String? = nil, halted: String? = nil) {
        chatStates[tafID, default: ChatState()].unsent.append(UnsentMessage(text: text, why: why, at: clock(), busy: busy, settle: settle, halted: halted))
    }

    /// Why a message wasn't sent when the owner wrote too many too fast: the runtime's limit
    /// of 10 in 10 minutes for each person (kiff/agents#220).
    static func busyWords(_ name: String) -> String {
        Words.Chat.tooMany(taf: "\(name)")
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
    /// `sent`: the local id of the owner's message this stream answers, marked with tafs
    /// cloud's id once the stream names it.
    private func followChatStream(_ events: AsyncThrowingStream<TafsCloud.StreamEvent, Error>, tafID: UUID, text: String, gen: Int,
                                  sent: String? = nil) async {
        var ended: String?
        var didBefore: [TafsCloud.DoneCall] = []
        var whyFailed: String?
        do {
            for try await e in events {
                guard gen == accountGeneration else { return }
                switch e {
                case .session(let session, _, let run, let message):
                    chatStates[tafID]?.live?.session = session
                    chatStates[tafID]?.live?.run = run
                    if let sent, let message { markSent(sent, serverID: message, in: tafID) }
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
                case .state(let state, let words):
                    chatStates[tafID]?.live?.state = LiveState(state: state, words: words)
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
                case .end(let status, let stop, let done, _, let words):
                    ended = status
                    didBefore = done
                    whyFailed = words
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
            chatStates[tafID]?.failedWhy = whyFailed
        }
        if ended == "CANCELLED" { chatStates[tafID]?.ended = .stopped }
        // What the run didn't read, and what waited for it, is tafs cloud's to send now
        // (docs/chat.md, `hold`): reading the thread sends it, and shows what was stopped.
        // The recorded answer first, then the live one goes: no gap between them. Each held
        // call keeps its place: after what the taf wrote before it (kiff/tafs#206).
        await loadChat(tafID)
        guard gen == accountGeneration else { return }
        if let live = chatStates[tafID]?.live { anchorHeld(live, tafID: tafID) }
        chatStates[tafID]?.live = nil
    }

    /// What a taf is doing, in the owner's words: a connected tool by the title its
    /// connection gives it ("Reading List payments (charges)…"), never its name in KIFF,
    /// which ends in four letters of its grant (kiff/tafs#236, item 7).
    func tafActivityWords(_ tool: String) -> String? {
        if let title = toolTitle(tool) { return Words.Kiff.askingKiff(title: "\(title)") }
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
        case "memory": return Words.Taf.Activity.readingNotes
        case "kiff_pending": return Words.Taf.Activity.checkingWaits
        default:
            // The tool's own name in words, past any prefix (kiff/tafs#138, item 52).
            return Words.Work.askingKiffRun(tool: "\(KIFFMapping.toolWords(tool))")
        }
    }

    /// The composer's Send: now, or, while the taf is at work, to its run, once, with
    /// `hold` (docs/chat.md): what the run doesn't take, tafs cloud holds and sends once the
    /// taf is done; a second message joins what waits. True once the message is taken:
    /// sent, written to the run, held, or kept in the thread as not sent. False leaves it with
    /// the composer: nothing to send, no run to write to yet, or more than tafs cloud holds.
    @discardableResult
    func submitChat(_ text: String, to tafID: UUID, files: [TafsCloud.AttachmentRef] = []) async -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // A message with files goes as a message of its own, never into the run at work
        // (kiff/tafs#209): the composer sends it once the taf is done.
        if !files.isEmpty { return await deliverChat(text, to: tafID, files: files) == .sent }
        guard !text.isEmpty else { return false }
        if mode == .kiff, chatThinking(tafID) || chatSending.contains(tafID) {
            // To the run at work, which reads it at its next step (kiff/agents#102). The
            // message that starts it is still on its way: no run yet, so it stays in the composer.
            let s = chatState(tafID)
            guard let run = s.live?.run ?? s.workingRun, let c = cloudTaf(for: tafID) else { return false }
            do {
                switch try await cloud.steer(c.id, run: run, text: text) {
                case .taken(let status):
                    // Shown at once, as the run now holds it; the next reload says the same.
                    let current = chatState(tafID).steering.flatMap { $0.run == run ? $0 : nil }
                    chatStates[tafID, default: ChatState()].steering = TafsCloud.Steering(
                        run: run, status: status, untaken: (current?.untaken ?? []) + [TafsCloud.Steering.Message(text: text, at: clock())],
                        held: current?.held, notSent: current?.notSent ?? [])
                case .kept(let sent, let held):
                    // tafs cloud holds what the run didn't take, for this chat, and sends it once the taf
                    // is done; shown at once as waiting. Sent already: the new run is in the thread.
                    let current = chatState(tafID).steering
                    chatStates[tafID, default: ChatState()].steering = TafsCloud.Steering(
                        run: current?.run ?? run, status: current?.status ?? "RUNNING", untaken: current?.untaken ?? [],
                        held: held, notSent: current?.notSent ?? [])
                    if sent != nil {
                        chatStates[tafID]?.awaiting = clock()
                        await loadChat(tafID)
                    }
                }
                clearBusyUnsent(tafID)
                return true
            } catch let e as TafsCloud.CloudError where e.code == "held_full" {
                // More than tafs cloud holds: nothing is cut, and the words stay in the composer,
                // with tafs cloud's why as it says it.
                chatErrors[tafID] = e.message
                return false
            } catch let e as TafsCloud.CloudError where Self.waitsForCredits(e) != nil {
                chatErrors[tafID] = Self.waitsForCredits(e)
                return false
            } catch let e as TafsCloud.CloudError where e.code == TafsCloud.aiConsentRequired {
                // The consent's screen says why; the words stay in the composer.
                return false
            } catch let e as TafsCloud.CloudError where e.status == 429 && e.words == nil {
                keepUnsent(text, tafID, why: Self.busyWords(c.name), busy: true)
                return true
            } catch {
                // Not reached: it stays in the thread as not sent, with why and Retry. The app
                // never resends it on its own.
                keepUnsent(text, tafID, why: error.localizedDescription, halted: Self.haltOf(error))
                return true
            }
        }
        return await deliverChat(text, to: tafID) != .notTried
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

    /// Keeps a steering row's hash across launches: only when Retry, Edit or Delete took it,
    /// never when it's shown, so a restart before that brings the row back.
    private func rememberTaken(_ message: UnsentMessage) {
        guard let key = message.settle, let remembered else { return }
        let kept = (remembered.stringArray(forKey: Self.settledKey) ?? []).filter { $0 != key } + [key]
        remembered.set(Array(kept.suffix(Self.settledKept)), forKey: Self.settledKey)
    }

    /// What a run never got to, as the thread says, shown once as not sent, with why and
    /// Retry, which sends it as a new message (docs/chat.md): tafs cloud's `not_sent`, in its
    /// words; and what a run that's over lists unread, from before the app wrote with `hold`.
    /// tafs cloud sends the rest itself, so nothing here is sent on its own.
    private func settleSteering(_ tafID: UUID) {
        guard let s = chatStates[tafID]?.steering else { return }
        func key(_ m: TafsCloud.Steering.Message) -> String {
            let digest = SHA256.hash(data: Data("\(s.run)|\(m.at.timeIntervalSince1970)|\(m.text)".utf8))
            return digest.map { String(format: "%02x", $0) }.joined()
        }
        let unread = s.over ? s.untaken.map { ($0, s.status == "CANCELLED" ? Words.Chat.Steer.stoppedBeforeRead : Words.Chat.Steer.notReadBeforeEnd) } : []
        let fresh = (s.notSent.map { ($0.message, $0.why) } + unread).filter { !wasSettled(key($0.0), tafID) }
        guard !fresh.isEmpty else { return }
        // Shown: dealt with in memory only, until Retry, Edit or Delete takes it.
        chatStates[tafID, default: ChatState()].settled.formUnion(fresh.map { key($0.0) })
        for (m, why) in fresh { keepUnsent(m.text, tafID, why: why, settle: key(m)) }
    }

    /// Sends a message that didn't go through again. It stays where it was until it's
    /// sent or queued, as Setup's does.
    func retryChat(_ message: UnsentMessage, to tafID: UUID) async {
        guard !chatSending.contains(tafID), let at = chatStates[tafID]?.unsent.firstIndex(where: { $0.id == message.id }) else { return }
        chatStates[tafID]?.unsent.remove(at: at)
        let left = chatState(tafID).unsent.count
        // Not taken at all (too long to join the queue): back in its place. A send that
        // fails keeps it again itself.
        if !(await submitChat(message.text, to: tafID)) {
            chatStates[tafID]?.unsent.insert(message, at: min(at, chatState(tafID).unsent.count))
        } else if chatState(tafID).unsent.count > left, message.settle != nil, let i = chatStates[tafID]?.unsent.indices.last {
            // Tried and kept as not sent again: still owed, so the new row carries its hash on.
            chatStates[tafID]?.unsent[i].settle = message.settle
        } else {
            rememberTaken(message)
        }
    }

    func discardUnsentChat(_ message: UnsentMessage, from tafID: UUID) {
        chatStates[tafID]?.unsent.removeAll { $0.id == message.id }
        rememberTaken(message)
    }

    /// Takes a message that wasn't sent out of the thread, to change it in the composer
    /// (kiff/tafs#476): its words, or nil when it's gone already (sent, or taken).
    func editUnsentChat(_ message: UnsentMessage, from tafID: UUID) -> String? {
        guard chatStates[tafID]?.unsent.contains(where: { $0.id == message.id }) == true else { return nil }
        discardUnsentChat(message, from: tafID)
        return message.text
    }

    /// The stops at a cap of the run's own: it ends with no words for it.
    nonisolated static let capStops: Set<String> = ["cost_cap", "turn_cap", "max_token", "refused"]

    /// What the chat says when its latest run stopped at a cap, never "…" (the 793 retest).
    nonisolated static func capWords(_ stop: String, taf: String) -> String? {
        switch stop {
        case "cost_cap": return Words.Chat.Cap.cost(taf: taf)
        case "turn_cap": return Words.Chat.Cap.turns(taf: taf)
        // A reply cut off at its length limit, and the AI declining to go on (docs/chat.md).
        case "max_token": return Words.Chat.Stopped.outOfRoom(taf: taf)
        case "refused": return Words.Chat.Stopped.declined(taf: taf)
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
        }, why: chatState(tafID).failedWhy)
    }

    /// `why`: tafs cloud's words for why it stopped, said as they are; what went through
    /// still follows, from the run's own record.
    nonisolated static func failedWords(taf: String, done: [(words: String, resource: String?, amount: String?)], why: String? = nil) -> String {
        let calls = done.map { c in
            let on = c.resource.map { "\(c.words) on \($0)" } ?? c.words
            return c.amount.map { "\(on), \($0)" } ?? on
        }
        if let why {
            return done.isEmpty ? why : Words.Chat.Failed.withWords(words: why.trimmingCharacters(in: .whitespacesAndNewlines), calls: calls.joined(separator: "; "))
        }
        guard !done.isEmpty else { return Words.Work.stoppedPartWayBeforeFinished(taf: "\(taf)") }
        let list = calls.joined(separator: "; ")
        return calls.count == 1 ? Words.Chat.Failed.after(taf: "\(taf)", calls: list) : Words.Chat.Failed.afterSeveral(taf: "\(taf)", calls: list)
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
            chatErrors[tafID] = Words.Work.didNotStop(why: error.localizedDescription)
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
        /// Someone wrote through a door (docs/doors.md), at its time among the messages: the
        /// owner answers it there.
        case knock(TafsCloud.Knock)
        /// A setting the taf hands to Coach (kiff/tafs#476, gap 15), at its time.
        case askCoach(TafsCloud.AskCoachCard)
        var id: String {
            switch self {
            case .message(let m): return "m|" + m.id
            // A held call and what became of it are one row, which changes in place
            // (kiff/tafs#206): the card, then "Refused by you · …".
            case .held(let h): return "k|" + AppModel.heldKey(h)
            case .outcome(let e): return "k|" + e.id.uuidString
            case .streaming(let id, _): return id
            case .knock(let k): return "d|" + k.id
            case .askCoach(let c): return "a|" + c.id
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
            case .knock(let k): return k.at
            case .askCoach(let c): return c.at
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
        // A door's knocks sit at their own times, so a reply newer than a knock comes after it.
        if mode == .kiff, !doorsOffered.isEmpty { items += (chatKnocks[tafID] ?? []).map(ChatItem.knock) }
        // The settings it handed to Coach, each at its time (kiff/tafs#476, gap 15).
        if mode == .kiff { items += (chatStates[tafID]?.askCoach ?? []).map(ChatItem.askCoach) }
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
        // Each once, by id: the same message twice in a thread shows once.
        var seen = Set<String>()
        timeline = timeline.filter { seen.insert($0.id).inserted }
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

    // MARK: Reporting an answer

    /// How a report went: kept, kept before, or not sent with why.
    enum ReportOutcome: Equatable { case sent, already, failed(String) }

    /// The answers reported in the UI test's sample, by id: a second report says it was reported already.
    private var sampleReports: Set<String> = []

    /// Whether `message` is an answer the person can report (kiff/tafs#421): Coach's or a
    /// taf's, never their own, and one tafs cloud has an id for (not one still streaming).
    /// The sample's canned answers aren't reported, except in the UI test's run.
    func canReport(_ message: TafsCloud.ChatMessage) -> Bool {
        guard message.from == .taf, message.kind == .message, !message.text.isEmpty else { return false }
        if mode == .sample { return Screenshots.enabled }
        return mode == .kiff && message.serverID != nil
    }

    /// Reports an answer from the chat `chatID` shows (a taf's, or a trial's), or Coach's
    /// when it's nil, to tafs cloud with its chat's session, so a person at KIFF reads it.
    func reportAnswer(_ message: TafsCloud.ChatMessage, in chatID: UUID?, reason: TafsCloud.ReportReason, note: String) async -> ReportOutcome {
        let note = note.trimmingCharacters(in: .whitespacesAndNewlines)
        if mode == .sample {
            return sampleReports.insert(message.id).inserted ? .sent : .already
        }
        let taf = chatID == nil ? setupTaf : cloudTaf(for: chatID!)
        guard mode == .kiff, let taf, let id = message.serverID else { return .failed(Words.Report.cantFromHere) }
        let gen = accountGeneration
        do {
            let report = try await cloud.reportAnswer(taf.id, message: id, reason: reason, note: note.isEmpty ? nil : note, session: message.session)
            guard gen == accountGeneration else { return .failed(Words.Common.youSignedOut) }
            return report.new ? .sent : .already
        } catch let e as TafsCloud.CloudError where e.words != nil {
            return .failed(e.words!)
        } catch let e as TafsCloud.CloudError where e.status == 404 {
            return .failed(Words.NeedsYou.answerGone)
        } catch {
            // tafs cloud's own wording is for developers: the app says it in its words.
            return .failed(Words.Kiff.didNotReachKiffTry)
        }
    }

    // MARK: The Record across the person's tafs (docs/ios-app.md › The Record)

    /// Whether this person sees every taf's Record (oversees or manages each), which the
    /// receipts export needs; tafs cloud still decides (403).
    var seesEveryTaf: Bool {
        guard mode == .kiff else { return false }
        let onCloud = tafs.filter { cloudTaf(for: $0.id) != nil }
        return !onCloud.isEmpty && onCloud.allSatisfy { taf in
            kiffAgent(for: taf.id).flatMap { access[$0] }.map { $0.oversee || $0.manage } ?? false
        }
    }

    /// The app's taf for tafs cloud's id, when the person has it on Home.
    func tafID(cloudID: String) -> UUID? {
        cloudTafs.first { $0.value.id == cloudID }.flatMap { holderIDs[$0.key] }.flatMap { id in tafs.contains { $0.id == id } ? id : nil }
    }

    /// The search box's words, read once from tafs cloud; the app's own stand in until they come.
    func loadRecordHint() async {
        guard mode == .kiff, recordHint == nil else { return }
        let gen = accountGeneration
        guard let hint = try? await cloud.searchRecord("", limit: 1).hint, gen == accountGeneration else { return }
        if !hint.trimmingCharacters(in: .whitespaces).isEmpty { recordHint = hint }
    }

    /// The search waiting for the person to stop typing (`recordQueryTyped`).
    private var recordTyping: Task<Void, Never>?
    /// How long the Record waits after the last key before it searches what's typed.
    var recordTypingPause: UInt64 = 400_000_000

    /// The Record's box as the person types: what's typed is searched `recordTypingPause` after
    /// the last key, so a word typed letter by letter is one search, not one a letter. Each key
    /// starts the wait again. Words already searched (or being searched) aren't searched again,
    /// and an emptied box shows the Record at once.
    func recordQueryTyped(_ q: String) {
        recordTyping?.cancel(); recordTyping = nil
        let asked = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !asked.isEmpty else { recordSearch = nil; return }
        let pause = recordTypingPause
        recordTyping = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: pause) } catch { return }
            guard let self, !Task.isCancelled, self.recordSearch?.q != asked else { return }
            self.recordTyping = nil
            await self.searchRecord(asked)
        }
    }

    /// The box's Search key: what's typed, at once, and no wait left to search it again.
    func recordQuerySubmitted(_ q: String) async {
        recordTyping?.cancel(); recordTyping = nil
        await searchRecord(q)
    }

    /// Searches the Record across the person's tafs (`GET /v1/record?q=`). A blank `q` clears the
    /// search and shows the Record as before. `more` reads the next page of the same search; a
    /// cursor tafs cloud refuses (409, a call made since) searches again from the top.
    func searchRecord(_ q: String, more: Bool = false) async {
        guard mode == .kiff else { return }
        let asked = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !asked.isEmpty else { recordSearch = nil; return }
        let current = recordSearch?.q == asked ? recordSearch : nil
        if more, current == nil || current?.loading == true || current?.nextCursor == nil { return }
        var started = more ? current! : RecordSearch(q: asked)
        started.loading = true; started.error = nil
        recordSearch = started
        let gen = accountGeneration
        do {
            let page = try await cloud.searchRecord(asked, cursor: more ? current?.nextCursor : nil)
            guard gen == accountGeneration, var now = recordSearch, now.q == asked else { return }
            now.pages = (more ? now.pages : []) + [page]
            now.loading = false
            recordSearch = now
            if !page.hint.trimmingCharacters(in: .whitespaces).isEmpty { recordHint = page.hint }
        } catch {
            guard gen == accountGeneration else { return }
            if more, (error as? TafsCloud.CloudError)?.status == 409 { await searchRecord(asked); return }
            guard var now = recordSearch, now.q == asked else { return }
            now.loading = false; now.error = error.localizedDescription
            recordSearch = now
        }
    }

    /// Opens one case from the Record: every call about `about`, oldest first, and the step that
    /// didn't go through.
    func openRecordCase(_ about: String) async {
        guard mode == .kiff, !about.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let gen = accountGeneration
        recordCase = TafsCloud.RecordCase(about: about); recordCaseError = nil
        do {
            let read = try await cloud.recordCase(about)
            if gen == accountGeneration, recordCase?.about == about { recordCase = read }
        } catch {
            if gen == accountGeneration, recordCase?.about == about { recordCaseError = error.localizedDescription }
        }
    }

    func closeRecordCase() { recordCase = nil; recordCaseError = nil }

    /// Where an export of receipts writes its two files: removed with the opened files, so
    /// nothing of the account stays on the phone after them, and replaced at each export.
    static var receiptFiles: URL { exportedFiles.appendingPathComponent("receipts", isDirectory: true) }

    /// The receipts of `from` to `to` (YYYY-MM-DD) for an accountant, as two files to share:
    /// tafs cloud's answer as it came (KIFF's receipts exactly as issued) and its CSV. Nil, with
    /// why in words, when they weren't exported.
    func exportReceipts(from: String, to: String) async -> (files: [URL]?, missing: Int, why: String?) {
        guard mode == .kiff else { return (nil, 0, Words.Error.signInFirst) }
        let export: TafsCloud.ReceiptsExport
        do { export = try await cloud.exportReceipts(from: from, to: to) } catch { return (nil, 0, error.localizedDescription) }
        do {
            let dir = Self.receiptFiles
            try? FileManager.default.removeItem(at: dir)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let stem = "receipts-\(export.from)-to-\(export.to)"
            let json = dir.appendingPathComponent("\(stem).json"), csv = dir.appendingPathComponent("\(stem).csv")
            try export.json.write(to: json, options: .completeFileProtection)
            try Data(export.csv.utf8).write(to: csv, options: .completeFileProtection)
            return ([json, csv], export.missing, nil)
        } catch {
            return (nil, 0, error.localizedDescription)
        }
    }

    // MARK: Exporting one's own chats

    /// The person's own chats, trials and notes, as two files to save or share
    /// (kiff/tafs#424): `tafs-chats.json` and `tafs-chats.txt`. Nil, with why, when it
    /// wasn't exported. The sample's UI test exports the sample's own words.
    func exportChats() async -> (files: [URL]?, why: String?) {
        let export: (json: Data, text: String)
        do {
            if mode == .sample, Screenshots.enabled {
                export = try TafsCloud.splitExport(Self.sampleExport(tafs, at: clock()))
            } else {
                guard mode == .kiff else { return (nil, Words.Export.signInFirst) }
                export = try await cloud.exportChats()
            }
        } catch {
            return (nil, Words.Connect.chatsWereNotExportedCheck)
        }
        do {
            let dir = Self.exportedFiles
            try? FileManager.default.removeItem(at: dir)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let json = dir.appendingPathComponent("tafs-chats.json"), text = dir.appendingPathComponent("tafs-chats.txt")
            try export.json.write(to: json, options: .completeFileProtection)
            try Data(export.text.utf8).write(to: text, options: .completeFileProtection)
            return ([json, text], nil)
        } catch {
            return (nil, Words.Chat.chatsWereNotExportedCould(device: "\(ThisDevice.kind)"))
        }
    }

    /// The sample's export, shaped as tafs cloud's, for the UI test.
    private static func sampleExport(_ tafs: [Taf], at now: Date) -> Data {
        let at = ISO8601DateFormatter().string(from: now)
        let body: [String: Any] = [
            "exported_at": at, "account": "sample", "person": "sample",
            "tafs": tafs.map { ["id": $0.id.uuidString, "name": $0.name, "chats": [], "notes": []] },
            "readable": Words.Chat.chatsTafsExported(at: "\(at)") + tafs.map { Words.Chat.noChats(name: "\($0.name)") }.joined(separator: "\n"),
        ]
        return (try? JSONSerialization.data(withJSONObject: body)) ?? Data("{}".utf8)
    }

    private func sampleMessage(_ from: TafsCloud.ChatMessage.From, _ text: String, session: String = "sample") -> TafsCloud.ChatMessage {
        TafsCloud.ChatMessage(at: clock(), from: from, text: text, session: session)
    }

    private static func sampleHello(_ taf: Taf) -> String {
        Words.Sample.hello(name: "\(taf.name)", now: "\(taf.now)")
    }

    /// A sample taf answers from its own record: what it did, what waits, or a pause.
    private func sampleReply(to text: String, tafID: UUID) -> String {
        let lower = text.lowercased()
        guard let taf = taf(tafID) else { return Words.Sample.Reply.gone }
        if ["stop", "pause", "hold off"].contains(where: { lower.contains($0) }) {
            if taf.state != .paused { togglePause(tafID) }
            return Words.Sample.Reply.paused
        }
        if ["go", "carry on", "resume", "continue"].contains(where: { lower == $0 || lower.hasPrefix($0 + " ") }) {
            if taf.state == .paused { togglePause(tafID) }
            return Words.Sample.Reply.backToWork
        }
        if ["more", "raise", "limit", "bigger", "increase"].contains(where: { lower.contains($0) }) {
            return Words.Sample.Reply.moreRoom
        }
        let done = entries(for: tafID).prefix(3).map { "• " + $0.title }
        let waiting = heldCalls(for: tafID).count
        var lines: [String] = []
        lines.append(done.isEmpty ? Words.Sample.Reply.nothingDone : Words.Sample.Reply.lately(done: done.joined(separator: "\n")))
        if waiting > 0 { lines.append(Words.Sample.Reply.waiting(count: waiting)) }
        lines.append(Words.Sample.Reply.note)
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
        if Screenshots.enabled, Screenshots.screen == "card", let i = tafs.firstIndex(where: { $0.name == TafTemplates.refunds.name }) {
            tafs[i].kiffCards = Screenshots.cards(tafs[i])
        }
        if let refunds = tafs.first(where: { $0.name == TafTemplates.refunds.name }) {
            chats[refunds.id] = [
                sampleMessage(.taf, Self.sampleHello(refunds)),
                sampleMessage(.owner, Words.Sample.Chat.question),
                sampleMessage(.taf, sampleReply(to: Words.Sample.Chat.question, tafID: refunds.id)),
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

    private func notifyHeld() {
        // Coach's own holds are named Coach where no taf names them.
        let coach = holderIDs[Self.setupAgent].map { [$0: Words.Coach.title] } ?? [:]
        onHeldChanged?(held, tafs + otherAgents, coach)
    }

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
        // An approval that went through ticks (Haptics); a refusal is the person's own choice.
        if kind == .approved { Haptics.success() }
        entry.byYou = true
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
                    out[i].byYou = mine.byYou
                } else {
                    settled.insert(id)
                }
            } else {
                out.append(mine)
            }
        }
        return (out.sorted { $0.at > $1.at }, settled)
    }

    // MARK: Doors and live updates

    /// Each taf's doors, one model per taf, shared by its Doors tab and its chat.
    private var doorModels: [UUID: DoorsModel] = [:]
    /// The open knocks of each taf whose doors were read, placed in its chat at their times.
    @Published private(set) var chatKnocks: [UUID: [TafsCloud.Knock]] = [:]

    /// The taf's doors, read with this person's session; none until tafs cloud runs the taf.
    func doors(for tafID: UUID) -> DoorsModel? {
        if let d = doorModels[tafID] { return d }
        // The screenshots draw a sample's doors, with no taf on tafs cloud.
        guard let id = cloudTaf(for: tafID)?.id ?? (Screenshots.enabled ? "sample" : nil) else { return nil }
        let d = DoorsModel(cloud: cloud, tafID: id)
        d.onKnocks = { [weak self] knocks in self?.chatKnocks[tafID] = knocks }
        doorModels[tafID] = d
        return d
    }

    /// A silent push from tafs cloud saying what changed (docs/approvals.md, "Live updates"):
    /// only its kind and the taf. The phone reads again what the kind names, with its own
    /// session, and trusts nothing from the push itself: the list for taf, halted and resumed,
    /// the taf's doors for knock, and the taf's thread where it's been read. planque's grants
    /// are read too, since tafs cloud never sees them: a connection its vendor signed out
    /// shows after the next push, whatever it named.
    func liveChange(_ change: String, cloudTafID: String?) async {
        guard mode == .kiff, signedIn else { return }
        let tafID = cloudTafID.flatMap { id in cloudTafs.first { $0.value.id == id }.flatMap { holderIDs[$0.key] } }
        switch change {
        case "knock":
            if let tafID { await doorModels[tafID]?.load() }
            await refreshConnections()
        case "taf", "halted", "resumed":
            // A refresh reads planque's grants too.
            await refresh()
        default:
            return
        }
        if let tafID, change == "taf" || change == "knock", chats[tafID] != nil { await loadChat(tafID) }
        // Its work too, where its page has read it.
        if let tafID, change == "taf", works[tafID] != nil { await loadWork(tafID) }
    }

    // MARK: A taf's work (docs/reliability.md)

    /// Where each taf's work stands, as tafs cloud says, once read; why it couldn't be read,
    /// or a Send again didn't go; and the requests whose reply is being sent again, by run.
    @Published private(set) var works: [UUID: TafsCloud.TafWork] = [:]
    @Published private(set) var workErrors: [UUID: String] = [:]
    @Published private(set) var sendingAgain: Set<String> = []

    /// Reads where the taf's work stands: its requests and skipped firings, in tafs cloud's words.
    func loadWork(_ tafID: UUID) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID) else { return }
        let gen = accountGeneration, presentation = presentationGeneration
        do {
            let work = try await cloud.work(c.id)
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            works[tafID] = work
            workErrors[tafID] = nil
        } catch is CancellationError {
        } catch let error as URLError where error.code == .cancelled {
        } catch {
            guard gen == accountGeneration, presentation == presentationGeneration else { return }
            workErrors[tafID] = Words.Work.notRead(why: "\(error.localizedDescription)")
        }
    }

    /// Answer within: the owner's promise for a taf, whole hours or nil to clear it. tafs cloud
    /// checks and keeps it, so the work is read again after, whatever came of it; a promise that
    /// couldn't be set says why under the work.
    func setPromise(_ tafID: UUID, hours: Int?) async {
        guard mode == .kiff, let c = cloudTaf(for: tafID) else { return }
        workErrors[tafID] = nil
        let gen = accountGeneration
        var failed: String?
        do {
            try await cloud.setPromise(c.id, hours: hours)
        } catch {
            failed = Words.Work.answerWithinNotSet(why: "\(error.localizedDescription)")
        }
        guard gen == accountGeneration else { return }
        await loadWork(tafID)
        if let failed { workErrors[tafID] = failed }
    }

    /// Send again: a door reply given up on after its tries goes once more. It does no work
    /// and signs nothing. The work is read again after, whatever came of it.
    func sendAgain(_ tafID: UUID, run: String, place: String = "door_reply") async {
        guard mode == .kiff, let c = cloudTaf(for: tafID), sendingAgain.insert(run).inserted else { return }
        workErrors[tafID] = nil
        let gen = accountGeneration
        var failed: String?
        do {
            try await cloud.sendAgain(c.id, run: run, place: place)
        } catch {
            failed = Words.Work.notSentAgain(why: "\(error.localizedDescription)")
        }
        sendingAgain.remove(run)
        guard gen == accountGeneration else { return }
        await loadWork(tafID)
        // Said after the work is read again, so the read doesn't clear it.
        if let failed { workErrors[tafID] = failed }
    }

    // MARK: Connections (planque, #51)

    /// The account's planque grants: connections, and connections waiting for the owner.
    @Published private(set) var grants: [PlanqueClient.Grant] = []
    /// KIFF's words for each grant's profile, by grant id, once read and checked.
    @Published private(set) var profileWords: [String: ProfileWords] = [:]
    /// Why a grant's words couldn't be shown, by grant id.
    @Published private(set) var profileWordsError: [String: String] = [:]

    /// Reads KIFF's words for `grant`'s profile with this person's session, checked against
    /// the profile shown. A grant with no profile has none to read.
    func loadProfileWords(for grant: PlanqueClient.Grant) async {
        guard let profile = grant.profile else { return }
        if let words = profileWords[grant.id], (try? ProfileWords.digest(profile)) == words.profileSHA256 { return }
        do {
            let words = try await kiff.profileWords(for: profile)
            profileWords[grant.id] = words
            profileWordsError[grant.id] = nil
        } catch {
            profileWords[grant.id] = nil
            profileWordsError[grant.id] = (error as? LocalizedError)?.errorDescription ?? ProfileWords.Failure.notWords.errorDescription
        }
    }

    /// The words to show for `grant`: only KIFF's words for exactly its profile.
    func shownWords(for grant: PlanqueClient.Grant) -> ProfileWords? {
        guard let profile = grant.profile, let words = profileWords[grant.id],
              (try? ProfileWords.digest(profile)) == words.profileSHA256 else { return nil }
        return words
    }

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

    /// A taf's connections: the grants made for its KIFF agent that are connected, whose
    /// sign-in planque holds while Setup chooses the tools, so the owner can end it, or that
    /// its vendor signed out, so the owner signs in again.
    func connections(for tafID: UUID) -> [PlanqueClient.Grant] {
        guard let agent = holderIDs.first(where: { $0.value == tafID })?.key else { return [] }
        return grants.filter { $0.request.agent == agent && ($0.connected || $0.holdsSignIn || $0.needsReconnect || $0.waitingSignIn) }
    }

    /// A connection's last failed call (gap 13): the newest call through one of its tools that
    /// wasn't done, its taf's own first, in KIFF's words for it. Nil when none is in the record.
    func lastFailure(_ g: PlanqueClient.Grant) -> String? {
        Self.lastFailure(g, record: record, ownTaf: holderIDs[g.request.agent])
    }

    nonisolated static func lastFailure(_ g: PlanqueClient.Grant, record: [RecordEntry], ownTaf: UUID?) -> String? {
        let actions = Set(g.tools ?? g.request.tools.filter(\.tafs).map(\.action))
        guard !actions.isEmpty else { return nil }
        let failed = record.filter { $0.kind == .notDone && $0.action.map(actions.contains) == true }
        let own = failed.filter { $0.tafID == ownTaf }
        guard let last = (own.isEmpty ? failed : own).max(by: { $0.at < $1.at }) else { return nil }
        return Words.Connect.lastFailed(title: "\(last.title)", detail: "\(last.detail)")
    }

    /// The taf's connection its vendor signed out, if one is: the taf can't use those tools
    /// until the owner signs in again, so it waits on the owner in the list, in planque's words.
    func signedOut(_ tafID: UUID) -> PlanqueClient.Grant? {
        guard let agent = holderIDs.first(where: { $0.value == tafID })?.key else { return nil }
        return grants.first { $0.request.agent == agent && $0.needsReconnect }
    }

    /// Signs in again to a connection its vendor signed out, from the taf's page: the same
    /// approval as Coach's card, behind one Face ID, then the vendor's own page. Signed in
    /// only once planque answers connected; otherwise the connection says why.
    /// Resolve the source id to a freshly read signed grant for the device's approval.
    func reconnectApp(_ id: String) async {
        let gen = accountGeneration
        await refreshConnections()
        guard gen == accountGeneration, let grant = grants.first(where: { $0.id == id }) else { return }
        await reconnect(grant)
    }

    func reconnect(_ grant: PlanqueClient.Grant) async {
        lastError = nil
        reconnectErrors[grant.id] = nil
        shownTermsChanged = false
        // A provider's connection is disconnected and connected again instead: planque would
        // refuse, so nothing is asked.
        if grant.signsInThroughProvider {
            reconnectErrors[grant.id] = ConnectionFixWords.throughProvider(grant.vendor)
            return
        }
        // KIFF's words for its profile, which the approval signs: read now, as the approval
        // card reads them, wherever Sign in again starts.
        await loadProfileWords(for: grant)
        if let why = profileWordsError[grant.id] {
            reconnectErrors[grant.id] = why
            return
        }
        let done = await connect(grantID: grant.id, shown: grant.digest, again: true)
        if done?.connected != true {
            reconnectErrors[grant.id] = lastError ?? quietStop ?? Words.Connect.nothingSignedIn
            lastError = nil
        }
    }

    /// A taf's own Connect again (tafs cloud's `connect_again`): signs that same connection in again, then
    /// reads the taf's page. Once planque says it's connected, the taf shows tafs cloud's `done_words`.
    func connectAgain(_ tafID: UUID, _ again: TafsCloud.ConnectAgain) async {
        connectedAgain[tafID] = nil
        await reconnectApp(again.grant)
        let connected = grants.first { $0.id == again.grant }?.connected == true
        await loadTafSetup(tafID)
        if connected, !again.doneWords.isEmpty { connectedAgain[tafID] = again.doneWords }
    }

    /// tafs cloud's words for a taf whose connection was just connected again, by taf.
    @Published private(set) var connectedAgain: [UUID: String] = [:]

    /// Why a sign-in again from a taf's page didn't connect, by grant id.
    @Published private(set) var reconnectErrors: [String: String] = [:]
    /// Where an approval in progress is, once back from the vendor's page: "Checking the sign-in…".
    @Published private(set) var connectStep: String?

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
            lastError = Words.SignIn.againToSign
            return false
        }
        if planqueReady { return true }
        let gen = accountGeneration
        await loadDeviceID()
        do {
            _ = try await signer.sign(Data("tafs.planque.v1|\(tenant)".utf8), reason: Words.Connect.Prompt.keepSealed)
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
            lastError = Words.Coach.connectAdminOnly
            return false
        } catch PlanqueClient.PlanqueError.server(409, _, _) {
            lastError = Words.Coach.connectOtherDevice
            return false
        } catch {
            lastError = Words.Coach.connectingNotSetUp(why: "\(error.localizedDescription)")
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
    /// `again`: sign in again (gap 13). A connected grant goes through its vendor's sign-in again,
    /// kept apart by planque until this phone confirms it.
    func connect(grantID: String, shown: String?, draftID: String? = nil, shownDraft: String? = nil, again: Bool = false) async -> PlanqueClient.Grant? {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else {
            lastError = Words.Connect.startConnectedToolsFirst(device: "\(ThisDevice.kind)")
            return nil
        }
        quietStop = nil
        notApprovable = false
        guard connecting == nil else {
            quietStop = Words.Connect.anotherApproval
            return nil
        }
        connecting = grantID
        vendorSignedIn = false
        nothingSigned = nil
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
                lastError = Words.Connect.noSuchConnection
                return nil
            }
            grant = found
            if let draftID, found.connected { draft = try await kiff.draft(id: draftID) }
        } catch {
            lastError = Words.Connect.approveNotRead(why: "\(error.localizedDescription)")
            return nil
        }
        if grant.connected, !again {
            // The second approval (kiff/agents#91): the vendor is connected, and the
            // taf's Cards over its tools wait as a draft. One Face ID confirms them.
            // Nothing left to sign: the grant is returned as it is, and noted, so an
            // approval card is never told "approved" without a signature made here.
            guard let draft, !draft.confirmed else {
                nothingSigned = (vendor: grant.vendor, cardsConfirmed: draft != nil)
                return grant
            }
            guard draft.version == shownDraft else {
                setupDrafts[draft.id] = draft
                termsChanged()
                return nil
            }
            do {
                _ = try await signer.sign(Data("tafs.cards.v1|\(tenant)|\(draft.id)|\(draft.version)".utf8),
                                          reason: Words.Connect.Prompt.giveCard(agent: "\(promptTaf(draft.agentID, label: draft.label))"), grants: true)
            } catch {
                quietStop = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
                return nil
            }
            guard gen == accountGeneration else { return nil }
            kiff.setupSigner = { try signer.signConfirmed($0) }
            do {
                _ = try await kiff.confirmDraft(id: draft.id, version: draft.version)
            } catch {
                lastError = Words.Connect.cardsNotIssued(why: "\(error.localizedDescription)")
                return nil
            }
            await keepKIFFAlerts(tenant: tenant, gen: gen)
            return grant
        }
        // A grant its vendor signed out (needs_reconnect) is approved and signed in again, as a new one is.
        // A connected one signs in again in place (gap 13): planque keeps the sign-in in use until
        // this phone confirms the new one.
        guard ["pending", "approved", "signed_in", "needs_reconnect"].contains(grant.status) || (again && grant.connected) else {
            failureIsTheRequests = grant.status == "failed"
            notApprovable = true
            lastError = grant.reason.map { Words.Connect.connectionIsBecause(status: grant.status.replacingOccurrences(of: "_", with: " "), reason: $0) }
                ?? Words.Connect.connectionIs(status: grant.status.replacingOccurrences(of: "_", with: " "))
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
        // A connection with a profile is approved only with KIFF's words for it shown.
        let words = shownWords(for: grant)
        if grant.profile != nil, words == nil {
            lastError = PlanqueClient.PlanqueError.wordsUnread.errorDescription
            return nil
        }

        do {
            _ = try await signer.sign(Data("tafs.connect.v1|\(tenant)|\(grant.id)|\(grant.digest)".utf8),
                                      reason: connectPrompt(grant), grants: true)
        } catch {
            quietStop = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
            return nil
        }
        guard gen == accountGeneration else { return nil }

        let connected: PlanqueClient.Grant
        do {
            let approved = try await planque.approve(grant, words: words)
            guard let signInURL = approved.signInURL else {
                // planque holds a fresh sign-in and waits for this phone to confirm it with its
                // one-use code: confirmed here, never a second sign-in, and connected only once
                // planque says so.
                if approved.status == "signed_in", let code = approved.confirmCode {
                    connectStep = Words.Coach.checkingSignIn
                    defer { connectStep = nil }
                    let confirmed = try await planque.confirm(grantID: grant.id, code: code)
                    await refreshConnections()
                    guard gen == accountGeneration else { return nil }
                    guard confirmed.connected || confirmed.listed else {
                        lastError = Words.Coach.notConnected(vendor: grant.vendor)
                        return nil
                    }
                    return confirmed
                }
                // planque already holds the sign-in (contract 5): approving the tools Setup
                // chose from the vendor's list connected them, with no second sign-in.
                guard approved.status == "connected" else {
                    await refreshConnections()
                    lastError = Words.Connect.noSignInLink(vendor: grant.vendor)
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
                lastError = Words.Connect.noConfirmationCode(vendor: "\(grant.vendor)")
                return nil
            }
            guard gen == accountGeneration else { return nil }
            // Back from the vendor: signed in only once planque says the grant is connected,
            // never because the page closed.
            connectStep = Words.Coach.checkingSignIn
            defer { connectStep = nil }
            connected = try await planque.confirm(grantID: grant.id, code: signedIn.code)
        } catch VendorSignIn.SignInError.cancelled {
            lastError = Words.Connect.signInClosedNothingConnected
            await refreshConnections()
            return nil
        } catch {
            lastError = Words.Connect.notConnected(why: "\(error.localizedDescription)")
            failureIsTheRequests = Self.refusesTheRequest(error)
            await refreshConnections()
            return nil
        }
        await refreshConnections()
        guard connected.connected || connected.listed else {
            lastError = Words.Coach.notConnected(vendor: grant.vendor)
            return nil
        }
        return connected
    }

    /// The account's live connections to a Connect apps entry: by the app planque names for
    /// each (its `entry`, which planque gives every grant, a vendor's own sign-in included), or
    /// the grant tafs cloud named for it.
    /// Whether this device reads the account's connections: set up with planque, with a session that
    /// holds its key. Without, an app's connections can't be listed here, and the detail says so.
    var readsConnections: Bool { planqueReady && sessionHasKey }

    func connections(of app: TafsCloud.ConnectApps.App) -> [PlanqueClient.Grant] {
        Self.connections(of: app, in: grants)
    }

    static func connections(of app: TafsCloud.ConnectApps.App, in grants: [PlanqueClient.Grant]) -> [PlanqueClient.Grant] {
        grants.filter { ($0.entry == app.entry || $0.id == app.grant) && !["disconnected", "failed"].contains($0.status) }
    }

    /// An app's connections for one taf, under its name (the owner's Connect apps ask): the
    /// heading, and its connections in planque's order.
    struct ConnectionGroup: Identifiable, Equatable {
        /// The KIFF agent that asked for them.
        let id: String
        let taf: String
        var grants: [PlanqueClient.Grant]
    }

    /// An app's connections grouped by the taf each was set up for. Tafs with one needing
    /// attention (waiting on its sign-in, signed out, or a failed last call) come first, then
    /// the rest by name. Then the removed tafs', only those whose agent tafs cloud names as
    /// removed (`removed_tafs`). Those with no taf this app can name come last: a taf missing
    /// from this person's list isn't proof it was removed (one they don't see, an agent that
    /// isn't a taf), so only the server's word puts a connection under removed.
    func connectionGroups(of app: TafsCloud.ConnectApps.App) -> (tafs: [ConnectionGroup], removed: [PlanqueClient.Grant], others: [PlanqueClient.Grant]) {
        Self.connectionGroups(connections(of: app), taf: { self.tafOf($0) }, removed: Set((connectApps?.removedTafs ?? []).map(\.agent)),
                              attention: { $0.waitingSignIn || $0.needsReconnect || self.lastFailure($0) != nil })
    }

    static func connectionGroups(_ grants: [PlanqueClient.Grant], taf: (PlanqueClient.Grant) -> String?, removed removedAgents: Set<String> = [],
                                 attention: (PlanqueClient.Grant) -> Bool) -> (tafs: [ConnectionGroup], removed: [PlanqueClient.Grant], others: [PlanqueClient.Grant]) {
        var groups: [ConnectionGroup] = []
        var removed: [PlanqueClient.Grant] = []
        var others: [PlanqueClient.Grant] = []
        for g in grants {
            guard let name = taf(g) else {
                if removedAgents.contains(g.request.agent) { removed.append(g) } else { others.append(g) }
                continue
            }
            if let i = groups.firstIndex(where: { $0.id == g.request.agent }) {
                groups[i].grants.append(g)
            } else {
                groups.append(ConnectionGroup(id: g.request.agent, taf: name, grants: [g]))
            }
        }
        let needs = Set(groups.filter { $0.grants.contains(where: attention) }.map(\.id))
        groups.sort { a, b in
            let (x, y) = (needs.contains(a.id), needs.contains(b.id))
            if x != y { return x }
            let order = a.taf.localizedCaseInsensitiveCompare(b.taf)
            return order == .orderedSame ? a.id < b.id : order == .orderedAscending
        }
        return (groups, removed, others)
    }

    /// What tafs cloud says of each removed taf's connection (`removed_tafs[].grants`), by grant:
    /// listed with the tafs whose Cards still use it, or not listed (its reads failed, or no
    /// signed read), and then nothing is cleared.
    var removedListed: [String: TafsCloud.ConnectApps.RemovedGrant] {
        Self.removedListed(connectApps?.removedTafs ?? [])
    }

    static func removedListed(_ removed: [TafsCloud.ConnectApps.RemovedTaf]) -> [String: TafsCloud.ConnectApps.RemovedGrant] {
        Dictionary(removed.flatMap { $0.grants ?? [] }.map { ($0.grant, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// Which of `ids` Clear ends and which it keeps: only a connection tafs cloud lists as a
    /// removed taf's, never one whose taf the phone merely can't see. Which to keep is tafs
    /// cloud's word too: one a live taf's Card still uses (`used_by`) is kept, for those tafs.
    static func clearPlan(_ ids: [String], grants: [PlanqueClient.Grant], listed: [String: TafsCloud.ConnectApps.RemovedGrant])
        -> (ending: [PlanqueClient.Grant], keptFor: [[TafsCloud.ConnectApps.UsedBy]]) {
        let asked = grants.filter { ids.contains($0.id) && listed[$0.id] != nil }
        let keptFor = asked.compactMap { g in listed[g.id].flatMap { $0.usedBy.isEmpty ? nil : $0.usedBy } }
        return (asked.filter { listed[$0.id]?.usedBy.isEmpty == true }, keptFor)
    }

    /// Clears the connections of tafs removed since, from Connect apps (the owner's ask): one
    /// confirmation, one Face ID, then the device key signs each disconnect, as removing a taf
    /// does. Only the ones tafs cloud lists, and one a live taf's Card still uses is kept, with
    /// the line naming that taf.
    @discardableResult
    func clearRemovedConnections(_ ids: [String]) async -> Bool {
        let plan = Self.clearPlan(ids, grants: grants, listed: removedListed)
        let asked = plan.ending.count + plan.keptFor.count
        guard mode == .kiff, planqueReady, sessionHasKey, let tenant = kiffTenant, asked > 0, !clearingRemoved else { return false }
        let keptNames = plan.keptFor.flatMap { $0 }.map(\.name)
        guard !plan.ending.isEmpty else {
            clearNote = ConnectionGroupWords.cleared(done: 0, kept: plan.keptFor.count, keptFor: keptNames, left: 0)
            return true
        }
        let gen = accountGeneration
        clearingRemoved = true
        clearNote = nil
        defer { if gen == accountGeneration { clearingRemoved = false } }
        do {
            _ = try await signer.sign(Data("tafs.disconnect.v1|\(tenant)|\(plan.ending.map(\.id).joined(separator: ","))".utf8),
                                      reason: ConnectionGroupWords.clearTitle(plan.ending.count))
        } catch {
            if gen == accountGeneration { clearNote = Words.Common.notDoneUnconfirmed }
            return false
        }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { planque.signer = nil }
        var left = 0
        for g in plan.ending {
            guard gen == accountGeneration else { return false }
            do { _ = try await planque.disconnect(grantID: g.id) } catch { left += 1 }
        }
        await refreshConnections()
        if gen == accountGeneration {
            clearNote = ConnectionGroupWords.cleared(done: plan.ending.count - left, kept: plan.keptFor.count, keptFor: keptNames, left: left)
        }
        return left == 0
    }

    /// The taf a connection was set up for, by name; nil when this app can't name it: removed,
    /// one this person doesn't see, or an agent that isn't a taf. Never proof it was removed.
    func tafOf(_ grant: PlanqueClient.Grant) -> String? {
        guard let t = holderIDs[grant.request.agent].flatMap({ taf($0) }), t.state != .revoked else { return nil }
        return t.name
    }

    /// The other tafs whose live Card has a rule on one of its tools, as a warning above
    /// Disconnect; nil when none.
    func alsoOnCards(_ grant: PlanqueClient.Grant) -> String? {
        let names = alsoStops(grant)
        guard !names.isEmpty else { return nil }
        return names.count == 1 ? Words.Connect.Apps.alsoOnOne(owner: names[0]) : Words.Connect.Apps.alsoOnMany(tafs: Words.list(names))
    }

    /// The other tafs whose Card draws on a connection's tools: disconnecting it stops them too.
    func alsoStops(_ grant: PlanqueClient.Grant) -> [String] {
        tafsUsing(grant, except: grant.request.agent).map(\.name)
    }

    /// The tafs that use a connection: a live Card rule, or a tool, on one of the actions it
    /// connects. KIFF's tools are the account's, so a taf can draw on a connection another taf
    /// asked for; `agent` leaves out the one being removed.
    func tafsUsing(_ grant: PlanqueClient.Grant, except agent: String? = nil) -> [Taf] {
        let actions = Set(grant.tools ?? grant.request.tools.filter(\.tafs).map(\.action))
        guard !actions.isEmpty else { return [] }
        let skip = agent.flatMap { holderIDs[$0] }
        return tafs.filter { t in
            t.id != skip && t.state != .revoked && !t.card.revoked
                && (t.card.rules.contains { actions.contains($0.action) } || t.tools.contains { actions.contains($0) })
        }
    }

    /// Disconnects a vendor: planque removes its tools from KIFF and revokes the sign-in at
    /// the vendor. Asks for Face ID.
    func disconnect(grantID: String) async -> Bool {
        guard planqueReady, sessionHasKey, let tenant = kiffTenant, let grant = grants.first(where: { $0.id == grantID }) else { return false }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.disconnect.v1|\(tenant)|\(grantID)".utf8), reason: DisconnectWords.title(grant.vendor, taf: tafOf(grant), alsoStops: alsoStops(grant)))
        } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { planque.signer = nil }
        do {
            _ = try await planque.disconnect(grantID: grantID)
        } catch {
            fail(Words.Connect.notDisconnected(why: "\(error.localizedDescription)"), from: .disconnect)
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
        didSet {
            // A card answered here is the thread's once it holds the answer.
            let held = Set(setupThread.components.filter { $0.answer != nil }.map(\.ref))
            if !answered.isDisjoint(with: held) { answered.subtract(held) }
            followHandOvers()
        }
    }
    @Published private(set) var setupSending = false
    @Published private(set) var startingSetup = false
    @Published var setupError: String?
    /// The drafts Setup's approvals name, read from KIFF so the owner sees the Cards.
    @Published private(set) var setupDrafts: [String: KIFFClient.AgentDraft] = [:]
    /// Cards the owner answered on this phone, by `ShownComponent.ref`, so their buttons go
    /// quiet. Never by id alone: Coach can use an id again, and a card answered in one
    /// conversation is never answered in the next (kiff/tafs#387). A card is the thread's once
    /// it holds the answer.
    @Published private(set) var answered: Set<String> = []

    /// The card an answer for `id` is for: the one waiting under that id, since Coach can use
    /// an id again, else the latest.
    func cardRef(_ id: String) -> String {
        let shown = setupThread.components.last { $0.component.id == id && $0.answer == nil } ?? setupThread.components.last { $0.component.id == id }
        return shown?.ref ?? ShownComponent.ref(setupChat ?? "", id)
    }

    /// Whether the owner answered this card on this phone.
    func isAnswered(_ c: SetupComponent) -> Bool { answered.contains(cardRef(c.id)) }

    /// A message that didn't reach Setup: it stays in the thread, with why, to send
    /// again (kiff/tafs#58). Never lost to a failed send.
    struct UnsentMessage: Identifiable, Equatable {
        let id = UUID()
        let text: String
        let why: String
        let at: Date
        /// Not sent only because the owner wrote too many too fast.
        var busy = false
        /// Its hash when it came from the thread's steering (a `not_sent`, or what a run that's
        /// over didn't read): kept across launches only once Retry, Edit or Delete takes the
        /// row, so a restart before that shows it again.
        var settle: String? = nil
        /// Refused because of a halt, as tafs cloud says: the whole `account`, or the `taf` on
        /// its own. The bubble then offers Resume, which Retry can't stand in for.
        var halted: String? = nil
    }

    /// What a send's failure says of a halt: tafs cloud's `halted`, or nil for any other failure.
    nonisolated static func haltOf(_ error: Error) -> String? { (error as? TafsCloud.CloudError)?.halted }
    @Published private(set) var setupUnsent: [UnsentMessage] = []
    /// What the owner is typing to Coach, not sent yet (kiff/tafs#245, item 3): kept here, not in the
    /// chat's view, so closing Coach mode, opening it again or switching tabs keeps it, as a
    /// taf's chat does. Only Send or the owner clears it; a new account starts empty.
    @Published var setupDraft = ""
    /// The recipes a new Coach conversation opens to, from tafs cloud (kiff/tafs#476); nil until
    /// read, or when they couldn't be.
    @Published var recipes: TafsCloud.Recipes?
    /// Every ready-made job, at any time: + New's gallery and the strip under the tafs list
    /// (`GET /v1/recipes/gallery`). Nil before it's read, or from a tafs cloud without it, where
    /// + New opens Coach as before.
    @Published var gallery: TafsCloud.Gallery?

    /// The person's AI-data consent, as tafs cloud keeps it; nil before it's read, or from a tafs
    /// cloud without the route (docs/accounts.md, "Your data and the AI").
    @Published private(set) var aiConsent: TafsCloud.AIConsent?
    /// The consent screen to show now: after sign-in while it isn't given, and whenever tafs cloud
    /// refuses a model turn for want of it. Not now closes it; a refused turn opens it again.
    @Published var aiConsentAsked: TafsCloud.AIConsent?
    @Published private(set) var aiConsentBusy = false
    @Published private(set) var aiConsentError: String?
    /// Not now was answered this session: the screen isn't put up again by a refresh, only by a
    /// turn tafs cloud refuses.
    private var aiConsentPutOff = false
    private var aiConsentObserver: NSObjectProtocol?

    /// Listens for tafs cloud's `ai_consent_required`, from any route, once per model.
    func watchAIConsent() {
        guard aiConsentObserver == nil else { return }
        aiConsentObserver = NotificationCenter.default.addObserver(forName: .aiConsentRequired, object: nil, queue: .main) { [weak self] note in
            guard let consent = note.object as? TafsCloud.AIConsent else { return }
            MainActor.assumeIsolated { self?.consentRequired(consent) }
        }
    }

    /// A turn was refused for want of the consent: its screen, now, whatever was put off before.
    func consentRequired(_ consent: TafsCloud.AIConsent) {
        guard mode == .kiff, consent.drawable else { return }
        aiConsent = consent
        aiConsentError = nil
        aiConsentAsked = consent
    }

    /// Reads the consent, with each refresh. Not given, its screen is put up, unless Not now was
    /// answered this session. A tafs cloud without the route leaves none, and asks nothing.
    func loadAIConsent() async {
        guard mode == .kiff else { return }
        let gen = accountGeneration
        guard let read = try? await cloud.aiConsent(), gen == accountGeneration else { return }
        aiConsent = read
        if !read.given, read.drawable, !aiConsentPutOff, aiConsentAsked == nil { aiConsentAsked = read }
    }

    /// Allow, for the version of the words on the screen. An older screen's yes is refused by tafs
    /// cloud with the new words, which then show in its place.
    func allowAI() async {
        guard let asked = aiConsentAsked ?? aiConsent else { return }
        let gen = accountGeneration
        aiConsentBusy = true
        defer { aiConsentBusy = false }
        do {
            let given = try await cloud.allowAI(version: asked.version)
            guard gen == accountGeneration else { return }
            aiConsent = given
            aiConsentError = nil
            aiConsentAsked = nil
        } catch let e as TafsCloud.CloudError where e.status == 409 {
            guard gen == accountGeneration else { return }
            aiConsentError = e.errorDescription
            var newer = e.consent
            if newer == nil { newer = try? await cloud.aiConsent() }
            if let newer, gen == accountGeneration {
                aiConsent = newer
                aiConsentAsked = newer
            }
        } catch {
            guard gen == accountGeneration else { return }
            aiConsentError = error.localizedDescription
        }
    }

    /// Not now: the screen closes, and nothing is sent to the model. Trying Coach or a chat asks again.
    func notNowAI() {
        aiConsentPutOff = true
        aiConsentAsked = nil
        aiConsentError = nil
    }

    /// Withdraw, from Account: from then on, nothing this person writes reaches the model.
    func withdrawAI() async {
        let gen = accountGeneration
        aiConsentBusy = true
        defer { aiConsentBusy = false }
        do {
            let now = try await cloud.withdrawAI()
            guard gen == accountGeneration else { return }
            aiConsent = now
            aiConsentError = nil
            aiConsentPutOff = true
        } catch {
            guard gen == accountGeneration else { return }
            aiConsentError = error.localizedDescription
        }
    }

    /// A new account's consent is its own: nothing of the last one's stays.
    private func forgetAIConsent() {
        aiConsent = nil; aiConsentAsked = nil; aiConsentError = nil; aiConsentPutOff = false
    }
    /// The account's "Show suggestions" setting as tafs cloud last said it, while it's being
    /// changed, and why a change didn't go.
    @Published var suggestions: TafsCloud.Suggestions?
    @Published var suggestionsBusy = false
    @Published var suggestionsError: String?
    /// What the owner wrote while Setup works: sent once it's done (kiff/tafs#58).
    /// What waits in Coach's chat to go once Coach is done: tafs cloud holds it (docs/chat.md,
    /// `hold`), and sends it as the person's next message. The app keeps no queue of its own.
    var setupQueued: String? { setupThread.steering?.held?.text }
    /// How Setup's last run ended, when the owner should know: they stopped it, or it
    /// failed part-way, with the message that started it to send again.
    enum RunEnd: Equatable { case stopped, failed(retry: String) }
    @Published private(set) var setupEnded: RunEnd?

    /// The Setup chat shown (kiff/tafs#73): one the owner reopened or started here, the
    /// newest at launch (kiff/tafs#389), or nil for the latest ones together when there's
    /// no list to pick from.
    @Published private(set) var setupChat: String?
    /// The owner asked for a new conversation: the next message starts one.
    @Published private(set) var setupFresh = false
    /// Words were handed to this conversation from Home (a recipe): on a phone where Coach
    /// isn't started yet, the message box shows with them, and Send starts Coach.
    @Published private(set) var setupHanded = false
    /// Coach's tools as tafs cloud reads them from KIFF; nil before the read, and from a tafs
    /// cloud that doesn't say.
    @Published private(set) var coachTools: TafsCloud.CoachTools?
    /// The tools step is asked: at the end of a first Start, until given or put off.
    @Published var coachToolsAsked = false
    /// tafs cloud's first start for Coach (`GET /v1/setup`'s `start`), while the account has no
    /// Coach; nil from a tafs cloud that sends none, which starts Coach as before.
    @Published private(set) var coachStart: TafsCloud.CoachStart?
    @Published private(set) var coachToolsError: String?
    @Published private(set) var givingCoachTools = false
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

    /// Where Setup's next message goes. Every one says the app follows a new chat: tafs cloud
    /// starts one for a message to a chat that already handed a taf over (`followNewSetupChat`).
    private var setupTarget: TafsCloud.ChatTarget {
        var target = setupFresh ? TafsCloud.ChatTarget.new : setupChat.map { TafsCloud.ChatTarget(session: $0) } ?? .live
        target.newChatAfterHandover = true
        return target
    }

    /// The taf a new Setup conversation improves (kiff/tafs#107), by its id in tafs cloud:
    /// its first message goes to tafs cloud's improve route, which puts the taf's context in
    /// front. Nil for any other conversation.
    @Published private(set) var setupImproving: TafsCloud.CloudTaf?
    private var setupContextTafID: String?
    private var setupConversationGeneration = 0

    // MARK: Coach mode (kiff/tafs#138)

    /// Coach mode is open: Setup's conversation, over whatever is on screen.
    @Published var coachOpen = false
    /// The taf whose page is on screen, the context Coach mode opens with; nil elsewhere.
    @Published var tafOnScreen: UUID?

    /// Opens Coach on the taf in view; elsewhere it reopens the current conversation.
    /// Returning to the same taf keeps its thread. Accepted work continues on the service.
    func openCoach() {
        // The Coach button while Coach drives the app stops it (docs/drive.md).
        if DriveSession.shared.isOn {
            DriveSession.shared.stop("stopped")
            return
        }
        if let taf = tafOnScreen, setupContextTafID != cloudTaf(for: taf)?.id {
            improveTaf(taf)
        }
        coachOpen = true
    }

    /// A new conversation in Coach mode is on no taf (kiff/tafs#138, item 36): the owner
    /// asked for a new taf in one opened on a taf's page, and got that taf's next version.
    /// The taf on screen is offered in it, to improve with one tap. The pencil is a plain chat
    /// for good: Coach reopened on the same taf's page stays on it, never back in that taf's mode,
    /// so the taf on screen is marked as already chosen against.
    func newCoachConversation() {
        let onScreen = tafOnScreen.flatMap { cloudTaf(for: $0)?.id }
        newSetupChat()
        setupContextTafID = onScreen
    }

    /// Coach's conversation has nothing in it yet: no message, card or answer, sent or on its
    /// way, and no taf it improves. New conversation keeps it, with what's typed. An empty one
    /// about a taf ("Improve Refunds") isn't: the pencil opens a plain new one, so it always
    /// opens a plain chat.
    var coachChatIsEmpty: Bool {
        setupImproving == nil && setupThread.items.isEmpty && setupSent.isEmpty && setupUnsent.isEmpty
            && setupQueued == nil && setupLive == nil && !setupThinking
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
        setupContextTafID = setupImproving?.id
    }

    /// Coach on a taf, with words in the box for the owner to send (kiff/tafs#476, gaps 10 and
    /// 15): a not-done call's Ask Coach, or a taf's ask_coach card. Nothing is sent. Only for
    /// someone who may use Coach and manages the taf.
    func askCoachAbout(_ tafID: UUID, words: String) {
        guard mode == .kiff, mayUseCoach, manages(tafID) else { return }
        improveTaf(tafID)
        setupDraft = words
        coachOpen = true
    }

    /// What a not-done call's next step does on this taf's Activity row (kiff/tafs#476, gap 10).
    func nextStep(_ next: TafsCloud.NextStep, tafID: UUID, fallback: String) -> NextStepWords.Action {
        NextStepWords.action(next, agent: cloudTaf(for: tafID)?.kiffAgent, grants: grants, fallback: fallback)
    }

    func discussCardUnit(_ ask: TafsCloud.CardUnitException) {
        guard mode == .kiff, mayUseCoach,
              let taf = tafs.first(where: { cloudTaf(for: $0.id)?.id == ask.taf }), manages(taf.id) else { return }
        improveTaf(taf.id)
        setupDraft = ask.coach_text
        coachOpen = true
    }

    /// Home's steps, less those answered here that it still lists; an answered step Home no
    /// longer lists is forgotten.
    private func showSteps(_ listed: [TafsCloud.YourStep]) {
        answeredSteps.formIntersection(listed.map(\.handoff))
        answeredStillCounted = answeredSteps.count
        yourSteps = listed.filter { !answeredSteps.contains($0.handoff) }
    }

    /// Whether a step still waits for an answer from this phone: not one it just answered.
    func canAnswer(step handoff: String) -> Bool { !answeredSteps.contains(handoff) }

    /// The step `handoff` waits on, from Home's list or, for a taf's request, its work.
    func yourStep(_ handoff: String) -> TafsCloud.YourStep? {
        yourSteps.first { $0.handoff == handoff }
    }

    /// Whether this phone can answer a step: as for a hold, KIFF checks the device key's
    /// signature, so the session must hold it, and the person must answer the account's holds.
    var canAnswerSteps: Bool {
        mode == .kiff && canAnswerHolds != false && kiffTenant != nil && (sessionHasKey || !kiff.serverVerifiesSignatures)
    }

    /// Answers a step a taf handed its owner (docs/api.md › Your step): "done" or "skipped", to
    /// KIFF, as Android does. The hand-off is read from KIFF first, for the version the phone
    /// signs; one KIFF no longer waits on isn't sent. The answer is signed with the device key over
    /// `kiff.handoff.v1`: it grants nothing, so the approval key isn't asked for. KIFF tells the
    /// taf's run, which goes on: "Done" checks the vendor where it can, and "Skip it" withdraws
    /// what it prepared. `tafID` is the taf whose page it was answered on; Needs you's steps name
    /// their taf.
    func answerYourStep(_ step: TafsCloud.YourStep, done: Bool, tafID: UUID? = nil) async {
        guard mode == .kiff, !answeringSteps.contains(step.handoff), !answeredSteps.contains(step.handoff) else { return }
        guard canAnswerSteps, let tenant = kiffTenant else {
            lastError = kiff.serverVerifiesSignatures && !sessionHasKey ? Words.NeedsYou.signInAgainToAnswer : Words.NeedsYou.answerInKiff
            return
        }
        answeringSteps.insert(step.handoff)
        let gen = accountGeneration
        defer { if gen == accountGeneration { answeringSteps.remove(step.handoff) } }
        let answer = done ? "done" : "skipped"
        let current: KIFFClient.Handoff
        do {
            current = try await kiff.handoff(step.handoff)
        } catch {
            if gen == accountGeneration { lastError = Words.NeedsYou.YourStep.notAnswered(why: Self.answerRefusedWords(error)) }
            return
        }
        guard gen == accountGeneration else { return }
        guard current.status.isEmpty || current.status == "waiting" else {
            lastError = Words.NeedsYou.YourStep.ended
            drop(step)
            return
        }
        let at = SigningTime.next(now: clock())
        let message = KIFFClient.handoffMessage(tenant: tenant, handoffID: current.id, answer: answer, version: current.version, signedAt: at)
        let value: String
        do {
            // The prompt is tafs cloud's words for the answer and the step: "Done · Approve the transfer to Acme".
            let prompt = [done ? step.words.done : step.words.skip, step.what].filter { !$0.isEmpty }.joined(separator: " · ")
            value = try await signer.sign(message, reason: prompt)
        } catch {
            guard gen == accountGeneration else { return }
            lastError = ApprovalReadiness.cancelled(error) ?? Words.NeedsYou.YourStep.notAnsweredUnconfirmed
            return
        }
        guard gen == accountGeneration else { return }
        do {
            try await kiff.answerHandoff(current.id, answer: answer, signature: .init(signedAt: at, value: value))
        } catch KIFFClient.KIFFError.server(409, _, _) {
            // Answered meanwhile, on another phone, or ended: nothing more to do here.
            if gen == accountGeneration { lastError = Words.NeedsYou.YourStep.ended; drop(step) }
            return
        } catch {
            if gen == accountGeneration { lastError = Words.NeedsYou.YourStep.notAnswered(why: Self.answerRefusedWords(error)) }
            return
        }
        guard gen == accountGeneration else { return }
        drop(step)
        // The taf's work shows the run going on once it hears the answer.
        let taf = tafID ?? step.taf.flatMap { t in cloudTafs.first { $0.value.id == t.id }.flatMap { holderIDs[$0.key] } }
        await refresh()
        if let taf, works[taf] != nil { await loadWork(taf) }
    }

    /// Takes an answered or ended step off Needs you at once, and its count, while Home still lists it.
    private func drop(_ step: TafsCloud.YourStep) {
        answeredSteps.insert(step.handoff)
        if yourSteps.contains(where: { $0.handoff == step.handoff }) { answeredStillCounted += 1 }
        yourSteps.removeAll { $0.handoff == step.handoff }
        if requestedStep == step.handoff { requestedStep = nil }
    }

    /// The app as the owner knows it, for a Face ID or Touch ID prompt: "Stripe", never a server's
    /// slug ("stripe-mcp") or host.
    nonisolated static func promptApp(_ grant: PlanqueClient.Grant) -> String {
        KIFFMapping.vendorWords(grant.vendor)
    }

    /// A connection's app as Connect apps names it ("Gmail"), by its grant or its catalog entry,
    /// else planque's vendor in people's words (as Android's `ConnectionWords.appName`).
    func appName(_ grant: PlanqueClient.Grant) -> String {
        let listed = connectApps?.apps.first { $0.grant == grant.id || (grant.entry != nil && $0.entry == grant.entry) }
        if let name = listed?.displayName.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty { return name }
        return Self.promptApp(grant)
    }

    /// A sign-in card's app: the card's service, else the connection's app; nil when neither is
    /// known (as Android's `signInWords`, Android 1.27.8).
    nonisolated static func signInApp(service: String?, app: String?) -> String? {
        [service, app].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty }
    }

    /// A sign-in card's title: "Sign in at Gmail", and "Sign in at the vendor" only when no app is
    /// known. Never a connection's status.
    nonisolated static func signInWords(app: String?) -> String {
        app.map { Words.Coach.SignIn.at(vendor: "\($0)") } ?? Words.Coach.SignIn.atVendor
    }

    /// The prompt read last before connecting: "Connect Stripe to Disputes", or "Connect Stripe"
    /// when no taf is named yet, never "to a taf" (as Android's `connectPrompt`).
    func connectPrompt(_ grant: PlanqueClient.Grant) -> String {
        let app = Self.promptApp(grant)
        guard let taf = namedTaf(grant.request.agent, grant: grant) else { return Words.Connect.Prompt.connectApp(vendor: "\(app)") }
        return Words.Connect.Prompt.connect(vendor: "\(app)", agent: "\(taf)")
    }

    /// The same, for a connection by key: "Give Stripe its key for Disputes", or "Give Stripe its key".
    func giveKeyPrompt(_ grant: PlanqueClient.Grant) -> String {
        let app = Self.promptApp(grant)
        guard let taf = namedTaf(grant.request.agent, grant: grant) else { return Words.Connect.Prompt.giveKeyApp(vendor: "\(app)") }
        return Words.Connect.Prompt.giveKey(vendor: "\(app)", agent: "\(taf)")
    }

    /// The taf a request is for, by its name: planque's, the label given, or the app's taf for
    /// that agent; nil when none is named.
    func namedTaf(_ agent: String?, grant: PlanqueClient.Grant? = nil, label: String? = nil) -> String? {
        [grant?.agentName, label, agent.flatMap { holderIDs[$0] }.flatMap { taf($0)?.name }]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty }
    }

    /// The taf as its owner knows it, for a Face ID or Touch ID prompt, the last thing read before
    /// signing: its name, as planque or the draft keeps it or as Home lists it, never its agent id.
    func promptTaf(_ agent: String?, grant: PlanqueClient.Grant? = nil, label: String? = nil) -> String {
        namedTaf(agent, grant: grant, label: label) ?? Words.NeedsYou.aTaf
    }

    func dismissCardUnit(_ exception: TafsCloud.CardUnitException) async {
        guard mode == .kiff, canAnswerHolds != false, let tenant = kiffTenant,
              let taf = tafs.first(where: { cloudTaf(for: $0.id)?.id == exception.taf }),
              let agent = cloudTaf(for: taf.id)?.kiffAgent, dismissingCardUnits.insert(exception.id).inserted else { return }
        let gen = accountGeneration
        defer { if gen == accountGeneration { dismissingCardUnits.remove(exception.id) } }
        do {
            let current = try await kiff.cardException(exception.id)
            guard gen == accountGeneration else { return }
            guard current.kind == "card_unit", current.status == "held", current.subject == agent, let version = current.accountingVersion else {
                lastError = Words.NeedsYou.CardUnit.changed
                return
            }
            let at = SigningTime.next()
            let message = KIFFClient.answerMessage(tenant: tenant, exceptionID: current.id, answer: Self.kiffAnswer(.refuse), nextStep: nil, accountingVersion: version, signedAt: at)
            let signature = try await signer.sign(message, reason: Words.NeedsYou.CardUnit.dismissPrompt(title: "\(exception.title)"))
            guard gen == accountGeneration else { return }
            try await kiff.reject(current.id, signature: .init(signedAt: at, value: signature))
            guard gen == accountGeneration else { return }
            // KIFF confirmed the dismissal: cached views stop offering the same answer at once.
            presentationGeneration += 1
            cardUnitExceptions.removeAll { $0.id == exception.id }
            for (key, var view) in tafSetups {
                view.card_unit_exceptions?.removeAll { $0.id == exception.id }
                tafSetups[key] = view
            }
            for (key, var cache) in activityPages {
                cache.pages = cache.pages.map { old in
                    var page = old
                    page.card_unit_exceptions?.removeAll { $0.id == exception.id }
                    return page
                }
                cache.etag = nil
                cache.loading = false; cache.moreWhenReady = false
                activityPages[key] = cache
            }
            for (key, var cache) in runPages {
                cache.loading = false; cache.moreWhenReady = false
                runPages[key] = cache
            }
            await refresh()
            await loadTafSetup(taf.id)
        } catch {
            if gen == accountGeneration { lastError = Words.NeedsYou.CardUnit.notDismissed(why: error.localizedDescription) }
        }
    }

    /// A new Setup conversation: an empty chat, and the next message starts it.
    func newSetupChat() {
        setupConversationGeneration += 1
        setupStream?.cancel(); setupStream = nil; setupLive = nil; setupSending = false
        setupContextTafID = nil
        setupImproving = nil
        setupChat = nil
        setupFresh = true
        setupHanded = false
        setupThread = SetupThread()
        setupSent = []
        setupAwaiting = nil
        setupUnsent = []
        setupEnded = nil
        setupError = nil
    }

    /// "Finish setting up" on another agent whose setup stopped partway (docs/tafs.md): Coach
    /// opens on the chat where it stopped. Reading that chat is what makes the taf, at tafs cloud;
    /// the next Home read lists it with the tafs. Only Coach's own chat, for someone who may use
    /// Coach; false otherwise, and the row opens as any other agent's.
    func canFinishSetup(_ finish: TafsCloud.HomeTaf.Finish?) -> Bool {
        guard mode == .kiff, mayUseCoach, let finish, finish.usable, let coach = setupTaf?.id else { return false }
        return finish.taf == coach
    }

    func finishSetup(_ finish: TafsCloud.HomeTaf.Finish) async {
        guard canFinishSetup(finish) else { return }
        coachOpen = true
        await openSetupChat(finish.session)
    }

    /// A Coach chat waiting on the owner, from Needs you: Coach opens on that chat, where its card waits.
    func openCoachWaiting(_ waiting: TafsCloud.CoachWaiting) async {
        guard mode == .kiff, mayUseCoach, !waiting.session.isEmpty else { return }
        coachOpen = true
        await openSetupChat(waiting.session)
    }

    /// Set aside, on this row: only signed in (not the sample), and only when tafs cloud sent its
    /// words (`words.set_aside`). The app decides nothing else about it.
    func canSetAside(_ waiting: TafsCloud.CoachWaiting) -> Bool {
        mode == .kiff && !waiting.session.isEmpty && !waiting.words.setAside.isEmpty
    }

    /// "Set all aside", above the rows: only with two or more, signed in, and when tafs cloud sent
    /// `coach_waiting_clear`.
    var setAllAside: TafsCloud.CoachWaitingClear? {
        guard mode == .kiff, coachWaiting.count > 1 else { return nil }
        return coachWaitingClear
    }

    /// Sets a waiting Coach chat aside, or every one with nil (docs/api.md › Coach waiting): tafs
    /// cloud's "not now", never an approval, and nothing is signed. One call per chat at a time: a
    /// chat already being set aside sends nothing until tafs cloud answers. The row goes once tafs
    /// cloud says so, Coach no longer shows that chat, and Home is read again for its count. A
    /// refusal keeps the row and says why, in tafs cloud's words. True once tafs cloud took it.
    @discardableResult
    func setAsideCoach(_ session: String?) async -> Bool {
        guard mode == .kiff else { return false }
        // Set all aside ends every chat tafs cloud listed, so it's guarded by all of them, rows
        // hidden while being set aside included: no second call for a chat already on its way.
        let sessions = session.map { Set([$0]) } ?? Set(coachWaitingListed.map(\.session))
        // Checked and marked before anything is awaited, so on the main actor no second call slips in between.
        guard !sessions.isEmpty, settingAside.isDisjoint(with: sessions) else { return false }
        settingAside.formUnion(sessions)
        let gen = accountGeneration
        // This phone's signed read of its connections: tafs cloud withdraws a connection the chat asked for.
        let signer = self.signer
        planque.tenant = kiffTenant
        var proof: String?
        if planqueReady && sessionHasKey { proof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) }) }
        let done: TafsCloud.SetAside
        do {
            guard gen == accountGeneration else { return false }
            done = try await cloud.setAsideCoach(session, proof: proof)
        } catch {
            guard gen == accountGeneration else { return false }
            settingAside.subtract(sessions)
            lastError = (error as? TafsCloud.CloudError).map { $0.words ?? $0.message } ?? error.localizedDescription
            return false
        }
        guard gen == accountGeneration else { return false }
        settingAside.subtract(sessions)
        let ended = Set(done.setAside)
        coachWaitingListed.removeAll { ended.contains($0.session) }
        setAsideChats.formUnion(ended)
        // Coach no longer shows a chat that was set aside: its next message would go on in the
        // ended session. A new conversation instead, as Android 1.27.1 does.
        if let shown = setupChat ?? (setupFresh ? nil : setupThread.session), ended.contains(shown) { newSetupChat() }
        Haptics.success()
        await refresh()
        return true
    }

    /// Reopens one of Setup's chats: it's shown alone, and messages go on in it.
    func openSetupChat(_ session: String) async {
        setupConversationGeneration += 1
        setupStream?.cancel(); setupStream = nil; setupLive = nil; setupSending = false
        setupContextTafID = nil
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
            lastError = Words.Taf.notArchived(why: "\(error.localizedDescription)")
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
            lastError = Words.Taf.notPutBack(why: "\(error.localizedDescription)")
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

    /// tafs cloud started a new chat for the message just sent to the chat shown, which had
    /// already handed a taf over (docs/api.md, `new_chat_after_handover`): the answer or the
    /// stream's `session` event says `new_session: true` with another session. Coach follows it:
    /// the new chat is the one shown, with the message just sent (`sent`, the local copy) and the
    /// answer streaming into it; the old chat stays under Previous conversations, as tafs cloud
    /// lists it. What else was on its way belonged to the old chat, which tafs cloud has. True
    /// once it moved: the conversation is a new one then, and the caller goes on in it.
    @discardableResult
    private func followNewSetupChat(_ session: String, newSession: Bool, sent: String?) -> Bool {
        guard newSession, !session.isEmpty, let open = setupChat, open != session else { return false }
        setupConversationGeneration += 1
        setupChat = session
        setupFresh = false
        setupSent = setupSent.filter { $0.id == sent }
        setupThread = SetupThread(messages: setupSent)
        setupAnsweredCard = nil
        setupLiveBefore = Set(setupThread.items.map(\.id))
        return true
    }

    /// Setup's KIFF agent: one per account.
    static let setupAgent = "setup"

    /// Reads whether the account has Setup, and its thread.
    func loadSetup() async {
        guard mode == .kiff else { return }
        let gen = accountGeneration
        guard let read = try? await cloud.setupRead(), gen == accountGeneration else { return }
        tookTimeZone(read.timeZone)
        setupTaf = read.setup
        // No Coach yet: tafs cloud's first start, when it sends one, is drawn as the tools step,
        // whose one button starts Coach and gives its tools under one Face ID.
        coachStart = read.setup == nil ? read.start : nil
        showCoachTools(read.setup == nil ? coachStart.flatMap { $0.startable ? $0.tools : nil } : read.tools)
        if read.setup != nil { await loadSetupThread() }
        await loadRecipes()
    }

    /// Reads the recipes a new Coach conversation opens to (kiff/tafs#476). Not read, the
    /// conversation opens without them; Coach still takes any job.
    func loadRecipes() async {
        guard mode == .kiff else { return }
        let gen = accountGeneration
        guard let r = try? await cloud.recipes(), gen == accountGeneration else { return }
        recipes = r
    }

    /// Reads the gallery of ready-made jobs. A read that fails keeps what was read; a tafs
    /// cloud without the route leaves none, so + New opens Coach as before.
    func loadGallery() async {
        guard mode == .kiff else { return }
        let gen = accountGeneration
        do {
            let g = try await cloud.recipesGallery()
            guard gen == accountGeneration else { return }
            gallery = g
        } catch let e as TafsCloud.CloudError where e.status == 404 {
            if gen == accountGeneration { gallery = nil }
        } catch {}
    }

    /// Reads the account's "Show suggestions" setting, for Account.
    func loadSuggestions() async {
        guard mode == .kiff else { return }
        let gen = accountGeneration
        guard let read = try? await cloud.suggestions(), gen == accountGeneration else { return }
        suggestions = read
    }

    /// Turns the account's suggestions off for good, or on again, in tafs cloud: it decides what
    /// a new Coach conversation shows, and the recipes are read again. A refusal is said in tafs
    /// cloud's words.
    func setSuggestions(_ show: Bool) async {
        guard mode == .kiff, !suggestionsBusy else { return }
        let gen = accountGeneration
        suggestionsBusy = true
        suggestionsError = nil
        defer { if gen == accountGeneration { suggestionsBusy = false } }
        do {
            let now = try await cloud.setSuggestions(show)
            guard gen == accountGeneration else { return }
            suggestions = now
            await loadRecipes()
        } catch {
            if gen == accountGeneration { suggestionsError = Words.Settings.suggestionsNotChanged(why: error.localizedDescription) }
        }
    }

    /// A recipe tapped: its words go in Coach's message box, for the person to edit and send.
    /// Nothing is sent.
    func fillRecipe(_ recipe: TafsCloud.Recipes.Recipe) {
        setupDraft = recipe.say
    }

    /// Whether Home with no taf lists the recipes: the account's list is read and empty, and
    /// tafs cloud says to show them (`home.shown`). Home then leads with tafs cloud's line.
    var homeListsRecipes: Bool {
        mode == .kiff && listLoaded && !listUnread && tafs.isEmpty && recipes?.homeListsRecipes == true
    }

    /// A recipe handed to Coach from Home: a new conversation with the recipe's words in the
    /// message box, for the person to edit and send, as a recipe tap in Coach does. Nothing is
    /// sent, and nothing is asked of the person yet: on a phone where Coach isn't started, the
    /// words show first and Send starts it (`submitSetup`).
    func handRecipeToCoach(_ recipe: TafsCloud.Recipes.Recipe) {
        guard mode == .kiff, mayUseCoach else { return }
        newSetupChat()
        setupDraft = recipe.say
        setupHanded = true
        coachOpen = true
    }

    /// Coach is started on this phone: it has its taf, and planque has this phone's key.
    var setupStarted: Bool { setupTaf != nil && planqueReady }

    func loadSetupThread() async {
        guard mode == .kiff, let s = setupTaf else { return }
        // A new conversation has nothing to read until its first message is sent.
        if setupFresh { return }
        let gen = accountGeneration
        let conversation = setupConversationGeneration
        // No conversation chosen yet (at launch): Coach opens on its newest alone, the earlier
        // ones under Previous conversations, never several together (kiff/tafs#389). Without
        // the list, or with none yet, the thread reads as before.
        if setupChat == nil, let chats = try? await cloud.chats(s.id) {
            guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
            setupChats = chats
            if setupChat == nil, !setupFresh, let newest = chats.max(by: { $0.updated < $1.updated }) {
                // A chat set aside here is never the one Coach goes on in: a new conversation instead.
                if setAsideChats.contains(newest.session) { newSetupChat(); return }
                setupChat = newest.session
            }
            if setupFresh { return }
        }
        // This phone's signed read of its connections, and the steps it signs: tafs cloud then marks each
        // approval card still waiting with what its fingerprint will sign (`signs`), so the card draws the
        // Card as being signed only when it is. Without them, the cards come as before.
        let signer = self.signer
        planque.tenant = kiffTenant
        var proof: String?
        if planqueReady && sessionHasKey { proof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) }) }
        guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
        // Coach's run ended since the last read, seen here rather than on a stream: Home is read again.
        var runEnded = false
        do {
            let thread = try await cloud.setupThread(s.id, session: setupChat, proof: proof, can: Self.stepsSignedHere)
            guard gen == accountGeneration, conversation == setupConversationGeneration, !setupFresh else { return }
            // At work at the last read, idle now, and no stream to say so at its end.
            runEnded = setupThread.working != nil && thread.working == nil && setupLive == nil
            setupThread = withSent(thread)
            setupError = nil
            settleSetupAwaiting()
            settleSetupSteering()
            // Coach handed the taf over and said its last words: its run is done, whatever the
            // stream still holds open, so Stop goes (kiff/tafs#476). tafs cloud's `working` still
            // says if a run is at work, and a message on its way keeps it.
            if setupLive != nil, thread.working == nil, !setupSending, setupSent.isEmpty, thread.handedOver(session: setupLive?.session) {
                setupLive = nil
                setupAwaiting = nil
                runEnded = true
            }
        } catch {
            guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
            setupError = Words.Coach.conversationNotLoaded(why: "\(error.localizedDescription)")
            return
        }
        // Setup ended or was handed over: Needs you's row for this chat, and its badge, go now.
        if runEnded {
            await refresh()
            guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
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
    }

    /// Starts connecting tools, once per account, behind one Face ID: planque gets this
    /// phone's key and a connector key, and Setup is started from a draft with no tools,
    /// whose key tafs cloud claims. Done before the first message, so every grant Setup
    /// asks for can be read and shown.
    func startSetup() async -> Bool {
        guard mode == .kiff, sessionHasKey, let tenant = kiffTenant else {
            setupError = Words.SignIn.againToSign
            return false
        }
        if planqueReady, setupTaf != nil { return true }
        guard !startingSetup else { return false }
        startingSetup = true
        defer { startingSetup = false }
        let gen = accountGeneration
        do {
            _ = try await signer.sign(Data("tafs.setup.v1|\(tenant)".utf8), reason: Words.Coach.Prompt.connectTools, grants: true)
        } catch { return false }
        guard gen == accountGeneration else { return false }
        let signer = self.signer
        kiff.tenant = tenant
        kiff.setupSigner = { try signer.signConfirmed($0) }
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        defer { kiff.setupSigner = nil; planque.signer = nil }
        // Approvals are set up here, not before sign-in (docs/approvals.md): a phone that signed in
        // before it could approve gives KIFF its approval key now, under the same Face ID.
        if !sessionHasApprovalKey { await registerApprovalKey() }
        guard gen == accountGeneration else { return false }
        // tafs cloud's first start, the pair or the draft alone, is signed by the approval key: with a
        // key KIFF doesn't hold, nothing is sent, and it says so in the words both phones use.
        if coachStart?.startable == true, approvalKeyUnheld {
            setupError = approvalKeyRefused()
            return false
        }
        await keepKIFFAlerts(tenant: tenant, gen: gen)
        if !planqueReady {
            guard await enrolPlanque(tenant: tenant, gen: gen) else {
                setupError = lastError
                return false
            }
        }
        if setupTaf == nil {
            do {
                let read = try await cloud.setupRead()
                guard gen == accountGeneration else { return false }
                if let existing = read.setup {
                    setupTaf = existing
                } else if let start = read.start, start.startable {
                    // A start read only now, with a key KIFF doesn't hold: nothing is sent.
                    if approvalKeyUnheld {
                        coachStart = start
                        setupError = approvalKeyRefused()
                        return false
                    }
                    // One Face ID, the one just asked: Coach's tools and its draft with its Cards.
                    coachStart = start
                    setupTaf = try await startCoachTogether(start, tenant: tenant, gen: gen)
                } else {
                    let draft = try await kiff.submitNoToolsDraft(agentID: Self.setupAgent, label: "Setup")
                    guard gen == accountGeneration, let secret = draft.claimSecret else {
                        setupError = Words.Kiff.kiffDidNotAnswerExpected
                        return false
                    }
                    setupTaf = try await cloud.startSetup(draftID: draft.id, claimSecret: secret)
                }
            } catch {
                guard gen == accountGeneration else { return false }
                setupError = Words.Coach.couldntStart(why: error.localizedDescription)
                return false
            }
        }
        await refreshConnections()
        await loadSetupThread()
        // A first Start ends on the step that asks for Coach's tools, behind its own Face ID
        // (docs/coach.md): nothing is given inside this one.
        await loadCoachTools()
        if gen == accountGeneration, coachTools?.toGive == true { coachToolsAsked = true }
        return true
    }

    /// Coach's first start as tafs cloud built it, under the Face ID that confirmed Start: the
    /// tools batch and Coach's draft, each built in full with one `signed_at`, signed once by the
    /// approval key over both hashes (`kiff.requests.v1`); the batch goes first, the draft once the
    /// batch answered, then tafs cloud claims the draft's key. With every tool connected, the draft
    /// goes alone. A draft KIFF refuses after the batch went in is started again from the next read,
    /// which carries the draft alone, under a new Face ID: never the pair again.
    private func startCoachTogether(_ start: TafsCloud.CoachStart, tenant: String, gen: Int) async throws -> TafsCloud.CloudTaf? {
        guard let draftRequest = start.draft else { return nil }
        let draft: KIFFClient.AgentDraft
        if start.together, let connectRequest = start.connect {
            guard let sent = try await sendTogether(connectRequest, draftRequest, tenant: tenant, gen: gen) else { return nil }
            draft = sent
        } else {
            draft = try await kiff.submitDraftAnswered(draftRequest.body)
        }
        guard gen == accountGeneration else { return nil }
        guard let secret = draft.claimSecret else { throw KIFFClient.KIFFError.server(status: 0, message: Words.Kiff.kiffDidNotAnswerExpected) }
        let started = try await cloud.startSetup(draftID: draft.id, claimSecret: secret)
        guard gen == accountGeneration else { return nil }
        coachStart = nil
        return started
    }

    /// The tools batch and a Cards' draft, as tafs cloud built them, each in full with one
    /// `signed_at`, signed once by the approval key over both hashes (`kiff.requests.v1`), under
    /// the Face ID that just confirmed the step: the batch first, the draft once it answered.
    private func sendTogether(_ connectRequest: TafsCloud.CoachStart.Request, _ draftRequest: TafsCloud.CoachStart.Request,
                              tenant: String, gen: Int) async throws -> KIFFClient.AgentDraft? {
        let signedAt = SigningTime.next()
        let batch = try kiff.prepare(method: connectRequest.method, path: connectRequest.path, body: connectRequest.body, gateway: true, signedAt: signedAt)
        let cards = try kiff.prepare(method: draftRequest.method, path: draftRequest.path, body: draftRequest.body, gateway: false, signedAt: signedAt)
        let batchHash = RequestsTogether.hash(batch.message), cardsHash = RequestsTogether.hash(cards.message)
        let signature = try signer.signApprovalConfirmed(RequestsTogether.message(tenant: tenant, signedAt: signedAt, batchHash, cardsHash))
        try await kiff.connectBatch(batch, signatureHeader: RequestsTogether.header(signedAt: signedAt, signature: signature, with: cardsHash))
        guard gen == accountGeneration else { return nil }
        return try await kiff.submitDraft(cards, signatureHeader: RequestsTogether.header(signedAt: signedAt, signature: signature, with: batchHash))
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
    enum Delivery {
        case sent, kept, notTried

        /// What the person feels as their Send ends (Haptics): sent, a success tick; tried and
        /// kept unsent, a warning; not tried (nothing to send, busy), nothing more than the press.
        var feel: Haptics.Kind? {
            switch self {
            case .sent: return .success
            case .kept: return .warning
            case .notTried: return nil
            }
        }
    }

    /// A send to Coach that went through ticks (Haptics), whichever way it went; one kept unsent warns.
    private func deliverSetup(_ text: String, files: [TafsCloud.AttachmentRef] = []) async -> Delivery {
        let delivery = await deliverSetupOnce(text, files: files)
        Haptics.play(delivery.feel)
        return delivery
    }

    private func deliverSetupOnce(_ text: String, files: [TafsCloud.AttachmentRef] = []) async -> Delivery {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !files.isEmpty, text.count <= Self.chatLimit, let s = setupTaf, !setupSending else { return .notTried }
        // An improvement's first message carries no files: tafs cloud adds the taf's context to it.
        if !files.isEmpty, setupImproving != nil { return .notTried }
        // A var: a new chat tafs cloud started for this message is still this send's (`followNewSetupChat`).
        var conversation = setupConversationGeneration
        setupSending = true
        defer { if conversation == setupConversationGeneration { setupSending = false } }
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
                    if conversation == setupConversationGeneration { setupAwaiting = nil }
                    throw error
                }
                guard gen == accountGeneration, conversation == setupConversationGeneration else { return .notTried }
                setupImproving = nil
                if let id = sent.message?.id { markSetupSent(mine.id, serverID: id) }
                settleSetupChat(sent.session)
                await loadSetupThread()
                return .sent
            }
            // Streamed, so Setup's words show as it writes them (#60); sent plainly
            // when the stream can't open.
            if let events = try await openStream(s.id, text: text, to: setupTarget, files: files) {
                guard gen == accountGeneration, conversation == setupConversationGeneration else { return .notTried }
                setupAwaiting = clock()
                setupStreamWorks = true
                setupLiveBefore = Set(setupThread.items.map(\.id))
                setupAnsweredCard = SetupThread.Answer(text).map { cardRef($0.id) }
                setupLive = LiveAnswer(started: clock())
                let sentID = mine.id
                setupStream = Task { await self.followSetupStream(events, text: text, gen: gen, conversation: conversation, sent: sentID) }
                return .sent
            }
            let sent = try await cloud.sendMessage(s.id, text: text, to: setupTarget, files: files)
            guard gen == accountGeneration, conversation == setupConversationGeneration else { return .notTried }
            settleSetupChat(sent.session)
            if followNewSetupChat(sent.session, newSession: sent.newSession, sent: mine.id) { conversation = setupConversationGeneration }
            if let id = sent.message?.id { markSetupSent(mine.id, serverID: id) }
            setupAwaiting = clock()
            await loadSetupThread()
            return .sent
        } catch let e as TafsCloud.CloudError where e.code == TafsCloud.aiConsentRequired {
            // Refused for want of the AI-data consent: its screen says why, and the message goes
            // back in the composer, with no Not sent row, to send once it's allowed.
            guard gen == accountGeneration, conversation == setupConversationGeneration else { return .notTried }
            setupSent.removeAll { $0.id == mine.id }
            setupThread.messages.removeAll { $0.id == mine.id }
            return .notTried
        } catch let e as TafsCloud.CloudError where Self.waitsForCredits(e) != nil {
            // Coach past its fair use waits as the tafs do: its words, the message kept to send later.
            guard gen == accountGeneration, conversation == setupConversationGeneration else { return .notTried }
            setupSent.removeAll { $0.id == mine.id }
            setupThread.messages.removeAll { $0.id == mine.id }
            setupError = Self.waitsForCredits(e)
            return .notTried
        } catch {
            guard gen == accountGeneration, conversation == setupConversationGeneration else { return .notTried }
            setupSent.removeAll { $0.id == mine.id }
            setupThread.messages.removeAll { $0.id == mine.id }
            if !files.isEmpty {
                // Left with the composer, files and all.
                fileNotices[Self.setupFiles] = error.localizedDescription
                return .notTried
            }
            setupUnsent.append(UnsentMessage(text: text, why: error.localizedDescription, at: clock(), halted: Self.haltOf(error)))
            return .kept
        }
    }

    /// The composer's Send: now, or, while Coach is at work, to its run, once, with `hold`
    /// (docs/chat.md), as a taf's chat and Android do: what the run doesn't take, tafs cloud
    /// holds and sends once Coach is done; a second message joins what waits. True once the
    /// message is taken: sent, written to the run, held, or kept in the thread as not sent.
    /// False leaves it with the composer: nothing to send, no run to write to yet, more than
    /// tafs cloud holds, or no Setup.
    @discardableResult
    func submitSetup(_ text: String, files: [TafsCloud.AttachmentRef] = []) async -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // Words first (docs/ios-app.md): on a phone where Coach isn't started, Send starts it,
        // behind its one prompt, and then sends. A Start that's cancelled or fails sends
        // nothing: the words stay with the composer.
        if !setupStarted {
            guard !text.isEmpty || !files.isEmpty else { return false }
            // A phone that can't approve yet starts nothing and is asked nothing: the words stay.
            guard !approvalBlocked else { return false }
            if coachStart?.oneScreen == true {
                // tafs cloud's first start, on Start's own screen: what's signed is drawn above the
                // box, so Send signs it, under the one Face ID, then sends.
                _ = await giveCoachTools()
                guard setupStarted else { return false }
            } else if coachStart?.startable == true {
                // A tafs cloud from before Start's words: the step that shows what Coach is given
                // opens first, and its one button starts Coach. The words stay in the composer.
                coachToolsAsked = true
                return false
            } else {
                guard await startSetup() else { return false }
            }
        }
        // A message with files goes as a message of its own (kiff/tafs#209): the composer sends it once Setup is done.
        if !files.isEmpty { return await deliverSetup(text, files: files) == .sent }
        guard !text.isEmpty else { return false }
        if setupThinking || setupSending {
            // The message that starts Coach's run is still on its way: no run yet, so it stays in the composer.
            guard let s = setupTaf, let run = setupLive?.run ?? setupThread.workingRun else { return false }
            let conversation = setupConversationGeneration
            do {
                switch try await cloud.steer(s.id, run: run, text: text) {
                case .taken(let status):
                    guard conversation == setupConversationGeneration else { return true }
                    // Shown at once, as the run now holds it; the next read says the same.
                    let current = setupThread.steering.flatMap { $0.run == run ? $0 : nil }
                    setupThread.steering = TafsCloud.Steering(
                        run: run, status: status, untaken: (current?.untaken ?? []) + [TafsCloud.Steering.Message(text: text, at: clock())],
                        held: current?.held, notSent: current?.notSent ?? [])
                case .kept(let sent, let held):
                    guard conversation == setupConversationGeneration else { return true }
                    // tafs cloud holds what the run didn't take, for this chat, and sends it once Coach is done.
                    let current = setupThread.steering
                    setupThread.steering = TafsCloud.Steering(
                        run: current?.run ?? run, status: current?.status ?? "RUNNING", untaken: current?.untaken ?? [],
                        held: held, notSent: current?.notSent ?? [])
                    // Sent already: the new run is in the thread.
                    if sent != nil {
                        setupAwaiting = clock()
                        await loadSetupThread()
                    }
                }
                return true
            } catch let e as TafsCloud.CloudError where e.code == "held_full" {
                // More than tafs cloud holds: nothing is cut, the words stay in the composer, with tafs cloud's why.
                setupError = e.message
                return false
            } catch let e as TafsCloud.CloudError where Self.waitsForCredits(e) != nil {
                setupError = Self.waitsForCredits(e)
                return false
            } catch let e as TafsCloud.CloudError where e.code == TafsCloud.aiConsentRequired {
                // The consent's screen says why; the words stay in the composer.
                return false
            } catch {
                // Not reached: it stays in the thread as not sent, with why and Retry; never resent on its own.
                setupUnsent.append(UnsentMessage(text: text, why: error.localizedDescription, at: clock(), halted: Self.haltOf(error)))
                return true
            }
        }
        return await deliverSetup(text) != .notTried
    }

    /// What Coach's run never got to, as its thread says, shown once as not sent, with why and
    /// Retry, which sends it as a new message (docs/chat.md): tafs cloud's `not_sent`, in its words.
    /// tafs cloud sends the rest itself, so nothing here is sent on its own.
    private func settleSetupSteering() {
        guard let s = setupThread.steering, !s.notSent.isEmpty else { return }
        func key(_ m: TafsCloud.Steering.NotSent) -> String {
            let digest = SHA256.hash(data: Data("coach|\(s.run)|\(m.at.timeIntervalSince1970)|\(m.text)".utf8))
            return digest.map { String(format: "%02x", $0) }.joined()
        }
        let fresh = s.notSent.filter { !setupSettled.contains(key($0)) && !(remembered?.stringArray(forKey: Self.settledKey) ?? []).contains(key($0)) }
        guard !fresh.isEmpty else { return }
        // Shown: dealt with in memory only, until Retry, Edit or Delete takes it.
        setupSettled.formUnion(fresh.map(key))
        for m in fresh { setupUnsent.append(UnsentMessage(text: m.text, why: m.why, at: clock(), settle: key(m))) }
    }

    /// Coach's `not_sent` messages shown in this launch, by hash.
    private var setupSettled: Set<String> = []

    /// Sends a message that didn't go through again; an answer's card goes quiet again.
    /// It stays where it was until it's sent or queued: a send that fails puts it back
    /// with the new why, and one that can't start (too long to join the queue) leaves it.
    func retrySetup(_ message: UnsentMessage) async {
        guard !setupSending, let at = setupUnsent.firstIndex(where: { $0.id == message.id }) else { return }
        let answer = SetupThread.Answer(message.text)
        // The card was answered since: sending this too would answer it twice.
        if let answer, setupThread.answer(to: answer.id) != nil {
            setupUnsent.remove(at: at)
            rememberTaken(message)
            return
        }
        setupUnsent.remove(at: at)
        let left = setupUnsent.count
        if let answer { answered.insert(cardRef(answer.id)) }
        if !(await submitSetup(message.text)) {
            // Not taken at all (too long to join the queue, or no Setup): back in its place.
            if let answer { answered.remove(cardRef(answer.id)) }
            setupUnsent.insert(message, at: min(at, setupUnsent.count))
        } else if setupUnsent.count > left {
            // Tried and kept again as not sent: its card can be answered again, and the new row
            // is still owed, so it carries its hash on.
            if let answer { answered.remove(cardRef(answer.id)) }
            if message.settle != nil { setupUnsent[setupUnsent.count - 1].settle = message.settle }
        } else {
            rememberTaken(message)
        }
    }

    /// Drops a message that didn't go through; an answer's card can be answered again.
    func discardUnsent(_ message: UnsentMessage) {
        setupUnsent.removeAll { $0.id == message.id }
        if let answer = SetupThread.Answer(message.text) { answered.remove(cardRef(answer.id)) }
        rememberTaken(message)
    }

    /// Takes a message that didn't go through out of Setup's thread, to change it in the
    /// composer (kiff/tafs#476): its words as the owner reads them, or nil when it's gone
    /// already. An answer's card can be answered again, on the card.
    func editUnsent(_ message: UnsentMessage) -> String? {
        guard setupUnsent.contains(where: { $0.id == message.id }) else { return nil }
        discardUnsent(message)
        return setupThread.ownerWords(message.text)
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
        /// Never drawn: the live line says what it's doing.
        var thinking = ""
        /// tafs cloud's live line, once its stream sends one (docs/chat.md, "The live line").
        var state: LiveState?
        /// The tools it called in this answer, in order, and how each went (kiff/tafs#285).
        var steps: [LiveStep] = []

        /// The live line, as Android draws it (`Chat.liveLine`): tafs cloud's while its stream
        /// sends one, else the step in progress (`current`, in its words), else "Writing…" or
        /// "Thinking…". The thinking text is never in it.
        func line(current: String?) -> String {
            state?.line ?? current ?? (text.isEmpty ? Words.Chat.thinking : Words.Chat.writing)
        }

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
            text = ""; parts = []; steps = []; activity = nil; thinking = ""; state = nil
        }

        /// A call ended: the step it began, or the latest still running when the stream
        /// didn't say which (a held call).
        mutating func ended(_ call: String?, _ status: LiveStep.Status) {
            let i = call.flatMap { c in steps.lastIndex { $0.call == c } } ?? steps.lastIndex { $0.status == .running }
            if let i { steps[i].status = status }
        }
    }

    /// One tool call of an answer as it streams (kiff/tafs#285).
    /// tafs cloud's live line: what the taf does now, in its state and, while working, its words.
    struct LiveState: Equatable {
        var state: String
        var words: String?

        /// The line in words, as Android draws it (`Chat.stateLine`): the step's or the note's
        /// own while working, else what the state is. None at `done`. A state the app doesn't
        /// know reads as thinking.
        var line: String? {
            switch state {
            case "done": return nil
            case "working": return words.map { "\($0)…" } ?? Words.Chat.working
            case "waiting_for_you": return Words.Chat.waitingForYou
            default: return Words.Chat.thinking
            }
        }
    }

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
    private func followSetupStream(_ events: AsyncThrowingStream<TafsCloud.StreamEvent, Error>, text: String, gen: Int,
                                   conversation: Int, sent: String? = nil) async {
        // The answer follows the new chat tafs cloud started for the message (`followNewSetupChat`).
        var conversation = conversation
        var ended: String?
        // Coach paused on a card its person answers: Needs you is read once then, so its row
        // and badge come while the chat is still open, not after it closes.
        var waitsOnYou = false
        do {
            for try await e in events {
                guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
                switch e {
                case .session(let session, let newSession, let run, let message):
                    if followNewSetupChat(session, newSession: newSession, sent: sent) { conversation = setupConversationGeneration }
                    setupLive?.session = session
                    if let run { setupLive?.run = run }
                    settleSetupChat(session)
                    if let sent, let message { markSetupSent(sent, serverID: message) }
                case .text(let t):
                    setupLive?.text += t
                    setupLive?.thinking = ""
                case .thinking(let t):
                    setupLive?.thinking += t
                case .state(let state, let words):
                    setupLive?.state = LiveState(state: state, words: words)
                    waitsOnYou = Self.stillWaitsOnYou(waitsOnYou, state: state)
                case .activity(let tool, let step, let call):
                    // The step shows at once; the thread then picks up the words written so far.
                    let words = step ?? Self.activityWords(tool)
                    setupLive?.activity = words
                    setupLive?.began(tool, words: words, call: call)
                    await loadSetupThread()
                    guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
                    setupLive?.text = ""
                case .step(let call, let status):
                    setupLive?.ended(call, LiveStep.Status(stream: status))
                case .component, .said:
                    await loadSetupThread()
                    guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
                    setupLive?.text = ""
                case .part:
                    // It went on after a pause: what came before is the thread's, with its work,
                    // and the steps start again (the 870 test, as in a taf's chat).
                    await loadSetupThread()
                    guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
                    setupLive?.newPart()
                    setupLiveBefore = Set(setupThread.items.map(\.id))
                case .end(let status, _, _, _, _):
                    ended = status
                    if status == "PAUSED" { waitsOnYou = true }
                case .held:
                    setupLive?.ended(nil, .held)
                case .broken, .taken:
                    break
                }
            }
        } catch {}
        guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
        // The run is over: Setup is no longer at work, whatever it wrote.
        if ended != nil { setupAwaiting = nil }
        if ended == "ERROR" { setupEnded = .failed(retry: text) }
        if ended == "CANCELLED" { setupEnded = .stopped }
        // The recorded answer first, then the live one goes: no gap between them.
        await loadSetupThread()
        guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
        setupLive = nil
        // Paused on a card: Home, and its Needs you, read once now, as a taf's chat does at its end.
        // Any other end (done, stopped, failed) reads it too: a row of this chat that waited on the
        // person goes, with its badge, rather than wait for the next read.
        if Self.readsHomeAtEnd(ended: ended, waitsOnYou: waitsOnYou) { await refresh() }
    }

    /// Whether a Coach run's stream, at its end, reads Home again: when the run ended, however it
    /// ended, or paused on a card. A stream that dropped with no end leaves it to the next read.
    nonisolated static func readsHomeAtEnd(ended: String?, waitsOnYou: Bool) -> Bool {
        waitsOnYou || ended != nil
    }

    /// Whether Coach's run still waits on its person after this live line: `waiting_for_you`
    /// (a card that waits on them, or a held call) says it does; any other line but the last,
    /// `done`, says it went on. One Home read per pause, at the stream's end.
    nonisolated static func stillWaitsOnYou(_ before: Bool, state: String) -> Bool {
        switch state {
        case "waiting_for_you": return true
        case "done": return before
        default: return false
        }
    }

    /// What Setup is doing, in the owner's words, from the tool it calls.
    nonisolated static func activityWords(_ tool: String) -> String? {
        // By its own name, past any prefix the run records it under ("kiff__kiff_draft").
        switch tool.components(separatedBy: "__").last ?? tool {
        case "planque": return Words.Coach.Activity.connection
        case "kiff_draft": return Words.Coach.Activity.card
        case "kiff_card", "kiff_pending": return Words.Coach.Activity.kiff
        case "tafs_state": return Words.Coach.Activity.tafs
        case "taf_run": return Words.Coach.Activity.run
        case "taf_task": return Words.Coach.Activity.task
        case "run_stop": return Words.Coach.Activity.stop
        case "taf_pause": return Words.Coach.Activity.pause
        case "version_try": return Words.Coach.Activity.tryVersion
        case "version_install": return Words.Coach.Activity.installVersion
        case "memory": return Words.Taf.Activity.readingNotes
        case "show": return nil
        default: return Words.Chat.working
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
            setupError = Words.Work.didNotStop(why: "\(error.localizedDescription)")
        }
        await loadSetupThread()
    }

    /// Where a card stands (kiff/tafs#58): waiting for the owner, at work, or answered
    /// with its outcome.
    enum CardState: Equatable { case needsYou, working, done, declined, failed(String?) }

    /// What an approval made on Coach's card plays as the card reaches `state` (Haptics): approved,
    /// a success tick; failed, a warning. Declined is the person's own choice, and on its way or
    /// back to asking is no outcome yet: nothing.
    nonisolated static func feel(approving state: CardState) -> Haptics.Kind? {
        switch state {
        case .done: return .success
        case .failed: return .warning
        case .needsYou, .working, .declined: return nil
        }
    }

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

    /// Finished only by its answer in the thread: approved after a signature made on a
    /// phone, or already approved, signed earlier. planque's state alone never finishes it:
    /// a grant connected before this card says nothing of what the owner approved here.
    func approvalFinished(_ c: SetupComponent) -> Bool {
        let grant = grants.first { $0.id == c.grant }
        // planque failed the grant after the owner approved (at connect, or later): the
        // card says so with planque's reason, whatever the answer said.
        if grantsReadFor.contains(c.id), let grant, grant.status == "failed" || grant.status == "disconnected" { return false }
        guard let a = setupThread.answer(to: c.id), !a.failed else { return false }
        return a.text.hasPrefix("approved") || a.text.hasPrefix("already approved")
    }

    /// The card waiting for the owner, if any (kiff/tafs#58): the latest approval they
    /// haven't answered, or a question or sign-in they haven't answered or written past.
    var setupWaiting: ShownComponent? {
        setupThread.asks.last { shown in
            let c = shown.component
            guard !answered.contains(shown.ref) else { return false }
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

    /// The owner's answer to a card, as data (`POST …/components/{id}/answer`): tafs cloud has
    /// kiff/agents check it against the card, and the run that waited goes on; the phone follows
    /// it, as it does a message's. `text` is the answer as the card gives it ("live", "approved",
    /// "failed: why", a questions card's JSON). A card that doesn't wait any more (409) is read
    /// again; any other failure stays on the card, in the app's words.
    func answer(_ component: SetupComponent, _ text: String) async {
        setupUnsent.removeAll { SetupThread.Answer($0.text)?.id == component.id }
        guard let s = setupTaf, let value = Self.answerValue(component.type, text) else { return }
        let ref = cardRef(component.id)
        answered.insert(ref)
        cardNotes[component.id] = nil
        let session = setupThread.components.last { $0.ref == ref }?.session
        let gen = accountGeneration
        do {
            let r = try await cloud.answerCard(s.id, component: component.id, answer: value, session: session)
            guard gen == accountGeneration else { return }
            settleSetupChat(r.session)
            await followAnswered(s.id, run: r.run, gen: gen)
        } catch let e as TafsCloud.CloudError where e.status == 409 {
            guard gen == accountGeneration else { return }
            answered.remove(ref)
            await loadSetupThread()
        } catch let e as TafsCloud.CloudError where e.code == TafsCloud.aiConsentRequired {
            // Refused for want of the AI-data consent: its screen says why, and the card stays as
            // it was, its answers as chosen, to send again once it's allowed.
            guard gen == accountGeneration else { return }
            answered.remove(ref)
        } catch {
            guard gen == accountGeneration else { return }
            answered.remove(ref)
            cardNotes[component.id] = Words.Coach.answerDidNotReachSetup
        }
    }

    /// A card's answer as tafs cloud takes it, from the card's own form of it: a questions card's
    /// answers as they were chosen; an approval "approved" (whatever followed it, "approved,
    /// signed in"), "declined" or "failed: why"; a sign-in "signed in", "not now" or "failed:
    /// why"; a confirm the option's value. None for an answer the card doesn't take.
    nonisolated static func answerValue(_ type: SetupComponent.Kind, _ text: String) -> TafsCloud.CardAnswerValue? {
        let failed = text.hasPrefix("failed:") ? text : nil
        switch type {
        case .confirm:
            return text.isEmpty ? nil : .text(text)
        case .questions:
            guard let q = try? JSONDecoder().decode([String: TafsCloud.CardAnswerValue.QuestionAnswer].self, from: Data(text.utf8)) else { return nil }
            return .questions(q)
        case .approval:
            if text == "approved" || text.hasPrefix("approved,") { return .text("approved") }
            if text == "declined" { return .text("declined") }
            // Signed earlier, not by this tap (kiff/agents takes it as Already approved).
            if text.hasPrefix("already approved:") { return .text(text) }
            return failed.map { .text($0) }
        case .signIn:
            if text == "signed in" || text == "not now" { return .text(text) }
            return failed.map { .text($0) }
        case .drive:
            // Only declining: a drive is approved by its signed start (docs/drive.md).
            return text == "declined" ? .text("declined") : nil
        default:
            return nil
        }
    }

    /// Follows the run a card's answer let go on, as a message's stream is followed: its words
    /// as it writes them, then the thread. A stream that can't open leaves it to the thread.
    private func followAnswered(_ setupID: String, run: String, gen: Int) async {
        let conversation = setupConversationGeneration
        setupAwaiting = clock()
        guard let events = try? await cloud.resumeRun(setupID, run: run) else {
            await loadSetupThread()
            return
        }
        guard gen == accountGeneration, conversation == setupConversationGeneration else { return }
        setupLiveBefore = Set(setupThread.items.map(\.id))
        setupLive = LiveAnswer(started: clock())
        setupStream = Task { await self.followSetupStream(events, text: "", gen: gen, conversation: conversation) }
    }

    /// Approve on a Coach card (kiff/tafs#413): tafs cloud decides. The phone sends that Approve was
    /// tapped, with what the card showed; tafs cloud reads planque's grant (as this phone) and
    /// KIFF's draft and says what needs signing, if anything. With nothing to sign it answers Coach
    /// itself (already approved, or failed with why), and the card shows its note. Otherwise the
    /// phone signs exactly the message it's given, under Face ID, makes the one call it names as
    /// given (planque's approve and the vendor's sign-in, or KIFF's draft confirm), and reports
    /// what came of it, signed as a setup call. tafs cloud checks the report with KIFF and the
    /// service before Coach hears "approved". The phone never builds an approval's answer.
    func approve(_ component: SetupComponent, shown: String?, shownDraft: String?) async {
        guard let grantID = component.grant, let s = setupTaf else { return }
        lastError = nil
        shownTermsChanged = false
        cardNotes[component.id] = nil
        guard planqueReady, sessionHasKey, let tenant = kiffTenant else {
            cardNotes[component.id] = Words.Connect.startConnectedToolsFirst(device: "\(ThisDevice.kind)")
            return
        }
        guard connecting == nil else {
            cardNotes[component.id] = Words.Connect.anotherApproval
            return
        }
        connecting = grantID
        defer { connecting = nil; connectStep = nil }
        let gen = accountGeneration
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        kiff.tenant = tenant
        defer { planque.signer = nil; kiff.setupSigner = nil }
        let ref = cardRef(component.id)
        let session = setupThread.components.last { $0.ref == ref }?.session
        do {
            // The read proof: planque shows the grant to tafs cloud only as this phone; it signs and grants nothing there.
            let proof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) })
            let words = grants.first { $0.id == grantID }.flatMap { shownWords(for: $0) }
            // With the taf's Card drafted and drawn on the card (its lines, at the version shown), the phone can sign
            // the tools added to a connected app and that Card together; tafs cloud asks for it only when that's on,
            // and otherwise answers as before. A Card the card didn't draw is never signed with the tools.
            let drawn = component.draft.flatMap { setupDrafts[$0] }
            let can: [String]? = Self.cardDrawn(drawn, shownDraft: shownDraft) ? [RequestsTogether.toolsAndCards, RequestsTogether.signs] : nil
            let decision = try await cloud.approval(s.id, component: component.id, body: .init(
                grant: grantID, shownDigest: shown, draft: component.draft, shownDraft: shownDraft,
                words: words.map { .init(profileSHA256: $0.profileSHA256, wordsSHA256: $0.wordsSHA256) }, session: session,
                can: can), proof: proof)
            guard gen == accountGeneration else { return }
            switch decision.outcome {
            case "answered":
                cardNotes[component.id] = decision.note
                await setupAnswered(s.id, ref: ref, decision: decision, gen: gen)
            case "read_again":
                cardNotes[component.id] = decision.note ?? Words.Version.outOfDateCard
                await refreshConnections()
                if let id = component.draft, let draft = try? await kiff.draft(id: id), gen == accountGeneration { setupDrafts[id] = draft }
            case "sign":
                guard let ask = decision.ask else { return }
                // What the card said this fingerprint signs (tafs cloud's mark, read with the thread) must be what
                // it signs now: the Card drawn as signed and only the connection asked, or the Card drawn as next
                // and asked now, is out of date. Nothing is signed, and the card is read again.
                guard Self.stepMatchesMark(component.signs, step: ask.step) else {
                    cardNotes[component.id] = Words.Version.outOfDateCard
                    await refreshConnections()
                    await loadSetupThread()
                    return
                }
                let came: Came
                if ask.step == RequestsTogether.toolsAndCards {
                    // The tools added to a connected app and the Card over them, under one Face ID.
                    guard let together = try await approveToolsAndCard(ask, component: component, tenant: tenant,
                                                                       shown: shown, shownDraft: shownDraft, words: words, gen: gen) else { return }
                    came = together
                } else {
                    // The phone is the last check, not a signing service: it signs only the message this card
                    // implies, rebuilt from what it holds (its own account, the card's grant or draft, the ask's
                    // digest or version), and refuses any other.
                    guard ask.message == Self.expectedApprovalMessage(ask, component: component, tenant: tenant, shownDigest: shown, shownDraft: shownDraft) else {
                        cardNotes[component.id] = Self.askNotThisCards
                        return
                    }
                    do {
                        _ = try await signer.sign(Data(ask.message.utf8), reason: ask.reason, grants: true)
                    } catch {
                        cardNotes[component.id] = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
                        Haptics.warning()
                        return
                    }
                    guard gen == accountGeneration else { return }
                    kiff.setupSigner = { try signer.signConfirmed($0) }
                    came = await makeAskedCall(ask, tenant: tenant, gen: gen)
                }
                guard gen == accountGeneration else { return }
                await refreshConnections()
                guard gen == accountGeneration else { return }
                // A fresh read proof for the report: the first was spent by the approval's read, and planque takes each
                // signed request once, within minutes (a vendor sign-in can take longer).
                let reportProof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) })
                let outcome = try await cloud.approvalReport(s.id, component: component.id, ask: ask.id,
                                                             body: .init(result: came.result, status: came.status, why: came.why, session: session),
                                                             proof: reportProof, tenant: tenant, signedBy: { try signer.signConfirmed($0) })
                guard gen == accountGeneration else { return }
                cardNotes[component.id] = outcome.note
                await setupAnswered(s.id, ref: ref, decision: outcome, gen: gen)
            default:
                cardNotes[component.id] = Words.Coach.unknownAnswer
            }
        } catch let refused as TafsCloud.CloudError where refused.code == Self.signInNotConfirmed {
            // tafs cloud's own line: the sign-in went through and isn't confirmed yet, so Approve
            // again confirms it. Said as it is, never as an answer that didn't arrive.
            guard gen == accountGeneration else { return }
            cardNotes[component.id] = refused.words ?? refused.message
            await loadSetupThread()
            // Once the thread is read again: a card it shows as failed is the same failure, one buzz.
            Haptics.warning()
        } catch {
            guard gen == accountGeneration else { return }
            cardNotes[component.id] = Words.Coach.answerNotReached(why: "\(error.localizedDescription)")
            await loadSetupThread()
            Haptics.warning()
        }
    }

    /// tafs cloud's code when a sign-in planque holds isn't confirmed yet: the card asks again.
    nonisolated static let signInNotConfirmed = "sign_in_not_confirmed"

    /// How giving a key and its Card under one Face ID went (`approveKeyAndCard`).
    enum KeyAndCard: Equatable {
        /// The key went in and the Card is confirmed, under one signature.
        case done
        /// tafs cloud didn't ask for the one signature (it isn't on there, or this card has no
        /// Card drafted): nothing was signed, and the key is given by itself, as before.
        case notOffered
        /// It was asked and didn't finish. The card's note says why.
        case failed
    }

    /// A key and its taf's Card under one Face ID (docs/secure-connection.md), from the key's
    /// sheet opened on Coach's card. The phone tells tafs cloud it can sign the step; when tafs
    /// cloud asks for it, the phone checks the ask names this card's grant, the digest it showed,
    /// this card's draft and the version it showed, builds both requests itself with one
    /// `signed_at`, signs their two hashes once with the approval key, and sends the key to the
    /// connection server, then the confirm to KIFF. It reports what came of them, and tafs cloud
    /// checks both before Coach hears "approved". The key never goes to tafs cloud.
    func approveKeyAndCard(_ component: SetupComponent, shown: String?, shownDraft: String?, key: String) async -> KeyAndCard {
        guard let grantID = component.grant, let draftID = component.draft, let shown, let shownDraft, let s = setupTaf,
              planqueReady, sessionHasKey, let tenant = kiffTenant, connecting == nil else { return .notOffered }
        cardNotes[component.id] = nil
        lastError = nil
        connecting = grantID
        defer { connecting = nil; connectStep = nil }
        let gen = accountGeneration
        let signer = self.signer
        planque.tenant = tenant
        planque.signer = { try signer.signConfirmed($0) }
        kiff.tenant = tenant
        defer { planque.signer = nil; kiff.setupSigner = nil }
        let ref = cardRef(component.id)
        let session = setupThread.components.last { $0.ref == ref }?.session
        do {
            let proof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) })
            let grant = grants.first { $0.id == grantID }
            let words = grant.flatMap { shownWords(for: $0) }
            let decision = try await cloud.approval(s.id, component: component.id, body: .init(
                grant: grantID, shownDigest: shown, draft: draftID, shownDraft: shownDraft,
                words: words.map { .init(profileSHA256: $0.profileSHA256, wordsSHA256: $0.wordsSHA256) }, session: session,
                can: [RequestsTogether.keyAndCards]), proof: proof)
            guard gen == accountGeneration else { return .failed }
            switch decision.outcome {
            case "answered":
                cardNotes[component.id] = decision.note
                await setupAnswered(s.id, ref: ref, decision: decision, gen: gen)
                return .failed
            case "read_again":
                cardNotes[component.id] = decision.note ?? Words.Version.outOfDateCard
                await refreshConnections()
                if let draft = try? await kiff.draft(id: draftID), gen == accountGeneration { setupDrafts[draftID] = draft }
                return .failed
            case "sign":
                break
            default:
                cardNotes[component.id] = Words.Coach.unknownAnswer
                return .failed
            }
            // Another step (the two signatures, as before): nothing is signed here, and the key goes by itself.
            guard let ask = decision.ask, ask.step == RequestsTogether.keyAndCards else { return .notOffered }
            // The phone is the last check: the ask must be this card's, for what the person was shown.
            guard let grant, grant.digestMatches, grant.digest == shown,
                  Self.keyAndCardIsThisCards(ask, grant: grantID, shownDigest: shown, draft: draftID, shownDraft: shownDraft),
                  grant.profile == nil || words != nil else {
                // An ask for another grant or draft isn't this card's. One for this card at a digest or a
                // version the phone didn't read is out of date: the card is read again, and nothing is signed.
                let sameCard = ask.planque?.grant == grantID && ask.kiff?.draft == draftID
                cardNotes[component.id] = sameCard ? Words.Version.outOfDateCard : Self.askNotThisCards
                if sameCard {
                    await refreshConnections()
                    if let draft = try? await kiff.draft(id: draftID), gen == accountGeneration { setupDrafts[draftID] = draft }
                }
                return .failed
            }
            let signedAt = SigningTime.next()
            let keyRequest = try planque.prepareKey(grant, key: key, words: words, signedAt: signedAt)
            let confirm = try kiff.prepareConfirmDraft(id: draftID, version: shownDraft, signedAt: signedAt)
            let keyHash = RequestsTogether.hash(keyRequest.message), confirmHash = RequestsTogether.hash(confirm.message)
            let signature: String
            do {
                signature = try await signer.signApproval(RequestsTogether.message(tenant: tenant, signedAt: signedAt, keyHash, confirmHash), reason: ask.reason)
            } catch {
                cardNotes[component.id] = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
                return .failed
            }
            guard gen == accountGeneration else { return .failed }
            // The key first, then the Card: a Card is never confirmed for a key that didn't go in.
            let came: Came
            do {
                let given = try await planque.giveKey(keyRequest, signature: signature, with: confirmHash)
                if given.connected {
                    do {
                        _ = try await kiff.confirmDraft(confirm, signatureHeader: RequestsTogether.header(signedAt: signedAt, signature: signature, with: keyHash))
                        await keepKIFFAlerts(tenant: tenant, gen: gen)
                        came = Came(result: "confirmed")
                    } catch {
                        came = Came(result: "failed", why: error.localizedDescription)
                    }
                } else {
                    came = Came(result: "not_connected", status: given.status)
                }
            } catch {
                came = Came(result: "failed", why: Words.Connect.keyNotGiven(why: "\(error.localizedDescription)"))
            }
            guard gen == accountGeneration else { return .failed }
            await refreshConnections()
            guard gen == accountGeneration else { return .failed }
            let reportProof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) })
            let outcome = try await cloud.approvalReport(s.id, component: component.id, ask: ask.id,
                                                         body: .init(result: came.result, status: came.status, why: came.why, session: session),
                                                         proof: reportProof, tenant: tenant, signedBy: { try signer.signConfirmed($0) })
            guard gen == accountGeneration else { return .failed }
            cardNotes[component.id] = outcome.note
            await setupAnswered(s.id, ref: ref, decision: outcome, gen: gen)
            return came.result == "confirmed" ? .done : .failed
        } catch {
            guard gen == accountGeneration else { return .failed }
            cardNotes[component.id] = Words.Coach.answerNotReached(why: "\(error.localizedDescription)")
            await loadSetupThread()
            return .failed
        }
    }

    /// Whether a `key_and_cards` ask is this card's, for what the person was shown: its grant
    /// and the digest shown, its draft and the version shown. Anything else is refused, and
    /// nothing is signed.
    nonisolated static func keyAndCardIsThisCards(_ ask: TafsCloud.ApprovalDecision.Ask, grant: String, shownDigest: String, draft: String, shownDraft: String) -> Bool {
        guard ask.step == RequestsTogether.keyAndCards, let p = ask.planque, let k = ask.kiff else { return false }
        return p.grant == grant && p.body.digest == shownDigest && k.draft == draft && k.version == shownDraft
    }

    /// Tools added to a connected app and the taf's Card over them, under one Face ID
    /// (`tools_and_cards`, docs/secure-connection.md). The phone checks the ask is this card's, for
    /// what the person was shown, builds planque's approve and KIFF's confirm itself with one
    /// `signed_at`, and signs their two hashes once with the approval key. It sends the approve
    /// first, and the confirm only once planque says the app is connected with the tools: a Card
    /// is never confirmed over tools that aren't in. Nil when nothing was sent: the card's note says why.
    private func approveToolsAndCard(_ ask: TafsCloud.ApprovalDecision.Ask, component: SetupComponent, tenant: String,
                                     shown: String?, shownDraft: String?, words: ProfileWords?, gen: Int) async throws -> Came? {
        let grant = component.grant.flatMap { id in grants.first { $0.id == id } }
        guard let grant, let shown, let shownDraft, let draftID = component.draft,
              let body = try? PlanqueClient.approveBody(grant, words: words),
              Self.toolsAndCardIsThisCards(ask, grant: grant.id, body: body, shownDigest: shown, draft: draftID, shownDraft: shownDraft) else {
            // An ask for another grant or draft isn't this card's. One for this card at a digest or a
            // version the phone didn't read is out of date: the card is read again, and nothing is signed.
            let sameCard = ask.planque?.grant == component.grant && ask.kiff?.draft == component.draft
            cardNotes[component.id] = sameCard ? Words.Version.outOfDateCard : Self.askNotThisCards
            if sameCard {
                await refreshConnections()
                if let id = component.draft, let draft = try? await kiff.draft(id: id), gen == accountGeneration { setupDrafts[id] = draft }
            }
            return nil
        }
        let signedAt = SigningTime.next()
        let approveRequest = try planque.prepareApprove(grantID: grant.id, body: body, signedAt: signedAt)
        let confirm = try kiff.prepareConfirmDraft(id: draftID, version: shownDraft, signedAt: signedAt)
        let approveHash = RequestsTogether.hash(approveRequest.message), confirmHash = RequestsTogether.hash(confirm.message)
        let signature: String
        do {
            signature = try await signer.signApproval(RequestsTogether.message(tenant: tenant, signedAt: signedAt, approveHash, confirmHash), reason: ask.reason)
        } catch {
            cardNotes[component.id] = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
            return nil
        }
        guard gen == accountGeneration else { return nil }
        // The tools first, then the Card.
        let tools: Came
        do {
            let approved = try await planque.approve(approveRequest, signature: signature, with: confirmHash)
            tools = try await connectedAfter(approved, grantID: grant.id, tenant: tenant, gen: gen)
        } catch VendorSignIn.SignInError.cancelled {
            return Came(result: "sign_in_closed")
        } catch {
            return Came(result: "failed", why: error.localizedDescription)
        }
        guard tools.result == "connected", gen == accountGeneration else { return tools }
        do {
            _ = try await kiff.confirmDraft(confirm, signatureHeader: RequestsTogether.header(signedAt: signedAt, signature: signature, with: approveHash))
            await keepKIFFAlerts(tenant: tenant, gen: gen)
            return Came(result: "confirmed")
        } catch {
            return Came(result: "failed", why: error.localizedDescription)
        }
    }

    /// Whether Coach's card drew the Card it would sign: the draft was read, the card shows its
    /// lines (limits and all, the same `lines` the card draws, so none means nothing drawn), and
    /// it's at the version shown.
    nonisolated static func cardDrawn(_ draft: KIFFClient.AgentDraft?, shownDraft: String?) -> Bool {
        guard let draft, let shownDraft, !draft.lines.isEmpty, draft.noTools != true else { return false }
        return draft.version == shownDraft
    }

    /// Whether the step tafs cloud asks signs the Card as the card's mark said: `card: true` for a step that
    /// signs it (`cards`, `key_and_cards`, `tools_and_cards`), `card: false` for one that doesn't. No mark: any.
    nonisolated static func stepMatchesMark(_ signs: SetupComponent.Signs?, step: String) -> Bool {
        guard let card = signs?.card else { return true }
        return card == ["cards", RequestsTogether.keyAndCards, RequestsTogether.toolsAndCards].contains(step)
    }

    /// What this phone says it can do beyond connect and cards: sign a key and its Card from the key's sheet,
    /// and an app's tools and its Card from Coach's card; and draw tafs cloud's mark of what a fingerprint
    /// signs (`signs`), so a held sign-in's first tools may go with their Card in one fingerprint.
    nonisolated static let stepsSignedHere = [RequestsTogether.toolsAndCards, RequestsTogether.keyAndCards, RequestsTogether.signs]

    /// Whether a `tools_and_cards` ask is this card's, for what the person was shown: its grant,
    /// planque's approve exactly as this phone builds it for the digest shown (and KIFF's words for
    /// its profile), its draft and the version shown. Anything else is refused, and nothing is signed.
    nonisolated static func toolsAndCardIsThisCards(_ ask: TafsCloud.ApprovalDecision.Ask, grant: String, body: PlanqueClient.ApproveBody,
                                                    shownDigest: String, draft: String, shownDraft: String) -> Bool {
        guard ask.step == RequestsTogether.toolsAndCards, let p = ask.planque, let k = ask.kiff else { return false }
        return p.grant == grant && p.body == body && body.digest == shownDigest && k.draft == draft && k.version == shownDraft
    }

    /// Approve on Coach's drive card (docs/drive.md): the start is signed with the approval key, behind
    /// Face ID or Touch ID, as a setup call (`kiff.request.v1` over this request). tafs cloud starts the
    /// drive only for that key, and tells Coach the drive is on. Then Coach's chat closes into its
    /// button, and the drive follows its stream until it ends.
    func approveDrive(_ component: SetupComponent) async {
        cardNotes[component.id] = nil
        guard mode == .kiff, let tenant = kiffTenant else {
            cardNotes[component.id] = Words.Error.signInFirstOnDevice(device: "\(ThisDevice.kind)")
            return
        }
        let ref = cardRef(component.id)
        guard let session = setupThread.components.last(where: { $0.ref == ref })?.session ?? setupChat else { return }
        let task = component.text ?? component.title ?? Words.Drive.driveApp
        let body = TafsCloud.driveStartBody(session: session, task: task, component: component.id)
        let signedAt = SigningTime.next()
        let message = KIFFClient.requestMessage(tenant: tenant, method: "POST", uri: TafsCloud.drivePath, body: body, signedAt: signedAt)
        let gen = accountGeneration
        let signature: String
        do {
            signature = try await signer.signApproval(message, reason: Words.Drive.approval(task: "\(task)"))
        } catch {
            guard gen == accountGeneration else { return }
            cardNotes[component.id] = ApprovalReadiness.notApproved(error, kind: Biometry.kind)
            return
        }
        guard gen == accountGeneration else { return }
        do {
            let drive = try await cloud.startDrive(body: body, signature: "t=\(signedAt),v1=\(signature)")
            guard gen == accountGeneration else { return }
            answered.insert(ref)
            let driving = DriveSession.shared
            driving.onStart = { [weak self] in self?.coachOpen = false }
            driving.onEnd = { [weak self] in
                self?.coachOpen = true
                Task { [weak self] in await self?.loadSetupThread() }
            }
            driving.tafOnScreen = { [weak self] in
                guard let self, let id = self.tafOnScreen else { return nil }
                return self.taf(id)?.name
            }
            driving.begin(drive, cloud: cloud)
        } catch {
            guard gen == accountGeneration else { return }
            // tafs cloud's words: the approval key is needed, or the card isn't there any more.
            cardNotes[component.id] = (error as? TafsCloud.CloudError)?.errorDescription ?? Words.Coach.Drive.cant(why: "\(error.localizedDescription)")
        }
    }

    /// After a launch: a drive this phone left on when it closed is over (it never outlives the app).
    func endLeftoverDrive() async {
        guard mode == .kiff, signedIn else { return }
        await DriveSession.shared.endLeftover(cloud)
    }

    /// tafs cloud asked this phone to sign a message that isn't this card's: refused, nothing signed.
    static let askNotThisCards = Words.Coach.askNotThisCards

    /// The message this card's approval may sign, rebuilt from what the phone holds: its own account, the
    /// card's grant and the digest the person was shown for the connect step; the card's draft and the
    /// version the person was shown for the Cards step. Nil for an ask that names another grant, draft,
    /// digest or version, or a step this app doesn't know, and when nothing was shown: the phone signs
    /// nothing it can't rebuild itself from what the person read.
    nonisolated static func expectedApprovalMessage(_ ask: TafsCloud.ApprovalDecision.Ask, component: SetupComponent, tenant: String,
                                                    shownDigest: String?, shownDraft: String?) -> String? {
        switch ask.step {
        case "connect":
            // The digest is what the person saw, never the ask's: terms they never read are never signed.
            guard let p = ask.planque, p.grant == component.grant, let grant = component.grant,
                  let shownDigest, p.body.digest == shownDigest else { return nil }
            return "tafs.connect.v1|\(tenant)|\(grant)|\(shownDigest)"
        case "cards":
            guard let k = ask.kiff, let draft = component.draft, k.draft == draft, let shownDraft, k.version == shownDraft else { return nil }
            return "tafs.cards.v1|\(tenant)|\(draft)|\(shownDraft)"
        default:
            return nil
        }
    }

    /// What came of the call tafs cloud asked for, as the report says it: never an answer, only what happened.
    private struct Came {
        let result: String
        var status: String? = nil
        var why: String? = nil
    }

    /// Makes the one call tafs cloud asked for, as given, and says what came of it.
    private func makeAskedCall(_ ask: TafsCloud.ApprovalDecision.Ask, tenant: String, gen: Int) async -> Came {
        do {
            if ask.step == "cards" {
                guard let k = ask.kiff else { return Came(result: "failed", why: Words.Coach.noDraftNamed) }
                _ = try await kiff.confirmDraft(id: k.draft, version: k.version)
                await keepKIFFAlerts(tenant: tenant, gen: gen)
                return Came(result: "confirmed")
            }
            guard let p = ask.planque else { return Came(result: "failed", why: Words.Connect.tafsCloudNamedNoConnection) }
            let approved = try await planque.approve(grantID: p.grant, body: p.body)
            return try await connectedAfter(approved, grantID: p.grant, tenant: tenant, gen: gen)
        } catch VendorSignIn.SignInError.cancelled {
            return Came(result: "sign_in_closed")
        } catch {
            return Came(result: "failed", why: error.localizedDescription)
        }
    }

    /// planque's answer to an approve, followed to where it ends: a sign-in it holds confirmed with
    /// its one-use code, or the vendor's sign-in made and confirmed. Connected only once planque says so.
    private func connectedAfter(_ approved: PlanqueClient.Approved, grantID: String, tenant: String, gen: Int) async throws -> Came {
        guard let signInURL = approved.signInURL else {
            // A fresh sign-in planque holds for this phone to confirm, with its one-use code:
            // confirmed here, then reported as planque answers it (unconfirmed, it's revoked).
            if approved.status == "signed_in", let code = approved.confirmCode {
                let confirmed = try await planque.confirm(grantID: grantID, code: code)
                return confirmed.connected || confirmed.listed ? Came(result: confirmed.status == "listed" ? "listed" : "connected")
                    : Came(result: "not_connected", status: confirmed.status)
            }
            // planque already holds the sign-in: approving connected it, with no second sign-in.
            return approved.status == "connected" || approved.status == "listed" ? Came(result: approved.status) : Came(result: "not_connected", status: approved.status)
        }
        let back = try await vendorSignIn.signIn(at: signInURL)
        guard let signedIn = PlanqueClient.signedIn(from: back), signedIn.grant == grantID, signedIn.tenant == tenant else { return Came(result: "no_code") }
        guard gen == accountGeneration else { return Came(result: "failed", why: "the account changed") }
        // Back from the vendor: connected only once planque says so, never because the page closed.
        connectStep = Words.Coach.checkingSignIn
        let connected = try await planque.confirm(grantID: grantID, code: signedIn.code)
        return connected.connected || connected.listed ? Came(result: "connected") : Came(result: "failed", why: Words.Connect.signCameBackConnection(status: "\(connected.status)"))
    }

    /// Coach heard the card's answer from tafs cloud: the card shows it's answered until the thread's
    /// words for it come, and the run that paused on it is followed to its end, which reads the thread again.
    private func setupAnswered(_ setupID: String, ref: String, decision: TafsCloud.ApprovalDecision, gen: Int) async {
        answered.insert(ref)
        if let session = decision.session { settleSetupChat(session) }
        if let run = decision.run { await followAnswered(setupID, run: run, gen: gen) } else { await loadSetupThread() }
    }

    /// Set by `connect` when the grant was already connected and nothing was left to
    /// sign: the vendor, and whether its Cards were already confirmed.
    private var nothingSigned: (vendor: String, cardsConfirmed: Bool)?

    /// An approval with nothing to sign and no Card, in the owner's words: nothing was approved.
    static func nothingToApprove(_ vendor: String) -> String {
        Words.Coach.nothingToApprove(vendor: "\(vendor)")
    }

    /// An approval signed earlier: connected, its Card confirmed in KIFF.
    static let alreadyApproved = Words.Coach.alreadyApproved

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
        guard case PlanqueClient.PlanqueError.server(let status, _, _) = error else { return false }
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
        // A connection that takes no approval now (signed in already, or past approving): tafs cloud
        // answers the card in the person's words, never planque's status ("This connection is listed.").
        // Where it can't be asked, the app's line stands (Android 1.27.8).
        if done == nil, notApprovable {
            let answeredByCloud = await notApprovableAnswered(component, grantID: grant)
            if answeredByCloud {
                lastError = nil
                return
            }
        }
        if let done, done.connected || done.listed {
            await answer(component, Words.Coach.Answer.signedInLower)
        } else if let e = lastError {
            await answer(component, "failed: \(e)")
        } else if done == nil {
            cardNotes[component.id] = quietStop ?? Words.Connect.nothingSignedIn
        }
    }

    /// Set by `connect` when the grant's status takes no approval (anything but pending, approved,
    /// signed in or signed out by its vendor): a sign-in card then asks tafs cloud for its words.
    private var notApprovable = false

    /// Asks tafs cloud about a sign-in card whose connection takes no approval now: its approval
    /// route takes a sign-in card too (docs/api.md). True once it answered Coach, with its note on
    /// the card; anything else (something to sign, no answer) leaves the card to the app.
    private func notApprovableAnswered(_ component: SetupComponent, grantID: String) async -> Bool {
        guard let s = setupTaf, let tenant = kiffTenant else { return false }
        let gen = accountGeneration
        let signer = self.signer
        let ref = cardRef(component.id)
        let session = setupThread.components.last { $0.ref == ref }?.session
        planque.tenant = tenant
        // The read proof: planque shows the grant to tafs cloud only as this phone; it signs and grants nothing there.
        let proof = try? await planque.grantsReadProof(signedBy: { try signer.signConfirmed($0) })
        guard gen == accountGeneration else { return false }
        let decision = try? await cloud.approval(s.id, component: component.id, body: .init(grant: grantID, session: session), proof: proof)
        guard gen == accountGeneration, let decision, decision.outcome == "answered" else { return false }
        cardNotes[component.id] = decision.note.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        await setupAnswered(s.id, ref: ref, decision: decision, gen: gen)
        return true
    }

    /// The taf a result hands over to, when this phone shows it.
    func taf(forAgent agent: String) -> UUID? { holderIDs[agent] }

    /// Whether the last connect opened the vendor's sign-in: the approval's answer says so.
    private var vendorSignedIn = false

    /// Why the last connect stopped before anything happened that Setup should hear of: the
    /// owner reads it on the card, which offers Approve again (kiff/tafs#236, item 1).
    private var quietStop: String?

    /// A line on a card whose tap ended without reaching Setup: why, in the owner's words
    /// (kiff/tafs#236). A tap always shows something: the vendor's sign-in, Setup's answer, or this.
    @Published private(set) var cardNotes: [String: String] = [:]

    /// Whether the last approval found terms other than those shown (kiff/tafs#115).
    @Published private(set) var shownTermsChanged = false

    /// The terms changed between the screen and the tap: the new ones are on screen now,
    /// nothing was signed, and the owner approves again.
    private func termsChanged() {
        shownTermsChanged = true
        lastError = Words.Version.outOfDateCard
    }

    /// The tafs tafs cloud said it set up that the list was read again for: one not listed yet
    /// is read for once.
    private var setUpTafs: Set<String> = []

    /// The versions tafs cloud said it made from an improvement, by taf and number, that the
    /// taf's Versions were read again for.
    private var versionsRead: Set<String> = []

    /// Coach's results, each tafs cloud's to hand over: it sets up a new taf, or makes an
    /// improved taf's next version, and answers the card itself; the phone only shows the
    /// answer. It reads the list again once for a taf it set up, and a taf's Versions once for
    /// a version it made, so it's there to try.
    private func followHandOvers() {
        var readList = false
        for shown in setupThread.components where shown.component.type == .result {
            let c = shown.component
            if c.taf?.improves != nil {
                guard let n = shown.answer?.outcome?.version, let tafID = improvedTaf(c) else { continue }
                if versionsRead.insert("\(tafID)#\(n)").inserted { Task { await loadVersions(tafID) } }
                continue
            }
            guard let id = shown.answer?.outcome?.tafID, let agent = c.taf?.agent else { continue }
            let listed = cloudTafs[agent] != nil || cloudTafs.values.contains { $0.id == id }
            if !listed, setUpTafs.insert(id).inserted { readList = true }
        }
        if readList { Task { await refresh() } }
    }

    /// The taf an improvement result improves, as this phone lists it: by its tafs id, else
    /// by its agent's, when Coach changed a taf it set up or found in this conversation
    /// (kiff/tafs#247).
    func improvedTaf(_ component: SetupComponent) -> UUID? {
        guard let taf = component.taf else { return nil }
        if let agent = cloudTafs.first(where: { $0.value.id == taf.improves })?.key, let id = self.taf(forAgent: agent) { return id }
        return taf.improves.flatMap { self.taf(forAgent: $0) } ?? self.taf(forAgent: taf.agent)
    }
}

/// The app's tabs, as the bar has them: the account, the tafs, the calls waiting on the owner,
/// and the record.
enum Tab: Hashable { case account, tafs, needsYou, record }

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
    /// `-screen calendar` opens Calendar on today in Day mode, `-screen calendar-week` in Week mode on
    /// today's week: today is in what's shown, so the Today pill is in its "already today" state. An
    /// iPad's whole-app sidebar selects its Calendar item; the phone opens it as Home's button does.
    static var calendar: CalendarState? {
        guard enabled else { return nil }
        let today = CalendarMath.today(in: AccountTime.zone)
        switch screen {
        case "calendar": return CalendarState(mode: .day, selected: today)
        case "calendar-week": return CalendarState(mode: .week, selected: today)
        default: return nil
        }
    }
    /// `-pack rayo`: the add-tasks screen with that pack alone, one screenshot a product.
    static var pack: String? { UserDefaults.standard.string(forKey: "pack") }
    /// `-narrow YES`: tafs laid out at Slide Over's width on an iPad, compact (kiff/tafs#210).
    static var narrow: Bool { UserDefaults.standard.bool(forKey: "narrow") }
    /// `-landscape YES`: the iPad's window turned to landscape, for its screenshots.
    static var landscape: Bool { UserDefaults.standard.bool(forKey: "landscape") }
    /// `-signedIn YES`: the iPad's sidebar laid out as for a signed-in account (its screens and
    /// Coach above the account), over the sample's tafs. A screenshot of the layout only: nothing
    /// is read from KIFF, and the screens it lists aren't opened.
    static var signedIn: Bool { enabled && UserDefaults.standard.bool(forKey: "signedIn") }

    @MainActor static func turnToLandscape() {
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
        }
    }
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
    /// purchase screens' screenshots. The App Store writes a price the way the phone's
    /// language does ("4,99 €"), so the sample does too.
    static let packs: [(id: String, name: String, price: String)] = [
        ("pico", "Pico", euros(4.99)), ("vonk", "Vonk", euros(9.99)), ("rayo", "Rayo", euros(19.99)),
        ("forte", "Forte", euros(49.99)), ("maxi", "Maxi", euros(99.99)),
    ]
    static func euros(_ amount: Decimal) -> String {
        amount.formatted(.currency(code: "EUR").locale(Words.locale))
    }
    /// `-screen card`: the Refunds taf's Cards as KIFF would list them (kiff/tafs#211), a live
    /// one with its total, a read-only one with its calls, and a test one.
    static func cards(_ taf: Taf) -> [TafCard] {
        let refunds = TafCard(id: "sample-refunds", title: Words.Sample.Card.refundsStripe, rules: Array(taf.card.rules.prefix(2)),
                              total: CardTotal(limit: .eur(2_000), used: .eur(480), window: .day))
        let reads = TafCard(id: "sample-reads", title: Words.Sample.Card.readStripe,
                            rules: [CardRule(action: "list_charges", label: Words.Sample.listCharges, countPerWindow: 200),
                                    CardRule(action: "get_charge", label: Words.Sample.lookUpCharge)],
                            calls: CardTotal(limit: 200, used: 20, window: .day))
        var test = refunds
        test.id = "sample-test"
        test.title = Words.Sample.refundsStripeTest
        test.environment = "test"
        test.live = false
        return [refunds, reads, test]
    }

    /// Whether this run's screenshot is a purchase screen.
    static var purchases: Bool { enabled && (screen == "solo" || screen == "buy-credits") }
    static let soloProduct = "bot.tafs.solo.monthly"
    static var store: KIFFClient.Usage.Store {
        let shown = packs.filter { pack == nil || $0.id == pack }
        return .init(appAccountToken: UUID(), soloProduct: soloProduct, packs: shown.map {
            .init(id: $0.id, name: $0.name, line: Words.Usage.packNamed(name: $0.name), appleProduct: "bot.tafs.pack.\($0.id)")
        })
    }
    static var products: [StoreProduct] {
        packs.map { StoreProduct(id: "bot.tafs.pack.\($0.id)", displayName: $0.name, displayPrice: $0.price) } +
            [StoreProduct(id: soloProduct, displayName: Words.Usage.solo, displayPrice: euros(10.99), subscription: .init(period: Words.Usage.month))]
    }
    static var tab: Tab {
        switch UserDefaults.standard.string(forKey: "tab") {
        case "needs-you": return .needsYou
        case "record": return .record
        case "account": return .account
        default: return .tafs
        }
    }
}

extension AppModel {
    /// Reads this device's readiness again: Check again, and the app coming back.
    func checkApprovalReadiness() {
        let now = readApprovalReadiness()
        if now != approvalReadiness { approvalReadiness = now }
    }

    /// This device can't approve now, so an approval's button is disabled. Sample mode signs
    /// nothing for real, so nothing is blocked there.
    var approvalBlocked: Bool { mode == .kiff && !approvalReadiness.isReady }

    /// Whether to say this device's approval key is gone. One state at a time: while the
    /// device itself can't approve (Face ID off for tafs, none enrolled), its words come
    /// first, since approving again needs it working; the lost key shows once it is.
    var showsApprovalKeyLost: Bool { approvalKeyLost && !approvalBlocked }
}

/// A search of the Record: what was asked, the pages read so far, and whether one is on its way
/// or failed.
struct RecordSearch {
    let q: String
    var pages: [TafsCloud.RecordSearchPage] = []
    var loading = false
    var error: String?
    var nextCursor: String? { pages.last?.next_cursor }
}
