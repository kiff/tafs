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
                Text(Words.SignIn.withKiff).scaledFont(34, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                Text(Words.SignIn.lead(device: "\(ThisDevice.kind)"))
                    .scaledFont(15).foregroundColor(Theme.soft)

                if let start {
                    Panel(stroke: Theme.amber.opacity(0.4)) {
                        Eyebrow(text: Words.SignIn.yourCode)
                        Text(start.userCode)
                            .scaledFont(34, weight: .semibold, design: .monospaced)
                            .foregroundColor(Theme.ink)
                            .textSelection(.enabled)
                        Text(Words.SignIn.checkCode)
                            .scaledFont(13).foregroundColor(Theme.dim)
                    }
                    Button(Words.SignIn.openKiff) {
                        if let url = URL(string: start.verificationURIComplete) { openURL(url) }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    // To approve in another browser, where they're signed in to KIFF.
                    VStack(alignment: .leading, spacing: 6) {
                        Text(Words.SignIn.otherBrowser).scaledFont(13).foregroundColor(Theme.dim)
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
                                Label(copied ? Words.Common.copied : Words.Common.copy, systemImage: copied ? "checkmark" : "doc.on.doc")
                                    .labelStyle(.titleAndIcon).scaledFont(13, weight: .semibold)
                            }
                            .buttonStyle(.borderless).tint(Theme.amber)
                        }
                    }
                    HStack(spacing: 10) {
                        ProgressView().tint(Theme.amber)
                        Text(Words.SignIn.waiting).scaledFont(14).foregroundColor(Theme.soft)
                    }
                } else if problem == nil {
                    ProgressView().tint(Theme.amber)
                }

                if let problem {
                    Text(problem).scaledFont(14).foregroundColor(Theme.no)
                    Button(Words.Common.tryAgain) { begin() }.buttonStyle(OutlineButtonStyle())
                }
                Spacer()
                Text(Legal.agreement())
                    .scaledFont(12).foregroundColor(Theme.dim).tint(Theme.amber)
            }
            .padding(24)
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Words.Common.cancel) { task?.cancel(); dismiss() }
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
                Section(Words.Settings.yourAccount) {
                    LabeledContent(Words.Settings.mode, value: modeText)
                    if model.mode == .kiff, let account = model.kiffAccount {
                        NavigationLink { AccountView(account: account, done: { dismiss() }).environmentObject(model) } label: { LabeledContent(Words.Settings.kiffAccount, value: account.name) }
                        // Who is signed in, said once: an account named after its owner's email
                        // (a Solo account) has no second line. Its id is on the account's own screen.
                        if let email = AccountLines.signedInAs(account) { LabeledContent(Words.Settings.signedInAs, value: email) }
                    }
                }
                if model.mode == .kiff {
                    Section(Words.Settings.peopleApps) {
                        NavigationLink(PeopleGroupsView.title) { PeopleGroupsView() }
                        NavigationLink(DevicesView.title) { DevicesView() }
                        NavigationLink(ConnectAppsView.title) { ConnectAppsView() }
                    }
                    Section(Words.Settings.plan) {
                        NavigationLink { UsageView() } label: { PlanRow(balance: model.balance) }.accessibilityIdentifier("plan-card")
                    }
                }
                // What a new Coach conversation offers, as tafs cloud keeps it: its words say what the
                // switch does now. Enabled for the people who manage the account's tafs.
                if model.mode == .kiff, model.mayUseCoach, let shown = model.suggestions {
                    Section(Words.Coach.title) {
                        Toggle(isOn: Binding(get: { shown.show }, set: { on in Task { await model.setSuggestions(on) } })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Words.Settings.showSuggestions)
                                if !shown.words.isEmpty { Text(shown.words).scaledFont(13).foregroundColor(Theme.soft) }
                            }
                        }
                        .disabled(!model.canManageCards || model.suggestionsBusy)
                        .drivable(Words.Settings.showSuggestions, role: .toggle, id: "show-suggestions") { Task { await model.setSuggestions(!shown.show) } }
                        .accessibilityIdentifier("show-suggestions")
                        if let why = model.suggestionsError { Text(why).scaledFont(13).foregroundColor(Theme.no) }
                    }
                }
                Section {
                    NavigationLink(NotificationSettingsView.title) { NotificationSettingsView() }
                }
                Section(Words.Settings.themeTitle) { ThemePicker() }
                // The language tafs's lines are in, by its own name, and iOS's own setting to change
                // it (Settings › tafs › Language). No picker of the app's own, as on Android.
                Section(Words.Settings.languageTitle) {
                    LabeledContent(Words.Settings.languageTitle, value: AppLanguage.name())
                        .accessibilityIdentifier("settings.language")
                    Button(Words.Settings.changeLanguage) { openTafsSettings() }
                        .foregroundColor(Theme.amberText)
                }
                // The AI-data consent: given, with Withdraw; not given, its screen. tafs cloud's words.
                if model.mode == .kiff, model.aiConsent?.drawable == true {
                    Section { AIConsentRow() }
                }
                if model.mode == .kiff {
                    Section {
                        NavigationLink { TimeZonePicker() } label: { LabeledContent(Words.Settings.timeZoneTitle, value: model.kiffAccount?.timeZone ?? "UTC") }
                    }
                    ExportChatsSection()
                }
                Section(Words.SignIn.howWorks) {
                    Text(Words.SignIn.everyActionTafTakesGoes(device: "\(ThisDevice.kind)"))
                        .scaledFont(14).foregroundColor(Theme.soft)
                }
                Section(Words.Settings.legal) {
                    Link(Words.Settings.terms, destination: Legal.terms)
                    Link(Words.Settings.privacyPolicy, destination: Legal.privacy)
                }
                Section(Words.Settings.about) {
                    LabeledContent(Words.Settings.version, value: Self.versionLabel())
                    Text(Words.Home.tagline).foregroundColor(Theme.dim)
                }
                Section(Words.Settings.accountActions) {
                    if model.mode == .sample { Button(Words.SignIn.withKiff) { signingIn = true } }
                    Button(model.mode == .kiff ? Words.Settings.signOut : Words.Settings.clearSample, role: .destructive) {
                        if model.mode == .kiff { confirming = .signOut }
                        else { Task { if await model.signOut() { dismiss() } } }
                    }
                    .confirming()
                    if let why = model.signOutError { Text(why).foregroundColor(Theme.no) }
                }
                Color.clear.frame(height: 1).listRowBackground(Color.clear).onAppear { atEnd = true }.onDisappear { atEnd = false }
            }
            .scrollContentBackground(.hidden)
            .screenBackground()
            .overlay(alignment: .bottom) {
                if !atEnd { Image(systemName: "chevron.down").foregroundColor(Theme.dim).padding(8).frame(maxWidth: .infinity).background(LinearGradient(colors: [.clear, Theme.background], startPoint: .top, endPoint: .bottom)).allowsHitTesting(false) }
            }
            .navigationTitle(Words.Settings.title)
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
        case .kiff: return Words.Settings.modeKiff
        case .sample: return Words.Settings.modeSample
        case .signedOut: return Words.Settings.modeSignedOut
        }
    }
}

