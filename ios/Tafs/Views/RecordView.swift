import SwiftUI

/// The Record (kiff/tafs#167): every call each taf made, with how much, on what, who decided
/// and what the tool did, and KIFF's receipt for it. Reads fold into one line a day.
struct RecordView: View {
    @EnvironmentObject private var model: AppModel
    @State private var filter: RecordFilter = .all
    /// The days whose reads are shown, not folded.
    @State private var openReads: Set<String> = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("Record").font(Theme.display(34)).foregroundColor(Theme.ink)
                Text("Everything your tafs did, were refused, or asked you. Newest first.")
                    .font(.system(size: 14)).foregroundColor(Theme.soft)
                chips
                ForEach(days, id: \.key) { day in
                    Text(day.title).font(Theme.mono(11, weight: .medium)).textCase(.uppercase)
                        .foregroundColor(Theme.dim).padding(.top, 6)
                    ForEach(day.lines) { RecordRow(entry: $0, showTaf: true) }
                    if !day.reads.isEmpty {
                        if openReads.contains(day.key) {
                            ForEach(day.reads) { RecordRow(entry: $0, showTaf: true) }
                        }
                        Button {
                            if openReads.contains(day.key) { openReads.remove(day.key) } else { openReads.insert(day.key) }
                        } label: {
                            Text("· \(day.reads.count == 1 ? "1 read" : "\(day.reads.count) reads") of data, within its Card · \(openReads.contains(day.key) ? "Hide" : "Show")")
                                .font(.system(size: 13)).foregroundColor(Theme.dim).padding(.horizontal, 4)
                        }
                        .buttonStyle(.plain)
                    }
                }
                if days.isEmpty {
                    Text(filter == .all ? "Nothing yet." : "Nothing here.").font(.system(size: 14)).foregroundColor(Theme.dim).padding(.top, 8)
                }
            }
            // The full width on an iPad, as the other tabs (kiff/tafs#96).
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .coachClearance()
        .screenBackground()
    }

    private var chips: some View {
        HStack(spacing: 6) {
            ForEach(RecordFilter.allCases, id: \.self) { f in
                Button { filter = f } label: {
                    Text(f.title).font(.system(size: 13))
                        .foregroundColor(filter == f ? .white : Theme.soft)
                        .padding(.horizontal, 11).padding(.vertical, 5)
                        .background(filter == f ? Theme.ink : Theme.panel)
                        .overlay(Capsule().stroke(filter == f ? Theme.ink : Theme.line))
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(filter == f ? .isSelected : [])
            }
        }
    }

    private struct Day {
        let key: String
        let title: String
        var lines: [RecordEntry] = []
        var reads: [RecordEntry] = []
    }

    /// The Record by day, newest first; a day's reads apart, to fold.
    private var days: [Day] {
        let calendar = Calendar.current
        var out: [Day] = []
        for entry in model.record.filter(filter.keeps).sorted(by: { $0.at > $1.at }) {
            let start = calendar.startOfDay(for: entry.at)
            let key = ISO8601DateFormatter().string(from: start)
            if out.last?.key != key { out.append(Day(key: key, title: Self.dayTitle(start, calendar: calendar))) }
            if entry.readOnly == true && filter == .all { out[out.count - 1].reads.append(entry) } else { out[out.count - 1].lines.append(entry) }
        }
        return out
    }

    static func dayTitle(_ day: Date, calendar: Calendar = .current) -> String {
        let date = day.formatted(.dateTime.day().month(.abbreviated))
        if calendar.isDateInToday(day) { return "Today · \(date)" }
        if calendar.isDateInYesterday(day) { return "Yesterday · \(date)" }
        return day.formatted(.dateTime.weekday(.wide)) + " · " + date
    }
}

/// The Record's filters (kiff/tafs#167).
enum RecordFilter: CaseIterable {
    case all, moneyMoved, stopped, waiting

    var title: String {
        switch self {
        case .all: return "All"
        case .moneyMoved: return "Money moved"
        case .stopped: return "Stopped"
        case .waiting: return "Waiting"
        }
    }

