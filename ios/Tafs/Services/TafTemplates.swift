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
    static let all: [TafTemplate] = [support, parcels, access, deploys, leads]

    static func template(_ key: String?) -> TafTemplate? {
        all.first { $0.key == key }
    }

    /// Support: answer tickets the same day; a promise of money or goods waits for the owner, and a
    /// customer's details never leave. Up to 40 replies a day, as /tafs's example Card says.
    static let support = TafTemplate(
        key: "support", name: Words.Sample.Support.name,
        keywords: ["support", "ticket", "helpdesk", "customer"],
        job: Words.Sample.Support.job,
        tools: [Words.Sample.Tool.email, Words.Sample.Tool.store],
        rules: [
            CardRule(action: "reply_ticket", label: Words.Sample.Support.Rule.replies, countPerWindow: 40, window: .day),
            CardRule(action: "promise", label: Words.Sample.Support.Rule.promises, alwaysAsk: true, nextSteps: [Words.Sample.Support.nextStep]),
            CardRule(action: "share_customer_data", label: Words.Sample.Support.Rule.customerData, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "reply_ticket", summary: Words.Sample.Support.reply7781, context: Words.Sample.Support.reply7781Why),
            ProposedAction(action: "reply_ticket", summary: Words.Sample.Support.reply7790, context: Words.Sample.Support.reply7790Why),
            ProposedAction(action: "promise", summary: Words.Sample.Support.promise7802, context: Words.Sample.Support.promise7802Why),
            ProposedAction(action: "share_customer_data", summary: Words.Sample.Support.shareHistory, context: Words.Sample.Support.shareHistoryWhy),
        ]
    )

    /// Operations: tell customers of a late parcel before they ask; a reship waits for the owner, and an
    /// address never changes from an email.
    static let parcels = TafTemplate(
        key: "parcels", name: Words.Sample.Parcels.name,
        keywords: ["parcel", "delivery", "shipping", "late", "carrier"],
        job: Words.Sample.Parcels.job,
        tools: [Words.Sample.Tool.store, Words.Sample.Tool.email],
        rules: [
            CardRule(action: "delay_message", label: Words.Sample.Parcels.Rule.delayMessages, countPerWindow: 100, window: .day),
            CardRule(action: "reship", label: Words.Sample.Parcels.Rule.reships, alwaysAsk: true, nextSteps: [Words.Sample.Parcels.nextStep]),
            CardRule(action: "change_address", label: Words.Sample.Parcels.Rule.addressChanges, never: true, outside: .refuse),
            CardRule(action: "parcel_refund", label: Words.Sample.Parcels.Rule.refunds, never: true, outside: .refuse),
        ],
        script: [
            ProposedAction(action: "delay_message", summary: Words.Sample.Parcels.tell5120, context: Words.Sample.Parcels.tell5120Why),
            ProposedAction(action: "delay_message", summary: Words.Sample.Parcels.tell5134, context: Words.Sample.Parcels.tell5134Why),
            ProposedAction(action: "reship", summary: Words.Sample.Parcels.reship5098, context: Words.Sample.Parcels.reship5098Why),
            ProposedAction(action: "change_address", summary: Words.Sample.Parcels.changeAddress, context: Words.Sample.Parcels.changeAddressWhy),
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

    /// Sales: reply to new leads within the hour and book a call; a discount waits for the owner.
    static let leads = TafTemplate(
        key: "leads", name: Words.Sample.Leads.name,
        keywords: ["lead", "sales", "quote", "prospect", "follow up"],
        job: Words.Sample.Leads.job,
        tools: [Words.Sample.Tool.email],
        rules: [
            CardRule(action: "reply_lead", label: Words.Sample.Leads.Rule.replies, countPerWindow: 50, window: .day),
            CardRule(action: "offer_discount", label: Words.Sample.Leads.Rule.discounts, alwaysAsk: true),
        ],
        script: [
            ProposedAction(action: "reply_lead", summary: Words.Sample.Leads.replyKaya, context: Words.Sample.Leads.replyKayaWhy),
            ProposedAction(action: "reply_lead", summary: Words.Sample.Leads.confirmNord, context: Words.Sample.Leads.confirmNordWhy),
            ProposedAction(action: "offer_discount", summary: Words.Sample.Leads.discountLumen, context: Words.Sample.Leads.discountLumenWhy),
        ]
    )
}
