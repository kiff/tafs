import ActivityKit
import SwiftUI
import WidgetKit

@main
struct TafsWidgets: WidgetBundle {
    var body: some Widget {
        HeldActivityWidget()
    }
}

private let amber = Color(red: 1.0, green: 0x9A / 255.0, blue: 0x1A / 255.0)
private let ink = Color(red: 0xF4 / 255.0, green: 0xEF / 255.0, blue: 0xE6 / 255.0)
private let dim = Color(red: 0x9D / 255.0, green: 0x97 / 255.0, blue: 0x8D / 255.0)
private let openURL = URL(string: "tafs://needs-you")!

/// The held call counting down on the lock screen and in the Dynamic Island.
/// Tapping it opens Needs you, where the owner answers it.
struct HeldActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: HeldActivityAttributes.self) { context in
            LockScreenView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.75))
                .activitySystemActionForegroundColor(ink)
                .widgetURL(openURL)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(context.state.tafName).font(.headline).foregroundColor(ink)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(timerInterval: Date()...max(Date(), context.state.expiresAt), countsDown: true)
                        .font(.system(.title3, design: .monospaced).weight(.semibold))
                        .foregroundColor(amber)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 70)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text([context.state.amount, context.state.summary].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline.weight(.semibold)).foregroundColor(ink)
                        Text(context.state.reason).font(.caption).foregroundColor(dim)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                Text("t").font(.system(.body, design: .serif).weight(.bold)).foregroundColor(amber)
            } compactTrailing: {
                Text(timerInterval: Date()...max(Date(), context.state.expiresAt), countsDown: true)
                    .monospacedDigit()
                    .foregroundColor(amber)
                    .frame(maxWidth: 44)
            } minimal: {
                Text("t").font(.system(.body, design: .serif).weight(.bold)).foregroundColor(amber)
            }
            .widgetURL(openURL)
        }
    }
}

private struct LockScreenView: View {
    let state: HeldActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("\(state.tafName) is waiting on you").font(.subheadline.weight(.semibold)).foregroundColor(ink)
                Spacer()
                Text(state.waiting > 1 ? "\(state.waiting) waiting" : "tafs").font(.caption).foregroundColor(dim)
            }
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    if let amount = state.amount {
                        Text(amount).font(.system(size: 30, design: .serif)).foregroundColor(ink)
                    }
                    Text(state.summary).font(.footnote).foregroundColor(ink).lineLimit(1)
                    Text(state.reason).font(.caption).foregroundColor(dim).lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(timerInterval: Date()...max(Date(), state.expiresAt), countsDown: true)
                        .font(.system(size: 26, weight: .semibold, design: .monospaced))
                        .foregroundColor(amber)
                        .multilineTextAlignment(.trailing)
                    Text("then refused").font(.caption2).foregroundColor(dim)
                }
            }
            Text("Tap to answer").font(.caption2).foregroundColor(dim)
        }
        .padding(16)
    }
}
