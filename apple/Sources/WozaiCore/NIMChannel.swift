import CryptoKit
import Foundation

public protocol ByteStream: Sendable {
    func readExactly(_ count: Int) async throws -> Data
    func write(_ data: Data) async throws
    func close()
}

/// One reader, ordered writes, and an independent Noise nonce space per connection.
public actor NIMChannel {
    private let stream: any ByteStream
    private let identity: DeviceIdentity
    private let nickname: String
    private let initiator: Bool
    private var transport: NoiseTransport?
    private var started = false
    private var established = false
    private var ended = false
    private var writer: Task<Void, Error>?
    private var writeSequence: UInt64 = 0
    private var textCapability: TextCapability?
    private var readCapability: ReadReceiptCapability?
    private var readEnabled = true
    var readReceiptsActive: Bool { readCapability?.active == true && !ended }

    public var textLimit: Int { textCapability?.outgoing ?? TextLimits.legacy }
    public private(set) var connectionGeneration: String?

    public init(stream: any ByteStream, identity: DeviceIdentity, nickname: String, initiator: Bool) {
        self.stream = stream; self.identity = identity; self.nickname = nickname; self.initiator = initiator
    }

    public func establish(expectedID: String? = nil, expectedRoot: Data? = nil) async throws -> VerifiedPeer {
        guard !started, !ended else { throw WozaiError.closed }
        started = true
        do {
            let handshake = try NoiseHandshake(initiator: initiator, staticPrivateKey: identity.noise.rawRepresentation,
                prologue: Data("wozai-nim4\0Noise_XX_25519_AESGCM_SHA256\0file-v2-32k\0".utf8))
            let local = try PeerClaim(initiator: initiator, id: identity.id, nickname: nickname, root: identity.root,
                noisePublic: identity.noise.publicKey.rawRepresentation, capabilities: 3).encoded()
            let remote: Data
            if initiator {
                try await sendEnvelope(.handshake, bytes: handshake.write(Data()))
                remote = try handshake.read(await readEnvelope(.handshake))
                try await sendEnvelope(.handshake, bytes: handshake.write(local))
            } else {
                guard try handshake.read(await readEnvelope(.handshake)).isEmpty else { throw WozaiError.protocolViolation }
                try await sendEnvelope(.handshake, bytes: handshake.write(local))
                remote = try handshake.read(await readEnvelope(.handshake))
            }
            let peer = try PeerClaim.decode(remote, expectedInitiator: !initiator, localID: identity.id)
            guard peer.noisePublic == handshake.remoteStatic else { throw WozaiError.identityChanged }
            let first = initiator ? local : remote, second = initiator ? remote : local
            var binding = WireWriter(); binding.data = Data("wozai-root-proof-v4\0".utf8)
            binding.shortBytes(first); binding.shortBytes(second); binding.shortBytes(handshake.handshakeHash)
            var generation = WireWriter(); generation.data = Data("wozai-connection-v4\0".utf8)
            generation.shortBytes(first); generation.shortBytes(second); generation.shortBytes(handshake.handshakeHash)
            connectionGeneration = Data(SHA256.hash(data: generation.data)).hexString
            transport = try handshake.split()
            if initiator {
                let signature = try identity.signing.signature(for: binding.data + Data([0])).derRepresentation
                try await sendEncrypted(.rootProof, plaintext: signature)
                try await verifyProof(peer.root, binding.data + Data([1]))
            } else {
                try await verifyProof(peer.root, binding.data + Data([0]))
                let signature = try identity.signing.signature(for: binding.data + Data([1])).derRepresentation
                try await sendEncrypted(.rootProof, plaintext: signature)
            }
            guard !ended else { throw WozaiError.closed }
            guard expectedID == nil || expectedID == peer.id, expectedRoot == nil || expectedRoot == peer.root else { throw WozaiError.identityChanged }
            textCapability = TextCapability(generation: connectionGeneration!)
            readCapability = ReadReceiptCapability(generation: connectionGeneration!, enabled: readEnabled)
            established = true
            return VerifiedPeer(id: peer.id, nickname: peer.nickname, root: peer.root, capabilities: peer.capabilities)
        } catch { close(); throw error }
    }

    private func verifyProof(_ root: Data, _ binding: Data) async throws {
        let encoded = try await readEncrypted(.rootProof)
        guard !encoded.isEmpty, encoded.count <= 80 else { throw WozaiError.protocolViolation }
        let signature = try P256.Signing.ECDSASignature(derRepresentation: encoded)
        guard try canonicalRoot(root).isValidSignature(signature, for: binding) else { throw WozaiError.identityChanged }
    }

    private func sendEnvelope(_ kind: Envelope.Kind, bytes: Data) async throws {
        guard !ended else { throw WozaiError.closed }
        try await writeOrdered(Envelope.header(kind: kind, payloadSize: bytes.count) + bytes)
    }

    private func readEnvelope(_ kind: Envelope.Kind) async throws -> Data {
        let prefix = try await stream.readExactly(4)
        var reader = WireReader(prefix)
        let length = Int(try reader.u32())
        let maximum = kind == .handshake ? 2048 : kind == .rootProof ? 96 : 48 * 1024 + 16
        guard length >= 6, length <= maximum + 6 else { throw WozaiError.protocolViolation }
        let suffix = try await stream.readExactly(6)
        guard try prefix + suffix == Envelope.header(kind: kind, payloadSize: length - 6) else { throw WozaiError.protocolViolation }
        return try await stream.readExactly(length - 6)
    }

    private func sendEncrypted(_ kind: Envelope.Kind, plaintext: Data) async throws {
        guard !ended, var state = transport else { throw WozaiError.closed }
        let header = try Envelope.header(kind: kind, payloadSize: plaintext.count + 16)
        let ciphertext = try state.sending.seal(plaintext, authenticating: header)
        transport = state
        try await writeOrdered(header + ciphertext)
    }

    private func writeOrdered(_ bytes: Data) async throws {
        guard !ended else { throw WozaiError.closed }
        let previous = writer, stream = self.stream
        writeSequence &+= 1
        let sequence = writeSequence
        // Reserve FIFO order before suspension; actor isolation alone does not order I/O.
        let task = Task {
            if let previous { try await previous.value }
            try Task.checkCancellation()
            try await stream.write(bytes)
        }
        writer = task
        do {
            try await withTaskCancellationHandler {
                try await task.value
            } onCancel: {
                task.cancel()
                stream.close()
            }
            if writeSequence == sequence { writer = nil }
        } catch { close(); throw error }
    }

    private func readEncrypted(_ kind: Envelope.Kind) async throws -> Data {
        let bytes = try await readEnvelope(kind)
        guard !ended, var state = transport else { throw WozaiError.closed }
        let plaintext = try state.receiving.open(bytes, authenticating: Envelope.header(kind: kind, payloadSize: bytes.count))
        transport = state
        return plaintext
    }

    public func send(_ frame: NIMFrame) async throws {
        guard established, !ended else { throw WozaiError.closed }
        let bytes = try frame.encoded(textLimit: textLimit)
        do { try await sendEncrypted(.record, plaintext: bytes) } catch { close(); throw error }
    }

    public func receive() async throws -> NIMFrame {
        guard established, !ended else { throw WozaiError.closed }
        do { return try NIMFrame.decode(await readEncrypted(.record), textLimit: textCapability?.incoming ?? TextLimits.legacy) } catch { close(); throw error }
    }

    func startTextNegotiation() async throws {
        if let request = textCapability?.start() { try await send(NIMFrame(type: .pong, timestamp: request)) }
    }
    func handleTextCapability(_ time: UInt64) async throws -> Int {
        if let response = textCapability?.receive(time) { try await send(NIMFrame(type: .pong, timestamp: response)) }
        return textLimit
    }

    func startReadNegotiation() async throws {
        if let token = readCapability?.start() { try await send(NIMFrame(type: .pong, timestamp: token)) }
    }
    func handleReadToken(_ time: UInt64) async throws {
        let wasNegotiated = readCapability?.negotiated == true
        if let response = readCapability?.receiveToken(time) { try await send(NIMFrame(type: .pong, timestamp: response)) }
        if !wasNegotiated, readCapability?.negotiated == true, let packet = readCapability?.policy { try await sendReadPacket(packet) }
    }
    func configureReadReceipts(_ enabled: Bool) async throws {
        readEnabled = enabled; readCapability?.configure(enabled)
        if readCapability?.negotiated == true, let packet = readCapability?.policy { try await sendReadPacket(packet) }
    }
    private func sendReadPacket(_ packet: ReadReceiptPacket) async throws {
        // Wait before sealing: dropping an already encrypted queued record would skip a Noise nonce.
        if let writer { try await writer.value }
        guard !ended, readCapability?.outgoingCurrent(packet) == true else { return }
        try await send(packet.frame())
    }
    func handleReadPacket(_ packet: ReadReceiptPacket) throws -> Bool { try readCapability?.receive(packet) ?? false }
    func currentReadPacket(_ packet: ReadReceiptPacket) -> Bool { !ended && readCapability?.current(packet) == true }
    func sendReadReceipts(_ ids: [String]) async throws {
        if let packet = readCapability?.send(ids) { try await sendReadPacket(packet) }
    }
    func acknowledgeReadReceipt(_ packet: ReadReceiptPacket) async throws {
        if let ack = readCapability?.acknowledge(packet) { try await sendReadPacket(ack) }
    }
    func readReceiptPersisted(_ packet: ReadReceiptPacket) { readCapability?.persisted(packet) }
    public func close() {
        ended = true; established = false; transport = nil
        writer?.cancel(); writer = nil
        stream.close()
    }
}
