import Foundation

/// A chat keeps the order it has already drawn, as Android's (tafs#1233): what's on screen never moves while
/// Coach answers. Items it has drawn keep their places, new ones go after them in the thread's order, and
/// an earlier page goes before. The owner's message keeps its place when tafs cloud's copy replaces the one
/// sent from this device (another id and time, the same words). Reopened, the thread is in its own order.
enum SteadyOrder {
    /// What was drawn: each item's id, and an owner's message's words, to find its copy.
    struct Entry: Equatable {
        let id: String
        let ownerText: String?
    }

    static func order<T>(_ items: [T], drawn: [Entry], id: (T) -> String, at: (T) -> Date, ownerText: (T) -> String?) -> [T] {
        guard !drawn.isEmpty else { return items }
        let ids = Set(items.map(id))
        var slot: [String: Int] = [:]
        for (i, entry) in drawn.enumerated() where slot[entry.id] == nil { slot[entry.id] = i }
        // An owner's message drawn from this device and gone now takes tafs cloud's copy in its place.
        var taken = Set<String>()
        for (i, entry) in drawn.enumerated() where !ids.contains(entry.id) {
            guard let text = entry.ownerText,
                  let copy = items.first(where: { slot[id($0)] == nil && !taken.contains(id($0)) && ownerText($0) == text }) else { continue }
            slot[id(copy)] = i
            taken.insert(id(copy))
        }
        let known = items.filter { slot[id($0)] != nil }.sorted { slot[id($0)]! < slot[id($1)]! }
        let fresh = items.filter { slot[id($0)] == nil }
        let start = known.map(at).min()
        let before = fresh.filter { item in start.map { at(item) < $0 } ?? false }
        let after = fresh.filter { item in !(start.map { at(item) < $0 } ?? false) }
        return before + known + after
    }
}
