import SwiftUI

@main
@MainActor
struct NearbyIMApp: App {
    @State private var model = AppModel()
    #if os(macOS)
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var appDelegate
    #endif
    @AppStorage("appearance") private var appearance = "system"

    private var colorScheme: ColorScheme? {
        appearance == "dark" ? .dark : appearance == "light" ? .light : nil
    }

    var body: some Scene {
        mainWindow
        #if os(macOS)
        Settings {
            SettingsView()
                .environment(model)
                .preferredColorScheme(colorScheme)
                .frame(minWidth: 420, idealWidth: 480)
        }
        #endif
    }

    private var mainWindow: some Scene {
        WindowGroup {
            RootView()
                #if os(macOS)
                .onAppear { appDelegate.model = model }
                #endif
                .environment(model)
                .preferredColorScheme(colorScheme)
        }
        #if os(macOS)
        .defaultSize(width: 940, height: 680)
        .commands {
            SidebarCommands()
            NearbyCommands()
        }
        #endif
    }
}

#if os(macOS)
@MainActor
private struct NearbyCommands: Commands {
    @FocusedValue(\.findNearby) private var findNearby

    var body: some Commands {
        CommandGroup(after: .newItem) {
            Button("Find nearby devices") { findNearby?() }
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .disabled(findNearby == nil)
        }
    }
}
#endif
