import SwiftUI

/// A taf's Doors tab (docs/doors.md): a dial around the taf, one stop per door and a
/// last stop to add one. Turn it to pick a door, press the taf to open or close it.
/// Below it, the door at the top: its address, who may give it work, and its day.
struct DoorsView: View {
    let taf: Taf
    @StateObject private var doors: DoorsModel
    @State private var selected = 0
    @State private var adding = false
    @State private var pairing: TafsCloud.Door?
    @State private var confirmRemove: TafsCloud.Door?
    @State private var copied = false

    init(taf: Taf, cloud: TafsCloud, cloudID: String) {
        self.taf = taf
        _doors = StateObject(wrappedValue: DoorsModel(cloud: cloud, tafID: cloudID))
    }

    private var door: TafsCloud.Door? { selected < doors.doors.count ? doors.doors[selected] : nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Panel {
                Eyebrow(text: "Doors · turn to pick, press to open")
                DoorDial(taf: taf, doors: doors.doors, knocks: doors.openKnocks, selected: $selected,
                         press: { press() })
                    .frame(height: 300)
                Text(hint).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    .frame(maxWidth: .infinity)
            }
            if let door { panel(door) } else { addPanel }
            let waiting = doors.openKnocks.filter { !$0.isLine }
            if !waiting.isEmpty {
                Panel(stroke: Theme.amber.opacity(0.45)) {
                    Eyebrow(text: "Knocks · waiting on you", color: Theme.amber)
                    ForEach(waiting) { KnockCard(knock: $0, taf: taf, doors: doors, compact: true) }
                }
            }
            if let error = doors.error {
                Text(error).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
            }
            Text("Anyone can write to a door. Only people you pair can give \(taf.name) work, and its Card decides the rest.")
                .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
        }
        .task { await doors.load() }
        .sheet(isPresented: $adding) { AddDoorSheet(doors: doors) { added in if added { selected = doors.doors.count - 1 } } }
        .sheet(item: $pairing) { PairSheet(door: $0, doors: doors) }
        .confirmationDialog("Remove the \(confirmRemove?.label.lowercased() ?? "") door?", isPresented: Binding(get: { confirmRemove != nil }, set: { if !$0 { confirmRemove = nil } }), titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                if let d = confirmRemove { Task { await doors.remove(d); selected = min(selected, doors.doors.count) } }
            }
        } message: {
            Text("Its address stops being \(taf.name)'s. Messages already in its chat stay.")
        }
    }

    private var hint: String {
        guard let door else { return "Press \(taf.name) to add a door" }
        return "Press \(taf.name) to \(door.open ? "close" : "open") \(door.label)"
    }

    private func press() {
        guard let door else { adding = true; return }
        Haptics.tap()
        Task { await doors.setOpen(door, !door.open) }
    }

    private var addPanel: some View {
        Panel {
            Eyebrow(text: "Add a door")
            Text("Give \(taf.name) an address people can write to. It has none until you turn one on, and its Card still decides what it does.")
                .scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            Button("Add a door") { adding = true }.buttonStyle(PrimaryButtonStyle()).disabled(!doors.loaded || doors.addable.isEmpty)
            if doors.loaded && doors.addable.isEmpty {
                Text("No more doors can be added here.").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
            }
        }
    }

    private func panel(_ door: TafsCloud.Door) -> some View {
        Group {
            Panel {
                HStack(spacing: 12) {
                    DoorBadge(app: door.app, open: door.open, size: 34)
                    VStack(alignment: .leading, spacing: 2) {
                        Eyebrow(text: "\(door.label) door")
                        Text(door.open ? (door.resting ? "Resting until tomorrow" : "Open") : "Closed")
                            .scaledFont(13, weight: .medium).foregroundColor(Theme.soft)
                    }
                    Spacer()
                    Toggle("Open", isOn: Binding(get: { door.open }, set: { on in Task { await doors.setOpen(door, on) } }))
                        .labelsHidden().tint(Theme.ok).disabled(doors.busy)
                }
                Text(door.address).font(Theme.mono(15, weight: .medium)).foregroundColor(Theme.ink).textSelection(.enabled)
                if door.kind == "email" {
                    Button(copied ? "Copied" : "Copy address") {
                        Clipboard.copy(door.address)
                        copied = true
                        Task { try? await Task.sleep(nanoseconds: 1_400_000_000); copied = false }
                    }
                    .buttonStyle(OutlineButtonStyle())
                }
                Meter(fraction: Double(door.today) / Double(max(door.limit, 1)), color: door.resting ? Theme.dim : Theme.tafColor(taf.colorIndex))
                Text(door.resting ? "\(door.limit) of \(door.limit) from you and paired senders today. More are dropped until tomorrow."
                     : "\(door.today) of \(door.limit) from you and paired senders today. Text only: attachments aren't opened, and the reply says so.")
                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                if let others = door.othersToday, let cap = door.othersLimit {
                    Text("\(others) of \(cap) from anyone else today.")
                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                }
            }
            Panel {
                Eyebrow(text: door.holdsKeys ? "Paired · can give work" : "No one is paired here")
                if door.holdsKeys {
                    ForEach(door.keys) { key in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(key.auto ? "You" : key.name ?? key.id).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                                Text(key.id).font(Theme.mono(12)).foregroundColor(Theme.dim)
                            }
                            Spacer()
                            if !key.auto {
                                Button("Unpair") { Task { await doors.unpair(door, key) } }
                                    .scaledFont(13, weight: .medium).foregroundColor(Theme.soft)
                            }
                        }
                    }
                    Text("\(door.how). A message counts as theirs only when that holds; anyone else knocks.")
                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    Button("Pair someone") { pairing = door }.buttonStyle(OutlineButtonStyle())
                } else {
                    Text("\(door.how), so no one here can give \(taf.name) work. Everything that comes through knocks.")
                        .scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
                Button("Remove door", role: .destructive) { confirmRemove = door }
                    .scaledFont(14, weight: .medium)
            }
        }
    }
}

