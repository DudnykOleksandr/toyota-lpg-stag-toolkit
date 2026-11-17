"""Engine health snapshot over ELM327, read-only: DTCs, readiness monitors, counters, VIN/CALID
and mode 06 on-board test results (Toyota CAN ECUs report misfire counts per cylinder there).

Nothing here clears codes: service 04 is never sent.

    from obd_health import read_health, format_health
    h = read_health(elm, supported_pids)      # dict, JSON-serialisable
    print("\\n".join(format_health(h)))
"""
from __future__ import annotations

from datetime import datetime

from elm327 import Elm327, ElmError

# Readiness monitors, PID 01 byte C (supported) / byte D (incomplete), spark ignition
SPARK_MONITORS = ["catalyst", "heated catalyst", "EVAP", "secondary air", "A/C", "O2 sensor",
                  "O2 heater", "EGR/VVT"]
# byte B bits 0..2 (supported), 4..6 (incomplete)
CONT_MONITORS = ["misfire", "fuel system", "components"]

# Mode 06 OBDMIDs (SAE J1979), the ones a 4-cylinder petrol engine is likely to report
MID_NAMES = {
    0x01: "O2 B1S1", 0x02: "O2 B1S2", 0x03: "O2 B1S3", 0x21: "catalyst B1", 0x31: "EGR", 0x35: "VVT B1",
    0x39: "EVAP 0.150\"", 0x3A: "EVAP 0.090\"", 0x3B: "EVAP 0.040\"", 0x3C: "EVAP 0.020\"", 0x3D: "purge flow",
    0x41: "O2 heater B1S1", 0x42: "O2 heater B1S2", 0x61: "heated cat B1", 0x71: "secondary air",
    0x81: "fuel system B1", 0xA1: "misfire general",
    **{0xA2 + i: f"misfire cyl {i + 1}" for i in range(8)},
}
# Misfire test ids (Toyota): 0B = EWMA over the last 10 driving cycles, 0C = last/current cycle
MISFIRE_TIDS = {0x0B: "avg10", 0x0C: "cycle"}

# Unit and scaling ids (SAE J1979 appendix E): uasid -> (scale, unit); signed ids = 0x80 | base
UAS = {
    0x01: (1, ""), 0x02: (0.1, ""), 0x03: (0.01, ""), 0x04: (0.001, ""), 0x05: (0.0000305, ""),
    0x06: (0.000305, ""), 0x07: (0.25, "rpm"), 0x08: (0.01, "km/h"), 0x09: (1, "km/h"),
    0x0A: (0.122, "mV"), 0x0B: (0.001, "V"), 0x0C: (0.01, "V"), 0x0D: (0.00390625, "mA"),
    0x0E: (0.001, "A"), 0x0F: (0.01, "A"), 0x10: (1, "ms"), 0x11: (100, "ms"), 0x12: (1, "s"),
    0x13: (1, "mOhm"), 0x14: (1, "Ohm"), 0x15: (1, "kOhm"), 0x16: (0.1, "°C"), 0x17: (0.01, "kPa"),
    0x1A: (1, "kPa"), 0x1C: (0.01, "°"), 0x1D: (0.5, "°"), 0x1E: (0.0000305, "λ"), 0x20: (0.0039062, ""),
    0x21: (0.001, "Hz"), 0x22: (1, "Hz"), 0x24: (1, "counts"), 0x25: (1, "km"), 0x26: (0.1, "mV/ms"),
    0x27: (0.01, "g/s"), 0x28: (1, "g/s"), 0x2B: (1, "switches"), 0x2F: (0.01, "%"), 0x30: (0.001526, "%"),
    0x34: (1, "min"), 0x35: (10, "ms"), 0x3C: (0.1, "µs"), 0x3D: (0.01, "mA"),
}


def decode_dtc(a: int, b: int) -> str:
    return "PCBU"[a >> 6] + f"{(a >> 4) & 3}{a & 0x0F:X}{b:02X}"


def _u16(d, i):
    return d[i] * 256 + d[i + 1]


