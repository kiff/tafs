import SwiftUI

/// A purchase's receipt in its Record line's sheet (docs/purchases.md), every word KIFF's, under this device's check of
/// KIFF's signature against the keys built into the app:
/// - **Checked:** KIFF's state is the headline ("Bought"), then its lines, then "checked on this iPhone".
/// - **Not checked** (a key this app doesn't hold, which is every receipt until KIFF's first key is pinned): that comes
///   first, and KIFF's words follow muted, never as a headline.
/// - **Changed, or another case's:** only that it doesn't check out, and why. Its words aren't drawn.
struct PurchaseReceiptPanel: View {
    @EnvironmentObject private var model: AppModel
    let caseID: String
    @State private var read: (receipt: PurchaseReceipt, check: PurchaseReceipt.Check)?
    @State private var problem: String?

    var body: some View {
        Group {
            if let read {
                switch read.check {
                case .verified:
                    Panel(stroke: Theme.ok.opacity(0.4)) {
                        Text(verbatim: read.receipt.words.state).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(verbatim: read.receipt.words.line).scaledFont(14).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        ForEach(read.receipt.words.lines, id: \.self) { line in
                            Text(verbatim: line).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        }
                        Label(Words.Receipt.checkedOnDevice(device: "\(ThisDevice.kind)"), systemImage: "checkmark.seal.fill")
                            .scaledFont(13, weight: .semibold).foregroundColor(Theme.ok)
                        Text(Words.Receipt.checkedWhy).scaledFont(12).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    }
                case .unknownKey:
                    Panel {
                        Text(Words.Receipt.Check.unknownSignature).scaledFont(13, weight: .semibold).foregroundColor(Theme.soft)
                            .fixedSize(horizontal: false, vertical: true)
                        ForEach([read.receipt.words.state, read.receipt.words.line] + read.receipt.words.lines, id: \.self) { line in
                            Text(verbatim: line).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                case .changed, .otherCase:
                    Panel {
                        Text(Words.Receipt.doesntCheckOut).scaledFont(13, weight: .semibold).foregroundColor(Theme.no)
                        Text(Words.Receipt.changed).scaledFont(12).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    }
                }
            } else if model.mode == .kiff {
                Panel {
                    Text(problem ?? Words.Receipt.checkingOnDevice(device: "\(ThisDevice.kind)"))
                        .scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .accessibilityIdentifier("purchase-receipt")
        .task(id: caseID) { await load() }
    }

    private func load() async {
        guard model.mode == .kiff else { return }
        do {
            let data = try await model.kiff.purchaseReceipt(caseID: caseID)
            guard let receipt = PurchaseReceipt.read(data) else {
                problem = Words.Receipt.Check.unreadable
                return
            }
            read = (receipt, receipt.check(caseID: caseID))
        } catch {
            problem = Words.Receipt.notLoaded(why: "\(error.localizedDescription)")
        }
    }
}
