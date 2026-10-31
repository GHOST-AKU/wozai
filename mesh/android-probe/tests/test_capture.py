import importlib.util
import json
from pathlib import Path
import unittest


SOURCE = Path(__file__).resolve().parents[2] / "tools/android-capture.py"


class CaptureTests(unittest.TestCase):
    def load_parser(self):
        self.assertTrue(SOURCE.exists(), "The structured capture implementation is missing")
        spec = importlib.util.spec_from_file_location("android_capture", SOURCE)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.parse_event

    def test_preserves_permission_failure_without_raw_device_diagnostics(self):
        parse_event = self.load_parser()
        event = {"schema": 1, "session": "c369c7e9-3c86-4eae-b450-8f706c70050c",
                 "event": "operation.error", "elapsed_ms": 7,
                 "operation": "wifi.group_info", "exception": "SecurityException"}
        lines = ["private adb diagnostic: MAC=01:02:03:04:05:06",
                 "INSTRUMENTATION_STATUS: mesh_event=" + json.dumps(event),
                 "INSTRUMENTATION_RESULT: stream=private output"]
        self.assertEqual([e for line in lines if (e := parse_event(line)) is not None], [event])

    def test_rejects_invalid_or_unversioned_events(self):
        parse_event = self.load_parser()
        for line in ["INSTRUMENTATION_STATUS: mesh_event={broken",
                     "INSTRUMENTATION_STATUS: mesh_event=[]",
                     'INSTRUMENTATION_STATUS: mesh_event={"event":"run.end"}']:
            self.assertIsNone(parse_event(line))


if __name__ == "__main__":
    unittest.main()
