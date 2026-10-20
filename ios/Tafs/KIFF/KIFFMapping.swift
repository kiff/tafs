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

    /// What the owner reads for an action: its tool's title as KIFF gives it (from the
    /// vendor, kiff-cloud#1277), else the action as KIFF sends it. Never a name made up here.
    static func name(_ action: String, title: String?) -> String {
        let t = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? action : t
    }

    /// What an approval names (a held call, a draft's Card): the vendor's title beside
    /// KIFF's own action, never instead of it. The words on an approval are part of what's
    /// approved, and a vendor chooses its titles (kiff-cloud#1277 review).
    static func approvalName(_ action: String, title: String?) -> String {
        let t = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty || t == action ? action : "\(t) · \(action)"
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
        var taf = Taf(id: id, name: h.name, job: "Works under \(count) KIFF Card\(count == 1 ? "" : "s").",
                      tools: h.card.tools, colorIndex: h.color,
                      card: Card(rules: rules, issuedBy: owner, expires: h.card.expires, revoked: !h.card.active))
        switch h.state {
        case "waiting": taf.state = .waiting; taf.now = "Waiting on you"
        case "paused": taf.state = .paused; taf.now = "Paused"
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
                kind = .refused; detail = "Not sent"
            }
            var entry = RecordEntry(id: recordID(c.proposalID), tafID: tafID, kind: kind, title: name(c.actionName, title: c.title),
                                    detail: detail, at: c.createdAt)
            if c.outcome == "allowed", c.held { entry.ownerApproved = true }
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
        let name = approvalName(e.action, title: e.title)
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
