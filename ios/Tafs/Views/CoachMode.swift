import SwiftUI

/// Coach mode (kiff/tafs#138): where tafs are set up, connected and changed, over whatever
/// is on screen. Its conversation is Setup's; closing it never ends one, and the bar's Coach
/// reopens it.

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
                        Button { model.coachOpen = false } label: { Label("Back", systemImage: "chevron.left") }.shortcut(.close)
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
    /// With Reduce Motion on, it fades in and out rather than slide (kiff/tafs#210).
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
                    // The full height, from the top edge to the bottom one, past the safe
                    // area: nothing of the screen behind shows under it (kiff/tafs#206 6.10).
                    .background(Theme.background.ignoresSafeArea())
                    .shadow(color: .black.opacity(0.18), radius: 24, x: -4)
                    .transition(reduceMotion ? .opacity : .move(edge: .trailing))
                }
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: model.coachOpen)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: full)
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

/// Slides back to the exact screen beneath it, including that taf's selected tab.
struct PhoneCoachPanel: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        ZStack {
            if model.coachOpen {
                CoachView().background(Theme.background.ignoresSafeArea())
                    .transition(reduceMotion ? .opacity : .move(edge: .trailing))
                    .simultaneousGesture(DragGesture(minimumDistance: 40).onEnded { value in
                        if value.startLocation.x < 24 && value.translation.width > 100 && abs(value.translation.height) < 60 { model.coachOpen = false }
                    })
            }
        }.animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: model.coachOpen)
    }
}