/// The dial: the taf in the middle, its doors on a ring that turns, a pointer at the top.
/// Each stop is a door; the last is always "Add a door".
struct DoorDial: View {
    let taf: Taf
    let doors: [TafsCloud.Door]
    let knocks: [TafsCloud.Knock]
    @Binding var selected: Int
    let press: () -> Void

    /// The dial's turn, in degrees: stop `i` is at the top when it's `i` steps.
    @State private var rotation: Double = 0
    @State private var drag: (angle: Double, rotation: Double, stop: Int)?
    @State private var breathe = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var stops: Int { doors.count + 1 }
    private var step: Double { DialMath.step(stops: stops) }

    var body: some View {
        GeometryReader { g in
            let size = min(g.size.width, g.size.height)
            let r = size * 0.36
            let center = CGPoint(x: g.size.width / 2, y: g.size.height / 2 + 6)
            ZStack {
                // The taf's glow: it breathes while a door is open.
                Circle()
                    .fill(RadialGradient(colors: [Theme.tafColor(taf.colorIndex).opacity(anyOpen ? (breathe ? 0.22 : 0.12) : 0.04), .clear],
                                         center: .center, startRadius: 10, endRadius: r * 1.3))
                    .frame(width: r * 2.6, height: r * 2.6)
                // The knob, with grip ticks that turn with it.
                Circle()
                    .fill(LinearGradient(colors: [Theme.panel, Theme.raised], startPoint: .topLeading, endPoint: .bottomTrailing))
                    .overlay(Circle().stroke(Theme.line))
                    .frame(width: (r - 14) * 2, height: (r - 14) * 2)
                ForEach(0..<60, id: \.self) { i in
                    Capsule().fill(Theme.ink.opacity(i % 5 == 0 ? 0.16 : 0.07))
                        .frame(width: 1, height: i % 5 == 0 ? 8 : 4)
                        .offset(y: -(r - 26))
                        .rotationEffect(.degrees(Double(i) * 6 - rotation))
                }
                Circle().stroke(Theme.line, lineWidth: 1).frame(width: r * 2, height: r * 2)
                ForEach(Array(doors.enumerated()), id: \.element.id) { i, door in
                    arc(door, at: DialMath.angle(index: i, stops: stops, rotation: rotation), radius: r)
                }
                // The pointer.
                Triangle().fill(Theme.ink).frame(width: 12, height: 9).offset(y: -r - 20)
                ForEach(0..<stops, id: \.self) { i in
                    stop(i, radius: r)
                }
                Button(action: press) { TafAvatar(taf: taf, size: 56) }
                    .buttonStyle(.plain)
                    .offset(y: -6)
                    .accessibilityHidden(true)
                Text(centerLine).font(Theme.mono(10, weight: .semibold)).foregroundColor(centerColor)
                    .offset(y: 38)
            }
            .frame(width: g.size.width, height: g.size.height)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 6).onChanged { v in turn(v, center: center) }.onEnded { _ in settle() })
        }
        .onAppear {
            rotation = Double(selected) * step
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true)) { breathe = true }
        }
        .onChange(of: stops) { _ in snap(to: min(selected, stops - 1)) }
        .accessibilityElement()
        .accessibilityLabel("Door dial")
        .accessibilityValue(accessibilityValue)
        .accessibilityHint("Swipe up or down to pick a door. Double-tap to open or close it.")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: snap(to: Int((rotation / step).rounded()) + 1)
            case .decrement: snap(to: Int((rotation / step).rounded()) - 1)
            @unknown default: break
            }
        }
        .accessibilityAction { press() }
    }

    private var anyOpen: Bool { doors.contains { $0.open } }

    private var accessibilityValue: String {
        guard selected < doors.count else { return "Add a door, \(stops) of \(stops)" }
        let d = doors[selected]
        return "\(d.label) door, \(d.open ? "open" : "closed"), \(selected + 1) of \(stops)"
    }

    private var centerLine: String {
        guard selected < doors.count else { return "ADD A DOOR" }
        return "\(doors[selected].label.uppercased()) · \(doors[selected].open ? "OPEN" : "CLOSED")"
    }

    private var centerColor: Color {
        guard selected < doors.count, !doors[selected].open else { return selected < doors.count ? Theme.dim : Theme.tafColor(taf.colorIndex) }
        return Theme.tafColor(taf.colorIndex)
    }

    /// A door's arc on the ring: lit while open, dashed while closed, grey while resting.
    private func arc(_ door: TafsCloud.Door, at angle: Double, radius: CGFloat) -> some View {
        let span = min(step * 0.76, 56)
        let color = door.open ? (door.resting ? Theme.dim : Theme.tafColor(taf.colorIndex)) : Theme.ink.opacity(0.22)
        return Circle()
            .trim(from: 0, to: span / 360)
            .stroke(color, style: StrokeStyle(lineWidth: door.open ? 4 : 2, lineCap: .round, dash: door.open ? [] : [3, 5]))
            .frame(width: radius * 2, height: radius * 2)
            .rotationEffect(.degrees(angle - span / 2))
    }

    /// A stop: the door's badge on the ring and its name outside, or "+" to add one.
    private func stop(_ i: Int, radius: CGFloat) -> some View {
        let a = CGFloat(DialMath.angle(index: i, stops: stops, rotation: rotation) * .pi / 180)
        let isSelected = i == selected
        let door = i < doors.count ? doors[i] : nil
        let knocking = door.map { d in knocks.contains { $0.door == d.id && !$0.isLine } } ?? false
        return ZStack {
            Group {
                if let door {
                    DoorBadge(app: door.app, open: door.open, size: isSelected ? 30 : 24)
                } else {
                    Circle().fill(Theme.panel)
                        .overlay(Circle().stroke(Theme.tafColor(taf.colorIndex), style: StrokeStyle(lineWidth: 1.5, dash: [3, 3])))
                        .overlay(Text("+").font(.system(size: 14, weight: .bold)).foregroundColor(Theme.tafColor(taf.colorIndex)))
                        .frame(width: isSelected ? 30 : 24, height: isSelected ? 30 : 24)
                }
            }
            .overlay(alignment: .topTrailing) {
                if knocking { Circle().fill(Theme.amber).frame(width: 9, height: 9).offset(x: 3, y: -3) }
            }
            .offset(x: cos(a) * radius, y: sin(a) * radius)
            .onTapGesture { turn(to: i) }
            Text((door?.label ?? "Add").uppercased())
                .font(Theme.mono(isSelected ? 11 : 9.5, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? Theme.ink : Theme.dim)
                .fixedSize()
                .offset(x: cos(a) * (radius + 40), y: sin(a) * (radius + 30))
                .opacity(isSelected && abs(sin(a) + 1) < 0.05 ? 0 : 1)
        }
    }

    private func turn(_ v: DragGesture.Value, center: CGPoint) {
        let angle = Double(atan2(v.location.y - center.y, v.location.x - center.x)) * 180 / .pi
        if drag == nil {
            let start = Double(atan2(v.startLocation.y - center.y, v.startLocation.x - center.x)) * 180 / .pi
            drag = (angle: start, rotation: rotation, stop: selected)
        }
        guard let d = drag else { return }
        rotation = d.rotation - DialMath.delta(from: d.angle, to: angle)
        let now = DialMath.stop(rotation: rotation, stops: stops)
        if now != selected {
            selected = now
            Haptics.step()
        }
    }

    private func settle() {
        drag = nil
        snap(to: Int((rotation / step).rounded()))
    }

    /// Turns the shortest way to stop `i`.
    private func turn(to i: Int) {
        let current = Int((rotation / step).rounded())
        snap(to: current + DialMath.shortest(from: current, to: i, stops: stops))
    }

    private func snap(to raw: Int) {
        let target = Double(raw) * step
        if reduceMotion { rotation = target } else {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.78)) { rotation = target }
        }
        let now = DialMath.stop(rotation: target, stops: stops)
        if now != selected { Haptics.step() }
        selected = now
    }
}

