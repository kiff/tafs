import SwiftUI

@main
struct TafsApp: App {
    @StateObject private var model = AppModel(store: SampleStore())
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
                    model.onHeldChanged = { [lockScreen] held, tafs in lockScreen.update(held: held, tafs: tafs) }
                    await model.restore()
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
