"""Man-in-the-middle sniffer for the AC STAG <-> Stag controller serial link (read-only tool).

AC STAG talks to a virtual COM port (com0com pair, here COM7<->COM8); this script opens the other end of
the pair and the real port of the controller (Bluetooth SPP COM or the USB cable) and forwards bytes both
ways unchanged, logging every chunk with a timestamp and direction. It never sends anything of its own.

    AC STAG --COM7==com0com==COM8-- stag_sniff.py --COMx (Bluetooth / USB) -- Stag controller

Frame format known from lpg-forum.pl t51729 (older STAG 200 / 300, AC gaz synchro v10; QBOX unverified):

    [start] [len hi?] [len hi?] [len] [data ... len-1 bytes] [cks]
    len counts the bytes after itself including cks; cks = sum(all previous bytes of the frame) & 0xFF
    PC hello: AC 00 00 04 00 00 S C - S becomes the start byte of every later frame of the session.

Bytes 1-2 are taken as the high bytes of a 24-bit length (the reply to the init is said to be hundreds of
bytes); unverified. The parser is a best-effort annotation: raw bytes are always logged.

Usage:
    python stag_sniff.py --real COM9 --virt COM8 [--baud 115200] [--out logs/stag_sniff_X.log]
    python stag_sniff.py --parse logs/stag_sniff_X.log     -> re-parse a saved log into frames
    python stag_sniff.py --selftest                        -> checks on the forum sample frames
Real/virt ports may also be pyserial URLs (socket://host:port, loop://) for testing.
Stop with Ctrl+C.
"""
from __future__ import annotations

import argparse
import re
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

PC, ST = "PC>ST", "ST>PC"
MAX_LEN = 4096          # sanity cap for the 24-bit length


def checksum(data: bytes) -> int:
    return sum(data) & 0xFF


@dataclass
class Frame:
    direction: str
    t: float
    raw: bytes

    @property
    def start(self) -> int:
        return self.raw[0]

    @property
    def length(self) -> int:
        return (self.raw[1] << 16) | (self.raw[2] << 8) | self.raw[3]

    @property
    def data(self) -> bytes:
        return self.raw[4:-1]

    def describe(self) -> str:
        cmd = f"cmd={self.data[0]:02X} " if self.data else ""
        return f"{self.t:10.3f} FRAME {self.direction} start={self.start:02X} len={self.length} {cmd}data={self.data.hex(' ').upper()}"


@dataclass
class FrameParser:
    """Splits one direction's byte stream into frames; bytes that do not form a valid frame are skipped."""
    direction: str
    buf: bytearray = field(default_factory=bytearray)
    skipped: int = 0

    def feed(self, chunk: bytes, t: float) -> list[Frame]:
        self.buf += chunk
        out: list[Frame] = []
        while len(self.buf) >= 5:
            n = (self.buf[1] << 16) | (self.buf[2] << 8) | self.buf[3]
            if not 1 <= n <= MAX_LEN:
                self._skip()
                continue
            end = 4 + n
            if len(self.buf) < end:
                break                      # wait for more bytes
            raw = bytes(self.buf[:end])
            if checksum(raw[:-1]) == raw[-1]:
                out.append(Frame(self.direction, t, raw))
                del self.buf[:end]
            else:
                self._skip()
        return out

    def _skip(self) -> None:
        del self.buf[:1]
        self.skipped += 1


