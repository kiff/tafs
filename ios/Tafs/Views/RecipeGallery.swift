import SwiftUI

/// What a person chose in the gallery: a job to hand to Coach, or their own words. Home acts on
/// it once the gallery has closed, since Coach opens over Home, not over a sheet.
enum GalleryChoice: Equatable {
    case recipe(TafsCloud.Recipes.Recipe)
    case ownWords
}

/// Why the gallery is open: from + New, to start a taf; or to browse the jobs, with nothing
/// created until "Try it with Coach" is tapped on one.
enum GalleryMode: String, Identifiable {
    case create, browse
    var id: String { rawValue }
}

/// + New: every ready-made job, at any time, as tafs cloud sends them (`GET /v1/recipes/gallery`):
/// a row that opens Coach with nothing in it, to say the job in one's own words, then under its
/// "All jobs" heading its chips and every job by category with its apps and colour. Every word is
/// tafs cloud's. Browsing (Home's "Explore jobs" and the row under the strip) is the same
/// gallery under tafs cloud's "Explore jobs", without the own-words row: each job's sheet is read
/// only, with "Try it with Coach" as its one action.
struct RecipeGallerySheet: View {
    let gallery: TafsCloud.Gallery
    var mode: GalleryMode = .create
    /// The job sheet's words from `/v1/recipes`, for a tafs cloud whose gallery sends none.
    var labels: TafsCloud.Recipes.Labels?
    var logo: (String?) -> URL? = { _ in nil }
    /// Whether this person may use Coach: without it, no job is handed and there's no own-words row.
    let canHand: Bool
    let choose: (GalleryChoice) -> Void
    let close: () -> Void
    @State private var open: TafsCloud.Recipes.Recipe?

    var body: some View {
        let recipes = gallery.asRecipes(labels: labels)
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if mode == .create, canHand, !gallery.words.ownWords.isEmpty { ownWords }
                    if !gallery.words.all.isEmpty { Eyebrow(text: gallery.words.all) }
                    HomeRecipes(recipes: recipes, logo: logo) { open = $0 }
                }
                .padding(.horizontal, 20).padding(.vertical, 16)
                .frame(maxWidth: 640, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .screenBackground()
            .navigationTitle(gallery.title(browse: mode == .browse))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Words.Common.close, action: close).accessibilityIdentifier("gallery.close")
                }
            }
            .sheet(item: $open) { recipe in
                RecipeSheet(recipe: recipe, recipes: recipes, canHand: canHand,
                            handWords: gallery.handWords(browse: mode == .browse, labels: recipes.labels),
                            sampleLabel: gallery.sampleLabel) {
                    open = nil
                    choose(.recipe(recipe))
                }
            }
        }
    }

    /// "Say it in your own words": Coach, with nothing in its box.
    private var ownWords: some View {
        OwnWordsRow(title: gallery.words.ownWords, line: gallery.words.ownWordsLine) { choose(.ownWords) }
    }
}

/// Home's row that opens the jobs to browse, in tafs cloud's words ("Explore jobs"): there with
/// or without tafs, and for someone who may not use Coach too, since browsing creates nothing.
struct ExploreRow: View {
    let words: String
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            Panel {
                HStack(spacing: 12) {
                    Image(systemName: "square.grid.2x2").scaledFont(16, weight: .semibold).foregroundColor(Theme.soft)
                        .accessibilityHidden(true)
                    Text(words).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
                        .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                        .accessibilityHidden(true)
                }
            }
        }
        .buttonStyle(.drivablePlain)
        .driveWords(words)
        .accessibilityIdentifier("home.explore")
    }
}

/// The strip under the tafs list: a few jobs the account doesn't run yet, in tafs cloud's
/// order, under its title. Shown only where tafs cloud says so (`strip.shown`).
struct RecipeStrip: View {
    let gallery: TafsCloud.Gallery
    var logo: (String?) -> URL? = { _ in nil }
    let open: (TafsCloud.Recipes.Recipe) -> Void
    /// Opens every job to browse, from the row under the strip.
    var seeAll: (() -> Void)? = nil

    var body: some View {
        let jobs = gallery.stripRecipes
        if !jobs.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                if !gallery.strip.title.isEmpty { Eyebrow(text: gallery.strip.title) }
                ForEach(jobs) { recipe in
                    RecipeRow(recipe: recipe, category: gallery.category(of: recipe), logo: logo) { open(recipe) }
                }
                // The strip shows a few: this row opens them all, in tafs cloud's words for them.
                if let seeAll, let words = gallery.seeAll {
                    Button(action: seeAll) {
                        HStack {
                            Text(words).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Spacer()
                            Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim).accessibilityHidden(true)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.drivablePlain)
                    .driveWords(words)
                    .accessibilityIdentifier("home.recipe-strip.all")
                }
            }
            .accessibilityIdentifier("home.recipe-strip")
        }
    }
}
