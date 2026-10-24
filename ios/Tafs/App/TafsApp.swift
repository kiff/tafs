import SwiftUI

@main
struct TafsApp: App {
    @StateObject private var model = AppModel(store: SampleStore(), remembered: .standard)
    @UIApplicationDelegateAdaptor(PushDelegate.self) private var push
    @Environment(\.scenePhase) private var scenePhase
    private let lockScreen = LockScreen()

    var body: some Scene {
        WindowGroup {
            RootView()
                // The screenshots' narrow iPad window (kiff/tafs#210): Slide Over's width.
                .modifier(NarrowForScreenshots())
                .environmentObject(model)
                .preferredColorScheme(.light)
                .tint(Theme.amber)
                .task {
                    if Screenshots.enabled {
                        await model.startSample()
                        return
                    }
                    // Once tafs cloud pushes held calls, the app doesn't post its own alert as well.
                    model.onHeldChanged = { [lockScreen, weak model] held, tafs in
                        lockScreen.update(held: held, tafs: tafs, notify: model?.alertsReady != true)
                    }
                    model.requestPushToken = { PushDelegate.register() }
                    model.askNotificationPermission = { await PushDelegate.askPermission() }
                    push.onToken = { [weak model] token in Task { await model?.pushTokenArrived(token) } }
                    push.onHeldChanged = { [weak model] in await model?.refresh() }
                    push.onOpen = { [weak model] destination in model?.open(destination) }
                    Routes.shared.onOpen = { [weak model] destination in model?.open(destination) }
                    // From launch: renewals, refunds and purchases made elsewhere reach KIFF.
                    model.purchases.start()
                    await model.restore()
                    PushDelegate.registerIfAllowed()
                }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active, model.mode == .kiff { Task { await model.refresh() } }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.scenePhase) private var scenePhase
    /// The keyboard is up: ✦ keeps off it (kiff/tafs#138).
    @State private var keyboardUp = false
    @State private var tab: Tab = Screenshots.enabled ? Screenshots.tab : .tafs
    /// The window's size as the screens were last laid out, and a count that lays them out
    /// again: an iPad turned while tafs was away can come back laid out for the old
    /// orientation, with the sidebar's foot and ✦ placed for it (kiff/tafs#138, item 47).
    @State private var laidOut: CGSize = .zero
    @State private var layoutEpoch = 0

    /// Coach mode works with the owner's KIFF account, once signed in.
    private var coachAvailable: Bool { model.signedIn && model.mode == .kiff }

    private func showRequestedTab(_ requested: Tab?) {
        guard let requested else { return }
        tab = requested
        model.requestedTab = nil
    }

    var body: some View {
        Group {
            if model.signedIn {
                TabView(selection: $tab) {
                    // A sidebar beside the open taf on a wide screen (kiff/tafs#85).
                    TafsTab(tab: $tab)
                        .tabItem { Label("Tafs", systemImage: "square.stack") }
                        .tag(Tab.tafs)
                    NavigationStack { NeedsYouView() }
                        .tabItem { Label("Needs you", systemImage: "bell") }
                        .badge(model.held.count)
                        .tag(Tab.needsYou)
                    NavigationStack { RecordView() }
                        .tabItem { Label("Record", systemImage: "list.bullet") }
                        .tag(Tab.record)
                }
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
        // Coach mode (kiff/tafs#138): ✦ on every screen, above the tab bar and a chat's field.
        .overlay(alignment: .bottomTrailing) {
            // Never on the keyboard's edge: hidden while it's up, and pinned to the screen.
            if coachAvailable && !model.coachOpen && !keyboardUp {
                CoachButton(wide: sizeClass == .regular).padding(.trailing, 16).padding(.bottom, CoachButton.bottomOffset)
                    .ignoresSafeArea(.keyboard)
            }
        }
        // The keyboard's shortcuts on every screen (kiff/tafs#210).
        .background { if model.signedIn { GlobalKeyCommands(tab: $tab, coachAvailable: coachAvailable) } }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardUp = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardUp = false }
        // A wide screen: a panel beside what's open. A phone: a sheet, with Close, that a drag
        // down closes too; either way the conversation stays for ✦ to reopen.
        .overlay { if coachAvailable && sizeClass == .regular { CoachPanel() } }
        // The whole screen on an iPhone, nothing of the screen behind showing (kiff/tafs#206 6.10).
        .fullCover(isPresented: Binding(get: { coachAvailable && sizeClass != .regular && model.coachOpen },
                                        set: { model.coachOpen = $0 })) {
            CoachView().environmentObject(model)
        }
        .onOpenURL { url in
            if url.host == "needs-you" { tab = .needsYou }
        }
        // A tapped request for tasks: Usage, where it's answered, on its own screen (kiff/tafs#215).
        .sheet(isPresented: Binding(get: { model.signedIn && model.usageRequested }, set: { model.usageRequested = $0 })) {
            NavigationStack {
                UsageView()
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { model.usageRequested = false }.keyboardShortcut(.cancelAction) } }
            }
            .environmentObject(model)
        }
        // Halt all, from a halt alert or from Siri when it needed the app (kiff/tafs#210).
        .sheet(isPresented: Binding(get: { model.signedIn && model.haltRequested }, set: { model.haltRequested = $0 })) {
            NavigationStack {
                HaltScreen(tafID: model.haltRequestedTaf)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { model.haltRequested = false }.keyboardShortcut(.cancelAction) } }
            }
            .environmentObject(model)
        }
        .onChange(of: model.requestedTab) { requested in showRequestedTab(requested) }
        .onAppear { showRequestedTab(model.requestedTab) }
        // The device's time zone, asked once (kiff/tafs#206 6.11).
        .alert("Use \(model.timeZoneOffer ?? "") for times in tafs?", isPresented: Binding(
            get: { model.timeZoneOffer != nil && model.lastError == nil },
            set: { _ in }
        )) {
            Button("Use \(model.timeZoneOffer ?? "")") { Task { await model.answerTimeZoneOffer(use: true) } }
            Button("Not now", role: .cancel) { Task { await model.answerTimeZoneOffer(use: false) } }
        } message: {
            Text("Holds' deadlines, Activity, the Record and what your tafs write would use this \(ThisDevice.kind)'s time zone. Your account uses \(model.kiffAccount?.timeZone ?? "UTC") now.")
        }
        .alert("Not done", isPresented: Binding(
            get: { model.lastError != nil },
            set: { if !$0 { model.lastError = nil } }
        )) {
            Button("OK", role: .cancel) {}
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
