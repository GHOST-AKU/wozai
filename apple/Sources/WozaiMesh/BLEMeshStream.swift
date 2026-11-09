import Foundation
import CoreFoundation

/// Foundation's L2CAP streams need a live run loop. Exactly one bounded worker owns them.
public final class BLEMeshStream: NSObject, MeshByteStream, StreamDelegate, @unchecked Sendable {
    private let input: InputStream
    private let output: OutputStream
    private let gate=NSLock()
    private var loop: CFRunLoop?
    private struct Operation {
        let run: @Sendable () -> Void
        let reject: @Sendable (Error) -> Void
    }
    private var pending: [UUID:Operation]=[:]
    private var closing=false
    // The remaining state is exclusively owned by the stream's run-loop thread.
    private var ended=false
    private var buffer=Data()
    private var reader: (Int,CheckedContinuation<Data,Error>)?
    private struct Write {
        let data: Data
        var offset: Int
        let done: CheckedContinuation<Void,Error>
    }
    private var writes: [Write]=[]
    private var queuedBytes=0
    public init(input: InputStream, output: OutputStream) {
        self.input=input; self.output=output; super.init()
        Thread.detachNewThread { [self] in run() }
    }
    private func perform(_ action: @escaping @Sendable () -> Void, rejected: @escaping @Sendable (Error) -> Void) {
        gate.lock()
        if closing || pending.count>=16 { let error:MeshError=closing ? .closed : .busy;gate.unlock();rejected(error);return }
        let id=UUID();pending[id]=Operation(run:action,reject:rejected)
        if let loop {
            CFRunLoopPerformBlock(loop, CFRunLoopMode.defaultMode.rawValue) { [self] in execute(id) }
            CFRunLoopWakeUp(loop)
        }
        gate.unlock()
    }
    private func execute(_ id: UUID) {
        gate.lock();let operation=pending.removeValue(forKey:id);let stopped=closing;gate.unlock()
        if stopped { operation?.reject(MeshError.closed) } else { operation?.run() }
    }
    private func run() {
        let owner=CFRunLoopGetCurrent()!
        // Keep an otherwise idle run loop asleep rather than spinning when no stream event is due.
        let keepAlive=CFRunLoopTimerCreateWithHandler(nil,CFAbsoluteTimeGetCurrent()+86400,86400,0,0) { _ in }
        CFRunLoopAddTimer(owner,keepAlive,.defaultMode)
        input.delegate=self; output.delegate=self
        input.schedule(in:.current,forMode:.default); output.schedule(in:.current,forMode:.default)
        input.open(); output.open()
        gate.lock(); loop=owner; let ids=Array(pending.keys); gate.unlock()
        for id in ids { execute(id) }
        gate.lock(); let stopped=closing; gate.unlock()
        if !stopped { CFRunLoopRun() }
        finish(MeshError.closed)
        input.remove(from:.current,forMode:.default); output.remove(from:.current,forMode:.default)
        input.close(); output.close(); input.delegate=nil; output.delegate=nil
        CFRunLoopTimerInvalidate(keepAlive)
        // Keep the loop reference valid for late operations: they must fail, not enqueue forever.
        gate.lock(); loop=nil; closing=true; let late=Array(pending.values); pending=[:]; gate.unlock()
        for operation in late { operation.reject(MeshError.closed) }
    }
    private func finish(_ error: Error) {
        if let reader { self.reader=nil; reader.1.resume(throwing:error) }
        let pending=writes; writes=[]; queuedBytes=0
        for write in pending { write.done.resume(throwing:error) }
        buffer.removeAll(keepingCapacity:false)
    }
    public func close() {
        gate.lock(); let first = !closing; closing=true; let owner=loop
        let queued=Array(pending.values);pending=[:];gate.unlock()
        for operation in queued { operation.reject(MeshError.closed) }
        if first {
            if let owner {
                CFRunLoopPerformBlock(owner,CFRunLoopMode.defaultMode.rawValue) { [self] in
                    finish(MeshError.closed); CFRunLoopStop(owner)
                }
                CFRunLoopWakeUp(owner)
            }
        }
    }
    public func read(maximum: Int) async throws -> Data {
        guard (1...32772).contains(maximum) else { throw MeshError.bound }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            try await withCheckedThrowingContinuation { continuation in
                gate.lock(); let stopped=closing; gate.unlock()
                if stopped { continuation.resume(throwing:MeshError.closed); return }
                perform({ [self] in
                    gate.lock(); let stopped=closing; gate.unlock()
                    guard !stopped else { continuation.resume(throwing:MeshError.closed); return }
                    guard reader == nil else { continuation.resume(throwing:MeshError.busy); return }
                    reader=(maximum,continuation); pumpRead()
                }, rejected: { continuation.resume(throwing:$0) })
            }
        } onCancel: { self.close() }
    }
    public func write(_ data: Data) async throws {
        guard data.count<=32772 else { throw MeshError.bound }
        try await withTaskCancellationHandler {
            try Task.checkCancellation()
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void,Error>) in
                gate.lock(); let stopped=closing; gate.unlock()
                if stopped { continuation.resume(throwing:MeshError.closed); return }
                perform({ [self] in
                    gate.lock(); let stopped=closing; gate.unlock()
                    guard !stopped else { continuation.resume(throwing:MeshError.closed); return }
                    guard writes.count<8,queuedBytes+data.count<=65536 else { continuation.resume(throwing:MeshError.busy); return }
                    writes.append(Write(data:data,offset:0,done:continuation)); queuedBytes+=data.count
                    pumpWrite()
                }, rejected: { continuation.resume(throwing:$0) })
            }
        } onCancel: { self.close() }
    }
    private func deliverRead() {
        guard let reader, !buffer.isEmpty || (ended && !input.hasBytesAvailable) else { return }
        self.reader=nil
        let count=min(reader.0,buffer.count)
        let data=Data(buffer.prefix(count)); buffer.removeFirst(count)
        reader.1.resume(returning:data)
    }
    private func pumpRead() {
        deliverRead()
        var bytes=[UInt8](repeating:0,count:4096)
        while input.hasBytesAvailable && buffer.count<65536 {
            let n=input.read(&bytes,maxLength:min(bytes.count,65536-buffer.count))
            if n<0 { finish(MeshError.closed); close(); return }
            if n==0 { ended=true; break }
            buffer.append(contentsOf:bytes.prefix(n)); deliverRead()
        }
        deliverRead()
    }
    private func pumpWrite() {
        while !writes.isEmpty {
            if writes[0].offset == writes[0].data.count {
                let done=writes.removeFirst(); queuedBytes-=done.data.count; done.done.resume(); continue
            }
            guard output.hasSpaceAvailable else { return }
            let offset=writes[0].offset
            let n=writes[0].data.withUnsafeBytes { raw in
                output.write(raw.bindMemory(to:UInt8.self).baseAddress!.advanced(by:offset),maxLength:raw.count-offset)
            }
            if n<0 { finish(MeshError.closed); close(); return }
            if n==0 { return }
            writes[0].offset+=n
        }
    }
    public func stream(_ stream: Stream, handle eventCode: Stream.Event) {
        if eventCode.contains(.errorOccurred) { finish(MeshError.closed); close(); return }
        if stream === input {
            if eventCode.contains(.endEncountered) { ended=true; deliverRead() }
            if eventCode.contains(.hasBytesAvailable) { pumpRead() }
        } else {
            if eventCode.contains(.endEncountered) { finish(MeshError.closed); close() }
            if eventCode.contains(.hasSpaceAvailable) { pumpWrite() }
        }
    }
}
