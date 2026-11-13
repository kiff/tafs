import SwiftUI

@main
struct TafsApp: App {
    @AppStorage(ThemePreference.key) private var theme = ThemePreference.system.rawValue
    @StateObject private var model = AppModel(store: SampleStore(), remembered: .standard)
    @UIApplicationDelegateAdaptor(PushDelegate.self) private var push
    @Environment(\.scenePhase) private var scenePhase
    /// The app went to the background since it was last active.
    @State private var wasAway = false
    private let lockScreen = LockScreen()

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(ThemePreference(rawValue: theme)?.scheme)
                // The screenshots' narrow iPad window (kiff/tafs#210): Slide Over's width.
                .modifier(NarrowForScreenshots())
                .environmentObject(model)
                .tint(Theme.amber)
                .task {
                    // tafs cloud's "allow AI use first", from whatever route the person tried.
                    model.watchAIConsent()
                    if Screenshots.enabled {
                        if Screenshots.landscape { Screenshots.turnToLandscape() }
                        await model.startSample()
                        return
                    }
                    // Once tafs cloud pushes held calls, the app doesn't post its own alert as well.
                    model.onHeldChanged = { [lockScreen, weak model] held, tafs, names in
                        lockScreen.update(held: held, tafs: tafs, names: names, notify: model?.alertsReady != true)
                    }
                    model.requestPushToken = { PushDelegate.register() }
                    model.askNotificationPermission = { await PushDelegate.askPermission() }
                    push.onToken = { [weak model] token in Task { await model?.pushTokenArrived(token) } }
                    push.onHeldChanged = { [weak model] in await model?.refresh() }
                    push.onHoldAlert = { [weak model] hold in await model?.refreshUntilHeld(hold) }
                    push.onChange = { [weak model] change, taf in await model?.liveChange(change, cloudTafID: taf) }
                    push.onOpen = { [weak model] destination in model?.open(destination) }
                    Routes.shared.onOpen = { [weak model] destination in model?.open(destination) }
                    // From launch: renewals, refunds and purchases made elsewhere reach KIFF.
                    model.purchases.start()
                    await model.restore()
                    // A drive this phone left on when it closed is over (docs/drive.md).
                    await model.endLeftoverDrive()
                    PushDelegate.registerIfAllowed()
                }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background { wasAway = true }
            // Back from the browser: a re-link waiting there asks KIFF again at once.
            if phase == .active, model.relink != nil { model.nudgeSignIn() }
            if phase == .active, model.mode == .kiff { Task { await model.refresh(); model.settleListOrder() } }
            // Back from Settings: whether this device can approve now (kiff/tafs#423).
            if phase == .active { model.checkApprovalReadiness() }
            if phase == .active, wasAway { wasAway = false; model.settleSampleOnReturn() }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var drive = DriveSession.shared
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.scenePhase) private var scenePhase
    /// The keyboard is up: the bar keeps off it.
    @State private var keyboardUp = false
    @State private var tab: Tab = Screenshots.enabled ? Screenshots.tab : .tafs
    /// The tabs opened so far: each is built the first time it's opened, then kept.
    @State private var opened: Set<Tab> = []
    /// A few seconds after the tafs are in: a phone with alerts on has said so by then, so the
    /// offer of alerts never flashes up on it.
    @State private var settled = false
    /// The window's size as the screens were last laid out, and a count that lays them out
    /// again: an iPad turned while tafs was away can come back laid out for the old
    /// orientation, with the sidebar's foot and ✦ placed for it (kiff/tafs#138, item 47).
    @State private var laidOut: CGSize = .zero
    @State private var layoutEpoch = 0

    /// Coach mode works with the owner's KIFF account, once signed in.
    /// Coach only for those who may use it: it was shown to a Viewer, who got no_access, or
    /// Touch ID and then a refusal (the 805 retest).
    private var coachAvailable: Bool { model.signedIn && model.mode == .kiff && model.mayUseCoach }

    private static let tabs: [Tab] = [.account, .tafs, .needsYou, .record]

    @ViewBuilder private func tabContent(_ t: Tab) -> some View {
        switch t {
        // A sidebar beside the open taf on a wide screen (kiff/tafs#85).
        case .tafs: TafsTab(tab: $tab)
        case .needsYou: NavigationStack { NeedsYouView() }
        case .record: NavigationStack { RecordView() }
        case .account: SettingsView()
        }
    }

    private func showRequestedTab(_ requested: Tab?) {
        guard let requested else { return }
        tab = requested
        model.requestedTab = nil
    }

    var body: some View {
        Group {
            if model.signedIn {
                // The bar's tabs, each kept as it was left once opened; the bar along the
                // foot on a phone, a rail down the left on a wide screen (the taf layout).
                let rail = sizeClass == .regular
                HStack(spacing: 0) {
                    if rail { TafsBar(tab: $tab, coachAvailable: coachAvailable, rail: true) }
                    // Below the tabs, not over them: a screen's own foot (a chat's field) stays
                    // above the bar.
                    VStack(spacing: 0) {
                        ZStack {
                            ForEach(Self.tabs, id: \.self) { t in
                                if opened.contains(t) || t == tab {
                                    tabContent(t)
                                        .opacity(t == tab ? 1 : 0)
                                        .allowsHitTesting(t == tab)
                                        .accessibilityHidden(t != tab)
                                }
                            }
                        }
                        // Off while the keyboard is up, as a tab bar is.
                        if !rail && !keyboardUp { TafsBar(tab: $tab, coachAvailable: coachAvailable, rail: false) }
                    }
                }
                .onChange(of: tab) { _ = opened.insert($0); DriveWaits.emit("screen_shown") }
            } else {
                OnboardingView()
            }
        }
        .background(GeometryReader { g in
            Color.clear.onAppear { laidOut = g.size }.onChange(of: g.size) { laidOut = $0 }
        }.ignoresSafeArea())
        .onChange(of: scenePhase) { phase in
            guard phase == .active else { return }
            Task {
                // Once the window has settled: if the screens are still laid out for another
                // size, they're laid out again (the tab stays as it was).
                try? await Task.sleep(nanoseconds: 600_000_000)
                guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return }
                let size = window.bounds.size
                if laidOut != .zero, abs(size.width - laidOut.width) > 1 || abs(size.height - laidOut.height) > 1 {
                    layoutEpoch += 1
                    laidOut = size
                }
            }
        }
        // The keyboard's shortcuts on every screen (kiff/tafs#210).
        // Off while Coach drives the app: a key the person presses then isn't a shortcut.
        .background { if model.signedIn && !drive.isOn { GlobalKeyCommands(tab: $tab, coachAvailable: coachAvailable) } }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardUp = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardUp = false }
        // Both sizes keep the underlying taf and tab mounted while Coach opens.
        .overlay {
            if coachAvailable {
                if sizeClass == .regular { CoachPanel() }
                else { PhoneCoachPanel() }
            }
        }
        .onOpenURL { url in
            if url.host == "needs-you" { tab = .needsYou }
        }
        // Alerts, offered once right after signing in, before any taf needs the owner (the 755 retest).
        .task(id: model.listLoaded) {
            settled = false
            guard model.listLoaded else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            settled = !Task.isCancelled
        }
        // Not a sheet (the 789 retest): a sheet's content is built lazily, and SwiftUI built this one on
        // its async render thread at launch on iPad, where the main actor's check trapped. 772 and 789
        // crashed there, with and without detents. An overlay is part of the screen itself.
        .overlay {
            if settled && model.offerAlertsUpFront && !model.coachOpen {
                ZStack {
                    Color.black.opacity(0.35).ignoresSafeArea()
                        .onTapGesture { if !model.turningOnAlerts { model.answerAlertsOffer() } }
                    AlertsOfferSheet()
                        .frame(maxWidth: 520)
                        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                        .shadow(color: .black.opacity(0.2), radius: 24, y: 8)
                        .padding(16)
                }
                .transition(.opacity)
            }
        }
        // A tapped request for tasks: Usage, where it's answered, on its own screen (kiff/tafs#215).
        .sheet(isPresented: Binding(get: { model.signedIn && model.usageRequested }, set: { model.usageRequested = $0 })) {
            NavigationStack {
                UsageView()
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button(Words.Common.done) { model.usageRequested = false }.keyboardShortcut(.cancelAction) } }
            }
            .environmentObject(model)
        }
        // Halt all, from a halt alert or from Siri when it needed the app (kiff/tafs#210).
        .sheet(isPresented: Binding(get: { model.signedIn && model.haltRequested }, set: { model.haltRequested = $0 })) {
            NavigationStack {
                HaltScreen(tafID: model.haltRequestedTaf)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button(Words.Common.done) { model.haltRequested = false }.keyboardShortcut(.cancelAction) } }
            }
            .environmentObject(model)
        }
        .onChange(of: model.requestedTab) { requested in showRequestedTab(requested) }
        .onAppear { showRequestedTab(model.requestedTab) }
        // The AI-data consent, full screen, before anything the person writes reaches the model:
        // after sign-in while it isn't given, and whenever tafs cloud refuses a turn for want of it.
        .fullScreenCover(item: Binding(get: { model.aiConsentAsked }, set: { if $0 == nil { model.aiConsentAsked = nil } })) { consent in
            AIConsentView(consent: consent).environmentObject(model)
        }
        // The device's time zone, asked once (kiff/tafs#206 6.11).
        .alert(Words.Settings.TimeZoneOffer.title(zone: model.timeZoneOffer ?? ""), isPresented: Binding(
            get: { model.timeZoneOffer != nil && model.lastError == nil },
            set: { _ in }
        )) {
            Button(Words.Settings.TimeZoneOffer.use(zone: model.timeZoneOffer ?? "")) { Task { await model.answerTimeZoneOffer(use: true) } }
            Button(Words.Common.notNow, role: .cancel) { Task { await model.answerTimeZoneOffer(use: false) } }
        } message: {
            Text(Words.Settings.TimeZoneOffer.textDevice(device: ThisDevice.kind, zone: model.kiffAccount?.timeZone ?? "UTC"))
        }
        .alert(Words.Common.notDone, isPresented: Binding(
            get: { model.lastError != nil },
            set: { if !$0 { model.lastError = nil } }
        )) {
            Button(Words.Common.ok, role: .cancel) {}
        } message: {
            Text(model.lastError ?? "")
        }
        // The whole screen, ✦ and the panels with it, when it's laid out again.
        .id(layoutEpoch)
    }
}

/// `-narrow YES`: the whole app at Slide Over's width, compact, as in a narrow iPad window.
private struct NarrowForScreenshots: ViewModifier {
    func body(content: Content) -> some View {
        if Screenshots.narrow {
            content.frame(width: 320).environment(\.horizontalSizeClass, .compact)
                .frame(maxWidth: .infinity).background(Color.black)
        } else {
            content
        }
    }
}
