import AppKit
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
                .accentColor(Theme.amber)
                .frame(minWidth: 760, minHeight: 500)
                .task { await model.restore() }
        }
        // tafs' own window, as the iPad's screen: no title bar over the warm background.
        .windowStyle(.hiddenTitleBar)
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
enum MacSelection: Hashable { case needsYou, record, coach, taf(UUID) }

/// The sidebar and the iPhone's own screen for what's chosen in it, drawn as the iPad draws
/// its sidebar (kiff/tafs#85): warm, each taf with its avatar, the calls that wait first.
/// The sidebar is the app's own, not macOS's: on macOS 12 a system sidebar can't take
/// tafs' background, and a column of links loses itself when a screen changes the window.
/// Answering a held call is on the iPhone: this Mac holds no key.
struct MacTafsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selection: MacSelection? = .needsYou

    var body: some View {
        // Two columns, so a screen's own links (a taf's Versions, Coach mode's sheets) push
        // in the detail. The sidebar is driven by its buttons, never by a link.
        NavigationView {
            sidebar.frame(minWidth: 250, idealWidth: 270)
            detail
                .id(selection)
                .frame(minWidth: 480, maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.background)
        }
        // Under the hidden title bar too: tafs' colours to the window's top edge.
        .ignoresSafeArea(edges: .top)
    }

    private var sidebar: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 10) {
                        Mascot(size: 30)
                        Text("tafs").font(Theme.display(24)).foregroundColor(Theme.ink)
                    }
                    .padding(.horizontal, 10).padding(.bottom, 12)
                    if !model.held.isEmpty {
                        Eyebrow(text: "Needs you", color: Theme.amber).padding(.horizontal, 10).padding(.top, 4)
                        ForEach(model.held) { call in
                            if let taf = model.taf(call.tafID) { item(.taf(taf.id)) { heldRow(call, taf) } }
                        }
                    }
                    item(.needsYou) { navRow("Needs you", "bell", count: model.held.count) }
                    item(.record) { navRow("Record", "list.bullet") }
                    Eyebrow(text: "Your tafs").padding(.horizontal, 10).padding(.top, 14)
                    ForEach(model.tafs) { taf in item(.taf(taf.id)) { tafRow(taf) } }
                    if model.listLoaded, !model.listUnread, model.tafs.isEmpty {
                        Text("Start one in Coach mode.").font(.system(size: 13)).foregroundColor(Theme.dim).padding(.horizontal, 10)
                    }
                    if !model.otherAgents.isEmpty {
                        // Agents made through KIFF's API, not run by tafs: their Cards, apart.
                        Eyebrow(text: "Other agents with Cards").padding(.horizontal, 10).padding(.top, 14)
                        ForEach(model.otherAgents) { taf in item(.taf(taf.id)) { tafRow(taf) } }
                    }
                    item(.coach) {
                        Label("Coach mode", systemImage: "sparkle")
                            .font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.coach)
                    }
                    .padding(.top, 14)
                }
                // Room for the window's buttons, which sit over the sidebar's top.
                .padding(.horizontal, 10).padding(.top, 40).padding(.bottom, 12)
            }
            footer
        }
        .background(Theme.raised)
    }

    /// A row the sidebar selects: a white card when chosen, as on the iPad.
    private func item<Content: View>(_ sel: MacSelection, @ViewBuilder _ content: () -> Content) -> some View {
        let chosen = selection == sel
        return Button { selection = sel } label: {
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 10).padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(chosen ? Theme.panel : Color.clear)
                        .shadow(color: chosen ? Theme.ink.opacity(0.08) : .clear, radius: 4, y: 1)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func navRow(_ title: String, _ symbol: String, count: Int = 0) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 14)).foregroundColor(Theme.soft).frame(width: 22)
            Text(title).font(.system(size: 15)).foregroundColor(Theme.ink)
            Spacer(minLength: 0)
            if count > 0 {
                Text("\(count)").font(.system(size: 12, weight: .bold)).foregroundColor(.white)
                    .padding(.horizontal, 7).padding(.vertical, 2)
                    .background(Theme.amber).clipShape(Capsule())
            }
        }
    }

    private func tafRow(_ taf: Taf) -> some View {
        HStack(spacing: 12) {
            TafAvatar(taf: taf, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(taf.name).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink).lineLimit(1)
                Text(taf.now).font(.system(size: 13)).foregroundColor(Theme.dim).lineLimit(1)
            }
        }
    }

    private func heldRow(_ call: HeldCall, _ taf: Taf) -> some View {
        HStack(spacing: 10) {
            Circle().fill(Theme.amber).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(taf.name): \(call.proposed.summary)").font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink).lineLimit(1)
                Text("\(call.reason) · answer by \(call.expiresAt.shortTime)").font(.system(size: 12)).foregroundColor(Theme.soft).lineLimit(1)
            }
        }
    }

    /// Which account, Settings and a refresh, at the foot as on the iPad.
    private var footer: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(MacSettings.accountName(model.kiffAccount))
                    .font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.ink).lineLimit(1)
                Text("Approvals go to your iPhone").font(.system(size: 11)).foregroundColor(Theme.dim).lineLimit(1)
            }
            Spacer(minLength: 0)
            Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }
                .buttonStyle(.borderless).help("Refresh").keyboardShortcut("r", modifiers: .command)
            Button { MacSettings.open() } label: { Image(systemName: "gearshape") }
                .buttonStyle(.borderless).help("Settings")
        }
        .foregroundColor(Theme.soft)
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Theme.raised)
        .overlay(alignment: .top) { Rectangle().fill(Theme.line).frame(height: 1) }
    }

    @ViewBuilder private var detail: some View {
        switch selection {
        // Below the window's title strip, which the colours run under.
        case .needsYou?: NeedsYouView().padding(.top, 28)
        case .record?: RecordView().padding(.top, 28)
        case .coach?: SetupChatView().padding(.top, 28)
        case .taf(let id)?: TafDetailView(tafID: id).padding(.top, 28)
        case nil:
            VStack(spacing: 12) {
                Mascot(size: 72)
                Text("Choose a taf").font(.system(size: 16)).foregroundColor(Theme.dim)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

enum MacSettings {
    /// The account as the owner knows it: its name when KIFF has one, else the email, never
    /// a raw id such as user_3KQ… (kiff/tafs#85).
    static func accountName(_ a: KIFFClient.Account?) -> String {
        guard let a else { return "Signed in with KIFF" }
        if let n = a.displayName, !n.isEmpty, n != a.slug { return n }
        if let e = a.email, !e.isEmpty { return e }
        return a.slug
    }

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
                line("KIFF account", MacSettings.accountName(account))
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
