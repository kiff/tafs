import Foundation

/// RFC 8785 (JSON Canonicalization Scheme), as planque computes a grant's digest: the
/// phone recomputes it from the request it shows, so the Face ID approves exactly that
/// request. Keys sort by UTF-16 code units, numbers print as ECMAScript does, and an
/// integer beyond 2^53 - 1 is refused, as planque and KIFF refuse it.
///
/// JSON is read by `JCS.parse`, not `JSONSerialization`: a number keeps its literal,
/// which Swift's `Double(String)` rounds correctly and which tells an integer from a
/// fraction, as planque's Go does.
enum JCS {
    enum Failure: Error, Equatable {
        case notJSON
        case unsafeInteger
        case notFinite
    }

    /// A JSON value as read, numbers kept as written.
    indirect enum Value: Equatable {
        case null
        case bool(Bool)
        case number(String)
        case string(String)
        case array([Value])
        case object([String: Value])

        subscript(key: String) -> Value? {
            if case .object(let o) = self { return o[key] }
            return nil
        }

        var string: String? {
            if case .string(let s) = self { return s }
            return nil
        }

        var array: [Value]? {
            if case .array(let a) = self { return a }
            return nil
        }
    }

    /// The canonical form of the JSON in `data`.
    static func canonical(_ data: Data) throws -> Data {
        try canonical(parse(data))
    }

    /// The canonical form of a parsed value.
    static func canonical(_ value: Value) throws -> Data {
        var out = ""
        try write(value, into: &out)
        return Data(out.utf8)
    }

    // MARK: Writing

    private static func write(_ value: Value, into out: inout String) throws {
        switch value {
        case .null:
            out += "null"
        case .bool(let b):
            out += b ? "true" : "false"
        case .number(let literal):
            out += try number(literal)
        case .string(let s):
            string(s, into: &out)
        case .array(let a):
            out += "["
            for (i, e) in a.enumerated() {
                if i > 0 { out += "," }
                try write(e, into: &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            let keys = o.keys.sorted { $0.utf16.lexicographicallyPrecedes($1.utf16) }
            for (i, k) in keys.enumerated() {
                if i > 0 { out += "," }
                string(k, into: &out)
                out += ":"
                try write(o[k]!, into: &out)
            }
            out += "}"
        }
    }

    private static func string(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x08: out += "\\b"
            case 0x09: out += "\\t"
            case 0x0A: out += "\\n"
            case 0x0C: out += "\\f"
            case 0x0D: out += "\\r"
            case 0..<0x20: out += "\\" + "u" + String(format: "%04x", u.value)
            default: out.unicodeScalars.append(u)
            }
        }
        out += "\""
    }

    static let maxSafeInteger = "9007199254740991"

    /// A number literal in its canonical form. A plain integer beyond 2^53 - 1 is refused,
    /// as planque refuses it; one written with a fraction or exponent prints as a double.
    static func number(_ literal: String) throws -> String {
        if !literal.contains(where: { $0 == "." || $0 == "e" || $0 == "E" }) {
            var digits = Substring(literal)
            if digits.first == "-" { digits = digits.dropFirst() }
            while digits.count > 1 && digits.first == "0" { digits = digits.dropFirst() }
            if digits.count > maxSafeInteger.count || (digits.count == maxSafeInteger.count && digits > Substring(maxSafeInteger)) {
                throw Failure.unsafeInteger
            }
        }
        guard let d = Double(literal), d.isFinite else { throw Failure.notFinite }
        return try ecmaScript(d)
    }

    /// ECMAScript's Number::toString, which RFC 8785 uses for every number. Swift's own
    /// description already gives the shortest digits that round-trip; this only places them.
    static func ecmaScript(_ d: Double) throws -> String {
        guard d.isFinite else { throw Failure.notFinite }
        if d == 0 { return "0" }
        if d < 0 { return "-" + (try ecmaScript(-d)) }
        let text = "\(d)".lowercased()
        let parts = text.split(separator: "e", maxSplits: 1)
        let mantissa = parts[0]
        let exp = parts.count > 1 ? Int(parts[1].replacingOccurrences(of: "+", with: ""))! : 0
        let pieces = mantissa.split(separator: ".", omittingEmptySubsequences: false)
        let intPart = String(pieces[0])
        let fracPart = pieces.count > 1 ? String(pieces[1]) : ""
        var digits = Array(intPart + fracPart)
        var point = intPart.count
        while digits.first == "0" { digits.removeFirst(); point -= 1 }
        while digits.last == "0" { digits.removeLast() }
        let s = String(digits)
        let k = s.count
        let n = point + exp
        if k <= n && n <= 21 { return s + String(repeating: "0", count: n - k) }
        if 0 < n && n <= 21 {
            let i = s.index(s.startIndex, offsetBy: n)
            return s[..<i] + "." + s[i...]
        }
        if -6 < n && n <= 0 { return "0." + String(repeating: "0", count: -n) + s }
        let e = n - 1
        let sign = e < 0 ? "-" : "+"
        let head = k == 1 ? s : String(s.first!) + "." + s.dropFirst()
        return head + "e" + sign + String(abs(e))
    }

    // MARK: Reading

    /// Reads one JSON value (RFC 8259), keeping each number's literal.
    static func parse(_ data: Data) throws -> Value {
        var p = Parser(bytes: Array(data))
        p.space()
        let v = try p.value(depth: 0)
        p.space()
        guard p.i == p.bytes.count else { throw Failure.notJSON }
        return v
    }

    private struct Parser {
        let bytes: [UInt8]
        var i = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        mutating func space() {
            while i < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[i]) { i += 1 }
        }

