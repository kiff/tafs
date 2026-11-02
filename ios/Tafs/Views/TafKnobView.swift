import SwiftUI

/// A taf's dial, from the approved design ("The knob, end to end", K1 to K3): the job in the
/// face, and Wakes it, When and Results go around it, inside to out, each stop's word along
/// its ring. The rim counts today's work. Tapping a word chooses it: its ring turns it to the
/// top and keeps its colour, the other two dim, and the face shows tafs cloud's `state` and
/// `tap` for it. It lays out what `GET /v1/tafs/{id}/knob` sends, every label and line as
/// tafs cloud wrote it; the app's own words are only "DOES", "Tap a ring" and the ring titles.
///
/// Drawn on the design's 360-point grid, scaled to the space it's given.
struct TafKnobView: View {
    let knob: TafsCloud.Knob
    /// The face was tapped on a chosen stop whose `tap` says it does something.
    var press: (TafsCloud.Knob.Ring, TafsCloud.Knob.Item) -> Void = { _, _ in }

    @State private var chosen: (ring: TafsCloud.Knob.Ring, id: String)?
    /// Each ring's turn, in stops.
    @State private var turns: [TafsCloud.Knob.Ring: Int] = [:]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    typealias Ring = TafsCloud.Knob.Ring

    /// The design's measurements, on its 360 grid.
    enum Grid {
        static let size: CGFloat = 360
        static let face: CGFloat = 64
        static let radii: [Ring: (inner: CGFloat, outer: CGFloat)] = [.wakes: (68, 104), .when: (108, 140), .results: (144, 170)]
        static let rim: (inner: CGFloat, outer: CGFloat) = (174, 178)
    }

    static func color(_ ring: Ring) -> Color {
        switch ring {
        case .wakes: return Color(hex: 0x16897C)
        case .when: return Color(hex: 0x2F6FDB)
        case .results: return Color(hex: 0x7458D0)
        }
    }

    var body: some View {
        VStack(spacing: 18) {
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
            if let line = knob.rim.line, !line.isEmpty {
                Text(line).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
        }
    }

    // MARK: Parts

    private func band(_ ring: Ring, _ k: CGFloat) -> some View {
        let r = Grid.radii[ring]!
        let items = knob.items(ring)
        let on = chosen?.ring == ring
        let dim = chosen != nil && !on
        let step = items.isEmpty ? 0 : 360 / Double(items.count)
        let mid = (r.inner + r.outer) / 2
        return ZStack {
            Circle()
                .stroke(Self.color(ring).opacity(on ? 0.16 : 0.07), lineWidth: (r.outer - r.inner) * k)
                .frame(width: mid * 2 * k, height: mid * 2 * k)
            ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                let picked = on && chosen?.id == item.id
                Button { choose(ring, item, index: i) } label: {
                    Text(item.label)
                        .scaledFont(12, weight: picked ? .bold : .medium, relativeTo: .caption)
                        .foregroundColor(picked ? Self.color(ring) : on ? Theme.ink : Self.color(ring).opacity(item.on ? 0.85 : 0.45))
                        .lineLimit(1)
                        .frame(width: 100 * k, height: 36 * k)
                }
                .buttonStyle(.plain)
                .offset(y: -mid * k)
                .rotationEffect(.degrees(Double(i) * step))
                .accessibilityLabel([item.label, ring.title, item.state].compactMap { $0 }.joined(separator: ", "))
                .accessibilityAddTraits(picked ? .isSelected : [])
            }
        }
        .rotationEffect(.degrees(-Double(turns[ring] ?? 0) * step))
        .opacity(dim ? 0.4 : 1)
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
        let tint = chosen.map { Self.color($0.ring) } ?? Theme.ink
        let big = item.map { $0.state ?? $0.label } ?? knob.does.label
        return Button {
            guard let chosen, let item, item.tap != nil else {
                if chosen == nil { choose(.wakes, knob.wakes.first, index: 0) }
                return
            }
            press(chosen.ring, item)
        } label: {
            VStack(spacing: 4 * k) {
                Text(kicker).scaledFont(10, weight: .bold, relativeTo: .caption2).tracking(1.2).foregroundColor(Theme.dim)
                    .lineLimit(1).minimumScaleFactor(0.7)
                Text(big).scaledFont(big.count > 5 ? 24 : 32, weight: .heavy, relativeTo: .title).foregroundColor(tint)
                    .lineLimit(1).minimumScaleFactor(0.6)
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
        chosen.flatMap { c in knob.items(c.ring).first { $0.id == c.id } }
    }

    private var kicker: String {
        guard let chosen, let item = chosenItem else { return Self.does }
        return "\(chosen.ring.title) · \(item.label)".uppercased()
    }

    private var hint: String {
        guard chosen != nil else { return Self.tapARing }
        return chosenItem?.tap ?? ""
    }

    private var note: String { chosenItem?.words ?? knob.does.line }

    private func choose(_ ring: Ring, _ item: TafsCloud.Knob.Item?, index: Int) {
        guard let item else { return }
        let n = knob.items(ring).count
        withMotion(reduceMotion ? nil : .spring(response: 0.5, dampingFraction: 0.8)) {
            turns[ring] = Self.nearestTurn(from: turns[ring] ?? 0, to: index, stops: n)
            chosen = (ring, item.id)
        }
    }

    /// The turn that brings stop `index` to the top by the shorter way round.
    static func nearestTurn(from turn: Int, to index: Int, stops: Int) -> Int {
        guard stops > 0 else { return turn }
        let at = ((turn % stops) + stops) % stops
        var d = index - at
        if Double(d) > Double(stops) / 2 { d -= stops }
        if Double(d) < -Double(stops) / 2 { d += stops }
        return turn + d
    }

    /// The dial's own words, the same on iPhone, iPad and Android.
    static let does = "DOES"
    static let tapARing = "Tap a ring"
}
