import SwiftUI

struct NeedsYouView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Needs you").font(Theme.display(34)).foregroundColor(Theme.ink)
                if model.mode == .kiff, model.sessionHasKey, model.canAnswerHolds != false, model.alertsReady != true {
                    AlertsCard()
                }
                if model.held.isEmpty {
                    Panel {
                        Text("Nothing is waiting.").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                        Text("When a taf asks for something outside its Card, it shows up here and on your lock screen.")
                            .font(.system(size: 14)).foregroundColor(Theme.soft)
                    }
                } else {
                    ForEach(model.held) { call in HeldCallCard(call: call) }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .coachClearance()
        .refreshable { await model.refresh() }
        .screenBackground()
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
            HStack(spacing: 10) {
                if let taf { TafAvatar(taf: taf, size: 32) }
                Text(taf?.name ?? "A taf").font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink)
                Spacer()
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(remaining(at: context.date))
                        .font(Theme.mono(12))
                        .foregroundColor(Theme.amber)
                }
            }
            if let amount = call.proposed.amount {
                Text(amount.formatted).font(Theme.display(44)).foregroundColor(Theme.ink)
            }
            Text(call.proposed.summary).font(.system(size: 16, weight: .semibold)).foregroundColor(Theme.ink)
            if !call.proposed.context.isEmpty {
                Text(call.proposed.context).font(.system(size: 14)).foregroundColor(Theme.soft)
            }
            // What it's on, as KIFF keeps the call (kiff/tafs#102): the payment, not a tool-call id.
            ForEach(call.details ?? [], id: \.self) { line in
                Text(line).font(Theme.mono(12)).foregroundColor(Theme.soft).textSelection(.enabled)
            }
            Text(call.reason)
                .font(.system(size: 12, weight: .medium))
                .padding(.horizontal, 10).padding(.vertical, 5)
                .foregroundColor(Theme.amber)
                .background(Theme.amber.opacity(0.14))
                .clipShape(Capsule())

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
                    .font(.system(size: 13)).foregroundColor(Theme.soft)
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

    private func send(_ answer: Answer) {
        sending = true
        Task {
            await model.answer(call, with: answer)
            sending = false
        }
    }

    private func remaining(at date: Date) -> String {
        let seconds = max(0, Int(call.expiresAt.timeIntervalSince(date)))
        return seconds == 0 ? "expired" : String(format: "%d:%02d left", seconds / 60, seconds % 60)
    }
}

/// Offers alerts that reach a closed app: KIFF tells tafs cloud when a call is held, and
/// tafs cloud tells this phone. One Face ID turns it on for the account.
struct AlertsCard: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Panel {
            Text("Know the moment a taf needs you").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
            Text("Get an alert when a call waits for your answer, even with tafs closed.")
                .font(.system(size: 14)).foregroundColor(Theme.soft)
            Button(model.turningOnAlerts ? "Turning on…" : "Turn on alerts") {
                Task { await model.turnOnAlerts() }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.turningOnAlerts)
        }
    }
}
