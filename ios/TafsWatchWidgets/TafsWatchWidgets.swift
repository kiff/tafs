import SwiftUI
import WidgetKit

/// tafs on the watch face (Watch row 24, step 1; docs/watch-app.md, "The complication"): "Ask tafs", which opens the app on
/// Coach's home, listening. It shows nothing of the account (no count, no taf, no words), so there's nothing to hide while the
/// watch is locked, and it never asks the phone or refreshes. The count of what waits comes later, with an App Group.
@main
struct TafsWatchWidgets: WidgetBundle {
    var body: some Widget {
        AskTafsWidget()
    }
}

struct AskTafsWidget: Widget {
    static let kind = "tafs.ask"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: AskTafsProvider()) { _ in
            AskTafsView()
                .widgetURL(ComplicationLink.ask)
                .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName(Words.Coach.ask)
        .description(Words.Watch.voiceTextOnly)
        .supportedFamilies([.accessoryCircular, .accessoryCorner, .accessoryRectangular, .accessoryInline])
    }
}

/// One entry, never refreshed: the complication's face doesn't change.
struct AskTafsProvider: TimelineProvider {
    struct Entry: TimelineEntry { let date: Date }

    func placeholder(in context: Context) -> Entry { Entry(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) { completion(Entry(date: .now)) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        completion(Timeline(entries: [Entry(date: .now)], policy: .never))
    }
}

/// The microphone, and "Ask Coach" where the family has room, in the system's tint.
struct AskTafsView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryInline:
            Label(Words.Coach.ask, systemImage: "mic.fill")
        case .accessoryRectangular:
            HStack(spacing: 6) {
                Image(systemName: "mic.fill").font(.title3)
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: "tafs").font(.headline)
                    Text(Words.Coach.ask).font(.caption).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        case .accessoryCorner:
            Image(systemName: "mic.fill")
                .font(.title2)
                .widgetLabel(Words.Coach.ask)
                .accessibilityLabel(Words.Coach.ask)
        default:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "mic.fill").font(.title2)
            }
            .accessibilityLabel(Words.Coach.ask)
        }
    }
}
