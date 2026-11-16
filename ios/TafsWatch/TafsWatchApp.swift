import SwiftUI

/// tafs on Apple Watch: a companion to the iPhone app, with no sign-in of its own. It reads
/// what needs the owner through the paired iPhone, which holds the session and the keys; the
/// watch holds neither. Approvals stay on the phone.
@main
struct TafsWatchApp: App {
    @StateObject private var needsYou = NeedsYouModel(phone: PhoneLink.shared)
    @StateObject private var tafs = TafsModel(phone: PhoneLink.shared)
    @Environment(\.scenePhase) private var scenePhase

    init() {
        PhoneLink.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            // Needs you first: it's why the wrist is looked at.
            TabView {
                NeedsYouScreen(model: needsYou)
                TafsScreen(model: tafs)
            }
            .tabViewStyle(.verticalPage)
        }
        .onChange(of: scenePhase) { _, phase in
            // In memory only: what was read goes once the app leaves the screen, and is read
            // again when it's back.
            if phase == .background {
                needsYou.leave()
                tafs.leave()
            }
            if phase == .active {
                Task {
                    await needsYou.load()
                    await tafs.load()
                }
            }
        }
    }
}
