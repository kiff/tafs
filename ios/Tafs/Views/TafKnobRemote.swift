import SwiftUI

/// The remote stays above the tab bar while its dial and door details scroll.
struct TafKnobRemote: View {
    let knob: TafsCloud.Knob
    @Binding var face: TafKnobView.Face
    let filter: KnobNavigation.Filter
    let paused: Bool
    let manages: Bool
    let busy: Bool
    var pause: () -> Void
    var adjust: (TafsCloud.Knob.Item, Int) -> Void
    var canAdjust: (TafsCloud.Knob.Item, Int) -> Bool

    private var allStops: [KnobNavigation.Stop] { KnobNavigation.stops(knob, filter: filter) }
    private var chosenItem: TafsCloud.Knob.Item? {
        guard case .chosen(let ring, let id) = face else { return nil }
        return KnobNavigation.items(knob, ring: ring, filter: filter).first { $0.id == id }
    }
    private func walk(_ direction: Int) {
        let ring: TafsCloud.Knob.Ring?
        let id: String?
        if case .chosen(let selectedRing, let selectedID) = face { ring = selectedRing; id = selectedID }
        else { ring = nil; id = nil }
        guard let stop = KnobNavigation.next(allStops, ring: ring, id: id, direction: direction) else { return }
        face = .chosen(stop.ring, stop.item.id)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            remoteButton("Back", symbol: "backward.end.fill", enabled: !allStops.isEmpty) { walk(-1) }
            remoteButton("Lower", symbol: "minus", enabled: manages && !busy && chosenItem.map { canAdjust($0, -1) } == true) {
                if let item = chosenItem { adjust(item, -1) }
            }
            remoteButton(paused ? "Resume" : "Pause", symbol: "power", enabled: manages && !busy && knob.canPause, power: true, action: pause)
            remoteButton("Raise", symbol: "plus", enabled: manages && !busy && chosenItem.map { canAdjust($0, 1) } == true) {
                if let item = chosenItem { adjust(item, 1) }
            }
            remoteButton("Next", symbol: "forward.end.fill", enabled: !allStops.isEmpty) { walk(1) }
        }
        .accessibilityIdentifier("knob.remote")
    }

    private func remoteButton(_ title: String, symbol: String, enabled: Bool, power: Bool = false,
                              action: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            Button(action: action) {
                Image(systemName: symbol).font(.system(size: 19, weight: .semibold))
                    .frame(maxWidth: .infinity).frame(minHeight: 52)
                    .foregroundColor(power ? .white : enabled ? Theme.ink : Theme.dim.opacity(0.4))
                    .background(RoundedRectangle(cornerRadius: power ? 26 : 16).fill(power ? (paused ? Theme.dim : Theme.ink) : Theme.panel))
                    .overlay(RoundedRectangle(cornerRadius: power ? 26 : 16).stroke(Theme.line))
            }
            .buttonStyle(.plain).disabled(!enabled)
            .accessibilityLabel(title).accessibilityIdentifier("knob." + (power ? "power" : title.lowercased()))
            Text(title.uppercased()).scaledFont(9, weight: .semibold, design: .monospaced).foregroundColor(Theme.dim)
                .lineLimit(1).minimumScaleFactor(0.7)
        }.frame(maxWidth: .infinity)
    }

}
