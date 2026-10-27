import CryptoKit
import Foundation
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#endif

/// A file the person picked for their next message (kiff/tafs#209), while it goes up. It is
/// sent with the message once it's `ready`; a file that failed says why.
struct PendingFile: Identifiable, Equatable {
    enum State: Equatable {
        case uploading
        case ready(TafsCloud.AttachmentRef)
        case failed(String)
    }
    let id: UUID
    let name: String
    let mediaType: String
    /// A small JPEG for the tray: a photo's, else nil.
    let thumbnail: Data?
    var state: State

    var ref: TafsCloud.AttachmentRef? {
        if case .ready(let ref) = state { return ref }
        return nil
    }
}

/// A file made ready to attach: its bytes as tafs sends them, its media type and its name.
struct PreparedFile: Equatable {
    let name: String
    let mediaType: String
    let data: Data
    let thumbnail: Data?
}

/// A file that can't be attached, in words the person reads.
struct FileProblem: LocalizedError, Equatable {
    let message: String
    var errorDescription: String? { message }
}

/// What a person may attach, and making it ready (kiff/tafs#209): the same types and limits
/// kiff/agents keeps, checked here first so a file over its limit is refused before upload.
enum FilePrep {
    static let perMessage = 5
    /// A photo's long edge, at most: the most the model reads.
    static let longEdge: CGFloat = 2576

    private static let mb = 1 << 20
    /// The media types tafs takes, with the most each may weigh.
    static let limits: [String: Int] = [
        "image/jpeg": 10 * mb, "image/png": 10 * mb, "image/gif": 10 * mb, "image/webp": 10 * mb,
        "application/pdf": 25 * mb,
        "text/plain": 2 * mb, "text/csv": 2 * mb, "text/markdown": 2 * mb,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": 2 * mb,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": 2 * mb,
    ]

    /// The kinds of file the Files picker offers.
    static var pickable: [UTType] {
        var types: [UTType] = [.pdf, .plainText, .commaSeparatedText, .image]
        for id in ["net.daringfireball.markdown", "org.openxmlformats.wordprocessingml.document", "org.openxmlformats.spreadsheetml.sheet"] {
            if let t = UTType(id) { types.append(t) }
        }
        return types
    }

    /// The media type tafs sends a file of `type` as; nil when it can't be attached.
    static func mediaType(_ type: UTType) -> String? {
        if type.conforms(to: .pdf) { return "application/pdf" }
        if type.conforms(to: .commaSeparatedText) { return "text/csv" }
        if let md = UTType("net.daringfireball.markdown"), type.conforms(to: md) { return "text/markdown" }
        if type.conforms(to: .plainText) { return "text/plain" }
        if let word = UTType("org.openxmlformats.wordprocessingml.document"), type.conforms(to: word) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        }
        if let excel = UTType("org.openxmlformats.spreadsheetml.sheet"), type.conforms(to: excel) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        if type.conforms(to: .jpeg) { return "image/jpeg" }
        if type.conforms(to: .png) { return "image/png" }
        if type.conforms(to: .gif) { return "image/gif" }
        if type.conforms(to: .webP) { return "image/webp" }
        return nil
    }

    /// A file from Files, as it is: refused when it can't be attached or is over its limit.
    /// A photo in a format the model can't read (HEIC) is made a JPEG.
    static func file(name: String, data: Data, type: UTType) throws -> PreparedFile {
        if type.conforms(to: .image), mediaType(type) == nil || data.count > (limits[mediaType(type) ?? ""] ?? 0) {
            return try photo(data, name: name)
        }
        guard let mt = mediaType(type) else {
            throw FileProblem(message: "\(name) can't be attached. Photos, PDFs, text, CSV, Markdown, Word and Excel files can.")
        }
        try check(name: name, size: data.count, mediaType: mt)
        return PreparedFile(name: name, mediaType: mt, data: data, thumbnail: nil)
    }

    /// Refuses a file over its type's limit, saying which.
    static func check(name: String, size: Int, mediaType: String) throws {
        guard let limit = limits[mediaType] else {
            throw FileProblem(message: "\(name) can't be attached.")
        }
        if size > limit {
            throw FileProblem(message: "\(name) is \(megabytes(size)); the most is \(megabytes(limit)).")
        }
    }

    static func megabytes(_ n: Int) -> String {
        String(format: "%.1f MB", Double(n) / Double(1 << 20))
    }

    /// A file's SHA-256, in lowercase hex, as its upload is signed for.
    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// A photo, any format the phone reads (HEIC included): as a JPEG, its long edge at most
    /// 2576 px, with a small thumbnail for the tray.
    static func photo(_ data: Data, name: String) throws -> PreparedFile {
        #if canImport(UIKit)
        guard let image = UIImage(data: data) else {
            throw FileProblem(message: "\(name) couldn't be read as a photo.")
        }
        return try photo(image, name: name)
        #else
        throw FileProblem(message: "Photos are attached on iPhone and iPad.")
        #endif
    }

    #if canImport(UIKit)
    static func photo(_ image: UIImage, name: String) throws -> PreparedFile {
        let jpeg = scaled(image, to: longEdge).jpegData(compressionQuality: 0.85)
        guard let jpeg else { throw FileProblem(message: "\(name) couldn't be read as a photo.") }
        let base = (name as NSString).deletingPathExtension
        let named = (base.isEmpty ? "Photo" : base) + ".jpg"
        try check(name: named, size: jpeg.count, mediaType: "image/jpeg")
        return PreparedFile(name: named, mediaType: "image/jpeg", data: jpeg,
                            thumbnail: scaled(image, to: 160).jpegData(compressionQuality: 0.7))
    }

    /// The image with its long edge at most `edge` points' worth of pixels.
    static func scaled(_ image: UIImage, to edge: CGFloat) -> UIImage {
        let size = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        let long = max(size.width, size.height)
        guard long > edge, long > 0 else { return image }
        let ratio = edge / long
        let target = CGSize(width: (size.width * ratio).rounded(), height: (size.height * ratio).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
    #endif
}
