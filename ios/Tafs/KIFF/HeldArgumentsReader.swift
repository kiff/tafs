import Foundation

/// A held call's arguments read from KIFF's own bytes, never through `Double` (contracts/held-arguments.json, cases
/// 6 and 7): what the owner approves shows the digits the taf sent, however long, and a number keeps the text it was
/// sent with. The same reader as the Mac's (`HeldArguments.parse`, kiff/tafs-macos), so the two Apple apps read a held
/// list alike. `JSONDecoder` reads numbers through `Double` on iOS 16, which rounds past 17 digits.
enum HeldArgumentsReader {
    typealias Argument = KIFFClient.CardException.Argument

    /// The deepest a held call's arguments are read, past what KIFF holds (it refuses a call nested deeper than 32):
    /// deeper, the document doesn't read, and the decoded arguments stay as they are.
    static let depthMax = 64

    /// One JSON document read as sent, its numbers kept as their text. Nil when it isn't JSON, or is nested past
    /// `depthMax`. A key sent twice keeps the last, which is what runs.
    static func parse(_ data: Data) -> Argument? {
        var reader = Reader(bytes: Array(data))
        guard let value = reader.value() else { return nil }
        reader.space()
        return reader.at == reader.bytes.count ? value : nil
    }

    /// A `card-exceptions` answer (`{"exceptions": [...]}`), or one held call (`{"id": ..., "arguments": {...}}`), taken
    /// apart: the answer without any call's arguments, for `JSONDecoder`, and each call's arguments by its id. On iOS 16
    /// `JSONDecoder` reads every number through `JSONSerialization`, which refuses a whole document for one number out
    /// of `Double`'s range (a taf's `1e400`), so the arguments never reach it. Bytes that don't read come back whole.
    static func apart(_ data: Data) -> (rest: Data, byID: [String: [String: Argument]]) {
        guard case .object(var top)? = parse(data) else { return (data, [:]) }
        var byID: [String: [String: Argument]] = [:]
        func without(_ call: Argument) -> Argument {
            guard case .object(var fields) = call else { return call }
            if case .text(let id)? = fields["id"], case .object(let arguments)? = fields["arguments"] { byID[id] = arguments }
            fields["arguments"] = nil
            return .object(fields)
        }
        if case .list(let calls)? = top["exceptions"] { top["exceptions"] = .list(calls.map(without)) }
        else if case .object(let fields) = without(.object(top)) { top = fields }
        return (Data(json(.object(top)).utf8), byID)
    }

    /// A value written back as JSON, each number as it was sent.
    static func json(_ value: Argument) -> String {
        switch value {
        case .text(let text): return quoted(text)
        case .number(let number): return number
        case .flag(let flag): return flag ? "true" : "false"
        case .null: return "null"
        case .list(let items): return "[" + items.map(json).joined(separator: ",") + "]"
        case .object(let fields): return "{" + fields.map { quoted($0.key) + ":" + json($0.value) }.joined(separator: ",") + "}"
        }
    }

    /// A string as a JSON string: quotes, backslashes and every control character escaped.
    private static func quoted(_ text: String) -> String {
        var out = [UInt8(ascii: "\"")]
        for byte in text.utf8 {
            switch byte {
            case 0x22, 0x5C: out += [0x5C, byte]
            case 0x00..<0x20: out += Array(String(format: "\\u%04x", Int(byte)).utf8)
            default: out.append(byte)
            }
        }
        out.append(UInt8(ascii: "\""))
        return String(decoding: out, as: UTF8.self)
    }

    private struct Reader {
        let bytes: [UInt8]
        var at = 0
        var depth = 0

        mutating func space() {
            while at < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[at]) { at += 1 }
        }

        mutating func take(_ word: [UInt8]) -> Bool {
            guard at + word.count <= bytes.count, Array(bytes[at..<at + word.count]) == word else { return false }
            at += word.count
            return true
        }

        mutating func value() -> Argument? {
            space()
            guard at < bytes.count, depth < HeldArgumentsReader.depthMax else { return nil }
            switch bytes[at] {
            case 0x7B: // {
                at += 1; depth += 1; defer { depth -= 1 }
                var fields: [String: Argument] = [:]
                space()
                if at < bytes.count, bytes[at] == 0x7D { at += 1; return .object(fields) }
                while true {
                    space()
                    guard let key = text() else { return nil }
                    space()
                    guard take([0x3A]), let field = value() else { return nil }
                    fields[key] = field
                    space()
                    if take([0x2C]) { continue }
                    return take([0x7D]) ? .object(fields) : nil
                }
            case 0x5B: // [
                at += 1; depth += 1; defer { depth -= 1 }
                var items: [Argument] = []
                space()
                if at < bytes.count, bytes[at] == 0x5D { at += 1; return .list(items) }
                while true {
                    guard let item = value() else { return nil }
                    items.append(item)
                    space()
                    if take([0x2C]) { continue }
                    return take([0x5D]) ? .list(items) : nil
                }
            case 0x22: return text().map(Argument.text)
            case 0x74: return take(Array("true".utf8)) ? .flag(true) : nil
            case 0x66: return take(Array("false".utf8)) ? .flag(false) : nil
            case 0x6E: return take(Array("null".utf8)) ? .null : nil
            default: return number()
            }
        }

