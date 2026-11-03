import SwiftUI

/// The taf's universal remote: When, What and Where around its Why.
/// All stops, words and fixed size choices come from tafs cloud.
struct TafKnobView: View {
    /// What the face shows: the job, a chosen stop, or a held call that waits on the owner.
    enum Face: Equatable {
        case overview
        case chosen(TafsCloud.Knob.Ring, String)
        case held(UUID)
    }

    let knob: TafsCloud.Knob
    @Binding var face: Face
    /// The held calls the face can show (K3), the taf's own.
    var held: [HeldCall] = []
    /// The face was tapped on a chosen stop whose `tap` says it does something.
    var press: (TafsCloud.Knob.Ring, TafsCloud.Knob.Item) -> Void = { _, _ in }
    var doors: [TafsCloud.Door] = []
    var knocks: [TafsCloud.Knock] = []
    var paused = false
    var manages = true
    var busy = false
    var askCoach: () -> Void = {}
    var pause: () -> Void = {}
    var adjust: (TafsCloud.Knob.Item, Int) -> Void = { _, _ in }
    var canAdjust: (TafsCloud.Knob.Item, Int) -> Bool = { _, _ in false }
    /// The face was tapped on a held call: approve it once.
    var approve: (HeldCall) -> Void = { _ in }

    private var chosen: (ring: TafsCloud.Knob.Ring, id: String)? {
        if case .chosen(let ring, let id) = face { return (ring, id) }
        return nil
    }
    private var heldCall: HeldCall? {
        if case .held(let id) = face { return held.first { $0.id == id } }
        return nil
    }
    /// Each ring's turn, in stops.
    @State private var arrivals: Set<String> = []
    @State private var doorCounts: [String: Int] = [:]
    @State private var filter: KnobNavigation.Filter = .all
    @State private var turns: [TafsCloud.Knob.Ring: Int] = [:]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    typealias Ring = TafsCloud.Knob.Ring

    /// The design's measurements, on its 360 grid.
    enum Grid {
        static let size: CGFloat = 360
        static let face: CGFloat = 64
        static let radii: [Ring: (inner: CGFloat, outer: CGFloat)] = [.wakes: (68, 104), .what: (108, 140), .results: (144, 170)]
        static let rim: (inner: CGFloat, outer: CGFloat) = (174, 178)
    }

    static func color(_ ring: Ring) -> Color {
        switch ring {
        case .wakes: return Color(hex: 0x0B7DA4)
        case .what: return Color(hex: 0x2A64F5)
        case .results: return Color(hex: 0x7B4DFF)
        }
    }

    var body: some View {
        VStack(spacing: 14) {
            Button(action: askCoach) {
                Label("Ask Coach or find anything", systemImage: "magnifyingglass")
                    .scaledFont(14).foregroundColor(Theme.dim)
                    .frame(maxWidth: .infinity, alignment: .leading).frame(minHeight: 46)
                    .padding(.horizontal, 14)
                    .background(Capsule().fill(Theme.panel))
                    .overlay(Capsule().stroke(Theme.line))
            }
            .buttonStyle(.plain).accessibilityIdentifier("knob.coach")
            serviceMenu
            GeometryReader { g in
                let k = min(g.size.width, g.size.height) / Grid.size
                ZStack {
                    rim(k)
                    ForEach(Ring.allCases, id: \.self) { ring in band(ring, k) }
                    pointer(k)
                    face(k)
                }
                .frame(width: Grid.size * k, height: Grid.size * k)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .aspectRatio(1, contentMode: .fit)
            Text(note)
                .scaledFont(15).foregroundColor(Theme.soft).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: 44, alignment: .top)
                .padding(.horizontal, 12)
                .accessibilityIdentifier("knob.note")
            remote
            if face != .overview {
                Button("‹ Why") { withMotion { face = .overview } }
                    .scaledFont(12).foregroundColor(Theme.dim).frame(minHeight: 36)
                    .accessibilityIdentifier("knob.overview")
            }
        }
        .onAppear { doorCounts = Dictionary(uniqueKeysWithValues: doors.map { ($0.id, $0.today) }) }
        .onChange(of: doors.map { "\($0.id):\($0.today)" }) { _ in
            for door in doors {
                if let before = doorCounts[door.id], door.today > before { arrivals.insert(door.id) }
            }
            doorCounts = Dictionary(uniqueKeysWithValues: doors.map { ($0.id, $0.today) })
        }
        .task(id: arrivals) {
            guard !arrivals.isEmpty else { return }
            do { try await Task.sleep(nanoseconds: 3_000_000_000) } catch { return }
            arrivals = []
        }
        .onChange(of: filter) { _ in
            turns = [:]
            face = .overview
        }
        .onChange(of: face) { _ in alignSelection() }
        .onChange(of: knob) { _ in
            if case .connection(let grant) = filter, !knob.connections.contains(where: { $0.grant == grant }) {
                filter = .all
            }
            if let chosen, !items(chosen.ring).contains(where: { $0.id == chosen.id }) { face = .overview }
            alignSelection()
        }
    }

