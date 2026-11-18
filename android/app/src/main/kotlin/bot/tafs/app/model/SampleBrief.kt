package bot.tafs.app.model

import bot.tafs.app.kiff.Brief
import bot.tafs.app.kiff.BriefWords
import bot.tafs.app.kiff.HomeLabel
import bot.tafs.app.kiff.MissionLogBy
import bot.tafs.app.kiff.MissionLogEvidence
import bot.tafs.app.kiff.MissionLogItem
import bot.tafs.app.kiff.MissionLogPage
import bot.tafs.app.model.Words.Sample.Brief as Lines

/**
 * The sample's brief (Missions design v2.1, the validated "Money" mockups): a morning brief over the sample's own tafs, so the store's
 * screenshots show it (a debug build opened with the `screenshots` and `brief` extras, and the store screenshots' test). tafs cloud
 * sends the real one, in the person's language; these lines stand in for its words, never as the app's own.
 */
object SampleBrief {
  const val MONEY = "money"
  const val CLIENTS = "clients"

  /**
   * The sample's brief, filtered to [label] as tafs cloud filters it: a label keeps its own decisions and cases, and the rest line
   * counts only its own. With [urgent], a payment that expires before the next brief leads the decisions, with no Later. Its lines
   * are the sample's (app/strings' `sample.brief.*`) and the brief's own headings, so each language's screenshots show it in that
   * language.
   */
  fun brief(label: String? = null, urgent: Boolean = false): Brief {
    val money = label == MONEY
    val parcels = Words.Sample.Parcels.name
    val leads = Words.Sample.Leads.name
    val pay = Brief.Decision(
      id = "d-pay", question = Lines.Pay.question,
      answers = listOf(Brief.Answer(Lines.Pay.answer, "approve", recommended = true), Brief.Answer(Words.Common.notNow, "reject", decline = true)),
      why = Lines.Pay.why(parcels),
      meanwhile = Lines.Pay.meanwhile,
      deadline = Lines.Pay.deadline, later = Lines.later,
      note = Lines.Pay.note,
      ref = Brief.Ref("hold", "sample-hold-pay", parcels),
    )
    val remind = Brief.Decision(
      id = "d-remind", question = Lines.Remind.question,
      answers = listOf(Brief.Answer(Words.Common.send, "approve", recommended = true), Brief.Answer(Lines.Remind.decline, "reject", decline = true)),
      why = Lines.Remind.why,
      meanwhile = Lines.Remind.meanwhile,
      later = Lines.later,
      ref = Brief.Ref("hold", "sample-hold-remind", leads),
    )
    val expires = Brief.Decision(
      id = "d-urgent", question = Lines.Expires.question,
      answers = listOf(Brief.Answer(Lines.Expires.answer, "approve", recommended = true), Brief.Answer(Lines.Expires.decline, "reject", decline = true)),
      why = Lines.Expires.why(parcels),
      meanwhile = Lines.Expires.meanwhile,
      cantWait = Lines.Expires.cantWait,
      urgent = true,
      ref = Brief.Ref("hold", "sample-hold-urgent", parcels),
    )
    // The parcels' mission (the label `money` keeps its id): its taf's line, with the value its brief leads with.
    val moneyDone = Brief.Done(
      mission = Lines.Label.money, value = Lines.moneyValue, log = MONEY,
      cases = listOf(Brief.Case(parcels, Lines.Case.billsLine, "open")),
    )
    // The leads' mission (the label `clients`): one case per lead.
    val leadsDone = Brief.Done(
      mission = Lines.Label.clients,
      cases = listOf(
        Brief.Case(Lines.Case.dubois, Lines.Case.duboisLine, "waiting", Lines.Case.duboisNext),
        Brief.Case(Lines.Case.brio, Lines.Case.brioLine, "closed"),
        Brief.Case(Lines.Case.fern, Lines.Case.fernLine, "waiting"),
      ),
    )
    val support = Brief.Done(
      mission = Lines.Label.support,
      cases = listOf(Brief.Case(Lines.Case.support, Lines.Case.supportLine, "open")),
    )
    val decisions = (if (urgent) listOf(expires) else emptyList()) + listOf(pay, remind)
    val rest = if (money) 9 else 14
    return Brief(
      at = "2026-11-14T08:00:00+01:00",
      nextAt = "2026-11-14T18:00:00+01:00",
      words = BriefWords(
        tab = Words.Brief.title, title = Words.Brief.title,
        `when` = if (money) "${Lines.Label.money} · ${Lines.`when`}" else Lines.`when`,
        decisions = "${Words.Brief.decisions} · ${Res.number(decisions.size)}", urgent = Words.Brief.urgent, done = Words.Brief.done,
        nextMove = Words.Brief.nextMove, recommends = Words.Brief.recommends, meanwhile = Words.Brief.meanwhile,
        evidence = Words.Brief.evidence, all = Words.Home.labelsAll,
      ),
      labels = listOf(
        HomeLabel(MONEY, Lines.Label.money, "money", count = 5, waiting = 2),
        HomeLabel(CLIENTS, Lines.Label.clients, "customers", count = 2),
        HomeLabel("support", Lines.Label.support, "operations", count = 1),
      ),
      decisions = decisions,
      nextMove = Brief.NextMove(Lines.nextMove, Words.Coach.start, Lines.nextMoveDismiss, listOf("0142", "0137", "0129")),
      done = if (money) listOf(moneyDone) else listOf(moneyDone, leadsDone, support),
      rest = Brief.Counted(rest, Lines.rest(rest)),
      credits = Lines.credits,
    )
  }

  /** The sample mission's log, as tafs cloud sends a page of it: newest first, with a removed taf's entry greyed and marked. */
  fun log(): MissionLogPage = MissionLogPage(
    listOf(
      MissionLogItem(
        "s5", "2026-11-14T06:10:00Z", MissionLogBy("lead"), "Coach", "stuck", "stuck", Words.Sample.Log.s5Line,
        listOf(MissionLogEvidence("run", "r1", "run"), MissionLogEvidence("hold", "h1", "hold")),
      ),
      MissionLogItem(
        "s4", "2026-11-14T06:03:00Z", MissionLogBy("taf", Words.Sample.Log.s4By), Words.Sample.Log.s4Title, "handed", "handed", Words.Sample.Log.s4Line,
        listOf(MissionLogEvidence("item", "5098", Words.Sample.Log.s4Evidence)),
      ),
      MissionLogItem(
        "s3", "2026-11-14T06:02:00Z", MissionLogBy("taf", Words.Sample.Log.s3By), null, "delivered", "delivered", Words.Sample.Log.s3Line,
        listOf(MissionLogEvidence("run", "r2", "run"), MissionLogEvidence("record", "rec", "12 record lines")), value = Words.Sample.Log.s3Value,
      ),
      MissionLogItem(
        "s2", "2026-11-13T17:28:00Z", MissionLogBy("person"), "You", "decided", "decided", Words.Sample.Log.s2Line,
        listOf(MissionLogEvidence("item", "kaya", Words.Sample.Log.s2Evidence), MissionLogEvidence("hold", "h2", "hold")),
      ),
      MissionLogItem(
        "s1", "2026-11-13T08:12:00Z", MissionLogBy("taf"), Words.Sample.Log.s1Title, "delivered", "delivered", Words.Sample.Log.s1Line,
        listOf(MissionLogEvidence("run", "r3", "run")), removed = true,
      ),
    ),
  )
}
