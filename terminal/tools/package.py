#!/usr/bin/env python3
"""Package only the native executable, documentation, license and completions."""
import hashlib
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tomllib
import zipfile

root = Path(__file__).resolve().parents[1]
version = tomllib.loads((root / "Cargo.toml").read_text())["package"]["version"]
windows = platform.system() == "Windows"
name = f"nearbyim-terminal-{version}-{'windows' if windows else 'linux'}-x64"
dist = root / "target" / "dist"
stage = root / "target" / "package" / name
stage.mkdir(parents=True, exist_ok=True)
dist.mkdir(parents=True, exist_ok=True)
exe = root / "target" / "release" / ("nearbyim.exe" if windows else "nearbyim")
shutil.copy2(exe, stage / exe.name)
for source, target in [(root / "README.md", "README.md"), (root / "README.zh-CN.md", "README.zh-CN.md"), (root / "../LICENSE", "LICENSE"), (root / "THIRD_PARTY.md", "THIRD_PARTY.md")]:
    shutil.copy2(source, stage / target)
subprocess.run(["python", str(root / "tools" / "licenses.py")], check=True)
shutil.copytree(root / "target" / "licenses", stage / "licenses", dirs_exist_ok=True)
for shell in ["bash", "zsh", "fish", "powershell"]:
    output = subprocess.check_output([str(exe), "completions", shell])
    (stage / f"nearbyim.{shell}").write_bytes(output)
if windows:
    archive = dist / f"{name}.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as package:
        for file in sorted(stage.rglob("*")):
            if file.is_file():
                package.write(file, f"{name}/{file.relative_to(stage).as_posix()}")
else:
    archive = dist / f"{name}.tar.gz"
    with tarfile.open(archive, "w:gz") as package:
        package.add(stage, arcname=name)
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
(archive.with_name(archive.name + ".sha256")).write_text(f"{digest}  {archive.name}\n", encoding="ascii")
print(f"{archive.name}: {archive.stat().st_size} bytes; executable: {exe.stat().st_size} bytes; SHA-256 {digest}")
