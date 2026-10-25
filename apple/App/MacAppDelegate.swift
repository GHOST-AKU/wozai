#if os(macOS)
import AppKit
import CoreFoundation

@MainActor
final class MacAppDelegate: NSObject, NSApplicationDelegate {
    weak var model: AppModel?
    private var terminationPending = false

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard !terminationPending else { return .terminateLater }
        guard let model else { return .terminateNow }
        terminationPending = true
        let flush = model.beginDraftFlush()
        Task.detached {
            let saved: Bool
            do { try await flush.value; saved = true } catch { saved = false }
            // AppKit's termination modal loop may not service main-dispatch/MainActor tasks.
            RunLoop.main.perform(inModes: [.default, .modalPanel]) {
                MainActor.assumeIsolated {
                    if saved {
                        sender.reply(toApplicationShouldTerminate: true)
                    } else {
                        self.terminationPending = false
                        sender.reply(toApplicationShouldTerminate: false)
                        let alert = NSAlert()
                        alert.alertStyle = .warning
                        alert.messageText = String(localized: "Couldn’t save drafts")
                        alert.informativeText = String(localized: "NearbyIM will stay open. Check available storage and try quitting again.")
                        alert.addButton(withTitle: String(localized: "OK"))
                        alert.runModal()
                    }
                }
            }
            CFRunLoopWakeUp(CFRunLoopGetMain())
        }
        // willTerminate is too late to await asynchronous SQLite writes.
        return .terminateLater
    }
}
#endif
