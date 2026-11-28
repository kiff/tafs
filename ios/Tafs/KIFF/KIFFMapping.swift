import CryptoKit
import Foundation

/// Turns tafs cloud's list and KIFF's held calls and activity into what the app shows.
///
/// tafs cloud makes the list from KIFF's Cards (one taf per Card holder); the app reads
/// held calls from KIFF itself, to answer them as KIFF sent them. An amount
/// carries the unit and decimal places KIFF declares for it; where KIFF declares
/// none, or could not read it, the amount is a bare number. The app never guesses a
/// currency or a scale from a parameter's name.
enum KIFFMapping {
    /// A summed quantity ("sum(amount_cents)") is an amount; "count" is a count.
    static func isSum(_ quantity: String) -> Bool { quantity.lowercased().hasPrefix("sum(") }

    /// The parameter a summed quantity adds up: "sum(amount_cents)" → "amount_cents".
    static func summed(_ quantity: String) -> String? {
        guard isSum(quantity), quantity.hasSuffix(")") else { return nil }
        return String(quantity.dropFirst(4).dropLast())
    }

    /// An amount in the unit KIFF declares for it; a bare number when it declares none.
    static func money(_ minor: Int64, unit: String?, scale: Int?) -> Money {
        guard let unit, !unit.isEmpty else { return .bare(minor) }
        return Money(minor: minor, currency: unit, scale: scale ?? 0)
    }

