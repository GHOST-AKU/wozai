#!/usr/bin/env python3
"""Validate native UI literal coverage and complete supported language resources without Xcode."""
import json
import plistlib
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
locales = {"en", "zh-Hans", "zh-Hant", "ja", "ko"}
catalog = json.loads((root / "apple/App/Localizable.xcstrings").read_text(encoding="utf-8"))
keys = set()
for source in [*(root / "apple/App").glob("*.swift"), *(root / "apple/Sources/WozaiCore").glob("*.swift")]:
    text = source.read_text(encoding="utf-8")
    for pattern in [r'(?:Button|Label|Text|Section|ContentUnavailableView|Picker|TextField|LabeledContent|navigationTitle|accessibilityLabel|help|LocalizedStringKey)\("([^"\\]+)"', r'String\(localized: "([^"\\]+)"']:
        keys.update(re.findall(pattern, text))
missing = keys - catalog["strings"].keys()
assert not missing, f"Native UI strings missing from catalog: {sorted(missing)}"
for key, item in catalog["strings"].items():
    for locale in locales:
        unit = item.get("localizations", {}).get(locale, {}).get("stringUnit", {})
        assert unit.get("state") == "translated" and unit.get("value"), f"Missing {locale}: {key}"
info = plistlib.loads((root / "apple/App/Info.plist").read_bytes())
assert set(info["CFBundleLocalizations"]) == locales
for locale in locales:
    text = (root / f"apple/App/{locale}.lproj/InfoPlist.strings").read_text(encoding="utf-8")
    for key in ["CFBundleDisplayName", "NSLocalNetworkUsageDescription", "NSCameraUsageDescription"]:
        assert re.search(r'"' + re.escape(key) + r'"\s*=\s*"[^"\n]+";', text), f"Missing native permission string {locale}: {key}"
print(f"PASS: {len(catalog['strings'])} native strings in five languages, UI literal coverage and localized permission descriptions")
