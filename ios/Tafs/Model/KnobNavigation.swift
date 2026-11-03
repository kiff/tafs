import Foundation

/// Presentation only: filtering and walking the server's stops never changes their meaning.
enum KnobNavigation {
    typealias Knob = TafsCloud.Knob
    enum Filter: Hashable { case all, taf, connection(String) }
    struct Stop: Equatable {
        var ring: Knob.Ring
        var item: Knob.Item
    }

    static func items(_ knob: Knob, ring: Knob.Ring, filter: Filter) -> [Knob.Item] {
        let items = knob.items(ring).filter { item in
            switch filter {
            case .all: return true
            case .taf: return item.grant == nil
            case .connection(let grant): return item.grant == grant
            }
        }
        return items
    }

    static func stops(_ knob: Knob, filter: Filter) -> [Stop] {
        knob.orderedRings.flatMap { ring in items(knob, ring: ring, filter: filter).map { Stop(ring: ring, item: $0) } }
    }

    static func next(_ stops: [Stop], ring: Knob.Ring?, id: String?, direction: Int) -> Stop? {
        guard !stops.isEmpty else { return nil }
        guard let index = stops.firstIndex(where: { $0.ring == ring && $0.item.id == id }) else {
            return direction < 0 ? stops.last : stops.first
        }
        return stops[(index + direction % stops.count + stops.count) % stops.count]
    }

    /// Keep labels readable on a crowded ring. Every stop remains reachable with Back/Next;
    /// choosing a stop moves the ring's visible window to its page.
    static func page(_ items: [Knob.Item], selectedID: String?, capacity: Int = 6) -> [Knob.Item] {
        guard capacity > 0 else { return [] }
        let index = items.firstIndex { $0.id == selectedID } ?? 0
        let start = index / capacity * capacity
        return Array(items.dropFirst(start).prefix(capacity))
    }

    /// Text on the lower half turns over inside its rotating stop so it reads upright.
    static func labelFlip(at angle: Double) -> Double {
        let wrapped = angle.truncatingRemainder(dividingBy: 360)
        let normalized = wrapped < 0 ? wrapped + 360 : wrapped
        return normalized > 90 && normalized < 270 ? 180 : 0
    }

    static func nearestTurn(from turn: Int, to index: Int, stops: Int) -> Int {
        guard stops > 0 else { return turn }
        let at = ((turn % stops) + stops) % stops
        var d = index - at
        if Double(d) > Double(stops) / 2 { d -= stops }
        if Double(d) < -Double(stops) / 2 { d += stops }
        return turn + d
    }
}
