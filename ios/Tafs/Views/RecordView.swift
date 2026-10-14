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

    var body: some View {
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

    private var color: Color {
        switch entry.kind {
        case .done, .approved, .started: return Theme.ok
        case .held, .unknown: return Theme.amber
        case .refused, .revoked: return Theme.no
        }
    }
}
