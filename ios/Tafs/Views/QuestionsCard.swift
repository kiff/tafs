import SwiftUI

/// Several questions an agent asks at once (kiff/tafs#138, item 57), drawn from its
/// component: every question, option and line is the agent's. The app adds two things to
/// every card: "Something else…" under each question, for the owner's own words, and
/// "Chat about this", which puts the card away so the owner talks it through instead.
struct QuestionsCard: View {
    @EnvironmentObject private var model: AppModel
    let component: SetupComponent

    @State private var picked: [String: Set<String>] = [:]
    @State private var own: [String: String] = [:]
    @State private var ownOpen: Set<String> = []
    @State private var chatting = false

    private static let ownValue = "\u{0}own"
    private var questions: [SetupComponent.Question] { component.questions ?? [] }

    var body: some View {
        if let answer = model.setupThread.answer(to: component.id) {
            OutcomeLine(text: "Your answers · " + (QuestionAnswers.words(answer.text, questions: questions) ?? answer.text), tone: Theme.ok)
        } else if chatting {
            HStack(spacing: 8) {
                Text("You're talking these questions through with Setup.").scaledFont(13).foregroundColor(Theme.soft)
                Button("Show them again") { chatting = false }.scaledFont(13, weight: .semibold).foregroundColor(Theme.coach)
            }
        } else {
            Panel {
                if let text = component.text, !text.isEmpty {
                    Text(text).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
                HStack {
                    Eyebrow(text: component.title ?? "Setup needs")
                    Spacer()
                    Text("\(answeredCount) of \(questions.count) answered").font(Theme.mono(12)).foregroundColor(Theme.dim)
                }
                ForEach(Array(questions.enumerated()), id: \.element.id) { i, q in
                    question(q, number: i + 1)
                }
                Divider()
                WrapRow(spacing: 12) {
                    Button { send() } label: { Text("Send answers") }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(answeredCount < questions.count || busy)
                    Button {
                        chatting = true
                        Haptics.tap()
                    } label: {
                        Label("Chat about this", systemImage: "bubble.left")
                            .scaledFont(15, weight: .semibold).foregroundColor(Theme.coach)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var busy: Bool { model.answered.contains(component.id) || model.setupSending }

    @ViewBuilder private func question(_ q: SetupComponent.Question, number: Int) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(number). \(q.text)").scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            WrapRow(spacing: 8) {
                ForEach(q.options, id: \.self) { o in
                    choice(o.label, detail: o.description, on: picked[q.id]?.contains(o.value) == true) { pick(q, o.value) }
                }
                choice("Something else…", detail: nil, on: ownOpen.contains(q.id)) { pick(q, Self.ownValue) }
            }
            if ownOpen.contains(q.id) {
                TextField("Your own answer", text: Binding(get: { own[q.id] ?? "" }, set: { own[q.id] = $0 }))
                    .scaledFont(15)
                    .padding(.horizontal, 14).padding(.vertical, 12)
                    .background(Theme.panel)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.coach))
                    .accessibilityLabel("Your own answer to: \(q.text)")
            }
            if let note = q.note, !note.isEmpty {
                Text(note).scaledFont(13).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func choice(_ label: String, detail: String?, on: Bool, _ action: @escaping () -> Void) -> some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).scaledFont(15, weight: on ? .semibold : .regular)
                if let detail, !detail.isEmpty { Text(detail).scaledFont(12).opacity(0.8) }
            }
            .foregroundColor(on ? .white : Theme.ink)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .frame(minHeight: 44)
            .background(on ? Theme.coach : Theme.panel)
            .overlay(Capsule().stroke(on ? Theme.coach : Theme.ink.opacity(0.18)))
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func pick(_ q: SetupComponent.Question, _ value: String) {
        if value == Self.ownValue {
            if ownOpen.contains(q.id) { ownOpen.remove(q.id) } else { ownOpen.insert(q.id) }
            if q.multi != true { picked[q.id] = [] }
            return
        }
        if q.multi == true {
            var set = picked[q.id] ?? []
            if set.contains(value) { set.remove(value) } else { set.insert(value) }
            picked[q.id] = set
        } else {
            picked[q.id] = [value]
            ownOpen.remove(q.id)
        }
    }

    /// The question's answer, if it has one: the owner's own words first, when they wrote some.
    private func answer(_ q: SetupComponent.Question) -> QuestionAnswers.Answer? {
        let words = (own[q.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if ownOpen.contains(q.id), !words.isEmpty { return .own(words) }
        let chosen = q.options.map(\.value).filter { picked[q.id]?.contains($0) == true }
        if chosen.isEmpty { return nil }
        return q.multi == true ? .choices(chosen) : .choice(chosen[0])
    }

    private var answeredCount: Int { questions.filter { answer($0) != nil }.count }

    private func send() {
        var answers: [String: QuestionAnswers.Answer] = [:]
        for q in questions { if let a = answer(q) { answers[q.id] = a } }
        guard answers.count == questions.count else { return }
        Task { await model.answer(component, QuestionAnswers.encode(answers)) }
    }
}
