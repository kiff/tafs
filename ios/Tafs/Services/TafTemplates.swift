import Foundation

/// A job tafs knows how to draft: its tools, its Card, and the actions the demo
/// plays through. Five jobs, each consequential and painful today, chosen from
/// research on where companies want agents to act but are afraid to let them.
/// Not a catalogue.
struct TafTemplate: Identifiable {
    var id: String { key }
    var key: String
    var name: String
    var keywords: [String]
    var job: String
    var tools: [String]
    var rules: [CardRule]
    /// Actions the demo proposes, in order, to show the Card at work.
    var script: [ProposedAction]
}

struct TafDraft: Hashable {
    var name: String
    var job: String
    var tools: [String]
    var rules: [CardRule]
    var templateKey: String?
}

enum TafTemplates {
    static let all: [TafTemplate] = [refunds, billPay, access, deploys, chargebacks]

    static func draft(from job: String) -> TafDraft {
        let words = job.lowercased()
        let match = all.first { t in t.keywords.contains { words.contains($0) } }
        guard let t = match else {
            return TafDraft(name: "New taf", job: job, tools: [], rules: [], templateKey: nil)
        }
        return TafDraft(name: t.name, job: job, tools: t.tools, rules: t.rules, templateKey: t.key)
    }

    static func template(_ key: String?) -> TafTemplate? {
        all.first { $0.key == key }
    }

    /// Support: settle refund and credit requests within policy.
    static let refunds = TafTemplate(
        key: "refunds", name: "Refunds",
        keywords: ["refund", "credit", "return", "goodwill"],
        job: "Settle refund and credit requests from the support queue. Never refund more than the original charge, never twice, never on a disputed charge.",
        tools: ["Payments", "Store", "Email"],
        rules: [
            CardRule(action: "refund", label: "Refunds", perAction: .usd(150), perWindow: .usd(2_000), window: .day, nextSteps: ["Return first"]),
            CardRule(action: "credit", label: "Credits", perAction: .usd(100), perWindow: .usd(1_000), window: .day),
            CardRule(action: "payout", label: "Payouts", never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "refund", summary: "Refund order #4471", amount: .usd(40), context: "Arrived 6 days late"),
            ProposedAction(action: "credit", summary: "Credit on account #2290", amount: .usd(25), context: "Billing error last month"),
            ProposedAction(action: "refund", summary: "Refund order #4502", amount: .usd(480), context: "Damaged on arrival, photo attached"),
            ProposedAction(action: "payout", summary: "Pay out to a new bank account", amount: .usd(900), context: "Customer asked for cash instead"),
        ]
    )

    /// Accounts payable: match, approve and pay routine vendor bills.
    static let billPay = TafTemplate(
        key: "billpay", name: "Bill pay",
        keywords: ["bill", "vendor", "payable", "pay invoice", "supplier"],
        job: "Pay routine vendor bills that match a purchase order. Existing vendors only. Never change bank details or add a payee.",
        tools: ["Accounting", "Email"],
        rules: [
            CardRule(action: "pay_bill", label: "Bills", perAction: .usd(3_500), perWindow: .usd(3_500), nextSteps: ["Pay half now"]),
            CardRule(action: "wire", label: "Wires", alwaysAsk: true),
            CardRule(action: "bank_details", label: "Bank detail changes", never: true, outside: .refuse),
            CardRule(action: "new_payee", label: "New payees", never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "pay_bill", summary: "Pay Northwind Packaging", amount: .usd(1_240), context: "Matches PO-3381"),
            ProposedAction(action: "pay_bill", summary: "Pay Atlas Freight", amount: .usd(8_400), context: "Matches PO-3390, quarterly"),
            ProposedAction(action: "bank_details", summary: "Update Atlas Freight's bank account", context: "Email from a new address asked for it"),
        ]
    )

    /// IT: joiners, movers and leavers across identity, workspace and code.
    static let access = TafTemplate(
        key: "access", name: "Access",
        keywords: ["access", "onboard", "offboard", "leaver", "joiner", "provision", "okta"],
        job: "Give new joiners their apps and remove leavers the day they go. Roles from the approved list only. Deactivate, never delete.",
        tools: ["Sign-in", "Code"],
        rules: [
            CardRule(action: "grant_role", label: "Approved roles", countPerWindow: 40, window: .day),
            CardRule(action: "deactivate", label: "Deactivations", countPerWindow: 20, window: .day),
            CardRule(action: "elevate", label: "Elevated access", alwaysAsk: true),
            CardRule(action: "grant_admin", label: "Admin roles", never: true, outside: .refuse),
            CardRule(action: "delete_user", label: "Deleting users", never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "grant_role", summary: "Give Ana the Sales role", context: "Starts Monday, per HR"),
            ProposedAction(action: "deactivate", summary: "Deactivate Tom's accounts", context: "Left Friday, per HR"),
            ProposedAction(action: "elevate", summary: "Production database access for Lee, 8 hours", context: "On call, incident open"),
            ProposedAction(action: "grant_admin", summary: "Make Lee an admin of the code", context: "Asked in the team chat"),
        ]
    )

    /// Engineering: ship fixes and roll back, never destroy.
    static let deploys = TafTemplate(
        key: "deploys", name: "Deploys",
        keywords: ["deploy", "production", "release", "migration", "rollback"],
        job: "Ship merged fixes to production when CI is green, and roll back when errors spike. Never drop or delete production data.",
        tools: ["Code", "Hosting", "Network"],
        rules: [
            CardRule(action: "deploy", label: "Production deploys", countPerWindow: 10, window: .day),
            CardRule(action: "rollback", label: "Rollbacks", countPerWindow: 20, window: .day),
            CardRule(action: "migration", label: "Schema migrations", alwaysAsk: true, nextSteps: ["Run it tonight"]),
            CardRule(action: "destroy_data", label: "Dropping or deleting data", never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "deploy", summary: "Deploy api v2.14.1", context: "PR #812 merged, CI green"),
            ProposedAction(action: "migration", summary: "Run migration 0042_orders_index", context: "Adds an index, 3M rows"),
            ProposedAction(action: "destroy_data", summary: "DROP TABLE sessions_old", context: "Looks unused"),
        ]
    )

    /// Payments: answer disputes with evidence before the deadline.
    static let chargebacks = TafTemplate(
        key: "chargebacks", name: "Chargebacks",
        keywords: ["chargeback", "dispute"],
        job: "Answer card disputes with the order's evidence before the deadline. Accept small ones that can't be won. Share nothing beyond the template.",
        tools: ["Payments"],
        rules: [
            CardRule(action: "submit_evidence", label: "Evidence", perAction: .usd(500), countPerWindow: 50, window: .day),
            CardRule(action: "accept_dispute", label: "Accepting a loss", perAction: .usd(70), perWindow: .usd(70)),
        ],
        script: [
            ProposedAction(action: "submit_evidence", summary: "Answer dispute on #3318", amount: .usd(120), context: "Delivered, signed for"),
            ProposedAction(action: "accept_dispute", summary: "Accept dispute on #3342", amount: .usd(35), context: "No proof of delivery"),
            ProposedAction(action: "submit_evidence", summary: "Answer dispute on #3360", amount: .usd(1_800), context: "Due in 2 days"),
        ]
    )
}
