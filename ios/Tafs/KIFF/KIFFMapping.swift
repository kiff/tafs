import CryptoKit
import Foundation

/// Turns KIFF's Cards and held calls into the app's tafs and held calls.
///
/// One taf per Card holder (the agent KIFF knows as the Card's subject). An amount
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

    static func label(_ action: String) -> String {
        let words = action.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: ".", with: " ")
        return words.prefix(1).uppercased() + words.dropFirst()
    }

    static func rules(_ mandate: KIFFClient.Mandate) -> [CardRule] {
        let outside: Outside = mandate.onExceed == "refuse" ? .refuse : .hold
        var rules = mandate.grants.map { grant -> CardRule in
            var rule = CardRule(action: grant.action, label: label(grant.action), outside: outside)
            if let cap = grant.perActionMax?.first { rule.perAction = money(cap.max, unit: cap.unit, scale: cap.scale) }
            return rule
        }
        // A Card's totals cover all its grants; show one of each, on the first line: the
        // first summed total, with the ceiling it has now (extra room included), and the
        // first count.
        guard !rules.isEmpty else { return rules }
        let count = mandate.aggregates.first { !isSum($0.quantity) }
        if let count { rules[0].countPerWindow = Int(count.limit) }
        if let shown = shownTotal(mandate) {
            let (total, line) = shown
            let ceiling = line.flatMap { $0.status == "ok" ? $0.limit : nil } ?? total.limit
            rules[0].perWindow = money(ceiling, unit: line?.unit ?? total.unit, scale: line?.scale ?? total.scale)
            rules[0].window = window(total.window)
        } else if let count {
            rules[0].window = window(count.window)
        }
        return rules
    }

    /// The summed total a Card shows, and its use line from `?include=use`: the same
    /// quantity and window, so the meter never divides one window's use by another's.
    static func shownTotal(_ mandate: KIFFClient.Mandate) -> (KIFFClient.Mandate.Aggregate, KIFFClient.Mandate.Limit?)? {
        guard let total = mandate.aggregates.first(where: { isSum($0.quantity) }) else { return nil }
        let line = mandate.limits?.first { $0.quantity == total.quantity && $0.window == total.window }
        return (total, line)
    }

    /// One taf per holder; `ids` keeps each holder's taf id stable across refreshes.
    static func tafs(from mandates: [KIFFClient.Mandate], ids: inout [String: UUID], owner: String) -> [Taf] {
        let live = mandates.filter { $0.revokedAt == nil }
        let holders = Array(Set(live.map(\.subject))).sorted()
        return holders.enumerated().map { index, holder in
            let cards = live.filter { $0.subject == holder }
            let id = ids[holder] ?? UUID()
            ids[holder] = id
            let card = Card(
                rules: cards.flatMap(rules),
                issuedBy: owner,
                expires: cards.compactMap(\.validUntil).min() ?? .distantFuture,
                revoked: cards.allSatisfy { !$0.active }
            )
            var taf = Taf(id: id, name: holder, job: "Works under \(cards.count) KIFF Card\(cards.count == 1 ? "" : "s").",
                          tools: [], colorIndex: index, card: card)
            taf.state = card.revoked ? .revoked : .working
            taf.now = card.revoked ? "Card not active" : "Acting under KIFF"
            taf.kiffCardIDs = cards.map(\.id)
            // What each Card has used of the total it shows. When KIFF could not read it,
            // the taf says so instead of showing nothing used.
            var unknown = Set<String>()
            for card in cards {
                guard let action = card.grants.first?.action, let shown = shownTotal(card) else { continue }
                let (total, line) = shown
                if let line, line.status == "ok", let used = line.used {
                    taf.used[action] = money(used, unit: line.unit ?? total.unit, scale: line.scale ?? total.scale)
                } else {
                    unknown.insert(action)
                }
            }
            taf.usageUnknown = unknown
            return taf
        }
    }

    static func held(_ e: KIFFClient.CardException, tafID: UUID) -> HeldCall {
        let reason = e.breaches.first?.reason ?? e.reason ?? outcomeText(e.breaches.first?.outcome)
        var call = HeldCall(
            id: heldID(e.id),
            tafID: tafID,
            proposed: ProposedAction(action: e.action, summary: "\(label(e.action)) \(e.entityID)", amount: amount(of: e)),
            reason: reason,
            heldAt: e.createdAt,
            expiresAt: e.expiresAt ?? e.createdAt.addingTimeInterval(10 * 60)
        )
        call.kiffID = e.id
        call.nextSteps = e.nextSteps ?? []
        return call
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
