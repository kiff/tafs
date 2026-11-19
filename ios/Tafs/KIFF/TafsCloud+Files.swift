import Foundation

/// Files (docs/files.md): what the account's tafs delivered in their runs, as tafs cloud lists them (`/v1/files`,
/// `contracts/files-*.json`). Everyone on the account reads them; Delete now is for everyone.
extension TafsCloud {
    /// One delivered file, as tafs cloud lists it, with its taf's name as tafs has it now (none once the taf is gone).
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

    /// One file with its start as plain text (`contracts/files-file.json`): tafs cloud's own cleaning, drawn as it is.
    struct FileRead: Decodable, Equatable {
        var preview: String? = nil
        var previewTruncated: Bool? = nil
        enum CodingKeys: String, CodingKey { case preview, previewTruncated = "preview_truncated" }
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

    /// One file, with its start as plain text.
    func file(_ id: String) async throws -> FileRead {
        try await send("GET", "/v1/files/\(escaped(id))")
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
    /// its run's time is when the file was delivered, it goes when tafs cloud says it expires, and its taf's name is none
    /// once tafs has none for it.
    init(_ read: TafsCloud.DeliveredFileRead) {
        let ext = (read.name as NSString).pathExtension
        let type = ext.isEmpty ? (read.mediaType.split(separator: "/").last.map(String.init) ?? "") : ext
        self.init(id: read.id, name: FileText.name(read.name, fallback: read.id), type: type.uppercased(), bytes: read.size,
                  tafID: read.taf, tafName: read.tafName ?? "", runID: read.run, runAt: read.created, chat: read.chat,
                  goesAt: read.expires)
    }
}

extension URLSession {
    /// For a person's files: nothing of what's read is kept in a disk cache (an ephemeral session's is in memory only and
    /// goes with the session's process), so Delete now's "no copy kept" holds on the phone.
    static let noStore: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()
}

/// The files from tafs cloud: every page of the list, the bytes through a URL that lasts a minute, and Delete now.
struct CloudFilesStore: FilesStore {
    let cloud: TafsCloud
    /// Reads a URL's bytes (the download's): the app's own session in use, a stub in tests.
    var fetch: @Sendable (URL) async throws -> Data = { url in try await URLSession.noStore.data(from: url).0 }

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

    func preview(of id: String) async throws -> FilePreviewText? {
        let read = try await cloud.file(id)
        return read.preview.map { FilePreviewText(text: FileText.preview($0), truncated: read.previewTruncated ?? false) }
    }

    func contents(of id: String) async throws -> Data {
        try await fetch(try await cloud.fileDownload(id))
    }

    func delete(_ id: String) async throws {
        try await cloud.deleteFile(id)
    }
}
