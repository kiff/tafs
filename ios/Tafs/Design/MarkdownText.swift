import SwiftUI

/// A message's Markdown, drawn as text (kiff/tafs#58): paragraphs, line breaks, bullet and
/// numbered lists, headings, code blocks, tables (kiff/tafs#211, 6.5), and bold, italic,
/// links and inline code within them. No Markdown symbol shows as written.
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
                case .table(let rows):
                    MarkdownTable(rows: rows, size: size)
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
            } else if case .table = b.kind {
                // A table shows once all of it has arrived, never cut mid-row.
                left = 0
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
    /// A table's rows, the header first, each cell with its inline Markdown.
    enum Kind: Equatable { case paragraph, heading, bullet, numbered(Int), code, table([[AttributedString]]) }
    let kind: Kind
    let text: AttributedString

    /// Splits Markdown into blocks, line by line. Consecutive plain lines are one
    /// paragraph, with their line breaks kept; a blank line ends it.
    static func parse(_ source: String) -> [MarkdownBlock] {
        var out: [MarkdownBlock] = []
        var paragraph: [String] = []
        var code: [String]?
        var table: [String] = []
        func flushTable() {
            // A header, its |---| line and the rows; anything less is text, as written.
            if table.count >= 2, Self.isTableRule(table[1]) {
                let rows = ([table[0]] + table.dropFirst(2)).map { Self.cells($0).map(inline) }
                let plain = rows.map { $0.map { String($0.characters) }.joined(separator: "\t") }.joined(separator: "\n")
                out.append(MarkdownBlock(kind: .table(rows), text: AttributedString(plain)))
            } else {
                paragraph += table
            }
            table = []
        }
        func flush() {
            flushTable()
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
            // A table's lines go together, and become one block once it ends: a line that
            // starts one (a pipe first, two cells or more) ends the paragraph before it.
            if line.contains("|"), !table.isEmpty || (line.hasPrefix("|") && Self.cells(line).count >= 2) {
                if table.isEmpty, !paragraph.isEmpty {
                    out.append(MarkdownBlock(kind: .paragraph, text: inline(paragraph.joined(separator: "\n"))))
                    paragraph = []
                }
                table.append(line)
                continue
            }
            flushTable()
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

    /// A table row's cells: the text between its pipes, without the outer ones, trimmed.
    /// A pipe written as \\| stays in its cell.
    static func cells(_ line: String) -> [String] {
        var row = line.trimmingCharacters(in: .whitespaces)
        if row.hasPrefix("|") { row.removeFirst() }
        if row.hasSuffix("|"), !row.hasSuffix("\\|") { row.removeLast() }
        let marker = "\u{1F}"
        return row.replacingOccurrences(of: "\\|", with: marker)
            .components(separatedBy: "|")
            .map { $0.replacingOccurrences(of: marker, with: "|").trimmingCharacters(in: .whitespaces) }
    }

    /// The line under a table's header: a run of dashes per column, colons allowed.
    static func isTableRule(_ line: String) -> Bool {
        let cells = cells(line)
        return cells.count >= 2 && cells.allSatisfy { c in
            !c.isEmpty && c.contains("-") && c.allSatisfy { $0 == "-" || $0 == ":" }
        }
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

/// A Markdown table, drawn (kiff/tafs#211, 6.5): the header in bold, a line under it and
/// between rows, a column of numbers aligned on the right. Drawn column by column, each
/// cell on one line, so the rows line up without Grid (the Mac app runs on macOS 12); a
/// table wider than the message scrolls sideways.
struct MarkdownTable: View {
    let rows: [[AttributedString]]
    var size: CGFloat = 15

    var body: some View {
        let columns = rows.map(\.count).max() ?? 0
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 0) {
                ForEach(0..<columns, id: \.self) { c in
                    let trailing = Self.isNumeric(column: c, of: rows)
                    VStack(alignment: trailing ? .trailing : .leading, spacing: 0) {
                        ForEach(Array(rows.enumerated()), id: \.offset) { r, row in
                            Text(c < row.count ? row[c] : AttributedString(""))
                                .scaledFont(size - 1, weight: r == 0 ? .semibold : .regular)
                                .lineLimit(1)
                                .fixedSize()
                                .padding(.vertical, 6)
                                .padding(.horizontal, 10)
                                .frame(maxWidth: .infinity, alignment: trailing ? .trailing : .leading)
                                .overlay(alignment: .bottom) {
                                    if r < rows.count - 1 { Rectangle().fill(Theme.line).frame(height: r == 0 ? 1 : 0.5) }
                                }
                        }
                    }
                    .fixedSize(horizontal: true, vertical: false)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.spoken(rows))
    }

    /// A column whose cells below the header are all amounts or numbers.
    static func isNumeric(column c: Int, of rows: [[AttributedString]]) -> Bool {
        let body = rows.dropFirst().compactMap { c < $0.count ? String($0[c].characters) : nil }.filter { !$0.isEmpty }
        guard !body.isEmpty else { return false }
        let numeric = CharacterSet(charactersIn: "0123456789.,-+%$€£¥ ")
        return body.allSatisfy { cell in
            // An amount may carry a currency code ("20.00 USD"); any other letter makes it text.
            let bare = cell.replacingOccurrences(of: #"\b[A-Z]{3}\b"#, with: "", options: .regularExpression)
            return bare.rangeOfCharacter(from: .decimalDigits) != nil && bare.unicodeScalars.allSatisfy { numeric.contains($0) }
        }
    }

    /// The table read aloud, row by row: each cell after its column's header.
    static func spoken(_ rows: [[AttributedString]]) -> String {
        guard let header = rows.first.map({ $0.map { String($0.characters) } }) else { return "" }
        // A header with no rows yet, as a streamed answer can be for a moment: its columns.
        if rows.count == 1 { return header.filter { !$0.isEmpty }.joined(separator: ", ") }
        return rows.dropFirst().map { row in
            row.enumerated().map { i, cell in
                (i < header.count && !header[i].isEmpty ? header[i] + ": " : "") + String(cell.characters)
            }.joined(separator: ", ")
        }.joined(separator: ". ")
    }
}
