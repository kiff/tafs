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
    /// Sends Coach the person's words under the message's id, and reads its answer for a moment.
    func coach(text: String, messageID: UUID) async -> WatchReply
    /// v2's Send (W2): the words, answered as soon as tafs cloud has them, with the ticket. As a watch request of their own
    /// (`POST /v1/watch/asks`, docs/watch-ask.md); `after` is the ticket of the request they reply to, when the watch's
    /// conversation is open.
    func coachSend(text: String, messageID: UUID, after: WatchCoachTicket?) async -> WatchReply
    /// Chatting with a taf (docs/watch-ask.md): the words into that taf's own chat, as the person, answered as soon as
    /// tafs cloud has them, with the ticket. Never Coach's chat, whatever tafs cloud answers.
    func tafSend(taf: String, text: String, messageID: UUID, after: WatchCoachTicket?) async -> WatchReply
    /// v2 (W2): Coach's answer so far to the words the ticket names, read once, and whether Coach is still at it.
    func coachRead(_ ticket: WatchCoachTicket) async -> WatchReply
    /// Your brief, as tafs cloud sends it; nil when the account has none.
    func brief() async throws -> WatchBrief?
    /// One taf's page (watch v2), by tafs cloud's id; nil for a taf the phone doesn't list.
    func tafPage(_ id: String) async throws -> WatchTafPage?
    /// A calendar day (watch v2), `YYYY-MM-DD`, or today in the account's zone with none.
    func calendar(day: String?) async throws -> WatchCalendarDay
    /// Who is signed in, the account's name and the plan's line (watch v2).
    func account() async throws -> WatchAccount
}

extension WatchReading {
    /// No brief: the watch shows Needs you, as it always has.
    func brief() async throws -> WatchBrief? { nil }
    /// A reader without W2's two steps: the watch sends as v1 does.
    func coachSend(text: String, messageID: UUID, after: WatchCoachTicket?) async -> WatchReply { WatchReply(.notYet) }
    /// A Send that replies to nothing.
    func coachSend(text: String, messageID: UUID) async -> WatchReply { await coachSend(text: text, messageID: messageID, after: nil) }
    func coachRead(_ ticket: WatchCoachTicket) async -> WatchReply { WatchReply(.notYet) }
    /// A reader from before chatting with a taf: nothing is sent.
    func tafSend(taf: String, text: String, messageID: UUID, after: WatchCoachTicket?) async -> WatchReply { WatchReply(.notYet) }
}

/// Answers the watch's requests (docs/watch-app.md, "The relay"). It answers only the kinds
/// `WatchRequest` decodes, only when the request is complete and live, and only while the
/// phone is signed in: anything else is refused before any read or signature. Only Refuse
/// signs, with the current key, as from its notification. Coach gets text, never audio.
@MainActor
enum WatchRelay {
    /// A held call's summary as the watch may keep it (its Inbox, watch v2): with no amount in it, even where KIFF's words
    /// write one in. First the call's own amount, in the ways it's written (150, 150.00, 150,00, 1,234.56, 1.234,56,
    /// 1 234,56), with its currency beside it if there is one; then any figure with a currency sign, code or word before or
    /// after it. A figure with no currency stays, so an order's number is never cut, and a space joins digits into one
    /// figure only in groups of three beside a currency ("order 4502 150 €" keeps 4502).
    static func withoutAmount(_ summary: String, amount: Money? = nil) -> String {
        var words = summary
        func remove(_ pattern: String) {
            words = words.replacingOccurrences(of: pattern, with: " ", options: [.regularExpression, .caseInsensitive])
        }
        if let amount, let own = ownFigure(amount) { remove(own) }
        return WatchAmount.strip(words)
    }

