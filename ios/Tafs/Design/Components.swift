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
        case .revoked: return Theme.no
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
                .foregroundColor(isEnabled ? Theme.amberInk : Theme.soft)
                .background(isEnabled ? Theme.brand.opacity(configuration.isPressed ? 0.8 : 1) : Theme.dim.opacity(0.22))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
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

extension View {
    func screenBackground() -> some View {
        background(Theme.background.ignoresSafeArea())
    }
}

extension Date {
    var shortTime: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: self)
    }
}

/// kiff.dev's Terms and Privacy policy, which cover tafs.
enum Legal {
    static let terms = URL(string: "https://kiff.dev/terms#tafs")!
    static let privacy = URL(string: "https://kiff.dev/privacy#tafs")!
}
