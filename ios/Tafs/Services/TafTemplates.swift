import Foundation

/// A job tafs knows how to draft: its tools, its Card, and the actions the demo
/// plays through. Five jobs, the ones tafs is sold for: refunds, card disputes,
/// overdue invoices, new leads and "where is my order", in euros. Not a catalogue.
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
    static let all: [TafTemplate] = [refunds, chargebacks, invoices, leads, orders]

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
            // Second, so the sample's Needs you opens on a refund over the Card's limit.
            ProposedAction(action: "refund", summary: Words.Sample.Refunds.refund4502, amount: .eur(480), context: Words.Sample.Refunds.refund4502Why),
            ProposedAction(action: "credit", summary: Words.Sample.Refunds.credit2290, amount: .eur(25), context: Words.Sample.Refunds.credit2290Why),
            ProposedAction(action: "payout", summary: Words.Sample.Refunds.payout, amount: .eur(900), context: Words.Sample.Refunds.payoutWhy),
        ]
    )

    /// Finance: chase overdue invoices, never write one off.
    static let invoices = TafTemplate(
        key: "invoices", name: Words.Sample.Invoices.name,
        keywords: ["invoice", "overdue", "reminder", "collections", "dunning"],
        job: Words.Sample.Invoices.job,
        tools: [Words.Sample.Tool.accounting, Words.Sample.Tool.email],
        rules: [
            CardRule(action: "send_reminder", label: Words.Sample.Invoices.Rule.reminders, countPerWindow: 30, window: .day),
            CardRule(action: "payment_plan", label: Words.Sample.Invoices.Rule.paymentPlans, alwaysAsk: true, nextSteps: [Words.Sample.Invoices.nextStep]),
            CardRule(action: "write_off", label: Words.Sample.Invoices.Rule.writeOffs, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "send_reminder", summary: Words.Sample.Invoices.remind1187, context: Words.Sample.Invoices.remind1187Why),
            ProposedAction(action: "payment_plan", summary: Words.Sample.Invoices.plan1164, amount: .eur(4_500), context: Words.Sample.Invoices.plan1164Why),
            ProposedAction(action: "send_reminder", summary: Words.Sample.Invoices.remind1192, context: Words.Sample.Invoices.remind1192Why),
            ProposedAction(action: "write_off", summary: Words.Sample.Invoices.writeOff1102, amount: .eur(320), context: Words.Sample.Invoices.writeOff1102Why),
        ]
    )

    /// Sales: answer new leads in minutes and book the call, never a discount.
    static let leads = TafTemplate(
        key: "leads", name: Words.Sample.Leads.name,
        keywords: ["lead", "prospect", "demo", "inbound", "sales"],
        job: Words.Sample.Leads.job,
        tools: [Words.Sample.Tool.crm, Words.Sample.Tool.email, Words.Sample.Tool.calendar],
        rules: [
            CardRule(action: "reply_lead", label: Words.Sample.Leads.Rule.replies, countPerWindow: 50, window: .day),
            CardRule(action: "book_call", label: Words.Sample.Leads.Rule.calls, countPerWindow: 10, window: .day),
            CardRule(action: "discount", label: Words.Sample.Leads.Rule.discounts, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "reply_lead", summary: Words.Sample.Leads.replyLena, context: Words.Sample.Leads.replyLenaWhy),
            ProposedAction(action: "book_call", summary: Words.Sample.Leads.bookMarco, context: Words.Sample.Leads.bookMarcoWhy),
            ProposedAction(action: "discount", summary: Words.Sample.Leads.discountMarco, context: Words.Sample.Leads.discountMarcoWhy),
        ]
    )

    /// Support: answer "where is my order", reship a lost parcel, never redirect one.
    static let orders = TafTemplate(
        key: "orders", name: Words.Sample.Orders.name,
        keywords: ["order", "tracking", "where is my order", "wismo", "parcel", "shipping"],
        job: Words.Sample.Orders.job,
        tools: [Words.Sample.Tool.store, Words.Sample.Tool.shipping, Words.Sample.Tool.email],
        rules: [
            CardRule(action: "answer", label: Words.Sample.Orders.Rule.answers, countPerWindow: 100, window: .day),
            CardRule(action: "reship", label: Words.Sample.Orders.Rule.reships, perAction: .eur(60), perWindow: .eur(300), window: .day, nextSteps: [Words.Sample.Orders.nextStep]),
            CardRule(action: "change_address", label: Words.Sample.Orders.Rule.addressChanges, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "answer", summary: Words.Sample.Orders.answer5108, context: Words.Sample.Orders.answer5108Why),
            ProposedAction(action: "reship", summary: Words.Sample.Orders.reship5091, amount: .eur(38), context: Words.Sample.Orders.reship5091Why),
            ProposedAction(action: "reship", summary: Words.Sample.Orders.reship5120, amount: .eur(145), context: Words.Sample.Orders.reship5120Why),
            ProposedAction(action: "change_address", summary: Words.Sample.Orders.address5133, context: Words.Sample.Orders.address5133Why),
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