    /// The call's own amount as a pattern: its whole part plain or grouped in threes, its fraction (optional when it's all
    /// zeros, else in full or without its trailing zeros), and its currency, or any currency, on either side. A bare amount,
    /// with no unit, goes too: it's the figure the owner reads in Needs you, as Android takes it out for Wear.
    private static func ownFigure(_ amount: Money) -> String? {
        let scale = max(amount.scale, 0)
        let digits = String(amount.minor.magnitude)
        let padded = String(repeating: "0", count: max(0, scale + 1 - digits.count)) + digits
        let whole = String(padded.dropLast(scale))
        let fraction = String(padded.suffix(scale))
        var wholes = [whole]
        if whole.count > 3 {
            for separator in [",", ".", " ", "\u{00A0}", "\u{202F}", "'", "’"] { wholes.append(grouped(whole, by: separator)) }
        }
        var figure = "(?:" + wholes.sorted { $0.count > $1.count }.map(NSRegularExpression.escapedPattern(for:))
            .joined(separator: "|") + ")"
        if fraction.allSatisfy({ $0 == "0" }) {
            if !fraction.isEmpty { figure += "(?:[.,]\(fraction))?" }
        } else {
            let short = String(fraction.reversed().drop { $0 == "0" }.reversed())
            figure += "[.,](?:" + Set([fraction, short]).sorted { $0.count > $1.count }.joined(separator: "|") + ")"
        }
        let unit = amount.currency.map { "(?:" + WatchAmount.currency + #"|(?<!\p{L})"# + NSRegularExpression.escapedPattern(for: $0) + #"(?!\p{L}))"# }
            ?? WatchAmount.currency
        // Not the start of a longer figure either: "150.5" isn't 150.00, and stays whole (as on Android).
        return "(?:" + unit + #"\s?)?(?<![\d.,'’])"# + figure + #"(?![.,]?\d)(?:\s?"# + unit + ")?"
    }

    /// "1234567" as "1,234,567" with the separator given.
    private static func grouped(_ whole: String, by separator: String) -> String {
        var groups: [String] = []
        var rest = Substring(whole)
        while rest.count > 3 {
            groups.insert(String(rest.suffix(3)), at: 0)
            rest = rest.dropLast(3)
        }
        groups.insert(String(rest), at: 0)
        return groups.joined(separator: separator)
    }

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
            // The watch's id for the words, the same when they're sent again; a watch from before it
            // sends none, and the phone gives the message one.
            return await reading.coach(text: request.text ?? "", messageID: request.message.flatMap(UUID.init(uuidString:)) ?? UUID())
        case .coachSend:
            if let taf = request.taf {
                return await reading.tafSend(taf: taf, text: request.text ?? "", messageID: request.message.flatMap(UUID.init(uuidString:)) ?? UUID(),
                                             after: request.ticket)
            }
            return await reading.coachSend(text: request.text ?? "", messageID: request.message.flatMap(UUID.init(uuidString:)) ?? UUID(),
                                           after: request.ticket)
        case .coachRead:
            guard let ticket = request.ticket else { return WatchReply(.refused) }
            return await reading.coachRead(ticket)
        case .brief:
            // Read only: nothing is answered or signed from the brief, on the phone or the wrist.
            do {
                return WatchReply(.ok, brief: try await reading.brief())
            } catch {
                return WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff)
            }
        case .taf, .calendar, .account:
            // The watch v2 pages: reads only, nothing answered or signed.
            do {
                switch request.kind {
                case .taf:
                    guard let page = try await reading.tafPage(request.taf ?? "") else {
                        return WatchReply(.failed, line: Words.Watch.openOnPhone)
                    }
                    return WatchReply(.ok, tafPage: page)
                case .calendar:
                    return WatchReply(.ok, calendar: try await reading.calendar(day: request.day))
                default:
                    return WatchReply(.ok, account: try await reading.account())
                }
            } catch {
                return WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff)
            }
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
    /// W3: after a watch Send, the iPhone follows Coach's answer (`CoachFollow`). Only the app's own reader does.
    let follows: Bool

    init() {
        let token = Keychain.load(HoldNotification.sessionAccount)
        let kiff = KIFFClient()
        kiff.token = token
        self.init(kiff: kiff, cloud: TafsCloud(token: token), signer: ApprovalSigner(),
                  tenant: Keychain.load(AppModel.tenantAccount), keyed: Keychain.load(AppModel.keyedAccount) == "1", follows: true)
    }

    init(kiff: KIFFClient, cloud: TafsCloud, signer: ApprovalSigning, tenant: String?, keyed: Bool,
         wait: @escaping () async -> Void = { try? await Task.sleep(nanoseconds: 1_000_000_000) }, follows: Bool = false) {
        self.kiff = kiff
        self.cloud = cloud
        self.signer = signer
        self.tenant = tenant
        self.keyed = keyed
        self.wait = wait
        self.follows = follows
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
                return WatchNeedsYou.Call(id: hold.id, taf: taf, what: what,
                                          summary: WatchRelay.withoutAmount(call.proposed.summary, amount: call.proposed.amount),
                                          reason: call.reason,
                                          answerBy: HoldNotification.time(call.expiresAt, in: zone))
            }
        let count = await home?.needsYou?.count ?? calls.count
        return WatchNeedsYou(count: max(count, calls.count), calls: calls, at: now)
    }

    /// Home's state for each taf it lists, by tafs cloud's id, in the iPhone's words.
    static func homeStates(_ tafs: [TafsCloud.HomeTaf]) -> [String: String] {
        var words: [String: String] = [:]
        for taf in tafs {
            guard let id = taf.id else { continue }
            words[id] = TafState(cloudState: taf.state, unscheduled: taf.unscheduled).label
        }
        return words
    }

    /// Each taf with its state: halted with the account, or on its own, then paused, then its
    /// next run as tafs cloud words it, else its state as Home gives it ("Ready", "Stopped"), as the iPhone shows it.
    func tafs() async throws -> [WatchTaf] {
        let listed = try await cloud.tafs()
        let all = try? await cloud.halted(nil)
        let states = Self.homeStates((try? await cloud.home())?.tafs ?? [])
        var tafs: [WatchTaf] = []
        for taf in listed {
            // Its own halt, unless the account's covers it: also when the account's couldn't be read.
            let halt = all == nil || all?.state == .running ? (try? await cloud.halted(taf.id)) : all
            let line: String?
            switch halt?.state {
            case .halted: line = Words.Taf.State.halted
            case .halting: line = Words.Taf.Halt.halting
            case .running, nil: line = taf.paused ? Words.Taf.State.paused : (taf.nextRunWords ?? states[taf.id])
            }
            tafs.append(WatchTaf(id: taf.id, name: taf.name, line: line, halted: halt.map { $0.state != .running } ?? false))
        }
        return tafs
    }

    /// Your brief, read with the session the app keeps, as Needs you is (the app may be closed): tafs
    /// cloud's `GET /v1/brief`, unfiltered. A route tafs cloud doesn't serve yet (404) is no brief, as on
    /// the phone. Each decision goes with its words and none of its answers; a held call's names its id.
    func brief() async throws -> WatchBrief? {
        let read: TafsCloud.Brief
        do {
            read = try await cloud.brief(label: nil)
        } catch let e as TafsCloud.CloudError where e.status == 404 {
            return nil
        }
        let names = (try? await cloud.tafs()) ?? []
        return Self.watchBrief(read, tafNames: Dictionary(names.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first }))
    }

    /// The brief as the wrist gets it, from tafs cloud's: nil when there's nothing in it, as the phone shows
    /// Needs you then. Every word is tafs cloud's; the taf's name is the phone's, by tafs cloud's id.
    nonisolated static func watchBrief(_ brief: TafsCloud.Brief, tafNames: [String: String]) -> WatchBrief? {
        let usable = brief.decisions.filter(\.usable)
        guard !usable.isEmpty || !brief.done.isEmpty else { return nil }
        // All of them: tafs cloud caps the decisions itself and keeps every urgent one, and `more` counts the rest.
        let decisions = usable.map { d in
            WatchBrief.Decision(id: d.id, question: d.question, taf: tafNames[d.ref.taf], deadline: d.deadline, urgent: d.urgent,
                                cantWait: d.cantWait, refKind: d.ref.kind, refID: d.ref.id,
                                holdID: d.ref.kind == "hold" && !d.ref.id.isEmpty ? d.ref.id : nil)
        }
        let cases = brief.done.flatMap(\.cases).map { $0.name.isEmpty ? $0.words : $0.name }.filter { !$0.isEmpty }
        func words(_ s: String) -> String? { s.isEmpty ? nil : s }
        return WatchBrief(title: brief.words.title, when: words(brief.words.when), urgent: words(brief.words.urgent),
                          decisions: decisions, more: brief.more.flatMap { words($0.words) },
                          done: brief.done.isEmpty ? nil : words(brief.words.done),
                          doneCases: Array(cases.prefix(WatchBrief.wristCases)), rest: brief.rest.flatMap { words($0.words) })
    }

    /// One taf's page, read as Home reads it: tafs cloud's list with each Card's use, the taf's halt and the
    /// account's, and KIFF's held calls for its agent. Nil for a taf tafs cloud doesn't list.
    func tafPage(_ id: String) async throws -> WatchTafPage? {
        let home = try await cloud.home()
        guard let listed = home.tafs.first(where: { $0.id == id }) else { return nil }
        let all = try? await cloud.halted(nil)
        // Its own halt, unless the account's covers it, as `tafs()` reads it.
        let own = all?.state == .running || all == nil ? (try? await cloud.halted(id)) : nil
        let held = (try? await kiff.heldCalls()) ?? []
        let halted = [all, own].contains { $0.map { $0.state != .running } ?? false }
        return Self.tafPage(listed, halted: halted, waiting: held.filter { $0.subject == listed.kiffAgent }.count)
    }

    /// A taf's page from tafs cloud's listing, as the phone's Card tab draws it: the Cards in tafs cloud's order,
    /// each with its title, its face (figure and line) and a line per rule. Nil for an agent tafs doesn't run.
    static func tafPage(_ listed: TafsCloud.HomeTaf, halted: Bool, waiting: Int) -> WatchTafPage? {
        guard let id = listed.id else { return nil }
        let taf = KIFFMapping.taf(listed, id: UUID(), owner: "")
        let cards = taf.shownCards.map { card in
            WatchTafPage.Card(title: card.title, figure: card.hero?.figure, words: card.hero?.words,
                              rules: card.rules.map { Words.Card.ruleLine(rule: $0.label, summary: $0.summary) })
        }
        return WatchTafPage(id: id, name: taf.name, state: halted ? Words.Taf.State.halted : taf.now, halted: halted,
                            job: taf.job, cards: cards, waiting: waiting)
    }

    /// A calendar day, read from tafs cloud for that day alone; with none, today in the account's zone.
    func calendar(day: String?) async throws -> WatchCalendarDay {
        let shown: String
        if let day {
            shown = day
        } else {
            // The account's zone, else the phone's own (as Android's): closer to the person's day than UTC.
            let zone = (try? await kiff.me())?.timeZone.flatMap(TimeZone.init(identifier:)) ?? .current
            shown = Self.day(Date(), in: zone)
        }
        let read = try await cloud.calendar(from: shown, to: shown)
        return Self.calendarDay(shown, read)
    }

    /// A date as the calendar names its days, `YYYY-MM-DD`, in `zone`.
    nonisolated static func day(_ date: Date, in zone: TimeZone) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }

    /// A calendar day in tafs cloud's words: its line, and that day's events in time order, each with its taf's name.
    nonisolated static func calendarDay(_ day: String, _ read: TafsCloud.CalendarRead) -> WatchCalendarDay {
        let events = read.events.filter { $0.day == day }.sorted { $0.minute < $1.minute }
            .map { WatchCalendarDay.Event(time: $0.time, taf: $0.name, words: $0.words, count: $0.count) }
        return WatchCalendarDay(day: day, words: read.words, events: events, halted: read.halted)
    }

    /// Who is signed in, and the plan's line as Account shows it (none when tafs cloud doesn't send one).
    func account() async throws -> WatchAccount {
        let me = try await kiff.me()
        let balance = (try? await cloud.balance()) ?? nil
        return Self.watchAccount(email: me.email, displayName: me.displayName, slug: me.slug, plan: balance?.planLine)
    }

    /// The account as the wrist names it: its display name, else its slug.
    nonisolated static func watchAccount(email: String?, displayName: String?, slug: String, plan: String?) -> WatchAccount {
        func words(_ s: String?) -> String? { s.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } }
        return WatchAccount(email: words(email), account: words(displayName) ?? words(slug), plan: words(plan))
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

    /// How long the phone waits for Coach's answer, inside the watch's live request: well within what
    /// the watch waits for a reply, so the watch always hears back; past it, the rest is on the phone.
    static let coachWait: TimeInterval = 12

    /// Coach, as the app's plain send does (`AppModel`'s setup send when the stream can't open):
    /// the words to Coach's current chat, then its thread read until Coach's answer to them is
    /// written, for `coachWait` at most. Refusals come back in the phone's words.
    func coach(text: String, messageID: UUID) async -> WatchReply {
        let setup: TafsCloud.CloudTaf
        let sent: TafsCloud.Sent
        switch await post(text: text, messageID: messageID) {
        case .failure(let refusal): return refusal.reply
        case .success(let posted): (setup, sent) = posted
        }
        var waited: TimeInterval = 0
        var answer: WatchCoachAnswer?
        while waited <= Self.coachWait {
            if let thread = try? await cloud.setupThread(setup.id, session: sent.session) {
                answer = Self.answer(in: thread, after: sent.message?.id, session: sent.session)
                if thread.working == nil, answer != nil { break }
            }
            await wait()
            waited += 1
        }
        guard var answer else { return WatchReply(.ok, line: Words.Watch.moreOnPhone) }
        if waited > Self.coachWait { answer.more = true }
        return WatchReply(.ok, coach: answer)
    }

    /// Why the words weren't sent, in the phone's words.
    struct Refusal: Error {
        let reply: WatchReply
    }

    /// The words to Coach's current chat under the message's id; refusals in the phone's words.
    private func post(text: String, messageID: UUID) async -> Result<(TafsCloud.CloudTaf, TafsCloud.Sent), Refusal> {
        do {
            guard let setup = try await cloud.setup() else { return .failure(Refusal(reply: WatchReply(.failed, line: Words.Watch.openOnPhone))) }
            let sent = try await cloud.sendMessage(setup.id, text: text, messageID: messageID)
            Self.coachChats.insert(setup.id)
            return .success((setup, sent))
        } catch let e as TafsCloud.CloudError where e.code == TafsCloud.aiConsentRequired {
            return .failure(Refusal(reply: WatchReply(.failed, line: Words.Watch.coachNeedsConsent)))
        } catch let e as TafsCloud.CloudError where AppModel.waitsForCredits(e) != nil {
            return .failure(Refusal(reply: WatchReply(.failed, line: AppModel.waitsForCredits(e))))
        } catch {
            return .failure(Refusal(reply: WatchReply(.failed, line: Words.Alert.notSent + "\n" + error.localizedDescription)))
        }
    }

    /// The Coach chats this phone sent the watch's words to since it started: a read names one of them, or the chat
    /// tafs cloud gives as Coach's now, never another conversation.
    static var coachChats: Set<String> = []

    /// v2's Send (W2): the words, answered as soon as tafs cloud has them, with where the answer will be. A watch request of
    /// their own first (`POST /v1/watch/asks`, signed with this phone's device key, never the approval key, with no prompt);
    /// where tafs cloud's switch for it is off (404 `watch_off`), Coach's chat, as before.
    func coachSend(text: String, messageID: UUID, after: WatchCoachTicket?) async -> WatchReply {
        if let asked = await ask(text: text, clientID: messageID.uuidString.lowercased(), replyTo: after?.askID) { return asked }
        switch await post(text: text, messageID: messageID) {
        case .failure(let refusal): return refusal.reply
        case .success(let (setup, sent)):
            let ticket = WatchCoachTicket(chat: setup.id, session: sent.session, sent: sent.message?.id)
            // W3: if the watch is put away before Coach answers, the iPhone posts the answer (`CoachFollow`).
            if follows {
                let reading = self
                Task { @MainActor in await CoachFollow.run(ticket, read: { await reading.readCoach($0) }) }
            }
            return WatchReply(.ok, ticket: ticket)
        }
    }

    /// Chatting with a taf: the words as a watch request naming it. Whatever tafs cloud answers, they never go to Coach:
    /// with the switch off, the taf gone or not the person's to use, the watch says to open the phone.
    func tafSend(taf: String, text: String, messageID: UUID, after: WatchCoachTicket?) async -> WatchReply {
        await ask(text: text, clientID: messageID.uuidString.lowercased(), replyTo: after?.askID, tafID: taf)
            ?? WatchReply(.failed, line: Words.Watch.openOnPhone)
    }

    /// The words as a watch request of their own (docs/watch-ask.md): the exact body, signed with the device key, under the
    /// watch's id for them, so a Send again gets the same request back. Nil where tafs cloud's switch for it is off, or the
    /// phone has no account to sign for: then Coach's chat takes them, as before. With `tafID`, that taf's own chat takes
    /// them, and a 404 is never nil: the words never fall back to Coach.
    func ask(text: String, clientID: String, replyTo: String?, tafID: String? = nil) async -> WatchReply? {
        guard let tenant else { return nil }
        let body = TafsCloud.watchAskBody(clientID: clientID, text: text, replyTo: replyTo, tafID: tafID)
        let signedAt = SigningTime.next()
        let message = KIFFClient.requestMessage(tenant: tenant, method: "POST", uri: TafsCloud.watchAsksPath, body: body, signedAt: signedAt)
        let signature: String
        do { signature = try signer.signConfirmed(message) } catch { return WatchReply(.failed, line: Words.Alert.notSent) }
        do {
            let asked = try await cloud.watchAsk(body: body, signature: "t=\(signedAt),v1=\(signature)")
            let ticket = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: asked.id, sent: nil)
            // W3: if the watch is put away before the request ends, the iPhone posts how it went (`CoachFollow`).
            if follows {
                let reading = self
                Task { @MainActor in await CoachFollow.run(ticket, read: { await reading.readCoach($0) }) }
            }
            return WatchReply(.ok, ticket: ticket)
        } catch let e as TafsCloud.CloudError where e.status == 404 && e.code == "no_coach" {
            return WatchReply(.failed, line: Words.Watch.openOnPhone)
        } catch let e as TafsCloud.CloudError where e.status == 404 && e.code == "no_ask" && replyTo != nil {
            // The request it replies to is gone (kept 30 days): the words go as a request of their own.
            return await ask(text: text, clientID: clientID, replyTo: nil, tafID: tafID)
        } catch let e as TafsCloud.CloudError where tafID != nil && (e.status == 404 || e.code == "coach_taf") {
            // The switch for chatting with a taf is off (`watch_taf_chat_off`), or the taf isn't one the person may use
            // (`no_taf`): the phone has it. Never Coach's chat.
            return WatchReply(.failed, line: Words.Watch.openOnPhone)
        } catch let e as TafsCloud.CloudError where e.status == 404 {
            // tafs cloud's switch for it is off for this account (`watch_off`), or a tafs cloud without the route.
            return nil
        } catch let e as TafsCloud.CloudError where e.status == 409 && e.code == "reply_too_soon" && replyTo != nil {
            // The request it replies to hasn't ended: the words go as a request of their own.
            return await ask(text: text, clientID: clientID, replyTo: nil, tafID: tafID)
        } catch let e as TafsCloud.CloudError where e.status == 403 && e.code == "view_only" {
            // This phone's session can only view (KIFF's `view_only`): it signs nothing, so the watch can't ask through it.
            return WatchReply(.failed, line: Words.Watch.viewOnlyPhone)
        } catch let e as TafsCloud.CloudError where e.code == TafsCloud.aiConsentRequired {
            return WatchReply(.failed, line: Words.Watch.coachNeedsConsent)
        } catch let e as TafsCloud.CloudError where AppModel.waitsForCredits(e) != nil {
            return WatchReply(.failed, line: AppModel.waitsForCredits(e))
        } catch {
            return WatchReply(.failed, line: Words.Alert.notSent + "\n" + error.localizedDescription)
        }
    }

    /// v2 (W2): Coach's thread read once: its answer so far, and whether its chat says it's still working. A chat that
    /// isn't Coach's is never read. A watch request is read by its id, the person's own only.
    func coachRead(_ ticket: WatchCoachTicket) async -> WatchReply {
        if ticket.askID == nil, !Self.coachChats.contains(ticket.chat) {
            // Not reached is said as such; only a chat that is someone else's is refused.
            let setup: TafsCloud.CloudTaf?
            do { setup = try await cloud.setup() } catch { return WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff) }
            guard let setup, setup.id == ticket.chat else { return WatchReply(.refused) }
            Self.coachChats.insert(setup.id)
        }
        CoachFollow.watchReading(ticket)
        let reply = await readCoach(ticket)
        if reply.outcome == .ok, reply.answering == false, reply.coach != nil { CoachFollow.watchHas(ticket) }
        return reply
    }

    /// Coach's thread read once by a ticket whose chat is Coach's: its answer so far, and whether it's still working. A watch
    /// request's ticket reads the request.
    func readCoach(_ ticket: WatchCoachTicket) async -> WatchReply {
        if let id = ticket.askID { return await readAsk(id) }
        guard let thread = try? await cloud.setupThread(ticket.chat, session: ticket.session) else {
            return WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff)
        }
        let answer = Self.answer(in: thread, after: ticket.sent, session: ticket.session)
        return WatchReply(.ok, coach: answer, answering: thread.working != nil)
    }

    /// A watch request read once: while it's at work, "still answering"; once it ends, or a call of it waits for the owner,
    /// its line (else tafs cloud's words for its state), with "Approve on your phone" for the held call.
    func readAsk(_ id: String) async -> WatchReply {
        guard let asked = try? await cloud.watchAsk(id) else { return WatchReply(.failed, line: Words.Alert.tafsCouldNotReachKiff) }
        return Self.reply(asked)
    }

    nonisolated static func reply(_ asked: TafsCloud.WatchAsk) -> WatchReply {
        guard asked.ended || asked.held else { return WatchReply(.ok, answering: true) }
        return WatchReply(.ok, coach: WatchCoachAnswer.cut(asked.shown, more: asked.more, approveOnPhone: asked.held), answering: false)
    }

    /// Coach's answer to the person's message: Coach's words written after it in that chat, and
    /// whether it shows any card there. Nil while Coach has written nothing yet.
    static func answer(in thread: SetupThread, after sentID: String?, session: String) -> WatchCoachAnswer? {
        // Until the person's own message is in the thread, nothing in it can be its answer: an older
        // answer or card is never taken for this one.
        guard let mine = thread.messages.last(where: { $0.session == session && $0.from == .owner && (sentID == nil || $0.serverID == sentID) })
        else { return nil }
        let since = mine.at
        let words = thread.messages.filter { $0.session == session && $0.from == .taf && $0.at >= since }.map(\.text)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let cards = thread.components.contains { $0.session == session && $0.firstAt >= since }
        guard !words.isEmpty || cards else { return nil }
        return WatchCoachAnswer.cut(words.joined(separator: "\n\n"), more: false, approveOnPhone: cards)
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
