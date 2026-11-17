"""Analyse an ELM log: LPG vs petrol fuel trims, O2, coolant, A/C effect, consumption estimate.

Usage:
    python analyze.py                      # newest logs/elm_*.csv
    python analyze.py ../logs/elm_X.csv    # a given log
Output: analysis/<log name>/report.md + PNG charts + CSV tables.
"""
from __future__ import annotations

import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm
import numpy as np
import pandas as pd

import os
# project root with logs/, stag/osc/, analysis/ (override with env LPG_ROOT)
ROOT = Path(os.environ.get("LPG_ROOT") or Path(__file__).resolve().parent.parent)

# palette (dataviz reference instance, light mode)
C_PETROL = "#2a78d6"
C_GAS = "#eb6834"
C_AC = "#1baf7a"
INK = "#0b0b0b"
INK2 = "#52514e"
GRID = "#e4e3df"
SURFACE = "#fcfcfb"
DIVERGING = LinearSegmentedColormap.from_list(
    "trimdiff", ["#b8322f", "#e34948", "#f0a09c", "#f0efec", "#9ec5f4", "#3987e5", "#1c5cab"])

WARM_ECT = 75           # degC: engine considered warm
SETTLE_S = 20           # s to ignore after a fuel switch (Stag switches cylinder by cylinder)
MIN_N = 15              # samples per cell to trust a mean
SLOW_MIN_SECS = 20      # seconds per cell and fuel for slow PIDs (timing, catalyst temperature)
PAIR_S = 300            # s: compare a fuel only with the other fuel recorded within this time
LEARN_S = 300           # s: after an ECU reset, ignore this long past the first LTFT step (still learning)
ECT_MIN_WARMUP_MIN = 10  # min: shorter cold logs say nothing about the thermostat
RPM_BINS = [500, 1000, 1500, 2000, 2500, 3000, 3500, 4500, 7000]
LOAD_BINS = [0, 20, 30, 40, 50, 60, 80, 101]

# stoichiometric AFR and liquid density for the consumption estimate
FUELS = {"gas": (15.5, 0.54), "petrol": (14.7, 0.745)}   # (AFR, kg/L)

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "axes.edgecolor": GRID,
    "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2, "text.color": INK,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6, "axes.spines.top": False,
    "axes.spines.right": False, "font.size": 9, "lines.linewidth": 1.2, "legend.frameon": False,
})