    func keeps(_ e: RecordEntry) -> Bool {
        switch self {
        case .all: return true
        case .moneyMoved: return e.amount != nil && (e.kind == .done || e.kind == .approved)
        case .stopped: return e.kind == .refused || e.kind == .notDone || e.kind == .revoked
        case .waiting: return e.kind == .held || e.kind == .sending || e.kind == .unknown
        }
    }
}

extension RecordEntry {
    /// "€150.00", from KIFF's decimal string and its unit; the unit as written when it isn't
    /// a currency.
    var amountText: String? {
        guard let amount, let value = Decimal(string: amount.value, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        if amount.unit.count == 3, amount.unit == amount.unit.uppercased(), Locale.isoCurrencyCodes.contains(amount.unit) {
            return value.formatted(.currency(code: amount.unit))
        }
        return "\(amount.value) \(amount.unit)"
    }

    /// "Refund €150.00": the action's verb with its amount, as the mockup has it.
    var titleWithAmount: String {
        guard let money = amountText else { return title }
        let words = title.split(separator: " ")
        if words.count > 1, ["a", "an", "the"].contains(words[1].lowercased()) { return "\(words[0]) \(money)" }
        return "\(title) · \(money)"
    }

    /// "payment …QbstR": what it acted on, by the argument's name, when tafs has its value
    /// and it matches KIFF's hash.
    var onText: String? {
        guard let value = onValue else { return nil }
        let noun = (onArg ?? "it").replacingOccurrences(of: "_", with: " ")
        return value.count > 6 ? "\(noun) …\(value.suffix(5))" : "\(noun) \(value)"
    }

    var answeredByOwner: Bool { answer?.by == nil || answer?.by == "owner" }

    /// Who decided: its Card, the owner (approving or refusing), or KIFF's refusal.
    var decidedLine: String {
        if let answer, answeredByOwner {
            return answer.decision == "refused" ? "refused by you" : "approved by you"
        }
        switch kind {
        case .refused: return "refused by its Card"
        case .held: return "waiting for you"
        case .revoked: return "Card revoked"
        case .started: return "Card issued"
        default: return ownerApproved == true ? "approved by you" : "within its Card"
        }
    }

    /// What became of it at the tool.
    var resultLine: String {
        switch kind {
        case .done, .approved: return "done"
        case .refused, .revoked: return "nothing sent"
        case .notDone: return "not done"
        case .held: return "nothing sent yet"
        case .sending: return "sent, no answer yet"
        case .unknown: return "result unknown"
        case .started: return "started"
        }
    }
}

struct RecordRow: View {
    @EnvironmentObject private var model: AppModel
    let entry: RecordEntry
    var showTaf = false
    @State private var showingReceipt = false

    var body: some View {
        Button { showingReceipt = true } label: { row }
            .buttonStyle(.plain)
            .sheet(isPresented: $showingReceipt) { ReceiptView(entry: entry).environmentObject(model) }
            .accessibilityHint("Shows the receipt")
    }

    private var row: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle().fill(color).frame(width: 9, height: 9).padding(.top, 6).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(entry.titleWithAmount).font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink)
                    Spacer()
                    Text(entry.at.shortTime).font(Theme.mono(12)).foregroundColor(Theme.dim)
                }
                Text(detail).font(.system(size: 13)).foregroundColor(Theme.soft)
                if entry.receipt?.status == "signed" {
                    Label("KIFF receipt", systemImage: "checkmark.seal")
                        .font(.system(size: 12)).foregroundColor(Theme.ok)
                } else if entry.signature != nil {
                    Label("Signed on this iPhone", systemImage: "checkmark.seal")
                        .font(.system(size: 12)).foregroundColor(Theme.ok)
                }
            }
        }
        .padding(14)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }

    /// "<taf> · <who decided> · <result at the tool>"; a call from before KIFF said who
    /// decided reads as it did.
    private var detail: String {
        var parts: [String] = []
        if showTaf, let taf = model.taf(entry.tafID) { parts.append(taf.name) }
        if let on = entry.onText { parts.append(on) }
        if entry.receipt != nil || entry.answer != nil {
            parts += [entry.decidedLine, entry.resultLine]
        } else {
            parts.append(entry.detail)
        }
        return parts.joined(separator: " · ")
    }

    private var color: Color { entry.kind.color }
}

