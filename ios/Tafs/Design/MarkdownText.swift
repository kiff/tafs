import SwiftUI

/// A message's Markdown, drawn as text (kiff/tafs#58): paragraphs, line breaks, bullet and
/// numbered lists, headings, code blocks, and bold, italic, links and inline code within
/// them. No Markdown symbol shows as written.
struct MarkdownText: View {
    let blocks: [MarkdownBlock]
    /// How many characters show, for an answer revealed as it arrives; nil shows all.
    var visible: Int?
    var size: CGFloat = 15
    /// The space added between lines, growing with the text as Dynamic Type does.
    @ScaledMetric private var spacing: CGFloat

    init(_ text: String, visible: Int? = nil, size: CGFloat = 15) {
        blocks = MarkdownBlock.parse(text)
        self.visible = visible
        self.size = size
        _spacing = ScaledMetric(wrappedValue: size * 0.2, relativeTo: .body)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, block in
                switch block.kind {
                case .paragraph:
                    Text(block.text).scaledFont(size)
                case .heading:
                    Text(block.text).scaledFont(size + 1, weight: .semibold)
                case .bullet:
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("•").scaledFont(size)
                        Text(block.text).scaledFont(size)
                    }
                case .numbered(let n):
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text("\(n).").monospacedDigit().scaledFont(size)
                        Text(block.text).scaledFont(size)
                    }
                case .code:
                    Text(block.text).scaledFont(size - 2, design: .monospaced)
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.raised)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
            }
        }
        // Lines about 1.4 times the text's size apart: the font's own 1.2, and a fifth more.
        .lineSpacing(spacing)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// The blocks as far as `visible` characters reach, the last one cut there.
    private var shown: [MarkdownBlock] {
        guard var left = visible else { return blocks }
        var out: [MarkdownBlock] = []
        for b in blocks {
            guard left > 0 else { break }
            let n = b.text.characters.count
            if n <= left {
                out.append(b)
                left -= n
            } else {
                let end = b.text.characters.index(b.text.startIndex, offsetBy: left)
                out.append(MarkdownBlock(kind: b.kind, text: AttributedString(b.text[b.text.startIndex..<end])))
                left = 0
            }
        }
        return out
    }

    /// How many characters the message has, as drawn.
    var length: Int { blocks.reduce(0) { $0 + $1.text.characters.count } }
}

/// One block of a message: its kind and its text, with inline Markdown applied.
struct MarkdownBlock: Equatable {
    enum Kind: Equatable { case paragraph, heading, bullet, numbered(Int), code }
    let kind: Kind
    let text: AttributedString

    /// Splits Markdown into blocks, line by line. Consecutive plain lines are one
    /// paragraph, with their line breaks kept; a blank line ends it.
    static func parse(_ source: String) -> [MarkdownBlock] {
        var out: [MarkdownBlock] = []
        var paragraph: [String] = []
        var code: [String]?
        func flush() {
            if !paragraph.isEmpty { out.append(MarkdownBlock(kind: .paragraph, text: inline(paragraph.joined(separator: "\n")))) }
            paragraph = []
        }
        for raw in source.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("```") {
                if let lines = code {
                    out.append(MarkdownBlock(kind: .code, text: AttributedString(lines.joined(separator: "\n"))))
                    code = nil
                } else {
                    flush()
                    code = []
                }
                continue
            }
            if code != nil { code?.append(raw); continue }
            if line.isEmpty { flush(); continue }
            if let heading = Self.groups(line, #"^#{1,6}\s+(.+)$"#) {
                flush()
                out.append(MarkdownBlock(kind: .heading, text: inline(heading[0])))
            } else if let item = Self.groups(line, #"^[-*•+]\s+(.+)$"#) {
                flush()
                out.append(MarkdownBlock(kind: .bullet, text: inline(item[0])))
            } else if let item = Self.groups(line, #"^(\d{1,3})[.)]\s+(.+)$"#) {
                flush()
                out.append(MarkdownBlock(kind: .numbered(Int(item[0]) ?? 1), text: inline(item[1])))
            } else if line.allSatisfy({ $0 == "-" || $0 == "*" || $0 == "_" }) && line.count >= 3 {
                flush() // a rule: nothing to draw
            } else {
                paragraph.append(line)
            }
        }
        if let lines = code { out.append(MarkdownBlock(kind: .code, text: AttributedString(lines.joined(separator: "\n")))) }
        flush()
        return out
    }

    /// Bold, italic, links and inline code; the text as written if it isn't Markdown.
    /// The capture groups of `pattern` over the whole line, or nil when it doesn't match.
    /// NSRegularExpression, not Swift's regexes: those need macOS 13, and the Mac app runs
    /// on 12 (kiff/tafs#85).
    static func groups(_ line: String, _ pattern: String) -> [String]? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { return nil }
        return (1..<m.numberOfRanges).map { i in
            Range(m.range(at: i), in: line).map { String(line[$0]) } ?? ""
        }
    }

    static func inline(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    }
}
