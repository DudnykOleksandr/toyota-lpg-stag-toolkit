"""ELM327 logger for LPG (Stag) vs petrol fuel-trim comparison. Works offline.

Usage:
    python elm_logger.py --list              # show COM ports
    python elm_logger.py --probe             # connect, print ELM/protocol/PIDs + engine health, exit
    python elm_logger.py                     # log (port auto-detected)
    python elm_logger.py --port COM5         # log on a given port
    python elm_logger.py --sim               # log from the built-in simulator

Keys while logging (Latin or Ukrainian layout both work):
    g  gas            b  petrol (benzyn)
    a  A/C on         s  A/C off
    h  highway        c  city          i  idle
    m  generic mark (numbered: m1, m2, ...)       q  quit
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

from elm327 import Elm327, ElmError, LinkLost, PIDS, columns_for, list_ports, pack_groups
from obd_health import format_health, read_health

# Candidate PIDs. Only those the ECU reports as supported are polled.
# O2 B1S2 is fast: judging the catalyst needs it next to B1S1 (a worn cat lets B2 mirror B1's switching).
# Commanded lambda is slow: with a narrow-band B1S1 the 1ZR ECU reports a constant 1.00 in closed loop.
FAST = [0x06, 0x07, 0x0C, 0x04, 0x10, 0x0B, 0x11, 0x0D, 0x14, 0x15]
FAST_WIDEBAND = [0x34, 0x24]          # used only if 0x14 (narrow-band O2) is absent
SLOW = [0x05, 0x0F, 0x0E, 0x03, 0x3C, 0x44, 0x43, 0x42, 0x33, 0x46, 0x1F]

KEYS = {
    "g": ("gas", "fuel", "gas"),
    "b": ("petrol", "fuel", "petrol"),
    "a": ("ac_on", "ac", "on"),
    "s": ("ac_off", "ac", "off"),
    "h": ("highway", "road", "highway"),
    "c": ("city", "road", "city"),
    "i": ("idle", "road", "idle"),
    "m": ("m", None, None),
}
# Same physical keys on the Ukrainian (ЙЦУКЕН) layout
KEYS_UA = {"п": "g", "и": "b", "ф": "a", "і": "s", "ы": "s", "р": "h", "с": "c", "ш": "i",
           "ь": "m", "й": "q"}

# project root with logs/ (override with env LPG_ROOT)
LOG_DIR = Path(os.environ.get("LPG_ROOT") or Path(__file__).resolve().parent.parent) / "logs"
# Creating this file stops a background logger cleanly (end health snapshot, closed CSV) - unlike a kill
STOP_FILE = LOG_DIR / "STOP"
# Keys written into this file are applied as if typed (markers for a background logger), then it is removed
KEYS_FILE = LOG_DIR / "KEYS"


class KeyReader:
    """Non-blocking single-key reader (Windows msvcrt, POSIX fallback via a thread)."""

    def __init__(self):
        self.queue: list[str] = []
        if os.name == "nt":
            import msvcrt
            self._msvcrt = msvcrt
        else:
            self._msvcrt = None
            threading.Thread(target=self._posix_loop, daemon=True).start()

    def _posix_loop(self):
        for line in sys.stdin:
            self.queue.extend(line.strip())

    def poll(self) -> list[str]:
        if self._msvcrt:
            while self._msvcrt.kbhit():
                ch = self._msvcrt.getwch()
                if ch in ("\x00", "\xe0"):      # function / arrow key prefix
                    self._msvcrt.getwch()
                    continue
                self.queue.append(ch)
        keys, self.queue = self.queue, []
        out = []
        for k in keys:
            k = k.lower()
            out.append(KEYS_UA.get(k, k))
        return out


def find_elm_port(log) -> str | None:
    """Try every COM port, Bluetooth serial ports first; return the one answering ATZ like an ELM."""
    ports = list_ports()
    ports.sort(key=lambda p: 0 if "bluetooth" in p[1].lower() else 1)
    for dev, desc in ports:
        log(f"  probing {dev} ({desc}) ...")
        try:
            e = Elm327(dev, timeout=4, log=lambda *_: None)
            e.open()
            e.ser.write(b"\r")
            time.sleep(0.3)
            r = " ".join(e.cmd("ATZ", timeout=5))
            e.close()
            if "ELM" in r.upper() or "OBD" in r.upper():
                log(f"  -> {dev}: {r}")
                return dev
        except (ElmError, LinkLost) as ex:
            log(f"     no: {ex}")
    return None


# Pseudo-PIDs in the slow groups: name -> CSV column
PSEUDO = {"RV": "elm_v", "ATF": "atf_c"}


def read_atf(elm: Elm327) -> float | None:
    """Gearbox (U340E) fluid temperature. The 1ZR ECU runs the gearbox itself; service 21 block D9,
    byte 4 - 40 degC read 79-80 degC after a 40 min drive (candidate, see logs/ecm_mode21_scan_*.txt).
    Read-only request."""
    try:
        d = elm._parse(elm.cmd("21D9", timeout=2)).get(0x61)
    except ElmError:
        return None
    return float(d[4] - 40) if d and len(d) > 4 and d[0] == 0xD9 else None


class Logger:
    def __init__(self, args):
        self.args = args
        self.state = {"fuel": "unknown", "ac": "unknown", "road": "unknown"}
        self.mark_no = 0
        self.pending_marker: list[str] = []
        self.values: dict = {}
        self.t0 = time.monotonic()
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        LOG_DIR.mkdir(exist_ok=True)
        tag = "_sim" if args.sim else ""
        self.base = LOG_DIR / f"elm_{stamp}{tag}"
        self.eventlog = open(self.base.with_suffix(".log"), "a", encoding="utf-8")
        self.csvfile = None
        self.writer = None
        self.columns: list[str] = []
        self.fast_groups: list[list[int]] = []
        self.slow_groups: list[list] = []   # PID lists, or ["RV"] for ELM voltage
        self.rate_n = 0
        self.rate_t = time.monotonic()
        self.hz = 0.0
        self.last_fsync = time.monotonic()
        self.auto_markers = []
        self.health: dict = {}
        if args.auto_markers:   # testing aid: "5:g,20:a,..." seconds:key
            for item in args.auto_markers.split(","):
                t, k = item.split(":")
                self.auto_markers.append((float(t), k))
            self.auto_markers.sort()

    # ---- helpers -------------------------------------------------------
    def log(self, msg: str):
        line = f"{datetime.now().isoformat(timespec='seconds')} {msg}"
        print("\n" + line if not msg.startswith("  ") else msg, flush=True)
        self.eventlog.write(line + "\n")
        self.eventlog.flush()

    def elapsed(self) -> float:
        return time.monotonic() - self.t0

    def make_elm(self) -> Elm327:
        if self.args.sim:
            from sim import SimElm
            return Elm327(SimElm(state=self._sim_state, time_scale=self.args.sim_speed), log=self.log)
        return Elm327(self.args.port, log=self.log)

    def _sim_state(self):
        return {"fuel": self.state["fuel"], "ac": self.state["ac"] == "on", "road": self.state["road"]}

    # ---- setup ---------------------------------------------------------
    def setup_pids(self, elm: Elm327):
        supported = elm.supported_pids()
        self.log("supported PIDs: " + " ".join(f"{p:02X}" for p in supported))
        known = [p for p in supported if p in PIDS]
        self.log("   of them known to logger: " + ", ".join(PIDS[p].name for p in known))
        fast = [p for p in FAST if p in supported]
        if 0x14 not in supported:
            wb = [p for p in FAST_WIDEBAND if p in supported]
            if wb:
                fast.append(wb[0])
                self.log(f"no narrow-band O2 B1S1 - using wide-band PID {wb[0]:02X}")
        slow = [p for p in SLOW if p in supported]
        missing = [PIDS[p].name for p in FAST if p not in supported]
        if missing:
            self.log("WARNING fast PIDs not supported: " + ", ".join(missing))

        if elm.is_can and not self.args.single:
            self.fast_groups = pack_groups(fast)
            self.slow_groups = pack_groups(slow)
            if not self._verify_groups(elm):
                self.log("multi-PID requests not answered correctly - falling back to 1 PID/request")
                elm.multi_pid_ok = False
        if not elm.is_can or self.args.single or not elm.multi_pid_ok:
            self.fast_groups = [[p] for p in fast]
            self.slow_groups = [[p] for p in slow]
        self.slow_groups.append(["RV"])
        if not self.args.sim and elm.is_can and read_atf(elm) is not None:
            self.slow_groups.append(["ATF"])
        self.log("fast groups: " + " | ".join(",".join(PIDS[p].name for p in g) for g in self.fast_groups))
        self.log("slow groups: " + " | ".join(
            ",".join(PSEUDO.get(p) or PIDS[p].name for p in g) for g in self.slow_groups))

        cols = []
        for g in self.fast_groups + self.slow_groups:
            for p in g:
                for c in ([PSEUDO[p]] if p in PSEUDO else columns_for(p)):
                    if c not in cols:
                        cols.append(c)
        if self.columns and cols != self.columns:
            self.log("PID set changed after reconnect - keeping original columns")
        if not self.columns:
            self.columns = cols
            self.open_csv(elm, supported)
        self.supported = supported

    # ---- engine health snapshot (read-only) ------------------------------
    def snapshot_health(self, elm: Elm327, when: str):
        """DTCs, readiness, counters, mode 06 misfires -> <log>.health.json and the event log.
        Taken at the first connect and at the end (current-cycle misfire counts are most useful then)."""
        if self.args.no_health or not getattr(self, "supported", None):
            return
        try:
            h = read_health(elm, self.supported, log=self.log)
        except LinkLost as e:
            self.log(f"health snapshot ({when}) lost link: {e}")
            return
        self.health[when] = h
        self.base.with_suffix(".health.json").write_text(json.dumps(self.health, indent=2, ensure_ascii=False),
                                                         encoding="utf-8")
        self.log(f"engine health ({when}):")
        for line in format_health(h):
            self.log("  " + line)

    def _verify_groups(self, elm: Elm327) -> bool:
        for g in self.fast_groups:
            got = elm.query_raw(g)
            if set(got) != set(g):
                # try once more: the first request after init is sometimes slow
                got = elm.query_raw(g)
                if set(got) != set(g):
                    self.log(f"group {[f'{p:02X}' for p in g]} -> got {[f'{p:02X}' for p in got]}")
                    return False
        return True

    def open_csv(self, elm: Elm327, supported: list[int]):
        path = self.base.with_suffix(".csv")
        self.csvfile = open(path, "w", newline="", encoding="utf-8")
        self.header = ["time_iso", "t_s", "marker", "fuel", "ac", "road", "hz"] + self.columns
        self.writer = csv.DictWriter(self.csvfile, fieldnames=self.header, extrasaction="ignore")
        self.writer.writeheader()
        self.csvfile.flush()
        meta = {"started": datetime.now().isoformat(), "elm": elm.version, "protocol": elm.protocol,
                "supported_pids": [f"{p:02X}" for p in supported],
                "fast_groups": [[f"{p:02X}" for p in g] for g in self.fast_groups],
                "slow_groups": [[p if p in PSEUDO else f"{p:02X}" for p in g] for g in self.slow_groups],
                "sim": self.args.sim}
        self.base.with_suffix(".meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
        self.log(f"writing {path}")

    # ---- markers -------------------------------------------------------
    def handle_key(self, k: str) -> bool:
        if k == "q":
            return False
        if k not in KEYS:
            return True
        label, key, val = KEYS[k]
        if k == "m":
            self.mark_no += 1
            label = f"m{self.mark_no}"
        else:
            self.state[key] = val
        self.pending_marker.append(label)
        self.log(f"MARK {label} at t={self.elapsed():.1f}s  state={self.state}")
        return True

    # ---- main loop -----------------------------------------------------
    def write_row(self):
        now = datetime.now()
        row = {"time_iso": now.isoformat(timespec="milliseconds"), "t_s": f"{self.elapsed():.3f}",
               "marker": ";".join(self.pending_marker), "hz": f"{self.hz:.2f}", **self.state}
        row.update(self.values)
        self.pending_marker = []
        self.writer.writerow(row)
        self.csvfile.flush()
        if time.monotonic() - self.last_fsync > 5:
            os.fsync(self.csvfile.fileno())
            self.last_fsync = time.monotonic()

    def status_line(self):
        v = self.values
        f = lambda k, fmt="{:.1f}": (fmt.format(v[k]) if isinstance(v.get(k), (int, float)) else "--")
        total = (v["stft"] + v["ltft"]) if isinstance(v.get("stft"), float) and isinstance(v.get("ltft"), float) else None
        line = (f"\r{self.elapsed():7.0f}s {self.hz:4.1f}Hz | {self.state['fuel'][:3]:>3} AC:{self.state['ac'][:3]:<3}"
                f" {self.state['road'][:4]:<4} | STFT {f('stft'):>5} LTFT {f('ltft'):>5} "
                f"SUM {('%.1f' % total) if total is not None else '--':>5} | ECT {f('ect', '{:.0f}'):>3} "
                f"RPM {f('rpm', '{:.0f}'):>4} v{f('speed', '{:.0f}'):>3} {v.get('fuel_status', '--'):<7}")
        sys.stdout.write(line)
        sys.stdout.flush()

    def run(self):
        keys = KeyReader()
        self.log(f"logger start; sim={self.args.sim} port={self.args.port}")
        print(__doc__.split("Keys while logging")[1])
        elm = None
        slow_i = 0
        last_slow = 0.0
        fails = 0
        backoff = 2.0
        running = True
        last_status = 0.0
        STOP_FILE.unlink(missing_ok=True)          # a stale signal from an earlier run
        while running:
            # --- keys (also during reconnects) ---
            for k in keys.poll():
                running = self.handle_key(k) and running
            if KEYS_FILE.exists():
                try:
                    text = KEYS_FILE.read_text(encoding="utf-8")
                    KEYS_FILE.unlink()
                except OSError:
                    text = ""      # still being written - pick it up next cycle
                for k in text.strip().lower():
                    running = self.handle_key(KEYS_UA.get(k, k)) and running
            while self.auto_markers and self.elapsed() >= self.auto_markers[0][0]:
                running = self.handle_key(self.auto_markers.pop(0)[1]) and running
            if self.args.duration and self.elapsed() > self.args.duration:
                running = False
            if STOP_FILE.exists():
                self.log(f"{STOP_FILE.name} file found - stopping")
                STOP_FILE.unlink(missing_ok=True)
                running = False
            if not running:
                break
            # --- connect ---
            if elm is None:
                try:
                    if not self.args.sim and not self.args.port:
                        self.log("searching for ELM327 ...")
                        self.args.port = find_elm_port(self.log)
                        if not self.args.port:
                            raise LinkLost("no ELM327 found on any COM port")
                    elm = self.make_elm()
                    elm.init()
                    self.setup_pids(elm)
                    if "start" not in self.health:
                        self.snapshot_health(elm, "start")
                    fails = 0
                    backoff = 2.0
                except (ElmError, LinkLost) as e:
                    self.log(f"connect failed: {e}; retry in {backoff:.0f}s")
                    if elm:
                        elm.close()
                    elm = None
                    self._sleep_with_keys(keys, backoff)
                    backoff = min(backoff * 1.5, 15)
                    continue
            # --- poll one cycle ---
            try:
                got_any = False
                for g in self.fast_groups:
                    vals = elm.query(g)
                    got_any |= bool(vals)
                    self.values.update(vals)
                period = self.args.slow_period / max(1, len(self.slow_groups))
                if self.slow_groups and time.monotonic() - last_slow >= period:
                    g = self.slow_groups[slow_i % len(self.slow_groups)]
                    slow_i += 1
                    last_slow = time.monotonic()
                    if g == ["RV"]:
                        self.values["elm_v"] = elm.voltage()
                    elif g == ["ATF"]:
                        self.values["atf_c"] = read_atf(elm)
                    else:
                        self.values.update(elm.query(g))
                if got_any:
                    fails = 0
                    self._tick_rate()
                    self.write_row()
                else:
                    fails += 1
                    if fails in (3, 10) or fails % 50 == 0:
                        self.log(f"no data from ECU ({fails} cycles) - engine/ignition off?")
                    if fails >= 10:
                        raise ElmError("ECU silent for 10 cycles, reinitialising")
                    time.sleep(0.2)
            except (ElmError, LinkLost) as e:
                self.log(f"link problem: {e} -> reconnect")
                elm.close()
                elm = None
                if isinstance(e, LinkLost) and self.args.rescan and not self.args.sim:
                    self.args.port = None
            if time.monotonic() - last_status > 0.5:
                self.status_line()
                last_status = time.monotonic()
        if elm:
            self.snapshot_health(elm, "end")
            elm.close()
        self.log("logger stop")
        if self.csvfile:
            self.csvfile.close()
            print(f"\nCSV: {self.base.with_suffix('.csv')}")
        self.eventlog.close()

    def _tick_rate(self):
        self.rate_n += 1
        dt = time.monotonic() - self.rate_t
        if dt >= 2.0:
            self.hz = self.rate_n / dt
            self.rate_n = 0
            self.rate_t = time.monotonic()

    def _sleep_with_keys(self, keys: KeyReader, seconds: float):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            for k in keys.poll():
                if not self.handle_key(k):
                    self.args.duration = 0.001
                    return
            time.sleep(0.1)


def probe(args):
    port = args.port
    if not args.sim and not port:
        port = find_elm_port(print)
        if not port:
            print("ELM327 not found. Is it paired and powered (plugged in, ignition on)?")
            return 1
    if args.sim:
        from sim import SimElm
        elm = Elm327(SimElm())
    else:
        elm = Elm327(port)
    elm.init()
    sup = elm.supported_pids()
    print("Supported PIDs:", " ".join(f"{p:02X}" for p in sup))
    for p in sup:
        if p in PIDS:
            print(f"  {p:02X} {PIDS[p].name:12s} {elm.query([p])}")
    print("ELM voltage:", elm.voltage())
    if not args.no_health:
        print("\nEngine health (read-only, nothing is cleared):")
        for line in format_health(read_health(elm, sup)):
            print("  " + line)
    elm.close()
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", help="COM port, e.g. COM5 (default: auto-detect)")
    ap.add_argument("--list", action="store_true", help="list serial ports and exit")
    ap.add_argument("--probe", action="store_true", help="connect, show supported PIDs, exit")
    ap.add_argument("--sim", action="store_true", help="use built-in simulator instead of a real ELM")
    ap.add_argument("--sim-speed", type=float, default=1.0, help="simulator time scale")
    ap.add_argument("--single", action="store_true", help="one PID per request (no multi-PID)")
    ap.add_argument("--slow-period", type=float, default=4.0, help="seconds to refresh all slow PIDs")
    ap.add_argument("--duration", type=float, default=0, help="stop after N seconds (0 = until q)")
    ap.add_argument("--auto-markers", help="testing: '5:g,20:a' inject keys at given seconds")
    ap.add_argument("--rescan", action="store_true", help="re-detect port after a link loss")
    ap.add_argument("--no-health", action="store_true",
                    help="skip the engine health snapshot (DTCs, readiness, mode 06) at start/end")
    args = ap.parse_args()

    if args.list:
        for dev, desc in list_ports():
            print(f"{dev:8s} {desc}")
        if not list_ports():
            print("no COM ports")
        return 0
    if args.probe:
        return probe(args)
    Logger(args).run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
