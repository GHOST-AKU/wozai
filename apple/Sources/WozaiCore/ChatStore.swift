import Foundation
import SQLite3

public enum MessageStatus: String, Codable, Sendable { case pending, sent, delivered, unconfirmed }

public struct MessageCursor: Equatable, Sendable {
    public let timestamp: UInt64
    public let sequence: Int64
}

public struct ChatMessage: Identifiable, Equatable, Sendable {
    public let id: String
    public let peerID: String
    public let body: String
    public let timestamp: UInt64
    public let outgoing: Bool
    public let status: MessageStatus
    public let cursor: MessageCursor
    public var rowID: String { (outgoing ? "out-" : "in-") + id }
}

public struct Conversation: Identifiable, Equatable, Sendable {
    public let id: String
    public let nickname: String
    public let root: Data
    public let remembered: Bool
    public let verified: Bool
    public let lastActivity: UInt64
}

/// SQLite FULL commits precede incoming ACKs; no full-history snapshots are rewritten.
public actor ChatStore {
    private let database: SQLiteDatabase

    public init(url: URL) throws {
        database = try SQLiteDatabase(url: url)
        try database.run("PRAGMA journal_mode=WAL")
        try database.run("PRAGMA synchronous=FULL")
        try database.run("PRAGMA cache_size=-1024")
        try database.run("CREATE TABLE IF NOT EXISTS contacts (id TEXT PRIMARY KEY, nickname TEXT NOT NULL, root BLOB NOT NULL, local_root BLOB NOT NULL, trusted INTEGER NOT NULL, verified INTEGER NOT NULL DEFAULT 0, activity INTEGER NOT NULL DEFAULT 0)")
        try database.run("CREATE TABLE IF NOT EXISTS messages (peer TEXT NOT NULL, id TEXT NOT NULL, outgoing INTEGER NOT NULL, body TEXT NOT NULL, time INTEGER NOT NULL, status TEXT NOT NULL, PRIMARY KEY(peer,id,outgoing))")
        try database.run("CREATE INDEX IF NOT EXISTS message_order ON messages(peer,time)")
        try database.run("CREATE TABLE IF NOT EXISTS drafts (peer TEXT PRIMARY KEY, body TEXT NOT NULL)")
        try database.run("UPDATE messages SET status='unconfirmed' WHERE outgoing=1 AND status IN ('pending','sent')")
    }

    public func recordPeer(_ peer: VerifiedPeer, remember: Bool, localRoot: Data) throws {
        _ = try canonicalRoot(peer.root)
        if let pinned = try trustedRoot(peerID: peer.id), pinned != peer.root { throw WozaiError.identityChanged }
        try database.run("""
            INSERT INTO contacts(id,nickname,root,local_root,trusted,activity) VALUES(?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET nickname=excluded.nickname,
            verified=CASE WHEN contacts.root=excluded.root AND contacts.local_root=excluded.local_root THEN contacts.verified ELSE 0 END,
            root=excluded.root,local_root=excluded.local_root,trusted=MAX(contacts.trusted,excluded.trusted),activity=excluded.activity
            """, [.text(peer.id), .text(peer.nickname), .blob(peer.root), .blob(localRoot), .integer(remember ? 1 : 0), .integer(Int64(Date().timeIntervalSince1970 * 1000))])
    }

    public func remember(_ peer: VerifiedPeer, localRoot: Data) throws { try recordPeer(peer, remember: true, localRoot: localRoot) }

    public func trustedRoot(peerID: String) throws -> Data? {
        try database.rows("SELECT root FROM contacts WHERE id=? AND trusted=1", [.text(peerID)]).first?["root"]?.blob
    }

    public func forget(peerID: String) throws {
        try database.run("UPDATE contacts SET trusted=0,verified=0 WHERE id=?", [.text(peerID)])
    }

    public func markVerified(_ peer: VerifiedPeer, localRoot: Data) throws {
        try database.run("UPDATE contacts SET verified=1 WHERE id=? AND root=? AND local_root=?", [.text(peer.id), .blob(peer.root), .blob(localRoot)])
    }

    public func conversations(localRoot: Data) throws -> [Conversation] {
        try database.rows("SELECT * FROM contacts ORDER BY activity DESC LIMIT 500").map { row in
            Conversation(id: row["id"]!.text, nickname: row["nickname"]!.text, root: row["root"]!.blob,
                         remembered: row["trusted"]!.integer == 1,
                         verified: row["verified"]!.integer == 1 && row["local_root"]!.blob == localRoot,
                         lastActivity: UInt64(max(0, row["activity"]!.integer)))
        }
    }

    @discardableResult
    public func saveIncoming(peerID: String, frame: NIMFrame) throws -> Bool {
        guard frame.type == .text else { throw WozaiError.protocolViolation }
        _ = try frame.encoded()
        let prior = try database.rows("SELECT body,time FROM messages WHERE peer=? AND id=? AND outgoing=0", [.text(peerID), .text(frame.id)])
        if let row = prior.first {
            guard row["body"]!.text == frame.body, row["time"]!.integer == Int64(frame.timestamp) else { throw WozaiError.protocolViolation }
            return false
        }
        try insert(peerID: peerID, frame: frame, outgoing: false, status: .delivered)
        return true
    }

    public func saveOutgoing(peerID: String, frame: NIMFrame) throws {
        _ = try frame.encoded()
        try insert(peerID: peerID, frame: frame, outgoing: true, status: .pending)
    }

    private func insert(peerID: String, frame: NIMFrame, outgoing: Bool, status: MessageStatus) throws {
        try database.run("INSERT INTO messages(peer,id,outgoing,body,time,status) VALUES(?,?,?,?,?,?)",
                         [.text(peerID), .text(frame.id), .integer(outgoing ? 1 : 0), .text(frame.body), .integer(Int64(frame.timestamp)), .text(status.rawValue)])
        try database.run("UPDATE contacts SET activity=? WHERE id=?", [.integer(Int64(Date().timeIntervalSince1970 * 1000)), .text(peerID)])
    }

    public func setOutgoingStatus(peerID: String, id: String, status: MessageStatus) throws {
        // A fast ACK may arrive before the socket completion; never regress delivery.
        try database.run("UPDATE messages SET status=? WHERE peer=? AND id=? AND outgoing=1 AND status!='delivered'", [.text(status.rawValue), .text(peerID), .text(id)])
    }

    public func messages(peerID: String, before: MessageCursor? = nil) throws -> [ChatMessage] {
        var bindings: [SQLValue] = [.text(peerID)]
        let condition: String
        if let before {
            condition = " AND (time<? OR (time=? AND rowid<?))"
            let timestamp = Int64(min(before.timestamp, UInt64(Int64.max)))
            bindings += [.integer(timestamp), .integer(timestamp), .integer(before.sequence)]
        } else { condition = "" }
        return try database.rows("SELECT rowid AS sequence,* FROM messages WHERE peer=?" + condition + " ORDER BY time DESC,rowid DESC LIMIT 200", bindings).reversed().map { row in
            guard let status = MessageStatus(rawValue: row["status"]!.text) else { throw WozaiError.storage }
            let timestamp = UInt64(row["time"]!.integer)
            return ChatMessage(id: row["id"]!.text, peerID: peerID, body: row["body"]!.text, timestamp: timestamp, outgoing: row["outgoing"]!.integer == 1, status: status,
                               cursor: MessageCursor(timestamp: timestamp, sequence: row["sequence"]!.integer))
        }
    }

    public func saveDraft(peerID: String, text: String) throws {
        try database.run("INSERT INTO drafts(peer,body) VALUES(?,?) ON CONFLICT(peer) DO UPDATE SET body=excluded.body", [.text(peerID), .text(text)])
    }

    public func draft(peerID: String) throws -> String {
        try database.rows("SELECT body FROM drafts WHERE peer=?", [.text(peerID)]).first?["body"]?.text ?? ""
    }
}

