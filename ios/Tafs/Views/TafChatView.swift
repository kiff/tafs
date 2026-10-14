import SwiftUI

/// A taf's thread: what it reports and asks, and what the owner tells it. A message
/// steers the taf within its job; it can't widen its Card, and KIFF still checks
/// every action it takes.
struct TafChatView: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var draft = ""
    @FocusState private var typing: Bool

    var body: some View {
        let taf = model.taf(tafID)
        let messages = model.chats[tafID] ?? []
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(model.heldCalls(for: tafID)) { HeldCallCard(call: $0) }
                        if messages.isEmpty {
                            Text("Ask \(taf?.name ?? "it") what it did, or tell it what to do differently. It stays inside its Card.")
                                .font(.system(size: 14)).foregroundColor(Theme.dim)
                                .frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 30)
                        }
                        ForEach(messages) { message in
                            ChatBubble(message: message, taf: taf).id(message.id)
                        }
                        if model.awaitingReply(tafID) {
                            TypingBubble(taf: taf).id("typing")
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: messages.count) { _ in scrollToEnd(proxy) }
                .onAppear { scrollToEnd(proxy) }
            }
            if let error = model.chatErrors[tafID] {
                Text(error).font(.system(size: 13)).foregroundColor(Theme.no)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 4)
            }
            composer
        }
        .task(id: tafID) { await model.followChat(tafID) }
    }

    private var composer: some View {
        let sending = model.chatSending.contains(tafID)
        let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return HStack(alignment: .bottom, spacing: 10) {
            TextField("Message \(model.taf(tafID)?.name ?? "your taf")", text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .focused($typing)
                .font(.system(size: 16))
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            Button {
                let text = draft
                draft = ""
                Task { if !(await model.sendChat(text, to: tafID)) { if draft.isEmpty { draft = text } } }
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 40, height: 40)
                    .background(Theme.brand)
                    .clipShape(Circle())
                    .opacity(empty || sending ? 0.4 : 1)
            }
            .disabled(empty || sending)
            .accessibilityLabel("Send")
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Theme.background.shadow(.drop(color: Theme.ink.opacity(0.06), radius: 6, y: -2)))
    }

    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        let last = model.awaitingReply(tafID) ? "typing" : model.chats[tafID]?.last?.id
        if let last { withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last, anchor: .bottom) } }
    }
}

/// One message. The taf's on the left with its avatar; the owner's on the right in the
/// mascot's colours. A run's kickoff shows as a small note, not a bubble.
struct ChatBubble: View {
    let message: TafsCloud.ChatMessage
    let taf: Taf?

    var body: some View {
        if message.kind == .kickoff {
            Text("Run started \(message.at.shortTime) · \(message.text)")
                .font(.system(size: 12)).foregroundColor(Theme.dim).lineLimit(2)
                .frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.vertical, 4)
        } else if message.from == .owner {
            HStack {
                Spacer(minLength: 48)
                Text(message.text)
                    .font(.system(size: 15)).foregroundColor(.white)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Theme.brand)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .opacity(message.session == "pending" ? 0.7 : 1)
                    .textSelection(.enabled)
            }
        } else {
            HStack(alignment: .bottom, spacing: 8) {
                if let taf { TafAvatar(taf: taf, size: 28) } else { Mascot(size: 28) }
                Text(message.text)
                    .font(.system(size: 15)).foregroundColor(Theme.ink)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Theme.panel)
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line))
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .textSelection(.enabled)
                Spacer(minLength: 48)
            }
        }
    }
}

/// The taf is working on an answer.
struct TypingBubble: View {
    let taf: Taf?

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if let taf { TafAvatar(taf: taf, size: 28) } else { Mascot(size: 28) }
            TimelineView(.animation) { context in
                let phase = context.date.timeIntervalSinceReferenceDate * 4
                HStack(spacing: 5) {
                    ForEach(0..<3, id: \.self) { i in
                        Circle().fill(Theme.dim).frame(width: 7, height: 7)
                            .opacity(0.35 + 0.65 * max(0, sin(phase - Double(i) * 0.9)))
                    }
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 14)
            .background(Theme.panel)
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line))
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            Spacer()
        }
        .accessibilityLabel("\(taf?.name ?? "The taf") is answering")
    }
}
