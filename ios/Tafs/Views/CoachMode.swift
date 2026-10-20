import SwiftUI

/// Coach mode (kiff/tafs#138): where tafs are set up, connected and changed, over whatever
/// is on screen. Its conversation is Setup's; closing it never ends one, and ✦ reopens it.

/// The floating ✦ on every screen: "✦ Coach mode" on a wide screen, "✦ Coach" on a phone.
struct CoachButton: View {
    @EnvironmentObject private var model: AppModel
    let wide: Bool

    /// Where it sits: this far above the screen's safe-area foot, this tall. The scrolling
    /// screens keep their last line clear of it from these (coachClearance, review on #153).
    static let bottomOffset: CGFloat = 112
    static let height: CGFloat = 44
    /// Room at the foot of a scroll view, so its last line stops above the button with a gap.
    /// A phone's tab bar is at the foot, inside the scroll view's own inset; an iPad's is at
    /// the top.
    static func clearance(wide: Bool) -> CGFloat {
        bottomOffset + height + 12 - (wide ? 0 : 49)
    }

    var body: some View {
        Button { model.openCoach() } label: {
            Label(wide ? "Coach mode" : "Coach", systemImage: "sparkle")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 16)
                .frame(height: Self.height)
                .background(Theme.coach)
                .clipShape(Capsule())
                .shadow(color: Theme.coach.opacity(0.35), radius: 10, y: 4)
        }
        .accessibilityLabel("Coach mode")
    }
}

/// The conversation, with Close on its title row: in the iPad's panel and the iPhone's sheet.
/// In the panel, ⤢ makes it full screen and brings it back to the width it had.
struct CoachView: View {
    @EnvironmentObject private var model: AppModel
    var fullScreen: Binding<Bool>? = nil

    var body: some View {
        NavigationStack {
            SetupChatView()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { model.coachOpen = false }
                    }
                    if let fullScreen {
                        ToolbarItem(placement: .navigationBarLeading) {
                            Button { fullScreen.wrappedValue.toggle() } label: {
                                Image(systemName: fullScreen.wrappedValue ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                            }
                            .accessibilityLabel(fullScreen.wrappedValue ? "Back to its width" : "Full screen")
                        }
                    }
                }
        }
        .tint(Theme.coach)
    }
}

/// On a wide screen, Coach mode slides in from the right, next to what's open, which stays
/// in view. It opens at a third of the screen, or the width the owner last chose: its left
/// edge drags it wider, up to full screen, and back, never narrower than a third, the width
/// it opens at. Dragged past that, it closes, as a swipe of the conversation to the right,
/// a tap outside it, or its Close do.
struct CoachPanel: View {
    @EnvironmentObject private var model: AppModel
    /// The width the owner chose, as a share of the screen: kept for the next time.
    @AppStorage("coach.width") private var share = 1.0 / 3
    @AppStorage("coach.full") private var full = false
    /// How far the edge is being dragged, in points, while it is.
    @State private var drag: CGFloat = 0

    private static let narrowest = 1.0 / 3

    var body: some View {
        GeometryReader { g in
            let screen = g.size.width
            let chosen = full ? screen : screen * share
            let width = min(screen, max(0, chosen - drag))
            ZStack(alignment: .trailing) {
                if model.coachOpen {
                    Color.black.opacity(full ? 0 : 0.12)
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture { model.coachOpen = false }
                        .allowsHitTesting(!full)
                        .transition(.opacity)
                        .accessibilityLabel("Close Coach mode")
                        .accessibilityAddTraits(.isButton)
                    HStack(spacing: 0) {
                        handle(screen: screen)
                        CoachView(fullScreen: $full)
                            // A swipe of the conversation to the right closes it; a scroll is
                            // left alone. Not on the handle: a drag there only resizes, and
                            // closes only past the narrowest (review on #148).
                            .simultaneousGesture(DragGesture(minimumDistance: 30).onEnded { v in
                                if v.translation.width > 140, abs(v.translation.height) < 60 { model.coachOpen = false }
                            })
                    }
                    .frame(width: width)
                    .background(Theme.background)
                    .clipShape(RoundedRectangle(cornerRadius: full ? 0 : 18, style: .continuous))
                    .shadow(color: .black.opacity(0.18), radius: 24, x: -4)
                    .padding(.vertical, full ? 0 : 8).padding(.trailing, full ? 0 : 8)
                    .transition(.move(edge: .trailing))
                }
            }
            .animation(.easeOut(duration: 0.22), value: model.coachOpen)
            .animation(.easeOut(duration: 0.22), value: full)
        }
    }

    /// The left edge's handle: drag it left to widen the panel, right to narrow it; past
    /// its narrowest, it closes.
    private func handle(screen: CGFloat) -> some View {
        ZStack {
            Color.clear
            Capsule().fill(Theme.dim.opacity(0.5)).frame(width: 5, height: 44)
        }
        .frame(width: 16)
        .frame(maxHeight: .infinity)
        .contentShape(Rectangle())
        .gesture(DragGesture()
            .onChanged { v in drag = v.translation.width }
            .onEnded { v in
                let chosen = (full ? screen : screen * share) - v.translation.width
                drag = 0
                if chosen < screen * Self.narrowest - 60 {
                    model.coachOpen = false
                    return
                }
                full = chosen >= screen - 40
                if !full { share = min(1, max(Self.narrowest, chosen / screen)) }
            })
        .accessibilityLabel("Coach mode's width")
        .accessibilityHint("Drag to make it wider or narrower")
    }
}
