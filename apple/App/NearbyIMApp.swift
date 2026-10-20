import SwiftUI

@main
@MainActor
struct NearbyIMApp: App {
    @State private var model = AppModel()
    @AppStorage("appearance") private var appearance = "system"

    private var colorScheme: ColorScheme? {
        appearance == "dark" ? .dark : appearance == "light" ? .light : nil
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(colorScheme)
        }
        #if os(macOS)
        .defaultSize(width: 940, height: 680)
        .commands {
            SidebarCommands()
            CommandGroup(after: .newItem) {
                Button("Find nearby devices") { model.newChatRequest += 1 }
                    .keyboardShortcut("n", modifiers: [.command, .shift])
            }
        }
        Settings {
            SettingsView()
                .environment(model)
                .preferredColorScheme(colorScheme)
                .frame(minWidth: 420, idealWidth: 480)
        }
        #endif
    }
}
