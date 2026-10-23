import SwiftUI

/// Deleting the account (kiff/tafs#185, App Store Review Guideline 5.1.1(v)): its own sheet,
/// opened from Settings. It says what goes, then deletes everything behind Face ID:
/// tafs cloud and kiff/agents, planque, then KIFF, and signs out.
struct DeleteAccountView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var deleting = false

    var body: some View {
        NavStack {
            List {
                Section {
                    Text("This deletes your KIFF account and everything tafs keeps for it. It can't be undone.")
                        .font(.system(size: 15, weight: .semibold))
                }
                Section("What goes") {
                    Label("Every taf, with its runs, chats, notes and schedules", systemImage: "person.2")
                    Label("Your tools' sign-ins, disconnected", systemImage: "link")
                    Label("Your Cards and keys: they stop working", systemImage: "creditcard")
                    Label("KIFF's records of your account, erased after 30 days", systemImage: "doc.text")
                }
                .font(.system(size: 14))
                if let why = model.deleteAccountError {
                    Section {
                        Text(why).font(.system(size: 14)).foregroundColor(Theme.no)
                    }
                }
                Section {
                    Button(role: .destructive) {
                        deleting = true
                        Task {
                            let done = await model.deleteAccount()
                            deleting = false
                            if done { dismiss() }
                        }
                    } label: {
                        HStack {
                            Text(model.deleteAccountError == nil ? "Delete account" : "Try again")
                            if deleting {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(deleting)
                } footer: {
                    Text("You confirm with \(Biometry.name) before anything is deleted. Signed in elsewhere, you're signed out there too.")
                }
            }
            .hiddenScrollBackground()
            .screenBackground()
            .navigationTitle("Delete account")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(deleting) } }
            .onAppear { model.deleteAccountError = nil }
        }
    }
}
