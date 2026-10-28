"""Check release identity, native version constraints and drift without platform builds."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release_version", ROOT / "tools/version.py")
version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version)


class VersionTests(unittest.TestCase):
    def test_preview_and_stable_mapping(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "i18n").mkdir()
            config = root / "i18n/config.json"
            for name, debian, channel in [("0.3.3-preview.2", "0.3.3~preview.2", "preview"),
                                           ("0.3.3", "0.3.3", "stable")]:
                config.write_text(json.dumps({"appVersion": name, "buildNumber": 13}), encoding="utf-8")
                info = version.metadata(root)
                self.assertEqual(info["version"], name)
                self.assertEqual(info["base_version"], "0.3.3")
                self.assertEqual(info["debian_version"], debian)
                self.assertEqual(info["tag"], "v" + name)
                self.assertEqual(info["channel"], channel)
            for name in ["0.3", "0.3.3-preview.0", "0.3.3-preview.02", "0.3.3 preview", "0.3.3-beta.1"]:
                config.write_text(json.dumps({"appVersion": name, "buildNumber": 13}), encoding="utf-8")
                with self.subTest(name=name), self.assertRaises(ValueError):
                    version.metadata(root)

    def test_checked_in_platforms_and_drift(self):
        info, changed = version.synchronize(check=True)
        self.assertFalse(changed)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ["i18n/config.json", "app/build.gradle.kts", "terminal/Cargo.toml",
                             "terminal/Cargo.lock", "apple/NearbyIM.xcodeproj/project.pbxproj"]:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())
            config = root / "i18n/config.json"
            data = json.loads(config.read_text(encoding="utf-8"))
            data.update(appVersion="0.3.3-preview.3", buildNumber=info["build_number"] + 1)
            config.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Version drift"):
                version.synchronize(root, check=True)
            _, changed = version.synchronize(root)
            self.assertEqual(len(changed), 4)
            version.synchronize(root, check=True)
            project = (root / "apple/NearbyIM.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
            self.assertEqual(project.count('WOZAI_VERSION = "0.3.3-preview.3";'), 2)
            self.assertEqual(project.count('MARKETING_VERSION = 0.3.3;'), 2)


if __name__ == "__main__":
    unittest.main()