def _scaled(uasid: int, raw: int):
    signed = uasid & 0x80
    if signed and raw >= 0x8000:
        raw -= 0x10000
    scale, unit = UAS.get(uasid & 0x7F, (1, "raw"))
    v = raw * scale
    if uasid == 0x16:          # unsigned temperature has a -40 °C offset
        v -= 40
    return round(v, 6), unit


class HealthReader:
    def __init__(self, elm: Elm327, supported: list[int]):
        self.elm = elm
        self.supported = set(supported)

    def req(self, service: int, rest: str = "", timeout: float = 4) -> list[int] | None:
        """Send one request; payload after the positive response byte, or None."""
        assert service != 0x04, "never clear codes"
        resp = self.elm._parse(self.elm.cmd(f"{service:02X}{rest}", timeout=timeout))
        return resp.get(service + 0x40)

    def dtcs(self, service: int) -> list[str] | None:
        d = self.req(service)
        if d is None:
            return None
        if self.elm.is_can and d:
            d = d[1:1 + 2 * d[0]]          # CAN: leading byte = number of codes
        return [decode_dtc(d[i], d[i + 1]) for i in range(0, len(d) - 1, 2) if d[i] or d[i + 1]]

    def pid(self, p: int) -> list[int] | None:
        if p not in self.supported:
            return None
        d = self.req(0x01, f"{p:02X}")
        return d[1:] if d and d[0] == p else None

    def readiness(self) -> dict | None:
        d = self.pid(0x01)
        if not d or len(d) < 4:
            return None
        a, b, c, dd = d[:4]
        mons = {}
        for i, name in enumerate(CONT_MONITORS):
            if b & (1 << i):
                mons[name] = "incomplete" if b & (1 << (i + 4)) else "ready"
        if not b & 0x08:                   # spark ignition
            for i, name in enumerate(SPARK_MONITORS):
                if c & (1 << i):
                    mons[name] = "incomplete" if dd & (1 << i) else "ready"
        return {"mil_on": bool(a & 0x80), "dtc_count": a & 0x7F, "monitors": mons}

    def counters(self) -> dict:
        out = {}
        for p, name in ((0x21, "km_with_mil"), (0x31, "km_since_clear"), (0x4D, "min_with_mil"),
                        (0x4E, "min_since_clear")):
            d = self.pid(p)
            if d and len(d) >= 2:
                out[name] = _u16(d, 0)
        d = self.pid(0x30)
        if d:
            out["warmups_since_clear"] = d[0]
        return out

    def vehicle_info(self) -> dict:
        out = {}
        d = self.req(0x09, "02", timeout=5)
        if d and len(d) > 2:
            out["vin"] = bytes(x for x in d[2:] if 32 < x < 127).decode()
        d = self.req(0x09, "04", timeout=5)
        if d and len(d) > 2:
            raw = bytes(d[2:])
            out["calid"] = [raw[i:i + 16].rstrip(b"\x00").decode("ascii", "replace")
                            for i in range(0, len(raw), 16) if raw[i:i + 16].strip(b"\x00")]
        return out

    def mode06_mids(self) -> list[int]:
        mids, base = [], 0x00
        while base <= 0xE0:
            d = self.req(0x06, f"{base:02X}")
            if not d or len(d) < 5 or d[0] != base:
                break
            bits = int.from_bytes(bytes(d[1:5]), "big")
            mids += [base + i + 1 for i in range(32) if bits & (1 << (31 - i)) and (i + 1) % 0x20]
            if not bits & 1:
                break
            base += 0x20
        return mids

    def mode06(self) -> list[dict]:
        tests = []
        for mid in self.mode06_mids():
            d = self.req(0x06, f"{mid:02X}")
            if not d:
                continue
            for i in range(0, len(d) - 8, 9):   # records: MID TID UASID value min max (u16 each)
                m, tid, uas = d[i], d[i + 1], d[i + 2]
                raw, lo, hi = _u16(d, i + 3), _u16(d, i + 5), _u16(d, i + 7)
                v, unit = _scaled(uas, raw)
                vlo, _ = _scaled(uas, lo)
                vhi, _ = _scaled(uas, hi)
                tests.append({"mid": f"{m:02X}", "name": MID_NAMES.get(m, f"MID {m:02X}"), "tid": f"{tid:02X}",
                              "uasid": f"{uas:02X}", "value": v, "min": vlo, "max": vhi, "unit": unit,
                              "pass": (vlo <= v <= vhi)})
        return tests


