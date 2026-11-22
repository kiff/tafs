import SwiftUI

/// "Report this payment" (docs/record.md): the sheet a payment's receipt opens when tafs cloud offers it, for a payment the
/// person didn't expect. One tap reports it: KIFF marks its receipt and its taf is halted, as the halt button does. It takes
/// no note and no reason (the approved text collects none), and no Face ID, since it only takes authority away; resuming the
/// taf stays behind the phone. The words are the app's (`record.report.*`), filled in with the entry's own taf, amount and date.
struct ReportPaymentSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let entry: RecordEntry
    /// Called once tafs cloud has answered, so the receipt takes its button away.
    var reported: () -> Void = {}

    enum Phase: Equatable { case asking, sending, done, failed }
    @State private var phase = Phase.asking

    private var tafName: String { model.taf(entry.tafID)?.name ?? "–" }

    /// "Report a payment you didn't expect: {taf} paid {amount} on {date}. We halt {taf} until you've looked."
    var intro: String {
        Words.Record.Report.intro(taf: tafName, amount: entry.amountText ?? "",
                                  date: (entry.reportMadeAt ?? entry.at).zonedShown(date: .long, time: .omitted))
    }

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(Words.Record.Report.title).scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    if phase == .done {
                        Text(Words.Record.Report.done(taf: tafName)).scaledFont(16).foregroundColor(Theme.ink)
                        Button { dismiss() } label: { Text(Words.Common.done).frame(maxWidth: .infinity) }
                            .buttonStyle(PrimaryButtonStyle())
                    } else {
                        Text(intro).scaledFont(16).foregroundColor(Theme.ink)
                        if phase == .failed {
                            Text(Words.Record.Report.failed).scaledFont(14, weight: .semibold).foregroundColor(Theme.no)
                        }
                        Button { Task { await send() } } label: {
                            Text(Words.Record.Report.submit(taf: tafName)).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(phase == .sending)
                        Button { dismiss() } label: { Text(Words.Common.cancel).frame(maxWidth: .infinity) }
                            .buttonStyle(OutlineButtonStyle())
                    }
                }
                .padding(20)
            }
            .screenBackground()
        }
    }

    private func send() async {
        guard phase != .sending else { return }
        phase = .sending
        if await model.reportPayment(entry) {
            phase = .done
            reported()
        } else {
            phase = .failed
        }
    }
}
