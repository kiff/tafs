import Foundation

/// The pieces of the app's design the extension's model files use (a taf's next run, in
/// `KIFFMapping`; when a Card's extra room ends, in `Models`), here alone: the rest of
/// `Tafs/Design` is the app's views.
extension Date {
    /// The time of day in this device's zone, in the chat's own clock: the device's 12- or 24-hour setting, in the
    /// app's language (`ChatTime.time`, kiff/tafs#2195).
    var shortTime: String { ChatTime.time(self, zone: .current) }

    var endsWords: String {
        Calendar.current.isDateInToday(self) ? shortTime : formatted(date: .abbreviated, time: .shortened)
    }
}