/// The dial's geometry, apart from drawing it, so it can be tested.
enum DialMath {
    static func step(stops: Int) -> Double { 360 / Double(max(stops, 1)) }

    /// Stop `index`'s angle on screen, in degrees (0 is 3 o'clock, clockwise): the top when the dial is turned to it.
    static func angle(index: Int, stops: Int, rotation: Double) -> Double {
        -90 + Double(index) * step(stops: stops) - rotation
    }

    /// The stop at the top for a turn.
    static func stop(rotation: Double, stops: Int) -> Int {
        let n = Int((rotation / step(stops: stops)).rounded())
        return ((n % stops) + stops) % stops
    }

    /// The turn between two angles, the short way round, in -180...180.
    static func delta(from a: Double, to b: Double) -> Double {
        var d = (b - a).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d < -180 { d += 360 }
        return d
    }

    /// Steps from stop `from` (any turn) to stop `to`, the short way round.
    static func shortest(from: Int, to: Int, stops: Int) -> Int {
        var d = ((to - from) % stops + stops) % stops
        if Double(d) > Double(stops) / 2 { d -= stops }
        return d
    }
}

private struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.midX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        p.closeSubpath()
        return p
    }
}

/// A door's app, as a small round badge: filled while open.
struct DoorBadge: View {
    let app: String
    let open: Bool
    var size: CGFloat = 24

