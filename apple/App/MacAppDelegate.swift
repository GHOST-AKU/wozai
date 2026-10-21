#if os(macOS)
import AppKit

@MainActor
final class MacAppDelegate: NSObject, NSApplicationDelegate {
    weak var model: AppModel?
    private var terminationPending = false

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard !terminationPending else { return .terminateLater }
        guard let model else { return .terminateNow }
        terminationPending = true
        Task { @MainActor in
            do {
                try await model.flushDrafts()
                sender.reply(toApplicationShouldTerminate: true)
            } catch {
                terminationPending = false
                sender.reply(toApplicationShouldTerminate: false)
                let alert = NSAlert()
                alert.alertStyle = .warning
                alert.messageText = String(localized: "Couldn’t save drafts")
                alert.informativeText = String(localized: "NearbyIM will stay open. Check available storage and try quitting again.")
                alert.addButton(withTitle: String(localized: "OK"))
                alert.runModal()
            }
        }
        // willTerminate is too late to await asynchronous SQLite writes.
        return .terminateLater
    }
}
#endif
