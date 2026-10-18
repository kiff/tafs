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

enum Tab: Hashable { case tafs, needsYou, record }

/// CI's screenshot run: `-screenshots YES -tab needs-you` opens the sample on a tab,
/// without asking for notifications.
enum Screenshots {
    static var enabled: Bool { UserDefaults.standard.bool(forKey: "screenshots") }
    /// `-screen taf` opens a taf's page; `-screen new` the new-taf flow.
    static var screen: String? { UserDefaults.standard.string(forKey: "screen") }
    static var tab: Tab {
        switch UserDefaults.standard.string(forKey: "tab") {
        case "needs-you": return .needsYou
        case "record": return .record
        default: return .tafs
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var tab: Tab = Screenshots.enabled ? Screenshots.tab : .tafs

    private func showRequestedTab(_ requested: Tab?) {
        guard let requested else { return }
        tab = requested
        model.requestedTab = nil
    }

    var body: some View {
        Group {
            if model.signedIn {
                TabView(selection: $tab) {
                    NavigationStack { HomeView(tab: $tab) }
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