def load(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    for c in df.columns:
        if c not in ("time_iso", "marker", "fuel", "ac", "road", "idle", "fuel_status"):   # idle: Android tag
            df[c] = pd.to_numeric(df[c], errors="coerce")
    df["marker"] = df["marker"].fillna("")
    if {"stft", "ltft"} <= set(df.columns):
        df["trim"] = df["stft"] + df["ltft"]
    df["dt"] = df["t_s"].diff().clip(lower=0, upper=2).fillna(0)
    # seconds since the last fuel switch
    switch_t = df["t_s"].where(df["fuel"].ne(df["fuel"].shift()))
    df["since_switch"] = df["t_s"] - switch_t.ffill()
    # driving mode from speed / throttle dynamics
    sp = df.get("speed", pd.Series(np.nan, index=df.index))
    acc = sp.diff() / df["t_s"].diff().replace(0, np.nan)
    acc = acc.rolling(5, center=True, min_periods=1).mean()
    tps = df.get("tps", pd.Series(np.nan, index=df.index))
    tps_min = tps.min() if tps.notna().any() else 0
    mode = np.select(
        [(sp < 3) & (df.get("rpm", 0) < 1100),
         (tps - tps_min < 1.5) & (sp > 10),
         acc > 1.0,
         (sp >= 15) & (acc.abs() <= 1.0)],
        ["idle", "coast", "accel", "cruise"], default="other")
    df["mode"] = mode
    if "ltft" in df:
        df["ltft_learning"] = ltft_learning(df["t_s"], df["ltft"])
    return df


def ltft_reset(ltft: pd.Series) -> bool:
    """LTFT reads exactly 0.0 at the start of the log: the ECU was reset (battery off / codes cleared)."""
    head = ltft.dropna().head(50)
    return len(head) == 50 and bool((head == 0).all())


def ltft_learning(t_s: pd.Series, ltft: pd.Series) -> pd.Series:
    """True while the ECU is still learning after a reset: up to LEARN_S past the first LTFT step.

    Gas and petrol share LTFT, so rows from that stretch are not comparable with later ones. LTFT's own
    drift is no indicator on this ECU: the 1ZR reports LTFT per operating range, and the PID jumps
    between ranges (e.g. 7 <-> 15 %) with rpm and load within a second."""
    if not ltft_reset(ltft):
        return pd.Series(False, index=ltft.index)
    stepped = t_s[ltft.fillna(0) != 0]
    if stepped.empty:
        return pd.Series(True, index=ltft.index)
    return t_s < stepped.iloc[0] + LEARN_S


def paired(t_s: pd.Series, is_gas: pd.Series, window: float = PAIR_S) -> pd.Series:
    """True where a sample of the other fuel exists within `window` seconds.

    Comparing whole-log averages mixes warm-up, LTFT learning and weather into the gas-vs-petrol
    difference (e.g. all gas during warm-up, petrol only once warm). Keeping only time-adjacent
    stretches of both fuels removes that drift."""
    t = t_s.to_numpy(dtype=float)
    g = is_gas.to_numpy(dtype=bool)
    out = np.zeros(len(t), dtype=bool)
    for mine, other in ((g, ~g), (~g, g)):
        to = np.sort(t[other])
        if not len(to) or not mine.any():
            continue
        tm = t[mine]
        i = np.searchsorted(to, tm)
        lo = to[np.clip(i - 1, 0, len(to) - 1)]
        hi = to[np.clip(i, 0, len(to) - 1)]
        out[mine] = np.minimum(np.abs(tm - lo), np.abs(hi - tm)) <= window
    return pd.Series(out, index=t_s.index)


def comparable_rows(df: pd.DataFrame, trims: bool = True, pair: bool = True) -> pd.DataFrame:
    """Rows fit for a gas-vs-petrol comparison: warm, settled after a switch, no overrun, LTFT not
    learning, and (with `pair`) with the other fuel recorded nearby in time."""
    m = df["fuel"].isin(["gas", "petrol"])
    if trims:
        m &= df["trim"].notna()
        if "fuel_status" in df and df["fuel_status"].notna().any():
            m &= df["fuel_status"] == "CL"
    if "ect" in df:
        m &= df["ect"] >= WARM_ECT
    m &= df["since_switch"] >= SETTLE_S
    m &= df["mode"] != "coast"
    if "ltft_learning" in df:
        m &= ~df["ltft_learning"]
    v = df[m]
    return v[paired(v["t_s"], v["fuel"] == "gas")] if pair else v


def valid_trim_rows(df: pd.DataFrame) -> pd.DataFrame:
    return comparable_rows(df, trims=True)


def filter_note(df: pd.DataFrame) -> str:
    """How many warm rows were dropped as LTFT-learning or unpaired, and a warning after an ECU reset."""
    base = df["fuel"].isin(["gas", "petrol"]) & df["trim"].notna() & (df["since_switch"] >= SETTLE_S)
    if "ect" in df:
        base &= df["ect"] >= WARM_ECT
    learning = df["ltft_learning"] if "ltft_learning" in df else pd.Series(False, index=df.index)
    learn = int((base & learning).sum())
    rest = int((base & ~learning).sum()) - len(valid_trim_rows(df))
    out = []
    if "ltft" in df and ltft_reset(df["ltft"]):
        out.append(f"**LTFT на старті = 0.0** — ECU скинуто (відкидали акумулятор / стирали помилки). "
                   f"Відкинуто {learn} рядків: до першого кроку адаптації + {LEARN_S // 60} хв. "
                   "Повністю трими довчаються 15–30 хв — для калібровки краще їздити вже після цього.")
    if rest > 0:
        out.append(f"Ще {rest} рядків без іншого палива в межах ±{PAIR_S // 60} хв (або накат / open loop) — "
                   "у порівняння не йдуть. Чергуйте газ і бензин відрізками по 4–5 хв.")
    return " ".join(out)


def shade_fuel(ax, df):
    """Background bands: orange = gas, blue = petrol."""
    seg = df["fuel"].ne(df["fuel"].shift()).cumsum()
    for _, g in df.groupby(seg):
        f = g["fuel"].iloc[0]
        if f in ("gas", "petrol"):
            ax.axvspan(g["t_s"].iloc[0] / 60, g["t_s"].iloc[-1] / 60,
                       color=C_GAS if f == "gas" else C_PETROL, alpha=0.08, lw=0)


def plot_overview(df, out: Path):
    panels = [("speed", "Швидкість, км/год"), ("rpm", "Оберти"), ("trim_panel", "Корекції палива, %"),
              ("ect", "Охолоджуюча рідина, °C"), ("maf_panel", "MAF, г/с"), ("o2_panel", "Лямбда B1S1")]
    panels = [p for p in panels if p[0].endswith("_panel") or p[0] in df]
    fig, axes = plt.subplots(len(panels), 1, figsize=(13, 2.0 * len(panels)), sharex=True)
    t = df["t_s"] / 60
    for ax, (key, title) in zip(axes, panels):
        shade_fuel(ax, df)
        ax.set_title(title, loc="left", fontsize=9, color=INK2)
        if key == "trim_panel":
            for col, lbl, c, lw in (("stft", "STFT", "#9a9993", 0.8), ("ltft", "LTFT", INK2, 1.6),
                                    ("trim", "STFT+LTFT", INK, 1.2)):
                if col in df:
                    ax.plot(t, df[col].rolling(5, min_periods=1).mean() if col == "stft" else df[col],
                            color=c, lw=lw, label=lbl)
            ax.axhline(0, color=INK2, lw=0.6)
            ax.legend(loc="upper right", ncol=3)
        elif key == "maf_panel":
            if "maf" in df:
                ax.plot(t, df["maf"], color=INK, lw=1)
            elif "map" in df:
                ax.plot(t, df["map"], color=INK, lw=1)
                ax.set_title("MAP, кПа", loc="left", fontsize=9, color=INK2)
        elif key == "o2_panel":
            if "o2s1_v" in df:
                ax.plot(t, df["o2s1_v"], color=INK, lw=0.6)
                ax.axhline(0.45, color=INK2, lw=0.6, ls="--")
                ax.set_title("O2 B1S1, В", loc="left", fontsize=9, color=INK2)
            elif "wr1_lambda" in df:
                ax.plot(t, df["wr1_lambda"], color=INK, lw=0.8)
                ax.axhline(1.0, color=INK2, lw=0.6, ls="--")
                ax.set_title("λ B1S1 (широкосмуговий)", loc="left", fontsize=9, color=INK2)
        else:
            ax.plot(t, df[key], color=INK, lw=1)
            if key == "ect":
                ax.axhspan(85, 95, color=C_AC, alpha=0.10, lw=0)
    if "ac" in df and (df["ac"] == "on").any():
        # thin green strip along the top of the first panel while A/C is on
        axes[0].fill_between(t, 0.97, 1.0, where=df["ac"] == "on", transform=axes[0].get_xaxis_transform(),
                             color=C_AC, lw=0, step="mid")
    for m_t, m in zip(t[df["marker"] != ""], df.loc[df["marker"] != "", "marker"]):
        for ax in axes:
            ax.axvline(m_t, color=INK2, lw=0.5, alpha=0.5)
        axes[0].annotate(m, (m_t, 1.0), xycoords=("data", "axes fraction"), fontsize=7,
                         color=INK2, rotation=90, va="bottom", ha="center")
    axes[-1].set_xlabel("хвилини від старту  (фон: помаранчевий = газ, синій = бензин; зелена смуга вгорі = A/C)")
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


def trim_tables(v: pd.DataFrame):
    v = v.assign(rpm_bin=pd.cut(v["rpm"], RPM_BINS, right=False),
                 load_bin=pd.cut(v["load"], LOAD_BINS, right=False))
    g = v.groupby(["fuel", "rpm_bin", "load_bin"], observed=True)["trim"].agg(["mean", "count"])
    mean = g["mean"].where(g["count"] >= MIN_N)
    tab = {f: mean.xs(f, level="fuel").unstack("load_bin") if f in mean.index.get_level_values(0) else None
           for f in ("gas", "petrol")}
    by_mode = v.groupby(["mode", "fuel"], observed=True)["trim"].agg(["mean", "median", "std", "count"]).round(2)
    full = dict(index=pd.IntervalIndex.from_breaks(RPM_BINS, closed="left"),
                columns=pd.IntervalIndex.from_breaks(LOAD_BINS, closed="left"))
    tab = {f: (t.reindex(**full) if t is not None else None) for f, t in tab.items()}   # full grid: position = bin
    return tab, by_mode


def plot_trim_maps(tab, out: Path):
    gas, pet = tab.get("gas"), tab.get("petrol")
    frames = [("Газ: STFT+LTFT, %", gas), ("Бензин: STFT+LTFT, %", pet)]
    diff = None
    if gas is not None and pet is not None:
        diff = gas.sub(pet)
        frames.append(("Газ − бензин, п.п.  (червоне = на газу багатіше)", diff))
    fig, axes = plt.subplots(1, len(frames), figsize=(5.2 * len(frames), 4.4))
    axes = np.atleast_1d(axes)
    for ax, (title, t) in zip(axes, frames):
        ax.set_title(title, loc="left", fontsize=9)
        ax.grid(False)
        if t is None or t.empty:
            ax.text(0.5, 0.5, "немає даних", ha="center", va="center", transform=ax.transAxes, color=INK2)
            ax.set_xticks([]); ax.set_yticks([])
            continue
        t = t.reindex(index=pd.IntervalIndex.from_breaks(RPM_BINS, closed="left"),
                      columns=pd.IntervalIndex.from_breaks(LOAD_BINS, closed="left"))
        vals = t.values.astype(float)
        lim = max(5.0, np.nanmax(np.abs(vals)) if np.isfinite(vals).any() else 5.0)
        ax.imshow(vals, origin="lower", aspect="auto", cmap=DIVERGING,
                  norm=TwoSlopeNorm(0, -lim, lim))
        for (i, j), x in np.ndenumerate(vals):
            if np.isfinite(x):
                ax.text(j, i, f"{x:+.1f}", ha="center", va="center", fontsize=7,
                        color="white" if abs(x) > lim * 0.6 else INK)
        ax.set_xticks(range(len(LOAD_BINS) - 1), [f"{LOAD_BINS[k]}–{LOAD_BINS[k+1]}" for k in range(len(LOAD_BINS) - 1)],
                      rotation=45, fontsize=7)
        ax.set_yticks(range(len(RPM_BINS) - 1), [f"{RPM_BINS[k]}–{RPM_BINS[k+1]}" for k in range(len(RPM_BINS) - 1)],
                      fontsize=7)
        ax.set_xlabel("навантаження (ENGINE_LOAD), %")
        ax.set_ylabel("оберти")
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)
    return diff


