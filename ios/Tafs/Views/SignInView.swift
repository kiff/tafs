import SwiftUI


/// Sign in (the approved redesign). On an iPad or a Mac, two halves: "Sign in with KIFF" with Continue, the usual
/// way, and "Sign in with your phone", the pairing QR, shown at once with no prompt. A narrow window stacks
/// them. An iPhone shows only "Sign in with KIFF": any phone can open KIFF's own sign-in, so it never pairs.
/// Continue shows a code, the person approves it in KIFF (on the web, signed in as themselves), and this
/// device receives a session. One way runs at a time (`SignInWays`): Continue stops the phone half's pairing, and
/// "Show the code" starts it again and stops KIFF's, so only the sign-in the person chose can land. Either way ends
/// on the celebration (`ConnectedView`). Every word is a shared key; the drawings carry no text.
struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var start: KIFFClient.DeviceStart?
    @State private var problem: String?
    @State private var task: Task<Void, Never>?
    @State private var copied = false
    /// Which way is running: the phone's pairing at first, KIFF's code once Continue is tapped.
    @State private var ways = SignInWays()
    /// Continue was tapped: the code is asked for and shown.
    private var continuing: Bool { ways.kiffRuns }

    /// Whether this device offers "Sign in with your phone": every device but an iPhone.
    static func offersPhone(isPhone: Bool = ThisDevice.isPhone) -> Bool { !isPhone }

    var body: some View {
        NavigationStack {
            Group {
                if !Self.offersPhone() {
                    ScrollView { kiffHalf.frame(maxWidth: .infinity) }
                } else if sizeClass == .regular {
                    HStack(spacing: 0) {
                        ScrollView { kiffHalf }.frame(maxWidth: .infinity)
                        Divider()
                        ScrollView { phoneHalf }.frame(maxWidth: .infinity).background(Theme.raised)
                    }
                } else {
                    ScrollView {
                        VStack(spacing: 0) {
                            kiffHalf
                            Divider()
                            phoneHalf.background(Theme.raised)
                        }
                    }
                }
            }
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Words.Common.cancel) { task?.cancel(); dismiss() }
                }
            }
        }
        .onDisappear { task?.cancel() }
        // Back from approving in the browser: ask KIFF at once.
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.nudgeSignIn() }
        }
    }

    /// The usual way: KIFF's own sign-in, a code to approve there.
    private var kiffHalf: some View {
        VStack(spacing: 24) {
            SignInArt.kiff
            Text(Words.SignIn.withKiff)
                .scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            if !continuing {
                Button(Words.Common.continue) { begin() }
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 320)
                    .accessibilityIdentifier("signIn.continue")
            } else if let start {
                VStack(alignment: .leading, spacing: 16) {
                    Text(Words.SignIn.lead(device: "\(ThisDevice.kind)"))
                        .scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
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
                }
                .frame(maxWidth: 420)
            } else if problem == nil {
                ProgressView().tint(Theme.amber)
            }
            if let problem {
                Text(problem).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                Button(Words.Common.tryAgain) { begin() }.buttonStyle(OutlineButtonStyle())
            }
            Text(Legal.agreement())
                .scaledFont(12).foregroundColor(Theme.dim).tint(Theme.amber)
                .multilineTextAlignment(.center).frame(maxWidth: 420)
            if let age = Onboarding.raisedAgeLine() {
                Text(age)
                    .scaledFont(12).foregroundColor(Theme.dim)
                    .multilineTextAlignment(.center).frame(maxWidth: 420)
                    .accessibilityIdentifier("signIn.age")
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }

    /// The other way, on an iPad or a Mac: this device's pairing QR, scanned and approved from the person's phone.
    /// Stopped while KIFF's code runs: its pairing ends with the view, and "Show the code" starts it again.
    @ViewBuilder private var phoneHalf: some View {
        if ways.phoneRuns {
            PairSignInView(embedded: true) { connected() }
                .padding(.vertical, 8)
        } else {
            VStack(spacing: 22) {
                Image(systemName: "qrcode").font(.system(size: 64)).foregroundColor(Theme.dim)
                    .frame(height: 120).accessibilityHidden(true)
                Text(Words.SignIn.withPhone)
                    .scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                Button(Words.SignIn.showCode) { showPhone() }
                    .buttonStyle(OutlineButtonStyle())
                    .frame(maxWidth: 320)
                    .accessibilityIdentifier("signIn.showCode")
            }
            .padding(32)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
        }
    }

    /// Back to the phone's way: KIFF's code stops, and the pairing QR shows again with a new code.
    private func showPhone() {
        task?.cancel()
        task = nil
        start = nil
        problem = nil
        copied = false
        ways.showPhone()
    }

    private func begin() {
        ways.continueWithKiff()
        problem = nil
        start = nil
        copied = false
        task?.cancel()
        task = Task {
            do {
                let s = try await model.startSignIn()
                start = s
                try await model.finishSignIn(s, startedAt: Date())
                connected()
            } catch is CancellationError {
            } catch {
                problem = error.localizedDescription
            }
        }
    }

    /// Signed in, either way: the celebration shows over the home screen, and the sign-in closes.
    private func connected() {
        task?.cancel()
        model.justConnected = true
        dismiss()
    }
}

