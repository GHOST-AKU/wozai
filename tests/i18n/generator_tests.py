"""Exercise the source contract and generated resource encoding, without an Android SDK."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("i18n_generator", ROOT / "tools/generate-i18n.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((ROOT / "i18n/config.json").read_text())
        self.messages = {
            "zh-Hans": {"greeting": "我是 {0}", "deviceCount": "{0, plural, other {# 台设备}}"},
            "en": {"greeting": "I'm {0}", "deviceCount": "{0, plural, one {# device} other {# devices}}"},
        }

    def test_plural_categories_may_differ_but_argument_types_must_match(self):
        generator.validate(self.config, self.messages)
        self.messages["en"]["deviceCount"] = "{0} devices"
        with self.assertRaisesRegex(ValueError, "parameters"):
            generator.validate(self.config, self.messages)

    def test_argument_number_and_missing_translation_rejected(self):
        self.messages["en"]["greeting"] = "Hello {1}"
        with self.assertRaisesRegex(ValueError, "parameters"):
            generator.validate(self.config, self.messages)
        del self.messages["en"]["greeting"]
        with self.assertRaisesRegex(ValueError, "keys"):
            generator.validate(self.config, self.messages)

    def test_icu_quotes_nested_branches_and_literal_braces(self):
        self.assertEqual(generator.parse_parameters("I'm {0}"), {0: "text"})
        self.assertEqual(generator.parse_parameters("'{literal}' {0, plural, one {{1}} other {{1} '#'}}"),
                         {0: "plural", 1: "text"})
        for malformed in ("{0", "{nickname}", "{0, plural, one {hello}}", "{0, invalid}"):
            with self.subTest(malformed=malformed), self.assertRaises(ValueError):
                generator.parse_parameters(malformed)

    def test_duplicate_json_keys_resource_name_collisions_and_duplicate_locale_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            generator.read_json_text('{"greeting":"Hello","greeting":"Hi"}')
        for catalog in self.messages.values():
            catalog.update({"httpUrl": "URL", "httpURL": "URL"})
        with self.assertRaisesRegex(ValueError, "resource name"):
            generator.validate(self.config, self.messages)
        self.config["languages"].append(self.config["languages"][0])
        with self.assertRaisesRegex(ValueError, "language"):
            generator.validate(self.config, self.messages)

    def test_registry_metadata_cannot_silently_route_to_the_wrong_resources(self):
        self.config["languages"][0]["androidQualifier"] = "fr"
        with self.assertRaisesRegex(ValueError, "qualifier must match"):
            generator.validate(self.config, self.messages)
        self.config["languages"][0]["androidQualifier"] = "b+zh+hans"
        self.config["languages"][0]["tag"] = "zh-hans"
        self.config["sourceLanguage"] = "zh-hans"
        self.messages["zh-hans"] = self.messages.pop("zh-Hans")
        with self.assertRaisesRegex(ValueError, "canonical"):
            generator.validate(self.config, self.messages)

    def test_xml_properties_and_native_metadata_keep_unicode_and_escapes(self):
        value = '  @我在 & <NearbyIM> "hello" \\ path\nI\'m {0}  '
        for catalog in self.messages.values():
            catalog["escapedText"] = value
        result = generator.outputs(self.config, self.messages)
        android = ET.fromstring(result["app/src/main/res/values/strings.xml"])
        node = android.find("string[@name='escaped_text']")
        self.assertEqual(node.get("formatted"), "false")
        self.assertIn("\\'", node.text)
        self.assertIn("\\n", node.text)
        self.assertIn("\\\\ path", node.text)
        self.assertEqual(ET.fromstring(result["app/src/main/res/xml/locales_config.xml"])[0].get(
            "{http://schemas.android.com/apk/res/android}name"), "zh-Hans")
        props = result["desktop/src/main/resources/dev/ghost/wozai/Strings_en.properties"]
        self.assertIn("escapedText=\\ \\ @我在", props)

    def test_check_detects_missing_changed_and_orphaned_generated_outputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "i18n/messages").mkdir(parents=True)
            (root / "i18n/config.json").write_text(json.dumps(self.config))
            for language, catalog in self.messages.items():
                (root / f"i18n/messages/{language}.json").write_text(json.dumps(catalog))
            with self.assertRaisesRegex(ValueError, "generated"):
                generator.run(root, check=True)
            generator.run(root, check=False)
            generator.run(root, check=True)
            generated = root / "app/src/main/res/values/strings.xml"
            generated.write_text("<resources/>")
            with self.assertRaisesRegex(ValueError, "generated"):
                generator.run(root, check=True)
            generator.run(root, check=False)
            orphan = root / "desktop/src/main/resources/dev/ghost/wozai/Strings_fr.properties"
            orphan.write_text(generator.HEADER + "\ngreeting=Bonjour\n")
            with self.assertRaisesRegex(ValueError, "orphan"):
                generator.run(root, check=True)

    def test_windows_git_checkout_line_endings_are_not_translation_drift(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "i18n/messages").mkdir(parents=True)
            (root / "i18n/config.json").write_text(json.dumps(self.config))
            for language, catalog in self.messages.items():
                (root / f"i18n/messages/{language}.json").write_text(json.dumps(catalog))
            generator.run(root, check=False)
            for relative in generator.outputs(self.config, self.messages):
                path = root / relative
                path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
            generator.run(root, check=True)


if __name__ == "__main__":
    unittest.main()