        /// A number's own text, checked against JSON's number grammar.
        mutating func number() -> Argument? {
            let start = at
            while at < bytes.count, Array("+-0123456789.eE".utf8).contains(bytes[at]) { at += 1 }
            guard at > start, let written = String(bytes: bytes[start..<at], encoding: .utf8),
                  written.range(of: #"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$"#, options: .regularExpression) != nil
            else { return nil }
            return .number(written)
        }

        mutating func text() -> String? {
            guard at < bytes.count, bytes[at] == 0x22 else { return nil }
            at += 1
            var out: [UInt8] = []
            while at < bytes.count {
                let b = bytes[at]
                at += 1
                if b == 0x22 { return String(bytes: out, encoding: .utf8) }
                guard b == 0x5C else { out.append(b); continue }
                guard at < bytes.count else { return nil }
                let e = bytes[at]
                at += 1
                switch e {
                case 0x22, 0x5C, 0x2F: out.append(e)
                case 0x62: out.append(0x08)
                case 0x66: out.append(0x0C)
                case 0x6E: out.append(0x0A)
                case 0x72: out.append(0x0D)
                case 0x74: out.append(0x09)
                case 0x75:
                    guard var code = hex4() else { return nil }
                    if (0xD800...0xDBFF).contains(code), take([0x5C, 0x75]), let low = hex4(), (0xDC00...0xDFFF).contains(low) {
                        code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                    }
                    // A lone surrogate is U+FFFD, the replacement character (its UTF-8 bytes).
                    if let scalar = Unicode.Scalar(code) { out.append(contentsOf: Array(String(Character(scalar)).utf8)) }
                    else { out.append(contentsOf: [0xEF, 0xBF, 0xBD]) }
                default: return nil
                }
            }
            return nil
        }

        mutating func hex4() -> UInt32? {
            guard at + 4 <= bytes.count, let s = String(bytes: bytes[at..<at + 4], encoding: .utf8),
                  let v = UInt32(s, radix: 16) else { return nil }
            at += 4
            return v
        }
    }

    /// The most digits a number is written out in; past it, the number is written as sent (case 7).
    static let plainDigitsMax = 100

    /// A JSON number written from its own text, every digit kept: no exponent ("1.5e-05" is "0.000015") and no
    /// trailing zero after the point ("1.50" is "1.5", "2.0" is "2"). One whose plain form would take more than
    /// `plainDigitsMax` digits (the 0 before a point and the zeros after it counted) is written as sent: "1e99" is
    /// written out, "1e100" and "1e-400" stay, and a taf's 1e999999999 is never a billion zeros. A zero is "0".
    static func plainLiteral(_ sent: String) -> String {
        var n = Substring(sent)
        var sign = ""
        if n.first == "-" { sign = "-"; n = n.dropFirst() }
        var mantissa = n
        var exponent = 0
        if let e = n.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            guard let value = Int(n[n.index(after: e)...].drop { $0 == "+" }) else { return sent }
            exponent = value
            mantissa = n[..<e]
        }
        let parts = mantissa.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...2).contains(parts.count), parts.allSatisfy({ $0.allSatisfy { $0.isASCII && $0.isNumber } }) else { return sent }
        let fraction = parts.count > 1 ? String(parts[1]) : ""
        var digits = Substring(String(parts[0]) + fraction).drop { $0 == "0" }
        if digits.isEmpty { return "0" }
        // Past these, the number can't be written in plainDigitsMax digits: no arithmetic on an exponent that large.
        if exponent > plainDigitsMax + mantissa.count || exponent < -plainDigitsMax - mantissa.count { return sent }
        var scale = fraction.count - exponent
        while digits.last == "0" { digits = digits.dropLast(); scale -= 1 }
        let written = scale <= 0 ? digits.count - scale : max(digits.count, scale + 1)
        if written > plainDigitsMax { return sent }
        if scale <= 0 { return sign + digits + String(repeating: "0", count: -scale) }
        if scale >= digits.count { return sign + "0." + String(repeating: "0", count: scale - digits.count) + digits }
        return sign + digits.dropLast(scale) + "." + digits.suffix(scale)
    }
}