    var body: some View {
        Circle().fill(open ? DoorNames.color(app) : Theme.panel)
            .overlay(Circle().stroke(open ? Color.clear : Theme.ink.opacity(0.25), lineWidth: 1.5))
            .overlay(Text(DoorNames.mark(app)).font(.system(size: size * 0.46, weight: .heavy, design: .rounded))
                .foregroundColor(open ? .white : Theme.dim))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// How a door's app is named and drawn.
enum DoorNames {
    static func label(_ app: String) -> String {
        switch app {
        case "email": return "email"
        case "whatsapp": return "WhatsApp"
        default: return app.prefix(1).uppercased() + app.dropFirst()
        }
    }

    static func mark(_ app: String) -> String {
        switch app {
        case "email": return "@"
        case "whatsapp": return "W"
        default: return String(app.prefix(1)).uppercased()
        }
    }

    static func color(_ app: String) -> Color {
        switch app {
        case "email": return Theme.sky
        case "whatsapp": return Theme.ok
        // The same colour on every launch: from the name's letters, not its hash.
        default: return Theme.tafColor(app.unicodeScalars.reduce(0) { $0 + Int($1.value) })
        }
    }
}

/// Picks the app a new door opens on: those that can tell who's writing first.
struct AddDoorSheet: View {
    @ObservedObject var doors: DoorsModel
    let done: (Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Add a door").font(Theme.display(24)).foregroundColor(Theme.ink)
                Spacer()
                Button("Done") { dismiss() }
            }
            TextField("Search apps", text: $query)
                .textFieldStyle(.roundedBorder)
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    section("Knows who's writing · can hold keys", doors.addable.filter { $0.proof == "proven" && matches($0) })
                    section("Can't prove who's writing · knocks only", doors.addable.filter { $0.proof != "proven" && matches($0) })
                    if doors.addable.filter(matches).isEmpty {
                        Text(query.isEmpty ? "No more doors can be added here." : "No app called \"\(query)\" here yet.")
                            .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                    }
                    Text("Replies go only to who wrote. Anything else the taf does still goes through KIFF.")
                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim).padding(.top, 6)
                }
            }
        }
        .padding(20)
        .screenBackground()
    }

    private func matches(_ app: TafsCloud.DoorApp) -> Bool {
        query.isEmpty || app.label.localizedCaseInsensitiveContains(query)
    }

    @ViewBuilder
    private func section(_ title: String, _ apps: [TafsCloud.DoorApp]) -> some View {
        if !apps.isEmpty {
            Eyebrow(text: title).padding(.top, 6)
            ForEach(apps) { app in
                Button {
                    Task {
                        let added = await doors.add(app) != nil
                        done(added)
                        if added { dismiss() }
                    }
                } label: {
                    HStack(spacing: 12) {
                        DoorBadge(app: app.id, open: true, size: 32)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(app.label).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Text(app.how).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                        }
                        Spacer()
                    }
                    .padding(12)
                    .background(Theme.panel)
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line))
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(doors.busy)
            }
        }
    }
}

