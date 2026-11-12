import Foundation

/// "Allow this from now on" on a held call (docs/allow-from-now-on.md). Whether to offer it is
/// the app's own rule, from the held call KIFF sent, with no request. What it offers is tafs
/// cloud's. What's signed is KIFF's draft, which the phone reads itself and signs only when it
/// is what was offered.
enum HoldAllow {
    /// The breaches a Card change can lift. A "never" is refused, not held, so it never shows here.
    static let liftable: Set<String> = ["per_action_exceeded", "limit_reached", "resource_not_on_card"]

    /// A hold (not Coach's install; a currency mismatch is never listed as one) whose every
    /// breach a Card change can lift.
    static func allowable(_ call: HeldCall) -> Bool {
        guard call.install == nil, call.kiffID != nil, let outcomes = call.breachOutcomes, !outcomes.isEmpty else { return false }
        return outcomes.allSatisfy(liftable.contains)
    }

    /// The device signs what the person saw. KIFF's draft is for `agent` (and, when KIFF says,
    /// made from held call `hold`). It retires no Card and adds none. Each Card it changes is
    /// one the offer names, and reads exactly as that Card in force with the offered limit
    /// moved from its figure before to its figure after, in the same unit (a per-call cap on the
    /// held `action`; a total in the offer's window when it names one). Every other action,
    /// cap, total, window and what happens past a limit is the same. A `resource` change has
    /// no figure the phone can read, so its Card reads as in force. A Card marked unchanged
    /// must read as in force too. Anything else isn't what was offered, and nothing is signed.
    static func matches(_ draft: KIFFClient.AgentDraft, _ offer: TafsCloud.AllowOffer, agent: String?, hold: String?, action: String) -> Bool {
        guard let agent, !agent.isEmpty, draft.agentID == agent else { return false }
        if let from = draft.fromHold, from != hold { return false }
        guard (draft.retires ?? []).isEmpty else { return false }
        let named = Set(offer.changes.map(\.mandateID))
        guard !named.isEmpty, let cards = draft.cards else { return false }
        var changed = Set<String>()
        for card in cards {
            guard let revises = card.revises, let current = card.current else { return false }
            let terms = Terms(card.grants, card.aggregates, card.onExceed)
            let inForce = Terms(current.grants, current.aggregates, current.onExceed)
            if card.unchanged == true {
                guard terms == inForce else { return false }
                continue
            }
            guard named.contains(revises), !changed.contains(revises) else { return false }
            var expected: Set<Terms> = [inForce]
            for change in offer.changes where change.mandateID == revises {
                expected = Set(expected.flatMap { applied($0, change, action: action) })
            }
            guard expected.contains(terms) else { return false }
            changed.insert(revises)
        }
        return changed == named
    }

    /// A Card's readable terms, in an order of their own. KIFF's lines and a grant's title are
    /// words, not terms.
    struct Terms: Hashable {
        struct Cap: Hashable { var quantity: String; var max: Int64; var unit: String?; var scale: Int? }
        struct Grant: Hashable { var action: String; var caps: [Cap] }
        struct Total: Hashable { var quantity: String; var limit: Int64; var window: String; var unit: String?; var scale: Int? }
        var grants: [Grant]
        var totals: [Total]
        var onExceed: String?

        init(_ grants: [KIFFClient.DraftCard.Grant]?, _ aggregates: [KIFFClient.DraftCard.Aggregate]?, _ onExceed: String?) {
            self.init(grants: (grants ?? []).map { g in
                Grant(action: g.action, caps: (g.perActionMax ?? []).map { Cap(quantity: $0.quantity, max: $0.max, unit: $0.unit, scale: $0.scale) })
            }, totals: (aggregates ?? []).map { Total(quantity: $0.quantity, limit: $0.limit, window: $0.window, unit: $0.unit, scale: $0.scale) }, onExceed: onExceed)
        }

        init(grants: [Grant], totals: [Total], onExceed: String?) {
            self.grants = grants.map { Grant(action: $0.action, caps: $0.caps.sorted { Self.capKey($0) < Self.capKey($1) }) }
                .sorted { ($0.action, $0.caps.map(Self.capKey).joined(separator: ";")) < ($1.action, $1.caps.map(Self.capKey).joined(separator: ";")) }
            self.totals = totals.sorted { Self.totalKey($0) < Self.totalKey($1) }
            self.onExceed = onExceed
        }

        private static func capKey(_ c: Cap) -> String { "\(c.quantity)|\(c.unit ?? "")|\(c.max)|\(c.scale.map(String.init) ?? "")" }
        private static func totalKey(_ t: Total) -> String { "\(t.quantity)|\(t.window)|\(t.unit ?? "")|\(t.limit)|\(t.scale.map(String.init) ?? "")" }
    }

    /// Every way `change` can apply to `terms`: one limit of its kind, at its figure before and
    /// in its unit, moved to its figure after. A per-call cap is the held call's `action`'s. A
    /// kind the contract doesn't name applies in no way.
    static func applied(_ terms: Terms, _ change: TafsCloud.AllowOffer.Change, action: String) -> [Terms] {
        switch change.kind {
        case "per_action":
            guard let before = change.before, let after = change.after else { return [] }
            var all: [Terms] = []
            for (g, grant) in terms.grants.enumerated() where grant.action == action {
                for (c, cap) in grant.caps.enumerated() where cap.max == before && cap.unit == change.unit {
                    var grants = terms.grants
                    grants[g].caps[c].max = after
                    all.append(Terms(grants: grants, totals: terms.totals, onExceed: terms.onExceed))
                }
            }
            return all
        case "total", "count":
            guard let before = change.before, let after = change.after else { return [] }
            var all: [Terms] = []
            for (t, total) in terms.totals.enumerated()
            where total.limit == before && total.unit == change.unit && (change.window == nil || change.window == total.window) {
                var totals = terms.totals
                totals[t].limit = after
                all.append(Terms(grants: terms.grants, totals: totals, onExceed: terms.onExceed))
            }
            return all
        case "resource":
            // A value added to a scope: nothing the phone can read differs.
            return [terms]
        default:
            return []
        }
    }

    /// tafs cloud's offer for a held call, then KIFF's draft, read by this phone, and whether
    /// the draft is what was offered. A `none` reads no draft.
    static func read(cloud: TafsCloud, kiff: KIFFClient, taf: String, call: HeldCall, round: Bool) async throws
        -> (answer: TafsCloud.AllowAnswer, draft: KIFFClient.AgentDraft?, matches: Bool) {
        let answer = try await cloud.allowOffer(taf: taf, exception: call.kiffID ?? "", round: round)
        guard let offer = answer.offer, !offer.draft.isEmpty else { return (answer, nil, false) }
        let draft = try await kiff.draft(id: offer.draft)
        return (answer, draft, matches(draft, offer, agent: call.agent, hold: call.kiffID, action: call.proposed.action))
    }
}
