import SwiftUI

/// The approved first-time and empty-state drawings, one per board (docs/ios-app.md › Illustrations).
/// Rule 14: shapes only, never a letter, a number or a word, so a drawing reads the same in every language;
/// the screen's own words say what it means. Each is drawn in code on its board's grid, in the theme's colours
/// so light and dark both read, and scaled down to the width it's given: none sets its screen's size. Every
/// one is hidden from VoiceOver. The sign-in's drawings (`SignInArt`) are the house style these follow.
@MainActor
enum Illustration {
    /// Board 1, first run: a card opening, sparkles round it. The welcome (`OnboardingView`).
    static var firstRun: some View {
        Board(width: 380, height: 335, shown: 200) {
            halo.placed(40, 10, 300, 300)
            card(padding: 24) {
                mark(52)
                bar(120, 0.85).padding(.top, 22)
                bar(90, 0.18).padding(.top, 12)
                bar(140, 0.18).padding(.top, 12)
                Capsule().fill(Theme.amber).frame(height: 40).padding(.top, 40)
            }
            .placed(90, 40, 200, 250)
            dot(Theme.amberText, 0.6).placed(10, 20, 18, 18)
            chip(Theme.blue, 0.55, degrees: 20).placed(350, 60, 16, 16)
            chip(Theme.pink, 0.5, degrees: -15).placed(30, 310, 14, 14)
            dot(Theme.amberText, 0.35).placed(330, 320, 20, 20)
        }
    }

