import Foundation

/// The pieces of the app's design the extension's model files use (a taf's next run, in
/// `KIFFMapping`; when a Card's extra room ends, in `Models`), here alone: the rest of
/// `Tafs/Design` is the app's views.
extension Date {
    var shortTime: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: self)
    }

    var endsWords: String {
        Calendar.current.isDateInToday(self) ? shortTime : formatted(date: .abbreviated, time: .shortened)
    }
}
