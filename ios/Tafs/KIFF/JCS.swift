import Foundation

/// RFC 8785 (JSON Canonicalization Scheme), as planque computes a grant's digest: the
/// phone recomputes it from the request it shows, so the Face ID approves exactly that
/// request. Keys sort by UTF-16 code units, numbers print as ECMAScript does, and an
/// integer beyond 2^53 - 1 is refused, as planque and KIFF refuse it.
enum JCS {
    enum Failure: Error, Equatable {
        case notJSON
        case unsafeInteger
        case notFinite
    }

    /// The canonical form of the JSON in `data`.
    static func canonical(_ data: Data) throws -> Data {
        guard let value = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { throw Failure.notJSON }
        return try canonical(value: value)
    }

    /// The canonical form of a value `JSONSerialization` read.
    static func canonical(value: Any) throws -> Data {
        var out = ""
        try write(value, into: &out)
        return Data(out.utf8)
    }

    private static func write(_ value: Any, into out: inout String) throws {
        switch value {
        case is NSNull:
            out += "null"
        case let s as String:
            string(s, into: &out)
        case let n as NSNumber:
            try number(n, into: &out)
        case let a as [Any]:
            out += "["
            for (i, e) in a.enumerated() {
                if i > 0 { out += "," }
                try write(e, into: &out)
            }
            out += "]"
        case let o as [String: Any]:
            out += "{"
            let keys = o.keys.sorted { $0.utf16.lexicographicallyPrecedes($1.utf16) }
            for (i, k) in keys.enumerated() {
                if i > 0 { out += "," }
                string(k, into: &out)
                out += ":"
                try write(o[k]!, into: &out)
            }
            out += "}"
        default:
            throw Failure.notJSON
        }
    }

    private static func string(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{09}": out += "\\t"
            case "\u{0A}": out += "\\n"
            case "\u{0C}": out += "\\f"
            case "\u{0D}": out += "\\r"
            case let c where c.value < 0x20: out += String(format: "\\u%04x", c.value)
            default: out.unicodeScalars.append(u)
            }
        }
        out += "\""
    }

    static let maxSafeInteger: Int64 = 9_007_199_254_740_991

    private static func number(_ n: NSNumber, into out: inout String) throws {
        if CFGetTypeID(n) == CFBooleanGetTypeID() {
            out += n.boolValue ? "true" : "false"
            return
        }
        // A plain integer literal reads as an integer type: refused past 2^53 - 1, as
        // planque refuses it. A literal with a fraction or exponent reads as a double and
        // prints as one, as planque prints it.
        let type = String(cString: n.objCType)
        if "cislqCISLQ".contains(type) {
            let magnitude = (type == "Q" || type == "L") ? n.uint64Value : n.int64Value.magnitude
            let unsafe = magnitude > UInt64(maxSafeInteger)
            if unsafe { throw Failure.unsafeInteger }
        }
        out += try ecmaScript(n.doubleValue)
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
}