extension RecordEntry.Kind {
    var color: Color {
        switch self {
        case .done, .approved, .started: return Theme.ok
        case .held, .unknown, .sending: return Theme.amber
        case .refused, .revoked, .notDone: return Theme.no
        }
    }

    var label: String {
        switch self {
        case .done: return "Done within the Card"
        case .approved: return "Approved once by you"
        case .refused: return "Refused"
        case .held: return "Held for you"
        case .unknown: return "Outcome unknown"
        case .notDone: return "Not done"
        case .sending: return "In progress"
        case .revoked: return "Card revoked"
        case .started: return "Taf started"
        }
    }
}

/// One entry of the Record, as a tafs sheet (kiff/tafs#167): the facts, what happened, and
/// KIFF's receipt, checked on this device. Nothing opens outside the app.
struct ReceiptView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let entry: RecordEntry
    /// KIFF's receipt as it wrote it, and the device's check of it.
    @State private var raw: Data?
    @State private var check: ReceiptCheck?
    @State private var loadProblem: String?

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 8) {
                        Circle().fill(entry.kind.color).frame(width: 10, height: 10)
                        Text(stateLabel).font(.system(size: 14, weight: .semibold)).foregroundColor(entry.kind.color)
                    }
                    Text(entry.titleWithAmount).font(Theme.display(30)).foregroundColor(Theme.ink)
                    Panel {
                        line("Taf", model.taf(entry.tafID)?.name ?? "–")
                        line("When", entry.at.formatted(date: .abbreviated, time: .standard))
                        if let value = entry.onValue {
                            line("On", "\((entry.onArg ?? "").replacingOccurrences(of: "_", with: " ")) \(value)")
                        }
                        line("Outcome", outcome, bold: true)
                        if let why = entry.why, !why.isEmpty { line("Why", why.joined(separator: "; ")) }
                        line("Decided by", decidedBy)
                    }
                    if !steps.isEmpty {
                        Eyebrow(text: "What happened")
                        timeline
                    }
                    if let signature = entry.signature {
                        Panel(stroke: Theme.ok.opacity(0.4)) {
                            Label("Signed on this iPhone", systemImage: "checkmark.seal.fill")
                                .font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ok)
                            Text("Your answer was signed with a key that never leaves this device, after \(Biometry.name).")
                                .font(.system(size: 13)).foregroundColor(Theme.soft)
                            Text(signature)
                                .font(Theme.mono(11)).foregroundColor(Theme.dim)
                                .lineLimit(3).textSelection(.enabled)
                        }
                    }
                    if entry.receipt != nil { receiptPanel }
                    Button {
                        model.requestedTaf = entry.tafID
                        dismiss()
                    } label: {
                        Text("Open in the taf's chat").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
                .padding(20)
            }
            .screenBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .task { await loadReceipt() }
    }

    private func loadReceipt() async {
        guard let trace = entry.receipt?.traceID, model.mode == .kiff else { return }
        do {
            let data = try await model.kiff.receipt(traceID: trace)
            raw = data
            check = ReceiptCheck.check(data)
        } catch {
            loadProblem = "KIFF's receipt couldn't be loaded: \(error.localizedDescription)"
        }
    }

    // MARK: The receipt

    @ViewBuilder private var receiptPanel: some View {
        switch check {
        case .verified? where onMismatch:
            failedPanel("The payment on this line doesn't match KIFF's receipt.")
        case .verified(let signer)?:
            Panel(stroke: Theme.ok.opacity(0.4)) {
                Label("KIFF's receipt · checked on this iPhone", systemImage: "checkmark.seal.fill")
                    .font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ok)
                Text("KIFF signed this when it decided. The signature matches KIFF's address and nothing in the receipt has changed since.")
                    .font(.system(size: 13)).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                receiptFacts(signer: signer)
                fullReceipt
                receiptButtons(share: true)
            }
        case .notSignedYet?:
            Panel {
                HStack(spacing: 8) {
                    Circle().fill(Theme.amber).frame(width: 9, height: 9)
                    Text("KIFF's receipt · not signed yet").font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.ink)
                }
                Text("KIFF signs receipts within a minute of deciding. Check again shortly.")
                    .font(.system(size: 13)).foregroundColor(Theme.soft)
                if Date().timeIntervalSince(entry.at) > 10 * 60 {
                    Text("If this stays, tell us the receipt id below.").font(.system(size: 13)).foregroundColor(Theme.soft)
                    Text(entry.receipt?.traceID ?? "").font(Theme.mono(11.5)).foregroundColor(Theme.dim).textSelection(.enabled)
                }
            }
        case .failed?:
            failedPanel("This receipt was changed after KIFF signed it, or it wasn't signed by KIFF. Don't rely on this line.")
        case nil:
            Panel {
                if let loadProblem {
                    Text(loadProblem).font(.system(size: 13)).foregroundColor(Theme.no)
                    Button("Try again") { Task { await loadReceipt() } }.buttonStyle(OutlineButtonStyle())
                } else {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Checking KIFF's receipt on this iPhone…").font(.system(size: 13)).foregroundColor(Theme.soft)
                    }
                }
            }
        }
    }

    private func failedPanel(_ why: String) -> some View {
        Panel(stroke: Theme.no.opacity(0.5)) {
            Label("KIFF's receipt doesn't check out", systemImage: "xmark.seal.fill")
                .font(.system(size: 14, weight: .semibold)).foregroundColor(Theme.no)
            Text(why).font(.system(size: 13)).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            receiptFacts(signer: nil)
            fullReceipt
            receiptButtons(share: false)
        }
    }

    /// The signed chain names what the call acted on (its decision step's on_hash), and
    /// the line's value isn't it.
    private var onMismatch: Bool {
        guard let value = entry.onValue, let signed = steps.compactMap(\.onHash).first else { return false }
        return !KIFFMapping.isOn(value, hash: signed)
    }

    private func receiptFacts(signer: String?) -> some View {
        let issued = receiptField("issued_at").flatMap { ISO8601DateFormatter.precise.date(from: $0) ?? ISO8601DateFormatter().date(from: $0) }
        var lines = ["\(entry.receipt?.traceID ?? "") · \(decisionWords)"]
        var second: [String] = []
        if let issued { second.append("issued \(issued.formatted(date: .abbreviated, time: .standard))") }
        if let signer { second.append("signer \(signer.prefix(6))…\(signer.suffix(4))") }
        if !second.isEmpty { lines.append(second.joined(separator: " · ")) }
        return Text(lines.joined(separator: "\n")).font(Theme.mono(11.5)).foregroundColor(Theme.dim).textSelection(.enabled)
    }

    /// Every step KIFF signed, with its hash, in the sheet.
    @ViewBuilder private var fullReceipt: some View {
        if !steps.isEmpty {
            DisclosureGroup {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                        HStack(alignment: .top, spacing: 6) {
                            Text("\(i + 1)").frame(width: 18, alignment: .leading)
                            Text("\(step.words) · \(step.at.map { $0.formatted(date: .omitted, time: .standard) } ?? "")\nhash \(step.hash)")
                        }
                    }
                    if let sig = receiptAttestation("signature") {
                        HStack(alignment: .top, spacing: 6) {
                            Text("✎").frame(width: 18, alignment: .leading)
                            Text("trace \(entry.receipt?.traceID ?? "") · secp256k1 + keccak256\nsig 0x\(sig.prefix(4))…\(sig.suffix(4))")
                        }
                    }
                }
                .font(Theme.mono(11.5)).foregroundColor(Theme.dim).textSelection(.enabled)
                .padding(.top, 8)
            } label: {
                Text("Show the full receipt").font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.blue)
            }
        }
    }

    @ViewBuilder private func receiptButtons(share: Bool) -> some View {
        if let raw, let text = String(data: raw, encoding: .utf8) {
            HStack(spacing: 8) {
                Button("Copy receipt") { Clipboard.copy(text) }.buttonStyle(OutlineButtonStyle())
                #if os(iOS)
                if share { ShareLink("Share", item: text).buttonStyle(OutlineButtonStyle()) }
                #endif
            }
        }
    }

    // MARK: The signed steps

    private struct Step {
        let words: String
        let at: Date?
        let hash: String
        let kind: String
        /// What the step says the call acted on, as KIFF hashed it.
        let onHash: String?
    }

    /// The receipt's steps, as KIFF signed them: what the sheet shows as happened.
    private var steps: [Step] {
        guard let raw, let chain = RawJSON.value(of: "causal_chain", in: raw),
              let list = try? JSONSerialization.jsonObject(with: chain) as? [[String: Any]] else { return [] }
        return list.map { s in
            let kind = s["kind"] as? String ?? ""
            let message = (s["message"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            let at = (s["occurred_at"] as? String).flatMap { ISO8601DateFormatter.precise.date(from: $0) ?? ISO8601DateFormatter().date(from: $0) }
            let hash = s["data_hash"] as? String ?? ""
            return Step(words: message ?? kind.replacingOccurrences(of: "_", with: " "), at: at,
                        hash: hash.count > 8 ? "\(hash.prefix(4))…\(hash.suffix(4))" : hash, kind: kind,
                        onHash: (s["data"] as? [String: Any])?["on_hash"] as? String)
        }
    }

    private var timeline: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                HStack(alignment: .top, spacing: 12) {
                    VStack(spacing: 0) {
                        Circle().fill(Theme.panel).overlay(Circle().stroke(Theme.dim, lineWidth: 2)).frame(width: 10, height: 10).padding(.top, 5)
                        if i < steps.count - 1 { Rectangle().fill(Theme.line).frame(width: 2).frame(maxHeight: .infinity) }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(step.words.prefix(1).uppercased() + step.words.dropFirst()).font(.system(size: 14)).foregroundColor(Theme.ink)
                        if let at = step.at {
                            Text(at.formatted(date: .omitted, time: .standard)).font(Theme.mono(12)).foregroundColor(Theme.dim)
                        }
                    }
                    .padding(.bottom, 12)
                }
            }
        }
    }

    private func receiptField(_ key: String) -> String? {
        guard let raw, let r = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] else { return nil }
        return r[key] as? String
    }

    private func receiptAttestation(_ key: String) -> String? {
        guard let raw, let r = try? JSONSerialization.jsonObject(with: raw) as? [String: Any],
              let a = r["attestation"] as? [String: Any] else { return nil }
        return a[key] as? String
    }

    // MARK: The facts

    private var stateLabel: String {
        if let answer = entry.answer, entry.answeredByOwner { return answer.decision == "refused" ? "Refused by you" : "Approved once by you" }
        return entry.kind.label
    }

    private var outcome: String {
        let money = entry.amountText.map { " \($0)" } ?? ""
        switch entry.kind {
        case .done, .approved: return entry.amount == nil ? "Done." : "Done.\(money) moved."
        case .refused: return entry.answer?.decision == "refused" ? "Not sent. You refused it." : "Not sent. Its Card refused it."
        case .held: return "Waiting for your answer."
        default: return entry.detail
        }
    }

    private var decisionWords: String {
        if let answer = entry.answer, entry.answeredByOwner {
            return answer.decision == "refused" ? "held → refused by you" : "held → approved once by you"
        }
        return entry.kind == .refused ? "refused by the Card" : "allowed by the Card"
    }

    private var decidedBy: String {
        if let answer = entry.answer, entry.answeredByOwner {
            var how = "You"
            if answer.signed == true { how += ", signed on \(answer.device ?? "your device")" }
            return how
        }
        switch entry.kind {
        case .approved: return "You, approving once"
        case _ where entry.ownerApproved == true: return "You, approving once"
        case .refused where entry.signature != nil: return "You"
        case .revoked: return "You, revoking the Card"
        case .started: return "You, issuing the Card"
        default: return model.mode == .kiff ? "KIFF, against the Card" : "The Card"
        }
    }

    private func line(_ name: String, _ value: String, bold: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(name).font(.system(size: 13)).foregroundColor(Theme.dim).frame(width: 90, alignment: .leading)
            Text(value).font(.system(size: 14, weight: bold ? .semibold : .regular)).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }
}

extension ISO8601DateFormatter {
    /// RFC 3339 with fractional seconds, as Go writes times.
    static let precise: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}
