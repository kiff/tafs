import Foundation

/// Turns KIFF's Cards and held calls into the app's tafs and held calls.
///
/// One taf per Card holder (the agent KIFF knows as the Card's subject). Amounts
/// are read as money only when KIFF's quantity says cents; anything else is a count,
/// so the app never invents a currency.
enum KIFFMapping {
    static func isMoney(_ quantity: String) -> Bool { quantity.lowercased().contains("cents") }

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
            if let cap = grant.perActionMax?.first, isMoney(cap.quantity) { rule.perAction = Money(minor: cap.max) }
            return rule
        }
        // A Card's totals cover all its grants; show each once, on the first line.
        for aggregate in mandate.aggregates where !rules.isEmpty {
            if isMoney(aggregate.quantity) {
                rules[0].perWindow = Money(minor: aggregate.limit)
            } else {
                rules[0].countPerWindow = Int(aggregate.limit)
            }
            rules[0].window = window(aggregate.window)
        }
        return rules
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
            return taf
        }
    }

    static func held(_ e: KIFFClient.CardException, tafID: UUID) -> HeldCall {
        let amountKey = e.parameters?.keys.sorted().first { isMoney($0) || $0.lowercased().contains("amount") }
        let amount = amountKey.flatMap { e.parameters?[$0] }.map { Money(minor: $0) }
        let reason = e.breaches.first?.reason ?? e.reason ?? outcomeText(e.breaches.first?.outcome)
        var call = HeldCall(
            tafID: tafID,
            proposed: ProposedAction(action: e.action, summary: "\(label(e.action)) \(e.entityID)", amount: amount),
            reason: reason,
            heldAt: e.createdAt,
            expiresAt: e.expiresAt ?? e.createdAt.addingTimeInterval(10 * 60)
        )
        call.kiffID = e.id
        call.nextSteps = e.nextSteps ?? []
        return call
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
