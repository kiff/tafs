import AppKit
import SwiftUI

/// tafs on a Mac (kiff/tafs#85): the same model, clients and services as the iPhone app,
/// in a window of its own. It runs on macOS 12, the owner's Mac's last system.
///
/// A Mac without a Secure Enclave holds no signing key, so it signs in without one and
/// approves nothing itself: what needs a signature is done on the owner's iPhone, until
/// KIFF takes an authenticator code.
@main
struct TafsMacApp: App {
    @StateObject private var model = AppModel(remembered: .standard)

    var body: some Scene {
        WindowGroup {
            MacRootView()
                .environmentObject(model)
                .preferredColorScheme(.light)
                .accentColor(Theme.amber)
                .frame(minWidth: 760, minHeight: 500)
                .task { model.purchases.start(); await model.restore() }
        }
        // ⌘, and the sidebar's Settings: the account, sign out, and what tafs is.
        Settings {
            MacSettingsView()
                .environmentObject(model)
                .preferredColorScheme(.light)
                .accentColor(Theme.amber)
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
        VStack(spacing: 18) {
            Mascot(size: 96)
            VStack(spacing: 6) {
                Text("tafs").font(Theme.display(40)).foregroundColor(Theme.ink)
                Eyebrow(text: "They taf. You kiff.")
            }
            if let start {
                Panel {
                    Text("Approve this sign-in in your browser. Check the code matches:")
                        .font(.system(size: 14)).foregroundColor(Theme.soft)
                    Text(start.userCode).font(.system(size: 30, weight: .semibold, design: .monospaced)).foregroundColor(Theme.ink)
                    if let url = URL(string: start.verificationURIComplete) {
                        Link("Open KIFF to approve", destination: url).font(.system(size: 14, weight: .semibold))
                    }
                }
                .frame(maxWidth: 420)
            } else {
                if let problem {
                    Text(problem).font(.system(size: 14)).foregroundColor(Theme.no).multilineTextAlignment(.center)
                }
                Button(problem == nil ? "Sign in with KIFF" : "Try again", action: begin)
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 320)
                    .keyboardShortcut(.defaultAction)
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

/// The sidebar, and the iPhone's own screen for what's chosen in it. The sidebar is a list
/// with a selection, and the detail reads it: no link drives the window, so nothing a screen
/// does can empty the sidebar (macOS 12, kiff/tafs#85). Answering a held call is on the
/// iPhone: this Mac holds no key.
struct MacTafsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selection: MacSelection? = .needsYou

    var body: some View {
        NavigationView {
            sidebar
            detail
                .id(selection)
                .frame(minWidth: 480, maxWidth: .infinity, maxHeight: .infinity)
        }
        .toolbar {
            ToolbarItem {
                Button { Task { await model.refresh() } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
            }
        }
    }

    private var sidebar: some View {
        VStack(spacing: 0) {
            List(selection: $selection) {
                Section {
                    row("Needs you", count: model.held.count, symbol: "bell").tag(MacSelection.needsYou)
                    row("Record", symbol: "list.bullet").tag(MacSelection.record)
                    row("Connected tools", symbol: "puzzlepiece.extension").tag(MacSelection.setup)
                }
                Section {
                    ForEach(model.tafs) { taf in
                        HStack(spacing: 10) {
                            Circle().fill(Theme.tafColors[taf.colorIndex % Theme.tafColors.count]).frame(width: 10, height: 10)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(taf.name).font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.ink)
                                Text(taf.now).font(.system(size: 11)).foregroundColor(Theme.soft).lineLimit(1)
                            }
                        }
                        .padding(.vertical, 2)
                        .tag(MacSelection.taf(taf.id))
                    }
                } header: { Eyebrow(text: "Your tafs") }
            }
            .listStyle(.sidebar)
            Divider()
            footer
        }
        .frame(minWidth: 230, idealWidth: 250)
    }

    private func row(_ title: String, count: Int = 0, symbol: String) -> some View {
        HStack {
            Label(title, systemImage: symbol).font(.system(size: 13)).foregroundColor(Theme.ink)
            Spacer()
            if count > 0 {
                Text("\(count)").font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                    .padding(.horizontal, 7).padding(.vertical, 2)
                    .background(Theme.amber).clipShape(Capsule())
            }
        }
    }

    /// Which account this is, and Settings (the iPhone's Settings, kiff/tafs#73).
    private var footer: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.kiffAccount?.name ?? "Signed in with KIFF")
                    .font(.system(size: 12, weight: .semibold)).foregroundColor(Theme.ink).lineLimit(1)
                if let email = model.kiffAccount?.email, !email.isEmpty {
                    Text(email).font(.system(size: 11)).foregroundColor(Theme.dim).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Button { MacSettings.open() } label: { Image(systemName: "gearshape") }
                .buttonStyle(.borderless)
                .help("Settings")
                .accessibilityLabel("Settings")
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }

    @ViewBuilder private var detail: some View {
        switch selection {
        case .needsYou?: NeedsYouView()
        case .record?: RecordView()
        case .setup?: SetupChatView()
        case .taf(let id)?: TafDetailView(tafID: id)
        case nil:
            VStack(spacing: 12) {
                Mascot(size: 72)
                Text("Choose a taf").font(.system(size: 16)).foregroundColor(Theme.dim)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background)
        }
    }
}

enum MacSettings {
    /// Opens the Settings window: macOS 12 names its action showPreferencesWindow:,
    /// macOS 13 showSettingsWindow:.
    static func open() {
        if #available(macOS 13, *) {
            NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
        } else {
            NSApp.sendAction(Selector(("showPreferencesWindow:")), to: nil, from: nil)
        }
    }
}

/// The account, as on the iPhone's Settings (kiff/tafs#73): which KIFF account, its time
/// zone, sign out; and what tafs is.
struct MacSettingsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Eyebrow(text: "Account")
            if model.mode == .kiff, let account = model.kiffAccount {
                line("KIFF account", account.name)
                if account.displayName.map({ !$0.isEmpty && $0 != account.slug }) ?? false { line("Account id", account.slug) }
                if let email = account.email, !email.isEmpty { line("Email", email) }
                line("Time zone", account.timeZone ?? "UTC")
                if (account.timeZone ?? "UTC") != TimeZone.current.identifier {
                    Button("Use this Mac's: \(TimeZone.current.identifier)") { Task { await model.useThisDevicesTimeZone() } }
                }
                if let why = model.timeZoneError { Text(why).font(.system(size: 12)).foregroundColor(Theme.no) }
            } else {
                line("Mode", model.signedIn ? "Signed in with KIFF" : "Signed out")
            }
            if model.signedIn {
                Button("Sign out of KIFF", role: .destructive) { Task { await model.signOut() } }
            }
            Divider()
            Eyebrow(text: "How it works")
            Text("Every action a taf takes goes through KIFF, which checks its Card before anything reaches a tool. This Mac approves nothing itself: your answers are signed on your iPhone.")
                .font(.system(size: 13)).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                Link("Terms", destination: Legal.terms)
                Link("Privacy policy", destination: Legal.privacy)
                Spacer()
                Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–")")
                    .font(.system(size: 12)).foregroundColor(Theme.dim)
            }
            .font(.system(size: 13))
        }
        .padding(24)
        .frame(width: 440)
        .background(Theme.background)
    }

    private func line(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.system(size: 13)).foregroundColor(Theme.soft)
            Spacer()
            Text(value).font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.ink).textSelection(.enabled)
        }
    }
}
