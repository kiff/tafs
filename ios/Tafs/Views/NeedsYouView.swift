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
                        send(.approveOnce)
                    } label: {
                        Label("Approve once with \(Biometry.name)", systemImage: Biometry.symbol)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(sending)
                } else {
                    // Over a total the Card keeps across calls: approving once can't pass it, so
                    // the card says so and offers what can (kiff/tafs#138, item 49).
                    Text("Approving it once can't pass this: it's over a total the Card allows across calls, such as a day's. Refuse it, or change the Card's limit.")
                        .font(.system(size: 13)).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    Button {
                        model.improveTaf(call.tafID)
                        model.coachOpen = true
                    } label: {
                        Label("Change the limit in Coach mode", systemImage: "sparkle")
                    }
                    .buttonStyle(PrimaryButtonStyle())
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
