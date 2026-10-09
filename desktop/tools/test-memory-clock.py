#!/usr/bin/env python3
"""Clock-resolution regression: retain CPU deltas across duplicate timestamps."""
import importlib.util
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

import performance

spec = importlib.util.spec_from_file_location("memory_trend", Path(__file__).with_name("memory-trend.py"))
memory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(memory)


class Clock:
    def __init__(self):
        self.now = 0.0
        self.steps = iter((1.0, 0.0, 1.0, 1.0))
    def monotonic(self):
        return self.now
    def sleep(self, delay):
        assert delay > 0, "Sampler attempted a zero-duration busy loop"
        self.now += next(self.steps)


class Tree:
    def __init__(self, pid=1):
        self.cpu = 0.0
    def reading(self):
        self.cpu += 0.1
        return 1024, {1: self.cpu}


class Process:
    pid = 1
    def poll(self):
        return None
    def wait(self, timeout):
        return 0
    def terminate(self):
        pass
    def num_threads(self):
        return 2
    def num_handles(self):
        return 3


def verify(points):
    assert len(points) == 3 and points[-1]["elapsed_s"] == 3
    assert all(p["cpu_percent_one_core"] > 0 for p in points)
    assert abs(points[1]["cpu_percent_one_core"] - 20) < 0.001, "CPU consumed at duplicate timestamp was lost"


clock = Clock()
with patch.object(performance, "time", clock):
    verify(performance.sample(Tree(), 0, 3)["samples"])

clock = Clock()
with tempfile.TemporaryDirectory() as folder:
    args = SimpleNamespace(output=Path(folder) / "report.json", work_dir=folder, settle=0, seconds=3)
    windows = SimpleNamespace(name="nt", environ=os.environ, path=os.path, pathsep=os.pathsep)
    with patch.object(memory, "time", clock), patch.object(memory, "os", windows), \
         patch.object(memory.subprocess, "Popen", return_value=Process()), \
         patch.object(memory, "heap", return_value={"used_heap_bytes": 100}), \
         patch.object(performance, "Tree", Tree), \
         patch.object(performance, "wait_json", return_value={"endpoint": "127.0.0.1:1", "id": "fixture"}), \
         patch.object(performance.psutil, "Process", return_value=Process()):
        report = memory.one(args, "connected", 1)
    verify(report["samples"])
    assert report["verified_normal_shutdown"] and (Path(folder) / "connected-1.json").is_file()
print("Memory samplers: duplicate clock values preserve CPU counters and normal shutdown")
