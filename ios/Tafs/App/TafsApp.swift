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
                .preferredColorScheme(.dark)
                .tint(Theme.amber)
                .task {
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

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var tab: Tab = .tafs

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
