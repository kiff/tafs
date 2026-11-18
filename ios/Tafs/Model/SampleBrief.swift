import Foundation

/// The sample's lines (app/strings' `sample.brief.*`, `sample.log.*`), named here because inside
/// `TafsCloud.Brief` `Words` is the brief's own headings.
private typealias SampleLines = Words.Sample.Brief
private typealias SampleNames = Words.Sample
private typealias SampleLog = Words.Sample.Log

/// The sample's brief (Missions design v2.1): a morning brief over the sample's own tafs (Late parcels,
/// Lead follow-up, Support replies), so screenshots and a signed-out walk show it. tafs cloud sends the
/// real one; the sample's own lines come from the shared words, so each language's screenshots show it
/// in that language, as Android's do. The label `money` keeps its id and is the parcels' mission.
extension TafsCloud.Brief {
    static let sampleMoney = "money"
    static let sampleClients = "clients"

    /// The sample's labels, over its tafs by template, so a translated sample keeps them.
    static var sampleLabels: [(label: TafsCloud.Brief.Label, tafs: [String])] { [
        (Label(id: sampleMoney, name: SampleLines.Label.money, color: "money"), ["parcels"]),
        (Label(id: sampleClients, name: SampleLines.Label.clients, color: "customers"), ["leads"]),
        (Label(id: "ops", name: "Ops", color: "operations"), ["deploys"]),
    ] }

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
            w.when = label == sampleMoney ? "\(SampleLines.Label.money) · \(SampleLines.when)" : SampleLines.when
            w.decisions = urgent ? "Decisions · 3" : "Decisions · 2"; w.urgent = "Urgent"; w.done = "Done since last evening"; w.nextMove = "Next move"
            w.recommends = "Coach recommends"; w.meanwhile = "Meanwhile:"; w.evidence = "Evidence"; w.all = "All"
            return w
        }()
        b.labels = [
            Label(id: sampleMoney, name: SampleLines.Label.money, color: "money", count: 5, waiting: 2),
            Label(id: sampleClients, name: SampleLines.Label.clients, color: "customers", count: 2),
            Label(id: "support", name: SampleLines.Label.support, color: "operations", count: 1),
        ]
        let parcels = SampleNames.Parcels.name, leads = SampleNames.Leads.name
        var pay = Decision()
        pay.id = "d-pay"; pay.question = SampleLines.Pay.question
        pay.answers = [Answer(words: SampleLines.Pay.answer, answer: "approve", recommended: true),
                       Answer(words: "Not now", answer: "reject", decline: true)]
        pay.why = SampleLines.Pay.why(taf: parcels)
        pay.meanwhile = SampleLines.Pay.meanwhile
        pay.deadline = SampleLines.Pay.deadline; pay.later = SampleLines.later
        pay.note = SampleLines.Pay.note
        pay.ref = Ref(kind: "hold", id: "sample-hold-pay", taf: parcels)
        var remind = Decision()
        remind.id = "d-remind"; remind.question = SampleLines.Remind.question
        remind.answers = [Answer(words: "Send", answer: "approve", recommended: true),
                          Answer(words: SampleLines.Remind.decline, answer: "reject", decline: true)]
        remind.why = SampleLines.Remind.why
        remind.meanwhile = SampleLines.Remind.meanwhile
        remind.later = SampleLines.later
        remind.ref = Ref(kind: "hold", id: "sample-hold-remind", taf: leads)
        b.decisions = [pay, remind]
        if urgent {
            var expires = Decision()
            expires.id = "d-urgent"; expires.question = SampleLines.Expires.question
            expires.answers = [Answer(words: SampleLines.Expires.answer, answer: "approve", recommended: true),
                               Answer(words: SampleLines.Expires.decline, answer: "reject", decline: true)]
            expires.why = SampleLines.Expires.why(taf: parcels)
            expires.meanwhile = SampleLines.Expires.meanwhile
            expires.cantWait = SampleLines.Expires.cantWait
            expires.urgent = true
            expires.ref = Ref(kind: "hold", id: "sample-hold-urgent", taf: parcels)
            b.decisions.insert(expires, at: 0)
        }
        b.nextMove = NextMove(words: SampleLines.nextMove, start: "Start", dismiss: SampleLines.nextMoveDismiss,
                              cases: ["0142", "0137", "0129"])
        // The parcels' mission (the label `money` keeps its id): its taf's line, with the value its brief leads with.
        var money = Done(mission: SampleLines.Label.money, value: SampleLines.moneyValue, log: sampleMoney)
        money.cases = [Case(name: parcels, words: SampleLines.Case.billsLine, state: "open")]
        // The leads' mission (the label `clients`): one case per lead.
        var clients = Done(mission: SampleLines.Label.clients)
        clients.cases = [
            Case(name: SampleLines.Case.dubois, words: SampleLines.Case.duboisLine, state: "waiting", nextCheck: SampleLines.Case.duboisNext),
            Case(name: SampleLines.Case.brio, words: SampleLines.Case.brioLine, state: "closed"),
            Case(name: SampleLines.Case.fern, words: SampleLines.Case.fernLine, state: "waiting"),
        ]
        let support = Done(mission: SampleLines.Label.support, cases: [Case(name: SampleLines.Case.support, words: SampleLines.Case.supportLine, state: "open")])
        b.done = label == sampleMoney ? [money] : [money, clients, support]
        let rest = label == sampleMoney ? 9 : 14
        b.rest = Counted(count: rest, words: SampleLines.rest(count: rest))
        b.credits = SampleLines.credits
        return b
    }

    /// The sample mission's log, newest first, with a removed taf's entry greyed and marked.
    static func sampleLog() -> TafsCloud.MissionLog {
        typealias Item = TafsCloud.MissionLog.Item
        typealias Evidence = TafsCloud.MissionLog.Evidence
        return TafsCloud.MissionLog(items: [
            Item(at: "2026-11-14T07:10:00+01:00", by: "coach", kind: "stuck", words: SampleLog.s5Line,
                 kindWords: "stuck", byWords: "Coach", evidence: [Evidence(kind: "run", id: "r1", words: "run"), Evidence(kind: "hold", id: "h1", words: "hold")]),
            Item(at: "2026-11-14T07:03:00+01:00", by: "taf", kind: "handed", words: SampleLog.s4Line,
                 kindWords: "handed", byWords: SampleLog.s4Title, evidence: [Evidence(kind: "item", id: "5098", words: SampleLog.s4Evidence)]),
            Item(at: "2026-11-14T07:02:00+01:00", by: "taf", kind: "delivered", words: SampleLog.s3Line,
                 kindWords: "delivered", byWords: SampleLog.s3By, evidence: [Evidence(kind: "run", id: "r2", words: "run"), Evidence(kind: "record", id: "rec", words: "12 record lines")],
                 value: SampleLog.s3Value),
            Item(at: "2026-11-13T18:28:00+01:00", by: "you", kind: "decided", words: SampleLog.s2Line,
                 kindWords: "decided", byWords: "You", evidence: [Evidence(kind: "item", id: "kaya", words: SampleLog.s2Evidence), Evidence(kind: "hold", id: "h2", words: "hold")]),
            Item(at: "2026-11-13T09:12:00+01:00", by: "taf", kind: "delivered", words: SampleLog.s1Line,
                 kindWords: "delivered", byWords: SampleLog.s1Title, evidence: [Evidence(kind: "run", id: "r3", words: "run")], removed: true),
        ])
    }
}
