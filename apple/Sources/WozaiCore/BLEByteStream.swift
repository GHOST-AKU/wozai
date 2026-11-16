import Foundation

/// NIM4 remains the authenticated protocol. These UUIDs identify only its BLE carrier.
public enum PrivateBLE {
    public static let service = UUID(uuidString: "b326d8e0-8a5c-4cc2-a35f-4b1146fe1001")!
    public static let receive = UUID(uuidString: "b326d8e0-8a5c-4cc2-a35f-4b1146fe1002")!
    public static let transmit = UUID(uuidString: "b326d8e0-8a5c-4cc2-a35f-4b1146fe1003")!
    public static let bufferLimit = 65_536
    public static let maximumWrite = 48 * 1024 + 26
}

/// One bounded reader and writer; the driver supplies ATT write-response/indication backpressure.
/// Value 0 || bytes carries ordered stream bytes; value 1 closes this subscription's stream.
public final class BLEByteStream: ByteStream, @unchecked Sendable {
    private let lock = NSLock()
    private var bytes = Data()
    private var ended = false
    private var writing = false
    private var reader: (Int, CheckedContinuation<Data, Error>)?
    private let valueSize: Int
    private let sendValue: @Sendable (Data) async throws -> Void
    private let onClose: @Sendable () -> Void

    public init(valueSize: Int, send: @escaping @Sendable (Data) async throws -> Void,
                closed: @escaping @Sendable () -> Void) {
        self.valueSize = min(512, max(20, valueSize)); sendValue = send; onClose = closed
    }

    @discardableResult public func receive(_ value: Data) -> Bool {
        if value == Data([1]) { finish(preserveInput: true); return true }
        guard (2...512).contains(value.count), value.first == 0 else { close(); return false }
        var delivery: (CheckedContinuation<Data, Error>, Data)?
        let accepted = lock.withLock {
            guard !ended, bytes.count + value.count - 1 <= PrivateBLE.bufferLimit else { return false }
            bytes.append(value.dropFirst())
            if let pending = reader, bytes.count >= pending.0 {
                let result = Data(bytes.prefix(pending.0)); bytes.removeFirst(pending.0)
                reader = nil; delivery = (pending.1, result)
            }
            return true
        }
        if let delivery { delivery.0.resume(returning: delivery.1) }
        if !accepted { close() }
        return accepted
    }

    public func readExactly(_ count: Int) async throws -> Data {
        guard (0...48 * 1024 + 16).contains(count) else { close(); throw WozaiError.protocolViolation }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                var result: Result<Data, Error>?
                lock.withLock {
                    if reader != nil { result = .failure(WozaiError.protocolViolation) }
                    else if bytes.count >= count {
                        result = .success(Data(bytes.prefix(count))); bytes.removeFirst(count)
                    } else if ended { result = .failure(WozaiError.closed)
                    } else { reader = (count, continuation) }
                }
                if let result { continuation.resume(with: result) }
            }
        } onCancel: { self.close() }
    }

    public func write(_ data: Data) async throws {
        guard data.count <= PrivateBLE.maximumWrite else { close(); throw WozaiError.protocolViolation }
        let admitted = lock.withLock { () -> Bool in
            guard !ended, !writing else { return false }; writing = true; return true
        }
        guard admitted else { throw WozaiError.closed }
        defer { lock.withLock { writing = false } }
        do {
            try await withTaskCancellationHandler {
                try Task.checkCancellation()
                for offset in stride(from: 0, to: data.count, by: valueSize - 1) {
                    guard !lock.withLock({ ended }) else { throw WozaiError.closed }
                    try Task.checkCancellation()
                    try await sendValue(Data([0]) + data.dropFirst(offset).prefix(valueSize - 1))
                }
                guard !lock.withLock({ ended }) else { throw WozaiError.closed }
            } onCancel: { self.close() }
        } catch { close(); throw error }
    }

    public func close() { finish(preserveInput: false) }
    private func finish(preserveInput: Bool) {
        var pending: CheckedContinuation<Data, Error>?
        let first = lock.withLock {
            guard !ended else { return false }; ended = true
            if !preserveInput { bytes.removeAll(keepingCapacity: false) }
            pending = reader?.1; reader = nil; return true
        }
        pending?.resume(throwing: WozaiError.closed)
        if first { onClose() }
    }
}
