import SwiftUI

/// Home with no taf, where tafs cloud lists the recipes (`home.shown`, docs/ios-app.md): its
/// line, then "Say it in your own words" where tafs cloud sent it, then a chip per category to
/// narrow the list, then each recipe led by the problem it ends (its headline) with its line
/// under it. A tap opens its sheet. Every word is tafs cloud's, in the person's language: the
/// app has none of its own here.
struct HomeRecipes: View {
    let recipes: TafsCloud.Recipes
    /// Where an app's logo loads from: only ever tafs cloud (`TafsCloud.logoURL`).
    var logo: (String?) -> URL? = { _ in nil }
    /// Coach with nothing in its box, from "Say it in your own words"; nil, the row isn't drawn.
    var ownWords: (() -> Void)? = nil
    let open: (TafsCloud.Recipes.Recipe) -> Void
    /// A category picked narrows the list; picked again, every recipe shows.
    @State private var picked: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let words = recipes.homeWords {
                Text(words).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let ownWords, let own = recipes.homeOwnWords {
                OwnWordsRow(title: own.title, line: own.line, action: ownWords)
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
        // The category's colour, tafs cloud's token, where it sent one: its words in it, and its
        // fill once picked. Without one, the chip is drawn as before.
        let tint = RecipeTint.color(category)
        return Button {
            Haptics.tap()
            picked = on ? nil : category.id
        } label: {
            Text(category.title)
                .scaledFont(13, relativeTo: .footnote)
                .foregroundColor(on ? Theme.onFill : (tint ?? Theme.soft))
                .padding(.horizontal, 12).padding(.vertical, 6)
                .frame(minHeight: 32)
                .background(on ? (tint ?? Theme.ink) : Theme.panel)
                .overlay(Capsule().stroke(on ? (tint ?? Theme.ink) : (tint ?? Theme.line), lineWidth: 1))
                .clipShape(Capsule())
        }
        .buttonStyle(.drivablePlain)
        .driveWords(category.title)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    /// One recipe: the problem it ends first, then its line.
    private func row(_ recipe: TafsCloud.Recipes.Recipe) -> some View {
        RecipeRow(recipe: recipe, category: recipes.categories.first { $0.id == recipe.category }, logo: logo) { open(recipe) }
    }
}

/// "Say it in your own words": Coach, with nothing in its box. Home's list leads with it, in
/// tafs cloud's words (`home.own_words`), and so does + New's gallery, in its own.
struct OwnWordsRow: View {
    let title: String
    let line: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Panel {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                            .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                        if !line.isEmpty {
                            Text(line).scaledFont(14).foregroundColor(Theme.soft)
                                .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                        .accessibilityHidden(true)
                }
            }
        }
        .buttonStyle(.drivablePlain)
        .driveWords(title)
        .accessibilityIdentifier("gallery.own-words")
    }
}

/// One ready-made job as a card: the problem it ends, its line, and the apps it uses, each
/// with its logo where tafs cloud has one, on an edge in its category's colour. Home's list,
/// the gallery and the strip all draw it.
struct RecipeRow: View {
    let recipe: TafsCloud.Recipes.Recipe
    let category: TafsCloud.Recipes.Category?
    var logo: (String?) -> URL? = { _ in nil }
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            Panel {
                Text(recipe.lead).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                    .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                if !recipe.line.isEmpty {
                    Text(recipe.line).scaledFont(14).foregroundColor(Theme.soft)
                        .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                }
                if !recipe.apps.isEmpty { AppMarks(apps: recipe.apps, logo: logo) }
            }
            .overlay(alignment: .leading) {
                if let tint = RecipeTint.color(category) {
                    tint.frame(width: 4).accessibilityHidden(true)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.drivablePlain)
        .driveWords(recipe.lead)
    }
}

/// The apps a job uses, by name, each after its logo when tafs cloud serves one: small and
/// as it comes. With no logo, or one that doesn't load, the name alone.
struct AppMarks: View {
    let apps: [TafsCloud.Recipes.App]
    var logo: (String?) -> URL? = { _ in nil }

    var body: some View {
        FlowRow(spacing: 8) {
            ForEach(Array(apps.enumerated()), id: \.offset) { _, app in
                AppMark(name: app.name, url: logo(app.logo))
            }
        }
    }
}

/// One app: its logo from tafs cloud, then its name.
struct AppMark: View {
    let name: String
    let url: URL?

    var body: some View {
        HStack(spacing: 6) {
            if let url {
                // Loading, or failed (tafs cloud's 404): nothing, so the name stands alone.
                AsyncImage(url: url) { phase in
                    if case .success(let image) = phase {
                        image.resizable().interpolation(.high).scaledToFit().frame(width: 18, height: 18)
                    }
                }
                .accessibilityHidden(true)
            }
            Text(name).scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
        }
    }
}

/// A category's colour, from tafs cloud's token: light or dark as the app's appearance is.
enum RecipeTint {
    static func color(_ category: TafsCloud.Recipes.Category?) -> Color? {
        guard let tint = category?.color else { return nil }
        return Color(light: tint.light, dark: tint.dark)
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
    /// The button's words where the gallery says them ("Try it with Coach" while browsing);
    /// nil for the sheet's own (`labels.hand`).
    var handWords: String? = nil
    /// The label over the job's sample, tafs cloud's; nil when it sends none.
    var sampleLabel: String? = nil
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
                // Where its proof run passed: tafs cloud's line for it, then what it reported
                // after that run, both as sent. A job with neither draws nothing here.
                if !recipe.proved.isEmpty {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Image(systemName: "checkmark.seal").scaledFont(14, weight: .semibold).foregroundColor(Theme.soft)
                            .accessibilityHidden(true)
                        Text(recipe.proved).scaledFont(14, weight: .semibold).foregroundColor(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityIdentifier("recipe.proved")
                }
                if !recipe.sample.isEmpty {
                    Panel {
                        if let sampleLabel { Eyebrow(text: sampleLabel) }
                        Text(recipe.sample).scaledFont(14).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityIdentifier("recipe.sample")
                }
                if canHand, let words = handWords ?? recipes.handWords {
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