/// The sign-in's two ways, one at a time (as on every computer): the phone's pairing runs from the start where it's
/// offered, Continue stops it and starts KIFF's code, and "Show the code" goes back. Never both, so a code approved
/// in a browser and a QR scanned on a phone can't race into two accounts.
struct SignInWays: Equatable {
    /// KIFF's code is asked for and polled.
    private(set) var kiffRuns = false
    /// The phone half's pairing QR is shown and polled. Where the phone half isn't offered, nothing reads it.
    var phoneRuns: Bool { !kiffRuns }

    /// Continue: KIFF's code starts, and the phone's pairing stops.
    mutating func continueWithKiff() { kiffRuns = true }

    /// "Show the code": the phone's pairing starts again, and KIFF's code stops.
    mutating func showPhone() { kiffRuns = false }
}

/// After signing in or pairing: a moment, not a flash. The computer and the phone, joined; the shared
/// words (`sign_in.connected`, `sign_in.connected_line`); one button into the app. The drawing has no text.
struct ConnectedView: View {
    let done: () -> Void

    var body: some View {
        VStack(spacing: 32) {
            Spacer(minLength: 0)
            SignInArt.connected
            VStack(spacing: 6) {
                Text(Words.SignIn.connected)
                Text(Words.SignIn.connectedLine)
            }
            .scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
            .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
            Button(Words.SignIn.letsGo, action: done)
                .buttonStyle(PrimaryButtonStyle())
                .frame(maxWidth: 320)
                .accessibilityIdentifier("signIn.letsGo")
            Spacer(minLength: 0)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .screenBackground()
    }
}

/// The sign-in's drawings, in shapes only: never a letter, a number or a word. The house style for the
/// app's other drawings (`Illustration`), which share its mark.
enum SignInArt {
    /// The app's mark: the brand's sweep (`Theme.brand`), as every drawing draws it.
    private static let mark = Theme.brand

