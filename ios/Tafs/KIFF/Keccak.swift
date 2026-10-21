import Foundation

/// Keccak-256 as Ethereum and KIFF's receipts use it (kiff/tafs#167): the original Keccak
/// padding (0x01), not SHA-3's (0x06). CryptoKit has SHA-3 only from iOS 26, and with the
/// other padding, so this is the permutation itself, checked against the standard vectors.
enum Keccak {
    static func hash256(_ data: Data) -> Data {
        let rate = 136
        var state = [UInt64](repeating: 0, count: 25)
        var message = [UInt8](data)
        // Pad: 0x01, zeros, and 0x80 on the block's last byte (the same byte when one is left).
        message.append(0x01)
        while message.count % rate != 0 { message.append(0) }
        message[message.count - 1] |= 0x80

        for block in stride(from: 0, to: message.count, by: rate) {
            for i in 0..<(rate / 8) {
                var lane: UInt64 = 0
                for b in 0..<8 { lane |= UInt64(message[block + i * 8 + b]) << (8 * UInt64(b)) }
                state[i] ^= lane
            }
            permute(&state)
        }

        var out = Data(capacity: 32)
        for i in 0..<4 {
            for b in 0..<8 { out.append(UInt8(truncatingIfNeeded: state[i] >> (8 * UInt64(b)))) }
        }
        return out
    }

    private static let roundConstants: [UInt64] = [
        0x0000000000000001, 0x0000000000008082, 0x800000000000808A, 0x8000000080008000,
        0x000000000000808B, 0x0000000080000001, 0x8000000080008081, 0x8000000000008009,
        0x000000000000008A, 0x0000000000000088, 0x0000000080008009, 0x000000008000000A,
        0x000000008000808B, 0x800000000000008B, 0x8000000000008089, 0x8000000000008003,
        0x8000000000008002, 0x8000000000000080, 0x000000000000800A, 0x800000008000000A,
        0x8000000080008081, 0x8000000000008080, 0x0000000080000001, 0x8000000080008008,
    ]

    /// Rotation offsets, indexed x + 5y.
    private static let rotations: [UInt64] = [
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    ]

    private static func rotl(_ x: UInt64, _ n: UInt64) -> UInt64 {
        n == 0 ? x : (x << n) | (x >> (64 - n))
    }

    /// Keccak-f[1600]: 24 rounds of θ, ρ and π, χ, ι.
    private static func permute(_ a: inout [UInt64]) {
        var c = [UInt64](repeating: 0, count: 5)
        var b = [UInt64](repeating: 0, count: 25)
        for round in 0..<24 {
            for x in 0..<5 { c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20] }
            for x in 0..<5 {
                let d = c[(x + 4) % 5] ^ rotl(c[(x + 1) % 5], 1)
                for y in 0..<5 { a[x + 5 * y] ^= d }
            }
            for x in 0..<5 {
                for y in 0..<5 {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = rotl(a[x + 5 * y], rotations[x + 5 * y])
                }
            }
            for x in 0..<5 {
                for y in 0..<5 {
                    a[x + 5 * y] = b[x + 5 * y] ^ (~b[(x + 1) % 5 + 5 * y] & b[(x + 2) % 5 + 5 * y])
                }
            }
            a[0] ^= roundConstants[round]
        }
    }
}