    /// Board 2, no tafs yet: an empty card's outline, a plus in it. Home with no taf (`HomeView`, `HomeSplitView`).
    static var noTafs: some View {
        Board(width: 360, height: 320, shown: 200) {
            halo.placed(30, 10, 300, 300)
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Theme.ink.opacity(0.35), style: StrokeStyle(lineWidth: 4, dash: [10, 7]))
                .placed(70, 60, 228, 208)
            Circle().fill(Theme.amber).shadow(color: Theme.amberText.opacity(0.35), radius: 13, y: 10).placed(148, 128, 64, 64)
            Capsule().fill(Theme.amberInk).placed(177, 142, 6, 36)
            Capsule().fill(Theme.amberInk).placed(162, 157, 36, 6)
            sheet(14).opacity(0.6).placed(10, 280, 120, 28)
            sheet(14).opacity(0.6).placed(230, 280, 120, 28)
        }
    }

    /// Board 3, a brief with nothing to decide: a calm sun over a clear page. Your brief (`BriefView`).
    static var briefClear: some View {
        Board(width: 320, height: 430, shown: 130) {
            dot(Theme.amber, 0.9).placed(100, 40, 120, 120)
            halo.placed(70, 10, 180, 180)
            card(padding: 28) {
                bar(140, 0.85)
                HStack(spacing: 14) { dot(Theme.ok).frame(width: 34, height: 34); bar(150, 0.15) }.padding(.top, 28)
                HStack(spacing: 14) { dot(Theme.ok).frame(width: 34, height: 34); bar(120, 0.15) }.padding(.top, 18)
            }
            .placed(10, 200, 300, 220)
        }
    }

    /// Board 4, every taf halted: cards at rest under a pause. Halt all, once halted (`HaltScreen`).
    static var allHalted: some View {
        Board(width: 330, height: 320, shown: 190) {
            Circle().fill(Theme.ink.opacity(0.06)).placed(15, 10, 300, 300)
            sheet(22).opacity(0.75).rotationEffect(.degrees(-6)).placed(15, 90, 150, 190)
            sheet(22).opacity(0.75).rotationEffect(.degrees(5)).placed(165, 80, 150, 190)
            card(padding: 20) {
                mark(40).grayscale(0.7)
                bar(90, 0.3).padding(.top, 18)
                bar(70, 0.15).padding(.top, 10)
            }
            .placed(90, 65, 150, 200)
            Circle().fill(Theme.ink).shadow(color: .black.opacity(0.3), radius: 14, y: 12).placed(205, 30, 76, 76)
            HStack(spacing: 8) { pause; pause }.placed(229, 50, 28, 36)
        }
    }

    /// Board 5, an app connected: two tiles joined, a check between them. A key's receipt (`ConnectApprovalSheet`).
    static var appConnected: some View {
        Board(width: 490, height: 215, shown: 280) {
            ZStack { sheet(22); mark(90, radius: 24) }.placed(15, 15, 180, 180)
            ZStack {
                sheet(22)
                RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Theme.ink, lineWidth: 3)
                dot(Theme.blue, 0.85).frame(width: 80, height: 80)
            }
            .placed(295, 15, 180, 180)
            Rule().stroke(Theme.amber, style: StrokeStyle(lineWidth: 5, dash: [12, 8])).placed(195, 101, 100, 5)
            Circle().fill(Theme.amberText).shadow(color: Theme.amber.opacity(0.35), radius: 15, y: 12).placed(213, 73, 64, 64)
            Tick().stroke(Theme.onFill, style: tick(4)).placed(229, 89, 32, 32)
        }
    }

    /// Board 6, nothing waiting: an empty tray under a calm check. Needs you, empty (`NeedsYouView`).
    static var nothingWaiting: some View {
        Board(width: 380, height: 325, shown: 200) {
            halo.placed(35, 10, 300, 300)
            sheet(26).placed(55, 160, 260, 150)
            Circle().fill(Theme.ok).shadow(color: Theme.ok.opacity(0.35), radius: 14, y: 12).placed(145, 60, 80, 80)
            Tick().stroke(Theme.onFill, style: tick(5)).placed(165, 80, 40, 40)
            bar(140, 0.12).placed(115, 240, 140, 10)
            bar(90, 0.08).placed(140, 262, 90, 10)
            dot(Theme.amberText, 0.4).placed(345, 90, 16, 16)
            dot(Theme.blue, 0.35).placed(13, 210, 12, 12)
            dot(Theme.pink, 0.35).placed(360, 260, 10, 10)
        }
    }

    /// Board 7, Coach with no taf or one: its spark beside one card, room for more. Coach's empty chat (`SetupChatView`).
    static var coachFirst: some View {
        Board(width: 610, height: 280, shown: 300) {
            halo.placed(10, 10, 260, 260)
            Spark().fill(Theme.brand).placed(70, 70, 140, 140)
            Spark().fill(Theme.amber.opacity(0.6)).placed(215, 40, 40, 40)
            card(padding: 20) {
                mark(40)
                bar(90, 0.85).padding(.top, 18)
                bar(70, 0.18).padding(.top, 10)
                Capsule().fill(Theme.amber.opacity(0.25)).frame(height: 30).padding(.top, 30)
            }
            .placed(310, 80, 150, 180)
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(Theme.ink.opacity(0.22), style: StrokeStyle(lineWidth: 4, dash: [10, 7]))
                .placed(480, 95, 118, 158)
            Rule().stroke(Theme.amber.opacity(0.7), style: dotted(5)).placed(250, 168, 50, 5)
        }
    }

    /// Board 8, a new taf, small: words in a bubble becoming a card. Beside Say it in your own words (`RecipeGallery`).
    static var newTaf: some View {
        Board(width: 320, height: 235, shown: 100) {
            halo.placed(60, 5, 220, 220)
            ZStack(alignment: .topLeading) {
                Bubble().fill(Theme.panel).shadow(color: .black.opacity(0.14), radius: 17, y: 14)
                VStack(alignment: .leading, spacing: 10) { bar(100, 0.7); bar(70, 0.2); bar(86, 0.2) }.padding(20)
            }
            .placed(10, 55, 150, 96)
            Rule().stroke(Theme.amber, style: dotted(4)).placed(168, 100, 36, 4)
            card(padding: 14) {
                mark(28, radius: 9)
                bar(52, 0.8, height: 8).padding(.top, 12)
                bar(40, 0.18, height: 8).padding(.top, 8)
            }
            .placed(210, 55, 90, 116)
            Spark().fill(Theme.amber).placed(280, 37, 34, 34)
        }
    }

    /// Board 9, iPad: Coach starting for the first time, its spark in rings filling. Coach's start (`SetupChatView`).
    static var coachStarting: some View {
        Board(width: 600, height: 490, shown: 300) {
            ZStack {
                Circle().stroke(Theme.ink.opacity(0.07), lineWidth: 10).frame(width: 280, height: 280)
                Circle().trim(from: 0, to: 0.625).stroke(Theme.amber, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                    .rotationEffect(.degrees(-90)).frame(width: 280, height: 280)
                Circle().stroke(Theme.ink.opacity(0.06), lineWidth: 8).frame(width: 216, height: 216)
                Circle().trim(from: 0, to: 0.38).stroke(Theme.blue.opacity(0.7), style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90)).frame(width: 216, height: 216)
            }
            .placed(150, 10, 300, 300)
            Spark().fill(Theme.brand).placed(240, 100, 120, 120)
            step(Theme.ok, 0.15).opacity(0.9).placed(20, 360, 170, 110)
            step(Theme.amber, 0.15).placed(215, 360, 170, 110)
            step(Theme.ink.opacity(0.15), 0.1).opacity(0.55).placed(410, 360, 170, 110)
            dot(Theme.pink, 0.4).placed(100, 70, 14, 14)
            dot(Theme.amberText, 0.4).placed(490, 50, 18, 18)
        }
    }

    /// Board 10, iPad: the overview, a ring, a gentle line and cards in a row. The Overview's header (`OverviewView`).
    static var overview: some View {
        Board(width: 860, height: 480, shown: 340) {
            card(padding: 28) {
                ZStack {
                    Circle().stroke(Theme.ink.opacity(0.07), lineWidth: 16)
                    Circle().trim(from: 0, to: 0.68).stroke(Theme.brand, style: StrokeStyle(lineWidth: 16, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                .frame(width: 120, height: 120)
                .frame(width: 150, height: 150)
                .padding(.top, 6)
                .frame(maxWidth: .infinity)
                bar(140, 0.15).padding(.top, 22).frame(maxWidth: .infinity)
            }
            .placed(20, 20, 360, 260)
            card(padding: 28) {
                bar(120, 0.8)
                ZStack(alignment: .topLeading) {
                    Rise(closed: true).fill(Theme.amber.opacity(0.12))
                    Rise().stroke(Theme.amber, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                    Circle().fill(Theme.amberText).frame(width: 20, height: 20).position(x: 250, y: 60)
                }
                .frame(width: 374, height: 160)
                .padding(.top, 24)
            }
            .placed(410, 20, 430, 260)
            tile(mark(38), 140, 100).placed(20, 310, 260, 150)
            tile(dot(Theme.ok), 120, 150).placed(300, 310, 260, 150)
            tile(dot(Theme.blue, 0.85), 130, 90).placed(580, 310, 260, 150)
        }
    }

    // MARK: The boards' pieces

    /// The soft amber glow behind a drawing.
    private static var halo: some View { Circle().fill(Theme.amber.opacity(0.16)) }

    private static func dot(_ color: Color, _ opacity: Double = 1) -> some View { Circle().fill(color.opacity(opacity)) }

    /// A small tilted square, a sparkle.
    private static func chip(_ color: Color, _ opacity: Double, degrees: Double) -> some View {
        RoundedRectangle(cornerRadius: 4, style: .continuous).fill(color.opacity(opacity)).rotationEffect(.degrees(degrees))
    }

    /// A line of a card, as a bar: never its words.
    private static func bar(_ width: CGFloat, _ opacity: Double, height: CGFloat = 10) -> some View {
        Capsule().fill(Theme.ink.opacity(opacity)).frame(width: width, height: height)
    }

    /// The app's mark: the brand's sweep, as the sign-in draws it.
    private static func mark(_ size: CGFloat, radius: CGFloat = 12) -> some View {
        RoundedRectangle(cornerRadius: radius, style: .continuous).fill(Theme.brand).frame(width: size, height: size)
    }

    /// A card's ground, lifted off the screen.
    private static func sheet(_ radius: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: radius, style: .continuous).fill(Theme.panel)
            .shadow(color: .black.opacity(0.14), radius: 17, y: 14)
    }

    /// A card, its pieces stacked from its top left.
    private static func card<Content: View>(radius: CGFloat = 22, padding: CGFloat, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .padding(padding)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(sheet(radius))
    }

    /// One of Coach's first steps, under its rings.
    private static func step(_ color: Color, _ line: Double) -> some View {
        card(padding: 18) {
            dot(color).frame(width: 30, height: 30)
            bar(110, line).padding(.top, 16)
        }
    }

    /// One of the overview's cards: a badge and two lines.
    private static func tile<Badge: View>(_ badge: Badge, _ first: CGFloat, _ second: CGFloat) -> some View {
        card(padding: 22) {
            badge.frame(width: 38, height: 38)
            bar(first, 0.75).padding(.top, 20)
            bar(second, 0.15).padding(.top, 10)
        }
    }

    private static var pause: some View {
        RoundedRectangle(cornerRadius: 3, style: .continuous).fill(Theme.onFill).frame(width: 10, height: 36)
    }

    private static func tick(_ width: CGFloat) -> StrokeStyle { StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round) }

    private static func dotted(_ width: CGFloat) -> StrokeStyle { StrokeStyle(lineWidth: width, lineCap: .round, dash: [0, width * 1.8]) }
}

/// A drawing on its board's grid (`width` by `height` points), scaled to fit the room it's given and never
/// wider than `shown` points, so a short screen gets a smaller drawing and never a scroll.
private struct Board<Content: View>: View {
    let width: CGFloat
    let height: CGFloat
    let shown: CGFloat
    let content: Content

    init(width: CGFloat, height: CGFloat, shown: CGFloat, @ViewBuilder content: () -> Content) {
        self.width = width
        self.height = height
        self.shown = shown
        self.content = content()
    }

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .topLeading) { content }
                .frame(width: width, height: height)
                .scaleEffect(max(0.01, min(g.size.width / width, g.size.height / height)))
                .frame(width: g.size.width, height: g.size.height)
        }
        .aspectRatio(width / height, contentMode: .fit)
        .frame(maxWidth: shown)
        .accessibilityHidden(true)
    }
}

