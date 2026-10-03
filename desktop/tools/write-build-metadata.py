#!/usr/bin/env python3
"""Embed the source revision in the desktop JAR, before packaging."""
import datetime
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
output = Path(sys.argv[1])
output.parent.mkdir(parents=True, exist_ok=True)
metadata = {
    "version": json.loads((root / "i18n/config.json").read_text(encoding="utf-8"))["appVersion"],
    "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
    "working_tree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip()),
    "built_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
output.write_text(json.dumps(metadata) + "\n", encoding="utf-8")
