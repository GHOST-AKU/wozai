#!/usr/bin/env python3
"""Opt-in desktop baseline. Python 3 + psutil==7.2.2; never collect chat content."""
import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import tempfile
import time
import zipfile

import psutil

ROOT = Path(__file__).resolve().parents[2]


def wait_json(path, processes, expected=None, timeout=45):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f"Measured process exited ({process.returncode}); inspect benchmark logs")
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
            if expected is None or value.get("phase") == expected:
                return value
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        time.sleep(0.02)
    raise RuntimeError(f"Timeout waiting for {path.name}; inspect benchmark logs")


class Tree:
    """Retain descendant identities even if a launcher exits or reparents them."""
    def __init__(self, pid):
        self.known = {}
        self.add(psutil.Process(pid))

    def add(self, process):
        self.known[(process.pid, process.create_time())] = process

    def reading(self):
        for process in list(self.known.values()):
            try:
                for child in process.children(recursive=True):
                    self.add(child)
            except psutil.NoSuchProcess:
                pass
        rss, cpu, alive = 0, {}, 0
        for identity, process in list(self.known.items()):
            try:
                if not process.is_running() or process.status() == psutil.STATUS_ZOMBIE:
                    continue
                usage = process.cpu_times()
                rss += process.memory_info().rss
                cpu[identity] = usage.user + usage.system
                alive += 1
            except psutil.NoSuchProcess:
                pass
        if not alive:
            raise RuntimeError("Measured process tree exited; refusing a zero resource result")
        return rss, cpu


def sample(tree, settle, seconds):
    end = time.monotonic() + settle
    while time.monotonic() < end:
        tree.reading()
        time.sleep(min(0.2, max(0, end - time.monotonic())))
    _, previous = tree.reading()
    start = previous_time = time.monotonic()
    points, consumed = [], 0.0
    while True:
        remaining = start + seconds - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(0.2, remaining))
        rss, cpu = tree.reading()
        now = time.monotonic()
        if now <= previous_time:
            continue
        delta = sum(max(0, value - previous.get(identity, 0)) for identity, value in cpu.items())
        consumed += delta
        points.append({"elapsed_s": round(now - start, 4), "rss_bytes": rss,
                       "cpu_percent_one_core": round(100 * delta / (now - previous_time), 4)})
        previous, previous_time = cpu, now
    rss_values = sorted(point["rss_bytes"] for point in points)
    return {"settle_s": settle, "duration_s": round(previous_time - start, 4),
            "rss_median_bytes": statistics.median(rss_values),
            "rss_p95_bytes": rss_values[math.ceil(len(rss_values) * .95) - 1],
            "cpu_mean_percent_one_core": round(100 * consumed / (previous_time - start), 4),
            "samples": points}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def logical_bytes(directory):
    return sum(path.stat().st_size for path in directory.rglob("*") if path.is_file())


def environment(args):
    model = platform.processor()
    if platform.system() == "Linux":
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                model = line.partition(":")[2].strip()
                break
    elif platform.system() == "Windows":
        import winreg
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
            model = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    return {"timestamp": dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).isoformat(),
            "environment_kind": args.environment_kind, "device": args.device, "notes": args.notes,
            "os": platform.platform(), "architecture": platform.machine(), "cpu": model,
            "logical_cpus": psutil.cpu_count(), "physical_cpus": psutil.cpu_count(logical=False),
            "system_memory_bytes": psutil.virtual_memory().total, "python": platform.python_version(),
            "psutil": psutil.__version__, "source_commit": commit, "working_tree_dirty": dirty,
            "display": args.dpi or "see startup JVM scale metadata; attach mode requires a manual DPI note"}


def terminate(process):
    if process.poll() is None:
        tree = psutil.Process(process.pid)
        descendants = tree.children(recursive=True)
        for child in reversed(descendants):
            try:
                child.kill()
            except psutil.NoSuchProcess:
                pass
        process.kill()
        process.wait(timeout=15)


