"""Add a timestamped note to a stag_sniff log from outside the sniffer console (e.g. typed in chat).

Notes go to a sidecar file <log>.notes as 't NOTE HH:MM:SS text', where t is the time of the last line
of the log (~ now while the sniffer runs) or --at. `stag_sniff.py --parse <log>` merges them with frames.

    python stag_note.py "екран монітор"              -> latest logs/stag_sniff_*.log
    python stag_note.py --at 66.4 "AC STAG connected"
    python stag_note.py --log logs/stag_sniff_X.log "text"
"""
from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime
from pathlib import Path

LOGS = Path(__file__).resolve().parent.parent / "logs"
T = re.compile(r"^\s*([\d.]+) ")


def last_t(log: Path) -> float:
    with log.open("rb") as f:
        f.seek(0, 2)
        f.seek(max(0, f.tell() - 8192))
        lines = f.read().decode("utf-8", "replace").splitlines()
    for line in reversed(lines):
        m = T.match(line)
        if m:
            return float(m.group(1))
    return 0.0


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("text", nargs="+")
    ap.add_argument("--log", type=Path, help="sniff log (default: latest logs/stag_sniff_*.log)")
    ap.add_argument("--at", type=float, help="sniffer time in s (default: last line of the log)")
    a = ap.parse_args(argv)
    log = a.log or max(LOGS.glob("stag_sniff_*.log"), key=lambda p: p.stat().st_mtime)
    t = a.at if a.at is not None else last_t(log)
    line = f"{t:10.3f} NOTE {datetime.now():%H:%M:%S} {' '.join(a.text)}"
    with log.with_name(log.name + ".notes").open("a", encoding="utf-8") as f:
        f.write(line + "\n")
    sys.stdout.reconfigure(errors="replace")      # cp1251 consoles cannot print every character
    print(f"{log.name}: {line.strip()}")


if __name__ == "__main__":
    main()