def read_health(elm: Elm327, supported: list[int], mode06: bool = True, log=print) -> dict:
    """Read everything read-only; each part is optional (clone ELMs / ECUs answer NO DATA to some)."""
    r = HealthReader(elm, supported)
    h: dict = {"time": datetime.now().isoformat(timespec="seconds")}
    for key, fn in (("readiness", r.readiness), ("dtc_stored", lambda: r.dtcs(0x03)),
                    ("dtc_pending", lambda: r.dtcs(0x07)), ("dtc_permanent", lambda: r.dtcs(0x0A)),
                    ("counters", r.counters), ("vehicle", r.vehicle_info),
                    ("mode06", r.mode06 if mode06 else lambda: None)):
        try:
            h[key] = fn()
        except ElmError as e:
            log(f"  health: {key} failed: {e}")
            h[key] = None
    return h


def misfires(h: dict) -> dict[str, dict[str, float]]:
    """{'cyl 1': {'avg10': x, 'cycle': y}, ...} from mode 06 MIDs A2.."""
    out: dict[str, dict[str, float]] = {}
    for t in h.get("mode06") or []:
        m = int(t["mid"], 16)
        if 0xA2 <= m <= 0xA9 and int(t["tid"], 16) in MISFIRE_TIDS:
            out.setdefault(f"cyl {m - 0xA1}", {})[MISFIRE_TIDS[int(t["tid"], 16)]] = t["value"]
    return out


def format_health(h: dict) -> list[str]:
    out = []
    rd = h.get("readiness")
    if rd:
        out.append(f"MIL (Check Engine): {'ON' if rd['mil_on'] else 'off'}, stored codes: {rd['dtc_count']}")
        inc = [k for k, v in rd["monitors"].items() if v == "incomplete"]
        out.append("readiness: " + ", ".join(f"{k} {'READY' if v == 'ready' else 'not ready'}"
                                             for k, v in rd["monitors"].items()))
        if inc:
            out.append(f"  not ready: {', '.join(inc)} (normal for a while after codes were cleared / battery off)")
    for key, title in (("dtc_stored", "stored DTC"), ("dtc_pending", "pending DTC"), ("dtc_permanent", "permanent DTC")):
        v = h.get(key)
        out.append(f"{title}: " + ("n/a" if v is None else (", ".join(v) if v else "none")))
    c = h.get("counters") or {}
    if c:
        out.append("counters: " + ", ".join(f"{k}={v}" for k, v in c.items()))
    veh = h.get("vehicle") or {}
    if veh:
        out.append("vehicle: " + ", ".join(f"{k}={v}" for k, v in veh.items()))
    mis = misfires(h)
    if mis:
        out.append("misfires (mode 06; avg10 = average of last 10 driving cycles, cycle = current/last):")
        for cyl, v in sorted(mis.items()):
            out.append(f"  {cyl}: " + ", ".join(f"{k} {v[k]:g}" for k in ("avg10", "cycle") if k in v))
    tests = [t for t in h.get("mode06") or [] if not ("misfire" in t["name"] and t["name"] != "misfire general")]
    if tests:
        out.append(f"mode 06 tests ({sum(not t['pass'] for t in tests)} out of limits):")
        for t in tests:
            flag = "OK  " if t["pass"] else "FAIL"
            out.append(f"  {flag} {t['name']:<16s} TID {t['tid']}: {t['value']:g} {t['unit']} "
                       f"[{t['min']:g} .. {t['max']:g}]")
    elif h.get("mode06") is not None:
        out.append("mode 06: no results (monitors have not run since codes were cleared)")
    return out
