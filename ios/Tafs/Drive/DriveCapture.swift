import UIKit
import Vision

/// The drive's screenshot (docs/drive.md): the app's own windows only, composited in their order,
/// drawn with `drawHierarchy`, never the drive's overlay, the keyboard or the system's prompts.
/// Secret fields and an open approval's terms are blanked. Sent as a JPEG about 1,100 pixels on its
/// long side, under 200,000 bytes.
@MainActor
enum DriveCapture {
    nonisolated static let longSide: CGFloat = 1100
    nonisolated static let maxBytes = 200_000

    /// The screen in points, and how its points become the screenshot's pixels.
    struct Geometry: Equatable {
        let screen: CGSize
        let scale: CGFloat

        var pixels: CGSize { CGSize(width: (screen.width * scale).rounded(), height: (screen.height * scale).rounded()) }

        /// A point on the screenshot, on the screen: the screenshot's pixels times screen points per pixel.
        func toScreen(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x / scale, y: p.y / scale) }

        func toPixels(_ r: CGRect) -> CGRect {
            CGRect(x: r.minX * scale, y: r.minY * scale, width: r.width * scale, height: r.height * scale)
        }
    }

    nonisolated static func geometry(screen: CGSize) -> Geometry {
        let long = max(screen.width, screen.height)
        return Geometry(screen: screen, scale: long > 0 ? longSide / long : 1)
    }

    /// The app's windows together: what the screenshot covers.
    static func geometry() -> Geometry {
        let frame = DriveTargets.appWindows().reduce(CGRect.null) { $0.union($1.frame) }
        return geometry(screen: frame.isNull ? UIScreen.main.bounds.size : CGSize(width: frame.maxX, height: frame.maxY))
    }

    /// The app's windows drawn as the person sees them, blanked where `blank` says (screen points).
    static func image(_ geo: Geometry, blank: [CGRect]) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: geo.pixels, format: format).image { ctx in
            UIColor.systemBackground.setFill()
            ctx.fill(CGRect(origin: .zero, size: geo.pixels))
            for w in DriveTargets.appWindows() {
                _ = w.drawHierarchy(in: geo.toPixels(w.frame), afterScreenUpdates: true)
            }
            UIColor.systemGray3.setFill()
            for r in blank { ctx.fill(geo.toPixels(r)) }
        }
    }

    /// What's blanked now: every secret field and open approval on screen.
    static func blanked(_ shown: [DriveTargets.Shown]) -> [CGRect] {
        shown.filter { $0.entry.secret || $0.entry.terms }.map(\.frame)
    }

    /// The screenshot as sent: a JPEG under the limit, smaller in quality first, then in size.
    static func jpeg(_ image: UIImage) -> Data? {
        var img = image
        for _ in 0..<4 {
            for q in [0.7, 0.55, 0.4, 0.3, 0.2] as [CGFloat] {
                if let d = img.jpegData(compressionQuality: q), d.count <= maxBytes { return d }
            }
            let size = CGSize(width: (img.size.width * 0.8).rounded(), height: (img.size.height * 0.8).rounded())
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = true
            let source = img
            img = UIGraphicsImageRenderer(size: size, format: format).image { _ in source.draw(in: CGRect(origin: .zero, size: size)) }
        }
        return nil
    }

    /// The screen now, for an answer: the image, and the JPEG's bytes.
    static func capture() -> (image: UIImage, jpeg: Data?) {
        let geo = geometry()
        let image = image(geo, blank: blanked(DriveTargets.shared.shown()))
        return (image, jpeg(image))
    }
}

/// The words drawn on screen, read from the screenshot on the phone with Vision's text recognition:
/// the words of a control that registered none (a button drawn by the app's styles).
@MainActor
enum DriveWords {
    /// Each line of text in an image, with where it is in the image's pixels.
    static func lines(in image: UIImage) -> [(String, CGRect)] {
        guard let cg = image.cgImage else { return [] }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast
        request.usesLanguageCorrection = false
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])
        guard (try? handler.perform([request])) != nil else { return [] }
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        return (request.results ?? []).compactMap { o in
            guard let text = o.topCandidates(1).first?.string, !text.isEmpty else { return nil }
            let b = o.boundingBox
            return (text, CGRect(x: b.minX * w, y: (1 - b.maxY) * h, width: b.width * w, height: b.height * h))
        }
    }

    /// The words whose middle is inside a frame (in the same pixels), top to bottom.
    nonisolated static func words(in frame: CGRect, from lines: [(String, CGRect)]) -> String {
        lines.filter { frame.contains(CGPoint(x: $0.1.midX, y: $0.1.midY)) }
            .sorted { ($0.1.minY, $0.1.minX) < ($1.1.minY, $1.1.minX) }
            .map(\.0).joined(separator: " ")
    }
}