private extension View {
    /// Where its board puts it: left, top, width and height, in the board's points.
    func placed(_ x: CGFloat, _ y: CGFloat, _ width: CGFloat, _ height: CGFloat) -> some View {
        frame(width: width, height: height).position(x: x + width / 2, y: y + height / 2)
    }
}

/// Coach's spark: four curved points, on a 100 by 100 grid.
private struct Spark: Shape {
    func path(in rect: CGRect) -> Path {
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * rect.width / 100, y: rect.minY + y * rect.height / 100) }
        var path = Path()
        path.move(to: p(50, 0))
        path.addCurve(to: p(100, 50), control1: p(54, 34), control2: p(66, 46))
        path.addCurve(to: p(50, 100), control1: p(66, 54), control2: p(54, 66))
        path.addCurve(to: p(0, 50), control1: p(46, 66), control2: p(34, 54))
        path.addCurve(to: p(50, 0), control1: p(34, 46), control2: p(46, 34))
        path.closeSubpath()
        return path
    }
}

/// A check, drawn: on a 24 by 24 grid, as the boards draw it.
private struct Tick: Shape {
    func path(in rect: CGRect) -> Path {
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * rect.width / 24, y: rect.minY + y * rect.height / 24) }
        var path = Path()
        path.move(to: p(5, 12.5))
        path.addLine(to: p(9.5, 17))
        path.addLine(to: p(19, 7.5))
        return path
    }
}

