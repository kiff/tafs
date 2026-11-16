package bot.tafs.app.model

import bot.tafs.app.kiff.Brief
import bot.tafs.app.kiff.BriefWords
import bot.tafs.app.kiff.HomeLabel
import bot.tafs.app.kiff.MissionLogBy
import bot.tafs.app.kiff.MissionLogEvidence
import bot.tafs.app.kiff.MissionLogItem
import bot.tafs.app.kiff.MissionLogPage

/**
 * The sample's brief (Missions design v2.1, the validated "Money" mockups): a morning brief over the sample's own tafs, so the store's
 * screenshots show it (a debug build opened with the `screenshots` and `brief` extras). tafs cloud sends the real one, in the
 * person's language; these lines stand in for its words, never as the app's own, as iOS's `SampleBrief.swift`.
 */
object SampleBrief {
  const val MONEY = "money"
  const val CLIENTS = "clients"

  /** The sample's brief, filtered to [label] as tafs cloud filters it: a label keeps its own decisions and cases, and the rest line counts only its own. With [urgent], a payment that expires before the next brief leads the decisions, with no Later. */
  fun brief(label: String? = null, urgent: Boolean = false): Brief {
    val money = label == MONEY
    val pay = Brief.Decision(
      id = "d-pay", question = "Pay 3 supplier bills due Friday, €1,240?",
      answers = listOf(Brief.Answer("Pay the 3", "approve", recommended = true), Brief.Answer("Not now", "reject", decline = true)),
      why = "All three match their orders and were checked by Bill pay.",
      meanwhile = "other payouts carry on; these three wait.",
      deadline = "by Fri 12:00", later = "Later",
      note = "Pay the 3 opens three approvals, one after the other.",
      ref = Brief.Ref("hold", "sample-hold-pay", "Bill pay"),
    )
    val remind = Brief.Decision(
      id = "d-remind", question = "Send the first reminder to Atelier Dubois (invoice 0142, €860, 21 days late)?",
      answers = listOf(Brief.Answer("Send", "approve", recommended = true), Brief.Answer("Don't send", "reject", decline = true)),
      why = "No reply to the invoice, and no dispute in the log.",
      meanwhile = "the reminder is held; nothing is sent.",
      later = "Later",
      ref = Brief.Ref("hold", "sample-hold-remind", "Refunds"),
    )
    val expires = Brief.Decision(
      id = "d-urgent", question = "Pay Mercier Textiles €2,100 before the payment expires at 12:00?",
      answers = listOf(Brief.Answer("Pay €2,100", "approve", recommended = true), Brief.Answer("Don't pay", "reject", decline = true)),
      why = "It matches order 0388, and Bill pay checked it this morning.",
      meanwhile = "the payment stays held; nothing is sent.",
      cantWait = "This can't wait: it expires at 12:00, before the next brief.",
      urgent = true,
      ref = Brief.Ref("hold", "sample-hold-urgent", "Bill pay"),
    )
    val moneyDone = Brief.Done(
      mission = "Money", value = "€3,400 due back", log = MONEY,
      cases = listOf(
        Brief.Case("Atelier Dubois, invoice 0142", "reminder sent Tuesday · waiting for payment · next check Friday", "waiting", "Friday"),
        Brief.Case("Brio Bikes, invoice 0139", "paid Thursday · closed", "closed"),
        Brief.Case("Bill pay", "12 bills sorted, 2 didn't match their order: Mercier 0412, Lumio 0415", "open"),
        Brief.Case("Fern & Co, invoice 0137", "promised Friday · waiting for payment · last checked Monday 14:00", "waiting"),
      ),
    )
    val support = Brief.Done(
      mission = "Support",
      cases = listOf(Brief.Case("Support replies", "9 tickets answered · 1 waits for a refund check · next check 13:00", "open")),
    )
    return Brief(
      at = "2026-11-14T08:00:00+01:00",
      nextAt = "2026-11-14T18:00:00+01:00",
      words = BriefWords(
        tab = "Your brief", title = "Your brief",
        `when` = if (money) "Money · Morning brief · 08:00 · next at 18:00" else "Morning brief · 08:00 · next at 18:00",
        decisions = if (urgent) "Decisions · 3" else "Decisions · 2", urgent = "Urgent", done = "Done since last evening",
        nextMove = "Next move", recommends = "Coach recommends", meanwhile = "Meanwhile:", evidence = "Evidence", all = "All",
      ),
      labels = listOf(
        HomeLabel(MONEY, "Money", "money", count = 5, waiting = 2),
        HomeLabel(CLIENTS, "Clients", "customers", count = 2),
        HomeLabel("support", "Support", "operations", count = 1),
      ),
      decisions = (if (urgent) listOf(expires) else emptyList()) + listOf(pay, remind),
      nextMove = Brief.NextMove("Prepare an escalation summary for these 3 invoices past 30 days?", "Start", "Dismiss", listOf("0142", "0137", "0129")),
      done = if (money) listOf(moneyDone) else listOf(moneyDone, support),
      rest = Brief.Counted(
        if (money) 9 else 14,
        if (money) "9 things wait that need nothing from you now" else "14 things wait that need nothing from you now",
      ),
      credits = "Today: 38 credits, the lead's runs included.",
    )
  }

  /** The sample mission's log, as tafs cloud sends a page of it: newest first, with a removed taf's entry greyed and marked. */
  fun log(): MissionLogPage = MissionLogPage(
    listOf(
      MissionLogItem(
        "s5", "2026-11-14T06:10:00Z", MissionLogBy("lead"), "Coach", "stuck", "stuck", "Bill pay didn't run at 07:00: its bank sign-in needs you.",
        listOf(MissionLogEvidence("run", "r1", "run"), MissionLogEvidence("hold", "h1", "hold")),
      ),
      MissionLogItem(
        "s4", "2026-11-14T06:03:00Z", MissionLogBy("taf", "Bill pay"), "Bill pay → Refunds", "handed", "handed", "Bill 0412, for payment. A reference, not its data.",
        listOf(MissionLogEvidence("item", "0412", "bill 0412")),
      ),
      MissionLogItem(
        "s3", "2026-11-14T06:02:00Z", MissionLogBy("taf", "Bill pay"), null, "delivered", "delivered", "12 bills sorted, 2 didn't match their order.",
        listOf(MissionLogEvidence("run", "r2", "run"), MissionLogEvidence("record", "rec", "12 record lines")), value = "€4,120 sorted",
      ),
      MissionLogItem(
        "s2", "2026-11-13T17:28:00Z", MissionLogBy("person"), "You", "decided", "decided", "Sent the reminder to Atelier Dubois.",
        listOf(MissionLogEvidence("item", "0142", "invoice 0142"), MissionLogEvidence("hold", "h2", "hold")),
      ),
      MissionLogItem(
        "s1", "2026-11-13T08:12:00Z", MissionLogBy("taf"), "Quotes", "delivered", "delivered", "3 quotes drafted for new clients.",
        listOf(MissionLogEvidence("run", "r3", "run")), removed = true,
      ),
    ),
  )
}
