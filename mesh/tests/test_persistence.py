"""A storage failure is a local fatal error, never an untrusted peer rejection."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_lab import Node

class PersistenceFailureTests(unittest.TestCase):
    def test_non_io_checkpoint_error_stops_before_display_or_forward(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);nodes=[]
            try:
                b=Node(root/'b',relay=True);nodes.append(b)
                a=Node(root/'a',peers=[b.address]);nodes.append(a)
                a.wait('neighbor_up')
                fixture=root/'fixture';fixture.write_text('test-only fixture')
                (root/'b'/'seen.tmp').symlink_to(fixture)
                a.command('send',text='must not display without durable replay state')
                mid=a.wait('queued')['id']
                try:code=b.p.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    self.fail('receiver continued after a local checkpoint failure')
                self.assertNotEqual(code,0)
                b.thread.join(timeout=1)
                self.assertFalse(any(e.get('event')=='message' and e.get('id')==mid for e in b.snapshot()))
                self.assertEqual(fixture.read_text(),'test-only fixture')
            finally:
                for node in reversed(nodes):node.close()

if __name__=='__main__':unittest.main()