/// A straight line across the middle of its frame, dashed or dotted by its stroke.
private struct Rule: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.midY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        return path
    }
}

/// A speech bubble: round corners, its bottom left one nearly square.
private struct Bubble: Shape {
    func path(in rect: CGRect) -> Path {
        let round: CGFloat = 24, tail: CGFloat = 6
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + round, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - round, y: rect.minY))
        path.addArc(tangent1End: CGPoint(x: rect.maxX, y: rect.minY), tangent2End: CGPoint(x: rect.maxX, y: rect.minY + round), radius: round)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - round))
        path.addArc(tangent1End: CGPoint(x: rect.maxX, y: rect.maxY), tangent2End: CGPoint(x: rect.maxX - round, y: rect.maxY), radius: round)
        path.addLine(to: CGPoint(x: rect.minX + tail, y: rect.maxY))
        path.addArc(tangent1End: CGPoint(x: rect.minX, y: rect.maxY), tangent2End: CGPoint(x: rect.minX, y: rect.maxY - tail), radius: tail)
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + round))
        path.addArc(tangent1End: CGPoint(x: rect.minX, y: rect.minY), tangent2End: CGPoint(x: rect.minX + round, y: rect.minY), radius: round)
        path.closeSubpath()
        return path
    }
}

/// The overview's gentle rise, on a 374 by 160 grid; closed, the area under it.
private struct Rise: Shape {
    var closed = false

    func path(in rect: CGRect) -> Path {
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * rect.width / 374, y: rect.minY + y * rect.height / 160) }
        var path = Path()
        path.move(to: p(0, 130))
        path.addCurve(to: p(120, 92), control1: p(50, 120), control2: p(70, 80))
        path.addCurve(to: p(250, 60), control1: p(170, 104), control2: p(200, 40))
        path.addCurve(to: p(374, 26), control1: p(300, 80), control2: p(330, 20))
        if closed {
            path.addLine(to: p(374, 160))
            path.addLine(to: p(0, 160))
            path.closeSubpath()
        }
        return path
    }
}
