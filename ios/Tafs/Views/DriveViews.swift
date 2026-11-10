import SwiftUI

/// Coach asks to drive the app (docs/drive.md): an approval prompt that names the request. Approve
/// is signed with the approval key behind Face ID or Touch ID; Not now declines it. Each request is
/// approved on its own.
struct DriveCard: View {
    @EnvironmentObject private var model: AppModel
    let shown: ShownComponent
    @State private var busy = false

    var body: some View {
        let c = shown.component
        let task = c.text ?? c.title ?? "Drive the app"
        if let answer = model.setupThread.answer(to: c.id) {
            OutcomeLine(text: answer.text == "declined" ? "Not now: Coach didn't drive the app" : "Coach drove the app · \(task)",
                        tone: answer.text == "declined" ? Theme.dim : Theme.ok, mark: answer.text == "declined" ? "minus.circle.fill" : "checkmark.circle.fill")
        } else if model.isAnswered(c) {
            OutcomeLine(text: "Coach drove the app · \(task)", tone: Theme.ok)
        } else {
            Panel(stroke: Theme.amber) {
                Eyebrow(text: "Needs you · let Coach drive", color: Theme.amber)
                Text(task).scaledFont(20, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Coach taps, types and scrolls in this app while you watch. It never confirms anything. Touch the app or tap Stop to take it back.")
                    .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 10) {
                    Button(Words.Common.notNow) {
                        Task { await model.answer(c, "declined") }
                    }
                    .buttonStyle(OutlineButtonStyle())
                    ApprovalGate {
                        Button(busy ? Words.Approval.approving : "Approve with \(Biometry.name)") {
                            busy = true
                            Task { await model.approveDrive(c); busy = false }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                }
                .disabled(busy)
                CardNote(id: c.id)
            }
        }
    }
}

/// "What I did": a drive's end in Coach's thread, its steps from tafs cloud's record and why it ended.
struct DriveRecapCard: View {
    let recap: TafsCloud.DriveRecap

    var body: some View {
        Panel {
            Eyebrow(text: Words.Drive.whatIDid, color: Theme.amber)
            Text(recap.task).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            if recap.steps.isEmpty {
                Text("No step was done.").scaledFont(14).foregroundColor(Theme.soft)
            }
            ForEach(Array(recap.steps.enumerated()), id: \.offset) { _, step in
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Image(systemName: "circle.fill").font(.system(size: 5)).foregroundColor(Theme.dim)
                    Text(step.touched.map { "\(step.line) · \($0.words)" + ($0.taf.map { " · \($0)" } ?? "") } ?? step.line)
                        .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
            }
            Text(words).scaledFont(13).foregroundColor(Theme.soft)
        }
    }

    private var words: String {
        if let w = recap.words, !w.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return DriveSession.sentence(w) }
        return recap.why.flatMap { DriveLines.ended[$0] } ?? Words.Drive.Ended.other
    }
}
