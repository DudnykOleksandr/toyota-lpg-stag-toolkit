"""Find the automatic gearbox fluid (ATF) temperature on the Toyota transmission ECU. Read-only.

Toyota ECUs keep extra data behind service 21 ("read data by local identifier"), which only
reads. The TCM answers on CAN 7E1 -> 7E9. Android scanners show the ATF temperature from one
of these blocks; which byte it is differs by gearbox (U340E etc.), so this scans them all and
marks bytes that decode as a plausible temperature (raw - 40 in 0..130 degC).

Usage (ignition on, the phone app disconnected from the ELM):
    python tcm_probe.py --port COM4            # scan 21 01..FF, print answering blocks
    python tcm_probe.py --port COM4 --watch 82 # poll block 21 82 every second (warm-up check)
"""
from __future__ import annotations

import argparse
import time

from elm327 import Elm327, ElmError, LinkLost

TCM_TX, TCM_RX = "7E1", "7E9"


def tcm_request(elm: Elm327, req: str) -> list[int] | None:
    """Payload after the positive response byte, parsed from TCM frames (7E9)."""
    lines = elm.cmd(req, timeout=3)
    frames, payload, expected = [], [], 0
    for ln in lines:
        tok = ln.split()
        if len(tok) > 1 and tok[0].upper() == TCM_RX:
            frames.append([int(t, 16) for t in tok[1:] if len(t) == 2])
    for f in frames:
        kind = f[0] >> 4
        if kind == 0:
            payload, expected = f[1:1 + (f[0] & 0x0F)], f[0] & 0x0F
            break
        if kind == 1:
            expected, payload = ((f[0] & 0x0F) << 8) | f[1], f[2:]
        elif kind == 2:
            payload += f[1:]
    payload = payload[:expected] if expected else payload
    sid = int(req[:2], 16) + 0x40
    return payload[1:] if payload and payload[0] == sid else None


def temp_candidates(block: int, data: list[int]) -> list[str]:
    # data[0] echoes the local id; the rest are values
    return [f"byte {i} = {b - 40} degC" for i, b in enumerate(data[1:], 1) if 40 <= b <= 170]


def open_tcm(port: str) -> Elm327:
    elm = Elm327(port, timeout=3, log=lambda *a: None)
    elm.init()                        # also selects 7E0; switch to the TCM below
    for c in (f"ATSH{TCM_TX}", f"ATCRA{TCM_RX}"):
        elm.cmd(c)
    return elm


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", default="COM4")
    ap.add_argument("--watch", help="local id (hex) to poll every second, e.g. 82")
    args = ap.parse_args()
    try:
        elm = open_tcm(args.port)
    except (ElmError, LinkLost) as e:
        print(f"connect failed: {e}\n(ignition on? phone scanner disconnected from the ELM?)")
        return 1
    try:
        if args.watch:
            lid = int(args.watch, 16)
            while True:
                d = tcm_request(elm, f"21{lid:02X}")
                print(time.strftime("%H:%M:%S"), " ".join(f"{b:02X}" for b in d or []),
                      "|", "; ".join(temp_candidates(lid, d or [])))
                time.sleep(1)
        print("standard 0100 from TCM:", tcm_request(elm, "0100"))
        for lid in range(0x01, 0x100):
            d = tcm_request(elm, f"21{lid:02X}")
            if d:
                print(f"21{lid:02X}: {' '.join(f'{b:02X}' for b in d)}")
                cand = temp_candidates(lid, d)
                if cand:
                    print("       temperature-like: " + "; ".join(cand))
    except KeyboardInterrupt:
        pass
    finally:
        elm.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
