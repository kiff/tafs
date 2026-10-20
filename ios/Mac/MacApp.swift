import SwiftUI

/// tafs on a Mac (kiff/tafs#85): the same model, clients and services as the iPhone app,
/// in a window of its own. It runs on macOS 12, the owner's Mac's last system.
///
/// A Mac without a Secure Enclave holds no signing key, so it signs in without one and
/// approves nothing itself: what needs a signature is done on the owner's iPhone, until
/// KIFF takes an authenticator code (kiff/kiff-cloud#1267).
@main
struct TafsMacApp: App {
    @StateObject private var model = AppModel(remembered: .standard)

    var body: some Scene {
        WindowGroup {
            MacRootView()
                .environmentObject(model)
                .preferredColorScheme(.light)
                .tint(Theme.amber)
                .frame(minWidth: 640, minHeight: 440)
                .task { await model.restore() }
        }
    }
}

struct MacRootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            if model.signedIn {
                MacTafsView()
            } else {
                MacSignInView()
            }
        }
        .background(Theme.background)
        .alert("Not done", isPresented: Binding(
            get: { model.lastError != nil },
            set: { if !$0 { model.lastError = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.lastError ?? "")
        }
    }
}

/// Signing in: KIFF shows a code, the owner approves it in their browser.
struct MacSignInView: View {
    @EnvironmentObject private var model: AppModel
    @State private var start: KIFFClient.DeviceStart?
    @State private var problem: String?
    @State private var task: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 16) {
            Text("tafs").font(.system(size: 34, weight: .semibold))
            if let start {
                Text("Approve this sign-in in your browser. Check the code matches:")
                    .foregroundColor(Theme.soft)
                Text(start.userCode).font(.system(size: 28, weight: .semibold, design: .monospaced))
                if let url = URL(string: start.verificationURIComplete) {
                    Link("Open KIFF to approve", destination: url)
                }
            } else if let problem {
                Text(problem).foregroundColor(Theme.soft).multilineTextAlignment(.center)
                Button("Try again", action: begin)
            } else {
                Button("Sign in with KIFF", action: begin).keyboardShortcut(.defaultAction)
            }
            Text("This Mac approves nothing itself: approvals go to your iPhone.")
                .font(.system(size: 12)).foregroundColor(Theme.dim)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                try await model.finishSignIn(s, startedAt: Date())
            } catch is CancellationError {
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}

/// What the sidebar opens.
enum MacSelection: Hashable { case needsYou, record, setup, taf(UUID) }

/// The sidebar, and the iPhone's own screen for what's chosen in it. Answering a held
/// call is on the iPhone: this Mac holds no key.
struct MacTafsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selection: MacSelection? = .needsYou

    var body: some View {
        NavigationView {
            List {
                NavigationLink(tag: MacSelection.needsYou, selection: $selection) {
                    NeedsYouView()
                } label: {
                    Label(model.held.isEmpty ? "Needs you" : "Needs you (\(model.held.count))", systemImage: "bell")
                }
                NavigationLink(tag: MacSelection.record, selection: $selection) {
                    RecordView()
                } label: {
                    Label("Record", systemImage: "list.bullet")
                }
                NavigationLink(tag: MacSelection.setup, selection: $selection) {
                    SetupChatView()
                } label: {
                    Label("Connected tools", systemImage: "puzzlepiece.extension")
                }
                Section("Tafs") {
                    ForEach(model.tafs) { taf in
                        NavigationLink(tag: MacSelection.taf(taf.id), selection: $selection) {
                            TafDetailView(tafID: taf.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(taf.name).font(.system(size: 13, weight: .semibold))
                                Text(taf.now).font(.system(size: 11)).foregroundColor(Theme.soft)
                            }
                        }
                    }
                }
            }
            .listStyle(.sidebar)
            .frame(minWidth: 220)

            Text("Choose a taf").foregroundColor(Theme.dim)
        }
        .toolbar {
            ToolbarItem {
                Button { Task { await model.refresh() } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
            }
        }
    }
}
