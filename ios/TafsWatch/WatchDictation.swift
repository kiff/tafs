import WatchKit

/// One tap to talk (row 44, A; as Wear OS's tafs#1582): Ask opens the system's input on dictation at once, rather than a
/// screen with a field to tap first. watchOS gives an app no speech recognizer, so this is the system's own dictation, and
/// only its text comes back (`watch.voice_text_only`). With no suggestions and plain text, watchOS opens the input on the
/// microphone; on a watch that shows its own choices first, it's still one tap to the input.
enum WatchDictation {
    /// What the system's input handed back.
    enum Heard: Equatable {
        /// The words, trimmed and never blank.
        case words(String)
        /// Cancelled, or nothing said.
        case nothing
        /// There's no screen to open it from: the ask opens as before.
        case unavailable
    }

    @MainActor
    static func listen() async -> Heard {
        let app = WKApplication.shared()
        guard let controller = app.visibleInterfaceController ?? app.rootInterfaceController else { return .unavailable }
        return await withCheckedContinuation { done in
            controller.presentTextInputController(withSuggestions: nil, allowedInputMode: .plain) { results in
                done.resume(returning: heard(results))
            }
        }
    }

    /// The input's results as the ask takes them: the first text, trimmed; nil or blank is nothing.
    static func heard(_ results: [Any]?) -> Heard {
        guard let words = (results?.first as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !words.isEmpty else {
            return .nothing
        }
        return .words(words)
    }
}

extension WatchDictation {
    /// The microphone's tap, on Coach's home or in a taf's chat: dictation first when the ask has nothing to come back to,
    /// and the words to Check before sending. The ask opens after it either way: on the review with words, or, cancelled,
    /// on Type and Quick reply. Nothing leaves the watch before Send.
    @MainActor
    static func talk(to model: CoachModel, listen: () async -> Heard = { await WatchDictation.listen() }) async {
        guard model.listensFirst else { return }
        if case .words(let words) = await listen() { model.review(words) }
    }
}
