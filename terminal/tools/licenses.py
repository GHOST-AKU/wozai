#!/usr/bin/env python3
"""Inventory locked crate licenses and retain upstream license/notice files in packages."""
import json
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--manifest-path", str(root / "Cargo.toml"), "--locked", "--format-version", "1"], text=True))
destination = root / "target" / "licenses"
destination.mkdir(parents=True, exist_ok=True)
records = []
for package in sorted(metadata["packages"], key=lambda p: (p["name"], p["version"])):
    if package["name"] == "nearbyim-terminal":
        continue
    directory = Path(package["manifest_path"]).parent
    folder = destination / f'{package["name"]}-{package["version"]}'
    folder.mkdir(exist_ok=True)
    names = []
    for path in sorted(directory.iterdir()):
        if path.is_file() and path.name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE", "UNLICENSE")):
            shutil.copy2(path, folder / path.name)
            names.append(path.name)
    if package.get("license_file"):
        path = directory / package["license_file"]
        if path.is_file():
            shutil.copy2(path, folder / path.name)
            names.append(path.name)
    records.append({"name": package["name"], "version": package["version"], "license": package.get("license"), "license_files": sorted(set(names)), "repository": package.get("repository")})
(destination / "inventory.json").write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Retained license inventory for {len(records)} locked crates. Review inventory before a public release.")