        mutating func expect(_ s: String) throws {
            let u = Array(s.utf8)
            guard i + u.count <= bytes.count, Array(bytes[i..<i + u.count]) == u else { throw Failure.notJSON }
            i += u.count
        }

        mutating func value(depth: Int) throws -> Value {
            guard depth < 200, i < bytes.count else { throw Failure.notJSON }
            switch bytes[i] {
            case UInt8(ascii: "n"): try expect("null"); return .null
            case UInt8(ascii: "t"): try expect("true"); return .bool(true)
            case UInt8(ascii: "f"): try expect("false"); return .bool(false)
            case UInt8(ascii: "\""): return .string(try string())
            case UInt8(ascii: "["):
                i += 1; space()
                var a: [Value] = []
                if i < bytes.count, bytes[i] == UInt8(ascii: "]") { i += 1; return .array(a) }
                while true {
                    space(); a.append(try value(depth: depth + 1)); space()
                    guard i < bytes.count else { throw Failure.notJSON }
                    if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                    if bytes[i] == UInt8(ascii: "]") { i += 1; return .array(a) }
                    throw Failure.notJSON
                }
            case UInt8(ascii: "{"):
                i += 1; space()
                var o: [String: Value] = [:]
                if i < bytes.count, bytes[i] == UInt8(ascii: "}") { i += 1; return .object(o) }
                while true {
                    space()
                    guard i < bytes.count, bytes[i] == UInt8(ascii: "\"") else { throw Failure.notJSON }
                    let k = try string()
                    space(); try expect(":"); space()
                    o[k] = try value(depth: depth + 1)   // a repeated key: the last wins, as in Go
                    space()
                    guard i < bytes.count else { throw Failure.notJSON }
                    if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                    if bytes[i] == UInt8(ascii: "}") { i += 1; return .object(o) }
                    throw Failure.notJSON
                }
            default:
                return .number(try number())
            }
        }

        mutating func number() throws -> String {
            let start = i
            func digits() -> Int { let s = i; while i < bytes.count, (0x30...0x39).contains(bytes[i]) { i += 1 }; return i - s }
            if i < bytes.count, bytes[i] == UInt8(ascii: "-") { i += 1 }
            guard i < bytes.count else { throw Failure.notJSON }
            if bytes[i] == UInt8(ascii: "0") { i += 1 } else if digits() == 0 { throw Failure.notJSON }
            if i < bytes.count, bytes[i] == UInt8(ascii: ".") {
                i += 1
                guard digits() > 0 else { throw Failure.notJSON }
            }
            if i < bytes.count, bytes[i] == UInt8(ascii: "e") || bytes[i] == UInt8(ascii: "E") {
                i += 1
                if i < bytes.count, bytes[i] == UInt8(ascii: "+") || bytes[i] == UInt8(ascii: "-") { i += 1 }
                guard digits() > 0 else { throw Failure.notJSON }
            }
            return String(decoding: bytes[start..<i], as: UTF8.self)
        }

        mutating func hex4() throws -> UInt32 {
            guard i + 4 <= bytes.count, let v = UInt32(String(decoding: bytes[i..<i + 4], as: UTF8.self), radix: 16) else { throw Failure.notJSON }
            i += 4
            return v
        }

        mutating func string() throws -> String {
            i += 1 // the opening quote
            var scalars = String.UnicodeScalarView()
            var run: [UInt8] = []
            func flush() throws {
                guard !run.isEmpty else { return }
                guard let s = String(bytes: run, encoding: .utf8) else { throw Failure.notJSON }
                scalars.append(contentsOf: s.unicodeScalars)
                run = []
            }
            while i < bytes.count {
                let b = bytes[i]
                switch b {
                case UInt8(ascii: "\""):
                    i += 1
                    try flush()
                    return String(scalars)
                case UInt8(ascii: "\\"):
                    try flush()
                    i += 1
                    guard i < bytes.count else { throw Failure.notJSON }
                    let e = bytes[i]
                    i += 1
                    switch e {
                    case UInt8(ascii: "\""): scalars.append("\"")
                    case UInt8(ascii: "\\"): scalars.append("\\")
                    case UInt8(ascii: "/"): scalars.append("/")
                    case UInt8(ascii: "b"): scalars.append(Unicode.Scalar(0x08))
                    case UInt8(ascii: "f"): scalars.append(Unicode.Scalar(0x0C))
                    case UInt8(ascii: "n"): scalars.append(Unicode.Scalar(0x0A))
                    case UInt8(ascii: "r"): scalars.append(Unicode.Scalar(0x0D))
                    case UInt8(ascii: "t"): scalars.append(Unicode.Scalar(0x09))
                    case UInt8(ascii: "u"):
                        var v = try hex4()
                        if (0xD800...0xDBFF).contains(v) {
                            // A surrogate pair, written as two escapes.
                            guard i + 1 < bytes.count, bytes[i] == UInt8(ascii: "\\"), bytes[i + 1] == UInt8(ascii: "u") else { throw Failure.notJSON }
                            i += 2
                            let low = try hex4()
                            guard (0xDC00...0xDFFF).contains(low) else { throw Failure.notJSON }
                            v = 0x10000 + ((v - 0xD800) << 10) + (low - 0xDC00)
                        }
                        guard let scalar = Unicode.Scalar(v) else { throw Failure.notJSON }
                        scalars.append(scalar)
                    default:
                        throw Failure.notJSON
                    }
                case 0..<0x20:
                    throw Failure.notJSON
                default:
                    run.append(b)
                    i += 1
                }
            }
            throw Failure.notJSON
        }
    }
}
