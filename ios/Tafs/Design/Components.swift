import SwiftUI

struct TafAvatar: View {
    let taf: Taf
    var size: CGFloat = 40

    var body: some View {
        Text(taf.initial)
            .font(Theme.display(size * 0.5))
            .foregroundColor(.white)
            .frame(width: size, height: size)
            .background(Theme.tafColor(taf.colorIndex))
            .clipShape(RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
            // Its doors (docs/doors.md): a lit rim while one is open.
            .overlay(DoorRim(lights: taf.doors ?? [], size: size, color: Theme.tafColor(taf.colorIndex)))
            .accessibilityHidden(true)
    }
}

struct StateChip: View {
    let state: TafState

    var body: some View {
        Text(state.label)
            .font(.system(size: 12, weight: .medium))
            .padding(.horizontal, 10).padding(.vertical, 4)
            .foregroundColor(color)
            .background(color.opacity(0.15))
            .clipShape(Capsule())
    }

    private var color: Color {
        switch state {
        case .working: return Theme.ok
        case .waiting: return Theme.amber
        case .revoked, .halted: return Theme.no
        case .idle, .paused: return Theme.soft
        }
    }
}

struct Meter: View {
    let fraction: Double
    var color: Color = Theme.ok

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.ink.opacity(0.1))
                Capsule().fill(color).frame(width: geo.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 5)
        .accessibilityHidden(true)
    }
}

struct Eyebrow: View {
    let text: String
    var color: Color = Theme.dim

    var body: some View {
        // Text's own tracking, before the font: a view's is macOS 13 and later (kiff/tafs#85).
        Text(text.uppercased())
            .tracking(0.7)
            .scaledFont(12, design: .monospaced, relativeTo: .caption)
            .foregroundColor(color)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        StyledLabel(configuration: configuration)
    }

    private struct StyledLabel: View {
        let configuration: Configuration
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            // Not available yet, it stays readable on any ground: a neutral fill with the
            // ink's own soft colour, not the amber faded (kiff/tafs#206 6.1).
            configuration.label
                .scaledFont(16, weight: .semibold)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 18)
                .frame(maxWidth: .infinity, minHeight: 54)
                .foregroundColor(ink)
                .background(fill)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }

        private var ink: Color { isEnabled ? Theme.amberInk : Theme.soft }

        @ViewBuilder private var fill: some View {
            if isEnabled {
                Theme.brand.opacity(configuration.isPressed ? 0.8 : 1)
            } else {
                Theme.dim.opacity(0.22)
            }
        }
    }
}

struct OutlineButtonStyle: ButtonStyle {
    var color: Color = Theme.ink

    func makeBody(configuration: Configuration) -> some View {
        StyledLabel(configuration: configuration, color: color)
    }

    private struct StyledLabel: View {
        let configuration: Configuration
        let color: Color
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            configuration.label
                .scaledFont(15, weight: .medium)
                .frame(maxWidth: .infinity, minHeight: 48)
                .foregroundColor(color)
                .background(Theme.ink.opacity(configuration.isPressed ? 0.08 : 0))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(color.opacity(0.4), lineWidth: 1))
                .opacity(isEnabled ? 1 : 0.4)
        }
    }
}

