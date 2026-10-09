#!/usr/bin/env python3
"""Opt-in Linux virtual GUI memory trend; disposable profiles, no message/key dumps."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import tempfile
import time

import performance

ROOT = Path(__file__).resolve().parents[2]


def heap(pid):
    result = subprocess.run(["jstat", "-gc", str(pid)], text=True, capture_output=True, check=True, timeout=10)
    lines = result.stdout.strip().splitlines()
    values = dict(zip(lines[0].split(), map(float, lines[1].split())))
    return {"used_heap_bytes": int(1024 * sum(values[name] for name in ("S0U", "S1U", "EU", "OU"))),
            "young_gc": values["YGC"], "full_gc": values["FGC"], "gc_seconds": values["GCT"]}


def one(args, phase, index):
    processes, handles = [], []
    with tempfile.TemporaryDirectory(prefix=f"wozai-memory-{phase}-{index}-", dir=args.work_dir) as temporary:
        directory = Path(temporary)
        env = os.environ.copy()
        def launch(command, name):
            log = (args.output.parent / f"{phase}-{index}-{name}.log").open("w")
            handles.append(log)
            process = subprocess.Popen(list(map(str, command)), env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            return process
        try:
            display = 140 + index * 2 + (phase == "connected")
            display_file = Path(f"/tmp/.X11-unix/X{display}")
            if display_file.exists():
                raise RuntimeError("Benchmark display is occupied")
            xvfb = launch(["Xvfb", f":{display}", "-screen", "0", "1280x1024x24", "-nolisten", "tcp"], "xvfb")
            end = time.monotonic() + 10
            while not display_file.exists():
                if xvfb.poll() is not None or time.monotonic() >= end:
                    raise RuntimeError("Benchmark display did not start")
                time.sleep(.05)
            env["DISPLAY"] = f":{display}"
            cp = os.pathsep.join([str(ROOT / "desktop/build/classes"), str(ROOT / "desktop/build/tests"), str(ROOT / "desktop/build/lib/*")])
            base = ["java", "-Xmx256m", "-cp", cp]
            peers = []
            if phase == "idle":
                marker = directory / "ready.json"
                target = launch(base[:1] + [f"-Dwozai.dataDir={directory / 'profile'}"] + base[1:] + ["dev.ghost.wozai.Main", "--performance-ready-file", marker], "target")
                performance.wait_json(marker, [target])
            else:
                peer = launch(base[:1] + ["-Djava.awt.headless=true"] + base[1:] + ["dev.ghost.wozai.PerformanceScenario", "peer", directory], "peer")
                peers.append(peer)
                identity = performance.wait_json(directory / "peer.json", [peer])
                target = launch(base + ["dev.ghost.wozai.PerformanceScenario", "target", directory, identity["endpoint"], identity["id"]], "target")
                performance.wait_json(directory / "phase.json", [target, peer], "connected", timeout=70)
                (directory / "command.txt").write_text("connected")
            tree = performance.Tree(target.pid)
            settle_end = time.monotonic() + args.settle
            while time.monotonic() < settle_end:
                tree.reading()
                time.sleep(.5)
            heap_start = heap(target.pid)
            start = time.monotonic()
            points, heaps = [], [{"elapsed_s": 0, **heap_start}]
            _, previous_cpu = tree.reading()
            previous_time = start
            while time.monotonic() - start < args.seconds:
                time.sleep(min(1, max(0, args.seconds - (time.monotonic() - start))))
                if any(process.poll() is not None for process in [target, *peers]):
                    raise RuntimeError("Measured client/peer exited")
                rss, cpu = tree.reading()
                now = time.monotonic()
                elapsed = now - start
                delta = sum(max(0, value - previous_cpu.get(identity, 0)) for identity, value in cpu.items())
                process = performance.psutil.Process(target.pid)
                points.append({"elapsed_s": round(elapsed, 3), "rss_bytes": rss,
                               "cpu_percent_one_core": 100 * delta / (now - previous_time),
                               "threads": process.num_threads(), "file_descriptors": process.num_fds()})
                previous_cpu, previous_time = cpu, now
                if elapsed - heaps[-1]["elapsed_s"] >= 60:
                    heaps.append({"elapsed_s": elapsed, **heap(target.pid)})
            heaps.append({"elapsed_s": time.monotonic() - start, **heap(target.pid)})
            if phase == "idle":
                marker.with_name(marker.name + ".stop").write_text("stop")
            else:
                (directory / "command.txt").write_text("stop")
            for process in [target, *peers]:
                if process.wait(timeout=35) != 0:
                    raise RuntimeError("Client shutdown failed")
            edge = min(300, args.seconds / 3)
            return {"phase": phase, "round": index, "duration_s": points[-1]["elapsed_s"],
                    "rss_change_edge_medians_bytes": statistics.median(p["rss_bytes"] for p in points if p["elapsed_s"] >= args.seconds - edge) - statistics.median(p["rss_bytes"] for p in points if p["elapsed_s"] <= edge),
                    "samples": points, "heap_samples": heaps, "verified_normal_shutdown": True}
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=5)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
            for handle in handles:
                handle.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=float, default=1800)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--settle", type=float, default=30)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    args = parser.parse_args()
    if args.seconds < 3 or not 1 <= args.rounds <= 3 or args.settle < 0:
        parser.error("seconds >= 3, rounds in 1..3 and settle >= 0 are required")
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.work_dir.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    for path in sorted((ROOT / "desktop/build/classes").rglob("*.class")):
        digest.update(path.relative_to(ROOT).as_posix().encode() + b"\0" + path.read_bytes())
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        futures = [executor.submit(one, args, phase, index) for phase in ["idle", "connected"] for index in range(1, args.rounds + 1)]
        reports = [future.result() for future in futures]
    report = {"source_commit": commit + ("+dirty" if dirty else ""), "class_artifact_sha256": digest.hexdigest(), "environment_kind": "virtual",
              "limitations": "Linux x64 compiled client GUI in isolated Xvfb displays; all rounds run concurrently. jstat used heap is not post-GC live heap. No phone, Windows, physical network, thermal or release acceptance.", "rounds": reports}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Verified {len(reports)} independent client runs; report: {args.output}", flush=True)


if __name__ == "__main__":
    main()
