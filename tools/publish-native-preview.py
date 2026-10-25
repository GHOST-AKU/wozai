#!/usr/bin/env python3
"""Publish this explicitly scoped preview using the repository's Actions token."""
import hashlib
import json
from pathlib import Path
import subprocess

TAG = "v0.3.3-preview.1"
SOURCE = "ee69718ac8c298c32d6d28028e7c1b060e6e9dfd"
TERMINAL_SOURCE = "137e7eaa486a6fa92441e86ec50472c4632ad311"
root = Path("release-assets")


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


for run_id, expected in [(40194947256, SOURCE), (40194520152, TERMINAL_SOURCE)]:
    run = json.loads(gh("api", f"repos/{{owner}}/{{repo}}/actions/runs/{run_id}"))
    assert run["head_sha"] == expected
    jobs = json.loads(gh("api", f"repos/{{owner}}/{{repo}}/actions/runs/{run_id}/jobs"))["jobs"]
    wanted = [j for j in jobs if j["name"] == "apple"] if run_id == 40194947256 else [j for j in jobs if j["name"].startswith("terminal (")]
    assert len(wanted) == (1 if run_id == 40194947256 else 2)
    assert all(j["conclusion"] == "success" for j in wanted)

assert (root / "SOURCE_COMMIT.txt").read_text().strip() == SOURCE
for checksum in [root / "SHA256SUMS-apple.txt", *root.glob("*.sha256")]:
    for line in checksum.read_text().splitlines():
        expected, name = line.split(None, 1)
        assert Path(name).name == name
        assert digest(root / name) == expected

archives = sorted(p for p in root.iterdir() if p.name.endswith((".ipa", ".zip", ".tar.gz")))
assert {p.name for p in archives} == {
    "NearbyIM-0.3.3-preview.1-ios-ipados-unsigned.ipa",
    "NearbyIM-0.3.3-preview.1-macos-universal-adhoc.zip",
    "nearbyim-terminal-0.1.0-dev-linux-x64.tar.gz",
    "nearbyim-terminal-0.1.0-dev-windows-x64.zip",
}
proof = {"release": TAG, "source_commit": SOURCE, "apple_run": 40194947256,
         "terminal_run": 40194520152, "terminal_source_commit": TERMINAL_SOURCE,
         "assets": [{"name": p.name, "size": p.stat().st_size, "sha256": digest(p)} for p in archives]}
(root / "BUILD_PROVENANCE.json").write_text(json.dumps(proof, indent=2) + "\n")
(root / "SHA256SUMS.txt").write_text("".join(
    digest(p) + "  " + p.name + "\n" for p in sorted(root.iterdir()) if p.is_file() and p.name != "SHA256SUMS.txt"))

releases = json.loads(gh("api", "repos/{owner}/{repo}/releases?per_page=100"))
release = next(r for r in releases if r["tag_name"] == TAG)
assert release["draft"] and release["prerelease"] and release["target_commitish"] == SOURCE
files = sorted(p for p in root.iterdir() if p.is_file())
subprocess.run(["gh", "release", "upload", TAG, "--clobber", *map(str, files)], check=True)
uploaded = json.loads(gh("api", f"repos/{{owner}}/{{repo}}/releases/{release['id']}/assets"))
assert {a["name"] for a in uploaded} == {p.name for p in files}
for asset in uploaded:
    path = root / asset["name"]
    assert asset["size"] == path.stat().st_size
    assert asset["digest"] == "sha256:" + digest(path)
subprocess.run(["gh", "release", "edit", TAG, "--draft=false", "--prerelease", "--latest=false"], check=True)
published = json.loads(gh("api", f"repos/{{owner}}/{{repo}}/releases/{release['id']}"))
assert not published["draft"] and published["prerelease"]
latest = json.loads(gh("api", "repos/{owner}/{repo}/releases/latest"))
assert latest["tag_name"] == "v0.3.2"
print(published["html_url"])
