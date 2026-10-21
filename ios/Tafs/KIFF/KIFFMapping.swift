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

    static func window(_ raw: String) -> Window {
        switch raw.lowercased() {
        case let w where w.contains("week") || w == "7d": return .week
        case let w where w.contains("month") || w == "30d": return .month
        default: return .day
        }
    }

    /// What the owner reads for an action, on a Card, an approval or a held call: its tool's
    /// title as KIFF gives it, else the action in words. The title is Setup's, set on the
    /// grant the owner signed, else the vendor's label (kiff/planque#37); the action's own
    /// name, a grant prefix included, stays out (kiff/tafs#138, items 52, 53). Never a name
    /// made up here. A vendor's label can be shared by several tools (kiff-cloud#1277's
    /// review), so where it would read alike, a Card's lines and a held call add the action
    /// in words (`DraftCard.lines`, `says`); planque refuses two titles alike (kiff/planque#38).
    static func name(_ action: String, title: String?) -> String {
        let t = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? toolWords(action) : t
    }

    /// A held call's name, which the owner approves alone, without the Card's other lines:
    /// its title, with its action in words unless the title names the action's last word, the
    /// thing it acts on or does. A vendor's shared label ("Write Stripe API data · refund
    /// payment") then still says which tool, even when it shares the vendor's own words
    /// (stripe_create_refund); "Refund a payment", for create_refund, stays alone (kiff/tafs#138).
    static func says(_ action: String, title: String?) -> String {
        let name = KIFFMapping.name(action, title: title)
        let words = toolWords(action)
        let titleWords = Set(name.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }))
        guard let last = words.lowercased().split(separator: " ").last else { return name }
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
        return words.isEmpty ? tool : words
    }

    /// A vendor as people say it: "stripe-mcp" or "mcp.stripe.com" → "Stripe" (item 52).
    static func vendorWords(_ vendor: String) -> String {
        var v = vendor.lowercased()
        if v.hasPrefix("mcp.") { v.removeFirst(4) }
        for suffix in ["-mcp", "_mcp", ".mcp"] where v.hasSuffix(suffix) { v.removeLast(suffix.count) }
        if let dot = v.firstIndex(of: ".") { v = String(v[..<dot]) }
        guard let first = v.first else { return vendor }
        return first.uppercased() + v.dropFirst().replacingOccurrences(of: "-", with: " ")
    }

    /// A taf as tafs cloud lists it (#91), for the screen: its state in the app's words, its
    /// Card's lines and use as tafs cloud read them from KIFF. Nothing is worked out here.
    static func taf(_ h: TafsCloud.HomeTaf, id: UUID, owner: String) -> Taf {
        func amount(_ a: TafsCloud.HomeTaf.Amount?) -> Money? { a.map { money($0.minor, unit: $0.unit, scale: $0.scale) } }
        let rules = h.card.rules.map { r in
            CardRule(action: r.action, label: r.label, perAction: amount(r.perAction), perWindow: amount(r.perWindow),
                     countPerWindow: r.countPerWindow, window: Window(rawValue: r.window) ?? .day,
                     outside: r.outside == "refuse" ? .refuse : .hold)
        }
        let count = h.card.ids.count
        // Its job in words, as tafs cloud says it (kiff/tafs#167); an agent tafs doesn't run
        // has none, and is described by its Cards.
        var taf = Taf(id: id, name: h.name, job: h.jobSummary ?? "Works under \(count) KIFF Card\(count == 1 ? "" : "s").",
                      tools: h.card.tools, colorIndex: h.color,
                      card: Card(rules: rules, issuedBy: owner, expires: h.card.expires, revoked: !h.card.active))
        switch h.state {
        case "waiting": taf.state = .waiting; taf.now = "Waiting on you"
        case "paused": taf.state = .paused; taf.now = "Paused"
        // A taf with no schedule isn't at work until it's told something in its chat
        // (kiff/tafs#138, item 55): it reads as ready, not running.
        case "working" where h.unscheduled == true: taf.state = .idle; taf.now = "Works from its chat"
        case "working": taf.state = .working; taf.now = h.nextRun.map { "Next run \($0.shortTime)" } ?? "Running"
        case "idle": taf.state = .idle; taf.now = "Acting under KIFF"
        default: taf.state = .revoked; taf.now = "Card not active"
        }
        taf.kiffCardIDs = h.card.ids
        taf.used = h.card.used.compactMapValues { amount($0) }
        taf.usageUnknown = Set(h.card.usageUnknown)
        return taf
    }

    /// What a taf did, as KIFF's activity records it (kiff/tafs#102): one entry a call of a
    /// holder the app shows, newest first, each with an id the same on every refresh.
    /// KIFF's decision and what the tool then did are kept apart (kiff/tafs#115): a call is
    /// done only when the tool said so, and no outcome is never read as done.
    static func record(_ calls: [KIFFClient.ActivityCall], ids: [String: UUID]) -> [RecordEntry] {
        calls.compactMap { c in
            guard let actor = c.actorID, let tafID = ids[actor] else { return nil }
            let kind: RecordEntry.Kind
            let detail: String
            switch c.outcome {
            case "allowed":
                (kind, detail) = executed(c.toolOutcome, approved: c.held)
            case "owner_required", "approval_required":
                kind = .held; detail = "Waiting for you"
            default:
                // KIFF's own words for why, when it has them (kiff/tafs#138).
                kind = .refused; detail = c.reason.map { "Not sent · \($0)" } ?? "Not sent"
            }
            var entry = RecordEntry(id: recordID(c.proposalID), tafID: tafID, kind: kind, title: name(c.actionName, title: c.title),
                                    detail: detail, at: c.createdAt)
            if c.outcome == "allowed", c.held { entry.ownerApproved = true }
            entry.amount = c.amount
            entry.readOnly = c.readOnly
            entry.entityID = c.entityID
            entry.onHash = c.onHash
            entry.onArg = c.onArg
            entry.answer = c.answer
            entry.receipt = c.receipt
            entry.why = c.reasons
            entry.madeAt = c.madeAt
            entry.reason = c.reason
            return entry
        }
    }

    /// What the tool did with an allowed call: its kind and the words for it. approved: the
    /// owner approved it, held first.
    static func executed(_ o: KIFFClient.ActivityCall.ToolOutcome?, approved: Bool) -> (RecordEntry.Kind, String) {
        let why = o?.message.flatMap { $0.isEmpty ? nil : ": \($0)" } ?? ""
        let allowed = approved ? "Approved by you" : "Allowed by its Card"
        switch o?.status {
        case "done"?:
            return approved ? (.approved, "Approved by you, then done") : (.done, "Done, within its Card")
        case "refused_by_tool"?:
            return (.notDone, "\(allowed), but the tool refused it\(why)")
        case "not_sent"?:
            return (.notDone, "\(allowed), but it couldn't be sent: nothing reached the tool\(why)")
        case "sending"?:
            return (.sending, "\(allowed), and sent: waiting for the tool's answer")
        case "unknown"?:
            return (.unknown, "\(allowed), and sent, but the tool's answer was lost: check the tool's own records")
        default:
            // not_recorded, none, or one this app doesn't know yet.
            return (.unknown, "\(allowed), but whether the tool did it wasn't recorded: check the tool's own records")
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

    static func held(_ e: KIFFClient.CardException, tafID: UUID) -> HeldCall {
        let reason = e.breaches.first?.reason ?? e.reason ?? outcomeText(e.breaches.first?.outcome)
        // A gateway call is filed under its tool call's id, which says nothing to the owner:
        // what it's on is in its arguments, as KIFF keeps them (kiff/tafs#102).
        let name = says(e.action, title: e.title)
        let summary = e.arguments == nil ? "\(name) \(e.entityID)" : name
        var call = HeldCall(
            id: heldID(e.id),
            tafID: tafID,
            proposed: ProposedAction(action: e.action, summary: summary, amount: amount(of: e)),
            reason: reason,
            heldAt: e.createdAt,
            expiresAt: e.expiresAt ?? e.createdAt.addingTimeInterval(10 * 60)
        )
        call.kiffID = e.id
        call.proposalID = e.proposalID
        call.approvableOnce = e.canApproveOnce ?? true
        call.accountingVersion = e.accountingVersion
        call.nextSteps = e.nextSteps ?? []
        call.details = details(of: e)
        return call
    }

    /// What a held gateway call is on, one line an argument, as KIFF keeps them ("payment_intent:
    /// pi_…"). The amount isn't repeated (it's shown above), nor KIFF's own operation id.
    static func details(of e: KIFFClient.CardException) -> [String] {
        guard let arguments = e.arguments else { return [] }
        let shown = Set((e.parameterUnits ?? [:]).keys.map { $0.lowercased() }).union((e.parameters ?? [:]).keys.map { $0.lowercased() })
        return arguments.keys.sorted().compactMap { key in
            let k = key.lowercased()
            guard !shown.contains(k), k != "kiff_operation_id", let text = arguments[key]?.text, !text.isEmpty else { return nil }
            return "\(key): \(text)"
        }
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
        case "per_action_exceeded": return "Over the Card's limit for one action"
        case "limit_reached": return "Over the Card's total"
        case "resource_not_on_card": return "Not one of the Card's resources"
        default: return "Outside the Card"
        }
    }
}