private enum SQLValue {
    case text(String), integer(Int64), blob(Data)
    var text: String { if case .text(let value) = self { return value }; return "" }
    var integer: Int64 { if case .integer(let value) = self { return value }; return 0 }
    var blob: Data { if case .blob(let value) = self { return value }; return Data() }
}

/// Owned exclusively by ChatStore's actor. SQLite also uses serialized connection mode.
private final class SQLiteDatabase: @unchecked Sendable {
    private var handle: OpaquePointer?
    init(url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            if let handle { sqlite3_close(handle) }; handle = nil
            throw WozaiError.storage
        }
        sqlite3_busy_timeout(handle, 2000)
    }
    deinit { if let handle { sqlite3_close(handle) } }

    private func prepare(_ sql: String, _ values: [SQLValue]) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw WozaiError.storage }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let code: Int32
            switch value {
            case .text(let text):
                guard text.utf8.count <= Int(Int32.max) else { sqlite3_finalize(statement); throw WozaiError.storage }
                code = sqlite3_bind_text(statement, index, text, Int32(text.utf8.count), transient)
            case .integer(let number): code = sqlite3_bind_int64(statement, index, number)
            case .blob(let data): code = data.withUnsafeBytes { sqlite3_bind_blob(statement, index, $0.baseAddress, Int32($0.count), transient) }
            }
            guard code == SQLITE_OK else { sqlite3_finalize(statement); throw WozaiError.storage }
        }
        return statement
    }

    func run(_ sql: String, _ values: [SQLValue] = []) throws {
        let statement = try prepare(sql, values); defer { sqlite3_finalize(statement) }
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW { result = sqlite3_step(statement) }
        guard result == SQLITE_DONE else { throw WozaiError.storage }
    }

    func rows(_ sql: String, _ values: [SQLValue] = []) throws -> [[String: SQLValue]] {
        let statement = try prepare(sql, values); defer { sqlite3_finalize(statement) }
        var rows = [[String: SQLValue]]()
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { return rows }
            guard result == SQLITE_ROW else { throw WozaiError.storage }
            var row = [String: SQLValue]()
            for index in 0..<sqlite3_column_count(statement) {
                let name = String(cString: sqlite3_column_name(statement, index))
                switch sqlite3_column_type(statement, index) {
                case SQLITE_INTEGER: row[name] = .integer(sqlite3_column_int64(statement, index))
                case SQLITE_BLOB:
                    let length = Int(sqlite3_column_bytes(statement, index))
                    row[name] = .blob(length > 0 ? Data(bytes: sqlite3_column_blob(statement, index)!, count: length) : Data())
                default:
                    let length = Int(sqlite3_column_bytes(statement, index))
                    guard let bytes = sqlite3_column_text(statement, index), let text = String(data: Data(bytes: bytes, count: length), encoding: .utf8) else { throw WozaiError.storage }
                    row[name] = .text(text)
                }
            }
            rows.append(row)
        }
    }
}