def plot_by_mode(by_mode: pd.DataFrame, out: Path):
    modes = [m for m in ("idle", "cruise", "accel", "other") if m in by_mode.index.get_level_values(0)]
    if not modes:
        return
    fig, ax = plt.subplots(figsize=(7, 3.4))
    w = 0.36
    for k, (fuel, c) in enumerate((("petrol", C_PETROL), ("gas", C_GAS))):
        ys = [by_mode.loc[(m, fuel), "mean"] if (m, fuel) in by_mode.index else np.nan for m in modes]
        xs = np.arange(len(modes)) + (k - 0.5) * w
        ax.bar(xs, ys, w - 0.04, color=c, label="бензин" if fuel == "petrol" else "газ")
        for x, y in zip(xs, ys):
            if np.isfinite(y):
                ax.text(x, y + (0.3 if y >= 0 else -0.3), f"{y:+.1f}", ha="center",
                        va="bottom" if y >= 0 else "top", fontsize=8, color=INK)
    names = {"idle": "холості", "cruise": "рівна їзда", "accel": "розгін", "other": "інше"}
    ax.set_xticks(range(len(modes)), [names[m] for m in modes])
    ax.axhline(0, color=INK2, lw=0.8)
    ax.set_title("Середня сумарна корекція STFT+LTFT, % (прогрітий, closed loop)", loc="left", fontsize=9)
    ax.legend()
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)


def o2_stats(df: pd.DataFrame) -> str:
    if "o2s1_v" not in df:
        if "wr1_lambda" in df:
            w = df[(df["mode"] == "cruise") & df["wr1_lambda"].notna()]
            if len(w):
                return (f"Широкосмуговий датчик: λ на рівній їзді середнє {w['wr1_lambda'].mean():.3f}, "
                        f"розкид (std) {w['wr1_lambda'].std():.3f}.")
        return "Дані O2 B1S1 відсутні."
    lines = []
    for fuel in ("petrol", "gas"):
        w = df[(df["fuel"] == fuel) & (df["mode"].isin(["cruise", "idle"])) & (df.get("ect", 90) >= WARM_ECT)]
        w = w[w["o2s1_v"].notna()]
        if len(w) < 50:
            continue
        above = w["o2s1_v"] > 0.45
        crossings = (above != above.shift()).sum() - 1
        dur = w["dt"].sum()
        hz = crossings / 2 / dur if dur else float("nan")
        rate = len(w) / dur if dur else float("nan")
        lines.append(f"- {fuel}: {crossings} перетинів 0.45 В за {dur:.0f} с -> ≈{hz:.2f} Гц "
                     f"(min {w['o2s1_v'].min():.2f} В, max {w['o2s1_v'].max():.2f} В, частота запису {rate:.1f} Гц)")
    if not lines:
        return "Замало даних O2 на прогрітому двигуні."
    return ("\n".join(lines) + "\n\n_Це лише грубий індикатор «живості» лямбди: частота запису ELM (~3–5 Гц) і "
            "осцилографа Stag (~9 Гц) замала, а здорова вузькосмугова лямбда на ~2500 об/хв перемикається "
            "5–7 разів на секунду і стрибає 0,2→0,8 В швидше за 100 мс. Дивись розмах (≈0,1–0,9 В) і чи немає "
            "«залипання» на одному рівні; швидкість лямбди міряють осцилографом або Toyota Techstream._")


def o2s2_is_fast(csv_path: Path) -> bool:
    """B1S2 was polled every cycle (logger >= 2026-09-27) rather than every ~4 s."""
    meta = csv_path.with_suffix(".meta.json")
    try:
        import json
        groups = json.loads(meta.read_text(encoding="utf-8")).get("fast_groups", [])
        return any("15" in g for g in groups)
    except (OSError, ValueError):
        return False


def catalyst_stats(df: pd.DataFrame, fast: bool) -> str:
    """Downstream O2 (B1S2) against upstream (B1S1).

    Behind a working catalyst the oxygen storage smooths the exhaust: B1S2 sits fairly steady (typically
    0.5-0.8 V) while B1S1 switches. A worn catalyst lets B1S2 follow B1S1's switching; the ratio of their
    0.45 V crossings is roughly what the ECU's own catalyst monitor looks at. The mean B1S2 voltage per
    fuel is also a slow, honest indicator of the average mixture after the catalyst.
    """
    if not {"o2s1_v", "o2s2_v"} <= set(df.columns):
        return "Немає даних O2 B1S2."
    # warm and settled; per-fuel means need no pairing, so a single-fuel trip still gets a verdict
    w = comparable_rows(df, trims=False, pair=False)
    w = w[w["o2s2_v"].notna() & w["o2s1_v"].notna()]
    if "fuel_status" in w and w["fuel_status"].notna().any():
        w = w[w["fuel_status"] == "CL"]
    if "cat_temp" in w and w["cat_temp"].notna().any():
        w = w[w["cat_temp"] >= 400]
    if len(w) < 100:
        return "Замало даних B1S2 на прогрітому каталізаторі (потрібен прогрітий двигун і кат ≥ 400 °C)."
    lines = []
    for fuel in ("petrol", "gas"):
        f = w[w["fuel"] == fuel]["o2s2_v"]
        if len(f) >= 50:
            lines.append(f"- {fuel}: B1S2 середнє {f.mean():.2f} В (10–90 %: {f.quantile(.1):.2f}–{f.quantile(.9):.2f} В), "
                         f"{len(f)} точок")
    if not fast:
        lines.append("")
        lines.append("_B1S2 у цьому лозі опитувався раз на ~4 с — стан каталізатора за ним не оцінити, "
                     "лише середнє. Новий логер опитує B1S2 щоциклу._")
        return "\n".join(lines)
    steady = w[w["rpm"].between(1200, 3000) & (w.get("speed", 0) > 20)]
    if len(steady) < 100:
        steady = w
    cross = lambda x: max(int(((x > 0.45) != (x > 0.45).shift()).sum()) - 1, 0)
    c1, c2 = cross(steady["o2s1_v"]), cross(steady["o2s2_v"])
    if c1 >= 30:
        r = c2 / c1
        verdict = ("каталізатор тримає кисень добре" if r < 0.2 else
                   "каталізатор старіє — стежити" if r < 0.5 else
                   "**B1S2 повторює B1S1 — каталізатор слабкий** (або витік у випуску перед B1S2)")
        lines.append(f"- перемикання 0.45 В на рівній їзді: B1S1 {c1}, B1S2 {c2} → відношення **{r:.2f}** — {verdict}")
    lines += ["", "_Норма: B1S2 тримається рівно, 0.5–0.8 В, а відношення перемикань B2/B1 < 0.2. Стабільно низьке "
              "B1S2 (< 0.35 В) — суміш у середньому бідна після каталізатора; помітна різниця середнього B1S2 між "
              "газом і бензином — ознака різної суміші на двох паливах, яку трими не показали._"]
    return "\n".join(lines)


