import AppKit
import WozaiCore

/// An independent process exercises normal AppKit termination before the debounce expires.
@main
struct MacTerminationProbe {
    @MainActor static func main() throws {
        let database = URL(fileURLWithPath: CommandLine.arguments[1])
        let store = try ChatStore(url: database)
        let model = AppModel(store: store)
        let application = NSApplication.shared
        application.setActivationPolicy(.prohibited)
        #if HAS_MAC_LIFECYCLE
        let delegate = MacAppDelegate()
        delegate.model = model
        application.delegate = delegate
        #endif
        Task { @MainActor in
            do {
                try await store.saveDraft(peerID: "edited", text: "old draft")
                try await store.saveDraft(peerID: "cleared", text: "old draft to clear")
                model.saveDraft(peerID: "edited", text: "latest draft before quit")
                model.saveDraft(peerID: "cleared", text: "")
                // No event-loop turn or sleep separates editing and the normal quit request.
                application.terminate(nil)
            } catch {
                fputs("Probe setup failed: \(error)\n", stderr)
                exit(2)
            }
        }
        withExtendedLifetime(model) {
            #if HAS_MAC_LIFECYCLE
            withExtendedLifetime(delegate) { application.run() }
            #else
            application.run()
            #endif
        }
    }
}