    private var filterName: String {
        switch filter {
        case .all: return "All services"
        case .taf: return "This taf"
        case .connection(let id): return knob.connections.first { $0.grant == id }?.name ?? "All services"
        }
    }

    private var serviceMenu: some View {
        Menu {
            Button { filter = .all } label: { Label("All services", systemImage: "circle.grid.2x2") }
            Button { filter = .taf } label: { Label("This taf", systemImage: "circle") }
            ForEach(knob.connections) { connection in
                let count = KnobNavigation.stops(knob, filter: .connection(connection.grant)).count
                Button { filter = .connection(connection.grant) } label: {
                    Label("\(connection.name) · \(count) stops", systemImage: "app.connected.to.app.below.fill")
                }
            }
        } label: {
            HStack(spacing: 10) {
                Text("SHOWING").scaledFont(10, weight: .semibold, design: .monospaced).foregroundColor(Theme.dim)
                Image(systemName: "circle.grid.2x2.fill").foregroundColor(Self.color(.wakes))
                Text(filterName).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                Spacer(minLength: 4)
                Image(systemName: "chevron.down").foregroundColor(Theme.dim)
            }
            .padding(.horizontal, 14).frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 14).fill(Theme.panel))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
        }
        .accessibilityLabel("Showing, " + filterName).accessibilityIdentifier("knob.showing")
    }

    private var remote: some View {
        HStack(alignment: .top, spacing: 8) {
            remoteButton("Back", symbol: "backward.end.fill", enabled: !allStops.isEmpty) { walk(-1) }
            remoteButton("Lower", symbol: "minus", enabled: manages && !busy && chosenItem.map { canAdjust($0, -1) } == true) {
                if let item = chosenItem { adjust(item, -1) }
            }
            remoteButton(paused ? "Resume" : "Pause", symbol: "power", enabled: manages && !busy, power: true, action: pause)
            remoteButton("Raise", symbol: "plus", enabled: manages && !busy && chosenItem.map { canAdjust($0, 1) } == true) {
                if let item = chosenItem { adjust(item, 1) }
            }
            remoteButton("Next", symbol: "forward.end.fill", enabled: !allStops.isEmpty) { walk(1) }
        }
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

    private func items(_ ring: Ring) -> [TafsCloud.Knob.Item] { KnobNavigation.items(knob, ring: ring, filter: filter) }
    private var allStops: [KnobNavigation.Stop] { KnobNavigation.stops(knob, filter: filter) }
    private func visibleItems(_ ring: Ring) -> [TafsCloud.Knob.Item] {
        KnobNavigation.page(items(ring), selectedID: chosen?.ring == ring ? chosen?.id : nil, capacity: ring == .wakes ? 4 : 6)
    }
    private func walk(_ direction: Int) {
        guard let stop = KnobNavigation.next(allStops, ring: chosen?.ring, id: chosen?.id, direction: direction) else { return }
        choose(stop.ring, stop.item)
    }
    private func alignSelection() {
        guard let chosen, let i = visibleItems(chosen.ring).firstIndex(where: { $0.id == chosen.id }) else { return }
        let n = visibleItems(chosen.ring).count
        withMotion(reduceMotion ? nil : .spring(response: 0.5, dampingFraction: 0.8)) {
            turns[chosen.ring] = Self.nearestTurn(from: turns[chosen.ring] ?? 0, to: i, stops: n)
        }
    }

    // MARK: Parts

    private func band(_ ring: Ring, _ k: CGFloat) -> some View {
        let r = Grid.radii[ring]!
        let items = visibleItems(ring)
        let on = chosen?.ring == ring
        let dim = chosen != nil && !on
        let step = items.isEmpty ? 0 : 360 / Double(items.count)
        let mid = (r.inner + r.outer) / 2
        return ZStack {
            Circle()
                .stroke(Self.color(ring).opacity(on ? 0.16 : 0.07), lineWidth: (r.outer - r.inner) * k)
                .frame(width: mid * 2 * k, height: mid * 2 * k)
            if items.isEmpty {
                Text("—").foregroundColor(Self.color(ring).opacity(0.4)).offset(y: -mid * k)
                    .accessibilityLabel(knob.title(ring) + ", no stops")
            }
            ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                stop(item, ring: ring, index: i, count: items.count, k: k)
            }
        }
        .rotationEffect(.degrees(-Double(turns[ring] ?? 0) * step))
        .opacity(dim ? 0.4 : 1)
    }

    private func stop(_ item: TafsCloud.Knob.Item, ring: Ring, index: Int, count: Int, k: CGFloat) -> some View {
        let r = Grid.radii[ring]!
        let mid = (r.inner + r.outer) / 2
        let angle = Double(index) * 360 / Double(max(count, 1))
        let on = chosen?.ring == ring
        let picked = on && chosen?.id == item.id
        let color = picked ? Self.color(ring) : on ? Theme.ink : Self.color(ring).opacity(item.on ? 0.85 : 0.45)
        let label = item.step.map { "\($0) · \(item.label)" } ?? item.label
        return ZStack {
            doorArc(item, ring: ring, angle: angle, count: count, k: k)
            Button { choose(ring, item) } label: {
                Text(label).scaledFont(12, weight: picked ? .bold : .medium, relativeTo: .caption)
                    .foregroundColor(color).lineLimit(1)
                    .frame(width: 100 * k, height: 36 * k)
            }
            .buttonStyle(.plain)
            .overlay(alignment: .bottom) { arrivalLight(item, ring: ring, k: k) }
            .offset(y: -mid * k)
            .rotationEffect(.degrees(angle))
            .accessibilityLabel([label, knob.title(ring), item.state].compactMap { $0 }.joined(separator: ", "))
            .accessibilityAddTraits(picked ? .isSelected : [])
        }
    }

    @ViewBuilder
    private func doorArc(_ item: TafsCloud.Knob.Item, ring: Ring, angle: Double, count: Int, k: CGFloat) -> some View {
        if let doorID = item.door, let door = doors.first(where: { $0.id == doorID }) {
            let radius = Grid.radii[ring]!.inner + 2
            let color = knocks.contains { $0.door == doorID } ? Theme.amber : door.resting ? Theme.dim : Self.color(ring).opacity(door.open ? 0.85 : 0.3)
            let end = CGFloat(max(0.015, 1 / Double(max(count, 1)) - 0.015))
            Circle().trim(from: 0.015, to: end)
                .stroke(color, style: StrokeStyle(lineWidth: 2 * k, dash: door.open ? [] : [3 * k, 4 * k]))
                .frame(width: radius * 2 * k, height: radius * 2 * k)
                .rotationEffect(.degrees(angle - 90 - 180 / Double(max(count, 1))))
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private func arrivalLight(_ item: TafsCloud.Knob.Item, ring: Ring, k: CGFloat) -> some View {
        if let doorID = item.door, let door = doors.first(where: { $0.id == doorID }) {
            let color = knocks.contains { $0.door == doorID } ? Theme.amber : arrivals.contains(doorID) ? Theme.ok
                : door.resting ? Theme.dim : door.open ? Self.color(ring).opacity(0.4) : Theme.line
            Circle().fill(color).frame(width: 5 * k, height: 5 * k).accessibilityHidden(true)
        }
    }

    /// Today's work around the rim: done, working, retrying, needs you.
    private func rim(_ k: CGFloat) -> some View {
        let parts: [(Int, Color)] = [(knob.rim.done, Theme.ok), (knob.rim.working, Theme.blue),
                                     (knob.rim.retrying, Theme.amber), (knob.rim.needsYou, Theme.no)]
        let total = Double(max(knob.rim.total, 1))
        let gap = 1.2 / 360
        var from = 0.0
        let arcs = parts.filter { $0.0 > 0 }.map { part -> (Double, Double, Color) in
            let span = Double(part.0) / total
            defer { from += span }
            return (from + gap, from + span - gap, part.1)
        }
        let mid = (Grid.rim.inner + Grid.rim.outer) / 2
        return ZStack {
            Circle().stroke(Theme.line, lineWidth: (Grid.rim.outer - Grid.rim.inner) * k)
            ForEach(Array(arcs.enumerated()), id: \.offset) { _, arc in
                Circle().trim(from: arc.0, to: max(arc.0, arc.1))
                    .stroke(arc.2, lineWidth: (Grid.rim.outer - Grid.rim.inner) * k)
                    .rotationEffect(.degrees(-90))
            }
        }
        .frame(width: mid * 2 * k, height: mid * 2 * k)
        .accessibilityHidden(true)
    }

    /// Points at the chosen ring's top stop, or at the rim.
    private func pointer(_ k: CGFloat) -> some View {
        let r = chosen.map { Grid.radii[$0.ring]!.outer } ?? Grid.rim.outer
        return Triangle()
            .fill(chosen.map { Self.color($0.ring) } ?? Theme.ink)
            .frame(width: 14 * k, height: 11 * k)
            .rotationEffect(.degrees(180))
            .offset(y: -(r + 7) * k)
            .accessibilityHidden(true)
    }

    private func face(_ k: CGFloat) -> some View {
        let item = chosenItem
        let tint = heldCall != nil ? Theme.amberText : chosen.map { Self.color($0.ring) } ?? Theme.ink
        let big = heldCall.map { $0.proposed.amount?.formatted ?? $0.proposed.summary } ?? item.map { item in
            if let size = item.size, size.choices.indices.contains(size.index) { return size.choices[size.index].label }
            return item.state ?? item.label
        } ?? (knob.why ?? knob.does.label)
        return Button {
            if let call = heldCall {
                if call.approvableOnce, manages, !busy { approve(call) }
                return
            }
            if item?.kind == "add_door" { askCoach(); return }
            guard let chosen, let item, item.tap != nil, manages, !busy else {
                if chosen == nil { walk(1) }
                return
            }
            press(chosen.ring, item)
        } label: {
            VStack(spacing: 4 * k) {
                Text(kicker).scaledFont(10, weight: .bold, relativeTo: .caption2).tracking(1.2).foregroundColor(Theme.dim)
                    .lineLimit(1).minimumScaleFactor(0.7)
                Text(big).scaledFont(big.count > 5 ? 24 : 32, weight: .heavy, relativeTo: .title).foregroundColor(tint)
                    .lineLimit(3).minimumScaleFactor(0.6)
                Text(hint).scaledFont(11, relativeTo: .caption2).foregroundColor(Theme.dim).lineLimit(1).minimumScaleFactor(0.7)
            }
            .padding(.horizontal, 10 * k)
            .frame(width: Grid.face * 2 * k, height: Grid.face * 2 * k)
            .background(Circle().fill(Theme.panel).shadow(color: tint.opacity(0.18), radius: 14 * k))
            .overlay(Circle().stroke(tint.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel([kicker, big, hint].filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityIdentifier("knob.face")
    }

    // MARK: State

    private var chosenItem: TafsCloud.Knob.Item? {
        chosen.flatMap { c in items(c.ring).first { $0.id == c.id } }
    }

    private var kicker: String {
        if let call = heldCall { return call.proposed.summary.uppercased() }
        guard let chosen, let item = chosenItem else { return Self.why }
        return "\(knob.title(chosen.ring)) · \(item.label)".uppercased()
    }

    private var hint: String {
        if let call = heldCall { return call.approvableOnce ? Self.approveOnce : "" }
        guard chosen != nil else { return Self.tapARing }
        if let item = chosenItem, let size = item.size,
           size.choices.indices.contains(size.index) { return size.label + ": − and +" }
        return chosenItem?.tap ?? "Ask Coach to change"
    }

    private var note: String { heldCall?.reason ?? chosenItem?.words ?? knob.does.line }

    private func choose(_ ring: Ring, _ item: TafsCloud.Knob.Item?) {
        guard let item else { return }
        withMotion(reduceMotion ? nil : .spring(response: 0.5, dampingFraction: 0.8)) {
            face = .chosen(ring, item.id)
        }
        alignSelection()
    }

    static func nearestTurn(from turn: Int, to index: Int, stops: Int) -> Int {
        KnobNavigation.nearestTurn(from: turn, to: index, stops: stops)
    }

    /// The dial's own words, the same on iPhone, iPad and Android.
    static let why = "WHY"
    static let tapARing = "Tap a ring"
    static let approveOnce = "Tap to approve once"
}
