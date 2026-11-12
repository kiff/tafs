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
    /// one the offer names, and reads exactly as that Card in force with the one offered change
    /// made: a per-call cap on the held `action` or a total moved from its figure before to its
    /// figure after, in the same unit (and window, when the offer names one); or, for a
    /// `resource` change, the held action's scope with the one offered value added. Every other
    /// term is the same (`Terms`): actions, caps, scopes, states, next steps, totals with their
    /// window, measure and per-item parameter, what happens past a limit, who holds the Card,
    /// where it applies, and when it starts and ends. A Card marked unchanged must read as in
    /// force too. Anything else isn't what was offered, and nothing is signed.
    static func matches(_ draft: KIFFClient.AgentDraft, _ offer: TafsCloud.AllowOffer, agent: String?, hold: String?, action: String) -> Bool {
        guard let agent, !agent.isEmpty, draft.agentID == agent else { return false }
        if let from = draft.fromHold, from != hold { return false }
        guard (draft.retires ?? []).isEmpty else { return false }
        let named = Set(offer.changes.map(\.mandateID))
        guard !named.isEmpty, let cards = draft.cards else { return false }
        var changed = Set<String>()
        for card in cards {
            guard let revises = card.revises, let current = card.current else { return false }
            // A draft that sets how long a hold waits, or revokes, changes more than a limit.
            guard (card.holdExpiry ?? "").isEmpty, card.revokedAt == nil else { return false }
            let inForce = Terms(current)
            let terms = Terms(card, over: current)
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

    /// Every term of a Card that decides what its taf may do, in an order of their own, as KIFF
    /// writes them in a draft's Card and in the Card in force. KIFF's lines and a grant's title
    /// are words, not terms. What a draft may leave out reads as KIFF fills it: a total's
    /// measure (attempts for a count, effects for a sum), and the Card's holders when the draft
    /// names only the same first holder.
    struct Terms: Hashable {
        struct Cap: Hashable { var quantity: String; var max: Int64; var unit: String?; var scale: Int? }
        struct Grant: Hashable {
            var action: String
            var caps: [Cap]
            /// The scope's parameter, and its values sorted; nil for a grant with no scope.
            var scopeOf: String?
            var scope: [String]
            var states: [String]
            var nextSteps: [String]
        }
        struct Total: Hashable {
            var quantity: String; var limit: Int64; var window: String; var unit: String?; var scale: Int?
            var measure: String; var per: String
        }
        var grants: [Grant]
        var totals: [Total]
        var onExceed: String?
        var holders: [String]
        var domain: String
        var environment: String
        var location: String
        var validFrom: String
        var validUntil: String

        /// The Card in force.
        init(_ current: KIFFClient.DraftCard.Terms) {
            self.init(grants: Self.grants(current.grants), totals: Self.totals(current.aggregates), onExceed: current.onExceed,
                      holders: Self.holders(current.subject, current.subjects), domain: current.domain, environment: current.environment,
                      location: current.location, validFrom: current.validFrom, validUntil: current.validUntil)
        }

        /// A draft's Card, read over the Card in force it revises: a draft that names no
        /// holders keeps the Card's, when its first holder is the same.
        init(_ card: KIFFClient.DraftCard, over current: KIFFClient.DraftCard.Terms) {
            var holders = Self.holders(card.subject, card.subjects)
            if (card.subjects ?? []).isEmpty, let subject = card.subject, subject == Self.holders(current.subject, current.subjects).first {
                holders = Self.holders(current.subject, current.subjects)
            }
            self.init(grants: Self.grants(card.grants), totals: Self.totals(card.aggregates), onExceed: card.onExceed,
                      holders: holders, domain: card.domain, environment: card.environment,
                      location: card.location, validFrom: card.validFrom, validUntil: card.validUntil)
        }

        init(grants: [Grant], totals: [Total], onExceed: String?, holders: [String], domain: String?, environment: String?,
             location: String?, validFrom: String?, validUntil: String?) {
            self.grants = grants.map { g in
                var g = g
                g.caps = g.caps.sorted { Self.capKey($0) < Self.capKey($1) }
                g.scope = g.scope.sorted()
                return g
            }.sorted { Self.grantKey($0) < Self.grantKey($1) }
            self.totals = totals.sorted { Self.totalKey($0) < Self.totalKey($1) }
            self.onExceed = onExceed
            self.holders = holders
            self.domain = (domain ?? "").lowercased()
            self.environment = (environment ?? "").lowercased()
            self.location = location ?? ""
            self.validFrom = validFrom ?? ""
            self.validUntil = validUntil ?? ""
        }

        private static func grants(_ grants: [KIFFClient.DraftCard.Grant]?) -> [Grant] {
            (grants ?? []).map { g in
                Grant(action: g.action, caps: (g.perActionMax ?? []).map { Cap(quantity: $0.quantity, max: $0.max, unit: $0.unit, scale: $0.scale) },
                      scopeOf: g.resources?.parameter, scope: g.resources?.values ?? [], states: g.entityStates ?? [], nextSteps: g.nextSteps ?? [])
            }
        }

        private static func totals(_ aggregates: [KIFFClient.DraftCard.Aggregate]?) -> [Total] {
            (aggregates ?? []).map { a in
                // Left out, KIFF counts attempts for a count and effects for a sum.
                let measure = (a.measure ?? "").isEmpty ? (a.quantity == "count" ? "attempts" : "effects") : (a.measure ?? "")
                return Total(quantity: a.quantity, limit: a.limit, window: a.window, unit: a.unit, scale: a.scale, measure: measure, per: a.per ?? "")
            }
        }

        /// Every holder, the first being the subject.
        private static func holders(_ subject: String?, _ subjects: [String]?) -> [String] {
            if let subjects, !subjects.isEmpty { return subjects }
            return subject.map { [$0] } ?? []
        }

        private static func capKey(_ c: Cap) -> String { "\(c.quantity)|\(c.unit ?? "")|\(c.max)|\(c.scale.map(String.init) ?? "")" }
        private static func grantKey(_ g: Grant) -> String {
            "\(g.action)|\(g.caps.map(capKey).joined(separator: ";"))|\(g.scopeOf ?? "")|\(g.scope.joined(separator: ","))|\(g.states.joined(separator: ","))|\(g.nextSteps.joined(separator: ","))"
        }
        private static func totalKey(_ t: Total) -> String {
            "\(t.quantity)|\(t.window)|\(t.per)|\(t.measure)|\(t.unit ?? "")|\(t.limit)|\(t.scale.map(String.init) ?? "")"
        }

        /// These terms with their grants or totals replaced, put back in order.
        func with(grants: [Grant]? = nil, totals: [Total]? = nil) -> Terms {
            Terms(grants: grants ?? self.grants, totals: totals ?? self.totals, onExceed: onExceed, holders: holders, domain: domain,
                  environment: environment, location: location, validFrom: validFrom, validUntil: validUntil)
        }
    }

    /// Every way `change` can apply to `terms`: one limit of its kind, at its figure before and
    /// in its unit, moved to its figure after; or one value added to the scope of the held
    /// call's `action`. A per-call cap is the held `action`'s. A kind the contract doesn't name
    /// applies in no way.
    static func applied(_ terms: Terms, _ change: TafsCloud.AllowOffer.Change, action: String) -> [Terms] {
        switch change.kind {
        case "per_action":
            guard let before = change.before, let after = change.after else { return [] }
            var all: [Terms] = []
            for (g, grant) in terms.grants.enumerated() where grant.action == action {
                for (c, cap) in grant.caps.enumerated() where cap.max == before && cap.unit == change.unit {
                    var grants = terms.grants
                    grants[g].caps[c].max = after
                    all.append(terms.with(grants: grants))
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
                all.append(terms.with(totals: totals))
            }
            return all
        case "resource":
            // Exactly the values in force plus this one, on the held action's scope for this parameter.
            guard let parameter = change.parameter, !parameter.isEmpty, let value = change.value, !value.isEmpty else { return [] }
            var all: [Terms] = []
            for (g, grant) in terms.grants.enumerated() where grant.action == action && grant.scopeOf == parameter && !grant.scope.contains(value) {
                var grants = terms.grants
                grants[g].scope.append(value)
                all.append(terms.with(grants: grants))
            }
            return all
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
