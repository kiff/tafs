import Foundation

/// When each chat message was sent (row 44, C; docs/chat.md "When each message was sent"), as Android's `ChatTime`:
/// a small time under each message, and a header where a day starts: Today, Yesterday, then the date in the
/// language's own order ("Wed, Nov 18", "11월 18일 (수)"), with the year when it isn't this one. Times follow the
/// device's 12- or 24-hour setting, in the app's language; days and times are the account's zone (`calendar`).
enum ChatTime {
    /// Whether the device's clock reads 24 hours ("j" resolves to "H"), whatever language the app is in.
    static func deviceUses24Hour(_ device: Locale = .autoupdatingCurrent) -> Bool {
        !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: device) ?? "H").contains("a")
    }

    /// The time under a message: "14:05", or "2:05 PM" on a 12-hour device, in the app's language.
    static func time(_ date: Date, zone: TimeZone, language: Locale = Words.locale,
                     twentyFour: Bool = ChatTime.deviceUses24Hour()) -> String {
        let f = DateFormatter()
        f.locale = language
        f.timeZone = zone
        f.setLocalizedDateFormatFromTemplate(twentyFour ? "Hm" : "hm")
        return f.string(from: date)
    }

    /// The header where a day starts: Today, Yesterday, else the date with its weekday, and its year when it
    /// isn't this one.
    static func day(_ date: Date, now: Date = Date(), calendar: Calendar, language: Locale = Words.locale) -> String {
        if calendar.isDate(date, inSameDayAs: now) { return Words.Calendar.today }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) {
            return Words.Chat.Day.yesterday
        }
        let f = DateFormatter()
        f.locale = language
        f.timeZone = calendar.timeZone
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
        f.setLocalizedDateFormatFromTemplate(sameYear ? "EEEMMMd" : "EEEMMMdy")
        return f.string(from: date)
    }

    /// Whether the message at `index` starts a day: the first one, or the first on a later day than the one before.
    static func startsDay(_ dates: [Date], at index: Int, calendar: Calendar) -> Bool {
        index == 0 || !calendar.isDate(dates[index], inSameDayAs: dates[index - 1])
    }
}