/// Settings' Plan card: tafs cloud's `plan_line`, in `plan_tone`'s colour, and Plan & usage
/// under it; just the title until the balance is read, or from a tafs cloud without it.
struct PlanRow: View {
    let balance: TafsCloud.Balance?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let balance, let line = balance.planLine {
                Text(line).scaledFont(16, weight: .semibold).foregroundColor(UsageView.color(balance.planTone))
                    .fixedSize(horizontal: false, vertical: true)
                Text(UsageView.title).scaledFont(13).foregroundColor(Theme.amberText)
            } else {
                Text(UsageView.title).foregroundColor(Theme.ink)
            }
        }
        .padding(.vertical, 2)
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
                LabeledContent(Words.Settings.kiffAccount, value: account.name)
                if account.name != account.slug {
                    LabeledContent(Words.Settings.accountId, value: account.slug)
                }
                if let email = AccountLines.signedInAs(account) {
                    LabeledContent(Words.Settings.signedInAs, value: email)
                }
            }
            Section {
                // A Card's day ends at midnight here, and every time the owner reads is in it
                // (kiff/tafs#96, #206 6.11); set from the device as a signed setup call.
                NavigationLink { TimeZonePicker() } label: { LabeledContent(Words.Settings.timeZoneTitle, value: zone) }
                if zone != TimeZone.current.identifier {
                    Button(Words.Settings.useDeviceZone(device: "\(ThisDevice.kind)", identifier: "\(TimeZone.current.identifier)")) {
                        Task { await model.useThisDevicesTimeZone() }
                    }
                }
                if let why = model.timeZoneError {
                    Text(why).scaledFont(13).foregroundColor(Theme.no)
                }
            } footer: {
                Text(Words.Settings.TimeZone.usesThis)
            }
            // Their own copy, for their rights of access and portability (kiff/tafs#424).
            ExportChatsSection()
            if model.mayDeleteAccount { Section {
                Button(Words.Settings.DeleteAccount.row, role: .destructive) { deletingAccount = true }.confirming()
            } footer: {
                Text(Words.SignIn.deletesEveryTafRunsChats)
            } }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Words.Settings.kiffAccount)
        .sheet(isPresented: $deletingAccount, onDismiss: { if model.mode != .kiff { done() } }) {
            DeleteAccountView().environmentObject(model)
        }
    }

    /// The account's time zone as KIFF holds it now.
    private var zone: String { model.kiffAccount?.timeZone ?? account.timeZone ?? "UTC" }
}

/// What Account says of who is signed in, each thing once.
enum AccountLines {
    /// The email under the account's name; none when the account is named after it (a Solo
    /// account), so it isn't shown twice, or when KIFF sent none.
    static func signedInAs(_ account: KIFFClient.Account) -> String? {
        guard let email = account.email?.trimmingCharacters(in: .whitespacesAndNewlines), !email.isEmpty else { return nil }
        return email.caseInsensitiveCompare(account.name.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame ? nil : email
    }

    /// People & groups' first line: the account and how many people; the count alone when the
    /// account is named after its one owner.
    static func people(_ account: KIFFClient.Account, count: Int) -> String {
        let people = Words.People.count(count: count)
        return signedInAs(account) == nil && account.email?.isEmpty == false ? people : "\(account.name) · " + people
    }
}
