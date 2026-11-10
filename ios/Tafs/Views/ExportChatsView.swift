import SwiftUI
import UIKit

/// Export my chats (kiff/tafs#424): the person's own chats, trials and notes, for their
/// rights of access and portability, as two files through the system's share and save
/// sheet: `tafs-chats.json` and `tafs-chats.txt`.
struct ExportChatsSection: View {
    @EnvironmentObject private var model: AppModel
    @State private var preparing = false
    @State private var why: String?
    @State private var files: SharedFiles?
    /// The files the last export made, to name them and share them again.
    @State private var made: SharedFiles?

    var body: some View {
        Section {
            Button {
                export()
            } label: {
                if preparing {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(Words.Export.preparing).foregroundColor(Theme.dim)
                    }
                } else {
                    Text(why == nil ? Words.Export.row : Words.Export.tryAgain)
                }
            }
            .disabled(preparing)
            .accessibilityIdentifier("export-chats")
            // On the button, which stays put: on the Section, the row naming the files
            // appearing with it took the sheet away before it showed.
            .sheet(item: $files) { ShareSheet(items: $0.urls).ignoresSafeArea() }
            if let why {
                Text(why).scaledFont(13).foregroundColor(Theme.no).accessibilityIdentifier("export-failed")
            } else if let made, !preparing {
                Button {
                    files = made
                } label: {
                    Text(Words.Export.readyShareAgain(files: made.urls.map(\.lastPathComponent).joined(separator: ", ")))
                        .scaledFont(13).foregroundColor(Theme.dim)
                }
                .accessibilityIdentifier("export-files")
            }
        } footer: {
            Text(Words.Export.ownChatsEachTafCoach)
        }
    }

    private func export() {
        guard !preparing else { return }
        preparing = true
        why = nil
        Task {
            let result = await model.exportChats()
            preparing = false
            if let urls = result.files {
                made = SharedFiles(urls: urls)
                files = made
            } else {
                why = result.why
            }
        }
    }
}

/// Files handed to the share sheet.
struct SharedFiles: Identifiable {
    let urls: [URL]
    var id: String { urls.map(\.lastPathComponent).joined(separator: ",") }
}

/// The system's share and save sheet, with files.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [URL]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
