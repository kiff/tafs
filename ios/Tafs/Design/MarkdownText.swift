import SwiftUI

/// A message's Markdown, drawn as text (kiff/tafs#58): paragraphs, line breaks, bullet and
/// numbered lists, headings, code blocks, and bold, italic, links and inline code within
/// them. No Markdown symbol shows as written.
struct MarkdownText: View {
    let blocks: [MarkdownBlock]
    /// How many characters show, for an answer revealed as it arrives; nil shows all.
    var visible: Int?
    var size: CGFloat = 15

    init(_ text: String, visible: Int? = nil, size: CGFloat = 15) {
        blocks = MarkdownBlock.parse(text)
        self.visible = visible
        self.size = size
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, block in
                switch block.kind {
                case .paragraph:
                    Text(block.text).font(.system(size: size))
                case .heading:
                    Text(block.text).font(.system(size: size + 1, weight: .semibold))
                case .bullet:
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("•").font(.system(size: size))
                        Text(block.text).font(.system(size: size))
                    }
                case .numbered(let n):
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text("\(n).").font(.system(size: size).monospacedDigit())
                        Text(block.text).font(.system(size: size))
                    }
                case .code:
                    Text(block.text).font(Theme.mono(size - 2))
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.raised)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
            }
        }
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
            if let heading = line.firstMatch(of: /^#{1,6}\s+(.+)$/) {
                flush()
                out.append(MarkdownBlock(kind: .heading, text: inline(String(heading.1))))
            } else if let item = line.firstMatch(of: /^[-*•+]\s+(.+)$/) {
                flush()
                out.append(MarkdownBlock(kind: .bullet, text: inline(String(item.1))))
            } else if let item = line.firstMatch(of: /^(\d{1,3})[.)]\s+(.+)$/) {
                flush()
                out.append(MarkdownBlock(kind: .numbered(Int(item.1) ?? 1), text: inline(String(item.2))))
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
    static func inline(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    }
}
