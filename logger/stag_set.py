"""Reader for AC STAG settings backups (*.set, magic "Ugic_Set").

Format reverse-engineered from two backups of this car (STAG-4 QBOX BASIC fw 2.14.2,
stag/backup/). After the 8-byte magic every byte is XOR-chained to the previous one:

    plain[i] = file[i] ^ file[i-1]        for i >= 8

Decoded, the file holds tables (RPM axis 1000..10000, per-cylinder blocks, B/G maps) and
a block of parameter records, all big-endian:

    u8 id; u16 len; 3 x 00; len bytes value

The multiplier map is stored as u8 n_points, n x u16 petrol time (units of 5 us),
padding, n x u16 multiplier x100. Confirmed against the AC STAG screenshot of
2026-09-28 (points 1.65 / 1.61 / 1.58 / ... at 0 / 2.475 / 4 / ... ms).

Parameter ids are NOT mapped to AC STAG fields yet, except the guesses in PARAM_GUESS.
To map them, save settings from AC STAG demo mode (known values) and compare with --diff.

Usage:
    python stag_set.py file.set                  -> parameter records and multiplier map
    python stag_set.py --diff a.set b.set        -> records and multiplier points that differ
    python stag_set.py --dump file.set           -> decoded bytes as hex
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

MAGIC = b"Ugic_Set"

# Unconfirmed: scale x100 like the multiplier, adjacent like the two fields in the UI,
# id 08 = 60 equals the factory minimum pressure 0.60 bar.
PARAM_GUESS = {
    0x07: "working pressure x100 bar?",
    0x08: "minimum pressure x100 bar?",
}


@dataclass
class Record:
    offset: int
    id: int
    value: bytes

    @property
    def int(self) -> int | None:
        return int.from_bytes(self.value, "big") if len(self.value) <= 4 else None

    @property
    def name(self) -> str:
        return PARAM_GUESS.get(self.id, "")

    def __str__(self) -> str:
        v = self.int
        shown = f"{v}" if v is not None else self.value.hex(" ")
        return f"0x{self.offset:04X} id={self.id:02X} len={len(self.value):2d} {shown:>12s}  {self.name}"


def decode(raw: bytes) -> bytes:
    if not raw.startswith(MAGIC):
        raise ValueError("not an AC STAG settings file")
    return raw[:8] + bytes(raw[i] ^ raw[i - 1] for i in range(8, len(raw)))


def _chain(b: bytes, o: int) -> list[Record]:
    recs = []
    while o + 6 <= len(b):
        ln = int.from_bytes(b[o + 1:o + 3], "big")
        if b[o + 3:o + 6] != b"\0\0\0" or not 0 < ln <= 64:
            break
        recs.append(Record(o, b[o], b[o + 6:o + 6 + ln]))
        o += 6 + ln
    return recs


def find_records(b: bytes) -> list[Record]:
    """The parameter block is the longest chain of well-formed records (66 in fw 2.14.2)."""
    best: list[Record] = []
    for o in range(8, len(b) - 6):
        if len(best) > 20 and o < best[-1].offset:
            continue
        r = _chain(b, o)
        if len(r) > len(best):
            best = r
    return best


def find_multiplier(b: bytes) -> list[tuple[float, float]]:
    """(petrol ms, multiplier) points: count byte, increasing time axis from 0, then values."""
    for p in range(8, len(b) - 40):
        n = b[p]
        if not 3 <= n <= 16 or b[p + 1:p + 3] != b"\0\0":
            continue
        xs = [int.from_bytes(b[p + 1 + 2 * k:p + 3 + 2 * k], "big") for k in range(n)]
        if not all(a < c for a, c in zip(xs, xs[1:])) or xs[-1] > 8000:
            continue
        end = p + 1 + 2 * n
        for q in range(end, min(end + 128, len(b) - 2 * n), 2):
            ys = [int.from_bytes(b[q + 2 * k:q + 2 + 2 * k], "big") for k in range(n)]
            if all(30 <= y <= 400 for y in ys):
                return [(x * 0.005, y / 100) for x, y in zip(xs, ys)]
    return []


def read_set(path) -> tuple[list[Record], list[tuple[float, float]]]:
    b = decode(Path(path).read_bytes())
    return find_records(b), find_multiplier(b)


def describe(path) -> str:
    recs, mult = read_set(path)
    lines = [f"{Path(path).name}: {len(recs)} parameter records"]
    lines += [f"  {r}" for r in recs]
    lines.append("  multiplier (ms -> x): " + ", ".join(f"{x:g} -> {y:.2f}" for x, y in mult))
    return "\n".join(lines)


def diff(a, b) -> str:
    ra, ma = read_set(a)
    rb, mb = read_set(b)
    va = {r.id: r for r in ra}
    vb = {r.id: r for r in rb}
    lines = [f"{Path(a).name} -> {Path(b).name}"]
    for i in sorted(va.keys() | vb.keys()):
        x, y = va.get(i), vb.get(i)
        if x is None or y is None or x.value != y.value:
            sx = "-" if x is None else (x.int if x.int is not None else x.value.hex(" "))
            sy = "-" if y is None else (y.int if y.int is not None else y.value.hex(" "))
            lines.append(f"  id={i:02X} {sx} -> {sy}  {PARAM_GUESS.get(i, '')}")
    if ma != mb:
        lines.append("  multiplier: " + ", ".join(f"{x:g}:{y:.2f}" for x, y in ma)
                     + "  ->  " + ", ".join(f"{x:g}:{y:.2f}" for x, y in mb))
    if len(lines) == 1:
        lines.append("  no differences in parameters or multiplier")
    return "\n".join(lines)


def main(argv):
    files = [a for a in argv if not a.startswith("--")]
    if "--diff" in argv and len(files) == 2:
        print(diff(*files))
    elif "--dump" in argv and files:
        b = decode(Path(files[0]).read_bytes())
        for o in range(0, len(b), 16):
            print(f"{o:05X}: {b[o:o + 16].hex(' ')}")
    elif files:
        for f in files:
            print(describe(f))
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
