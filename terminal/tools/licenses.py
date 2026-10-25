#!/usr/bin/env python3
"""Inventory locked crate licenses and retain upstream license/notice files in packages."""
import json
import hashlib
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
host=next(line.split(": ",1)[1] for line in subprocess.check_output(["rustc","-vV"],text=True).splitlines() if line.startswith("host: "))
metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--manifest-path", str(root / "Cargo.toml"), "--locked", "--format-version", "1", "--filter-platform", host], text=True))
destination = root / "target" / "licenses"
if destination.exists():
    shutil.rmtree(destination)
destination.mkdir(parents=True, exist_ok=True)
nodes={node["id"]:node for node in metadata["resolve"]["nodes"]}
selected=set()
pending=[metadata["resolve"]["root"]]
while pending:
    package_id=pending.pop()
    if package_id in selected:
        continue
    selected.add(package_id)
    pending.extend(dep["pkg"] for dep in nodes[package_id]["deps"] if any(kind["kind"]!="dev" for kind in dep["dep_kinds"]))
records = []
fallback_root=root / "vendor-licenses"
fallbacks=json.loads((fallback_root / "sources.json").read_text(encoding="utf-8"))
for package in sorted(metadata["packages"], key=lambda p: (p["name"], p["version"])):
    if package["name"] == "nearbyim-terminal" or package["id"] not in selected:
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
    if not names:
        key=f"{package['name']}-{package['version']}"
        retained=fallbacks.get(key)
        if not retained:
            raise RuntimeError(f"Missing upstream license text for shipped crate {key}")
        vcs=json.loads((directory / ".cargo_vcs_info.json").read_text(encoding="utf-8"))
        if vcs["git"]["sha1"] != retained["source_revision"]:
            raise RuntimeError(f"License source revision mismatch for {key}")
        for name, proof in retained["files"].items():
            source=fallback_root / key / name
            if hashlib.sha256(source.read_bytes()).hexdigest()!=proof["sha256"]:
                raise RuntimeError(f"Retained license checksum mismatch for {key}/{name}")
            shutil.copy2(source,folder / name)
            names.append(name)
        (folder / "source.json").write_text(json.dumps(retained,indent=2)+"\n",encoding="utf-8")
    records.append({"name": package["name"], "version": package["version"], "license": package.get("license"), "license_files": sorted(set(names)), "repository": package.get("repository")})
(destination / "inventory.json").write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Retained license inventory for {len(records)} locked crates. Review inventory before a public release.")
