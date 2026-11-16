import SwiftUI

/// Needs you on the wrist: the count, then each held call in the phone's words. Until the
/// phone answers, it says it's loading, never that nothing is waiting.
struct NeedsYouScreen: View {
    var body: some View {
        NavigationStack {
            ProgressView()
                .navigationTitle(Words.NeedsYou.title)
        }
    }
}
