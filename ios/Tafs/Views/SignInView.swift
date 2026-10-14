import SwiftUI

/// Sign in with KIFF: the phone shows a code, the person approves it in KIFF
/// (on the web, signed in as themselves), and the phone receives a session.
struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var start: KIFFClient.DeviceStart?
    @State private var problem: String?
    @State private var task: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Text("Sign in with KIFF").font(Theme.display(34)).foregroundColor(Theme.ink)
                Text("Your tafs act under KIFF Cards. Approve this phone in KIFF, and the Cards you issued show up here.")
                    .font(.system(size: 15)).foregroundColor(Theme.soft)

                if let start {
                    Panel(stroke: Theme.amber.opacity(0.4)) {
                        Eyebrow(text: "Your code")
                        Text(start.userCode)
                            .font(Theme.mono(34, weight: .semibold))
                            .foregroundColor(Theme.ink)
                            .textSelection(.enabled)
                        Text("Check that KIFF shows the same code before you approve.")
                            .font(.system(size: 13)).foregroundColor(Theme.dim)
                    }
                    Button("Open KIFF to approve") {
                        if let url = URL(string: start.verificationURIComplete) { openURL(url) }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    HStack(spacing: 10) {
                        ProgressView().tint(Theme.amber)
                        Text("Waiting for your approval…").font(.system(size: 14)).foregroundColor(Theme.soft)
                    }
                } else if problem == nil {
                    ProgressView().tint(Theme.amber)
                }

                if let problem {
                    Text(problem).font(.system(size: 14)).foregroundColor(Theme.no)
                    Button("Try again") { begin() }.buttonStyle(OutlineButtonStyle())
                }
                Spacer()
            }
            .padding(24)
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { task?.cancel(); dismiss() }
                }
            }
        }
        .onAppear(perform: begin)
        .onDisappear { task?.cancel() }
    }

    private func begin() {
        problem = nil
        start = nil
        task?.cancel()
        task = Task {
            do {
                let s = try await model.startSignIn()
                start = s
                try await model.finishSignIn(s)
                dismiss()
            } catch is CancellationError {
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var signingIn = false

    var body: some View {
        NavigationStack {
            List {
                Section("Account") {
                    LabeledContent("Mode", value: modeText)
                    if model.mode == .sample {
                        Button("Sign in with KIFF") { signingIn = true }
                    }
                    Button(model.mode == .kiff ? "Sign out of KIFF" : "Clear the sample", role: .destructive) {
                        Task { await model.signOut(); dismiss() }
                    }
                }
                Section("How it works") {
                    Text("Every action a taf takes goes through KIFF, which checks its Card before anything reaches a tool. Your answers are signed on this iPhone.")
                        .font(.system(size: 14)).foregroundColor(Theme.soft)
                }
                Section("About") {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–")
                    Text("They taf. You kiff.").foregroundColor(Theme.dim)
                }
            }
            .scrollContentBackground(.hidden)
            .screenBackground()
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $signingIn) { SignInView().environmentObject(model) }
        }
    }

    private var modeText: String {
        switch model.mode {
        case .kiff: return "Signed in with KIFF"
        case .sample: return "Sample data"
        case .signedOut: return "Signed out"
        }
    }
}
