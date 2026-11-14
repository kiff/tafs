import SwiftUI

struct NeedsYouView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollViewReader { reader in
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(Words.NeedsYou.title).scaledFont(34, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                if model.mode == .kiff, model.sessionHasKey, model.canAnswerHolds != false, model.alertsReady != true {
                    AlertsCard()
                }
                // A step a taf handed its owner comes first, oldest first (docs/api.md › Your step), as on Android.
                ForEach(model.yourSteps) { step in YourStepCard(step: step).id(step.id) }
                // Coach waits on the owner in a chat (an approval, a sign-in, an answer): tafs cloud's words, and its way in.
                // "Set all aside", tafs cloud's, above them: only with two or more, and one ask that names the count.
                if let clear = model.setAllAside { SetAllAsideButton(clear: clear) }
                ForEach(model.coachWaiting) { waiting in CoachWaitingCard(waiting: waiting).id(waiting.id) }
                // A knock at a taf's door waits for the owner too (kiff/tafs#236, item 17).
                let knocked = model.tafs.filter { knocks($0) > 0 }
                // A money call whose outcome KIFF can't prove: the owner says whether it happened.
                ForEach(model.unproven) { call in DidThisHappenCard(call: call) }
                ForEach(model.cardUnitExceptions) { ask in CardUnitExceptionPanel(ask: ask).id(ask.id) }
                if model.held.isEmpty && knocked.isEmpty && model.unproven.isEmpty && model.cardUnitExceptions.isEmpty && model.yourSteps.isEmpty && model.coachWaiting.isEmpty {
                    Panel {
                        Text(Words.NeedsYou.nothingWaiting).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(Words.NeedsYou.emptyHint)
                            .scaledFont(14).foregroundColor(Theme.soft)
                    }
                } else {
                    ForEach(model.held) { call in HeldCallCard(call: call).id(call.id) }
                    ForEach(knocked) { taf in
                        // Its door requests are answered on Activity, so it opens there, not on Chat.
                        NavigationLink { TafDetailView(tafID: taf.id, opensOn: TafDetailView.activitySection) } label: {
                            Panel(stroke: Theme.amber.opacity(0.4)) {
                                HStack(spacing: 10) {
                                    TafAvatar(taf: taf, size: 32)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(taf.name).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                                        let n = knocks(taf)
                                        Text(Words.NeedsYou.knocks(count: n))
                                            .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                                }
                                .contentShape(Rectangle())
                            }
                        }
                        .buttonStyle(.drivablePlain)
                        .accessibilityHint(Words.NeedsYou.opensWhereYouAnswer(taf: "\(taf.name)"))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        // A hold opened from its notification is brought into view (kiff/tafs#210).
        .onChange(of: model.requestedHold) { request in scroll(to: request, reader) }
        .onChange(of: model.held) { _ in scroll(to: model.requestedHold, reader) }
        .onChange(of: model.cardUnitExceptions) { _ in scroll(to: model.requestedHold, reader) }
        // A step opened from its notification too, once Home has listed it.
        .onChange(of: model.requestedStep) { _ in scroll(toStep: reader) }
        .onChange(of: model.yourSteps) { _ in scroll(toStep: reader) }
        .onAppear { scroll(to: model.requestedHold, reader); scroll(toStep: reader) }
        }
        .refreshable {
            await model.refresh()
            await model.loadUnproven()
        }
        .task { await model.loadUnproven() }
        .screenBackground()
    }

    private func scroll(toStep reader: ScrollViewProxy) {
        guard let handoff = model.requestedStep, model.yourSteps.contains(where: { $0.handoff == handoff }) else { return }
        withMotion { reader.scrollTo(handoff, anchor: .top) }
        model.requestedStep = nil
    }

    private func knocks(_ taf: Taf) -> Int { (taf.doors ?? []).reduce(0) { $0 + $1.knocks } }

    private func scroll(to request: AppModel.HoldRequest?, _ reader: ScrollViewProxy) {
        guard let request else { return }
        if let call = model.held.first(where: { $0.kiffID == request.kiffID }) { withMotion { reader.scrollTo(call.id, anchor: .top) } }
        else if let exception = model.cardUnitExceptions.first(where: { $0.id == request.kiffID }) { withMotion { reader.scrollTo(exception.id, anchor: .top) } }
    }
}

/// "Did this happen?" (A5): a money call whose outcome KIFF can't prove, its answer lost or
/// refused with nothing proving no effect, or one of the first outcomes of a money operation
/// on probation. The owner says: No gives the Card its room back,
/// Yes keeps it counted. Signed like an approval; the first answer stands.
struct DidThisHappenCard: View {
    @EnvironmentObject private var model: AppModel
    let call: KIFFClient.Unproven
    @State private var sending = false

