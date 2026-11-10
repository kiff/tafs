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
                    Text(Words.Settings.DeleteAccount.warning)
                        .scaledFont(15, weight: .semibold)
                }
                Section(Words.Settings.DeleteAccount.whatGoes) {
                    Label(Words.Settings.DeleteAccount.goesTafs, systemImage: "person.2")
                    Label(Words.Settings.DeleteAccount.goesSignIns, systemImage: "link")
                    Label(Words.Settings.DeleteAccount.goesCards, systemImage: "creditcard")
                    Label(Words.Settings.DeleteAccount.goesRecords, systemImage: "doc.text")
                }
                .scaledFont(14)
                if let why = model.deleteAccountError {
                    Section {
                        Text(why).scaledFont(14).foregroundColor(Theme.no)
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
                    .confirming()
                    .disabled(deleting)
                } footer: {
                    Text(Words.Settings.DeleteAccount.confirmWith(biometry: "\(Biometry.name)"))
                }
            }
            .hiddenScrollBackground()
            .screenBackground()
            .navigationTitle(Words.Settings.DeleteAccount.title)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { dismiss() }.disabled(deleting).keyboardShortcut(.cancelAction) } }
            .onAppear { model.deleteAccountError = nil }
        }
    }
}
