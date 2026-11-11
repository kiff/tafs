import SwiftUI

/// The Record (kiff/tafs#167): every call each taf made, with how much, on what, who decided
/// and what the tool did, and KIFF's receipt for it. Reads fold into one line a day.
struct RecordView: View {
    @EnvironmentObject private var model: AppModel
    @State private var filter: RecordFilter = .all
    /// The one taf shown, or every taf.
    @State private var taf: UUID?
    /// The days whose reads are shown, not folded.
    @State private var openReads: Set<String> = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(Words.Record.title).scaledFont(34, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                Text(Words.Record.lead)
                    .scaledFont(14).foregroundColor(Theme.soft)
                // Signed in: search across the person's tafs, and export receipts for whoever
                // sees every taf's Record. While a search is shown, it stands in for the list.
                if model.mode == .kiff {
                    RecordSearchBox()
                    if model.seesEveryTaf { ExportReceiptsRow() }
                }
                if model.mode == .kiff, let search = model.recordSearch {
                    RecordResults(search: search)
                } else {
                    list
                }
            }
            // The full width on an iPad, as the other tabs (kiff/tafs#96).
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .screenBackground()
        // One case, open from a search: drawn over the Record.
        .sheet(isPresented: Binding(get: { model.recordCase != nil }, set: { if !$0 { model.closeRecordCase() } })) {
            if let open = model.recordCase { RecordCaseSheet(recordCase: open, error: model.recordCaseError).environmentObject(model) }
        }
    }

    /// Every call by day, as before a search.
    @ViewBuilder private var list: some View {
        tafChips
        chips
        ForEach(days, id: \.key) { day in
            Text([day.title, Self.spent(day.lines + day.reads)].compactMap { $0 }.joined(separator: " · "))
                .scaledFont(11, weight: .medium, design: .monospaced).textCase(.uppercase)
                .foregroundColor(Theme.dim).padding(.top, 6)
            ForEach(day.lines) { RecordRow(entry: $0, showTaf: true) }
            if !day.reads.isEmpty {
                if openReads.contains(day.key) {
                    ForEach(day.reads) { RecordRow(entry: $0, showTaf: true) }
                }
                Button {
                    if openReads.contains(day.key) { openReads.remove(day.key) } else { openReads.insert(day.key) }
                } label: {
                    Text("· " + Words.Record.reads(count: day.reads.count) + " · " + (openReads.contains(day.key) ? Words.Record.hide : Words.Record.show))
                        .scaledFont(13).foregroundColor(Theme.ok).padding(.horizontal, 4)
                }
                .buttonStyle(.drivablePlain)
            }
        }
        if days.isEmpty {
            Text(filter == .all && taf == nil ? Words.Common.nothingYet : Words.Record.nothingHere).scaledFont(14).foregroundColor(Theme.dim).padding(.top, 8)
        }
    }

    /// "All tafs", then one chip a taf with its colour: it narrows the Record with the filter.
    private var tafChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                chip(Words.Record.allTafs, selected: taf == nil) { taf = nil }
                ForEach(model.tafs + model.otherAgents) { t in
                    chip(t.name, dot: Theme.tafColor(t.colorIndex), selected: taf == t.id) { taf = t.id }
                }
            }
        }
    }

    private var chips: some View {
        HStack(spacing: 6) {
            ForEach(RecordFilter.allCases, id: \.self) { f in
                chip(f.title, selected: filter == f) { filter = f }
            }
        }
    }

    /// A chip; the chosen one inverts: ink fill, the background's colour for its words.
    private func chip(_ title: String, dot: Color? = nil, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let dot { Circle().fill(dot).frame(width: 8, height: 8) }
                Text(title).scaledFont(13).lineLimit(1)
            }
            .foregroundColor(selected ? Theme.onFill : Theme.soft)
            .padding(.horizontal, 11).padding(.vertical, 5)
            .background(selected ? Theme.ink : Theme.panel)
            .overlay(Capsule().stroke(selected ? Theme.ink : Theme.line))
            .clipShape(Capsule())
        }
        .buttonStyle(.drivablePlain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// "€640 spent": the money moved that day, a sum for each currency; nil when none was.
    static func spent(_ entries: [RecordEntry]) -> String? {
        var sums: [String: Money] = [:]
        var order: [String] = []
        for e in entries where RecordFilter.moneyMoved.keeps(e) {
            guard let m = e.money, let unit = m.currency else { continue }
            if let sum = sums[unit] {
                let scale = max(sum.scale, m.scale)
                func at(_ x: Money) -> Int64 { x.minor * Int64(pow(10, Double(scale - x.scale))) }
                sums[unit] = Money(minor: at(sum) + at(m), currency: unit, scale: scale)
            } else {
                sums[unit] = m
                order.append(unit)
            }
        }
        guard !order.isEmpty else { return nil }
        return order.compactMap { sums[$0]?.formatted }.joined(separator: " + ") + " spent"
    }

    private struct Day {
        let key: String
        let title: String
        var lines: [RecordEntry] = []
        var reads: [RecordEntry] = []
    }

    /// The Record by day, newest first; a day's reads apart, to fold.
    private var days: [Day] {
        let calendar = AccountTime.calendar
        var out: [Day] = []
        for entry in model.record.filter({ filter.keeps($0) && (taf == nil || $0.tafID == taf) }).sorted(by: { $0.at > $1.at }) {
            let start = calendar.startOfDay(for: entry.at)
            let key = ISO8601DateFormatter().string(from: start)
            if out.last?.key != key { out.append(Day(key: key, title: Self.dayTitle(start, calendar: calendar))) }
            if entry.readOnly == true && filter == .all { out[out.count - 1].reads.append(entry) } else { out[out.count - 1].lines.append(entry) }
        }
        return out
    }

    static func dayTitle(_ day: Date, calendar: Calendar = AccountTime.calendar) -> String {
        let date = day.shown(.dateTime.day().month(.abbreviated))
        if calendar.isDateInToday(day) { return Words.Calendar.todayDate(date: "\(date)") }
        if calendar.isDateInYesterday(day) { return Words.Calendar.yesterdayDate(date: "\(date)") }
        return day.shown(.dateTime.weekday(.wide)) + " · " + date
    }
}

