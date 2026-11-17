"""Fake ELM327 + Toyota ECM for testing the logger and the analysis without a car.

Behaves like a serial port (write / read_until / reset_input_buffer / timeout).
The engine model is crude but produces the effects we look for:
  - coolant warm-up curve, closed loop after warm-up
  - STFT noise, LTFT slowly following STFT
  - on gas: mixture offset by GAS_RICH_PCT (negative trims) - a "badly calibrated Stag"
  - A/C adds load / MAF
"""
from __future__ import annotations

import math
import random
import time

GAS_RICH_PCT = 6.0      # gas pours 6 % too much fuel -> trims go ~ -6 %
AC_LOAD_PCT = 12.0

SUPPORTED = [0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x14, 0x15,
             0x1F, 0x33, 0x3C, 0x42, 0x43, 0x44, 0x46]


def _bitmap(base: int) -> list[int]:
    bits = 0
    for p in SUPPORTED + [0x20, 0x40]:
        if base < p <= base + 0x20:
            bits |= 1 << (32 - (p - base))
    return list(bits.to_bytes(4, "big"))


class SimElm:
    def __init__(self, state=lambda: {}, latency: float = 0.035, time_scale: float = 1.0):
        self.state = state          # callable -> {"fuel": "gas"/"petrol", "ac": bool, "road": ...}
        self.latency = latency
        self.time_scale = time_scale
        self.timeout = 2.0
        self._out = b""
        self._buf = b""
        self.headers = False
        self.spaces = True
        self.t0 = time.monotonic()
        self.ltft = 2.0
        self.stft = 0.0
        self.o2_phase = 0.0

    # serial-like API
    def reset_input_buffer(self):
        self._out = b""

    def write(self, data: bytes):
        self._buf += data
        while b"\r" in self._buf:
            line, self._buf = self._buf.split(b"\r", 1)
            self._out += self._handle(line.decode().strip().upper()).encode() + b"\r\r>"

    def read_until(self, expected=b">"):
        time.sleep(self.latency)
        out, self._out = self._out, b""
        return out

    def close(self):
        pass

    # ELM behaviour
    def _handle(self, line: str) -> str:
        if not line:
            return ""
        if line.startswith("AT"):
            c = line[2:].replace(" ", "")
            if c == "Z":
                self.headers = False
                return "ELM327 v1.5 (sim)"
            if c.startswith("H"):
                self.headers = c == "H1"
            if c.startswith("S") and c[1:] in ("0", "1"):
                self.spaces = c == "S1"
            if c == "DP":
                return "AUTO, ISO 15765-4 (CAN 11/500)"
            if c == "DPN":
                return "A6"
            if c == "RV":
                return f"{13.9 + random.uniform(-0.1, 0.1):.1f}V"
            return "OK"
        req = line.split()[0]
        if not req.startswith("01"):
            return "NO DATA"
        pids = [int(req[i:i + 2], 16) for i in range(2, len(req), 2)]
        payload = [0x41]
        for p in pids:
            d = self._pid(p)
            if d is not None:
                payload += [p] + d
        if len(payload) == 1:
            return "NO DATA"
        return self._frame(payload)

    def _frame(self, payload: list[int]) -> str:
        sep = " " if self.spaces else ""
        hx = lambda bs: sep.join(f"{b:02X}" for b in bs)
        if len(payload) <= 7:
            body = [len(payload)] + payload
            return (f"7E8{sep}" if self.headers else "") + hx(body if self.headers else payload)
        # ISO-TP multi-frame
        if not self.headers:
            lines = [f"{len(payload):03X}"]
            chunks = [payload[:6]] + [payload[i:i + 7] for i in range(6, len(payload), 7)]
            lines += [f"{i}: {hx(c)}" for i, c in enumerate(chunks)]
            return "\r".join(lines)
        lines = [f"7E8{sep}" + hx([0x10 | (len(payload) >> 8), len(payload) & 0xFF] + payload[:6])]
        seq = 1
        for i in range(6, len(payload), 7):
            lines.append(f"7E8{sep}" + hx([0x20 | (seq & 0x0F)] + payload[i:i + 7]))
            seq += 1
        return "\r".join(lines)

    # engine model
    def _model(self) -> dict:
        t = (time.monotonic() - self.t0) * self.time_scale
        st = self.state() or {}
        gas = st.get("fuel") == "gas"
        ac = st.get("ac") is True
        road = st.get("road", "city")
        ect = min(90.0, 20 + t * 0.12)
        if road == "highway":
            speed = 80 + 10 * math.sin(t / 60)
            rpm = speed * 26 + random.uniform(-40, 40)
            load = 38 + 6 * math.sin(t / 17)
        elif road == "idle":
            speed, rpm, load = 0.0, 700 + random.uniform(-20, 20), 22.0
        else:
            ph = (t % 60) / 60
            speed = max(0.0, 50 * math.sin(math.pi * ph))
            rpm = 750 + speed * 40 + random.uniform(-60, 60)
            load = 25 + 45 * max(0.0, math.cos(math.pi * ph)) * (speed > 3)
        if ac:
            load += AC_LOAD_PCT
        closed = ect > 60
        error = (GAS_RICH_PCT if gas else 0.0) * (0.7 + 0.6 * load / 100)
        target_total = -error - 0.5
        if closed:
            self.stft += 0.15 * (target_total - self.ltft - self.stft) + random.gauss(0, 1.2)
            self.stft = max(-25, min(25, self.stft))
            self.ltft += 0.004 * self.stft
        else:
            self.stft = 0.0
        self.o2_phase += 1.3 + random.uniform(-0.3, 0.3)
        o2 = 0.45 + 0.38 * math.sin(self.o2_phase) if closed else 0.1
        maf = rpm * load / 100 * 1.6 / 120 * 1.2
        return dict(t=t, ect=ect, speed=speed, rpm=rpm, load=min(load, 100), closed=closed,
                    o2=o2, maf=maf)

    def _pid(self, p: int):
        m = self._model()
        enc_trim = lambda v: [max(0, min(255, round((v + 100) * 128 / 100)))]
        if p in (0x00, 0x20, 0x40):
            return _bitmap(p)
        if p not in SUPPORTED:
            return None
        rpm = round(m["rpm"] * 4)
        maf = round(m["maf"] * 100)
        return {
            0x03: [2 if m["closed"] else 1, 0],
            0x04: [round(m["load"] * 255 / 100)],
            0x05: [round(m["ect"] + 40)],
            0x06: enc_trim(self.stft),
            0x07: enc_trim(self.ltft),
            0x0C: [rpm >> 8, rpm & 0xFF],
            0x0D: [round(m["speed"])],
            0x0E: [round((12 + 64) * 2)],
            0x0F: [round(30 + 40)],
            0x10: [maf >> 8, maf & 0xFF],
            0x11: [round((12 + m["load"] * 0.4) * 255 / 100)],
            0x14: [round(m["o2"] * 200), 0xFF],
            0x15: [round(0.68 * 200), 0xFF],
            0x1F: [int(m["t"]) >> 8 & 0xFF, int(m["t"]) & 0xFF],
            0x33: [100],
            0x3C: [(v := round((min(700, 200 + m["t"] * 2) + 40) * 10)) >> 8, v & 0xFF],
            0x42: [(v := 14100) >> 8, v & 0xFF],
            0x43: [0, round(m["load"] * 0.9 * 255 / 100)],
            0x44: [0x80, 0x00],
            0x46: [round(22 + 40)],
        }[p]
