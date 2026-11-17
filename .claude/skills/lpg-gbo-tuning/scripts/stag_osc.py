"""Reader for AC STAG oscilloscope files (*.osc, magic "Ugic_Osc").

Format reverse-engineered from the demo files shipped with AC STAG 0.64.4
(see stag/osc_format/FORMAT.md). All integers are big-endian.

    "Ugic_Osc" ... header / display settings ...
    u16  table_len                      (bytes, multiple of 10)
    table_len/10 x { u8 id; f64 scale; u8 size }   channel table, first entry is all zeros
    u32  data_len                       (bytes)
    data_len/rec x record               record = channels in table order, unsigned BE ints
    trailer: ... u32 start_unix, u32 end_unix (UTC), u32 n_samples, ... controller info

value = raw * scale. Sample timestamps are not stored per record; they are spread
evenly between start and end time.

Usage:
    python stag_osc.py file.osc [more.osc]       -> writes file.csv next to each file
    python stag_osc.py --info file.osc           -> channel table and summary only
"""
from __future__ import annotations

import struct
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

MAGIC = b"Ugic_Osc"

# Channel ids confirmed against values shown by AC STAG in demo mode (QBOX BASIC fw 2.10.2).
# Unconfirmed ids are exported as ch_XX.
CHANNEL_NAMES = {
    0x03: "map_bar",
    0x04: "gas_p_bar",
    0x05: "gas_temp_c",
    0x06: "reducer_temp_c",
    0x07: "internal_temp_c",
    0x08: "rpm",
    0x09: "supply_v",
    0x0B: "status",            # 1 = petrol, 4/8 = switching, 10 = gas (demo QBOX BASIC)
    0x48: "engine_temp_emul_c",
    0x10: "petrol_ms_1", 0x11: "petrol_ms_2", 0x12: "petrol_ms_3", 0x13: "petrol_ms_4",
    0x14: "petrol_ms_5", 0x15: "petrol_ms_6", 0x16: "petrol_ms_7", 0x17: "petrol_ms_8",
    0x20: "gas_ms_1", 0x21: "gas_ms_2", 0x22: "gas_ms_3", 0x23: "gas_ms_4",
    0x24: "gas_ms_5", 0x25: "gas_ms_6", 0x26: "gas_ms_7", 0x27: "gas_ms_8",
    0x28: "lambda1_v",
}


@dataclass
class Channel:
    id: int
    scale: float
    size: int
    offset: int = 0

    @property
    def name(self) -> str:
        return CHANNEL_NAMES.get(self.id, f"ch_{self.id:02X}")


@dataclass
class OscFile:
    path: Path
    channels: list[Channel]
    record_size: int
    n_samples: int
    start: datetime | None
    end: datetime | None
    columns: dict[str, list[float]] = field(default_factory=dict)
    times: list[float] = field(default_factory=list)   # seconds from start


def _find_table(b: bytes) -> tuple[int, list[Channel], int, int]:
    """Locate the channel table by structure: returns (table_pos, channels, rec_size, data_pos)."""
    for p in range(8, len(b) - 16):
        tlen = struct.unpack_from(">H", b, p)[0]
        if tlen < 30 or tlen % 10 or tlen > 2000 or p + 2 + tlen + 4 > len(b):
            continue
        t0 = p + 2
        if b[t0:t0 + 10] != b"\x00" * 10:          # leading null entry
            continue
        chans = []
        ok = True
        for k in range(1, tlen // 10):
            cid, scale, size = struct.unpack_from(">BdB", b, t0 + 10 * k)
            if size not in (1, 2, 4) or not (0 < scale < 1e6) or cid == 0:
                ok = False
                break
            chans.append(Channel(cid, scale, size))
        if not ok or not chans:
            continue
        rec = sum(c.size for c in chans)
        dlen = struct.unpack_from(">I", b, t0 + tlen)[0]
        dpos = t0 + tlen + 4
        if dlen == 0 or dlen % rec or dpos + dlen > len(b):
            continue
        off = 0
        for c in chans:
            c.offset = off
            off += c.size
        return p, chans, rec, dpos
    raise ValueError("channel table not found - unknown .osc variant")


def _find_times(b: bytes, pos: int, n: int):
    """Trailer right after the data: look for (start, end, n_samples) as BE u32."""
    for q in range(pos, min(pos + 64, len(b) - 12)):
        s, e, cnt = struct.unpack_from(">III", b, q)
        if cnt == n and 1_000_000_000 < s <= e < 4_000_000_000:
            return (datetime.fromtimestamp(s, timezone.utc).astimezone(),
                    datetime.fromtimestamp(e, timezone.utc).astimezone())
    return None, None


def read_osc(path) -> OscFile:
    path = Path(path)
    b = path.read_bytes()
    if not b.startswith(MAGIC):
        raise ValueError(f"{path.name}: not an AC STAG oscilloscope file")
    _, chans, rec, dpos = _find_table(b)
    dlen = struct.unpack_from(">I", b, dpos - 4)[0]
    n = dlen // rec
    start, end = _find_times(b, dpos + dlen, n)
    fmt = {1: "B", 2: "H", 4: "I"}
    cols: dict[str, list[float]] = {c.name: [] for c in chans}
    for i in range(n):
        base = dpos + i * rec
        for c in chans:
            raw = struct.unpack_from(">" + fmt[c.size], b, base + c.offset)[0]
            cols[c.name].append(round(raw * c.scale, 6))
    osc = OscFile(path, chans, rec, n, start, end, cols)
    dur = (end - start).total_seconds() if start and end else None
    step = dur / (n - 1) if dur and n > 1 else None
    osc.times = [round(i * step, 3) if step else float(i) for i in range(n)]
    return osc


def to_dataframe(osc: OscFile):
    import pandas as pd
    df = pd.DataFrame(osc.columns)
    df.insert(0, "t_s", osc.times)
    if osc.start:
        df.insert(0, "time", [osc.start.timestamp() + t for t in osc.times])
        df["time"] = pd.to_datetime(df["time"], unit="s", utc=True).dt.tz_convert(osc.start.tzinfo)
    gas = [c for c in df.columns if c.startswith("gas_ms_")]
    if gas:
        df["on_gas"] = (df[gas].max(axis=1) > 0).astype(int)
    return df


def write_csv(osc: OscFile, out: Path | None = None) -> Path:
    out = out or osc.path.with_suffix(".csv")
    to_dataframe(osc).to_csv(out, index=False)
    return out


def describe(osc: OscFile) -> str:
    lines = [f"{osc.path.name}: {osc.n_samples} samples x {osc.record_size} B, "
             f"{osc.start} -> {osc.end}"]
    for c in osc.channels:
        v = osc.columns[c.name]
        lines.append(f"  {c.id:02X} {c.name:16s} size={c.size} scale={c.scale:<10g} "
                     f"min={min(v):<9g} max={max(v):<9g} first={v[0]:g}")
    return "\n".join(lines)


def main(argv):
    info = "--info" in argv
    files = [a for a in argv if not a.startswith("--")]
    if not files:
        print(__doc__)
        return 1
    for f in files:
        osc = read_osc(f)
        print(describe(osc))
        if not info:
            print("  ->", write_csv(osc))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
