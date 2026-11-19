import Foundation

/// Files (docs/files.md): what the account's tafs delivered in their runs, as tafs cloud lists them (`/v1/files`,
/// `contracts/files-*.json`). Everyone on the account reads them; Delete now is for everyone.
extension TafsCloud {
    /// One delivered file, as tafs cloud lists it: its taf's name is kept as it was when the file was delivered.
    struct DeliveredFileRead: Decodable, Equatable {
        let id: String
        let taf: String
        let run: String
        var chat: String? = nil
        let name: String
        let mediaType: String
        let size: Int
        let created: Date
        let expires: Date
        var tafName: String? = nil

        enum CodingKeys: String, CodingKey {
            case id, taf, run, chat, name, mediaType = "media_type", size, created, expires, tafName = "taf_name"
        }
    }

    /// A page of the account's files, newest first; `next` reads the page after it.
    struct FilesPage: Decodable, Equatable {
        let files: [DeliveredFileRead]
        var next: String? = nil
    }

    /// A URL to a file's bytes for a minute: read at once, for its preview, Download and the share sheet. Never handed out.
    struct FileDownload: Decodable, Equatable {
        let url: URL
    }

    /// The account's files, or one taf's, one run's or one chat's.
    func files(taf: String? = nil, run: String? = nil, chat: String? = nil, after: String? = nil) async throws -> FilesPage {
        let query = [("taf", taf), ("run", run), ("chat", chat), ("after", after)]
            .compactMap { key, value in value.map { "\(key)=\(escaped($0))" } }
        return try await send("GET", "/v1/files" + (query.isEmpty ? "" : "?" + query.joined(separator: "&")))
    }

    /// Where a file's bytes can be read, for a minute.
    func fileDownload(_ id: String) async throws -> URL {
        let read: FileDownload = try await send("GET", "/v1/files/\(escaped(id))/download")
        return read.url
    }

    /// Delete now, for everyone on the account.
    func deleteFile(_ id: String) async throws {
        struct Response: Decodable { let deleted: String }
        let _: Response = try await send("DELETE", "/v1/files/\(escaped(id))")
    }
}

extension DeliveredFile {
    /// A file as the screens draw it, from tafs cloud's facts: its type is its name's extension (else its media type's),
    /// its run's time is when the file was delivered, and it goes when tafs cloud says it expires.
    init(_ read: TafsCloud.DeliveredFileRead) {
        let ext = (read.name as NSString).pathExtension
        let type = ext.isEmpty ? (read.mediaType.split(separator: "/").last.map(String.init) ?? "") : ext
        self.init(id: read.id, name: read.name, type: type.uppercased(), bytes: read.size, tafID: read.taf,
                  tafName: read.tafName ?? "", runID: read.run, runAt: read.created, chat: read.chat, goesAt: read.expires)
    }
}

/// The files from tafs cloud: every page of the list, the bytes through a URL that lasts a minute, and Delete now.
struct CloudFilesStore: FilesStore {
    let cloud: TafsCloud
    /// Reads a URL's bytes (the download's): the app's own session in use, a stub in tests.
    var fetch: @Sendable (URL) async throws -> Data = { url in try await URLSession.shared.data(from: url).0 }

    /// At most this many pages are read at once: a month of an account's files, well past what a screen shows.
    static let pages = 10

    func files() async throws -> [DeliveredFile] {
        var all: [DeliveredFile] = []
        var after: String?
        for _ in 0..<Self.pages {
            let page = try await cloud.files(after: after)
            all += page.files.map(DeliveredFile.init)
            guard let next = page.next else { break }
            after = next
        }
        return all
    }

    func contents(of id: String) async throws -> Data {
        try await fetch(try await cloud.fileDownload(id))
    }

    func delete(_ id: String) async throws {
        try await cloud.deleteFile(id)
    }
}
