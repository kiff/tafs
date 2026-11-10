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

    static func template(_ key: String?) -> TafTemplate? {
        all.first { $0.key == key }
    }

    /// Support: settle refund and credit requests within policy.
    static let refunds = TafTemplate(
        key: "refunds", name: Words.Sample.Refunds.name,
        keywords: ["refund", "credit", "return", "goodwill"],
        job: Words.Sample.Refunds.job,
        tools: [Words.Sample.Tool.payments, Words.Sample.Tool.store, Words.Sample.Tool.email],
        rules: [
            CardRule(action: "refund", label: Words.Sample.Refunds.Rule.refunds, perAction: .eur(150), perWindow: .eur(2_000), window: .day, nextSteps: [Words.Sample.Refunds.nextStep]),
            CardRule(action: "credit", label: Words.Sample.Refunds.Rule.credits, perAction: .eur(100), perWindow: .eur(1_000), window: .day),
            CardRule(action: "payout", label: Words.Sample.Refunds.Rule.payouts, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "refund", summary: Words.Sample.Refunds.refund4471, amount: .eur(40), context: Words.Sample.Refunds.refund4471Why),
            ProposedAction(action: "credit", summary: Words.Sample.Refunds.credit2290, amount: .eur(25), context: Words.Sample.Refunds.credit2290Why),
            ProposedAction(action: "refund", summary: Words.Sample.Refunds.refund4502, amount: .eur(480), context: Words.Sample.Refunds.refund4502Why),
            ProposedAction(action: "payout", summary: Words.Sample.Refunds.payout, amount: .eur(900), context: Words.Sample.Refunds.payoutWhy),
        ]
    )

    /// Accounts payable: match, approve and pay routine vendor bills.
    static let billPay = TafTemplate(
        key: "billpay", name: Words.Sample.BillPay.name,
        keywords: ["bill", "vendor", "payable", "pay invoice", "supplier"],
        job: Words.Sample.BillPay.job,
        tools: [Words.Sample.Tool.accounting, Words.Sample.Tool.email],
        rules: [
            CardRule(action: "pay_bill", label: Words.Sample.BillPay.Rule.bills, perAction: .eur(5_000), perWindow: .eur(25_000), window: .week, nextSteps: [Words.Sample.BillPay.nextStep]),
            CardRule(action: "wire", label: Words.Sample.BillPay.Rule.wires, alwaysAsk: true),
            CardRule(action: "bank_details", label: Words.Sample.BillPay.Rule.bankDetails, never: true, outside: .refuse),
            CardRule(action: "new_payee", label: Words.Sample.BillPay.Rule.newPayees, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "pay_bill", summary: Words.Sample.BillPay.payNorthwind, amount: .eur(1_240), context: Words.Sample.BillPay.payNorthwindWhy),
            ProposedAction(action: "pay_bill", summary: Words.Sample.BillPay.payAtlas, amount: .eur(8_400), context: Words.Sample.BillPay.payAtlasWhy),
            ProposedAction(action: "bank_details", summary: Words.Sample.BillPay.updateBank, context: Words.Sample.BillPay.updateBankWhy),
        ]
    )

    /// IT: joiners, movers and leavers across identity, workspace and code.
    static let access = TafTemplate(
        key: "access", name: Words.Sample.Access.name,
        keywords: ["access", "onboard", "offboard", "leaver", "joiner", "provision", "okta"],
        job: Words.Sample.Access.job,
        tools: [Words.Sample.Tool.signIn, Words.Sample.Tool.code],
        rules: [
            CardRule(action: "grant_role", label: Words.Sample.Access.Rule.approvedRoles, countPerWindow: 40, window: .day),
            CardRule(action: "deactivate", label: Words.Sample.Access.Rule.deactivations, countPerWindow: 20, window: .day),
            CardRule(action: "elevate", label: Words.Sample.Access.Rule.elevated, alwaysAsk: true),
            CardRule(action: "grant_admin", label: Words.Sample.Access.Rule.adminRoles, never: true, outside: .refuse),
            CardRule(action: "delete_user", label: Words.Sample.Access.Rule.deletingUsers, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "grant_role", summary: Words.Sample.Access.salesRole, context: Words.Sample.Access.salesRoleWhy),
            ProposedAction(action: "deactivate", summary: Words.Sample.Access.deactivateTom, context: Words.Sample.Access.deactivateTomWhy),
            ProposedAction(action: "elevate", summary: Words.Sample.Access.databaseLee, context: Words.Sample.Access.databaseLeeWhy),
            ProposedAction(action: "grant_admin", summary: Words.Sample.Access.adminLee, context: Words.Sample.Access.adminLeeWhy),
        ]
    )

    /// Engineering: ship fixes and roll back, never destroy.
    static let deploys = TafTemplate(
        key: "deploys", name: Words.Sample.Deploys.name,
        keywords: ["deploy", "production", "release", "migration", "rollback"],
        job: Words.Sample.Deploys.job,
        tools: [Words.Sample.Tool.code, Words.Sample.Tool.hosting, Words.Sample.Tool.network],
        rules: [
            CardRule(action: "deploy", label: Words.Sample.Deploys.Rule.deploys, countPerWindow: 10, window: .day),
            CardRule(action: "rollback", label: Words.Sample.Deploys.Rule.rollbacks, countPerWindow: 20, window: .day),
            CardRule(action: "migration", label: Words.Sample.Deploys.Rule.migrations, alwaysAsk: true, nextSteps: [Words.Sample.Deploys.nextStep]),
            CardRule(action: "destroy_data", label: Words.Sample.Deploys.Rule.destroyData, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "deploy", summary: Words.Sample.Deploys.deployApi, context: Words.Sample.Deploys.deployApiWhy),
            ProposedAction(action: "migration", summary: Words.Sample.Deploys.migration, context: Words.Sample.Deploys.migrationWhy),
            ProposedAction(action: "destroy_data", summary: "DROP TABLE sessions_old", context: Words.Sample.Deploys.dropTableWhy),
        ]
    )

    /// Payments: answer disputes with evidence before the deadline.
    static let chargebacks = TafTemplate(
        key: "chargebacks", name: Words.Sample.Chargebacks.name,
        keywords: ["chargeback", "dispute"],
        job: Words.Sample.Chargebacks.job,
        tools: [Words.Sample.Tool.payments],
        rules: [
            CardRule(action: "submit_evidence", label: Words.Sample.Chargebacks.Rule.evidence, perAction: .eur(500), countPerWindow: 50, window: .day),
            CardRule(action: "accept_dispute", label: Words.Sample.Chargebacks.Rule.acceptingLoss, perAction: .eur(100), perWindow: .eur(500), window: .week),
        ],
        script: [
            ProposedAction(action: "submit_evidence", summary: Words.Sample.Chargebacks.answer3318, amount: .eur(120), context: Words.Sample.Chargebacks.answer3318Why),
            ProposedAction(action: "accept_dispute", summary: Words.Sample.Chargebacks.accept3342, amount: .eur(35), context: Words.Sample.Chargebacks.accept3342Why),
            ProposedAction(action: "submit_evidence", summary: Words.Sample.Chargebacks.answer3360, amount: .eur(1_800), context: Words.Sample.Chargebacks.answer3360Why),
        ]
    )
}
