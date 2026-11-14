import Foundation

/// The sample's brief (the owner's validated "Money" mockups, Missions design v2.1): a morning brief
/// over the sample's own tafs, so screenshots and a signed-out walk show it. tafs cloud sends the real
/// one, in the person's language; these lines stand in for its words, never as the app's own strings.
extension TafsCloud.Brief {
    static let sampleMoney = "money"
    static let sampleClients = "clients"

    /// The sample's labels, over its tafs by template, so a translated sample keeps them.
    static let sampleLabels: [(label: TafsCloud.Brief.Label, tafs: [String])] = [
        (Label(id: sampleMoney, name: "Money", color: "money"), ["billpay", "refunds", "chargebacks"]),
        (Label(id: sampleClients, name: "Clients", color: "customers"), ["refunds", "access"]),
        (Label(id: "ops", name: "Ops", color: "operations"), ["deploys"]),
    ]

    /// The sample's brief, filtered to `label` as tafs cloud filters it: a label keeps its own
    /// decisions and cases, and the rest line counts only its own.
    /// With `urgent` (`-briefUrgent YES`), the owner's urgent mockup: a payment that expires before the
    /// next brief leads the decisions, with no Later.
    static func sample(label: String? = nil, urgent: Bool = false) -> TafsCloud.Brief {
        var b = TafsCloud.Brief()
        b.at = "2026-11-14T08:00:00+01:00"
        b.nextAt = "2026-11-14T18:00:00+01:00"
        b.words = {
            var w = Words()
            w.tab = "Your brief"; w.title = "Your brief"
            w.when = label == sampleMoney ? "Money · Morning brief · 08:00 · next at 18:00" : "Morning brief · 08:00 · next at 18:00"
            w.decisions = urgent ? "Decisions · 3" : "Decisions · 2"; w.urgent = "Urgent"; w.done = "Done since last evening"; w.nextMove = "Next move"
            w.recommends = "Coach recommends"; w.meanwhile = "Meanwhile:"; w.evidence = "Evidence"; w.all = "All"
            return w
        }()
        b.labels = [
            Label(id: sampleMoney, name: "Money", color: "money", count: 5, waiting: 2),
            Label(id: sampleClients, name: "Clients", color: "customers", count: 2),
            Label(id: "support", name: "Support", color: "operations", count: 1),
        ]
        var pay = Decision()
        pay.id = "d-pay"; pay.question = "Pay 3 supplier bills due Friday, €1,240?"
        pay.answers = [Answer(words: "Pay the 3", answer: "approve", recommended: true),
                       Answer(words: "Not now", answer: "reject", decline: true)]
        pay.why = "All three match their orders and were checked by Bill pay."
        pay.meanwhile = "other payouts carry on; these three wait."
        pay.deadline = "by Fri 12:00"; pay.later = "Later"
        pay.note = "Pay the 3 opens three approvals, one after the other."
        pay.ref = Ref(kind: "hold", id: "sample-hold-pay", taf: "Bill pay")
        var remind = Decision()
        remind.id = "d-remind"; remind.question = "Send the first reminder to Atelier Dubois (invoice 0142, €860, 21 days late)?"
        remind.answers = [Answer(words: "Send", answer: "approve", recommended: true),
                          Answer(words: "Don't send", answer: "reject", decline: true)]
        remind.why = "No reply to the invoice, and no dispute in the log."
        remind.meanwhile = "the reminder is held; nothing is sent."
        remind.later = "Later"
        remind.ref = Ref(kind: "hold", id: "sample-hold-remind", taf: "Refunds")
        b.decisions = [pay, remind]
        if urgent {
            var expires = Decision()
            expires.id = "d-urgent"; expires.question = "Pay Mercier Textiles €2,100 before the payment expires at 12:00?"
            expires.answers = [Answer(words: "Pay €2,100", answer: "approve", recommended: true),
                               Answer(words: "Don't pay", answer: "reject", decline: true)]
            expires.why = "It matches order 0388, and Bill pay checked it this morning."
            expires.meanwhile = "the payment stays held; nothing is sent."
            expires.cantWait = "This can't wait: it expires at 12:00, before the next brief."
            expires.urgent = true
            expires.ref = Ref(kind: "hold", id: "sample-hold-urgent", taf: "Bill pay")
            b.decisions.insert(expires, at: 0)
        }
        b.nextMove = NextMove(words: "Prepare an escalation summary for these 3 invoices past 30 days?", start: "Start", dismiss: "Dismiss",
                              cases: ["0142", "0137", "0129"])
        var money = Done(mission: "Money", value: "€3,400 due back", log: sampleMoney)
        money.cases = [
            Case(name: "Atelier Dubois, invoice 0142", words: "reminder sent Tuesday · waiting for payment · next check Friday", state: "waiting", nextCheck: "Friday"),
            Case(name: "Brio Bikes, invoice 0139", words: "paid Thursday · closed", state: "closed"),
            Case(name: "Bill pay", words: "12 bills sorted, 2 didn't match their order: Mercier 0412, Lumio 0415", state: "open"),
            Case(name: "Fern & Co, invoice 0137", words: "promised Friday · waiting for payment · last checked Monday 14:00", state: "waiting"),
        ]
        let support = Done(mission: "Support", cases: [Case(name: "Support replies", words: "9 tickets answered · 1 waits for a refund check · next check 13:00", state: "open")])
        b.done = label == sampleMoney ? [money] : [money, support]
        b.rest = Counted(count: label == sampleMoney ? 9 : 14,
                         words: label == sampleMoney ? "9 things wait that need nothing from you now" : "14 things wait that need nothing from you now")
        b.credits = "Today: 38 credits, the lead's runs included."
        return b
    }

