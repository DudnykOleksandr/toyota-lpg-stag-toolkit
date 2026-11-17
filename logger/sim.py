"""Fake ELM327 + Toyota ECM for testing the logger and the analysis without a car.

Behaves like a serial port (write / read_until / reset_input_buffer / timeout).
`python sim.py --tcp 35000` serves it over TCP for the Android app (Settings -> Simulator) and its
tests; there the fuel alternates gas/petrol every --fuel-period seconds, gas first.
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

SUPPORTED = [0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x14, 0x15,
             0x1F, 0x21, 0x30, 0x31, 0x33, 0x3C, 0x42, 0x43, 0x44, 0x46, 0x4D, 0x4E]

# Health data: no stored codes, one pending misfire code, catalyst/O2 monitors not run yet
DTC_PENDING = [(0x03, 0x02)]                      # P0302
MODE06 = {   # MID -> [(TID, UASID, value, min, max)]
    0x01: [(0x01, 0x0A, 3690, 0, 8196)],          # O2 B1S1 rich-to-lean threshold, mV
    0x21: [(0x91, 0x01, 22, 0, 30)],              # catalyst oxygen storage
    0xA1: [(0x0B, 0x24, 5, 0, 0xFFFF)],
    0xA2: [(0x0B, 0x24, 0, 0, 0xFFFF), (0x0C, 0x24, 0, 0, 0xFFFF)],
    0xA3: [(0x0B, 0x24, 4, 0, 0xFFFF), (0x0C, 0x24, 12, 0, 0xFFFF)],
    0xA4: [(0x0B, 0x24, 0, 0, 0xFFFF), (0x0C, 0x24, 0, 0, 0xFFFF)],
    0xA5: [(0x0B, 0x24, 1, 0, 0xFFFF), (0x0C, 0x24, 0, 0, 0xFFFF)],
}


def _bitmap(base: int, ids=None) -> list[int]:
    bits = 0
    ids = SUPPORTED + [0x20, 0x40] if ids is None else ids
    for p in ids:
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
            return self._other_service(req)
        pids = [int(req[i:i + 2], 16) for i in range(2, len(req), 2)]
        payload = [0x41]
        for p in pids:
            d = self._pid(p)
            if d is not None:
                payload += [p] + d
        if len(payload) == 1:
            return "NO DATA"
        return self._frame(payload)

    def _other_service(self, req: str) -> str:
        """Read-only services used by obd_health: 03/07/0A codes, 06 test results, 09 vehicle info."""
        svc = req[:2]
        if svc in ("03", "07", "0A"):
            codes = DTC_PENDING if svc == "07" else []
            return self._frame([int(svc, 16) + 0x40, len(codes)] + [b for c in codes for b in c])
        if svc == "06" and len(req) == 4:
            mid = int(req[2:], 16)
            if mid % 0x20 == 0:
                mids = list(MODE06) + [m for m in range(0x20, 0xA1, 0x20)]
                return self._frame([0x46, mid] + _bitmap(mid, mids))
            if mid in MODE06:
                recs = []
                for tid, uas, v, lo, hi in MODE06[mid]:
                    recs += [mid, tid, uas, v >> 8, v & 0xFF, lo >> 8, lo & 0xFF, hi >> 8, hi & 0xFF]
                return self._frame([0x46] + recs)
        if req == "0902":
            return self._frame([0x49, 0x02, 0x01] + list(b"SIMVIN0ZRE15200001"[:17]))
        if req == "0904":
            return self._frame([0x49, 0x04, 0x01] + list(b"SIMCAL1ZR0000000"))
        return "NO DATA"

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
            0x01: [0x00, 0x07, 0x65, 0x21],          # MIL off, 0 codes; catalyst + O2 monitors incomplete
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
            0x21: [0, 0],
            0x30: [3],
            0x31: [0, 42],
            0x4D: [0, 0],
            0x4E: [0, 55],
        }[p]


def serve_tcp(port: int, time_scale: float = 1.0, fuel_period: float = 240.0, road: str = "city"):
    """One simulated car per TCP connection. The app cannot tell the simulator its markers, so the
    fuel follows a fixed schedule (gas first) - tap the matching markers to get a gas/petrol comparison."""
    import socket

    srv = socket.create_server(("0.0.0.0", port))
    print(f"sim ELM327 on tcp port {port}; fuel gas/petrol every {fuel_period:g} s (gas first), road={road}, "
          f"time x{time_scale:g}", flush=True)
    while True:
        conn, addr = srv.accept()
        t0 = time.monotonic()

        def state(t0=t0):
            fuel = "gas" if int((time.monotonic() - t0) // fuel_period) % 2 == 0 else "petrol"
            return {"fuel": fuel, "ac": False, "road": road}

        sim = SimElm(state=state, latency=0.0, time_scale=time_scale)
        print(f"connect from {addr[0]}:{addr[1]}", flush=True)
        with conn:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            try:
                while True:
                    data = conn.recv(256)
                    if not data:
                        break
                    time.sleep(0.035)             # roughly a Bluetooth ELM round trip
                    sim.write(data)
                    out = sim.read_until()
                    if out:
                        conn.sendall(out)
            except OSError as e:
                print(f"connection error: {e}", flush=True)
        print("disconnected", flush=True)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="ELM327 + Toyota ECM simulator over TCP")
    ap.add_argument("--tcp", type=int, default=35000, help="TCP port (default 35000)")
    ap.add_argument("--sim-speed", type=float, default=1.0, help="engine model time scale (warm-up faster)")
    ap.add_argument("--fuel-period", type=float, default=240.0, help="seconds per gas / petrol stretch")
    ap.add_argument("--road", default="city", choices=["city", "highway", "idle"])
    a = ap.parse_args()
    serve_tcp(a.tcp, a.sim_speed, a.fuel_period, a.road)