/// Pairs a sender with a door: they can give the taf work from now on.
struct PairSheet: View {
    let door: TafsCloud.Door
    @ObservedObject var doors: DoorsModel
    @Environment(\.dismiss) private var dismiss
    @State private var sender = ""
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Pair someone").font(Theme.display(24)).foregroundColor(Theme.ink)
            Text("Their messages to this \(door.label.lowercased()) door give work, within the Card. Anyone else's only knock.")
                .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            TextField(door.kind == "email" ? "Email address" : door.kind == "whatsapp" ? "Phone number, with its country code" : "Who, as \(door.label) names them", text: $sender)
                .textFieldStyle(.roundedBorder)
                .disableAutocorrection(true)
            TextField("Name (optional)", text: $name).textFieldStyle(.roundedBorder)
            Button("Pair") {
                Task {
                    await doors.pair(door, sender: sender, name: name)
                    if doors.error == nil { dismiss() }
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(sender.trimmingCharacters(in: .whitespaces).isEmpty || doors.busy)
            Button("Cancel") { dismiss() }.frame(maxWidth: .infinity)
            Spacer()
        }
        .padding(20)
        .screenBackground()
    }
}

/// Someone without a key wrote: who, what they want, and the owner's three answers.
struct KnockCard: View {
    let knock: TafsCloud.Knock
    let taf: Taf?
    @ObservedObject var doors: DoorsModel
    var compact = false
    @State private var asking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("● KNOCK · BY \(DoorNames.label(knock.app).uppercased())\(knock.proof == "fail" ? " · DIDN'T CHECK OUT" : "")")
                .font(Theme.mono(10, weight: .semibold)).foregroundColor(Theme.amber)
            if let rest = knock.rest {
                Text(rest == "keys"
                     ? "This door had its messages from you and paired senders for today. \(knock.dropped) more \(knock.dropped == 1 ? "was" : "were") dropped."
                     : "This door had its messages from people who aren't paired for today. \(knock.dropped) more \(knock.dropped == 1 ? "was" : "were") dropped.")
                    .scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Button("OK") { Task { await doors.answer(knock, "ignore") } }.buttonStyle(OutlineButtonStyle())
            } else {
                Text("\(knock.who) wrote\(knock.subject.map { " about \"\($0)\"" } ?? ""). Nothing was done.")
                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                Text(knock.text).scaledFont(14).foregroundColor(Theme.soft).lineLimit(compact ? 3 : 8).textSelection(.enabled)
                if knock.attachments > 0 {
                    Text("It had \(knock.attachments) attachment\(knock.attachments == 1 ? "" : "s"), not opened.")
                        .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                }
                if let note = proofNote {
                    Text(note).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: 8) {
                    if knock.proof != "fail" {
                        Button("Do it") { asking = true }
                            .buttonStyle(.plain)
                            .scaledFont(14, weight: .semibold).foregroundColor(.white)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(Theme.amber).clipShape(Capsule())
                    }
                    if knock.proof == "pass" {
                        Button("Pair \(knock.name?.components(separatedBy: " ").first ?? "them")") { Task { await doors.answer(knock, "pair") } }
                            .buttonStyle(.plain).scaledFont(14, weight: .medium).foregroundColor(Theme.ink)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .overlay(Capsule().stroke(Theme.ink.opacity(0.2)))
                    }
                    Button("Ignore") { Task { await doors.answer(knock, "ignore") } }
                        .buttonStyle(.plain).scaledFont(14, weight: .medium).foregroundColor(Theme.soft)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                }
                .disabled(doors.busy)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.amber.opacity(0.07))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.amber.opacity(0.45)))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .sheet(isPresented: $asking) { DoItSheet(knock: knock, taf: taf, doors: doors) }
    }

    private var proofNote: String? {
        switch knock.proof {
        case "fail": return "It says it's from \(knock.from), but it didn't pass the sender check, so it's treated as a stranger's."
        case "none": return "This door can't prove who wrote it."
        default: return nil
        }
    }
}