    /// KIFF's sign-in: a card with the app's mark, two lines and its button, under a lock.
    static var kiff: some View {
        ZStack(alignment: .topTrailing) {
            Circle().fill(Theme.amber.opacity(0.16)).frame(width: 190, height: 190)
            VStack(alignment: .leading, spacing: 10) {
                RoundedRectangle(cornerRadius: 10, style: .continuous).fill(mark).frame(width: 34, height: 34)
                Capsule().fill(Theme.ink.opacity(0.85)).frame(width: 78, height: 8)
                Capsule().fill(Theme.ink.opacity(0.18)).frame(width: 58, height: 8)
                Spacer(minLength: 0)
                Capsule().fill(Theme.amber).frame(height: 26)
            }
            .padding(16)
            .frame(width: 122, height: 156)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color.white))
            .shadow(color: .black.opacity(0.14), radius: 16, y: 10)
            .offset(x: -34, y: 20)
            Image(systemName: "lock")
                .font(.system(size: 22, weight: .regular))
                .foregroundColor(Theme.ink)
                .frame(width: 50, height: 50)
                .background(Circle().fill(Color.white))
                .shadow(color: .black.opacity(0.12), radius: 10, y: 6)
        }
        .frame(width: 200, height: 200)
        .accessibilityHidden(true)
    }

    /// Joined: the computer and the phone, a check between them.
    static var connected: some View {
        ZStack {
            HStack(alignment: .bottom, spacing: 40) {
                VStack(alignment: .leading, spacing: 8) {
                    RoundedRectangle(cornerRadius: 9, style: .continuous).fill(mark).frame(width: 30, height: 30)
                    Capsule().fill(Theme.ink.opacity(0.8)).frame(width: 110, height: 8)
                    Capsule().fill(Theme.ink.opacity(0.18)).frame(width: 80, height: 8)
                    Spacer(minLength: 0)
                }
                .padding(16)
                .frame(width: 200, height: 136, alignment: .topLeading)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.ink, lineWidth: 3))
                RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color.white)
                    .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Theme.ink, lineWidth: 3))
                    .frame(width: 80, height: 156)
            }
            Image(systemName: "checkmark")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 52, height: 52)
                .background(Circle().fill(Theme.amberText))
                .shadow(color: Theme.amber.opacity(0.35), radius: 14, y: 8)
                .offset(x: 60)
        }
        .accessibilityHidden(true)
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
    /// `-tab account -screen devices`: Devices pushed in Account, over the sample, for the reference set.
    @State private var openDevices = Screenshots.enabled && Screenshots.screen == "devices"
    /// A screen of Settings asked for by name (`AppModel.requestedAccountPage`), and deleting
    /// the account, which is its own sheet.
    @State private var opened: AppModel.AccountPage?
    @State private var deletingAccount = false

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
                        // Files (docs/files.md): every file the account's tafs delivered, behind its switch.
                        if FilesFeature.on { NavigationLink(FilesView.title) { FilesView(store: CloudFilesStore(cloud: model.cloud)) } }
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
                // The language tafs's lines are in, by its own name, and Change language opens the
                // app's own list, as on Android: on a Mac too, which has no Settings › tafs page.
                Section(Words.Settings.languageTitle) {
                    LabeledContent(Words.Settings.languageTitle, value: AppLanguage.name())
                        .accessibilityIdentifier("settings.language")
                    NavigationLink { LanguagePicker() } label: { Text(Words.Settings.changeLanguage).foregroundColor(Theme.amberText) }
                        .accessibilityIdentifier("settings.change-language")
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
                    // The three cards again, with the privacy and terms pages (docs/onboarding.md).
                    if Onboarding.on {
                        NavigationLink(Words.Onboarding.howItWorks) { HowTafsWorksView() }
                            .accessibilityIdentifier("howItWorks")
                    }
                }
                // tafs's own pages, in English only for now: outside English the rows say so (Android 1.27.8).
                Section(Words.Settings.legal) {
                    Link(Legal.label(Words.Settings.terms), destination: Legal.terms)
                    Link(Legal.label(Words.Settings.privacyPolicy), destination: Legal.privacy)
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
            .navigationDestination(isPresented: $openDevices) { DevicesView().environmentObject(model) }
            .navigationDestination(isPresented: Binding(get: { opened != nil }, set: { if !$0 { opened = nil } })) { requestedPage }
            .sheet(isPresented: $deletingAccount, onDismiss: { if model.mode != .kiff { dismiss() } }) {
                DeleteAccountView().environmentObject(model)
            }
            .onAppear { take(model.requestedAccountPage) }
            .onChange(of: model.requestedAccountPage) { take($0) }
            .sheet(isPresented: $signingIn) { SignInView().environmentObject(model) }
            .confirmingSignOut($confirming) { _ in
                Task { if await model.signOut() { dismiss() } }
            }
        }
    }

    @ViewBuilder private var requestedPage: some View {
        switch opened {
        case .people?: PeopleGroupsView()
        case .devices?: DevicesView()
        case .apps?: ConnectAppsView()
        case .alerts?: NotificationSettingsView()
        case .timeZone?: TimeZonePicker()
        case .deleteAccount?, nil: EmptyView()
        }
    }

    /// Opens the screen asked for by name, and clears the request.
    private func take(_ page: AppModel.AccountPage?) {
        guard let page else { return }
        if page == .deleteAccount { deletingAccount = true } else { opened = page }
        model.requestedAccountPage = nil
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
