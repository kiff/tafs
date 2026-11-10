import SwiftUI

/// Shows the complete server-supplied filters before the phone signs a subscription.
struct AppEventApprovalSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let item: TafsCloud.Knob.Item
    var approve: () async -> Bool
    @State private var busy = false

    private var filters: String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        guard let config = item.approval?.config, let bytes = try? encoder.encode(config) else { return "" }
        return String(decoding: bytes, as: UTF8.self)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(item.label).scaledFont(20, weight: .bold).foregroundColor(Theme.ink)
                    Text(item.words).scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("Listen only for events matching these filters.")
                        .scaledFont(14).foregroundColor(Theme.ink)
                    Text(filters).scaledFont(13, design: .monospaced).foregroundColor(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("event.filters")
                        // An open approval's terms: blanked for a drive (docs/drive.md).
                        .driveTerms(item.label)
                    if let error = model.lastError {
                        Text(error).scaledFont(13).foregroundColor(Theme.no)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    ApprovalGate {
                        Button(busy ? Words.Approval.approving : "Approve with " + Biometry.name) {
                            busy = true
                            Task {
                                defer { busy = false }
                                if await approve() { dismiss() }
                            }
                        }
                        .buttonStyle(PrimaryButtonStyle()).disabled(busy)
                        .accessibilityIdentifier("event.approve")
                    }
                }.padding(20)
            }
            .navigationTitle("Approve event").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { dismiss() }.disabled(busy) } }
        }
        .interactiveDismissDisabled(busy)
    }
}
