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
                if model.held.isEmpty {
                    Panel {
                        Text("Nothing is waiting.").scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text("When a taf asks for something outside its Card, it shows up here and on your lock screen.")
                            .scaledFont(14).foregroundColor(Theme.soft)
                    }
                } else {
                    ForEach(model.held) { call in HeldCallCard(call: call).id(call.id) }
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
            HStack(spacing: 10) {
                if let taf { TafAvatar(taf: taf, size: 32) }
                Text(taf?.name ?? "A taf").scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                Spacer()
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(remaining(at: context.date))
                        .scaledFont(12, design: .monospaced)
                        .foregroundColor(Theme.amber)
                }
            }
            if let amount = call.proposed.amount {
                Text(amount.formatted).scaledFont(44, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
            }
            Text(call.proposed.summary).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
            if !call.proposed.context.isEmpty {
                Text(call.proposed.context).scaledFont(14).foregroundColor(Theme.soft)
            }
            // What it's on, as KIFF keeps the call (kiff/tafs#102): the payment, not a tool-call id.
            ForEach(call.details ?? [], id: \.self) { line in
                Text(line).scaledFont(12, design: .monospaced).foregroundColor(Theme.soft).textSelection(.enabled)
            }
            Text(call.reason)
                .scaledFont(12, weight: .medium)
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
                    // Over a total the Card keeps across calls: approving once can't pass it, so
                    // the card says so and offers what can (kiff/tafs#138, item 49).
                    Text("Approving it once can't pass this: it's over a total the Card allows across calls, such as a day's. Refuse it, or change the Card's limit.")
                        .scaledFont(13).foregroundColor(Theme.soft)
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

    private func takeRequest() {
        guard let request = model.requestedHold, request.kiffID == call.kiffID, !sending else { return }
        model.requestedHold = nil
        guard model.canAnswer(call) else { return }
        switch request.answer {
        case AlertAnswers.approve? where call.approvableOnce:
            if call.install != nil { install() } else { send(.approveOnce) }
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