class Sniffer:
    def __init__(self, real, virt, out_path: Path):
        self.real, self.virt = real, virt
        self.out = out_path.open("a", encoding="utf-8", buffering=1)
        self.t0 = time.perf_counter()
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.parsers = {PC: FrameParser(PC), ST: FrameParser(ST)}
        self.counts = {PC: 0, ST: 0}
        self.frames = {PC: 0, ST: 0}

    def log(self, line: str) -> None:
        with self.lock:
            self.out.write(line + "\n")

    def pump(self, src, dst, direction: str) -> None:
        while not self.stop.is_set():
            try:
                chunk = src.read(src.in_waiting or 1)
            except Exception as e:          # port vanished (BT dropped) -> stop, keep the log
                self.log(f"# {direction} read error: {e}")
                self.stop.set()
                break
            if not chunk:
                continue
            dst.write(chunk)                # forward first: AC STAG must not notice the sniffer
            t = time.perf_counter() - self.t0
            if not self.counts[direction]:
                print(f"  first bytes {direction} at {t:.1f} s")
            self.counts[direction] += len(chunk)
            self.log(f"{t:10.3f} {direction} {chunk.hex(' ').upper()}")
            for fr in self.parsers[direction].feed(chunk, t):
                self.frames[direction] += 1
                self.log(fr.describe())

    def status(self) -> str:
        return (f"  {PC} {self.counts[PC]} B / {self.frames[PC]} frames   "
                f"{ST} {self.counts[ST]} B / {self.frames[ST]} frames")

    def notes(self) -> None:
        """Console notes: text + Enter -> timestamped NOTE line in the log; empty Enter -> traffic counters."""
        while not self.stop.is_set():
            try:
                line = input().strip()
            except (EOFError, KeyboardInterrupt):
                break
            if line:
                t = time.perf_counter() - self.t0
                self.log(f"{t:10.3f} NOTE {datetime.now():%H:%M:%S} {line}")
                print(f"  noted at {t:.1f} s")
            else:
                print(self.status())

    def run(self, notes: bool = True) -> None:
        self.log(f"# stag_sniff start {datetime.now().isoformat(timespec='seconds')}")
        threads = [threading.Thread(target=self.pump, args=(self.virt, self.real, PC), daemon=True),
                   threading.Thread(target=self.pump, args=(self.real, self.virt, ST), daemon=True)]
        if notes:
            threads.append(threading.Thread(target=self.notes, daemon=True))
            print("type a note + Enter (e.g. 'RPM 2000', 'multiplier 3.5ms +4%'); empty Enter = counters")
        for th in threads:
            th.start()
        try:
            while not self.stop.is_set():
                time.sleep(0.5)
        except KeyboardInterrupt:
            pass
        print(self.status())
        self.stop.set()
        self.log(f"# stop; bytes {self.counts}; skipped (not a frame) "
                 f"{ {d: p.skipped for d, p in self.parsers.items()} }")
        self.out.close()
        print()


LINE = re.compile(r"^\s*([\d.]+) (PC>ST|ST>PC) ((?:[0-9A-F]{2} ?)+)$")
NOTE = re.compile(r"^\s*([\d.]+) NOTE (.*)$")


def parse_log(path: Path, notes: list | None = None) -> list[Frame]:
    """Re-parse the raw chunk lines of a sniff log (FRAME lines are ignored); NOTE lines go to `notes`."""
    parsers = {PC: FrameParser(PC), ST: FrameParser(ST)}
    frames: list[Frame] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = LINE.match(line)
        if m:
            frames += parsers[m.group(2)].feed(bytes.fromhex(m.group(3)), float(m.group(1)))
        elif notes is not None and (n := NOTE.match(line)):
            notes.append((float(n.group(1)), n.group(2)))
    side = path.with_name(path.name + ".notes")      # notes added from outside by stag_note.py
    if notes is not None and side.exists():
        for line in side.read_text(encoding="utf-8").splitlines():
            if n := NOTE.match(line):
                notes.append((float(n.group(1)), n.group(2)))
    return frames