def health_report(csv_path: Path) -> list[str]:
    """Engine health snapshots saved by the logger (<log>.health.json): codes, readiness, mode 06."""
    path = csv_path.with_suffix(".health.json")
    if not path.exists():
        return []
    import json
    from obd_health import format_health, misfires
    try:
        snaps = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return []
    out = ["## Стан ECU: коди помилок, монітори, пропуски запалювання", ""]
    for when, title in (("start", "на початку запису"), ("end", "в кінці запису")):
        h = snaps.get(when)
        if not h:
            continue
        out += [f"**{title}** ({h.get('time', '')}):", "", "```", *format_health(h), "```", ""]
    h = snaps.get("end") or snaps.get("start") or {}
    notes = []
    codes = (h.get("dtc_stored") or []) + (h.get("dtc_pending") or []) + (h.get("dtc_permanent") or [])
    if codes:
        notes.append(f"- Коди: {', '.join(sorted(set(codes)))}. P0171 — бідно, P0172 — багато, P0300–P0304 — "
                     "пропуски запалювання (циліндр = остання цифра), P0420 — ефективність каталізатора.")
    mis = misfires(h)
    if mis:
        tot = {c: v.get("cycle", v.get("avg10", 0)) for c, v in mis.items()}
        worst = max(tot, key=tot.get)
        others = [v for c, v in tot.items() if c != worst]
        if tot[worst] >= 10 and tot[worst] > 3 * (max(others) if others else 0):
            notes.append(f"- Пропуски зосереджені в **{worst}** ({tot[worst]:g} за цикл). На газу це найчастіше "
                         "свічка / котушка / дріт цього циліндра (газу потрібна сильніша іскра) або газова форсунка "
                         "цього циліндра; поміняти котушки місцями й подивитися, чи пропуски перейдуть.")
        elif sum(tot.values()) >= 20:
            notes.append("- Пропуски розкидані по всіх циліндрах — спільна причина: бідна суміш, свічки (зазор), "
                         "тиск газу.")
    rd = h.get("readiness") or {}
    inc = [k for k, v in (rd.get("monitors") or {}).items() if v == "incomplete"]
    if inc:
        notes.append(f"- Монітори ще не готові: {', '.join(inc)} — після скидання ECU це нормально перші 1–3 поїздки; "
                     "результати режиму 06 для них з'являться, коли ECU проведе тест.")
    c = h.get("counters") or {}
    if "km_since_clear" in c:
        notes.append(f"- З моменту скидання ECU / стирання кодів: {c['km_since_clear']} км, "
                     f"{c.get('min_since_clear', '?')} хв роботи, {c.get('warmups_since_clear', '?')} прогрівів.")
    return out + notes + [""]


def ect_stats(df: pd.DataFrame) -> str:
    if "ect" not in df or df["ect"].isna().all():
        return "ECT відсутня."
    e = df[df["ect"].notna()]
    out = [f"- старт: {e['ect'].iloc[0]:.0f} °C, максимум: {e['ect'].max():.0f} °C"]
    reached = e[e["ect"] >= 80]
    span_min = (e["t_s"].iloc[-1] - e["t_s"].iloc[0]) / 60
    if e["ect"].iloc[0] >= 80:
        out.append("- двигун уже прогрітий на старті логу")
    elif len(reached):
        out.append(f"- до 80 °C за {reached['t_s'].iloc[0] / 60:.1f} хв від старту логу")
    elif span_min < ECT_MIN_WARMUP_MIN:
        rise = e["ect"].iloc[-1] - e["ect"].iloc[0]
        out.append(f"- 80 °C не досягнуто, але запис лише {span_min:.1f} хв (+{rise:.0f} °C, "
                   f"≈{rise / span_min:.0f} °C/хв) — закоротко для висновку про термостат")
    else:
        out.append(f"- **80 °C так і не досягнуто за {span_min:.0f} хв** — підозра на термостат")
    hw = e[(e["road"] == "highway") & (e["t_s"] > (reached["t_s"].iloc[0] if len(reached) else 0))]
    if len(hw):
        out.append(f"- на трасі (після прогріву): min {hw['ect'].min():.0f}, середнє {hw['ect'].mean():.1f}, "
                   f"max {hw['ect'].max():.0f} °C")
        if hw["ect"].min() < 80:
            out.append("  - **на трасі падала нижче 80 °C** — типова ознака термостата, що не закривається")
        elif hw["ect"].mean() < 85:
            out.append("  - на трасі тримається нижче норми (для 1ZR-FE з термостатом 82 °C норма 86–94 °C) — "
                       "термостат під підозрою; стрілка приладки це не покаже")
    return "\n".join(out)


