import SwiftUI

/// tafs on Apple Watch: a companion to the iPhone app, with no sign-in of its own. It reads
/// what needs the owner through the paired iPhone, which holds the session and the keys; the
/// watch holds neither. Approvals stay on the phone.
@main
struct TafsWatchApp: App {
    // The paired iPhone, or the demo's phone of fixed answers under `-watch-demo` (`WatchDemo`, for the UI tests).
    @StateObject private var needsYou = NeedsYouModel(phone: WatchDemo.phone, lock: WatchDemo.lock)
    @StateObject private var tafs = TafsModel(phone: WatchDemo.phone, lock: WatchDemo.lock)
    /// v2 keeps one message for later on the watch (row 8), behind its switch; v1 keeps nothing.
    @StateObject private var coach = CoachModel(phone: WatchDemo.phone, lock: WatchDemo.lock,
                                                drafts: WatchV2.on && CoachModel.keepForLaterOn ? FileDraftStore() : nil)
    /// v2's Inbox, kept on the watch while it has a passcode (step iv). The demo keeps it in its own folder, emptied at launch.
    @StateObject private var inbox = WatchDemo.on
        ? InboxModel(store: FileInboxStore(url: WatchDemo.folder.appendingPathComponent("inbox.json")), lock: WatchDemo.lock,
                     phone: WatchDemo.phone)
        : InboxModel(phone: PhoneLink.shared)
    /// v2: signed out on this watch alone (Account's Sign out), kept across restarts; the demo's in its own folder.
    @StateObject private var signIn = WatchDemo.on
        ? WatchSignIn(store: WatchSignIn.FileStore(url: WatchDemo.folder.appendingPathComponent("signed-out")))
        : WatchSignIn()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // A recording an app ended mid-transfer left behind (row 44, A).
        VoiceModel.sweepLeftovers()
        // The demo asks no real phone.
        if !WatchDemo.on { PhoneLink.shared.start() }
    }

    var body: some Scene {
        WindowGroup {
            Group {
            if WatchV2.on, signIn.signedOutHere {
                // Signed out on this watch: only that, and Sign in with your phone. Nothing is read or sent.
                SignedOutHereScreen(signIn: signIn)
            } else if WatchV2.on {
                // v2 (docs/watch-app.md, "Version 2"): Coach is home, then the Inbox, Your tafs and Account.
                WatchPagesView(needsYou: needsYou, tafs: tafs, coach: coach, inbox: inbox, signOut: signOut)
            } else {
                // Needs you first: it's why the wrist is looked at.
                TabView {
                    NeedsYouScreen(model: needsYou)
                    TafsScreen(model: tafs)
                    CoachScreen(model: coach)
                }
                .tabViewStyle(.verticalPage)
            }
            }
            // v2 (W7): Send and the microphone in the app's orange; everything else the system's grey.
            .tint(WatchV2.on ? WatchLook.send : nil)
        }
        .onChange(of: scenePhase) { _, phase in
            // Signed out on this watch: nothing is read, and there's nothing to forget.
            if WatchV2.on, signIn.signedOutHere { return }
            // In memory only: what was read goes once the app leaves the screen, and is read
            // again when it's back.
            // v2's Inbox is kept only with a passcode: checked when the app is put away and opened again.
            if WatchV2.on, phase == .background || phase == .active {
                inbox.checkLock()
                // And a message kept for later, by the same rule (row 8).
                coach.checkLock()
            }
            // So it opens at once (row 44, D): kept only with a passcode.
            if phase == .background || phase == .active { tafs.cache.checkLock() }
            if phase == .background {
                needsYou.leave()
                tafs.leave()
                coach.leave()
            }
            if phase == .active {
                Task {
                    await needsYou.load()
                    // A message kept for later goes once the phone answers (v2 only).
                    await coach.afterRead(needsYou.state)
                    await tafs.load()
                }
            }
        }
        .onChange(of: signIn.signedOutHere) { _, signedOut in
            // Signed in again with the phone: read it as when the app opens.
            guard !signedOut else { return }
            Task {
                await needsYou.load()
                await coach.afterRead(needsYou.state)
                await tafs.load()
            }
        }
    }

    /// Account's Sign out, once confirmed: the Inbox goes and the flag is kept, then each page forgets what it showed.
    private func signOut() {
        signIn.signOut(inbox: inbox)
        tafs.cache.clear()
        // Nothing of the account stays on the watch, a message kept for later included.
        coach.deleteKept()
        needsYou.leave()
        tafs.leave()
        coach.leave()
    }
}
