#!/usr/bin/env python3
"""Runs a command and kills its whole process tree when it uses too much memory.

    scripts/memguard.py <command> [args...]

MEMGUARD_MAX_GB       cap on the tree's memory, GiB (default 24)
MEMGUARD_MIN_FREE_GB  floor on system free memory, GiB (default 16)

Every 0.5 s the guard sums the physical footprint of every process in the tree
(RSS plus compressed pages plus GPU buffers, which plain RSS misses on Apple Silicon)
and reads the system free level (kern.memorystatus_level, the value memory_pressure
prints). Above the cap or below the floor it sends SIGTERM to the tree, SIGKILL
3 s later, and exits 137. Stdlib only; macOS only.
"""
import ctypes
import os
import signal
import subprocess
import sys
import time

GIB = 1 << 30
MAX = float(os.environ.get("MEMGUARD_MAX_GB", "24")) * GIB
MIN_FREE = float(os.environ.get("MEMGUARD_MIN_FREE_GB", "16")) * GIB
RAM = int(subprocess.check_output(["sysctl", "-n", "hw.memsize"]))

_libproc = ctypes.CDLL("/usr/lib/libproc.dylib")


def footprint(pid):
    """ri_phys_footprint from proc_pid_rusage(RUSAGE_INFO_V0); 0 if the process is gone."""
    buf = (ctypes.c_uint64 * 12)()  # uuid (2 words) + 10 counters
    if _libproc.proc_pid_rusage(pid, 0, buf) != 0:
        return 0
    return max(buf[9], buf[8])  # phys_footprint, resident_size


def tree(root):
    """The root, every descendant (by parent id) and every member of its process group."""
    # ponytail: a grandchild that calls setsid and is reparented to launchd escapes; uvx -> python does not do that.
    rows = [line.split() for line in subprocess.check_output(["ps", "-axo", "pid=,ppid=,pgid="], text=True).splitlines()]
    pids = {root} | {int(p) for p, _, g in rows if int(g) == root}
    grew = True
    while grew:
        more = {int(p) for p, pp, _ in rows if int(pp) in pids} - pids
        pids |= more
        grew = bool(more)
    return pids


def free_bytes():
    return int(subprocess.check_output(["sysctl", "-n", "kern.memorystatus_level"])) * RAM // 100


def kill(root, why, reaped=False):
    if why:
        print(f"memguard: {why}; killing process tree {root}", file=sys.stderr, flush=True)
    pids = tree(root) - ({root} if reaped else set())  # a reaped root pid may be reused
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for pid in pids:
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass
        deadline = time.time() + 3
        while sig == signal.SIGTERM and time.time() < deadline and any(footprint(p) for p in pids):
            time.sleep(0.2)


def main(argv):
    if not argv:
        sys.exit(__doc__)
    if free_bytes() < MIN_FREE:
        sys.exit(f"memguard: only {free_bytes() / GIB:.1f} GiB free, below the {MIN_FREE / GIB:.0f} GiB floor; not starting")
    # ponytail: if the guard itself gets SIGKILL, the tree lives on; stop it with SIGTERM or Ctrl-C.
    child = subprocess.Popen(argv, start_new_session=True)  # own process group
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, lambda s, _: (kill(child.pid, f"got signal {s}"), sys.exit(128 + s)))
    peak = 0
    while child.poll() is None:
        used = sum(footprint(p) for p in tree(child.pid))
        peak = max(peak, used)
        free = free_bytes()
        if used > MAX or free < MIN_FREE:
            kill(child.pid, f"tree {used / GIB:.1f} GiB (cap {MAX / GIB:.0f}), system free {free / GIB:.1f} GiB (floor {MIN_FREE / GIB:.0f})")
            child.wait()
            sys.exit(137)
        time.sleep(0.5)  # ponytail: a burst shorter than this is not seen
    kill(child.pid, None, reaped=True)  # no orphans once the main child is gone
    print(f"memguard: exit {child.returncode}, peak {peak / GIB:.1f} GiB", file=sys.stderr)
    sys.exit(child.returncode)


if __name__ == "__main__":
    main(sys.argv[1:])
