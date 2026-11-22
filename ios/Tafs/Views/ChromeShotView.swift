import SwiftUI
import UIKit

/// The page as a Chrome showed it just before a taf's step acted, kept for 30 days (docs/chrome.md, "Screenshots of a run's
/// acts"). Opened from a taf's Activity row that names a Chrome step; planque keeps no picture while its switch is off, and
/// the sheet then says so plainly.
struct ChromeShotView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let step: String

    enum Phase: Equatable {
        case loading
        case shown(PlanqueClient.ChromeScreenshot)
        case none
        case failed
    }

    @State private var phase: Phase = .loading

    var body: some View {
        NavStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    switch phase {
                    case .loading:
                        ProgressView().frame(maxWidth: .infinity, minHeight: 160)
                    case .shown(let shot):
                        if let image = UIImage(data: shot.image) {
                            Image(uiImage: image)
                                .resizable().scaledToFit()
                                .frame(maxWidth: .infinity)
                                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Theme.dim.opacity(0.3)))
                                .accessibilityIdentifier("chrome.shot.image")
                        }
                        Text(shot.takenAt.formatted(date: .abbreviated, time: .standard)).scaledFont(13).foregroundColor(Theme.dim)
                        Text(Words.Chrome.shotKept).scaledFont(13).foregroundColor(Theme.dim)
                    case .none:
                        Text(Words.Chrome.shotNone).scaledFont(15).foregroundColor(Theme.dim)
                    case .failed:
                        Text(Words.Chrome.shotFailed).scaledFont(15).foregroundColor(Theme.no)
                    }
                }
                .padding(16)
            }
            .background(Theme.background)
            .navigationTitle(Words.Chrome.shotTitle)
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .trailingBar) { Button(Words.Common.done) { dismiss() } }
            }
        }
        .task { await load() }
    }

    private func load() async {
        do {
            if let shot = try await model.chromeScreenshot(step: step) {
                phase = .shown(shot)
            } else {
                phase = .none
            }
        } catch {
            phase = .failed
        }
    }
}
