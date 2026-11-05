import SwiftUI


/// Sign in with KIFF: the phone shows a code, the person approves it in KIFF
/// (on the web, signed in as themselves), and the phone receives a session.
struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var start: KIFFClient.DeviceStart?
    @State private var problem: String?
    @State private var task: Task<Void, Never>?
    @State private var copied = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Text("Sign in with KIFF").scaledFont(34, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                Text("Your tafs act under KIFF Cards. Approve this phone in KIFF, and the Cards you issued show up here.")
                    .scaledFont(15).foregroundColor(Theme.soft)

                if let start {
                    Panel(stroke: Theme.amber.opacity(0.4)) {
                        Eyebrow(text: "Your code")
                        Text(start.userCode)
                            .scaledFont(34, weight: .semibold, design: .monospaced)
                            .foregroundColor(Theme.ink)
                            .textSelection(.enabled)
                        Text("Check that KIFF shows the same code before you approve.")
                            .scaledFont(13).foregroundColor(Theme.dim)
                    }
                    Button("Open KIFF to approve") {
                        if let url = URL(string: start.verificationURIComplete) { openURL(url) }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    // To approve in another browser, where they're signed in to KIFF.
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Or open this link in another browser:").scaledFont(13).foregroundColor(Theme.dim)
                        HStack(spacing: 8) {
                            Text(start.verificationURIComplete)
                                .scaledFont(13, design: .monospaced).foregroundColor(Theme.soft)
                                .lineLimit(2).truncationMode(.middle)
                                .textSelection(.enabled)
                            Spacer(minLength: 0)
                            Button {
                                Clipboard.copy(start.verificationURIComplete)
                                copied = true
                            } label: {
                                Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                                    .labelStyle(.titleAndIcon).scaledFont(13, weight: .semibold)
                            }
                            .buttonStyle(.borderless).tint(Theme.amber)
                        }
                    }
                    HStack(spacing: 10) {
                        ProgressView().tint(Theme.amber)
                        Text("Waiting for your approval…").scaledFont(14).foregroundColor(Theme.soft)
                    }
                } else if problem == nil {
                    ProgressView().tint(Theme.amber)
                }

                if let problem {
                    Text(problem).scaledFont(14).foregroundColor(Theme.no)
                    Button("Try again") { begin() }.buttonStyle(OutlineButtonStyle())
                }
                Spacer()
                Text("By signing in, you agree to KIFF's [Terms](https://kiff.dev/terms#tafs), which cover tafs, and its [Privacy policy](https://kiff.dev/privacy#tafs).")
                    .scaledFont(12).foregroundColor(Theme.dim).tint(Theme.amber)
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
        // Back from approving in the browser: ask KIFF at once.
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.nudgeSignIn() }
        }
    }

    private func begin() {
        problem = nil
        start = nil
        copied = false
        task?.cancel()
        task = Task {
            do {
                let s = try await model.startSignIn()
                start = s
                try await model.finishSignIn(s, startedAt: Date())
                dismiss()
            } catch is CancellationError {
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}

/// The bar's Account tab: the plan, the account and its settings.
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var signingIn = false
    @State private var atEnd = false
    @State private var confirming: SignOutWay?
    /// `-screen alerts`: the screenshots open Alerts by themselves.
    @State private var openAlerts = Screenshots.enabled && Screenshots.screen == "alerts"

    var body: some View {
        NavigationStack {
            List {
                Section("Your account") {
                    LabeledContent("Mode", value: modeText)
                    if model.mode == .kiff, let account = model.kiffAccount {
                        NavigationLink { AccountView(account: account, done: { dismiss() }).environmentObject(model) } label: { LabeledContent("KIFF account", value: account.name) }
                        LabeledContent("Account id", value: account.slug)
                        if let email = account.email { LabeledContent("Signed in as", value: email) }
                    }
                }
                if model.mode == .kiff {
                    Section("People & apps") {
                        NavigationLink(PeopleGroupsView.title) { PeopleGroupsView() }
                        NavigationLink(DevicesView.title) { DevicesView() }
                        NavigationLink(ConnectAppsView.title) { ConnectAppsView() }
                    }
                    Section("Plan") {
                        NavigationLink { UsageView() } label: { PlanRow(usage: model.usage) }.accessibilityIdentifier("plan-card")
                    }
                }
                // What a new Coach conversation offers, as tafs cloud keeps it: its words say what the
                // switch does now. Enabled for the people who manage the account's tafs.
                if model.mode == .kiff, model.mayUseCoach, let shown = model.suggestions {
                    Section("Coach") {
                        Toggle(isOn: Binding(get: { shown.show }, set: { on in Task { await model.setSuggestions(on) } })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Show suggestions")
                                if !shown.words.isEmpty { Text(shown.words).scaledFont(13).foregroundColor(Theme.soft) }
                            }
                        }
                        .disabled(!model.canManageCards || model.suggestionsBusy)
                        .accessibilityIdentifier("show-suggestions")
                        if let why = model.suggestionsError { Text(why).scaledFont(13).foregroundColor(Theme.no) }
                    }
                }
                Section {
                    NavigationLink(NotificationSettingsView.title) { NotificationSettingsView() }
                }
                Section("Theme") { ThemePicker() }
                if model.mode == .kiff {
                    Section {
                        NavigationLink { TimeZonePicker() } label: { LabeledContent("Time zone", value: model.kiffAccount?.timeZone ?? "UTC") }
                    }
                    ExportChatsSection()
                }
                Section("How it works") {
                    Text("Every action a taf takes goes through KIFF, which checks its Card before anything reaches a tool. Your answers are signed on this \(ThisDevice.kind).")
                        .scaledFont(14).foregroundColor(Theme.soft)
                }
                Section("Legal") {
                    Link("Terms", destination: Legal.terms)
                    Link("Privacy policy", destination: Legal.privacy)
                }
                Section("About") {
                    LabeledContent("Version", value: Self.versionLabel())
                    Text("They taf. You kiff.").foregroundColor(Theme.dim)
                }
                Section("Account actions") {
                    if model.mode == .sample { Button("Sign in with KIFF") { signingIn = true } }
                    Button(model.mode == .kiff ? "Sign out" : "Clear the sample", role: .destructive) {
                        if model.mode == .kiff { confirming = .signOut }
                        else { Task { if await model.signOut() { dismiss() } } }
                    }
                    if let why = model.signOutError { Text(why).foregroundColor(Theme.no) }
                }
                Color.clear.frame(height: 1).listRowBackground(Color.clear).onAppear { atEnd = true }.onDisappear { atEnd = false }
            }
            .scrollContentBackground(.hidden)
            .screenBackground()
            .overlay(alignment: .bottom) {
                if !atEnd { Image(systemName: "chevron.down").foregroundColor(Theme.dim).padding(8).frame(maxWidth: .infinity).background(LinearGradient(colors: [.clear, Theme.background], startPoint: .top, endPoint: .bottom)).allowsHitTesting(false) }
            }
            .navigationTitle("Account")
            .task(id: model.mode) { await model.loadSuggestions() }
            .navigationDestination(isPresented: $openAlerts) { NotificationSettingsView().environmentObject(model) }
            .sheet(isPresented: $signingIn) { SignInView().environmentObject(model) }
            .confirmingSignOut($confirming) { _ in
                Task { if await model.signOut() { dismiss() } }
            }
        }
    }

    static func versionLabel(bundle: Bundle = .main) -> String {
        let version = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–"
        guard let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String, !build.isEmpty else { return version }
        return "\(version) (\(build))"
    }

    private var modeText: String {
        switch model.mode {
        case .kiff: return "Signed in with KIFF"
        case .sample: return "Sample data"
        case .signedOut: return "Signed out"
        }
    }
}