/// "Do it": what the owner tells the taf to do about a knock. Their words are the instruction.
struct DoItSheet: View {
    let knock: TafsCloud.Knock
    let taf: Taf?
    @ObservedObject var doors: DoorsModel
    @Environment(\.dismiss) private var dismiss
    @State private var say = "Do what it asks, within your Card."

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Tell \(taf?.name ?? "your taf") what to do").font(Theme.display(22)).foregroundColor(Theme.ink)
            Text("\(knock.who)'s message goes with your words, as information. \(taf?.name ?? "It") acts on what you say, within its Card, and doesn't write back to them unless you ask.")
                .scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            TextEditor(text: $say)
                .scaledFont(15)
                .frame(minHeight: 110)
                .padding(6)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.line))
            Button("Send to \(taf?.name ?? "your taf")") {
                Task {
                    await doors.answer(knock, "do", say: say.trimmingCharacters(in: .whitespacesAndNewlines))
                    if doors.error == nil { dismiss() }
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(say.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || doors.busy)
            Button("Cancel") { dismiss() }.frame(maxWidth: .infinity)
            Spacer()
        }
        .padding(20)
        .screenBackground()
    }
}

/// The knocks waiting in a taf's chat, read with the chat.
struct ChatKnocks: View {
    let taf: Taf?
    /// Read again when the thread changes.
    let refresh: Int
    @StateObject private var doors: DoorsModel

    init(taf: Taf?, cloud: TafsCloud, cloudID: String, refresh: Int) {
        self.taf = taf
        self.refresh = refresh
        _doors = StateObject(wrappedValue: DoorsModel(cloud: cloud, tafID: cloudID))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(doors.openKnocks) { KnockCard(knock: $0, taf: taf, doors: doors) }
            if let error = doors.error {
                Text(error).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.no)
            }
        }
        .task(id: refresh) { await doors.load() }
    }
}

/// The line over a door's message, and under its answer: where it came from, and how it went back.
struct DoorStamp: View {
    let mark: TafsCloud.DoorMark
    let fromOwner: Bool

    var body: some View {
        Text(text).font(Theme.mono(10, weight: .medium)).foregroundColor(color)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: fromOwner ? .trailing : .leading)
            .accessibilityLabel(text.lowercased())
    }

    private var label: String { DoorNames.label(mark.app) }

    private var text: String {
        if fromOwner {
            return "BY \(label.uppercased())\(mark.from.map { " · \($0)" } ?? "")"
        }
        switch mark.reply {
        case "sent": return "↩ SENT BACK BY \(label.uppercased()) · SAME THREAD"
        case "not_sent": return "NOT SENT BACK\(mark.why.map { " · \($0)" } ?? "")"
        default: return "GOES BACK BY \(label.uppercased()) WHEN DONE"
        }
    }

    private var color: Color {
        mark.reply == "not_sent" ? Theme.no : Theme.blue
    }
}

/// The taf's avatar's rim: one lit arc while a door is open, turning slowly; amber while a
/// knock waits; grey when every open door rests for the day.
struct DoorRim: View {
    let lights: [DoorLight]
    let size: CGFloat
    let color: Color
    @State private var spin = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let open = lights.filter { $0.open }
        if !open.isEmpty {
            let tint = lights.contains { $0.knocks > 0 } ? Theme.amber : open.allSatisfy { $0.resting } ? Theme.dim : color
            RoundedRectangle(cornerRadius: size * 0.3 + 4, style: .continuous)
                .trim(from: 0, to: min(0.12 * Double(open.count) + 0.1, 0.6))
                .stroke(tint, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .frame(width: size + 8, height: size + 8)
                .rotationEffect(.degrees(spin ? 360 : 0))
                .onAppear {
                    guard !reduceMotion else { return }
                    withAnimation(.linear(duration: 4.5).repeatForever(autoreverses: false)) { spin = true }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}
