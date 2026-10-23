import Foundation
import SQLite3
import WozaiCore

/// Hold a real SQLite write while background flushing overlaps a new foreground edit.
private final class DraftTrace: @unchecked Sendable {
    static let shared = DraftTrace()
    let entered = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    let foreground = DispatchSemaphore(value: 0)
    let latest = CommandLine.arguments[2] == "clear" ? "" : "foreground newest"
}

private let installDraftTrace: @convention(c) (OpaquePointer?, UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?, UnsafeRawPointer?) -> Int32 = { database, _, _ in
    sqlite3_trace_v2(database, UInt32(SQLITE_TRACE_STMT), { _, _, statement, _ in
        guard let statement, let expanded = sqlite3_expanded_sql(OpaquePointer(statement)) else { return 0 }
        defer { sqlite3_free(expanded) }
        let sql = String(cString: expanded)
        guard sql.hasPrefix("INSERT INTO drafts") else { return 0 }
        let trace = DraftTrace.shared
        if sql.contains("'blocked prior write'") {
            trace.entered.signal()
            _ = trace.release.wait(timeout: .now() + 8)
        }
        if sql.contains("VALUES('draft-peer','\(trace.latest)')") { trace.foreground.signal() }
        return 0
    }, nil)
}

@main
struct DraftFlushProbe {
    @MainActor static func main() async {
        do {
            let callback = unsafeBitCast(installDraftTrace, to: (@convention(c) () -> Void).self)
            guard sqlite3_auto_extension(callback) == SQLITE_OK else { throw WozaiError.storage }
            let store = try ChatStore(url: URL(fileURLWithPath: CommandLine.arguments[1]))
            let model = AppModel(store: store), trace = DraftTrace.shared
            defer { trace.release.signal(); sqlite3_reset_auto_extension() }
            model.saveDraft(peerID: "draft-peer", text: "blocked prior write")
            try await wait(trace.entered)
            model.saveDraft(peerID: "draft-peer", text: "background snapshot")
            let flush = model.beginDraftFlush()
            model.resume()
            model.saveDraft(peerID: "draft-peer", text: trace.latest)
            let overlapping = CommandLine.arguments[2] == "overlap" ? model.beginDraftFlush() : nil
            // Let the foreground edit's normal debounce elapse while the older SQLite write is held.
            try await Task.sleep(for: .milliseconds(650))
            trace.release.signal()
            try await flush.value
            try await overlapping?.value
            try await wait(trace.foreground)
            let saved = try await store.draft(peerID: "draft-peer")
            guard saved == trace.latest else {
                throw NSError(domain: "DraftFlushProbe", code: 1, userInfo: [NSLocalizedDescriptionKey: "Old background snapshot overwrote foreground draft: \(saved.debugDescription)"])
            }
            print("PASS: \(CommandLine.arguments[2]) preserves the latest foreground draft after overlapping flushes")
        } catch {
            fputs("Draft flush regression: \(error)\n", stderr)
            exit(1)
        }
    }

    private static func wait(_ semaphore: DispatchSemaphore) async throws {
        let completed = await Task.detached { semaphore.wait(timeout: .now() + 5) == .success }.value
        guard completed else { throw WozaiError.timeout }
    }
}