def measure(args, result):
    image = args.image.resolve()
    windows = platform.system() == "Windows"
    app = image / ("app" if windows else "lib/app")
    runtime = image / ("runtime" if windows else "lib/runtime")
    launcher = image / ("NearbyIM.exe" if windows else "bin/NearbyIM")
    java = runtime / ("bin/java.exe" if windows else "bin/java")
    jar = app / "nearbyim-desktop.jar"
    for required in (launcher, java, jar, args.test_classes):
        if not required.exists():
            raise RuntimeError(f"Required build output missing: {required}")
    flavor = "windows" if windows else "linux"
    packages = sorted(image.parent.parent.glob(f"NearbyIM-*-{flavor}-*.*"))
    result["distribution"] = {"image_logical_bytes": logical_bytes(image),
        "runtime_logical_bytes": logical_bytes(runtime), "application_jar_sha256": sha256(jar),
        "packages": [{"name": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)} for path in packages]}
    with zipfile.ZipFile(jar) as archive:
        result["distribution"]["build"] = json.loads(archive.read("dev/ghost/wozai/build-info.json"))
    result["method"] = {"startup": "native packaged launcher to painted visible GUI plus EDT round trip; 20 ms polling",
        "first_launch": "fresh disposable profile, OS page cache uncontrolled; NOT true cold boot",
        "warm_launch": "same profile, fresh JVM; OS cache may be warm",
        "resources": "RSS sum and user+system CPU for target process tree; one CPU core = 100%; peer excluded",
        "lan": "same-host private IPv4 TCP, signed protocol, persisted trust/receipts and actual GUI; lab helper overhead included",
        "workload": "256 UTF-8 bytes, up to 5 messages/s per direction, new empty history",
        "bluetooth": "unavailable in automated TCP lab; use sample on a real adapter",
        "thresholds": "none; first baseline"}
    logs = args.output.resolve().with_suffix(".logs")
    logs.mkdir(parents=True, exist_ok=True)
    processes = []
    handles = []
    def launch(command, label, env=None):
        handle = (logs / f"{label}.txt").open("w", encoding="utf-8")
        handles.append(handle)
        process = subprocess.Popen([str(part) for part in command], stdout=handle, stderr=handle, env=env)
        processes.append(process)
        return process
    try:
        with tempfile.TemporaryDirectory(prefix="nearbyim-performance-") as temporary:
            directory = Path(temporary)
            profile = directory / "startup-profile"
            env = os.environ.copy()
            # Set only the benchmark's data location; no normal profile or portable image is changed.
            option = f'"-Dwozai.dataDir={profile}"'
            if '"' in str(profile):
                raise RuntimeError("Temporary profile path cannot contain a quote")
            env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "") + " " + option
            launches = []
            for index in range(args.startup_runs):
                marker = directory / f"startup-{index}.json"
                start = time.monotonic()
                process = launch([launcher, "--performance-ready-file", marker], f"startup-{index}", env)
                tree = Tree(process.pid)
                metadata = wait_json(marker, [process])
                launches.append(round((time.monotonic() - start) * 1000, 3))
                print(f"Startup {index + 1}/{args.startup_runs}: {launches[-1]:.1f} ms", flush=True)
                if index == args.startup_runs - 1:
                    print("Sampling Idle", flush=True)
                    result["idle"] = sample(tree, args.settle, args.seconds)
                marker.with_name(marker.name + ".stop").write_text("stop", encoding="utf-8")
                if process.wait(timeout=30) != 0:
                    raise RuntimeError("Packaged GUI shutdown failed")
            result["startup"] = {"first_fresh_profile_ms": launches[0], "warm_ms": launches[1:],
                "warm_median_ms": statistics.median(launches[1:]), "jvm": metadata}
            cp = str(app / "*") + os.pathsep + str(args.test_classes.resolve())
            scenario = directory / "lan"
            scenario.mkdir()
            peer = launch([java, "-Djava.awt.headless=true", "-cp", cp,
                           "dev.ghost.wozai.PerformanceScenario", "peer", scenario], "lan-peer")
            peer_info = wait_json(scenario / "peer.json", [peer])
            target = launch([java, "-cp", cp, "dev.ghost.wozai.PerformanceScenario", "target", scenario,
                             peer_info["endpoint"], peer_info["id"]], "lan-target")
            tree = Tree(target.pid)
            wait_json(scenario / "scenario-ready.json", [target, peer])
            connection = wait_json(scenario / "phase.json", [target, peer], "connected", timeout=60)
            result["lan_connection"] = {key: (float(value) if value != "unavailable" else None)
                                        for key, value in connection.items() if key != "phase"}
            print("Sampling Connected / LAN", flush=True)
            result["connected_lan"] = sample(tree, args.settle, args.seconds)
            command = scenario / "command.txt"
            def send_command(value):
                staging = command.with_suffix(".tmp")
                staging.write_text(value, encoding="utf-8")
                staging.replace(command)
            send_command("active")
            wait_json(scenario / "phase.json", [target, peer], "active")
            print("Sampling Active / LAN", flush=True)
            result["active_lan"] = sample(tree, 2, args.seconds)
            send_command("report")
            traffic = wait_json(scenario / "traffic.json", [target, peer])
            traffic = {key: int(value) for key, value in traffic.items()}
            if not traffic["sent"] or not traffic["received"] or traffic["sent"] != traffic["delivered"]:
                raise RuntimeError("Bidirectional traffic or persisted receipts failed")
            result["lan_traffic"] = traffic
            send_command("reconnect")
            reconnect = wait_json(scenario / "phase.json", [target, peer], "reconnected")
            result["lan_connection"]["reconnect_ms"] = float(reconnect["reconnect_ms"])
            send_command("stop")
            for process in (target, peer):
                if process.wait(timeout=30) != 0:
                    raise RuntimeError("LAN scenario shutdown failed")
    finally:
        for process in reversed(processes):
            terminate(process)
        for handle in handles:
            handle.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    for name in ("measure", "sample"):
        command = sub.add_parser(name)
        command.add_argument("--output", type=Path, required=True)
        command.add_argument("--environment-kind", choices=["ci", "virtual", "physical"], required=True)
        command.add_argument("--device", required=True, help="Device/model and display/session description")
        command.add_argument("--notes", default="")
        command.add_argument("--dpi", default="")
        command.add_argument("--settle", type=float, default=30)
        command.add_argument("--seconds", type=float, default=30)
        if name == "measure":
            command.add_argument("--image", type=Path, required=True)
            command.add_argument("--test-classes", type=Path, required=True)
            command.add_argument("--startup-runs", type=int, default=5)
        else:
            command.add_argument("--pid", type=int, required=True)
            command.add_argument("--phase", choices=["idle", "connected", "active"], required=True)
            command.add_argument("--transport", choices=["none", "lan", "bluetooth"], required=True)
    args = parser.parse_args()
    if args.seconds < 1 or args.settle < 0 or (args.mode == "measure" and args.startup_runs < 2):
        parser.error("seconds must be >= 1, settle >= 0 and startup-runs >= 2")
    if args.mode == "sample" and ((args.phase == "idle") != (args.transport == "none")):
        parser.error("idle requires transport none; connected/active require lan or bluetooth")
    result = {"schema": 1, "status": "running", "environment": environment(args)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        if args.mode == "measure":
            measure(args, result)
        else:
            result["phase"], result["transport"] = args.phase, args.transport
            result["method"] = "Attach-only RSS/process-tree CPU; never stop the user's process; record exact build/JRE/DPI/workload in notes"
            result["resources"] = sample(Tree(args.pid), args.settle, args.seconds)
        result["status"] = "complete"
    except Exception as error:
        result["status"], result["error"] = "failed", str(error)
        raise
    finally:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
