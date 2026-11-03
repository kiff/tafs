import SwiftUI

/// The app's bar (the owner's taf layout): Account · Tafs · Needs you · Record, and Coach at
/// the end, a filled button that opens Coach mode rather than a tab, only for those who may
/// use it. Along the foot on a phone; a rail down the left on a wide screen, Coach at its
/// bottom.
struct TafsBar: View {
    @EnvironmentObject private var model: AppModel
    @Binding var tab: Tab
    let coachAvailable: Bool
    let rail: Bool

    struct Item {
        let tab: Tab
        let title: String
        let icon: String
        /// Its icon while it is the open tab.
        let selectedIcon: String
    }

    static let items = [
        Item(tab: .account, title: "Account", icon: "person.crop.circle", selectedIcon: "person.crop.circle.fill"),
        Item(tab: .tafs, title: "Tafs", icon: "square.stack", selectedIcon: "square.stack.fill"),
        Item(tab: .needsYou, title: "Needs you", icon: "bell", selectedIcon: "bell.fill"),
        Item(tab: .record, title: "Record", icon: "list.bullet", selectedIcon: "list.bullet"),
    ]

    var body: some View {
        if rail {
            VStack(spacing: 6) {
                ForEach(Self.items, id: \.tab) { item in itemButton(item) }
                Spacer(minLength: 12)
                if coachAvailable { coachButton }
            }
            .padding(.vertical, 12).padding(.horizontal, 6)
            .frame(width: 84)
            .frame(maxHeight: .infinity)
            .background(Theme.panel.ignoresSafeArea())
            .overlay(alignment: .trailing) { Rectangle().fill(Theme.line).frame(width: 1).ignoresSafeArea() }
        } else {
            HStack(spacing: 0) {
                ForEach(Self.items, id: \.tab) { item in itemButton(item).frame(maxWidth: .infinity) }
                if coachAvailable { coachButton.padding(.leading, 4) }
            }
            .padding(.horizontal, 8).padding(.top, 6).padding(.bottom, 4)
            .background(Theme.panel.ignoresSafeArea(edges: .bottom))
            .overlay(alignment: .top) { Rectangle().fill(Theme.line).frame(height: 1) }
        }
    }

    private func itemButton(_ item: Item) -> some View {
        let selected = tab == item.tab
        let count = item.tab == .needsYou ? model.held.count : 0
        return Button { tab = item.tab } label: {
            VStack(spacing: 3) {
                Image(systemName: selected ? item.selectedIcon : item.icon)
                    .scaledFont(19, weight: selected ? .semibold : .regular)
                    .frame(height: 24)
                    .overlay(alignment: .topTrailing) {
                        if count > 0 {
                            Text("\(count)").scaledFont(11, weight: .bold).foregroundColor(Theme.onFill)
                                .padding(.horizontal, 5).frame(minWidth: 17, minHeight: 17)
                                .background(Theme.no).clipShape(Capsule())
                                .offset(x: 10, y: -6)
                        }
                    }
                Text(item.title).scaledFont(11, weight: selected ? .semibold : .regular).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundColor(selected ? Theme.amberText : Theme.soft)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label(item, count: count))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("bar." + item.title)
    }

    /// The account this is stays in reach (the 870 test): VoiceOver reads it with Account.
    private func label(_ item: Item, count: Int) -> String {
        switch item.tab {
        case .account: return model.accountLabel.map { "Account, \($0)" } ?? "Account"
        case .needsYou where count > 0: return "Needs you, \(count)"
        default: return item.title
        }
    }

    /// Coach: ink fill, the background's colour for its words, in both themes.
    private var coachButton: some View {
        Button { model.openCoach() } label: {
            Group {
                if rail {
                    VStack(spacing: 3) {
                        Image(systemName: "sparkle").scaledFont(18, weight: .semibold)
                        Text("Coach").scaledFont(12, weight: .semibold)
                    }
                    .frame(maxWidth: .infinity, minHeight: 56)
                } else {
                    Label("Coach", systemImage: "sparkle").scaledFont(15, weight: .semibold)
                        .padding(.horizontal, 14).frame(minHeight: 44)
                }
            }
            .foregroundColor(Theme.onFill)
            .background(Theme.ink)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Coach mode")
        .accessibilityIdentifier("bar.Coach")
    }
}