    /// A held call's local id, the same on every refresh and every launch: derived from
    /// KIFF's id, so a notification is posted once and an answer removes the right row.
    static func heldID(_ kiffID: String) -> UUID {
        var bytes = Array(SHA256.hash(data: Data("tafs.held|\(kiffID)".utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50  // version 5 style: name-based
        bytes[8] = (bytes[8] & 0x3F) | 0x80  // RFC 4122 variant
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                           bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
    }

    /// A window as tafs cloud sends it: KIFF's name, in the words it sent with it.
    static func window(_ raw: String, _ words: TafsCloud.HomeTaf.WindowWords?) -> Window {
        Window(raw, each: words?.each, now: words?.now)
    }

    /// What the owner reads for an action, on a Card, an approval or a held call: its tool's
    /// title as KIFF gives it, else the action in words. The title is Setup's, set on the
    /// grant the owner signed, else the vendor's label (kiff/planque#37); the action's own
    /// name, a grant prefix included, stays out (kiff/tafs#138, items 52, 53). Never a name
    /// made up here. A vendor's label can be shared by several tools (/// review), so where it would read alike, a Card's lines and a held call add the action
    /// in words (`DraftCard.lines`, `says`); planque refuses two titles alike (kiff/planque#38).
    static func name(_ action: String, title: String?) -> String {
        let t = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? toolWords(action) : visible(t)
    }

    /// A held call's name, which the owner approves alone, without the Card's other lines:
    /// its title, with its action in words unless the title names the action's last word, the
    /// thing it acts on or does. A vendor's shared label ("Write Stripe API data · refund
    /// payment") then still says which tool, even when it shares the vendor's own words
    /// (stripe_create_refund); "Refund a payment", for create_refund, stays alone (kiff/tafs#138).
    static func says(_ action: String, title: String?) -> String {
        let name = KIFFMapping.name(action, title: title)
        var words = toolWords(action)
        let titleWords = Set(name.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }))
        var parts = words.lowercased().split(separator: " ")
        // planque ends a tool's name with four letters or digits of its grant ("create_refund_repg"):
        // with a title, a last word of four the title doesn't have is that, and never shown
        // (kiff/tafs#236, item 7).
        if title?.isEmpty == false, parts.count > 1, let last = parts.last, last.count == 4,
           !titleWords.contains(last), last.allSatisfy({ $0.isLetter || $0.isNumber }) {
            parts.removeLast()
            words = parts.joined(separator: " ")
        }
        guard let last = parts.last else { return name }
        return titleWords.contains(last) ? name : "\(name) · \(words)"
    }

    /// A tool's name in words, for people (kiff/tafs#138, item 52): past a server's prefix
    /// ("stripe.create_refund", "kiff__refund_order") and the grant's own ("bxkwf7vi_list_charges"),
    /// separators as spaces. "bxkwf7vi_list_charges" → "list charges".
    static func toolWords(_ tool: String) -> String {
        var name = tool.components(separatedBy: "__").last?.split(separator: ".").last.map(String.init) ?? tool
        if let r = name.range(of: #"^(?=[a-z]*[0-9])[a-z0-9]{6,}_"#, options: .regularExpression), r.upperBound < name.endIndex {
            name = String(name[r.upperBound...])
        }
        let words = name.split(whereSeparator: { $0 == "_" || $0 == "-" }).joined(separator: " ")
        return visible(words.isEmpty ? tool : words)
    }

    /// A vendor as people say it: "stripe-mcp" or "mcp.stripe.com" → "Stripe" (item 52).
    static func vendorWords(_ vendor: String) -> String {
        var v = vendor.lowercased()
        if v.hasPrefix("mcp.") { v.removeFirst(4) }
        for suffix in ["-mcp", "_mcp", ".mcp"] where v.hasSuffix(suffix) { v.removeLast(suffix.count) }
        if let dot = v.firstIndex(of: ".") { v = String(v[..<dot]) }
        guard let first = v.first else { return visible(vendor) }
        return visible(first.uppercased() + v.dropFirst().replacingOccurrences(of: "-", with: " "))
    }

    /// A taf as tafs cloud lists it (#91), for the screen: its state in the app's words, its
    /// Card's lines and use as tafs cloud read them from KIFF. Nothing is worked out here.
    static func taf(_ h: TafsCloud.HomeTaf, id: UUID, owner: String) -> Taf {
        func amount(_ a: TafsCloud.HomeTaf.Amount?) -> Money? { a.map { money($0.minor, unit: $0.unit, scale: $0.scale) } }
        func rule(_ r: TafsCloud.HomeTaf.Rule) -> CardRule {
            CardRule(action: r.action, label: visible(r.label), perAction: amount(r.perAction), perWindow: amount(r.perWindow),
                     countPerWindow: r.countPerWindow, window: window(r.window, r.windowWords),
                     outside: r.outside == "refuse" ? .refuse : .hold, extraRoom: amount(r.extraRoom),
                     baseLimit: amount(r.baseLimit), roomEnds: r.roomWindowEnd)
        }
        let rules = h.card.rules.map(rule)
        let count = h.card.ids.count
        // Its job in words, as tafs cloud says it (kiff/tafs#167); an agent tafs doesn't run
        // has none, and is described by its Cards.
        var taf = Taf(id: id, name: visible(h.name), job: h.jobSummary.map(visible) ?? Words.Taf.worksUnderCards(count: count),
                      tools: h.card.tools, colorIndex: h.color,
                      card: Card(rules: rules, issuedBy: owner, expires: h.card.expires, revoked: !h.card.active))
        switch h.state {
        case "waiting": taf.state = .waiting; taf.now = Words.Taf.Now.waitingOnYou
        case "paused": taf.state = .paused; taf.now = Words.Taf.State.paused
        // Halted, by itself or with the whole account: it reads so everywhere (kiff/tafs#236, item 13).
        case "halted": taf.state = .halted; taf.now = Words.Taf.Now.halted
        // A taf with no schedule isn't at work until it's told something in its chat
        // (kiff/tafs#138, item 55): it reads as ready, not running.
        case "working" where h.unscheduled == true: taf.state = .idle; taf.now = Words.Taf.Now.worksFromChat
        // tafs cloud's words when it sent them ("Next run Mon 08:00", with the day): a bare time read
        // as tomorrow. The app's own only from a tafs cloud that sends none.
        case "working": taf.state = .working
            taf.now = h.nextRunWords.map(visible) ?? h.nextRun.map { Words.Taf.Now.nextRun(time: "\($0.shortTime)") } ?? Words.Taf.Now.running
        case "idle": taf.state = .idle; taf.now = Words.Taf.Now.actingUnderKiff
        default: taf.state = .revoked; taf.now = Words.Taf.Now.cardNotActive
        }
        taf.kiffCardIDs = h.card.ids
        // Each Card on its own (kiff/tafs#211), as tafs cloud read it from KIFF.
        taf.kiffCards = h.card.cards?.map { c in
            TafCard(id: c.id, title: c.title, environment: c.environment, live: c.live, active: c.active, expires: c.expires,
                    rules: c.rules.map(rule),
                    total: c.total.flatMap { t in amount(t.limit).map { CardTotal(limit: $0, used: amount(t.used), window: window(t.window, t.windowWords),
                                                                                          extra: amount(t.extraRoom), base: amount(t.baseLimit), roomEnds: t.roomWindowEnd, pending: t.pendingText,
                                                                                          unitWords: t.unitWords) } },
                    calls: c.calls.map { CardTotal(limit: $0.limit, used: $0.used, window: window($0.window, $0.windowWords),
                                                   extra: $0.extraRoom, base: $0.baseLimit, roomEnds: $0.roomWindowEnd, pending: $0.pendingText) },
                    counted: c.counted?.map { CardCounted(tool: $0.tool, title: $0.title, lines: $0.lines) },
                    eachItem: c.eachItem?.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
        }
        taf.doors = h.doors
        taf.avatarRim = h.avatarRim
        taf.health = h.health
        // What went wrong last, before its next run: tafs cloud flags it (`attention`), and an idle
        // taf that isn't healthy says it too.
        if let work = h.work, work.attention || (work.status == "idle" && h.health?.needsAttention == true),
           !work.line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            taf.healthNow = visible(work.line)
            taf.attention = work.attention
        }
        taf.used = h.card.used.compactMapValues { amount($0) }
        taf.usageUnknown = Set(h.card.usageUnknown)
        // Its setup stopped partway: tafs cloud's words in place of what it's doing, and the chat
        // to finish it in. One with no chat or no words is none.
        if let finish = h.finish, finish.usable {
            taf.finish = finish
            taf.now = finish.words
        }
        return taf
    }

    /// What a taf did, as KIFF's activity records it (kiff/tafs#102): one entry a call of a
    /// holder the app shows, newest first, each with an id the same on every refresh.
    /// KIFF's decision and what the tool then did are kept apart (kiff/tafs#115): a call is
    /// done only when the tool said so, and no outcome is never read as done.
    /// `vendors` names the vendor each connected tool is from, by its name in KIFF, for a
    /// call the vendor refused (kiff/tafs#206 6.13).
    static func record(_ calls: [KIFFClient.ActivityCall], ids: [String: UUID], vendors: [String: String] = [:]) -> [RecordEntry] {
        calls.compactMap { c in
            guard let actor = c.actorID, let tafID = ids[actor] else { return nil }
            // Approved once only when KIFF says the owner answered this call with an approval (kiff/tafs#2225).
            // A held call that went through some other way (its Card changed, room given) is the Card's, as
            // KIFF's own trail on the receipt shows. As Android's KiffMapping.record.
            let approvedOnce = c.held && c.answer != nil && c.answer?.decision != "refused"
            let kind: RecordEntry.Kind
            let detail: String
            switch c.outcome {
            case "allowed":
                (kind, detail) = executed(c.toolOutcome, approved: approvedOnce, vendor: vendors[c.actionName], counts: counted(c))
            case "owner_required", "approval_required":
                kind = .held; detail = Words.Record.waitingForYou
            default:
                // KIFF's own words for why, when it has them (kiff/tafs#138).
                kind = .refused; detail = c.reason.map { Words.Record.notSentBecause(reason: visible("\($0)")) } ?? Words.Record.notSent
            }
            var entry = RecordEntry(id: recordID(c.proposalID), tafID: tafID, kind: kind, title: visible(name(c.actionName, title: c.title)),
                                    detail: detail, at: c.createdAt)
            if c.outcome == "allowed", c.toolOutcome?.status == "refused_by_tool", c.toolOutcome?.refusedBy == "connection", c.toolOutcome?.code != nil {
                entry.detail = c.detail.map(visible) ?? Words.Record.connectionRefusedBeforeSending
            }
            entry.proposalID = c.proposalID
            if c.outcome == "allowed", approvedOnce { entry.ownerApproved = true }
            if c.outcome == "allowed", c.toolOutcome?.status == "refused_by_tool" {
                entry.refusedByTool = true
                entry.refusedBy = c.toolOutcome?.refusedBy == "connection" ? Words.Record.theConnection : vendors[c.actionName]
            }
            entry.amount = c.amount
            if c.payment == true, kind == .done || kind == .approved { entry.moneyMoved = true }
            entry.readOnly = c.readOnly
            entry.entityID = c.entityID.map(visible)
            entry.action = c.actionName
            entry.onHash = c.onHash
            entry.onArg = c.onArg
            entry.answer = c.answer
            entry.receipt = c.receipt
            entry.why = c.reasons?.map(visible)
            entry.madeAt = c.madeAt
            entry.reason = c.reason.map(visible)
            return entry
        }
    }

    /// What a write's amount counts on the Card's totals: money when its unit is a currency,
    /// some other unit (a count of anything) when it isn't, or nothing.
    enum Counted { case money, amount, nothing }

    static func counted(_ c: KIFFClient.ActivityCall) -> Counted {
        guard let amount = c.amount, c.readOnly != true else { return .nothing }
        return isCurrency(amount.unit) ? .money : .amount
    }

    /// An ISO 4217 code: a gateway's unit can name anything, so only a currency is money.
    static func isCurrency(_ unit: String?) -> Bool {
        guard let unit, unit.count == 3 else { return false }
        return NSLocale.commonISOCurrencyCodes.contains(unit.uppercased())
    }

    /// The app's own words for why it refused or wasn't reached, when they're a short plain sentence: KIFF's
    /// `tool_outcome.message` carries the vendor's body, which can be JSON and name the connected account, a
    /// file or a request. Shown to everyone who sees the taf, people it's shared with included, it's quoted only
    /// when it's one line, at most 160 characters, not JSON, and names no email address; otherwise the line
    /// says who refused it, without the body (the same rule as Android's, and tafs cloud's Activity).
    static func quotable(_ message: String?) -> String? {
        guard let text = message?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty, text.count <= 160,
              !text.contains(where: \.isNewline) else { return nil }
        if text.hasPrefix("{") || text.hasPrefix("[") || text.contains("{\"") || text.contains("\":") { return nil }
        if text.range(of: #"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"#, options: .regularExpression) != nil { return nil }
        return visible(text)
    }

    /// What the tool did with an allowed call: its kind and the words for it. approved: the
    /// owner approved it, held first.
    /// A call the vendor refused still counts against the Card's totals: it
    /// says so plainly, naming the vendor as connected. Unless KIFF says no total kept it
    /// (`counted == false`: the tool did nothing, and the Card's call count counts what's carried
    /// out): then it says it doesn't count. The money and amount lines stay: those totals keep it.
    static func executed(_ o: KIFFClient.ActivityCall.ToolOutcome?, approved: Bool, vendor: String? = nil, counts: Counted = .nothing) -> (RecordEntry.Kind, String) {
        let why = quotable(o?.message).map { ": \($0)" } ?? ""
        let allowed = approved ? Words.Record.approvedByYou : Words.Record.allowedByCard
        switch o?.status {
        case "done"?:
            return approved ? (.approved, Words.Record.approvedThenDone) : (.done, Words.Record.doneWithinCard)
        case "refused_by_tool"?:
            let who = vendor ?? Words.Record.theTool
            switch counts {
            case .money: return (.notDone, Words.Record.toolRefusedMoney(who: "\(who)"))
            case .amount: return (.notDone, Words.Record.toolRefusedAmount(who: "\(who)"))
            case .nothing:
                if o?.counted == false { return (.notDone, Words.Record.toolRefusedNotCountedWhy(who: "\(who)", why: "\(why)")) }
                return (.notDone, Words.Record.toolRefusedCountsWhy(who: "\(who)", why: "\(why)"))
            }
        case "not_sent"?:
            return (.notDone, Words.Record.notSentNothingReachedWhy(allowed: "\(allowed)", why: "\(why)"))
        case "sending"?:
            return (.sending, Words.Record.sentWaiting(allowed: "\(allowed)"))
        case "unknown"?:
            return (.unknown, Words.Record.answerLost(allowed: "\(allowed)"))
        default:
            // not_recorded, none, or one this app doesn't know yet.
            return (.unknown, Words.Record.notRecorded(allowed: "\(allowed)"))
        }
    }

    /// Whether a value is what KIFF's hash names (kiff/tafs#167): "sha256:<hex>" of its
    /// UTF-8 bytes, untrimmed, in its own case.
    static func isOn(_ value: String, hash: String) -> Bool {
        hash == "sha256:" + SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// The Record's lines with what each acted on, from the tafs' call_refs by KIFF's key:
    /// only a value whose hash is KIFF's, so a line never names a payment KIFF didn't.
    static func naming(_ record: [RecordEntry], refs: [String: String]) -> [RecordEntry] {
        record.map { e in
            var e = e
            if let key = e.entityID, let hash = e.onHash, let value = refs[key], isOn(value, hash: hash) { e.onValue = value }
            return e
        }
    }

    /// A record entry's id, from KIFF's call id: the same on every refresh.
    static func recordID(_ proposalID: String) -> UUID {
        var bytes = Array(SHA256.hash(data: Data("tafs.record|\(proposalID)".utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                           bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
    }

    /// `coach`: the account's Setup agent, once the app has connected Coach's tools itself; a
    /// held version_install is Coach's only from it (kiff/tafs#206).
    static func held(_ e: KIFFClient.CardException, tafID: UUID, coach: String? = nil) -> HeldCall {
        let reason = e.reasonText.flatMap { $0.isEmpty ? nil : $0 } ?? moneyReason(e) ?? countReason(e)
            ?? reasonBreach(e)?.reason ?? e.reason ?? outcomeText(reasonBreach(e)?.outcome)
        // A gateway call is filed under its tool call's id, which says nothing to the owner:
        // what it's on is in its arguments, as KIFF keeps them (kiff/tafs#102).
        let name = visible(says(e.action, title: e.title))
        let summary = e.arguments == nil ? "\(name) \(visible(e.entityID))" : name
        var call = HeldCall(
            id: heldID(e.id),
            tafID: tafID,
            proposed: ProposedAction(action: e.action, summary: summary, amount: amount(of: e)),
            reason: visible(reason),
            heldAt: e.createdAt,
            expiresAt: e.expiresAt ?? e.createdAt.addingTimeInterval(10 * 60)
        )
        call.kiffID = e.id
        call.proposalID = e.proposalID
        // A sign-in fill is never allowed without its release, which this app can't make yet: Refuse only.
        call.needsRelease = e.needsRelease ? true : nil
        // A purchase is approved only with every word it signs read; one that doesn't read is refused for its case, never approved.
        call.purchase = e.purchaseBlock?.purchase
        call.purchaseBinding = e.purchaseBlock?.binding
        call.purchaseUnreadable = e.purchaseBlock != nil && e.purchaseBlock?.purchase == nil ? true : nil
        call.approvableOnce = (e.canApproveOnce ?? true) && !e.needsRelease && call.purchaseUnreadable != true
        call.accountingVersion = e.accountingVersion
        call.nextSteps = e.nextSteps ?? []
        call.details = details(of: e)
        call.install = install(of: e, coach: coach)
        call.tafAbout = tafWords(e.tafAbout, max: 120)
        call.tafReason = tafWords(e.tafReason, max: 280)
        call.agent = e.subject
        call.breachOutcomes = e.breaches.map(\.outcome)
        // The summary stays KIFF's: planque's words for the act carry the element's own label, which the page wrote,
        // so they're drawn only in the block's page part, never as what the card asks.
        call.browser = browserStep(e.browser)
        return call
    }

    /// A held browser step as the card draws it (docs/chrome.md, "The approval card"). Every line is one plain line,
    /// cut to planque's cap; without a site or planque's words for the act, there's no browser card and the hold
    /// reads as any other.
    /// One basket line as the card draws it: "2 × Mug · 12.50", the quantity only when there's more than one, the
    /// price as the page showed it. Each part is the page's words, made one plain line; a line with no item is left out.
    static func basketLine(_ line: KIFFClient.CardException.BrowserStep.Line?) -> String? {
        guard let line, let item = tafWords(line.item, max: 160) else { return nil }
        var text = item
        if let quantity = line.quantity, quantity > 1 { text = "\(quantity) × " + text }
        if let price = tafWords(line.price?.text, max: 30) { text += " · " + price }
        return text
    }

    static func browserStep(_ b: KIFFClient.CardException.BrowserStep?) -> BrowserStep? {
        guard let b, let site = tafWords(b.site, max: 253), let act = tafWords(b.actionWords, max: 200) else { return nil }
        let context = b.context?.value
        let checkout = b.checkout?.value
        return BrowserStep(
            site: site,
            origin: tafWords(b.origin, max: 300),
            actionWords: act,
            pageTitle: tafWords(context?.title, max: 200),
            pageText: tafWords(context?.text, max: 600),
            basket: Array((checkout?.lines ?? []).compactMap { basketLine($0.value) }.prefix(20)),
            chromeName: tafWords(b.chrome?.value?.name, max: 80)
        )
    }

    /// The arguments KIFF's gateway adds to every call: its operation id, which isn't the
    /// owner's, and the taf's own words, which the card shows under their heading. None is
    /// listed again under Details.
    static let kiffOwnArguments: Set<String> = ["kiff_operation_id", "kiff_about", "kiff_reason"]

    /// A line the taf wrote for the owner, made safe to draw (wave 1 item 4): it may come from
    /// a customer's text, so it's one plain line, with control characters and line breaks as
    /// spaces, runs of space as one, cut to KIFF's cap. It's drawn verbatim, never as markup or
    /// a link, and nothing is read from it. Nil when nothing is left.
    static func tafWords(_ text: String?, max: Int) -> String? {
        guard let text else { return nil }
        let flat = String(String.UnicodeScalarView(text.unicodeScalars.map {
            CharacterSet.controlCharacters.contains($0) || CharacterSet.newlines.contains($0) ? " " : $0
        }))
        let line = flat.split(whereSeparator: { $0 == " " }).joined(separator: " ")
        guard !line.isEmpty else { return nil }
        return line.count > max ? String(line.prefix(max - 1)) + "…" : line
    }

    /// The version a held version_install of Coach's names (kiff/tafs#206), or nil. Coach's only:
    /// held from `coach`, the account's Setup agent, on the connection the app made, which KIFF
    /// names the action after (a name is one connection per account). Anything else is an
    /// ordinary held call.
    static func install(of e: KIFFClient.CardException, coach: String?) -> VersionInstall? {
        guard let coach, !coach.isEmpty, e.subject == coach, e.action == "version_install",
              let taf = e.arguments?["taf"]?.text, !taf.isEmpty,
              let n = e.arguments?["n"].flatMap({ Int($0.text) }), n > 0 else { return nil }
        return VersionInstall(taf: taf, n: n)
    }

    /// The one breach a held call names (kiff/tafs#236, item 33). A call over both its limit
    /// for one call and a total can't be approved once, and the card says it's the total:
    /// the reason names that total too, not the first breach KIFF lists.
    static func reasonBreach(_ e: KIFFClient.CardException) -> KIFFClient.CardException.Breach? {
        if e.canApproveOnce == false, let total = e.breaches.first(where: { $0.outcome != "per_action_exceeded" }) {
            return total
        }
        return e.breaches.first
    }

    /// Why a call is held, with its amounts written as the app writes money everywhere else
    /// (kiff/tafs#206 6.8), when KIFF declares the limit's unit and it is the amount's: "€150
    /// is over the €100 limit for one call". Otherwise nil, and KIFF's own words show.
    /// A hold over a count of calls, in the Card's words, for a KIFF that doesn't send its own:
    /// a limit of 0 means each call asks first; otherwise which call this would be. Never the
    /// machine's "count limit 0 per calendar_day reached".
    static func countReason(_ e: KIFFClient.CardException) -> String? {
        guard let b = reasonBreach(e), b.outcome == "limit_reached", b.quantity == "count", let limit = b.limit else { return nil }
        if limit == 0 { return Words.Card.Held.eachCallAsks }
        let nth = ordinal((b.used ?? limit) + (b.draw ?? 1))
        guard let raw = b.window else { return Words.Card.overLimitCallsWould(limit: "\(limit)", nth: "\(nth)") }
        // A total for each item apart says so, and which item: "… for each invoice id: this would be the 2nd for in_42".
        let each = b.per.flatMap { $0.isEmpty ? nil : Words.Card.Held.forEach(name: $0.lowercased().replacingOccurrences(of: "_", with: " ")) } ?? ""
        let item = each.isEmpty ? "" : b.value.flatMap { $0.isEmpty ? nil : Words.Card.Held.forItem(item: $0) } ?? ""
        return Words.Card.Held.countInWindow(count: Int(limit), window: Window(raw).label, each: each, nth: nth, item: item)
    }

    /// 1st, 2nd, 3rd, 11th, 21st.
    static func ordinal(_ n: Int64) -> String {
        Words.ordinal(Int(n))
    }

    static func moneyReason(_ e: KIFFClient.CardException) -> String? {
        guard let b = reasonBreach(e), let limit = b.limit, let unit = b.unit, b.unitAmbiguous != true,
              let amount = amount(of: e), amount.currency == unit else { return nil }
        let cap = Money(minor: limit, currency: unit, scale: b.scale ?? amount.scale).formatted
        switch b.outcome {
        case "per_action_exceeded": return Words.Card.Held.overOneCall(amount: "\(amount.formatted)", cap: "\(cap)")
        case "limit_reached": return Words.Card.Held.overTotal(amount: "\(amount.formatted)", cap: "\(cap)")
        default: return nil
        }
    }

    /// What a held gateway call is on, as KIFF keeps its arguments ("payment_intent: pi_…"): one line an argument,
    /// and a list or object written out in full below its name, each item (1, 2, …) or field on its own line, two
    /// spaces deeper each level, keys in code point order at every level (contracts/held-arguments.json). Nothing the
    /// call carries is ever folded to "…": a held browser call's `steps` are what the owner signs, and a step the line
    /// hid could click what the taf's words never said (red-team, 27 Nov). The amount isn't repeated (it's shown
    /// above), nor the gateway's own arguments; an empty string at the top says nothing and isn't shown.
    static func details(of e: KIFFClient.CardException) -> [String] {
        guard let arguments = e.arguments else { return [] }
        let shown = Set((e.parameterUnits ?? [:]).keys.map { $0.lowercased() }).union((e.parameters ?? [:]).keys.map { $0.lowercased() })
        return arguments.keys.sorted(by: codePointOrder).flatMap { key -> [String] in
            let k = key.lowercased()
            guard !shown.contains(k), !kiffOwnArguments.contains(k), let value = arguments[key] else { return [] }
            if case .text(let t) = value, t.isEmpty { return [] }
            return argumentLines(visible(key), value, depth: 0)
        }
    }

    /// `label`'s `value` as lines: a plain value on its label's line; a list's items (1, 2, …) and an object's fields
    /// below it, two spaces deeper each level. A string below the top that is empty is written `""`, so a fill with
    /// nothing still shows.
    static func argumentLines(_ label: String, _ value: KIFFClient.CardException.Argument, depth: Int) -> [String] {
        let pad = String(repeating: "  ", count: depth)
        switch value {
        case .list(let items):
            guard !items.isEmpty else { return ["\(pad)\(label): []"] }
            return ["\(pad)\(label):"] + items.enumerated().flatMap { argumentLines("\($0.offset + 1)", $0.element, depth: depth + 1) }
        case .object(let fields):
            guard !fields.isEmpty else { return ["\(pad)\(label): {}"] }
            return ["\(pad)\(label):"] + fields.keys.sorted(by: codePointOrder).flatMap { key in
                fields[key].map { argumentLines(visible(key), $0, depth: depth + 1) } ?? []
            }
        case .text(let t): return ["\(pad)\(label): \(t.isEmpty ? "\"\"" : visible(t))"]
        case .number(let n): return ["\(pad)\(label): \(HeldArgumentsReader.plainLiteral(n))"]
        case .flag(let b): return ["\(pad)\(label): \(b ? "true" : "false")"]
        case .null: return ["\(pad)\(label): null"]
        }
    }

    /// Keys in Unicode code point order, the same on every app (a dictionary keeps no order of its own).
    static func codePointOrder(_ a: String, _ b: String) -> Bool {
        a.unicodeScalars.lexicographicallyPrecedes(b.unicodeScalars)
    }

    /// A line from KIFF, a vendor or a taf as the approval and the record draw it: no direction
    /// character (U+202A–U+202E, U+2066–U+2069, U+200E, U+200F, U+061C, `FileText.isDirection`), so a payee name
    /// can't reorder how an amount or an IBAN beside it looks; any other control character becomes a space.
    static func visible(_ text: String) -> String {
        var kept = String.UnicodeScalarView()
        for scalar in text.unicodeScalars where !FileText.isDirection(scalar) {
            kept.append(FileText.isControl(scalar) ? " " : scalar)
        }
        return String(kept)
    }

    /// The amount to show above the answer: a parameter KIFF declares a unit for, in that
    /// unit; otherwise the parameter a breach sums, as a bare number; otherwise none.
    static func amount(of e: KIFFClient.CardException) -> Money? {
        guard let parameters = e.parameters, !parameters.isEmpty else { return nil }
        let units = Dictionary((e.parameterUnits ?? [:]).map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { a, _ in a })
        let sorted = parameters.sorted { $0.key < $1.key }
        if let declared = sorted.first(where: { units[$0.key.lowercased()] != nil }),
           let unit = units[declared.key.lowercased()] {
            return Money(minor: declared.value, currency: unit.unit, scale: unit.scale)
        }
        let summedNames = e.breaches.compactMap { $0.quantity.flatMap(summed) }.map { $0.lowercased() }
        if let counted = sorted.first(where: { summedNames.contains($0.key.lowercased()) }) {
            return .bare(counted.value)
        }
        return nil
    }

    static func outcomeText(_ outcome: String?) -> String {
        switch outcome {
        case "per_action_exceeded": return Words.Card.Outcome.overOneAction
        case "limit_reached": return Words.Card.Outcome.overTotal
        case "resource_not_on_card": return Words.Card.Outcome.notAResource
        default: return Words.Card.Outcome.outside
        }
    }
}

extension TafState {
    /// tafs cloud's state for a taf on Home, as the app reads it above: a taf with no schedule is ready, not working,
    /// and any state the app doesn't know reads as stopped (no active Card).
    init(cloudState state: String, unscheduled: Bool? = nil) {
        switch state {
        case "waiting": self = .waiting
        case "paused": self = .paused
        case "halted": self = .halted
        case "working" where unscheduled == true: self = .idle
        case "working": self = .working
        case "idle": self = .idle
        default: self = .revoked
        }
    }
}