    var body: some View {
        Panel(stroke: Theme.amber.opacity(0.4)) {
            VStack(alignment: .leading, spacing: 10) {
                Text(Words.Record.DidThisHappen.title).scaledFont(13, weight: .semibold, relativeTo: .footnote).foregroundColor(Theme.amberText)
                // KIFF's own words for the call and for why it asks, as they are; before KIFF
                // sent them, its title and amount.
                Text(call.words ?? call.title).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                if call.words == nil, let money = call.money {
                    Text(money.formatted).scaledFont(15).foregroundColor(Theme.ink)
                }
                if let why = call.statusWords {
                    Text(why).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
                if model.mayAnswerHolds {
                    // One gate for both answers: when the device can't approve, its words and
                    // buttons show once, at full width, not once per answer. The line about No
                    // goes with the buttons: without them it names a button that isn't there.
                    ApprovalGate {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 10) {
                                Button(Words.Record.DidThisHappen.no) { answer(false) }.buttonStyle(OutlineButtonStyle())
                                Button(Words.Record.DidThisHappen.yes) { answer(true) }.buttonStyle(PrimaryButtonStyle())
                            }
                            .disabled(sending)
                            Text(Words.NeedsYou.noGivesCardRoomBack(biometry: "\(Biometry.name)"))
                                .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
        }
        .accessibilityIdentifier("happened.\(call.proposal)")
    }

    private func answer(_ happened: Bool) {
        sending = true
        Task {
            _ = await model.answerHappened(call, happened: happened)
            sending = false
        }
    }
}

struct HeldCallCard: View {
    @EnvironmentObject private var model: AppModel
    let call: HeldCall
    @State private var sending = false
    /// Coach's install, its switch begun, while its Card is read (kiff/tafs#206).
    @State private var installing: AppModel.CoachInstall?
    @Environment(\.openURL) private var openURL

    /// Approve, extra room, next steps and Refuse, while the call can still be answered.
    /// refuseOnly: KIFF takes this person's refusal but not their approval (they asked for
    /// the call): Refuse and the next steps stay, approving and extra room go.
    @ViewBuilder private func answers(refuseOnly: Bool) -> some View {
        if !model.mayAnswerHolds {
            // A viewer uses the taf but doesn't answer for the account (the 870 test).
            Text(Words.NeedsYou.someoneAnswers)
                .scaledFont(13).foregroundColor(Theme.soft)
                .fixedSize(horizontal: false, vertical: true)
        } else if model.canAnswer(call) {
            if refuseOnly {
                if !call.approvableOnce, model.mayUseCoach {
                    Button {
                        model.improveTaf(call.tafID)
                        model.coachOpen = true
                    } label: {
                        Label(Words.NeedsYou.changeLimit, systemImage: "sparkle")
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(sending)
                }
            } else if model.showsApprovalKeyLost, call.kiffID != nil {
                // Approving or extra room would fail: the way out instead. Refuse stays below.
                ApprovalKeyLostLine()
            } else if call.approvableOnce {
                ApprovalGate {
                    Button {
                        if call.install != nil { install() } else { send(.approveOnce) }
                    } label: {
                        Label(call.install == nil ? Words.NeedsYou.approveOnceWith(biometry: "\(Biometry.name)") : Words.Version.installWith(biometry: "\(Biometry.name)"), systemImage: Biometry.symbol)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(sending)
                }
            } else {
                // Over a total the Card keeps across calls: approving once can't pass it
                // (kiff/tafs#138, item 49). Extra room can, for this call, until that total
                // resets (kiff/tafs#211, 6.13); a lasting change is Coach's.
                Text(model.canManageCards
                     ? Words.NeedsYou.overTotalManager
                     : Words.NeedsYou.overTotal)
                    .scaledFont(13).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                // What of the total is from calls the tool refused, in KIFF's words.
                ForEach(model.pendingLines(call.tafID), id: \.self) { line in
                    Text(line)
                        .scaledFont(13).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if model.canManageCards {
                    ApprovalGate {
                        Button { send(.extraRoom) } label: {
                            Label(Words.NeedsYou.extraRoomWith(biometry: "\(Biometry.name)"), systemImage: Biometry.symbol)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(sending)
                    }
                }
                if model.mayUseCoach {
                    Button {
                        model.improveTaf(call.tafID)
                        model.coachOpen = true
                    } label: {
                        Label(Words.NeedsYou.changeLimit, systemImage: "sparkle")
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(sending)
                }
            }

            // A third answer, for someone who manages Cards: change the Card so calls like this
            // go on their own (docs/allow-from-now-on.md). Shown from the held call itself.
            if !refuseOnly, !model.showsApprovalKeyLost, model.offersAllow(call) {
                Button { model.openAllow(call) } label: {
                    Text(Words.NeedsYou.allowFromNowOn)
                }
                .buttonStyle(OutlineButtonStyle())
                .disabled(sending)
                .accessibilityIdentifier("held.allow")
                .sheet(item: Binding(get: { model.allow?.call.id == call.id ? model.allow : nil },
                                     set: { if $0 == nil, model.allow?.call.id == call.id { model.allow = nil } })) { open in
                    AllowSheetView(open: open).environmentObject(model)
                }
            }

            HStack(spacing: 8) {
                ForEach(call.nextSteps, id: \.self) { step in
                    Button(step) { send(.nextStep(step)) }
                        .buttonStyle(OutlineButtonStyle())
                }
                Button(Words.NeedsYou.refuse) { send(.refuse) }
                    .buttonStyle(OutlineButtonStyle(color: Theme.no))
                    .confirming()
            }
            .disabled(sending)
        } else {
            Text(Words.NeedsYou.answerInKiffUntil)
                .scaledFont(13).foregroundColor(Theme.soft)
            if let page = model.answerPage(for: call) {
                Button {
                    openURL(page)
                } label: {
                    Label(Words.NeedsYou.answerInKiffButton, systemImage: "arrow.up.right.square")
                }
                .buttonStyle(PrimaryButtonStyle())
            }
        }
    }

    var body: some View {
        let taf = model.taf(call.tafID)
        Panel(stroke: Theme.amber.opacity(0.4)) {
            // One sentence for VoiceOver (kiff/tafs#210): the taf, what it asks, when it lapses.
            VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                if let taf {
                    TafAvatar(taf: taf, size: 32)
                } else if model.isCoach(call.tafID) {
                    // Coach's own hold, with Coach's mark.
                    Image(systemName: "sparkle").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.onFill)
                        .frame(width: 32, height: 32).background(Circle().fill(Theme.coach))
                }
                Text(model.holderName(call.tafID) ?? Words.NeedsYou.aTaf).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                Spacer()
                // The system counts it down, every second, wherever the card is (kiff/tafs#236,
                // item 8); the timeline only turns it to "expired" at the end.
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    remaining(at: context.date)
                        .scaledFont(12, design: .monospaced)
                        .foregroundColor(Theme.amberText)
                }
            }
            if let amount = call.proposed.amount {
                Text(amount.formatted).scaledFont(44, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
            }
            Text(call.proposed.summary).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
            if !call.proposed.context.isEmpty {
                Text(call.proposed.context).scaledFont(14).foregroundColor(Theme.soft)
            }
            Text(call.reason)
                .scaledFont(12, weight: .medium)
                .padding(.horizontal, 10).padding(.vertical, 5)
                .foregroundColor(Theme.amberText)
                .background(Theme.amber.opacity(0.14))
                .clipShape(Capsule())
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.sentence(call, taf: model.holderName(call.tafID)))

            // What the call is on and why, in the taf's own words (wave 1 item 4). They may come
            // from a customer's text, so they sit under their own heading, drawn verbatim (no
            // markup, no link), and never stand in for KIFF's amount, rule or action above.
            if call.tafAbout != nil || call.tafReason != nil {
                VStack(alignment: .leading, spacing: 4) {
                    Text(Words.NeedsYou.tafSays)
                        .scaledFont(12, weight: .semibold, relativeTo: .caption).foregroundColor(Theme.soft)
                    if let about = call.tafAbout {
                        Text(verbatim: about).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let reason = call.tafReason {
                        Text(verbatim: reason).scaledFont(14).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.soft.opacity(0.08)))
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("held.taf-says")
            }

            // What it's on, as KIFF keeps the call (kiff/tafs#102), under Details: ids and
            // a vendor's own values aren't words for the card's face (kiff/tafs#236, item 7).
            if let details = call.details, !details.isEmpty {
                DisclosureGroup {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(details, id: \.self) { line in
                            Text(line).scaledFont(12, design: .monospaced).foregroundColor(Theme.soft).textSelection(.enabled)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
                } label: {
                    Text(Words.Common.details).scaledFont(13, weight: .semibold, relativeTo: .footnote).foregroundColor(Theme.blue)
                }
            }

            // Expired, or KIFF says this person may not answer it: words, not buttons that can't
            // work (the 789 retest).
            TimelineView(.periodic(from: .now, by: 1)) { context in
                if context.date >= call.expiresAt {
                    Text(Words.NeedsYou.expiredLine)
                        .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                } else if let code = model.unanswerable[call.id], !AppModel.refusesOnly(code) {
                    Text(AppModel.refusalWords(code) ?? Words.NeedsYou.answerNotTaken)
                        .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        // Said here, where the owner answers, so the taf's words needn't say it.
                        Text(HeldCallCard.nothingSent)
                            .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        // They asked for it: someone else approves, and they can still refuse it.
                        if let code = model.unanswerable[call.id], let words = AppModel.refusalWords(code) {
                            Text(words).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        }
                        answers(refuseOnly: model.unanswerable[call.id].map(AppModel.refusesOnly) ?? false)
                    }
                }
            }
        }
        .sheet(item: $installing) { ready in
            VersionCardSheet(version: ready.version, tafID: ready.tafID, approving: ready.call)
        }
        // The answer chosen on its notification (kiff/tafs#210): Approve asks for Face ID now.
        .onAppear { takeRequest() }
        .onChange(of: model.requestedHold) { _ in takeRequest() }
    }

    /// While a call waits: nothing the call asks for happens until someone approves it. The
    /// card, its alert and VoiceOver say it, so the taf's own words don't have to.
    static let nothingSent = Words.NeedsYou.nothingSent

    /// The hold as VoiceOver reads it: "Rae asks: Refund order 42, €150.00. Over €50 each.
    /// It lapses at 14:05. Nothing is sent until you approve."
    static func sentence(_ call: HeldCall, taf: String?) -> String {
        let what = [call.proposed.summary, call.proposed.amount?.formatted].compactMap { $0 }.joined(separator: ", ")
        return Words.NeedsYou.asksLapses(taf: taf ?? Words.NeedsYou.aTaf, what: what, reason: call.reason, time: call.expiresAt.shortTime, nothingSent: nothingSent)
    }

    private func takeRequest() {
        guard let request = model.requestedHold, request.kiffID == call.kiffID, !sending else { return }
        model.requestedHold = nil
        guard model.canAnswer(call) else { return }
        switch request.answer {
        case AlertAnswers.approve? where call.approvableOnce:
            if call.install != nil { install() } else { send(.approveOnce, fromNotification: true) }
        case AlertAnswers.refuse?:
            send(.refuse)
        default:
            break
        }
    }

    /// Approving Coach's install is installing it (kiff/tafs#206): the switch begins, what's in
    /// the way stopped; a Card to change is read first; then one Face ID confirms it, makes the
    /// version live, and approves the held call, in that order.
    private func install() {
        sending = true
        Task {
            if let ready = await model.beginCoachInstall(call) {
                if ready.hasCard { installing = ready } else { await model.useVersion(ready.version, of: ready.tafID, approving: call) }
            }
            sending = false
        }
    }

    private func send(_ answer: Answer, fromNotification: Bool = false) {
        sending = true
        Task {
            await model.answer(call, with: answer, fromNotification: fromNotification)
            sending = false
        }
    }

    private func remaining(at date: Date) -> Text {
        let seconds = Int(call.expiresAt.timeIntervalSince(date))
        guard seconds >= 1 else { return Text(Words.NeedsYou.expired) }
        if #available(iOS 16, *) {
            // The line's own words sit around the live timer, in the order its language puts them.
            let parts = Words.NeedsYou.left(time: "\u{1}").components(separatedBy: "\u{1}")
            return Text(parts.first ?? "") + Text(timerInterval: date...call.expiresAt, countsDown: true) + Text(parts.dropFirst().joined())
        }
        return Text(Words.NeedsYou.left(time: String(format: "%d:%02d", seconds / 60, seconds % 60)))
    }
}

/// Offers alerts that reach a closed app: KIFF tells tafs cloud when a call is held, and
/// tafs cloud tells this phone. One Face ID turns it on for the account.
struct AlertsCard: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Panel {
            Text(Words.NeedsYou.knowMomentTafNeedsYou).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
            Text(Words.NeedsYou.getAlertWhenCallWaits)
                .scaledFont(14).foregroundColor(Theme.soft)
            Button(model.turningOnAlerts ? Words.NeedsYou.turningOn : Words.NeedsYou.turnAlerts) {
                Task { await model.turnOnAlerts() }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.turningOnAlerts)
        }
    }
}

/// Alerts offered up front, right after signing in (the 755 retest), over the screen: one line, as
/// on Android, then iOS's own question. Not now keeps tafs usable; Needs you offers it again.
struct AlertsOfferSheet: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "bell.badge").scaledFont(40, weight: .semibold).foregroundColor(Theme.amber)
                .accessibilityHidden(true)
            Text(Words.Alert.prompt).scaledFont(24, weight: .bold, relativeTo: .title2).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            if let error = model.lastError, !model.turningOnAlerts {
                Text(error).scaledFont(14, relativeTo: .footnote).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
            Button(model.turningOnAlerts ? Words.NeedsYou.turningOn : Words.NeedsYou.turnAlerts) {
                Task { if await model.turnOnAlerts() { model.answerAlertsOffer() } }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.turningOnAlerts)
            Button(Words.Common.notNow) { model.answerAlertsOffer() }
                .scaledFont(15).foregroundColor(Theme.soft)
                .frame(maxWidth: .infinity, minHeight: 44)
                .disabled(model.turningOnAlerts)
        }
        .padding(24)
        .background(Theme.background)
    }
}

/// A Coach chat waiting on its person (docs/api.md › Coach waiting): tafs cloud's line, and its way in,
/// which opens Coach on that chat. When tafs cloud sends `words.set_aside`, the row can be swiped
/// away either way to set it aside, its "not now", and has a button with the same words, so a swipe
/// isn't the only way. One call per gesture: the row asks once, and the model sends nothing for a chat
/// already being set aside.
struct CoachWaitingCard: View {
    @EnvironmentObject private var model: AppModel
    let waiting: TafsCloud.CoachWaiting
    /// How far the row is dragged sideways now.
    @State private var dragged: CGFloat = 0
    /// This row asked to be set aside, and tafs cloud hasn't answered yet.
    @State private var asked = false
    /// How far a swipe goes before it sets the chat aside.
    static let swipeDistance: CGFloat = 96

