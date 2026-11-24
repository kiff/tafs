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
        // Never a sign-in fill: a Card that let fills go on their own would allow them without a release.
        guard call.needsRelease != true else { return false }
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
    /// force too. When KIFF names the Card in force (`current.id`), it must be the Card revised.
    /// Anything else isn't what was offered, and nothing is signed.
    static func matches(_ draft: KIFFClient.AgentDraft, _ offer: TafsCloud.AllowOffer, agent: String?, hold: String?, action: String) -> Bool {
        guard let agent, !agent.isEmpty, draft.agentID == agent else { return false }
        if let from = draft.fromHold, from != hold { return false }
        guard (draft.retires ?? []).isEmpty else { return false }
        let named = Set(offer.changes.map(\.mandateID))
        guard !named.isEmpty, let cards = draft.cards else { return false }
        var changed = Set<String>()
        for card in cards {
            guard let revises = card.revises, let current = card.current else { return false }
            // The Card in force the draft shows must be the one it revises, when KIFF names it: else the comparison
            // below could be against another Card's terms.
            if let id = current.id, id != revises { return false }
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
        /// When it starts and ends, as the instants KIFF's times name (`instant`): "" for none.
        var validFrom: String
        var validUntil: String

        /// The Card in force.
        init(_ current: KIFFClient.DraftCard.Terms) {
            self.init(grants: Self.grants(current.grants), totals: Self.totals(current.aggregates), onExceed: current.onExceed,
                      holders: Self.holders(current.subject, current.subjects), domain: current.domain, environment: current.environment,
                      location: current.location, validFrom: Self.instant(current.validFrom), validUntil: Self.instant(current.validUntil))
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
                      location: card.location, validFrom: Self.instant(card.validFrom), validUntil: Self.instant(card.validUntil))
        }

        init(grants: [Grant], totals: [Total], onExceed: String?, holders: [String], domain: String?, environment: String?,
             location: String?, validFrom: String, validUntil: String) {
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
            // Already read as instants by the two initializers above: never read twice.
            self.validFrom = validFrom
            self.validUntil = validUntil
        }

        /// A time as KIFF writes it, as the instant it names, so the same moment written with
        /// another offset or precision is the same term. None is "". Every other text is
        /// parsed: one that can't be read as a time is kept as written behind a mark no
        /// instant has, so it equals only the same unreadable text, and never a time.
        static func instant(_ time: String?) -> String {
            guard let time, !time.isEmpty else { return "" }
            let precise = ISO8601DateFormatter()
            precise.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            guard let date = precise.date(from: time) ?? ISO8601DateFormatter().date(from: time) else { return "?" + time }
            return "@" + String(Int64((date.timeIntervalSince1970 * 1000).rounded()))
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
        let (draft, raw) = try await kiff.draftRaw(id: offer.draft)
        // Signed only when it's what was offered and every key of its Cards is one this check reads.
        return (answer, draft, keysKnown(raw) && matches(draft, offer, agent: call.agent, hold: call.kiffID, action: call.proposed.action))
    }

    /// The keys each object of a draft's Cards may carry: those `matches` compares, and the few it reads as words or knows
    /// to be unchanged. The shared vector (vectors/agent-draft-keys.json) lists the same, and its cases are this app's tests,
    /// Android's and tafs cloud's.
    static let keys: [String: Set<String>] = [
        "card": ["id", "revises", "current", "unchanged", "grants", "aggregates", "on_exceed", "lines", "subject", "subjects", "domain",
                 "environment", "location", "valid_from", "valid_until", "hold_expiry", "revoked_at"],
        "current": ["id", "grants", "aggregates", "on_exceed", "lines", "subject", "subjects", "domain", "environment", "location",
                    "valid_from", "valid_until", "active", "domain_id", "hold_expiry_seconds"],
        "grant": ["action", "title", "per_action_max", "resources", "entity_states", "next_steps"],
        "cap": ["quantity", "quantity_of", "max", "unit", "scale"],
        "total": ["quantity", "quantity_of", "limit", "window", "unit", "scale", "measure", "per"],
        "resources": ["parameter", "values"],
        "quantity_of": ["kind", "parameter"],
    ]

    /// "Allow this from now on", its second half: every key of each of the draft's Cards, as KIFF sent them, is one this
    /// check reads (`keys`), so a term KIFF adds later is never signed unseen. A key whose value is null sets nothing. A
    /// Card's `id`, when sent, is the Card it revises. A `quantity_of` says what its `quantity` says: "sum(amount_cents)" is
    /// {kind: sum, parameter: amount_cents}, "count" is {kind: count}. The draft's own keys aren't a Card's.
    static func keysKnown(_ raw: Data) -> Bool {
        guard let root = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] else { return false }
        func absent(_ v: Any?) -> Bool { v == nil || v is NSNull }
        func only(_ v: Any?, _ kind: String) -> [String: Any]? {
            guard let o = v as? [String: Any], let allowed = keys[kind] else { return nil }
            for (k, value) in o where !(value is NSNull) && !allowed.contains(k) { return nil }
            return o
        }
        func list(_ v: Any?, _ each: (Any) -> Bool) -> Bool {
            if absent(v) { return true }
            guard let all = v as? [Any] else { return false }
            return all.allSatisfy(each)
        }
        func quantityOf(_ o: [String: Any]) -> Bool {
            if absent(o["quantity_of"]) { return true }
            guard let of = only(o["quantity_of"], "quantity_of"), let quantity = o["quantity"] as? String,
                  let kind = of["kind"] as? String else { return false }
            if !absent(of["parameter"]), !(of["parameter"] is String) { return false }
            let parameter = of["parameter"] as? String
            let pattern = try? NSRegularExpression(pattern: #"^([A-Za-z0-9_]+)\((.+)\)$"#)
            let whole = NSRange(quantity.startIndex..., in: quantity)
            if let m = pattern?.firstMatch(in: quantity, range: whole), let k = Range(m.range(at: 1), in: quantity), let p = Range(m.range(at: 2), in: quantity) {
                return kind == String(quantity[k]) && parameter == String(quantity[p])
            }
            return kind == quantity && parameter == nil
        }
        func cap(_ v: Any) -> Bool { only(v, "cap").map(quantityOf) ?? false }
        func total(_ v: Any) -> Bool { only(v, "total").map(quantityOf) ?? false }
        func grant(_ v: Any) -> Bool {
            guard let g = only(v, "grant") else { return false }
            return list(g["per_action_max"], cap) && (absent(g["resources"]) || only(g["resources"], "resources") != nil)
        }
        func terms(_ t: [String: Any]) -> Bool { list(t["grants"], grant) && list(t["aggregates"], total) }
        return list(root["cards"]) { v in
            guard let card = only(v, "card") else { return false }
            if !absent(card["id"]) {
                guard let id = card["id"] as? String, id == card["revises"] as? String else { return false }
            }
            guard terms(card) else { return false }
            if absent(card["current"]) { return true }
            guard let current = only(card["current"], "current") else { return false }
            return terms(current)
        }
    }
}
