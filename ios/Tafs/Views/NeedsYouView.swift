import SwiftUI

struct NeedsYouView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollViewReader { reader in
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Needs you").scaledFont(34, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                if model.mode == .kiff, model.sessionHasKey, model.canAnswerHolds != false, model.alertsReady != true {
                    AlertsCard()
                }
                // A knock at a taf's door waits for the owner too (kiff/tafs#236, item 17).
                let knocked = model.tafs.filter { knocks($0) > 0 }
                if model.held.isEmpty && knocked.isEmpty {
                    Panel {
                        Text("Nothing is waiting.").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text("When a taf asks for something outside its Card, it shows up here and on your lock screen.")
                            .scaledFont(14).foregroundColor(Theme.soft)
                    }
                } else {
                    ForEach(model.held) { call in HeldCallCard(call: call).id(call.id) }
                    ForEach(knocked) { taf in
                        NavigationLink { TafDetailView(tafID: taf.id) } label: {
                            Panel(stroke: Theme.amber.opacity(0.4)) {
                                HStack(spacing: 10) {
                                    TafAvatar(taf: taf, size: 32)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(taf.name).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                                        let n = knocks(taf)
                                        Text(n == 1 ? "Someone knocked at its door. Nothing was done." : "\(n) knocks at its doors. Nothing was done.")
                                            .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                                }
                                .contentShape(Rectangle())
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Opens \(taf.name), where you answer it")
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        // A hold opened from its notification is brought into view (kiff/tafs#210).
        .onChange(of: model.requestedHold) { request in scroll(to: request, reader) }
        .onChange(of: model.held) { _ in scroll(to: model.requestedHold, reader) }
        .onAppear { scroll(to: model.requestedHold, reader) }
        }
        .coachClearance()
        .refreshable { await model.refresh() }
        .screenBackground()
    }

    private func knocks(_ taf: Taf) -> Int { (taf.doors ?? []).reduce(0) { $0 + $1.knocks } }

    private func scroll(to request: AppModel.HoldRequest?, _ reader: ScrollViewProxy) {
        guard let request, let call = model.held.first(where: { $0.kiffID == request.kiffID }) else { return }
        withMotion { reader.scrollTo(call.id, anchor: .top) }
    }
}

struct HeldCallCard: View {
    @EnvironmentObject private var model: AppModel
    let call: HeldCall
    @State private var sending = false
    /// Coach's install, its switch begun, while its Card is read (kiff/tafs#206).
    @State private var installing: AppModel.CoachInstall?
    @Environment(\.openURL) private var openURL

    var body: some View {
        let taf = model.taf(call.tafID)
        Panel(stroke: Theme.amber.opacity(0.4)) {
            // One sentence for VoiceOver (kiff/tafs#210): the taf, what it asks, when it lapses.
            VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                if let taf { TafAvatar(taf: taf, size: 32) }
                Text(taf?.name ?? "A taf").scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
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
            .accessibilityLabel(Self.sentence(call, taf: taf?.name))

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
                    Text("Details").scaledFont(13, weight: .semibold, relativeTo: .footnote).foregroundColor(Theme.blue)
                }
            }

            if model.canAnswer(call) {
                if call.approvableOnce {
                    Button {
                        if call.install != nil { install() } else { send(.approveOnce) }
                    } label: {
                        Label(call.install == nil ? "Approve once with \(Biometry.name)" : "Install with \(Biometry.name)", systemImage: Biometry.symbol)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(sending)
                } else {
                    // Over a total the Card keeps across calls: approving once can't pass it
                    // (kiff/tafs#138, item 49). Extra room can, for this call, until that total
                    // resets (kiff/tafs#211, 6.13); a lasting change is Coach's.
                    Text(model.canManageCards
                         ? "Approving it once can't pass this: it's over a total the Card allows across calls, such as a day's. Extra room adds just what this call needs, until that total resets. After that the Card is as it was."
                         : "Approving it once can't pass this: it's over a total the Card allows across calls, such as a day's. Someone who manages the account's Cards can give it extra room, or you can refuse it.")
                        .scaledFont(13).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    if let refused = HeldCallCard.refusedLine(model.refusedToday(call.tafID)) {
                        Text(refused)
                            .scaledFont(13).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if model.canManageCards {
                        Button { send(.extraRoom) } label: {
                            Label("Extra room with \(Biometry.name)", systemImage: Biometry.symbol)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(sending)
                    }
                    Button {
                        model.improveTaf(call.tafID)
                        model.coachOpen = true
                    } label: {
                        Label("Change the limit in Coach mode", systemImage: "sparkle")
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .disabled(sending)
                }

                HStack(spacing: 8) {
                    ForEach(call.nextSteps, id: \.self) { step in
                        Button(step) { send(.nextStep(step)) }
                            .buttonStyle(OutlineButtonStyle())
                    }
                    Button("Refuse") { send(.refuse) }
                        .buttonStyle(OutlineButtonStyle(color: Theme.no))
                }
                .disabled(sending)
            } else {
                Text("Answering from the phone arrives once KIFF checks the phone's signature. Until then, answer in KIFF.")
                    .scaledFont(13).foregroundColor(Theme.soft)
                if let page = model.answerPage(for: call) {
                    Button {
                        openURL(page)
                    } label: {
                        Label("Answer in KIFF", systemImage: "arrow.up.right.square")
                    }
                    .buttonStyle(PrimaryButtonStyle())
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

    /// The hold as VoiceOver reads it: "Rae asks: Refund order 42, €150.00. Over €50 each.
    /// It lapses at 14:05."
    static func sentence(_ call: HeldCall, taf: String?) -> String {
        let what = [call.proposed.summary, call.proposed.amount?.formatted].compactMap { $0 }.joined(separator: ", ")
        return "\(taf ?? "A taf") asks: \(what). \(call.reason). It lapses at \(call.expiresAt.shortTime)."
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

    /// How many calls the vendor refused today that still count toward the Card's totals,
    /// in a line, naming the vendor when every one is the same; nil for none.
    static func refusedLine(_ refused: [RecordEntry]) -> String? {
        guard !refused.isEmpty else { return nil }
        let vendors = Set(refused.compactMap(\.refusedBy))
        let who = vendors.count == 1 && refused.allSatisfy({ $0.refusedBy != nil }) ? vendors.first! : "the vendor"
        let calls = refused.count == 1 ? "1 call" : "\(refused.count) calls"
        return "\(calls) \(who) refused today still counted toward it: nothing moved, but they used room."
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
        guard seconds >= 1 else { return Text("expired") }
        if #available(iOS 16, macOS 13, *) {
            return Text(timerInterval: date...call.expiresAt, countsDown: true) + Text(" left")
        }
        return Text(String(format: "%d:%02d left", seconds / 60, seconds % 60))
    }
}

/// Offers alerts that reach a closed app: KIFF tells tafs cloud when a call is held, and
/// tafs cloud tells this phone. One Face ID turns it on for the account.
struct AlertsCard: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Panel {
            Text("Know the moment a taf needs you").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
            Text("Get an alert when a call waits for your answer, even with tafs closed.")
                .scaledFont(14).foregroundColor(Theme.soft)
            Button(model.turningOnAlerts ? "Turning on…" : "Turn on alerts") {
                Task { await model.turnOnAlerts() }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.turningOnAlerts)
        }
    }
}

/// Alerts offered up front, right after signing in (the 755 retest): why, in a few words,
/// then iOS's own question. Not now keeps tafs usable; Needs you offers it again.
struct AlertsOfferSheet: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "bell.badge").scaledFont(40, weight: .semibold).foregroundColor(Theme.amber)
                .accessibilityHidden(true)
            Text("Know the moment a taf needs you").scaledFont(24, weight: .bold, relativeTo: .title2).foregroundColor(Theme.ink)
            Text("When a taf asks for something outside its Card, it waits for your answer. An alert tells you at once, on this \(ThisDevice.kind) and its lock screen, even with tafs closed.")
                .scaledFont(16).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            Text("iOS asks next. Confirming with \(Biometry.name) sets them up for your account.")
                .scaledFont(14, relativeTo: .footnote).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
            if let error = model.lastError, !model.turningOnAlerts {
                Text(error).scaledFont(14, relativeTo: .footnote).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Button(model.turningOnAlerts ? "Turning on…" : "Turn on alerts") {
                Task { if await model.turnOnAlerts() { model.answerAlertsOffer() } }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.turningOnAlerts)
            Button("Not now") { model.answerAlertsOffer() }
                .scaledFont(15).foregroundColor(Theme.soft)
                .frame(maxWidth: .infinity, minHeight: 44)
                .disabled(model.turningOnAlerts)
        }
        .padding(24)
        .background(Theme.background.ignoresSafeArea())
        .interactiveDismissDisabled(model.turningOnAlerts)
    }
}
