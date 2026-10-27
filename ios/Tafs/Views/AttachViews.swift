import QuickLook
import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import PhotosUI
import UIKit
#endif

// Files a person attaches to a message (kiff/tafs#209): the paperclip in both message bars,
// the tray above the box, and the files in the thread, opened full size.

#if os(iOS)
/// The paperclip: take a photo, choose photos, or choose a file. Each file goes up as soon
/// as it's picked, and waits in the tray for the message.
struct AttachButton: View {
    @EnvironmentObject private var model: AppModel
    /// The chat the files are for: a taf's id, or `AppModel.setupFiles`.
    let key: String
    @State private var choosingPhotos = false
    @State private var photos: [PhotosPickerItem] = []
    @State private var choosingFile = false
    @State private var takingPhoto = false

    var body: some View {
        let room = max(0, FilePrep.perMessage - model.files(key).count)
        Menu {
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button { takingPhoto = true } label: { Label("Take photo", systemImage: "camera") }
            }
            Button { choosingPhotos = true } label: { Label("Choose photos", systemImage: "photo.on.rectangle") }
            Button { choosingFile = true } label: { Label("Choose file", systemImage: "doc") }
        } label: {
            Image(systemName: "paperclip")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(Theme.ink)
                .frame(width: 40, height: 40)
                .background(Theme.panel)
                .overlay(Circle().stroke(Theme.line))
                .clipShape(Circle())
        }
        .disabled(room == 0)
        .accessibilityLabel(room == 0 ? "Attach: \(FilePrep.perMessage) files is the most a message carries" : "Attach a photo or file")
        .photosPicker(isPresented: $choosingPhotos, selection: $photos, maxSelectionCount: max(room, 1), matching: .images)
        .onChange(of: photos) { picked in
            guard !picked.isEmpty else { return }
            photos = []
            for (n, item) in picked.enumerated() {
                Task {
                    guard let data = try? await item.loadTransferable(type: Data.self) else {
                        model.refuseFile(FileProblem(message: "That photo couldn't be read."), in: key)
                        return
                    }
                    await add { try FilePrep.photo(data, name: "Photo \(n + 1)") }
                }
            }
        }
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: FilePrep.pickable, allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            for url in urls.prefix(room) {
                Task { await add { try Self.read(url) } }
            }
        }
        .sheet(isPresented: $takingPhoto) {
            CameraPicker { image in
                Task { await add { try FilePrep.photo(image, name: "Photo") } }
            }
            .ignoresSafeArea()
        }
    }

    private func add(_ prepare: () throws -> PreparedFile) async {
        do {
            await model.attach(try prepare(), to: key)
        } catch {
            model.refuseFile(error, in: key)
        }
    }

    /// A file from Files: read while its security scope is open.
    static func read(_ url: URL) throws -> PreparedFile {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let type = (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType) ?? UTType(filenameExtension: url.pathExtension) ?? .data
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let name = url.lastPathComponent
        // Over every limit: refused before it's read into memory.
        if size > 25 << 20, !type.conforms(to: .image) {
            throw FileProblem(message: "\(name) is \(FilePrep.megabytes(size)); the most is \(FilePrep.megabytes(25 << 20)).")
        }
        return try FilePrep.file(name: name, data: try Data(contentsOf: url), type: type)
    }
}

/// The camera, for one photo.
struct CameraPicker: UIViewControllerRepresentable {
    let took: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    @MainActor
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.took(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
#endif

/// The files waiting for the next message, above the box: each with a remove control, going
/// up, ready, or saying why it can't go.
struct FileTray: View {
    @EnvironmentObject private var model: AppModel
    let key: String

    var body: some View {
        let files = model.files(key)
        let notice = model.fileNotices[key]
        if !files.isEmpty || notice != nil {
            VStack(alignment: .leading, spacing: 6) {
                if !files.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(files) { file in
                                FileChip(name: file.name, mediaType: file.mediaType, thumbnail: file.thumbnail, state: file.state) {
                                    model.removeFile(file.id, from: key)
                                }
                            }
                        }
                        .padding(.horizontal, 2).padding(.vertical, 2)
                    }
                }
                if let notice {
                    Text(notice).scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.no)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 12).padding(.top, 8)
        }
    }
}

/// One file in the tray.
struct FileChip: View {
    let name: String
    let mediaType: String
    let thumbnail: Data?
    let state: PendingFile.State
    let remove: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            FileIcon(mediaType: mediaType, thumbnail: thumbnail)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).scaledFont(13, weight: .semibold).foregroundColor(Theme.ink).lineLimit(1)
                switch state {
                case .uploading:
                    HStack(spacing: 4) {
                        ProgressView().controlSize(.mini)
                        Text("Uploading").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    }
                case .ready:
                    Text("Ready").scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                case .failed(let why):
                    Text(why).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.no).lineLimit(2)
                }
            }
            .frame(maxWidth: 180, alignment: .leading)
            Button(action: remove) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(Theme.dim)
                    .frame(width: 28, height: 28)
            }
            .accessibilityLabel("Remove \(name)")
        }
        .padding(.leading, 6).padding(.trailing, 2).padding(.vertical, 6)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// A file's picture: a photo's thumbnail, else an icon for its kind.
struct FileIcon: View {
    let mediaType: String
    var thumbnail: Data? = nil

    var body: some View {
        Group {
            #if os(iOS)
            if let thumbnail, let image = UIImage(data: thumbnail) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                symbol
            }
            #else
            symbol
            #endif
        }
        .frame(width: 36, height: 36)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private var symbol: some View {
        Image(systemName: Self.symbol(mediaType))
            .font(.system(size: 17, weight: .medium))
            .foregroundColor(Theme.ink)
            .frame(width: 36, height: 36)
            .background(Theme.line.opacity(0.4))
    }

    static func symbol(_ mediaType: String) -> String {
        switch mediaType {
        case _ where mediaType.hasPrefix("image/"): return "photo"
        case "application/pdf": return "doc.richtext"
        case "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "tablecells"
        default: return "doc.text"
        }
    }
}

/// The files sent with a message, in the thread: each opens full size.
struct MessageFiles: View {
    @EnvironmentObject private var model: AppModel
    let files: [TafsCloud.AttachmentRef]
    /// The chat they were sent in: a taf's id, or `AppModel.setupFiles`.
    let key: String
    @State private var opening: String?
    @State private var shown: URL?

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            ForEach(files) { file in
                Button {
                    guard opening == nil else { return }
                    opening = file.id
                    Task {
                        shown = await model.openFile(file, in: key)
                        opening = nil
                    }
                } label: {
                    HStack(spacing: 8) {
                        FileIcon(mediaType: file.mediaType)
                        Text(file.name).scaledFont(14, weight: .medium).foregroundColor(Theme.ink).lineLimit(1)
                        if opening == file.id { ProgressView().controlSize(.small) }
                    }
                    .padding(6).padding(.trailing, 6)
                    .background(Theme.panel)
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.line))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open \(file.name)")
            }
        }
        .quickLookPreview($shown)
    }
}