/// Settings' Plan card: the plan in one line, in its tone, and Plan & usage under it; just the
/// title until KIFF's usage is read.
struct PlanRow: View {
    let usage: KIFFClient.Usage?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let usage {
                Text(UsageView.planLine(usage)).scaledFont(16, weight: .semibold).foregroundColor(Self.color(UsageView.planTone(usage)))
                    .fixedSize(horizontal: false, vertical: true)
                Text(UsageView.title).scaledFont(13).foregroundColor(Theme.amberText)
            } else {
                Text(UsageView.title).foregroundColor(Theme.ink)
            }
        }
        .padding(.vertical, 2)
    }

    static func color(_ tone: UsageView.PlanTone) -> Color {
        switch tone {
        case .attention: return Theme.amberText
        case .out: return Theme.no
        case .calm: return Theme.ink
        }
    }
}

/// The KIFF account's details (kiff/tafs#206 6.9): its name, id, email and time zone, and at
/// the bottom, set apart, deleting it. Deleting stays in the app (App Store Review Guideline
/// 5.1.1(v)); it is never next to an everyday action.
struct AccountView: View {
    @EnvironmentObject private var model: AppModel
    let account: KIFFClient.Account
    /// Closes Settings, once the account is gone.
    let done: () -> Void
    @State private var deletingAccount = false

    var body: some View {
        List {
            Section {
                LabeledContent("KIFF account", value: account.name)
                if account.name != account.slug {
                    LabeledContent("Account id", value: account.slug)
                }
                if let email = account.email, !email.isEmpty {
                    LabeledContent("Signed in as", value: email)
                }
            }
            Section {
                // A Card's day ends at midnight here, and every time the owner reads is in it
                // (kiff/tafs#96, #206 6.11); set from the device as a signed setup call.
                NavigationLink { TimeZonePicker() } label: { LabeledContent("Time zone", value: zone) }
                if zone != TimeZone.current.identifier {
                    Button("Use this \(ThisDevice.kind)'s: \(TimeZone.current.identifier)") {
                        Task { await model.useThisDevicesTimeZone() }
                    }
                }
                if let why = model.timeZoneError {
                    Text(why).scaledFont(13).foregroundColor(Theme.no)
                }
            } footer: {
                Text("Holds' deadlines, Activity, the Record and what your tafs write use this time zone.")
            }
            // Their own copy, for their rights of access and portability (kiff/tafs#424).
            ExportChatsSection()
            if model.mayDeleteAccount { Section {
                Button("Delete account…", role: .destructive) { deletingAccount = true }
            } footer: {
                Text("Deletes every taf, its runs, chats and notes, your Cards and your connections. It can't be undone.")
            } }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle("KIFF account")
        .sheet(isPresented: $deletingAccount, onDismiss: { if model.mode != .kiff { done() } }) {
            DeleteAccountView().environmentObject(model)
        }
    }

    /// The account's time zone as KIFF holds it now.
    private var zone: String { model.kiffAccount?.timeZone ?? account.timeZone ?? "UTC" }
}