/// The Record's filters (kiff/tafs#167).
enum RecordFilter: CaseIterable {
    case all, moneyMoved, stopped, waiting

    var title: String {
        switch self {
        case .all: return Words.Record.Filter.all
        case .moneyMoved: return Words.Record.Filter.moneyMoved
        case .stopped: return Words.Record.Filter.stopped
        case .waiting: return Words.Record.Filter.waiting
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
        guard let amount, Decimal(string: amount.value, locale: Locale(identifier: "en_US_POSIX")) != nil else { return nil }
        guard let unit = amount.unit, !unit.isEmpty else { return amount.value }
        // As the app writes money everywhere: "US$80", never "US$80,00" (kiff/tafs#236, item 16).
        return money?.formatted ?? "\(amount.value) \(unit)"
    }

    /// The amount as money, when its unit is a currency.
    var money: Money? {
        guard let amount, let value = Decimal(string: amount.value, locale: Locale(identifier: "en_US_POSIX")),
              let unit = amount.unit, unit.count == 3, unit == unit.uppercased(), Locale.isoCurrencyCodes.contains(unit) else { return nil }
        let scale = amount.value.split(separator: ".").dropFirst().first?.count ?? 0
        var minor = value * Decimal(sign: .plus, exponent: scale, significand: 1)
        var whole = Decimal()
        NSDecimalRound(&whole, &minor, 0, .plain)
        return Money(minor: NSDecimalNumber(decimal: whole).int64Value, currency: unit, scale: scale)
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
        let noun = (onArg ?? Words.Record.On.it).replacingOccurrences(of: "_", with: " ")
        return value.count > 6 ? "\(noun) …\(value.suffix(5))" : "\(noun) \(value)"
    }

    /// The person reading answered it, as KIFF says (`answer.you`).
    var answeredByOwner: Bool { answer?.you == true }

    /// Who answered, in words: "you", or by KIFF's role for them ("an admin").
    var answerer: String {
        if answeredByOwner { return Words.Record.Answerer.you }
        guard let role = answer?.by, !role.isEmpty else { return Words.Record.Answerer.someoneElse }
        switch role {
        case "admin": return Words.People.RolePhrase.admin
        case "approver": return Words.People.RolePhrase.approver
        case "editor": return Words.People.RolePhrase.editor
        case "member": return Words.People.RolePhrase.member
        case "manager": return Words.People.RolePhrase.manager
        case "owner": return Words.People.RolePhrase.owner
        case "viewer": return Words.People.RolePhrase.viewer
        default: return Words.People.RolePhrase.other(role: role.replacingOccurrences(of: "_", with: " "))
        }
    }

    /// Who decided: its Card, the owner or another person (approving or refusing), or KIFF's refusal.
    var decidedLine: String {
        if let answer {
            return answer.decision == "refused" ? Words.Record.Decided.refusedBy(who: "\(answerer)") : Words.Record.Decided.approvedBy(who: "\(answerer)")
        }
        switch kind {
        case .refused: return Words.Record.Decided.refusedByCard
        case .held: return Words.Record.Decided.waitingForYou
        case .revoked: return Words.Card.cardRevoked
        case .started: return Words.Record.Decided.cardIssued
        default: return ownerApproved == true ? Words.Record.Decided.approvedByYou : Words.Record.Decided.withinCard
        }
    }

    /// What became of it at the tool.
    var resultLine: String {
        switch kind {
        case .done, .approved: return Words.Record.Result.done
        case .refused, .revoked: return Words.Record.nothingSent
        case .notDone: return Words.Record.Result.notDone
        case .held: return Words.Record.Result.nothingSentYet
        case .sending: return Words.Record.Result.noAnswerYet
        case .unknown: return Words.Record.Result.unknown
        case .started: return Words.Record.Result.started
        case .inTafs: return ""
        }
    }
}

struct RecordRow: View {
    @EnvironmentObject private var model: AppModel
    let entry: RecordEntry
    var showTaf = false
    @State private var showingReceipt = false

