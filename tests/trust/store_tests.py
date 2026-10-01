import base64
import sqlite3
import subprocess
import sys
import unittest

sql = [base64.b64decode(line).decode() for line in subprocess.check_output(
    ['java', '-cp', sys.argv.pop(1), 'dev.ghost.nearbyim.storage.SchemaExport'], text=True).splitlines()]
CREATE_CONVERSATIONS, CREATE_MESSAGES, CREATE_INDEX, CREATE_TRUST, CONVERSATIONS, REMEMBER, REVOKE, CLEAR, TOUCH_INSERT, TOUCH_UPDATE = sql

class StoreTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        # Exact v1 schema, including histories with no key binding.
        for statement in (CREATE_CONVERSATIONS, CREATE_MESSAGES, CREATE_INDEX):
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

unittest.main()
