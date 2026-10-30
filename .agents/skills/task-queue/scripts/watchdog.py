#!/usr/bin/env python
"""task-queue watchdog: poll .tasks/pending for new task files.

Runs in the background of a ZCode session. Prints one machine-readable
line and exits; the session wakes on process exit and acts on the line.

Lines:
  TASKQUEUE NEW <filename>   oldest pending task found, claim and run it
  TASKQUEUE STATE off        queue.state says off, stop the monitor loop
  TASKQUEUE TIMEOUT          nothing found within --timeout, re-arm me
"""

import argparse
import sys
import time
from pathlib import Path


def read_state(root: Path) -> str:
    f = root / "queue.state"
    try:
        return f.read_text(encoding="utf-8", errors="replace").strip().lower()
    except OSError:
        return "on"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="queue root (.tasks)")
    ap.add_argument("--interval", type=float, default=10.0)
    ap.add_argument("--timeout", type=float, default=1800.0)
    args = ap.parse_args()

    root = Path(args.dir)
    pending = root / "pending"
    deadline = time.monotonic() + args.timeout

    while True:
        state = read_state(root)
        if state == "off":
            print("TASKQUEUE STATE off", flush=True)
            return 0
        if state != "paused" and pending.is_dir():
            md = sorted(p for p in pending.glob("*.md") if p.is_file())
            if md:
                print(f"TASKQUEUE NEW {md[0].name}", flush=True)
                return 0
        if time.monotonic() >= deadline:
            print("TASKQUEUE TIMEOUT", flush=True)
            return 0
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
