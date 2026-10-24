import Foundation

/// The one piece of the app's design the extension's KIFF files use (a taf's next run, in
/// `KIFFMapping`), here alone: the rest of `Tafs/Design` is the app's views.
extension Date {
    var shortTime: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: self)
    }
}
