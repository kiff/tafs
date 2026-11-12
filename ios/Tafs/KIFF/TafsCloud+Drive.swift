import CoreGraphics
import Foundation

/// Coach drives the app (docs/drive.md): the person's drive on tafs cloud, its stream of requests,
/// and the app's answers.
extension TafsCloud {
    struct Drive: Decodable, Equatable, Sendable {
        let id: String
        let session: String
        let task: String
        var started: String?
        var ended: DriveEnded?
    }

    struct DriveEnded: Decodable, Equatable, Sendable {
        let why: String
        var at: String?
        /// tafs cloud's words for why: what the caption says.
        var words: String?
    }

    /// One step the drive did, for the "What I did" card.
    struct DriveStep: Decodable, Equatable, Sendable {
        let at: String
        let line: String
        var touched: DriveTouched?
    }

    struct DriveTouched: Codable, Equatable, Sendable {
        let words: String
        var taf: String?
    }

    /// A drive's end in Coach's thread, at the time it ended: what it did, from tafs cloud's record.
    struct DriveRecap: Decodable, Equatable, Sendable {
        let id: String
        var session: String?
        let task: String
        var why: String?
        /// tafs cloud's words for why it ended.
        var words: String?
        var steps: [DriveStep] = []
        let at: Date

        enum CodingKeys: String, CodingKey { case id, session, task, why, words, steps, at }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            session = try c.decodeIfPresent(String.self, forKey: .session)
            task = try c.decode(String.self, forKey: .task)
            why = try c.decodeIfPresent(String.self, forKey: .why)
            words = try c.decodeIfPresent(String.self, forKey: .words)
            steps = (try? c.decodeIfPresent([DriveStep].self, forKey: .steps)) ?? []
            at = try c.decode(Date.self, forKey: .at)
        }
    }

    struct DriveRead: Decodable, Equatable, Sendable {
        var drive: Drive?
        var steps: [DriveStep]?
    }

    /// One event of the drive's stream.
    enum DriveEvent: Equatable, Sendable {
        case drive(String)
        case request(DriveRequest)
        case ended(why: String, words: String)
        case reopen
    }

    /// One step for the app to do, as the stream sends it: Coach's arguments, checked by tafs cloud.
    struct DriveRequest: Equatable, Sendable {
        let id: String
        /// app_look or app_act.
        let tool: String
        /// How long the app has to start it, by its own clock from when it came; nil: no limit said.
        var startWithinMs: Int?
        var tree = false
        var diagnostics = false
        var wait: String?
        var timeoutS: Int?
        var action: String?
        var line: String?
        /// A point on the last screenshot, in its pixels.
        var at: CGPoint?
        var element: String?
        var to: CGPoint?
        var ms: Int?
        var direction: String?
        var text: String?
        /// When it came on the stream, by the app's clock.
        var came: Date?
    }

    /// The app's answer to one request.
    struct DriveResult: Encodable, Equatable {
        struct Image: Encodable, Equatable {
            let mediaType: String
            let data: String
            enum CodingKeys: String, CodingKey { case mediaType = "media_type", data }
        }

        struct Element: Encodable, Equatable {
            struct Bounds: Encodable, Equatable { let x: Int, y: Int, w: Int, h: Int }
            let id: String
            let words: String
            let role: String
            let bounds: Bounds
            let actions: [String]
            /// Only on a confirming control.
            var confirm: Bool?
        }

        struct Waited: Encodable, Equatable { let event: String, came: Bool }

        var outcome: String
        var line: String
        var image: Image?
        var tree: [Element]?
        var diagnostics: DriveDiagnosticsReport?
        var touched: DriveTouched?
        var waited: Waited?

        static func done(_ line: String) -> DriveResult { DriveResult(outcome: "done", line: line) }
        static func refused(_ line: String) -> DriveResult { DriveResult(outcome: "refused", line: line) }
    }

    /// The body a drive's start sends, as the approval key signs it: these exact bytes, in this order.
    nonisolated static func driveStartBody(session: String, task: String, component: String?) -> Data {
        func quoted(_ s: String) -> String {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.withoutEscapingSlashes]
            return (try? encoder.encode(s)).map { String(decoding: $0, as: UTF8.self) } ?? "\"\""
        }
        var fields = ["\"session\":\(quoted(session))", "\"task\":\(quoted(task))"]
        if let component { fields.append("\"component\":\(quoted(component))") }
        return Data(("{" + fields.joined(separator: ",") + "}").utf8)
    }

    static let drivePath = "/v1/coach/drive"

    /// Starts the person's drive, signed with the approval key: `body` is `driveStartBody`'s bytes,
    /// `signature` the `KIFF-Device-Signature` header made over them.
    func startDrive(body: Data, signature: String) async throws -> Drive {
        struct Answer: Decodable { let drive: Drive }
        let data = try await sendData("POST", Self.drivePath, body: KIFFClient.RawJSON(data: body), headers: ["KIFF-Device-Signature": signature])
        return try KIFFClient.decoder.decode(Answer.self, from: data).drive
    }

    /// The drive as it stands, and its steps.
    func readDrive() async throws -> DriveRead {
        try await send("GET", Self.drivePath)
    }

    /// Ends the drive: `why` is the app's (owner_took_over, left_app, stopped, locked, signed_out).
    @discardableResult
    func stopDrive(_ drive: String, why: String) async throws -> DriveEnded? {
        struct Body: Encodable { let drive: String, why: String }
        struct Answer: Decodable { let ended: DriveEnded? }
        let a: Answer = try await send("POST", Self.drivePath + "/stop", body: Body(drive: drive, why: why))
        return a.ended
    }

    /// The person touched the app: tafs cloud sends no step until `resumeDrive`. Pausing one already
    /// paused changes nothing.
    func pauseDrive(_ drive: String) async throws {
        struct Body: Encodable { let drive: String }
        _ = try await sendData("POST", Self.drivePath + "/pause", body: Body(drive: drive))
    }

    /// The person tapped Continue: the drive goes on, and Coach's first step is a look.
    func resumeDrive(_ drive: String) async throws {
        struct Body: Encodable { let drive: String }
        _ = try await sendData("POST", Self.drivePath + "/resume", body: Body(drive: drive))
    }

    func postDriveResult(_ drive: String, request: String, result: DriveResult) async throws {
        _ = try await sendData("POST", Self.drivePath + "/\(escaped(drive))/results/\(escaped(request))", body: result)
    }

    /// The drive's requests as they come, on the streaming function's address.
    func driveStream(_ drive: String) async throws -> AsyncThrowingStream<DriveEvent, Error> {
        guard let url = URL(string: Self.drivePath + "/\(escaped(drive))/stream", relativeTo: streamURL), let token else {
            throw CloudError(status: 401, message: Words.Error.signInFirst)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 900
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(KIFFClient.acceptLanguage(KIFFClient.screenLanguages), forHTTPHeaderField: "Accept-Language")
        let (bytes, response) = try await session.bytes(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            var data = Data()
            for try await b in bytes { data.append(b) }
            throw Self.refusal(status, data)
        }
        return AsyncThrowingStream { continuation in
            let reading = Task {
                do {
                    var name = "message"
                    for try await line in bytes.lines {
                        if line.hasPrefix("event:") {
                            name = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
                        } else if line.hasPrefix("data:"), let e = Self.driveEvent(name, data: Data(line.dropFirst(5).utf8)) {
                            if case .request(var r) = e {
                                r.came = Date()
                                continuation.yield(.request(r))
                            } else {
                                continuation.yield(e)
                            }
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in reading.cancel() }
        }
    }

    /// One event of the drive's stream, from its name and its line of JSON; nil when unknown.
    nonisolated static func driveEvent(_ name: String, data: Data) -> DriveEvent? {
        let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        switch name {
        case "drive": return .drive(o["drive"] as? String ?? "")
        case "ended": return .ended(why: o["why"] as? String ?? "", words: o["words"] as? String ?? "")
        case "reopen": return .reopen
        case "request":
            guard let id = o["id"] as? String, let tool = o["tool"] as? String else { return nil }
            let args = o["args"] as? [String: Any] ?? [:]
            func int(_ v: Any?) -> Int? { (v as? NSNumber)?.intValue }
            func point(_ v: Any?) -> CGPoint? {
                guard let p = v as? [String: Any], let x = (p["x"] as? NSNumber)?.doubleValue, let y = (p["y"] as? NSNumber)?.doubleValue else { return nil }
                return CGPoint(x: x, y: y)
            }
            var r = DriveRequest(id: id, tool: tool, startWithinMs: int(o["start_within_ms"]))
            r.tree = args["tree"] as? Bool ?? false
            r.diagnostics = args["diagnostics"] as? Bool ?? false
            r.wait = args["wait"] as? String
            r.timeoutS = int(args["timeout_s"])
            r.action = args["action"] as? String
            r.line = args["line"] as? String
            r.at = point(args["at"])
            r.element = args["element"] as? String
            r.to = point(args["to"])
            r.ms = int(args["ms"])
            r.direction = args["direction"] as? String
            r.text = args["text"] as? String
            return .request(r)
        default: return nil
        }
    }
}