def ac_effect(df: pd.DataFrame, out: Path) -> str:
    if "ac" not in df or not (df["ac"] == "on").any() or not (df["ac"] == "off").any():
        return "Немає ділянок з позначками A/C увімк./вимк."
    air = "maf" if "maf" in df else ("load" if "load" in df else None)
    if air is None:
        return "Немає MAF/LOAD."
    c = df[(df["mode"].isin(["cruise", "idle"])) & df["ac"].isin(["on", "off"]) & df[air].notna()]
    c = c.assign(sbin=(c["speed"] // 10 * 10).astype(int))
    g = c.groupby(["sbin", "ac"])[air].agg(["mean", "count"]).unstack("ac")
    g = g[(g[("count", "on")] >= MIN_N) & (g[("count", "off")] >= MIN_N)] if ("count", "on") in g else g.iloc[0:0]
    fig, ax = plt.subplots(figsize=(7, 3.4))
    for acv, col, lbl in (("off", INK2, "A/C вимк."), ("on", C_AC, "A/C увімк.")):
        s = c[c["ac"] == acv]
        ax.scatter(s["speed"], s[air], s=8, color=col, alpha=0.35, lw=0, label=lbl)
    ax.set_xlabel("швидкість, км/год")
    ax.set_title(f"{air.upper()} на холостих і рівній їзді: з кондиціонером і без", loc="left", fontsize=9)
    ax.legend(markerscale=2)
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    plt.close(fig)
    if g.empty:
        return "Замало порівнянних точок (однакова швидкість з A/C і без)."
    rows = ["| швидкість | " + air + " без A/C | з A/C | різниця |", "|---|---|---|---|"]
    for sb, r in g.iterrows():
        off, on = r[("mean", "off")], r[("mean", "on")]
        rows.append(f"| {sb}–{sb + 10} | {off:.2f} | {on:.2f} | {100 * (on - off) / off:+.0f} % |")
    return "\n".join(rows)


def consumption(df: pd.DataFrame) -> str:
    """Fuel estimate from MAF. In closed loop lambda = 1, so fuel mass = air / AFR regardless
    of how well the Stag is calibrated; open-loop enrichment is taken from commanded lambda (PID 44)."""
    if "maf" not in df or "speed" not in df:
        return "Потрібні MAF і швидкість."
    rows = ["| паливо | ділянка | км | хв | оцінка л/100 км | л/год |", "|---|---|---|---|---|---|"]
    for fuel, (afr, rho) in FUELS.items():
        d = df[(df["fuel"] == fuel) & df["maf"].notna() & df["speed"].notna()]
        if d.empty:
            continue
        lam = d["cmd_lambda"].fillna(1.0).clip(0.7, 1.3) if "cmd_lambda" in d else 1.0
        fuel_cut = (d["mode"] == "coast") & (d.get("fuel_status", "") == "OL_load")
        kg_s = (d["maf"] / 1000) / (afr * lam) * (~fuel_cut)
        litres = (kg_s * d["dt"]).sum() / rho
        for road in ["all"] + sorted(d["road"].dropna().unique()):
            sub = d if road == "all" else d[d["road"] == road]
            if sub.empty:
                continue
            ks = kg_s.loc[sub.index]
            L = (ks * sub["dt"]).sum() / rho
            km = (sub["speed"] * sub["dt"]).sum() / 3600
            hrs = sub["dt"].sum() / 3600
            per100 = f"{100 * L / km:.1f}" if km > 0.5 else "—"
            rows.append(f"| {fuel} | {road} | {km:.1f} | {60 * hrs:.0f} | {per100} | {L / hrs if hrs else 0:.2f} |")
    return "\n".join(rows)


def timing_cat_compare(df: pd.DataFrame) -> str:
    """Ignition advance (PID 0E) and catalyst temperature (PID 3C) on gas vs petrol in the same
    rpm x load cells. LPG does not cool the charge and a lean or slow burn finishes late, so a
    hotter exhaust on gas under load is the thing to watch for valve seats."""
    cols = [c for c in ("timing", "cat_temp") if c in df and df[c].notna().any()]
    if not cols or "load" not in df:
        return "Немає даних кута випередження / температури каталізатора."
    v = comparable_rows(df, trims=False)
    # Slow PIDs are carried forward between refreshes (~4 s), so sample counts overstate the data;
    # require enough *time* in a cell on each fuel instead.
    out = []
    names = {"timing": ("Кут випередження, °", "{:+.1f}"), "cat_temp": ("Темп. каталізатора, °C", "{:+.0f}")}
    for c in cols:
        s = v[v[c].notna()]
        s = s.assign(rpm_bin=pd.cut(s["rpm"], RPM_BINS, right=False), load_bin=pd.cut(s["load"], LOAD_BINS, right=False))
        g = s.groupby(["rpm_bin", "load_bin", "fuel"], observed=True).agg(
            mean=(c, "mean"), count=(c, "size"), secs=("dt", "sum")).unstack("fuel")
        if ("mean", "gas") not in g or ("mean", "petrol") not in g:
            continue
        g = g[(g[("secs", "gas")] >= SLOW_MIN_SECS) & (g[("secs", "petrol")] >= SLOW_MIN_SECS)]
        if g.empty:
            continue
        title, fmt = names[c]
        rows = [f"**{title}** (газ − бензин у тих самих клітинках):", "",
                "| оберти | навантаження, % | бензин | газ | різниця | точок б/г |", "|---|---|---|---|---|---|"]
        for (rb, lb), r in g.iterrows():
            d = r[("mean", "gas")] - r[("mean", "petrol")]
            rows.append(f"| {rb.left:g}–{rb.right:g} | {lb.left:g}–{lb.right:g} | {r[('mean', 'petrol')]:.1f} | "
                        f"{r[('mean', 'gas')]:.1f} | {fmt.format(d)} | {int(r[('count', 'petrol')])}/{int(r[('count', 'gas')])} |")
        out += rows + [""]
    if not out:
        return "Замало порівнянних точок (повільні PID оновлюються раз на ~4 с — потрібні довші відрізки на обох паливах)."
    out.append("_Газ не охолоджує заряд, а бідна/повільна суміш догоряє на випуску → гарячіший вихлоп і випускні "
               "клапани. Помітно вища температура каталізатора на газу під навантаженням разом із плюсовими "
               "тримами = бідна зона, яку треба збагатити (множник / Кор. оборотов / Доля бензина). Кут ECU Toyota "
               "виставляє сам за датчиком детонації; інший кут на газу — очікуваний, не несправність._")
    return "\n".join(out)


def verdict(by_mode: pd.DataFrame, diff) -> list[str]:
    out = []
    def mean(mode, fuel):
        return by_mode.loc[(mode, fuel), "mean"] if (mode, fuel) in by_mode.index else None
    pet = [mean(m, "petrol") for m in ("idle", "cruise", "accel")]
    gas = [mean(m, "gas") for m in ("idle", "cruise", "accel")]
    for name, p, g in zip(("холості", "рівна їзда", "розгін"), pet, gas):
        if p is None or g is None:
            continue
        d = g - p
        tag = ("OK" if abs(d) < 3 else ("газ БАГАТШИЙ за бензин" if d < 0 else "газ БІДНІШИЙ за бензин"))
        out.append(f"- {name}: бензин {p:+.1f} %, газ {g:+.1f} %, різниця {d:+.1f} п.п. → {tag}")
    pv = [x for x in pet if x is not None]
    if pv and max(abs(x) for x in pv) > 8:
        out.append("- **На бензині трими теж великі (>8 %)** — проблема не лише в Stag: "
                   "підсмоктування повітря, забруднений MAF, лямбда, паливний тиск.")
    if diff is not None:
        dv = diff.values.astype(float)
        if np.isfinite(dv).any():
            i, j = np.unravel_index(np.nanargmax(np.abs(dv)), dv.shape)
            out.append(f"- Найбільша розбіжність газ−бензин: {dv[i, j]:+.1f} п.п. при "
                       f"{RPM_BINS[i]}–{RPM_BINS[i+1]} об/хв і навантаженні {LOAD_BINS[j]}–{LOAD_BINS[j+1]} %.")
    return out or ["- Замало порівнянних даних газ/бензин (перевірте позначки g/b і прогрів)."]


# ---------------------------------------------------------------------------
# AC STAG oscilloscope recordings (*.osc) - see stag_osc.py
# Both AC STAG and the ELM logger stamp time with the same laptop clock, so the
# two recordings are aligned by wall-clock time directly.
# ---------------------------------------------------------------------------
MAP_BINS = [0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.80, 1.05]
STAG_COLS = ["map_bar", "gas_p_bar", "gas_temp_c", "reducer_temp_c", "rpm", "status", "on_gas",
             "petrol_ms", "gas_ms", "lambda1_v"]


def load_stag(osc_paths: list[Path], t_from=None, t_to=None) -> pd.DataFrame | None:
    import stag_osc
    frames = []
    for p in osc_paths:
        try:
            d = stag_osc.to_dataframe(stag_osc.read_osc(p))
        except Exception as e:
            print(f"  skip {p.name}: {e}")
            continue
        if "time" not in d:
            continue
        d["time"] = d["time"].dt.tz_localize(None).astype("datetime64[ns]")   # naive local, like the ELM log
        if t_from is not None and (d["time"].iloc[-1] < t_from or d["time"].iloc[0] > t_to):
            continue
        pet = [c for c in d if c.startswith("petrol_ms_")]
        gas = [c for c in d if c.startswith("gas_ms_")]
        d["petrol_ms"] = d[pet].mean(axis=1) if pet else np.nan
        d["gas_ms"] = d[gas].mean(axis=1) if gas else np.nan
        d["osc_file"] = p.name
        frames.append(d)
        print(f"  stag: {p.name} {d['time'].iloc[0]} .. {d['time'].iloc[-1]} ({len(d)} samples)")
    if not frames:
        return None
    s = pd.concat(frames).sort_values("time").reset_index(drop=True)
    s["since_switch"] = s["time"].sub(s["time"].where(s["on_gas"].ne(s["on_gas"].shift())).ffill()).dt.total_seconds()
    return s


def load_stag_csv(path: Path) -> pd.DataFrame | None:
    """`<log>.stag.csv` from the Android logger: the Stag monitor at ~10 Hz, same clock as the ELM log."""
    d = pd.read_csv(path)
    if d.empty or "time_iso" not in d:
        return None
    d["time"] = pd.to_datetime(d["time_iso"]).astype("datetime64[ns]")
    d["osc_file"] = path.name
    d = d.sort_values("time").reset_index(drop=True)
    d["since_switch"] = d["time"].sub(d["time"].where(d["on_gas"].ne(d["on_gas"].shift())).ffill()).dt.total_seconds()
    print(f"  stag: {path.name} {d['time'].iloc[0]} .. {d['time'].iloc[-1]} ({len(d)} samples)")
    return d


def merge_stag(df: pd.DataFrame, s: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["time"] = pd.to_datetime(df["time_iso"]).astype("datetime64[ns]")
    cols = [c for c in STAG_COLS if c in s]
    m = pd.merge_asof(df.sort_values("time"), s[["time"] + cols].add_prefix("stag_").rename(
        columns={"stag_time": "time"}), on="time", direction="nearest", tolerance=pd.Timedelta("0.5s"))
    return m


def stag_deviation(s: pd.DataFrame, elm: pd.DataFrame | None):
    """Stag-style deviation D = (petrol time on petrol - petrol time on gas) / petrol time on petrol.
    Positive = mixture rich on gas (ECU shortens injection on gas)."""
    v = s[(s["rpm"] > 600) & (s["since_switch"] > SETTLE_S) & (s["petrol_ms"] > 0.8)].copy()
    if elm is not None and "ect" in elm:
        cols = ["ect"] + (["ltft_learning"] if "ltft_learning" in elm else [])
        e = elm[["time_iso"] + cols].dropna().assign(time=lambda x: pd.to_datetime(x["time_iso"]).astype("datetime64[ns]"))
        v = pd.merge_asof(v.sort_values("time"), e[["time"] + cols].sort_values("time"), on="time",
                          direction="nearest", tolerance=pd.Timedelta("5s"))
        v = v[v["ect"] >= WARM_ECT]
        if "ltft_learning" in v:
            v = v[v["ltft_learning"].eq(False)]   # NaN after the merge = no ELM data nearby -> drop
    else:
        v = v[v["reducer_temp_c"] >= 45]
    if v.empty:
        return None, None
    # petrol injection time includes the ECU's LTFT, so compare only time-adjacent gas/petrol stretches
    ts = (v["time"] - v["time"].iloc[0]).dt.total_seconds()
    v = v[paired(ts, v["on_gas"] == 1)]
    if v.empty:
        return None, None
    v["rpm_bin"] = pd.cut(v["rpm"], RPM_BINS, right=False)
    v["map_bin"] = pd.cut(v["map_bar"], MAP_BINS, right=False)
    g = v.groupby(["on_gas", "rpm_bin", "map_bin"], observed=True)["petrol_ms"].agg(["mean", "count"])
    g = g["mean"].where(g["count"] >= MIN_N)
    if 0 not in g.index.get_level_values(0) or 1 not in g.index.get_level_values(0):
        return None, v
    pet = g.xs(0, level="on_gas").unstack("map_bin")
    gas = g.xs(1, level="on_gas").unstack("map_bin")
    dev = ((pet - gas) / pet * 100).reindex(index=pd.IntervalIndex.from_breaks(RPM_BINS, closed="left"),
                                            columns=pd.IntervalIndex.from_breaks(MAP_BINS, closed="left"))
    return dev, v


def plot_stag(s: pd.DataFrame, dev, out_dir: Path):
    t = (s["time"] - s["time"].iloc[0]).dt.total_seconds() / 60
    panels = [("petrol_ms", "Час впорску бензину Б (середнє), мс"), ("gas_ms", "Доза газу Г (середнє), мс"),
              ("gas_p_bar", "Тиск газу, бар"), ("map_bar", "MAP, бар"), ("reducer_temp_c", "Редуктор, °C")]
    panels = [p for p in panels if p[0] in s]
    fig, axes = plt.subplots(len(panels), 1, figsize=(13, 1.8 * len(panels)), sharex=True)
    for ax, (k, title) in zip(np.atleast_1d(axes), panels):
        on = s["on_gas"] == 1
        ax.fill_between(t, 0, 1, where=on, transform=ax.get_xaxis_transform(), color=C_GAS, alpha=0.08, lw=0)
        ax.fill_between(t, 0, 1, where=~on, transform=ax.get_xaxis_transform(), color=C_PETROL, alpha=0.08, lw=0)
        ax.plot(t, s[k], color=INK, lw=0.8)
        ax.set_title(title, loc="left", fontsize=9, color=INK2)
    np.atleast_1d(axes)[-1].set_xlabel("хвилини від початку запису Stag (фон: помаранчевий = газ за статусом Stag)")
    fig.tight_layout(); fig.savefig(out_dir / "stag_overview.png", dpi=130); plt.close(fig)
    if dev is None:
        return
    dev = dev.reindex(index=pd.IntervalIndex.from_breaks(RPM_BINS, closed="left"),
                      columns=pd.IntervalIndex.from_breaks(MAP_BINS, closed="left"))
    vals = dev.values.astype(float)
    lim = max(5.0, np.nanmax(np.abs(vals)) if np.isfinite(vals).any() else 5.0)
    fig, ax = plt.subplots(figsize=(6.5, 4.4)); ax.grid(False)
    # positive = rich on gas -> red side of the diverging map (hence the minus sign)
    ax.imshow(-vals, origin="lower", aspect="auto", cmap=DIVERGING, norm=TwoSlopeNorm(0, -lim, lim))
    for (i, j), x in np.ndenumerate(vals):
        if np.isfinite(x):
            ax.text(j, i, f"{x:+.1f}", ha="center", va="center", fontsize=7,
                    color="white" if abs(x) > lim * 0.6 else INK)
    ax.set_xticks(range(len(MAP_BINS) - 1), [f"{MAP_BINS[k]:.2f}–{MAP_BINS[k+1]:.2f}" for k in range(len(MAP_BINS) - 1)],
                  rotation=45, fontsize=7)
    ax.set_yticks(range(len(RPM_BINS) - 1), [f"{RPM_BINS[k]}–{RPM_BINS[k+1]}" for k in range(len(RPM_BINS) - 1)], fontsize=7)
    ax.set_xlabel("MAP, бар"); ax.set_ylabel("оберти")
    ax.set_title("Відхилення D за даними Stag, %  (+ / червоне = на газу багато)", loc="left", fontsize=9)
    fig.tight_layout(); fig.savefig(out_dir / "stag_deviation.png", dpi=130); plt.close(fig)


def pressure_report(s: pd.DataFrame, work_p: float | None = None) -> list[str]:
    """Gas (differential) pressure on gas: level vs configured working pressure, sag under load."""
    g = s[(s["on_gas"] == 1) & (s["since_switch"] > 5) & s["gas_p_bar"].notna()]
    if len(g) < 50:
        return []
    g = g.assign(map_bin=pd.cut(g["map_bar"], MAP_BINS, right=False))
    t = g.groupby("map_bin", observed=True)["gas_p_bar"].agg(["mean", "min", "count"])
    t = t[t["count"] >= MIN_N]
    if t.empty:
        return []
    base = t["mean"].iloc[0]
    rows = ["| MAP, бар | тиск газу сер., бар | мін., бар | точок |", "|---|---|---|---|"]
    rows += [f"| {b.left:.2f}–{b.right:.2f} | {r['mean']:.2f} | {r['min']:.2f} | {int(r['count'])} |" for b, r in t.iterrows()]
    out = ["### Тиск газу (різниця редуктор − колектор) на газу", "", *rows, ""]
    sag = base - t["mean"].min()
    if sag > 0.15:
        out.append(f"- **Тиск просідає під навантаженням на {sag:.2f} бар** відносно малих навантажень — "
                   "редуктор/фільтр летючої фази/недогрів не встигають. На великому навантаженні це бідна суміш "
                   "у зоні, де ECU вже не коригує (open loop) → ризик для клапанів. Не лікувати множником.")
    elif len(t) > 2:
        out.append(f"- Тиск стабільний по навантаженню (перепад {sag:.2f} бар).")
    if work_p:
        diff = base - work_p
        out.append(f"- Фактичний тиск ≈ {base:.2f} бар проти «Рабочее давление» {work_p:.2f} бар "
                   f"(різниця {diff:+.2f}). " + ("Схоже, тиск на редукторі змінювали без оновлення налаштування — "
                   "контролер частково компенсує, але множник калібрувався під інший тиск."
                   if abs(diff) > 0.1 else "Відповідає налаштуванню."))
    else:
        out.append(f"- Фактичний тиск ≈ {base:.2f} бар. Порівняй з «Рабочее давление» у «Настройки блока "
                   "управления газа» (передай `--work-pressure`): різниця > 0.1 бар = редуктор крутили без "
                   "оновлення налаштування.")
    return out + [""]


def trim_cells_by_ms(df: pd.DataFrame, out_dir: Path) -> list[str]:
    """Gas-vs-petrol total trim per rpm x Stag-MAP cell, listed by the petrol-mode injection time.

    Cells are binned by rpm and MAP (independent of the fuel); the petrol-mode injection time of the
    cell is its address on the multiplier axis. Binning by injection time itself would be biased,
    because on a rich gas map the petrol time on gas is shorter and samples slide to lower bins.
    """
    need = {"trim", "stag_map_bar", "stag_petrol_ms", "rpm"}
    if not need <= set(df.columns):
        return []
    v = valid_trim_rows(df)
    v = v[v["stag_map_bar"].notna() & v["stag_petrol_ms"].notna()]
    if v.empty:
        return []
    v = v.assign(rpm_bin=pd.cut(v["rpm"], RPM_BINS, right=False),
                 map_bin=pd.cut(v["stag_map_bar"], MAP_BINS, right=False))
    g = v.groupby(["rpm_bin", "map_bin", "fuel"], observed=True).agg(
        trim=("trim", "mean"), n=("trim", "size"), ms=("stag_petrol_ms", "mean")).unstack("fuel")
    if ("trim", "gas") not in g or ("trim", "petrol") not in g:
        return []
    ok = (g[("n", "gas")] >= MIN_N) & (g[("n", "petrol")] >= MIN_N)
    g = g[ok]
    if g.empty:
        return []
    rows = []
    for (rb, mb), r in g.iterrows():
        tg, tp = r[("trim", "gas")], r[("trim", "petrol")]
        k = (1 + tg / 100) / (1 + tp / 100)
        rows.append((r[("ms", "petrol")], rb, mb, tp, tg, tg - tp, k, int(r[("n", "petrol")]), int(r[("n", "gas")])))
    rows.sort()
    pd.DataFrame(rows, columns=["petrol_ms", "rpm_bin", "map_bin", "trim_petrol", "trim_gas", "delta_pp", "k",
                                "n_petrol", "n_gas"]).to_csv(out_dir / "trim_cells_by_ms.csv", index=False)
    out = ["### Трими газ − бензин по клітинках, упорядковані за часом впорску Б (= адреса точки множника)", "",
           "| Б на бензині, мс | оберти | MAP, бар | трим бенз., % | трим газ, % | Δ, п.п. | k | зміна точки | точок б/г |",
           "|---|---|---|---|---|---|---|---|---|"]
    for ms, rb, mb, tp, tg, d, k, npet, ngas in rows:
        flag = " ⚠" if abs(d) >= 3 else ""
        out.append(f"| {ms:.2f} | {rb.left:g}–{rb.right:g} | {mb.left:.2f}–{mb.right:.2f} | {tp:+.1f} | {tg:+.1f} | "
                   f"{d:+.1f}{flag} | {k:.3f} | {100 * (k - 1):+.1f} % | {npet}/{ngas} |")
    out += ["", "k = (1 + трим_газ) / (1 + трим_бенз) — у скільки разів змінити точку множника при цьому Б. "
            "Правити **у відсотках від поточного значення точки** (лінія множника в AC STAG ≠ Г/Б через поправки), "
            "першим кроком 60–80 % від розрахунку; ⚠ = |Δ| ≥ 3 п.п.", ""]
    return out


def stag_report(df: pd.DataFrame, s: pd.DataFrame, dev, v, out_dir: Path, work_p: float | None = None) -> list[str]:
    lines = ["## Запис Stag (осцилограф AC STAG)", "",
             f"Файли: {', '.join(sorted(s['osc_file'].unique()))}; {len(s)} вибірок, "
             f"{s['time'].iloc[0]:%H:%M:%S} – {s['time'].iloc[-1]:%H:%M:%S}.", "",
             "![stag](stag_overview.png)", ""]
    if "stag_on_gas" in df:
        # df["fuel"] is already overwritten from Stag; compare Stag with the operator's own g/b keys
        marks = df["fuel_marker"] if "fuel_marker" in df else pd.Series("", index=df.index)
        both = df[marks.isin(["gas", "petrol"]) & df["stag_on_gas"].notna()]
        if len(both):
            agree = ((both["fuel_marker"] == "gas") == (both["stag_on_gas"] == 1)).mean() * 100
            lines.append(f"Позначки g/b в ELM-лозі збігаються зі статусом Stag на {agree:.0f} % рядків "
                         f"({len(both)} рядків з позначками).")
        else:
            lines.append("Позначок g/b в ELM-лозі немає.")
        lines += ["Для аналізу тримів паливо береться зі статусу Stag там, де він є.", ""]
    lines += trim_cells_by_ms(df, out_dir)
    lines += pressure_report(s, work_p)
    if dev is not None:
        dev.round(1).to_csv(out_dir / "stag_deviation.csv")
        dv = dev.values.astype(float)
        if np.isfinite(dv).any():
            i, j = np.unravel_index(np.nanargmax(np.abs(dv)), dv.shape)
            cell = v[(v["on_gas"] == 0) & (v["rpm"] >= RPM_BINS[i]) & (v["rpm"] < RPM_BINS[i + 1])
                     & (v["map_bar"] >= MAP_BINS[j]) & (v["map_bar"] < MAP_BINS[j + 1])]["petrol_ms"]
            ms = f"{cell.mean():.1f} мс" if len(cell) else "—"
            lines.append(f"Найбільше відхилення D: {dv[i, j]:+.1f} % при {RPM_BINS[i]}–{RPM_BINS[i+1]} об/хв, "
                         f"MAP {MAP_BINS[j]:.2f}–{MAP_BINS[j+1]:.2f} бар; час впорску Б на бензині там ≈ {ms} — "
                         "це точка на осі множника для корекції. Мануал Stag вважає нормою ±10 %, ціль ±5 %.")
            # Cells binned by rpm x MAP (independent of fuel), listed by their petrol-mode injection
            # time: that time is the multiplier's x axis in AC STAG.
            pet_ms = (v[v["on_gas"] == 0]
                      .assign(rpm_bin=lambda x: pd.cut(x["rpm"], RPM_BINS, right=False),
                              map_bin=lambda x: pd.cut(x["map_bar"], MAP_BINS, right=False))
                      .groupby(["rpm_bin", "map_bin"], observed=True)["petrol_ms"].mean())
            rows = []
            for (ri, mi), d_ in dev.stack().items():
                if np.isfinite(d_) and (ri, mi) in pet_ms.index:
                    rows.append((pet_ms[(ri, mi)], ri, mi, d_))
            if rows:
                lines += ["", "Клітинки, впорядковані за часом впорску Б (= вісь множника):", "",
                          "| Б на бензині, мс | оберти | MAP, бар | D, % |", "|---|---|---|---|"]
                lines += [f"| {ms_:.2f} | {ri.left:g}–{ri.right:g} | {mi.left:.2f}–{mi.right:.2f} | {d_:+.1f} |"
                          for ms_, ri, mi, d_ in sorted(rows)]
        lines += ["", "![stag deviation](stag_deviation.png)", ""]
    else:
        lines += ["Замало даних для карти D (потрібні і газ, і бензин на прогрітому моторі в однакових режимах).", ""]
    return lines


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="?", help="ELM log (default: newest logs/elm_*.csv)")
    ap.add_argument("--osc", nargs="*", default=None,
                    help="AC STAG .osc files (default: all stag/osc/**/*.osc overlapping the log)")
    ap.add_argument("--work-pressure", type=float, default=None,
                    help="'Рабочее давление' from AC STAG settings, bar (to compare with actual gas pressure)")
    args = ap.parse_args()
    if args.csv:
        path = Path(args.csv)
    else:
        logs = sorted((p for p in (ROOT / "logs").glob("elm_*.csv") if not p.name.endswith(".stag.csv")),
                  key=lambda p: p.stat().st_mtime)
        if not logs:
            print("no logs/elm_*.csv found")
            return 1
        path = logs[-1]
    out = ROOT / "analysis" / path.stem
    out.mkdir(parents=True, exist_ok=True)
    df = load(path)
    print(f"{path.name}: {len(df)} rows, {df['t_s'].iloc[-1] / 60:.1f} min")

    osc_paths = [Path(p) for p in args.osc] if args.osc is not None else sorted((ROOT / "stag" / "osc").rglob("*.osc"))
    stag = None
    stag_csv = path.with_name(path.stem + ".stag.csv")
    if args.osc is None and stag_csv.exists():
        stag = load_stag_csv(stag_csv)
    if stag is None and osc_paths:
        t = pd.to_datetime(df["time_iso"])
        stag = load_stag(osc_paths, t.iloc[0], t.iloc[-1])
    if stag is not None:
        df = merge_stag(df, stag)
        # Stag's own status is measured; prefer it over manual g/b markers
        has = df["stag_on_gas"].notna()
        df["fuel_marker"] = df["fuel"]
        df.loc[has, "fuel"] = np.where(df.loc[has, "stag_on_gas"] == 1, "gas", "petrol")
        switch_t = df["t_s"].where(df["fuel"].ne(df["fuel"].shift()))
        df["since_switch"] = df["t_s"] - switch_t.ffill()

    plot_overview(df, out / "overview.png")
    report = [f"# Аналіз логу `{path.name}`", "",
              f"Тривалість {df['t_s'].iloc[-1] / 60:.1f} хв, {len(df)} рядків, "
              f"середня частота {len(df) / max(df['t_s'].iloc[-1], 1):.1f} Гц.", "",
              "![overview](overview.png)", ""]
    if "trim" in df:
        v = valid_trim_rows(df)
        report += [f"## Корекції палива: газ проти бензину", "",
                   f"Враховано {len(v)} точок: ECT ≥ {WARM_ECT} °C, closed loop, ≥ {SETTLE_S} с після перемикання, "
                   f"без накату, LTFT не навчається, інше паливо в межах ±{PAIR_S // 60} хв.", "",
                   filter_note(df), ""]
        if len(v):
            tab, by_mode = trim_tables(v)
            diff = plot_trim_maps(tab, out / "trim_maps.png")
            plot_by_mode(by_mode, out / "trim_by_mode.png")
            by_mode.to_csv(out / "trim_by_mode.csv")
            for f, t in tab.items():
                if t is not None:
                    t.round(2).to_csv(out / f"trim_map_{f}.csv")
            report += ["### Висновок по тримах", ""] + verdict(by_mode, diff) + [
                "", "![by mode](trim_by_mode.png)", "", "![maps](trim_maps.png)", "",
                "Від'ємна корекція = ECU прибирає паливо (суміш була багата). Якщо на газу корекція помітно "
                "нижча, ніж на бензині в тій самій клітинці, Stag переливає саме там → знижувати множник "
                "(або 'Кор. оборотов') у відповідній зоні часу впорску.", "",
                "```", by_mode.to_string(), "```", ""]
    report += ["## Кут випередження і температура каталізатора: газ проти бензину", "",
               timing_cat_compare(df), "",
               "## Лямбда-зонд B1S1", "", o2_stats(df), "",
               "## Каталізатор: датчик B1S2 після нього", "", catalyst_stats(df, o2s2_is_fast(path)), "",
               "## Температура охолоджуючої рідини", "", ect_stats(df), "",
               "## Вплив кондиціонера", "", ac_effect(df, out / "ac_effect.png"), "",
               "![ac](ac_effect.png)", "",
               "## Оцінка витрати з MAF", "",
               "Розрахунок: паливо = повітря / стехіометрія (газ 15.5, бензин 14.7), густина газу 0.54 кг/л. "
               "У closed loop λ≈1 незалежно від калібровки Stag, тому це оцінка того, **скільки палива "
               "двигуну реально треба**. Якщо фактична витрата по заправках помітно більша — надлишок "
               "іде в зонах open loop (розгони, холодний двигун) або це похибка вимірювання витрати.", "",
               consumption(df), ""]
    report += health_report(path)
    if stag is not None:
        dev, v = stag_deviation(stag, df)
        plot_stag(stag, dev, out)
        report += stag_report(df, stag, dev, v, out, args.work_pressure)
    (out / "report.md").write_text("\n".join(report), encoding="utf-8")
    print(f"report: {out / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