    /// The sample mission's log, newest first, with a removed taf's entry greyed and marked.
    static func sampleLog() -> TafsCloud.MissionLog {
        typealias Item = TafsCloud.MissionLog.Item
        typealias Evidence = TafsCloud.MissionLog.Evidence
        return TafsCloud.MissionLog(items: [
            Item(at: "2026-11-14T07:10:00+01:00", by: "coach", kind: "stuck", words: "Bill pay didn't run at 07:00: its bank sign-in needs you.",
                 kindWords: "stuck", byWords: "Coach", evidence: [Evidence(kind: "run", id: "r1", words: "run"), Evidence(kind: "hold", id: "h1", words: "hold")]),
            Item(at: "2026-11-14T07:03:00+01:00", by: "taf", kind: "handed", words: "Bill 0412, for payment. A reference, not its data.",
                 kindWords: "handed", byWords: "Bill pay → Refunds", evidence: [Evidence(kind: "item", id: "0412", words: "bill 0412")]),
            Item(at: "2026-11-14T07:02:00+01:00", by: "taf", kind: "delivered", words: "12 bills sorted, 2 didn't match their order.",
                 kindWords: "delivered", byWords: "Bill pay", evidence: [Evidence(kind: "run", id: "r2", words: "run"), Evidence(kind: "record", id: "rec", words: "12 record lines")],
                 value: "€4,120 sorted"),
            Item(at: "2026-11-13T18:28:00+01:00", by: "you", kind: "decided", words: "Sent the reminder to Atelier Dubois.",
                 kindWords: "decided", byWords: "You", evidence: [Evidence(kind: "item", id: "0142", words: "invoice 0142"), Evidence(kind: "hold", id: "h2", words: "hold")]),
            Item(at: "2026-11-13T09:12:00+01:00", by: "taf", kind: "delivered", words: "3 quotes drafted for new clients.",
                 kindWords: "delivered", byWords: "Quotes", evidence: [Evidence(kind: "run", id: "r3", words: "run")], removed: true),
        ])
    }
}
