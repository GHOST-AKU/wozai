import base64
import sqlite3
import subprocess
import sys
import unittest

classpath = sys.argv.pop(1)
export = subprocess.check_output(['java', '-cp', classpath, 'dev.ghost.nearbyim.storage.SchemaExport'], text=True).splitlines()
version = int(export[0])
sql = [base64.b64decode(line).decode() for line in export[1:]]
CREATE_CONVERSATIONS, CREATE_MESSAGES, CREATE_INDEX, CREATE_TRUST, CONVERSATIONS, REMEMBER, REVOKE, CLEAR, TOUCH_INSERT, TOUCH_UPDATE, RECOVER_PENDING = sql
LEGACY_MESSAGES=CREATE_MESSAGES.replace(' attachment TEXT,','')

class AttachmentMigrationTests(unittest.TestCase):
    def test_v3_to_v4_preserves_text_and_adds_nullable_attachment_metadata(self):
        self.assertEqual(4, version, 'attachments need a lossless schema migration')
        db=sqlite3.connect(':memory:')
        db.execute("CREATE TABLE messages (peer_id TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,outgoing INTEGER NOT NULL,state TEXT NOT NULL,time INTEGER NOT NULL,received INTEGER NOT NULL,PRIMARY KEY(peer_id,id,outgoing))")
        db.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?)',('peer','id','old text',1,'delivered',1,2))
        export=subprocess.check_output(['java','-cp',classpath,'dev.ghost.nearbyim.storage.SchemaExport','3','4'],text=True)
        for line in export.splitlines(): db.execute(base64.b64decode(line).decode())
        self.assertEqual(('old text','delivered',None),db.execute('SELECT body,state,attachment FROM messages').fetchone())

class StoreTests(unittest.TestCase):
    def test_schema_supports_language_independent_status_upgrade(self):
        self.assertEqual(4, version, "attachment schema includes earlier language-independent status migration")

    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        # Exact v1 schema, including histories with no key binding.
        for statement in (CREATE_CONVERSATIONS, LEGACY_MESSAGES, CREATE_INDEX):
            self.db.execute(statement)
        self.db.execute('INSERT INTO conversations VALUES (?,?,?)', ('old', '旧朋友', 999999))
        self.db.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?)', ('old','first','你好',0,'',10,10))
        self.db.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?)', ('old','second','我的回复',1,'未确认',20,20))
        self.before = self.db.execute('SELECT * FROM messages ORDER BY rowid').fetchall()
        self.db.execute(CREATE_TRUST)

    def test_upgrade_preserves_all_v1_messages_without_granting_trust(self):
        tables = {row[0] for row in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertIn('trusted_devices', tables, 'v2 must create an independent trust table')
        self.assertEqual(self.before, self.db.execute('SELECT * FROM messages ORDER BY rowid').fetchall())
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM trusted_devices').fetchone()[0])

    def test_latest_saved_message_drives_summary_and_time(self):
        self.db.execute(TOUCH_INSERT, ('old','改名'))
        self.db.execute(TOUCH_UPDATE, ('改名','old'))
        row = self.db.execute(CONVERSATIONS).fetchone()
        self.assertEqual(('old','改名',20,'我的回复',1,'未确认'), row)
        self.db.execute('INSERT INTO conversations VALUES (?,?,?)', ('new','新朋友',99999999))
        self.db.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?)', ('new','m','最近收到',0,'',30,30))
        self.assertEqual(['new','old'], [row[0] for row in self.db.execute(CONVERSATIONS)])

    def test_equal_receive_times_use_latest_row(self):
        self.db.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?)', ('old','third','最后一条',0,'',25,20))
        self.assertEqual(('old','旧朋友',25,'最后一条',0,''), self.db.execute(CONVERSATIONS).fetchone())

    def test_trust_pin_cannot_be_silently_overwritten(self):
        self.db.execute(REMEMBER, ('old','key-a','旧朋友',40,2,'AA:BB:CC:DD:EE:FF'))
        self.db.execute(REMEMBER, ('old','key-b','冒名者',50,1,None))
        row = self.db.execute('SELECT public_key,name,last_connected,mode,bluetooth_address FROM trusted_devices').fetchone()
        self.assertEqual(('key-a','旧朋友',40,2,'AA:BB:CC:DD:EE:FF'), row)
        self.db.execute(REMEMBER, ('old','key-a','改名',60,1,None))
        self.assertEqual(('key-a','改名',60,1,'AA:BB:CC:DD:EE:FF'), self.db.execute('SELECT public_key,name,last_connected,mode,bluetooth_address FROM trusted_devices').fetchone())

    def test_clear_messages_preserves_trust_and_empty_summary(self):
        self.db.execute(REMEMBER, ('old','key-a','旧朋友',40,1,None))
        self.db.execute(CLEAR, ('old',))
        self.assertEqual(1, self.db.execute('SELECT COUNT(*) FROM trusted_devices').fetchone()[0])
        self.assertEqual(('old','旧朋友',0,'',0,''), self.db.execute(CONVERSATIONS).fetchone())

    def test_revoke_preserves_history_and_allows_deliberate_new_binding(self):
        self.db.execute(REMEMBER, ('old','key-a','旧朋友',40,1,None))
        self.db.execute(REVOKE, ('old',))
        self.assertEqual(self.before, self.db.execute('SELECT * FROM messages ORDER BY rowid').fetchall())
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM trusted_devices').fetchone()[0])
        self.db.execute(REMEMBER, ('old','key-b','重新认识',60,1,None))
        self.assertEqual('key-b', self.db.execute('SELECT public_key FROM trusted_devices').fetchone()[0])

class StatusMigrationTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        for statement in (CREATE_CONVERSATIONS, LEGACY_MESSAGES, CREATE_INDEX):
            self.db.execute(statement)
        self.db.execute('INSERT INTO conversations VALUES (?,?,?)', ('peer', 'Nickname 我的朋友', 123))
        for i, state in enumerate(('待确认', '已送达', '未确认', 'pending', 'delivered', 'unknown')):
            self.db.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?)',
                            ('peer', str(i), 'Unchanged body 待确认 ' + str(i), 1, state, 100 + i, 200 + i))
        self.db.execute('INSERT INTO messages VALUES (?,?,?,?,?,?,?)', ('peer', 'incoming', '待确认', 0, '', 10, 11))
        self.bodies = self.db.execute('SELECT peer_id,id,body,outgoing,time,received FROM messages ORDER BY rowid').fetchall()

    def upgrade(self, old, new=3):
        output = subprocess.check_output(['java', '-cp', classpath, 'dev.ghost.nearbyim.storage.SchemaExport', str(old), str(new)], text=True)
        for line in output.splitlines():
            self.db.execute(base64.b64decode(line).decode())

    def assert_preserved(self):
        self.assertEqual(self.bodies, self.db.execute('SELECT peer_id,id,body,outgoing,time,received FROM messages ORDER BY rowid').fetchall())
        self.assertEqual(('peer', 'Nickname 我的朋友', 123), self.db.execute('SELECT * FROM conversations').fetchone())
        self.assertEqual(['pending', 'delivered', 'unknown', 'pending', 'delivered', 'unknown', ''],
                         [row[0] for row in self.db.execute('SELECT state FROM messages ORDER BY rowid')])

    def test_v1_to_v3_chains_trust_creation_and_status_migration(self):
        self.upgrade(1)
        self.assert_preserved()
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM trusted_devices').fetchone()[0])

    def test_v2_to_v3_preserves_device_trust_and_bluetooth_address(self):
        self.db.execute(CREATE_TRUST)
        self.db.execute(REMEMBER, ('peer', 'pinned-key', 'Nickname', 42, 2, 'AA:BB:CC:DD:EE:FF'))
        trusted = self.db.execute('SELECT * FROM trusted_devices').fetchall()
        self.upgrade(2)
        self.assert_preserved()
        self.assertEqual(trusted, self.db.execute('SELECT * FROM trusted_devices').fetchall())

    def test_pending_recovery_after_upgrade_preserves_delivery_and_incoming_text(self):
        self.db.execute(CREATE_TRUST)
        self.upgrade(2)
        self.db.execute(RECOVER_PENDING)
        self.assertEqual(['unknown', 'delivered', 'unknown', 'unknown', 'delivered', 'unknown', ''],
                         [row[0] for row in self.db.execute('SELECT state FROM messages ORDER BY rowid')])
        self.assertEqual(self.bodies, self.db.execute('SELECT peer_id,id,body,outgoing,time,received FROM messages ORDER BY rowid').fetchall())

    def test_v1_to_v2_keeps_legacy_states_for_intermediate_upgrade(self):
        self.upgrade(1, 2)
        self.assertEqual('待确认', self.db.execute("SELECT state FROM messages WHERE id='0'").fetchone()[0])
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM trusted_devices').fetchone()[0])

unittest.main()
