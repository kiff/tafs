import SwiftUI
import UIKit

/// The Mac's window (docs/mac-app.md).
enum MacWindow {
    /// The smallest the window goes: the width of an iPad held upright, so the sidebar and a
    /// screen beside it always fit.
    static let minimumSize = CGSize(width: 820, height: 600)

    /// Sets the smallest size on every window. Nothing to do on iPhone and iPad.
    @MainActor
    static func apply() {
        #if targetEnvironment(macCatalyst)
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            scene.sizeRestrictions?.minimumSize = minimumSize
        }
        #endif
    }
}

#if targetEnvironment(macCatalyst)
/// The Mac's menu bar: the global shortcuts (`KeyCommand.global`), each in one of the
/// system's menus (`MacMenu`). On the Mac these take the place of `GlobalKeyCommands`.
@MainActor
struct MacCommands: Commands {
    @ObservedObject var model: AppModel
    @ObservedObject private var drive = DriveSession.shared

    var body: some Commands {
        CommandGroup(replacing: .newItem) { items(MacMenu.file) }
        CommandGroup(replacing: .appSettings) { items(MacMenu.app) }
        CommandGroup(before: .sidebar) { items(MacMenu.view) }
    }

    /// Off while Coach drives the app, as the shortcuts are: a key pressed then isn't a shortcut.
    private func items(_ commands: [KeyCommand]) -> some View {
        ForEach(commands, id: \.self) { command in
            Button(command.title) { model.run(command) }
                .shortcut(command)
                .disabled(drive.isOn || !model.allows(command))
        }
    }
}
#endif
