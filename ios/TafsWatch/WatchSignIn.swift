import SwiftUI

/// Where the watch keeps that it was signed out here, and whether it's there.
protocol WatchSignInStore {
    func signedOut() -> Bool
    func set(_ signedOut: Bool)
}

/// v2's Sign out on this watch (docs/watch-app.md, "Sign out on this watch"), the owner's choice of 19 Nov, as Wear OS's: on
/// this watch alone, nothing is sent to the phone, and the phone stays signed in. The watch forgets everything it holds (its
/// Inbox, and what each page showed) and keeps one thing: that it was signed out here, as an empty file in its own
/// Application Support. Until the person taps Sign in with your phone it shows only "Signed out on this watch" and reads and
/// sends nothing.
@MainActor
final class WatchSignIn: ObservableObject {
    typealias Store = WatchSignInStore

    /// The flag as a file, `tafs.signed_out_here`, protected like the Inbox's: its presence is the whole of it.
    struct FileStore: Store {
        let url: URL

        init(url: URL? = nil) {
            self.url = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("tafs.signed_out_here")
        }

        func signedOut() -> Bool { FileManager.default.fileExists(atPath: url.path) }

        func set(_ signedOut: Bool) {
            if signedOut {
                try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? Data().write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            } else {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    @Published private(set) var signedOutHere: Bool
    private let store: Store

    init(store: Store = FileStore()) {
        self.store = store
        signedOutHere = store.signedOut()
    }

    /// Sign out on this watch: the Inbox goes (the ids taken away included), then the flag is kept. What the pages showed is
    /// the caller's to forget (`leave()`), as when the app is put away.
    func signOut(inbox: InboxModel) {
        inbox.clear()
        store.set(true)
        signedOutHere = true
    }

    /// Whether a data push from the phone (a thread, the Inbox's marks, held calls) may be taken: never while signed out here,
    /// so nothing of the account is kept again until the person signs in here. The phone isn't told, and may keep sending; the
    /// watch drops it. An account change (`WatchPush.Kind.account`) is heard either way, since it can only wipe.
    nonisolated static func takesData(signedOutHere: Bool) -> Bool { !signedOutHere }

    /// Sign in with your phone: the flag goes, and the watch reads the phone again.
    func signIn() {
        store.set(false)
        signedOutHere = false
    }
}

/// What a watch signed out here shows, and all it shows: the line, and Sign in with your phone.
struct SignedOutHereScreen: View {
    @ObservedObject var signIn: WatchSignIn

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                Text(Words.Watch.signedOutHere)
                    .multilineTextAlignment(.center)
                Button(Words.SignIn.withPhone) { signIn.signIn() }
            }
            .padding(.top, 8)
        }
    }
}