def selftest() -> None:
    # Frames from lpg-forum.pl t51729 (STAG EasyGo): hello, PC request, two controller replies.
    samples = [
        (PC, "AC 00 00 04 00 00 91 41"),
        (ST, "91 00 00 28 80 00 01 00 22 03 01 00 01 00 03 1B 03 07 DF 0F 16 08 0B 11 11 15 06 27 04 01 67"
             " FF FF FF FF FF FF FF FF 02 00 02 00 6C"),
        (PC, "91 00 00 03 52 00 E6"),
        (ST, "91 00 00 23 D2 00 00 22 03 01 00 01 00 03 1B 03 07 DF 0F 16 08 0B 11 11 15 06 27 04 01 67 FF FF"
             " FF FF FF FF FF FF B4"),
    ]
    for d, hx in samples:
        raw = bytes.fromhex(hx)
        assert checksum(raw[:-1]) == raw[-1], hx
        p = FrameParser(d)
        # garbage before, frame split into 3 chunks, garbage after
        got = p.feed(b"\x55\x00", 0) + p.feed(raw[:3], 1) + p.feed(raw[3:10], 2) + p.feed(raw[10:] + b"\x07", 3)
        assert len(got) == 1 and got[0].raw == raw, (hx, got)
        assert got[0].length == len(raw) - 4
    # corrupted checksum -> no frame
    bad = bytearray.fromhex("91 00 00 03 52 00 E7")
    assert FrameParser(PC).feed(bytes(bad), 0) == []
    # forwarding through the sniffer: two in-memory port pairs
    import serial
    import tempfile
    a_virt, a_real = serial.serial_for_url("loop://", timeout=0.05), serial.serial_for_url("loop://", timeout=0.05)
    log = Path(tempfile.gettempdir()) / "stag_sniff_selftest.log"
    log.unlink(missing_ok=True)

    class Pair:
        """Writes go to `peer` loop, reads come from `own` loop: emulates two ends of a cable."""
        def __init__(self, own, peer):
            self.own, self.peer = own, peer
        @property
        def in_waiting(self):
            return self.own.in_waiting
        def read(self, n):
            return self.own.read(n)
        def write(self, b):
            return self.peer.write(b)

    out_to_acstag = serial.serial_for_url("loop://", timeout=0.05)   # what AC STAG would receive
    out_to_ctrl = serial.serial_for_url("loop://", timeout=0.05)     # what the controller would receive
    virt, real = Pair(a_virt, out_to_acstag), Pair(a_real, out_to_ctrl)
    s = Sniffer(real, virt, log)
    th = threading.Thread(target=s.run, kwargs={"notes": False}, daemon=True)
    th.start()
    a_virt.write(bytes.fromhex(samples[2][1]))   # "AC STAG" sends a request
    a_real.write(bytes.fromhex(samples[3][1]))   # "controller" replies
    time.sleep(0.6)
    s.stop.set()
    th.join(timeout=3)
    assert out_to_ctrl.read(100) == bytes.fromhex(samples[2][1])
    assert out_to_acstag.read(100) == bytes.fromhex(samples[3][1])
    frames = parse_log(log)
    assert [f.direction for f in frames] == [PC, ST], frames
    print("selftest OK: checksum on 4 forum frames, resync/split parsing, forwarding, log re-parse")


def open_port(name: str, baud: int):
    import serial
    if "://" in name:
        return serial.serial_for_url(name, baudrate=baud, timeout=0.05)
    return serial.Serial(name, baudrate=baud, timeout=0.05)


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--real", help="controller port: Bluetooth COM of the Stag interface or the USB cable")
    ap.add_argument("--virt", help="free end of the com0com pair (AC STAG uses the other end)")
    ap.add_argument("--baud", type=int, default=115200,
                    help="baud for --real (ignored by Bluetooth COM ports; must match for the USB cable)")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--parse", type=Path, help="re-parse a saved log")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args(argv)
    if a.selftest:
        selftest()
        return
    if a.parse:
        notes: list = []
        rows = [(fr.t, fr.describe()) for fr in parse_log(a.parse, notes)]
        rows += [(t, f"{t:10.3f} NOTE  {text}") for t, text in notes]
        for _, line in sorted(rows, key=lambda r: r[0]):
            print(line)
        return
    if not (a.real and a.virt):
        ap.error("--real and --virt are required (or --parse / --selftest)")
    out = a.out or Path(__file__).resolve().parent.parent / "logs" / f"stag_sniff_{datetime.now():%Y%m%d_%H%M%S}.log"
    out.parent.mkdir(parents=True, exist_ok=True)
    real, virt = open_port(a.real, a.baud), open_port(a.virt, a.baud)
    print(f"sniffing {a.real} <-> {a.virt}; log {out}; Ctrl+C to stop")
    Sniffer(real, virt, out).run()


if __name__ == "__main__":
    main(sys.argv[1:])
