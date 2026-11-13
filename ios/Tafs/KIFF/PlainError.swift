import Foundation

/// What a person reads when KIFF or planque refuses or fails: a plain line of the app's own, by
/// what happened, never their raw reason (an English line, a code, a vendor's words). The raw
/// reason stays in the error itself, for the code that acts on it and for a drive's diagnostics.
/// tafs cloud's own `words` and `error` are written for the person and are shown as they are.
enum PlainError {
    nonisolated static func words(status: Int) -> String {
        switch status {
        case 0: return Words.Error.offline
        case 401: return Words.Error.youreSignedOut
        case 403: return Words.Error.notAllowed
        case 409: return Words.Error.changed
        case 429: return Words.Error.busy
        case 500...599: return Words.Error.down
        default: return Words.Error.ours
        }
    }
}