    var body: some View {
        if model.canSetAside(waiting) {
            ZStack(alignment: dragged < 0 ? .trailing : .leading) {
                // Behind the row as it moves: tafs cloud's words for what the swipe does.
                Text(waiting.words.setAside).scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                    .padding(.horizontal, 16)
                    .opacity(min(1, abs(dragged) / Self.swipeDistance))
                    .accessibilityHidden(true)
                panel.offset(x: dragged)
            }
            // Beside the scroll, never instead of it: only a mostly sideways drag moves the row.
            .simultaneousGesture(DragGesture(minimumDistance: 20)
                .onChanged { drag in
                    guard !asked, abs(drag.translation.width) > abs(drag.translation.height) else { return }
                    dragged = drag.translation.width
                }
                .onEnded { drag in
                    let sideways = abs(drag.translation.width) > abs(drag.translation.height)
                    if sideways, abs(drag.translation.width) >= Self.swipeDistance { setAside() }
                    withMotion { dragged = 0 }
                })
            .accessibilityAction(named: Text(waiting.words.setAside)) { setAside() }
        } else {
            panel
        }
    }

    private var panel: some View {
        Panel(stroke: Theme.amber.opacity(0.4)) {
            HStack(alignment: .center, spacing: 10) {
                Image(systemName: "sparkle").foregroundColor(Theme.amberText).accessibilityHidden(true)
                Text(waiting.words.line).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            // Its way in only for someone who may use Coach: else the line alone, never a button that does nothing.
            if model.mayUseCoach {
                Button(waiting.words.open) { Task { await model.openCoachWaiting(waiting) } }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("needs-you.coach-waiting." + waiting.session)
            }
            // The swipe's words as a button too, only when tafs cloud sent them.
            if model.canSetAside(waiting) {
                Button(waiting.words.setAside) { setAside() }
                    .buttonStyle(OutlineButtonStyle(color: Theme.soft))
                    .disabled(asked || model.settingAside.contains(waiting.session))
                    // Coach driving the app never sets a chat aside: it's the person's to confirm (docs/drive.md).
                    .confirming()
                    .accessibilityIdentifier("needs-you.coach-waiting.set-aside." + waiting.session)
            }
        }
    }

    /// Asks once: a gesture, a tap or an accessibility action while it's on its way sends nothing more.
    private func setAside() {
        guard !asked, !model.settingAside.contains(waiting.session) else { return }
        asked = true
        Haptics.tap()
        Task {
            await model.setAsideCoach(waiting.session)
            asked = false
        }
    }
}

/// "Set all aside" (`coach_waiting_clear`), above two or more waiting Coach chats: it asks once, in
/// tafs cloud's words (the title names the count it ends), then sets every one aside in one call.
struct SetAllAsideButton: View {
    @EnvironmentObject private var model: AppModel
    let clear: TafsCloud.CoachWaitingClear
    @State private var confirming = false

    var body: some View {
        Button(clear.words.button) { confirming = true }
            .buttonStyle(OutlineButtonStyle(color: Theme.amberText))
            .disabled(!model.settingAside.isEmpty)
            .confirming()
            .accessibilityIdentifier("needs-you.coach-waiting.set-all-aside")
            .confirmationDialog(clear.words.title, isPresented: $confirming, titleVisibility: .visible) {
                Button(clear.words.yes, role: .destructive) { Task { await model.setAsideCoach(nil) } }
                Button(clear.words.no.isEmpty ? Words.Common.cancel : clear.words.no, role: .cancel) {}
            } message: {
                if !clear.words.text.isEmpty { Text(clear.words.text) }
            }
    }
}
