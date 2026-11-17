"""Minimal ELM327 driver tuned for fast OBD-II mode 01 polling over Bluetooth SPP.

Why not python-OBD: it sends one PID per request. On CAN (ISO 15765-4) one mode 01
request may carry up to 6 PIDs, and a response that fits into a single CAN frame
(<= 7 data bytes) returns without ISO-TP flow control. Packing PIDs into such groups
roughly doubles the useful sample rate over a slow Bluetooth link.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable

import serial
import serial.tools.list_ports


# ---------------------------------------------------------------------------
# PID table: pid -> (column name, data bytes, decoder)
# Decoders take the raw data bytes (list[int]) and return a dict of columns.
# ---------------------------------------------------------------------------

def _trim(a: int) -> float:
    return round(a * 100.0 / 128.0 - 100.0, 2)


def _o2_narrow(prefix: str):
    def dec(d):
        out = {f"{prefix}_v": round(d[0] / 200.0, 3)}
        # second byte = per-sensor trim; 0xFF means "not used for trim"
        out[f"{prefix}_trim"] = None if d[1] == 0xFF else _trim(d[1])
        return out
    return dec


FUEL_STATUS = {1: "OL_cold", 2: "CL", 4: "OL_load", 8: "OL_fault", 16: "CL_fault"}


@dataclass(frozen=True)
class Pid:
    pid: int
    name: str
    nbytes: int
    decode: Callable[[list[int]], dict]


PIDS: dict[int, Pid] = {p.pid: p for p in [
    Pid(0x03, "fuel_status", 2, lambda d: {"fuel_status": FUEL_STATUS.get(d[0], f"0x{d[0]:02X}")}),
    Pid(0x04, "load", 1, lambda d: {"load": round(d[0] * 100 / 255, 1)}),
    Pid(0x05, "ect", 1, lambda d: {"ect": d[0] - 40}),
    Pid(0x06, "stft", 1, lambda d: {"stft": _trim(d[0])}),
    Pid(0x07, "ltft", 1, lambda d: {"ltft": _trim(d[0])}),
    Pid(0x0B, "map", 1, lambda d: {"map": d[0]}),
    Pid(0x0C, "rpm", 2, lambda d: {"rpm": round((d[0] * 256 + d[1]) / 4)}),
    Pid(0x0D, "speed", 1, lambda d: {"speed": d[0]}),
    Pid(0x0E, "timing", 1, lambda d: {"timing": d[0] / 2 - 64}),
    Pid(0x0F, "iat", 1, lambda d: {"iat": d[0] - 40}),
    Pid(0x10, "maf", 2, lambda d: {"maf": round((d[0] * 256 + d[1]) / 100, 2)}),
    Pid(0x11, "tps", 1, lambda d: {"tps": round(d[0] * 100 / 255, 1)}),
    Pid(0x14, "o2s1", 2, _o2_narrow("o2s1")),
    Pid(0x15, "o2s2", 2, _o2_narrow("o2s2")),
    Pid(0x1F, "runtime", 2, lambda d: {"runtime": d[0] * 256 + d[1]}),
    Pid(0x24, "wr1", 4, lambda d: {"wr1_lambda": round((d[0] * 256 + d[1]) * 2 / 65536, 4),
                                    "wr1_v": round((d[2] * 256 + d[3]) * 8 / 65536, 3)}),
    Pid(0x33, "baro", 1, lambda d: {"baro": d[0]}),
    Pid(0x34, "wr1c", 4, lambda d: {"wr1_lambda": round((d[0] * 256 + d[1]) * 2 / 65536, 4),
                                     "wr1_ma": round((d[2] * 256 + d[3]) / 256 - 128, 3)}),
    Pid(0x3C, "cat_temp", 2, lambda d: {"cat_temp": round((d[0] * 256 + d[1]) / 10 - 40, 1)}),
    Pid(0x42, "ecu_v", 2, lambda d: {"ecu_v": round((d[0] * 256 + d[1]) / 1000, 2)}),
    Pid(0x43, "abs_load", 2, lambda d: {"abs_load": round((d[0] * 256 + d[1]) * 100 / 255, 1)}),
    Pid(0x44, "cmd_lambda", 2, lambda d: {"cmd_lambda": round((d[0] * 256 + d[1]) * 2 / 65536, 4)}),
    Pid(0x46, "ambient", 1, lambda d: {"ambient": d[0] - 40}),
]}

# Columns each PID produces (for the CSV header)
PID_COLUMNS: dict[int, list[str]] = {
    0x14: ["o2s1_v", "o2s1_trim"],
    0x15: ["o2s2_v", "o2s2_trim"],
    0x24: ["wr1_lambda", "wr1_v"],
    0x34: ["wr1_lambda", "wr1_ma"],
}


def columns_for(pid: int) -> list[str]:
    return PID_COLUMNS.get(pid, [PIDS[pid].name])


ELM_ERRORS = ("NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUS INIT", "BUS ERROR", "BUS BUSY",
              "STOPPED", "BUFFER FULL", "FB ERROR", "DATA ERROR", "RX ERROR", "LV RESET",
              "ACT ALERT", "ERR")


class ElmError(Exception):
    pass


class LinkLost(Exception):
    """Serial link is gone (Bluetooth dropped, port vanished)."""


def list_ports() -> list[tuple[str, str]]:
    return [(p.device, p.description) for p in serial.tools.list_ports.comports()]


def pack_groups(pids: list[int], capacity: int = 6) -> list[list[int]]:
    """First-fit-decreasing packing so every response fits one CAN frame.

    A single frame carries 7 data bytes: 1 byte for the 0x41 mode echo, then
    (1 PID byte + N data bytes) per PID -> capacity 6 for the PID payload.
    """
    groups: list[tuple[int, list[int]]] = []
    for pid in sorted(pids, key=lambda p: -(1 + PIDS[p].nbytes)):
        cost = 1 + PIDS[pid].nbytes
        for i, (used, g) in enumerate(groups):
            if used + cost <= capacity:
                groups[i] = (used + cost, g + [pid])
                break
        else:
            groups.append((cost, [pid]))
    return [g for _, g in groups]


class Elm327:
    def __init__(self, port, baudrate: int = 38400, timeout: float = 2.0, log=print):
        """`port` is a COM port name or an already-open serial-like object (simulator)."""
        self.log = log
        self._port_arg = port
        self.baudrate = baudrate
        self.timeout = timeout
        self.ser = None
        self.version = ""
        self.protocol = ""
        self.is_can = False
        self.headers = False
        self.count_suffix = False   # "010C 1": ELM returns after the first response
        self.multi_pid_ok = True

    # ---- low level ---------------------------------------------------------
    def open(self):
        if isinstance(self._port_arg, str):
            try:
                self.ser = serial.Serial(self._port_arg, self.baudrate, timeout=self.timeout,
                                         write_timeout=self.timeout)
            except (serial.SerialException, OSError) as e:
                raise LinkLost(str(e)) from e
        else:
            self.ser = self._port_arg

    def close(self):
        try:
            if self.ser is not None and isinstance(self._port_arg, str):
                self.ser.close()
        except Exception:
            pass
        self.ser = None

    def cmd(self, line: str, timeout: float | None = None) -> list[str]:
        if self.ser is None:
            raise LinkLost("port not open")
        try:
            self.ser.reset_input_buffer()
            self.ser.write((line + "\r").encode("ascii"))
            old = self.ser.timeout
            if timeout is not None:
                self.ser.timeout = timeout
            raw = self.ser.read_until(b">")
            if timeout is not None:
                self.ser.timeout = old
        except (serial.SerialException, OSError) as e:
            raise LinkLost(str(e)) from e
        if not raw.endswith(b">"):
            raise ElmError(f"timeout on {line!r} (got {raw!r})")
        text = raw[:-1].decode("ascii", errors="replace").replace("\n", "\r")
        lines = [ln.strip() for ln in text.split("\r") if ln.strip()]
        # drop command echo if ATE0 has not taken effect yet
        if lines and lines[0].replace(" ", "") == line.replace(" ", ""):
            lines = lines[1:]
        lines = [ln for ln in lines if not ln.startswith("SEARCHING")]
        return lines

    # ---- init --------------------------------------------------------------
    def init(self):
        self.open()
        # wake up / flush: a lone CR aborts anything pending
        try:
            self.ser.write(b"\r")
            time.sleep(0.3)
            self.ser.reset_input_buffer()
        except (serial.SerialException, OSError) as e:
            raise LinkLost(str(e)) from e
        resp = self.cmd("ATZ", timeout=5)
        self.version = " ".join(resp) or "?"
        for c in ("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0"):
            self.cmd(c)
        # force protocol detection; ignition must be on
        resp = self.cmd("0100", timeout=10)
        if not resp or any(e in " ".join(resp) for e in ELM_ERRORS):
            raise ElmError(f"ECU not answering on 0100: {resp} (ignition on?)")
        self.protocol = " ".join(self.cmd("ATDP"))
        dpn = " ".join(self.cmd("ATDPN")).replace("A", "")
        self.is_can = dpn[:1] in "6789" if dpn else False
        if self.is_can:
            # Headers on + physical addressing to the engine ECU only (7E0 -> 7E8).
            # The automatic gearbox ECU would otherwise also answer some PIDs.
            self.cmd("ATH1")
            self.headers = True
            for c in ("ATSH7E0", "ATCRA7E8"):
                if "?" in self.cmd(c):
                    self.log(f"ELM ignored {c} (clone?) - filtering by header in software")
            self.count_suffix = self._probe_count_suffix()
        self.log(f"ELM: {self.version} | protocol: {self.protocol} | CAN={self.is_can} "
                 f"| count-suffix={self.count_suffix}")

    def _probe_count_suffix(self) -> bool:
        try:
            r = self.cmd("010D 1", timeout=2)
            return bool(self._parse(r).get(0x41))
        except ElmError:
            return False

    # ---- parsing -----------------------------------------------------------
    def _parse(self, lines: list[str]) -> dict[int, list[int]]:
        """Return {service_response_byte: payload bytes} for the engine ECU.

        Handles single frames, ISO-TP multi-frame (headers on) and headerless output.
        """
        joined = " ".join(lines)
        if "?" in lines or any(e in joined for e in ELM_ERRORS):
            return {}
        if not self.headers:
            data: list[int] = []
            for ln in lines:
                if ":" in ln:          # "0: 41 00 ..." multi-line without headers
                    ln = ln.split(":", 1)[1]
                elif len(ln.replace(" ", "")) <= 3:
                    continue           # byte-count line of a multi-frame message
                data += _hex_bytes(ln)
            return {data[0]: data[1:]} if data else {}
        frames = []
        for ln in lines:
            tok = ln.split()
            if len(tok) < 2:
                continue
            if tok[0].upper() != "7E8":
                continue
            frames.append([int(t, 16) for t in tok[1:] if _is_hex(t)])
        payload: list[int] = []
        expected = 0
        for f in frames:
            if not f:
                continue
            kind = f[0] >> 4
            if kind == 0:
                payload = f[1:1 + (f[0] & 0x0F)]
                expected = len(payload)
                break
            if kind == 1:
                expected = ((f[0] & 0x0F) << 8) | f[1]
                payload = f[2:]
            elif kind == 2:
                payload += f[1:]
        payload = payload[:expected] if expected else payload
        return {payload[0]: payload[1:]} if payload else {}

    # ---- queries -----------------------------------------------------------
    def query_raw(self, pids: list[int]) -> dict[int, list[int]]:
        """Mode 01 request for up to 6 PIDs; returns {pid: data bytes}."""
        req = "01" + "".join(f"{p:02X}" for p in pids)
        if self.count_suffix:
            req += " 1"
        resp = self._parse(self.cmd(req))
        data = resp.get(0x41, [])
        out: dict[int, list[int]] = {}
        i = 0
        while i < len(data):
            pid = data[i]
            if pid not in PIDS:
                break
            n = PIDS[pid].nbytes
            if i + 1 + n > len(data):
                break
            out[pid] = data[i + 1:i + 1 + n]
            i += 1 + n
        return out

    def query(self, pids: list[int]) -> dict:
        values: dict = {}
        for pid, d in self.query_raw(pids).items():
            try:
                values.update(PIDS[pid].decode(d))
            except Exception:
                pass
        return values

    def supported_pids(self) -> list[int]:
        """Read the 0100/0120/0140 support bitmaps."""
        supported: list[int] = []
        base = 0x00
        while base <= 0x40:
            resp = self._parse(self.cmd(f"01{base:02X}"))
            data = resp.get(0x41, [])
            if len(data) < 5 or data[0] != base:
                break
            bits = int.from_bytes(bytes(data[1:5]), "big")
            for i in range(32):
                if bits & (1 << (31 - i)):
                    supported.append(base + i + 1)
            if not bits & 1:          # PID base+0x20 not supported -> stop
                break
            base += 0x20
        return supported

    def voltage(self) -> float | None:
        try:
            r = self.cmd("ATRV")
            return float(r[0].rstrip("Vv")) if r else None
        except (ElmError, ValueError, IndexError):
            return None


def _is_hex(t: str) -> bool:
    try:
        int(t, 16)
        return len(t) == 2
    except ValueError:
        return False


def _hex_bytes(s: str) -> list[int]:
    s = s.replace(" ", "")
    return [int(s[i:i + 2], 16) for i in range(0, len(s) - 1, 2) if _is_hex(s[i:i + 2])]