    var body: some View {
        if entry.kind == .inTafs {
            // Done in tafs, not a call: there's no receipt to show (kiff/tafs#236).
            row.accessibilityElement(children: .combine)
        } else {
            Button { showingReceipt = true } label: { row }
                .buttonStyle(.drivablePlain)
                .sheet(isPresented: $showingReceipt) { ReceiptView(entry: entry).environmentObject(model) }
                .accessibilityHint(Words.Record.showsReceipt)
        }
    }

    private var row: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle().fill(color).frame(width: 9, height: 9).padding(.top, 6).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    if showTaf, let taf = model.taf(entry.tafID) {
                        // "Chargebacks · Refund €120": its taf first, by its colour and name.
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Circle().fill(Theme.tafColor(taf.colorIndex)).frame(width: 8, height: 8).alignmentGuide(.firstTextBaseline) { $0[.bottom] }
                            Text("\(taf.name) · \(entry.titleWithAmount)").scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                        }
                    } else {
                        Text(entry.titleWithAmount).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                    }
                    Spacer()
                    Text(entry.at.zonedShortTime).scaledFont(12, design: .monospaced).foregroundColor(Theme.dim)
                }
                Text(detail).scaledFont(13).foregroundColor(Theme.soft)
                if entry.receipt?.status == "signed" {
                    // KIFF says it's signed; the seal is for the sheet's own check.
                    Label(Words.Receipt.kiffReceipt, systemImage: "doc.text")
                        .scaledFont(12).foregroundColor(Theme.ok)
                } else if entry.signature != nil {
                    Label(Words.Record.signedOnDevice(device: "\(ThisDevice.kind)"), systemImage: "checkmark.seal")
                        .scaledFont(12).foregroundColor(Theme.ok)
                }
            }
        }
        .padding(14)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }

    /// "<who decided> · <result at the tool>"; a call from before KIFF said who decided reads
    /// as it did. The taf is the title's.
    private var detail: String {
        var parts: [String] = []
        if let on = entry.onText { parts.append(on) }
        if entry.kind == .held, !model.isWaiting(entry) {
            // Held, and KIFF no longer holds it (the 789 retest: the row still said "waiting for
            // you" after it expired). It may have expired, or been withdrawn when its run stopped.
            parts += [Words.Record.noLongerWaiting, Words.Record.nothingSent]
        } else if entry.receipt != nil || entry.answer != nil {
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
        case .inTafs: return Theme.dim
        }
    }

    var label: String {
        switch self {
        case .done: return Words.Receipt.Label.done
        case .approved: return Words.Receipt.Label.approved
        case .refused: return Words.Record.Filter.refused
        case .held: return Words.Receipt.Label.held
        case .unknown: return Words.Receipt.Label.unknown
        case .notDone: return Words.Receipt.Label.notDone
        case .sending: return Words.Receipt.Label.sending
        case .revoked: return Words.Card.cardRevoked
        case .started: return Words.Receipt.Label.started
        case .inTafs: return Words.Receipt.Label.inTafs
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
                        Text(stateLabel).scaledFont(14, weight: .semibold).foregroundColor(entry.kind.color)
                    }
                    Text(entry.titleWithAmount).scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    Panel {
                        line(Words.Receipt.Fact.taf, model.taf(entry.tafID)?.name ?? "–")
                        line(Words.Receipt.Fact.at, entry.at.zonedShown(date: .abbreviated, time: .standard))
                        if let value = entry.onValue {
                            line(Words.Receipt.Fact.on, "\((entry.onArg ?? "").replacingOccurrences(of: "_", with: " ")) \(value)")
                        }
                        line(Words.Receipt.Fact.outcome, outcome, bold: true)
                        // KIFF's words, never its codes (kiff/tafs#138).
                        if let reason = entry.reason { line(Words.Receipt.Fact.why, reason) }
                        line(Words.Receipt.Fact.decidedBy, decidedBy)
                    }
                    if !steps.isEmpty {
                        Eyebrow(text: Words.Receipt.whatHappened)
                        timeline
                    }
                    if let signature = entry.signature {
                        Panel(stroke: Theme.ok.opacity(0.4)) {
                            Label(Words.Record.signedOnDevice(device: "\(ThisDevice.kind)"), systemImage: "checkmark.seal.fill")
                                .scaledFont(14, weight: .semibold).foregroundColor(Theme.ok)
                            Text(Words.Receipt.signedOnDeviceWhy(biometry: "\(Biometry.name)"))
                                .scaledFont(13).foregroundColor(Theme.soft)
                            Text(signature)
                                .scaledFont(11, design: .monospaced).foregroundColor(Theme.dim)
                                .lineLimit(3).textSelection(.enabled)
                        }
                    }
                    if entry.receipt != nil { receiptPanel }
                    Button {
                        model.requestedTaf = entry.tafID
                        dismiss()
                    } label: {
                        Text(Words.Record.openTafChat).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
                .padding(20)
            }
            .screenBackground()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(Words.Record.Filter.done) { dismiss() }.keyboardShortcut(.cancelAction) } }
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
            loadProblem = Words.Receipt.notLoaded(why: "\(error.localizedDescription)")
        }
    }

    // MARK: The receipt

    @ViewBuilder private var receiptPanel: some View {
        switch check {
        case .verified? where onMismatch:
            failedPanel(Words.Receipt.Check.paymentMismatch)
        case .verified? where amountMismatch:
            failedPanel(Words.Receipt.Check.amountMismatch)
        case .verified? where answerMismatch:
            failedPanel(Words.Receipt.Check.answerMismatch)
        case .verified(let signer)?:
            Panel(stroke: Theme.ok.opacity(0.4)) {
                Label(Words.Receipt.checkedOnDevice(device: "\(ThisDevice.kind)"), systemImage: "checkmark.seal.fill")
                    .scaledFont(14, weight: .semibold).foregroundColor(Theme.ok)
                Text(Words.Receipt.checkedWhy)
                    .scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                receiptFacts(signer: signer)
                fullReceipt(signer: signer)
                receiptButtons(share: true)
            }
        case .notSignedYet?:
            Panel {
                HStack(spacing: 8) {
                    Circle().fill(Theme.amber).frame(width: 9, height: 9)
                    Text(Words.Receipt.notSignedYet).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                }
                Text(Words.Receipt.signsWithin)
                    .scaledFont(13).foregroundColor(Theme.soft)
                if Date().timeIntervalSince(entry.at) > 10 * 60 {
                    Text(Words.Receipt.ifItStays).scaledFont(13).foregroundColor(Theme.soft)
                    Text(entry.receipt?.traceID ?? "").scaledFont(11.5, design: .monospaced).foregroundColor(Theme.dim).textSelection(.enabled)
                }
            }
        case .failed?:
            failedPanel(Words.Receipt.changed)
        case nil:
            Panel {
                if let loadProblem {
                    Text(loadProblem).scaledFont(13).foregroundColor(Theme.no)
                    Button(Words.Common.tryAgain) { Task { await loadReceipt() } }.buttonStyle(OutlineButtonStyle())
                } else {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text(Words.Receipt.checkingOnDevice(device: "\(ThisDevice.kind)")).scaledFont(13).foregroundColor(Theme.soft)
                    }
                }
            }
        }
    }

    private func failedPanel(_ why: String) -> some View {
        Panel(stroke: Theme.no.opacity(0.5)) {
            Label(Words.Record.kiffReceiptDoesNotCheck, systemImage: "xmark.seal.fill")
                .scaledFont(14, weight: .semibold).foregroundColor(Theme.no)
            Text(why).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            receiptFacts(signer: nil)
            fullReceipt(signer: nil)
            receiptButtons(share: false)
        }
    }

    /// The signed chain names what the call acted on (its decision step's on_hash), and
    /// the line's value isn't it.
    private var onMismatch: Bool {
        guard let value = entry.onValue, let signed = steps.compactMap(\.onHash).first else { return false }
        return !KIFFMapping.isOn(value, hash: signed)
    }

    /// The signed decision step names the amount, and the line's isn't it.
    private var amountMismatch: Bool {
        guard let line = entry.amount, let signed = steps.compactMap(\.amount).first else { return false }
        return line.value != signed.value || (line.unit ?? "") != (signed.unit ?? "")
    }

    /// The signed chain has the answer (its hold_settled step), and the line's isn't it:
    /// KIFF records a refusal as "reject".
    private var answerMismatch: Bool {
        guard let line = entry.answer, let signed = steps.first(where: { $0.kind == "hold_settled" })?.answer else { return false }
        return (line.decision == "refused") != (signed == "reject")
    }

    private func receiptFacts(signer: String?) -> some View {
        let issued = receiptField("issued_at").flatMap { ISO8601DateFormatter.precise.date(from: $0) ?? ISO8601DateFormatter().date(from: $0) }
        // The decision and when; the receipt's id and signer are under "Show the full
        // receipt" (kiff/tafs#138): ids aren't for reading at a glance.
        var lines = [decisionWords]
        if let issued { lines.append(Words.Receipt.issued(at: issued.zonedShown(date: .abbreviated, time: .standard))) }
        return Text(lines.joined(separator: " · ")).scaledFont(11.5, design: .monospaced).foregroundColor(Theme.dim).textSelection(.enabled)
    }

    /// Every step KIFF signed, with its hash, in the sheet.
    @ViewBuilder private func fullReceipt(signer: String?) -> some View {
        if !steps.isEmpty {
            DisclosureGroup {
                VStack(alignment: .leading, spacing: 6) {
                    Text(["receipt \(entry.receipt?.traceID ?? "")", signer.map { "signer \($0.prefix(6))…\($0.suffix(4))" }]
                        .compactMap { $0 }.joined(separator: " · "))
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                        HStack(alignment: .top, spacing: 6) {
                            Text("\(i + 1)").frame(width: 18, alignment: .leading)
                            Text("\(step.words) · \(step.at.map { $0.zonedShown(date: .omitted, time: .standard) } ?? "")\nhash \(step.hash)")
                        }
                    }
                    if let sig = receiptAttestation("signature") {
                        HStack(alignment: .top, spacing: 6) {
                            Text("✎").frame(width: 18, alignment: .leading)
                            Text("trace \(entry.receipt?.traceID ?? "") · secp256k1 + keccak256\nsig 0x\(sig.prefix(4))…\(sig.suffix(4))")
                        }
                    }
                }
                .scaledFont(11.5, design: .monospaced).foregroundColor(Theme.dim).textSelection(.enabled)
                .padding(.top, 8)
            } label: {
                Text(Words.Receipt.showFull).scaledFont(13, weight: .semibold).foregroundColor(Theme.blue)
            }
        }
    }

    @ViewBuilder private func receiptButtons(share: Bool) -> some View {
        if let raw, let text = String(data: raw, encoding: .utf8) {
            HStack(spacing: 8) {
                Button(Words.Receipt.copy) { Clipboard.copy(text) }.buttonStyle(OutlineButtonStyle())
                if share { ShareLink(Words.Receipt.share, item: text).buttonStyle(OutlineButtonStyle()) }
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
        /// The amount the decision step signed, when it has one.
        let amount: KIFFClient.ActivityCall.Amount?
        /// A hold_settled step's answer, as KIFF records it ("reject" for a refusal).
        let answer: String?
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
            let data = s["data"] as? [String: Any]
            let amount = (data?["amount"] as? [String: Any]).flatMap { a in
                (a["value"] as? String).map { KIFFClient.ActivityCall.Amount(value: $0, unit: a["unit"] as? String) }
            }
            return Step(words: message ?? kind.replacingOccurrences(of: "_", with: " "), at: at,
                        hash: hash.count > 8 ? "\(hash.prefix(4))…\(hash.suffix(4))" : hash, kind: kind,
                        onHash: data?["on_hash"] as? String, amount: amount, answer: data?["answer"] as? String)
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
                        Text(step.words.prefix(1).uppercased() + step.words.dropFirst()).scaledFont(14).foregroundColor(Theme.ink)
                        if let at = step.at {
                            Text(at.zonedShown(date: .omitted, time: .standard)).scaledFont(12, design: .monospaced).foregroundColor(Theme.dim)
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
        if let answer = entry.answer {
            return answer.decision == "refused" ? Words.Receipt.refusedBy(who: "\(entry.answerer)") : Words.Receipt.approvedOnceBy(who: "\(entry.answerer)")
        }
        return entry.kind.label
    }

    private var outcome: String {
        switch entry.kind {
        case .done, .approved: return entry.amount == nil ? Words.Receipt.Outcome.done : entry.amountText.map { Words.Receipt.Outcome.moved(amount: $0) } ?? Words.Receipt.Outcome.done
        case .refused: return entry.answer?.decision == "refused" ? Words.Receipt.Outcome.refusedBy(who: "\(entry.answerer)") : Words.Receipt.Outcome.cardRefused
        case .held: return Words.Receipt.Outcome.waiting
        default: return entry.detail
        }
    }

    private var decisionWords: String {
        if let answer = entry.answer {
            return answer.decision == "refused" ? Words.Receipt.Decision.heldRefusedBy(who: "\(entry.answerer)") : Words.Receipt.Decision.heldApprovedOnceBy(who: "\(entry.answerer)")
        }
        switch entry.kind {
        case .refused: return Words.Receipt.Decision.refusedByCard
        // Still waiting: the Card held it, it didn't allow it (the 755 retest).
        case .held: return Words.Receipt.Decision.heldByCard
        default: return entry.ownerApproved == true ? Words.Receipt.Decision.heldApprovedByYou : Words.Receipt.Decision.allowedByCard
        }
    }

    private var decidedBy: String {
        if let answer = entry.answer {
            var how = entry.answeredByOwner ? Words.Receipt.DecidedBy.you : entry.answerer.prefix(1).uppercased() + entry.answerer.dropFirst()
            if answer.signed == true { how = Words.Receipt.DecidedBy.signedOn(who: how, device: answer.device ?? Words.Receipt.DecidedBy.yourDevice) }
            return how
        }
        switch entry.kind {
        case .approved: return Words.Receipt.DecidedBy.youApprovingOnce
        case _ where entry.ownerApproved == true: return Words.Receipt.DecidedBy.youApprovingOnce
        case .refused where entry.signature != nil: return Words.Receipt.DecidedBy.you
        case .revoked: return Words.Receipt.DecidedBy.youRevoking
        case .started: return Words.Receipt.DecidedBy.youIssuing
        default: return model.mode == .kiff ? Words.Receipt.DecidedBy.kiff : Words.Receipt.DecidedBy.card
        }
    }

    private func line(_ name: String, _ value: String, bold: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(name).scaledFont(13).foregroundColor(Theme.dim).frame(width: 90, alignment: .leading)
            Text(value).scaledFont(14, weight: bold ? .semibold : .regular).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }
}

extension ISO8601DateFormatter {
    /// RFC 3339 with fractional seconds, as Go writes times.
    nonisolated(unsafe) static let precise: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}
