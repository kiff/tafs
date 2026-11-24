import Foundation

/// Watch row 24, step 1: the complication's one link. The complication and the Smart Stack widget open the app with it, and
/// the app opens on Coach's home, listening (`CoachModel.askFromComplication`). Compiled by the watch app and its widget.
/// The complication shows no data: no count, no taf, no words of the account. The count comes later, with an App Group.
enum ComplicationLink {
    /// `tafs-watch://ask`: open on Coach's home and start listening.
    static let ask = URL(string: "tafs-watch://ask")!

    /// Whether a URL the system hands the app is the complication's.
    static func isAsk(_ url: URL) -> Bool {
        url.scheme == ask.scheme && url.host == ask.host && (url.path.isEmpty || url.path == "/") && url.query == nil
    }
}
