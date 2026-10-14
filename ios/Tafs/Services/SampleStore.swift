import Foundation

/// Keeps the sample on the phone between launches, as one JSON file.
@MainActor
final class SampleStore {
    struct Snapshot: Codable, Equatable {
        var tafs: [Taf]
        var held: [HeldCall]
        var record: [RecordEntry]
        var scriptPosition: [UUID: Int]
        var templateOf: [UUID: String]
    }

    private let url: URL

    init(url: URL? = nil) {
        if let url {
            self.url = url
        } else {
            let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            self.url = folder.appendingPathComponent("sample.json")
        }
    }

    func load() -> Snapshot? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: data)
    }

    func save(_ snapshot: Snapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
    }
}