struct Panel<Content: View>: View {
    var stroke: Color = Theme.line
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.panel)
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(stroke, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// A panel that opens a screen: the whole panel takes the tap, its padding too, not only its
/// words and caret (the 755 retest), with the caret every row that opens something has.
struct PanelLink<Destination: View, Label: View>: View {
    @ViewBuilder var destination: () -> Destination
    @ViewBuilder var label: () -> Label

    var body: some View {
        NavigationLink {
            destination()
        } label: {
            Panel {
                HStack(spacing: 12) {
                    label()
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                        .accessibilityHidden(true)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

extension View {
    func screenBackground() -> some View {
        background(Theme.background.ignoresSafeArea())
    }
}

/// The time zone every time the app shows is in (kiff/tafs#211, 6.11): the KIFF account's,
/// the one that ends a Card's day and that holds' deadlines, Activity and the Record are
/// kept in, whatever zone this device is in. Without an account (sample mode, signed out)
/// it's the device's. Times sent to a server stay in ISO 8601, as they were.
enum AccountTime {
    /// Set on the main actor as the account loads, read wherever a time is drawn.
    /// `nonisolated(unsafe)`: written only when the account is read or signed out, and a
    /// formatter that reads it a moment early or late draws one frame in the other zone,
    /// which the next render puts right.
    nonisolated(unsafe) private(set) static var zone: TimeZone = .current

    /// The account's zone by its identifier; nil (no account) is the device's. An
    /// identifier this device doesn't know falls back to UTC, KIFF's own default.
    static func use(_ identifier: String?) {
        zone = identifier.map { TimeZone(identifier: $0) ?? TimeZone(identifier: "UTC")! } ?? .current
    }

    /// The zone's short name after a time (" CET") when the account's zone shows a
    /// different clock from this device's at that moment, so a travelling owner doesn't
    /// read it as their own local time; nothing when the clocks agree.
    static func label(at date: Date) -> String {
        guard zone.secondsFromGMT(for: date) != TimeZone.current.secondsFromGMT(for: date) else { return "" }
        return zone.abbreviation(for: date).map { " " + $0 } ?? ""
    }

    /// A calendar in the account's zone, for "today" and the day a time falls on.
    static var calendar: Calendar {
        var c = Calendar.current
        c.timeZone = zone
        return c
    }
}

extension Date {
    /// The time of day, as 14:05, in the account's zone.
    var shortTime: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        f.timeZone = AccountTime.zone
        return f.string(from: self)
    }

    /// When something ends, in the account's zone: the time alone today, with its date on a
    /// later day, as the chat's timestamps are ("00:00", "1 Nov 2026, 00:00").
    var endsWords: String {
        AccountTime.calendar.isDateInToday(self) ? shortTime : shown(date: .abbreviated, time: .shortened)
    }

    /// `shortTime`, with the account zone's short name when it isn't this device's.
    var zonedShortTime: String { shortTime + AccountTime.label(at: self) }

    /// `shown(date:time:)`, with the account zone's short name when it isn't this device's.
    func zonedShown(date: Date.FormatStyle.DateStyle, time: Date.FormatStyle.TimeStyle) -> String {
        shown(date: date, time: time) + AccountTime.label(at: self)
    }

    /// This date in a style, in the account's zone.
    func shown(date: Date.FormatStyle.DateStyle, time: Date.FormatStyle.TimeStyle) -> String {
        shown(Date.FormatStyle(date: date, time: time))
    }

    /// This date in a format style (such as `.dateTime.weekday(.wide)`), in the account's zone.
    func shown(_ style: Date.FormatStyle) -> String {
        var style = style
        style.timeZone = AccountTime.zone
        return formatted(style)
    }
}

/// kiff.dev's Terms and Privacy policy, which cover tafs.
enum Legal {
    static let terms = URL(string: "https://kiff.dev/terms#tafs")!
    static let privacy = URL(string: "https://kiff.dev/privacy#tafs")!
    /// KIFF's status page, served apart from KIFF, so it answers when KIFF doesn't.
    static let status = URL(string: "https://status.kiff.dev")!
}

/// While a refresh can't get through (the 930 test): one quiet line under the account's name,
/// with the status page beside it. Nothing when all is well.
struct UnreachableLine: View {
    let words: String
    /// Whether the app is still trying again by itself.
    var retrying = true

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(Theme.amber).frame(width: 7, height: 7)
            Text(retrying ? "\(words) · trying again" : words).scaledFont(12).foregroundColor(Theme.dim)
            Link("Status", destination: Legal.status).scaledFont(12, weight: .semibold)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("unreachable")
    }
}

/// This phone can't approve until its person approves it again in their browser (its
/// approval key is gone, or KIFF holds another): said with the way out, in place of a button
/// that would fail. Refuse stays. While the browser hasn't answered, it shows the code to
/// check there, with Cancel. Sign in again stays as the fallback, for a KIFF without re-link.
struct ApprovalKeyLostLine: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var starting = false
    @State private var signingOut = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let relink = model.relink {
                Text("Waiting for your browser… Check that it shows \(relink.userCode), and approve only with this \(ThisDevice.kind) in your hands.")
                    .scaledFont(13).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Button("Open the browser again") { openURL(relink.url) }
                        .buttonStyle(OutlineButtonStyle())
                    Button("Cancel") { model.cancelRelink() }
                        .buttonStyle(OutlineButtonStyle(color: Theme.no))
                }
            } else {
                Text(KIFFClient.approvalKeyLost)
                    .scaledFont(13).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    starting = true
                    Task {
                        if let url = await model.startRelink() { openURL(url) }
                        starting = false
                    }
                } label: {
                    Label("Approve again in your browser", systemImage: Biometry.symbol)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(starting)
                Button {
                    signingOut = true
                    // Signing out ends the session at KIFF, the old approval key with it; the
                    // sign-in screen follows, and the next sign-in registers the new key.
                    Task { _ = await model.signOut(); signingOut = false }
                } label: {
                    Text("Sign in again instead").scaledFont(13)
                }
                .buttonStyle(.plain)
                .foregroundColor(Theme.soft)
                .disabled(signingOut || starting)
                if let why = model.signOutError {
                    Text(why).scaledFont(12).foregroundColor(Theme.amber)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .accessibilityIdentifier("approval-key-lost")
    }
}
