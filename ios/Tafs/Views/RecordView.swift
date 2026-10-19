import SwiftUI

struct RecordView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("Record").font(Theme.display(34)).foregroundColor(Theme.ink)
                Text("Everything your tafs did, were refused, or asked you. Newest first.")
                    .font(.system(size: 14)).foregroundColor(Theme.soft)
                ForEach(model.record) { RecordRow(entry: $0, showTaf: true) }
            }
            // The full width on an iPad, as the other tabs (kiff/tafs#96).
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
    }
}

struct RecordRow: View {
    @EnvironmentObject private var model: AppModel
    let entry: RecordEntry
    var showTaf = false
    @State private var showingReceipt = false

    var body: some View {
        Button { showingReceipt = true } label: { row }
            .buttonStyle(.plain)
            .sheet(isPresented: $showingReceipt) { ReceiptView(entry: entry).environmentObject(model) }
            .accessibilityHint("Shows the receipt")
    }

    private var row: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle().fill(color).frame(width: 9, height: 9).padding(.top, 6).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(entry.title).font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink)
                    Spacer()
                    Text(entry.at.shortTime).font(Theme.mono(12)).foregroundColor(Theme.dim)
                }
                Text(detail).font(.system(size: 13)).foregroundColor(Theme.soft)
                if entry.signature != nil {
                    Label("Signed on this iPhone", systemImage: "checkmark.seal")
                        .font(.system(size: 12)).foregroundColor(Theme.ok)
                }
            }
        }
        .padding(14)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }

    private var detail: String {
        guard showTaf, let taf = model.taf(entry.tafID) else { return entry.detail }
        return "\(taf.name) · \(entry.detail)"
    }

    private var color: Color { entry.kind.color }
}

extension RecordEntry.Kind {
    var color: Color {
        switch self {
        case .done, .approved, .started: return Theme.ok
        case .held, .unknown: return Theme.amber
        case .refused, .revoked: return Theme.no
        }
    }

    var label: String {
        switch self {
        case .done: return "Done within the Card"
        case .approved: return "Approved once by you"
        case .refused: return "Refused"
        case .held: return "Held for you"
        case .unknown: return "Outcome unknown"
        case .revoked: return "Card revoked"
        case .started: return "Taf started"
        }
    }
}

/// One entry of the Record, as a receipt: what happened, when, and who answered.
struct ReceiptView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let entry: RecordEntry

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 8) {
                        Circle().fill(entry.kind.color).frame(width: 10, height: 10)
                        Text(entry.kind.label).font(.system(size: 14, weight: .semibold)).foregroundColor(entry.kind.color)
                    }
                    Text(entry.title).font(Theme.display(30)).foregroundColor(Theme.ink)
                    Panel {
                        line("Taf", model.taf(entry.tafID)?.name ?? "–")
                        line("When", entry.at.formatted(date: .abbreviated, time: .standard))
                        line("What", entry.detail)
                        line("Decided by", decidedBy)
                    }
                    if let signature = entry.signature {
                        Panel(stroke: Theme.ok.opacity(0.4)) {
                            Label("Signed on this iPhone", systemImage: "checkmark.seal.fill")
                                .font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ok)
                            Text("Your answer was signed with a key that never leaves this device, after \(Biometry.name).")
                                .font(.system(size: 13)).foregroundColor(Theme.soft)
                            Text(signature)
                                .font(Theme.mono(11)).foregroundColor(Theme.dim)
                                .lineLimit(3).textSelection(.enabled)
                        }
                    }
                    if model.mode == .kiff {
                        Text("KIFF keeps the signed receipt for every decision. Open it in KIFF to share it.")
                            .font(.system(size: 13)).foregroundColor(Theme.dim)
                    }
                }
                .padding(20)
            }
            .screenBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private var decidedBy: String {
        switch entry.kind {
        case .approved: return "You, approving once"
        case .refused where entry.signature != nil: return "You"
        case .revoked: return "You, revoking the Card"
        case .started: return "You, issuing the Card"
        default: return model.mode == .kiff ? "KIFF, against the Card" : "The Card"
        }
    }

    private func line(_ name: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(name).font(.system(size: 13)).foregroundColor(Theme.dim).frame(width: 90, alignment: .leading)
            Text(value).font(.system(size: 14)).foregroundColor(Theme.ink)
            Spacer(minLength: 0)
        }
    }
}
