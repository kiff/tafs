import SwiftUI

/// Home with no taf, where tafs cloud lists the recipes (`home.shown`, docs/ios-app.md): its
/// line, then a chip per category to narrow the list, then each recipe led by the problem it
/// ends (its headline) with its line under it. A tap opens its sheet. Every word is tafs
/// cloud's, in the person's language: the app has none of its own here.
struct HomeRecipes: View {
    let recipes: TafsCloud.Recipes
    let open: (TafsCloud.Recipes.Recipe) -> Void
    /// A category picked narrows the list; picked again, every recipe shows.
    @State private var picked: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let words = recipes.homeWords {
                Text(words).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
            let all = recipes.byCategory
            if all.count > 1 {
                FlowRow(spacing: 8) {
                    ForEach(all, id: \.category.id) { group in
                        chip(group.category)
                    }
                }
            }
            ForEach(recipes.groups(picked: picked), id: \.category.id) { group in
                Eyebrow(text: group.category.title)
                ForEach(group.recipes) { recipe in row(recipe) }
            }
        }
    }

    private func chip(_ category: TafsCloud.Recipes.Category) -> some View {
        let on = picked == category.id
        return Button {
            Haptics.tap()
            picked = on ? nil : category.id
        } label: {
            Text(category.title)
                .scaledFont(13, relativeTo: .footnote)
                .foregroundColor(on ? Theme.background : Theme.soft)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .frame(minHeight: 32)
                .background(on ? Theme.ink : Theme.panel)
                .overlay(Capsule().stroke(on ? Theme.ink : Theme.line, lineWidth: 1))
                .clipShape(Capsule())
        }
        .buttonStyle(.drivablePlain)
        .driveWords(category.title)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    /// One recipe: the problem it ends first, then its line.
    private func row(_ recipe: TafsCloud.Recipes.Recipe) -> some View {
        Button { open(recipe) } label: {
            Panel {
                Text(recipe.lead).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                    .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                if !recipe.line.isEmpty {
                    Text(recipe.line).scaledFont(14).foregroundColor(Theme.soft)
                        .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .buttonStyle(.drivablePlain)
        .driveWords(recipe.lead)
    }
}

/// A recipe's sheet: what it ends and what that costs today, then its Card in plain zones
/// (what it does on its own, asks first, never does), a zone its Card leaves empty left out,
/// and one button that hands it to Coach with the recipe's words, to edit and send.
struct RecipeSheet: View {
    let recipe: TafsCloud.Recipes.Recipe
    let recipes: TafsCloud.Recipes
    /// Whether this person may use Coach: without it, the sheet has no button.
    let canHand: Bool
    let hand: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(recipe.lead)
                    .scaledFont(24, weight: .bold, design: .rounded, relativeTo: .title2).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if !recipe.line.isEmpty {
                    Text(recipe.line).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                }
                if !recipe.today.isEmpty {
                    Text(recipe.today).scaledFont(14).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                }
                ForEach(recipes.zones(of: recipe)) { zone in
                    Panel {
                        if !zone.title.isEmpty { Eyebrow(text: zone.title) }
                        ForEach(Array(zone.lines.enumerated()), id: \.offset) { _, line in
                            Text(line).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                if canHand, let words = recipes.handWords {
                    Button(words, action: hand).buttonStyle(PrimaryButtonStyle())
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 16)
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .screenBackground()
        .presentationDetents([.large]).presentationDragIndicator(.visible)
    }
}
