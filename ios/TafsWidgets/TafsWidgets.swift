import ActivityKit
import SwiftUI
import WidgetKit

@main
struct TafsWidgets: WidgetBundle {
    var body: some Widget {
        HeldActivityWidget()
    }
}

// The app's palette, from the mascot. The Dynamic Island is always black (iOS draws
// it), so there the text is light and the countdown takes the mascot's orange.
private let cream = Color(red: 0xFB / 255.0, green: 0xF7 / 255.0, blue: 0xEE / 255.0)
private let ink = Color(red: 0x1B / 255.0, green: 0x17 / 255.0, blue: 0x26 / 255.0)
private let dim = Color(red: 0x8A / 255.0, green: 0x84 / 255.0, blue: 0x96 / 255.0)
private let orange = Color(red: 1.0, green: 0x8A / 255.0, blue: 0x1F / 255.0)
private let islandText = Color(red: 0xF4 / 255.0, green: 0xEF / 255.0, blue: 0xE6 / 255.0)
private let islandDim = Color(white: 0.65)

/// The mascot, from WidgetAssets.xcassets: 28 pt at most, so the Live Activity
/// can draw it. The app's 600 px mascot is over its budget and shows as a gray shape.
private func mascot(_ size: CGFloat) -> some View {
    Image("IslandMascot").resizable().scaledToFit().frame(width: size, height: size)
}
private let openURL = URL(string: "tafs://needs-you")!

/// The held call counting down on the lock screen and in the Dynamic Island.
/// Tapping it opens Needs you, where the owner answers it.
struct HeldActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: HeldActivityAttributes.self) { context in
            LockScreenView(state: context.state)
                .activityBackgroundTint(cream)
                .activitySystemActionForegroundColor(ink)
                .widgetURL(openURL)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 6) {
                        mascot(26)
                        Text(context.state.tafName).font(.headline).foregroundColor(islandText).lineLimit(1)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: Date()...max(Date(), context.state.expiresAt), countsDown: true)
                        .font(.system(.title3, design: .monospaced).weight(.semibold))
                        .foregroundColor(orange)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 70)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text([context.state.amount, context.state.summary].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline.weight(.semibold)).foregroundColor(islandText)
                        Text(context.state.reason).font(.caption).foregroundColor(islandDim)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                mascot(22)
            } compactTrailing: {
                Text(timerInterval: Date()...max(Date(), context.state.expiresAt), countsDown: true)
                    .monospacedDigit()
                    .foregroundColor(orange)
                    .frame(maxWidth: 44)
            } minimal: {
                mascot(22)
            }
            .widgetURL(openURL)
        }
    }
}

private struct LockScreenView: View {
    let state: HeldActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                mascot(28)
                Text("\(state.tafName) is waiting on you").font(.subheadline.weight(.semibold)).foregroundColor(ink)
                Spacer()
                Text(state.waiting > 1 ? "\(state.waiting) waiting" : "tafs").font(.caption).foregroundColor(dim)
            }
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    if let amount = state.amount {
                        Text(amount).font(.system(size: 30, weight: .bold, design: .rounded)).foregroundColor(ink)
                    }
                    Text(state.summary).font(.footnote).foregroundColor(ink).lineLimit(1)
                    Text(state.reason).font(.caption).foregroundColor(dim).lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(timerInterval: Date()...max(Date(), state.expiresAt), countsDown: true)
                        .font(.system(size: 26, weight: .semibold, design: .monospaced))
                        // A plain colour: under a gradient the lock screen drew the timer once
                        // and it stood still (kiff/tafs#236, item 8). The island's moves; it's plain too.
                        .foregroundColor(orange)
                        .multilineTextAlignment(.trailing)
                    Text("then refused").font(.caption2).foregroundColor(dim)
                }
            }
            Text("Tap to answer").font(.caption2).foregroundColor(dim)
        }
        .padding(16)
    }
}
