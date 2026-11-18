import Foundation
@testable import Tafs

/// A taf that moves money, for the tests that hold a call over a limit: the Card the Refunds sample
/// had (€150 a refund, €2,000 a day; credits €100, €1,000 a day; payouts never). The app's samples
/// no longer move money (Support replies, Lead follow-up, Late parcels), so these tests keep their own.
enum TestTemplates {
    static let refunds = TafTemplate(
        key: "refunds", name: "Refunds",
        keywords: ["refund"],
        job: "Settle refund and credit requests within policy.",
        tools: ["Payments", "Store", "Email"],
        rules: [
            CardRule(action: "refund", label: "Refunds", perAction: .eur(150), perWindow: .eur(2_000), window: .day),
            CardRule(action: "credit", label: "Credits", perAction: .eur(100), perWindow: .eur(1_000), window: .day),
            CardRule(action: "payout", label: "Payouts", never: true, outside: .refuse),
        ],
        script: []
    )
}
