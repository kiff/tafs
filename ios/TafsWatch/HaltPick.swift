import SwiftUI

/// v2's Choose which to halt (docs/watch-app.md, "Step (v)"), from the menu's Halt, as Wear OS's: All your tafs, then each taf
/// not halted, each a switch; Halt now · N, asked once more. Every taf offered picked sends one Halt all; one or a few send
/// one Halt per taf, one after the other, each to the end, so one that fails never keeps the next from halting. Nothing is
/// sent before Halt now, and nothing from a watch with no passcode.
@MainActor
final class HaltPickModel: ObservableObject {
    /// What came of one Halt: the taf by name, and the phone's words for it.
    struct Result: Equatable {
        let name: String
        let answer: WatchAnswer
    }

    enum Outcome: Equatable {
        case choosing
        /// "Halting …" until the last answers.
        case halting(String)
        /// What came of each, by name.
        case done([Result])
        /// Nothing was sent: why.
        case notSent(String)
    }

    /// The tafs offered: those Your tafs listed that aren't halted, in its order.
    let offered: [WatchTaf]
    @Published private(set) var picked: Set<String> = []
    @Published private(set) var outcome: Outcome = .choosing
    private let tafs: TafsModel

    init(tafs: TafsModel) {
        self.tafs = tafs
        if case .loaded(let listed) = tafs.state {
            offered = listed.filter { !$0.halted }
        } else {
            offered = []
        }
    }

    /// Every taf offered is picked: All your tafs is on, and Halt now sends one Halt all.
    var allPicked: Bool { !offered.isEmpty && picked.count == offered.count }

    func toggleAll() {
        picked = allPicked ? [] : Set(offered.map(\.id))
    }

    func toggle(_ id: String) {
        guard offered.contains(where: { $0.id == id }) else { return }
        if picked.contains(id) { picked.remove(id) } else { picked.insert(id) }
    }

    /// The picked tafs, in Your tafs' order.
    private var chosen: [WatchTaf] { offered.filter { picked.contains($0.id) } }
    private var names: String { chosen.map(\.name).joined(separator: ", ") }

    /// The question Halt now asks: all of them, or the tafs by name.
    var question: String { allPicked ? Words.Watch.haltAllQ : names }

    /// The line under it, for all, one or several.
    var explain: String {
        if allPicked { return Words.Halt.Explain.runningAll }
        return chosen.count == 1 ? Words.Halt.Explain.runningOne : Words.Halt.haltingStopsNoRunStarts(who: names)
    }

    /// Halt now, once confirmed.
    func haltNow() async {
        guard !picked.isEmpty else { return }
        if case .halting = outcome { return }
        guard tafs.lock.isSecure else { outcome = .notSent(Words.Watch.needsLock); return }
        if allPicked {
            outcome = .halting(Words.Watch.haltingAll)
            await tafs.halt(nil)
            outcome = .done([Result(name: Words.Watch.allYourTafs, answer: answer(TafsModel.all))])
            return
        }
        let picks = chosen
        outcome = .halting(Words.Watch.halting(taf: names))
        var results: [Result] = []
        for taf in picks {
            await tafs.halt(taf.id)
            results.append(Result(name: taf.name, answer: answer(taf.id)))
        }
        outcome = .done(results)
    }

    private func answer(_ key: String) -> WatchAnswer {
        tafs.halts[key] ?? .failed(Words.Watch.phoneNotReachable)
    }
}

/// Choose which to halt: the switches, Halt now · N (off with none picked), asked once more; then "Halting …" and what came of
/// each, by name, in the phone's words.
struct HaltPickScreen: View {
    @StateObject private var model: HaltPickModel
    @State private var confirming = false

    init(tafs: TafsModel) {
        _model = StateObject(wrappedValue: HaltPickModel(tafs: tafs))
    }

    var body: some View {
        List {
            switch model.outcome {
            case .choosing:
                Toggle(Words.Watch.allYourTafs, isOn: Binding(get: { model.allPicked }, set: { _ in model.toggleAll() }))
                ForEach(model.offered) { taf in
                    Toggle(taf.name, isOn: Binding(get: { model.picked.contains(taf.id) }, set: { _ in model.toggle(taf.id) }))
                }
                Button(Words.Watch.haltNowCount(count: model.picked.count), role: .destructive) { confirming = true }
                    .disabled(model.picked.isEmpty)
            case .halting(let line):
                ProgressView()
                Text(line).font(.footnote)
            case .done(let results):
                ForEach(Array(results.enumerated()), id: \.offset) { _, result in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(result.name).font(.headline)
                        switch result.answer {
                        case .done(let line): Text(line).font(.footnote.weight(.semibold))
                        case .failed(let why): Text(why).font(.footnote)
                        case .sending: ProgressView()
                        }
                    }
                }
            case .notSent(let why):
                Text(why).font(.footnote)
            }
        }
        .navigationTitle(Words.Watch.haltPickTitle)
        .confirmationDialog(model.question, isPresented: $confirming, titleVisibility: .visible) {
            Button(Words.Halt.now, role: .destructive) { Task { await model.haltNow() } }
        } message: {
            Text(model.explain)
        }
    }
}
