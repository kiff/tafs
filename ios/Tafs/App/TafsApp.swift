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
    @State private var tab: Tab = Screenshots.enabled ? Screenshots.tab : .tafs

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
        // Coach mode (kiff/tafs#138): ✦ on every screen, above the tab bar and a chat's field.
        .overlay(alignment: .bottomTrailing) {
            if coachAvailable && !model.coachOpen {
                CoachButton(wide: sizeClass == .regular).padding(.trailing, 16).padding(.bottom, 112)
            }
        }
        // A wide screen: a panel beside what's open. A phone: a sheet, with Close, that a drag
        // down closes too; either way the conversation stays for ✦ to reopen.
        .overlay { if coachAvailable && sizeClass == .regular { CoachPanel() } }
        .sheet(isPresented: Binding(get: { coachAvailable && sizeClass != .regular && model.coachOpen },
                                    set: { model.coachOpen = $0 })) {
            CoachView().environmentObject(model)
        }
        .onOpenURL { url in
            if url.host == "needs-you" { tab = .needsYou }
        }
        .onChange(of: model.requestedTab) { requested in showRequestedTab(requested) }
        .onAppear { showRequestedTab(model.requestedTab) }
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
