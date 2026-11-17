# 02 — Diagnosing and tuning 4th-gen sequential LPG systems (general theory and practice)

Scope: general, not brand-specific, knowledge for vapour-phase sequential ("4th generation", "sekwencja", "ГБО-4")
autogas systems on port-injected petrol engines. Written for a skill that helps an owner explain why LPG consumption
rose (example: 12 -> 14 L/100 km on a 1.6 petrol engine) and how to check and recalibrate the system.

Confidence marks used below:
- **[MS]** multiple independent sources agree
- **[SS]** single source (manufacturer manual, article or paper)
- **[FO]** forum or installer opinion, not verified
- **[CALC]** my own calculation or inference from the cited data

Research date: 2026-09. All URLs were accessed during research. Some come from search-result snippets where the page
itself blocked fetching; those are marked "(snippet)".

---

## 1. How the system works and why petrol fuel trims are the reference

### 1.1 Signal chain

- The petrol ECU keeps full control of the engine. It calculates the petrol injector opening time (Tinj, ms) from
  MAF/MAP, rpm, coolant temperature, lambda feedback and its adaptive trims. On gas, the LPG ECU cuts the petrol
  injector signals, reads the time the petrol ECU *wanted* to inject, and opens gas injectors for a converted time.
  **[MS]** ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf),
  [AC installation guide](https://www.stag.gr/sites/default/files/wytyczne_montazuen_0.pdf),
  [ScienceDirect, K-coefficient paper](https://www.sciencedirect.com/science/article/abs/pii/S0016236115003294))
- Basic conversion: T_gas = T_petrol x K (the "multiplier"), plus corrections. K is set during auto-calibration and then
  refined while driving. **[MS]** (same sources; [AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf))
- Typical correction layers in a modern gas ECU (STAG is a representative example): the multiplier curve against petrol
  Tinj; an rpm x Tinj correction map; gas-temperature, reducer-temperature and gas-pressure correction maps; per-injector
  % correction and opening-offset correction; and optional "autoadaptation" maps (rpm x MAP) driven by OBD trims or by a
  stored petrol reference map. **[SS]** ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf))
- Pressure compensation is based on flow physics. One published method scales gas time by sqrt(P_ref / P_actual) when the
  measured rail pressure moves away from the calibration pressure. **[SS]** (search summary of patent/manual literature,
  [rangerovers.pub autocalibration topic](https://rangerovers.pub/topic/1054-lpg-system-autocalibration))
  - [CALC] So an uncompensated +10 % rail-pressure rise gives about +5 % gas flow. STAG warns that any change of
    "operational pressure" requires re-doing the multiplier map. **[SS]** ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf))
- Rail pressure is regulated as a *difference* over manifold pressure (gas pressure minus MAP), so it should stay roughly
  constant across load. **[MS]** ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf),
  [CRD Performance](https://www.crdperformance.com/lpg/lpg-conversions/lpg-technical-information/))
- Vapour density depends on temperature: [CALC] at constant pressure it scales with 1/T(K), so going from 20 °C to 50 °C gas
  temperature cuts density by about 10 %. That is why gas and reducer temperature sensors and correction maps exist.

### 1.2 Why petrol ECU trims and petrol Tinj are the "ground truth"

- The petrol ECU does not know it is running on gas. Its lambda loop keeps correcting towards lambda = 1. If the gas dose
  is wrong, the ECU changes STFT/LTFT and therefore the petrol Tinj it outputs. The rule every manual gives: **at the same
  operating point, petrol Tinj and trims on gas should equal those on petrol.** **[MS]**
  - STAG: if petrol Tinj gets longer after switching to gas, raise the multiplier at that Tinj; if it gets shorter,
    lower it. When the point is right, Tinj does not change on switch-over. ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf))
  - AEB: trims moving positive on gas mean the petrol ECU sees lean, so raise K. Negative means rich, so lower K. Correct
    when the trim moves by more than 3–4 points from its petrol value (example given: 8 % -> 11–12 %), or when Tinj moves
    by about 0.5–1 ms. Trims are preferred over Tinj when OBD is available.
    ([AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf))
  - The AC installation guide requires petrol trims to be close to 0 **before** installation, because a sick petrol
    system gives a sick gas calibration. ([AC guide](https://www.stag.gr/sites/default/files/wytyczne_montazuen_0.pdf))
- Sign convention: positive trim = ECU adding fuel (it sees lean). Negative = ECU removing fuel (it sees rich). A few
  ECUs are reversed, and STAG has a "Reversed STFT" option for them. **[MS]**
  ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf),
  [w203.pl forum](https://w203.pl/lpg-nowy-dzial-f51/korekty-krotkoterminowe-i-dlugoterminowe-t14820.html))
- **Shared LTFT problem:** the petrol ECU keeps one set of adaptations. A lean or rich gas calibration is "learned" into
  LTFT while on gas and then carried over to petrol, where the car runs off-target until it relearns. A big gap between
  petrol and gas trims means a fault, poor calibration or poor installation. **[FO]**
  ([rangerovers.net thread (snippet)](https://www.rangerovers.net/threads/cant-it-be-similar-on-lpg-and-petrol.24795/))
  - [CALC] Practical consequence: a car driven 95 % on gas has LTFT values that mostly describe the *gas* calibration.
    To see a "petrol baseline" you need a proper petrol drive (installers suggest 30–50 km after an adaptation reset,
    [w203.pl](https://w203.pl/lpg-nowy-dzial-f51/korekty-krotkoterminowe-i-dlugoterminowe-t14820.html) [FO]).

### 1.3 Closed loop vs open loop, and why this matters for consumption

- **Closed loop** (warm engine, part load, cruise, idle): the lambda sensor drives STFT, so a moderately wrong gas map is
  *hidden*. The ECU trims it out, fuel use stays near stoichiometric, and the only signs are shifted trims or Tinj.
  **[MS]** ([best-obd2-scanner](https://best-obd2-scanner.com/fuel-trims-explained/),
  [STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf))
- **Open loop** (cold engine and warm-up enrichment, heavy load or WOT enrichment, some transients, overrun cut): lambda
  feedback is ignored and the ECU uses fixed tables, so trims are frozen. Any multiplier error in that area goes
  **straight into fuel consumption** (rich) or into combustion temperature (lean). **[MS]**
  ([best-obd2-scanner](https://best-obd2-scanner.com/fuel-trims-explained/),
  [thirdgen.org PE discussion](https://www.thirdgen.org/forums/diy-prom/374720-open-loop-power-enrichment.html))
  - STAG's own adaptation has an "Adaptation in OL" option that needs a lambda signal and fuel-loop status from OBD.
    Without it, open-loop areas are not auto-corrected. **[SS]** ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf))
  - Some installers deliberately run open-loop areas about 10 % rich on gas "to protect valves", which costs fuel
    directly. **[FO]** ([drive2 GBO community](https://www.drive2.ru/c/483792813876576598/))
- Low-load, short-pulse area: STAG says *not* to chase zero deviation at very short petrol pulses, because the ECU
  naturally scatters Tinj there. Its minimum sensible adaptation threshold is 4 %. **[SS]**
  ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf))
- High load: if petrol injectors approach 100 % duty, the gas ECU can only hold its injectors open and can no longer
  correct. STAG suggests switching to petrol at about 95 % load in such engines. **[SS]** (same manual)

### 1.4 Typical calibration numbers

- STAG: after idle auto-calibration the multiplier at the calibration point should be **1.2–1.8**. Above 1.8, gas pulses
  can overlap at high load ("injection time too long"), so larger nozzles are needed. If gas Tinj is *shorter* than petrol
  Tinj at idle, the nozzles are probably too big. **[SS]** ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf))
- UK forum: multiplier about 1.1 at idle falling to 1.0 at full load for Matrix injectors, and about 1.2 -> 1.0 for some
  Tartarini injectors. **[FO]** ([search summary of rangerovers.pub](https://rangerovers.pub/topic/731-advanced-lpg-mapping))
- Map-comparison acceptance: STAG considers the calibration correct when the petrol-vs-gas Tinj deviation map is within
  **±10 %**. Positive deviation there means gas-map times are shorter than petrol, i.e. the gas mixture is rich. **[SS]**
  ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf))
- Manual check points: hold constant load at about 5 ms petrol Tinj, switch fuels, adjust, then repeat about every 2 ms up
  to heavy load. **[SS]** (same manual)
- Gas-temperature map tuning: start from cold and compare Tinj on petrol vs gas in 5 °C gas-temperature steps. **[SS]** (same)
- Idle auto-calibration conditions: warm engine, lambda active, A/C and lights off, no steering. **[MS]**
  ([STAG-4 manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf),
  [AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf))

---

## 2. Expected LPG consumption vs petrol, and how to measure it

### 2.1 Fuel properties

| Property | Petrol | Propane | n-Butane | Source / confidence |
|---|---|---|---|---|
| Stoichiometric AFR (mass) | ~14.7 | ~15.7 | ~15.5 | petrol: [Wikipedia AFR](https://en.wikipedia.org/wiki/Air%E2%80%93fuel_ratio); LPG ~15.5 per [propan.ru](https://www.propan.ru/forum/viewtopic.php?t=5321) [FO]; C3/C4 values [CALC] from combustion stoichiometry (15.68 / 15.47) |
| Liquid density | 0.71–0.77 kg/L | 0.519 kg/L @ 6.9 °C; 0.473 @ 36.9 °C | 0.593 @ 6.9 °C; 0.571 @ 26.9 °C | [Wikipedia LPG](https://en.wikipedia.org/wiki/Liquefied_petroleum_gas); [EngineeringToolbox propane](https://www.engineeringtoolbox.com/propane-C3H8-density-specific-weight-temperature-pressure-d_2033.html); [EngineeringToolbox butane](https://www.engineeringtoolbox.com/butane-density-specific-weight-temperature-pressure-d_2080.html) [MS] |
| LPG mix density | – | 0.50–0.58 kg/L depending on mix and temperature | | [Wikipedia LPG](https://en.wikipedia.org/wiki/Liquefied_petroleum_gas) [MS] |
| Energy per kg | ~43.5 MJ/kg | ~46.3 MJ/kg | ~45.7 MJ/kg | [Wikipedia LPG](https://en.wikipedia.org/wiki/Liquefied_petroleum_gas), [Illinois LHV table](https://courses.grainger.illinois.edu/npre470/sp2018/web/Lower_and_Higher_Heating_Values_of_Gas_Liquid_and_Solid_Fuels.pdf) [MS] |
| Energy per litre | ~34.2 MJ/L | ~25.5 MJ/L | ~28.7 MJ/L | [Wikipedia Autogas](https://en.wikipedia.org/wiki/Autogas) [MS] |
| Octane (RON/MON) | 95 RON typical | 90–110 for autogas | | [Wikipedia Autogas](https://en.wikipedia.org/wiki/Autogas) [SS] |

- Thermal expansion [CALC] from the density rows: liquid propane changes volume by about 0.3 %/°C, butane by about
  0.19 %/°C. Mixed LPG is roughly 0.25 %/°C. So 20 °C of difference in fuel temperature at the pump changes the litres
  dispensed per kg by about 5 %.
- Energy-per-litre ratio petrol:LPG is about 34.2 / 26–27 = **1.27–1.33** [CALC]. Wikipedia cites about 1.33 L autogas per
  1 L petrol on a CO2-equivalence basis ([Autogas](https://en.wikipedia.org/wiki/Autogas)).

### 2.2 Expected volumetric increase: what sources say

| Claim | Source | Confidence |
|---|---|---|
| +10–20 % litres vs petrol | [gaz-time.pl](https://gaz-time.pl/dlaczego-rosnie-zuzycie-gazu-w-aucie-od-czego-zalezy-spalanie), [gepard.org.ua](https://gepard.org.ua/bolshoj-rashod-gaza-gbo-4-pokolenija/) | [MS] |
| +15 % (8 L petrol -> 9.2 L LPG) | [Van Meenen](https://www.vanmeenen.com/en/is-it-still-cost-effective-to-drive-on-lpg/) | [SS] |
| +10–30 % | [gbomotor.ru](https://gbomotor.ru/diagnostika-i-remont/bolshoy-rashod-gaza-na-mashine-s-gbo) | [SS] |
| ~+10 % "normal" for 4th gen | [search summary of RU/UA sites](https://mirgbo.ru/vse-o-gbo/bolshoy-rashod-gaza-gbo-4-pokoleniya-prichiny/) | [FO] |
| Summer 1 L petrol = 1.19 L LPG, winter 1.25 L; >20 % is suspicious | [digitronicgas.ru](https://www.digitronicgas.ru/stati/pereraskhod-gaza-na-avto/) | [SS] |
| Investigate if gap >30 % | [pyrogiv.kiev.ua](https://pyrogiv.kiev.ua/uvelichilsya-rasxod-gaza/) | [SS] |

- **Working rule for the skill:** a healthy, well-calibrated system typically uses **+15–25 %** litres vs the *same car's
  real petrol consumption on the same routes*. Above +30 % is a red flag. [CALC], synthesised from the table.
- Why real-world figures (+10–25 %) come in below the pure energy ratio (+27–33 %) [CALC/inference]: warm-up and some
  high-load running are on petrol and not counted; there is no wall-wetting or cold enrichment on gas; there is a small
  efficiency gain from gaseous mixture and knock margin. This is not well documented (see open questions).
- Applied to the example: 12 L/100 km LPG on a 1.6 NA engine implies about 9.5–10.5 L/100 km petrol-equivalent.
  That is plausible for city driving but high for mixed driving. 14 L/100 km (+17 % over the previous LPG figure) is a
  real change worth diagnosing unless season, routes or measurement explain it. [CALC]

### 2.3 Seasonal mix effects

- Poland: winter mix (1 Dec–31 Mar) about 60 % propane / 40 % butane; summer (1 Apr–30 Nov) about 40/60. Butane does not
  vaporise below about −0.5 °C, which is why winter mixes carry more propane. **[SS]**
  ([fuelfusion.pl](https://fuelfusion.pl/en/lpg-composition-differences-in-summer-and-winter/)). Russia: winter up to
  about 75 % propane. **[SS]** ([digitronicgas.ru](https://www.digitronicgas.ru/stati/pereraskhod-gaza-na-avto/))
- Effect on consumption: +3–5 % in winter ([gbomotor.ru](https://gbomotor.ru/diagnostika-i-remont/bolshoy-rashod-gaza-na-mashine-s-gbo) [SS]);
  about +5 % (1.19 -> 1.25) ([digitronicgas.ru](https://www.digitronicgas.ru/stati/pereraskhod-gaza-na-avto/) [SS]).
  [CALC] Swapping 40/60 for 60/40 alone lowers energy per litre by only about 2.3 % (27.4 -> 26.8 MJ/L). The rest of the
  winter penalty comes from longer warm-up (more petrol and open-loop time), cabin heat, denser air, colder tyres and
  cold oil.
- General cold-weather penalty for petrol cars: about 15 % worse in city driving at −7 °C (20 °F) vs 25 °C, and up to 24 %
  on 5–6 km trips **[SS]** ([fueleconomy.gov](https://www.fueleconomy.gov/feg/coldweather.shtml)). These factors apply on
  LPG too.
- Counter-effect [CALC]: cold LPG at the pump is denser, so each litre bought in winter carries more kg and energy. A
  winter-vs-summer litre comparison therefore mixes a composition penalty with a density bonus.

### 2.4 Measuring consumption properly

- Use fill-to-fill: fill until the pump stops, drive, fill again, then litres / km x 100. Over 500 km or more the error is
  about 2–3 % **[SS]** ([search summary, kalkulato.pl](https://kalkulato.pl/blog/auto/jak-obliczyc-spalanie-paliwa-wzor/)).
- The 80 % fill-stop valve is a float mechanism and is repeatable only to about **80 % ±5 %** of tank volume **[SS]**
  ([warsztat.pl](https://warsztat.pl/artykuly/ile-gazu,61205)). [CALC] On a 50 L (water) tank that is ±2.5 L per fill, so
  a single 300 km fill-to-fill can be off by ±8 %. Average **at least 3 fills or more than 1000 km**, use the same station
  and pump, and fill until the valve actually stops (not a pump preset).
- Cold gas is denser, so more fits in the tank; in-ground station tanks keep a steadier temperature than above-ground
  ones **[MS]** ([warsztat.pl](https://warsztat.pl/artykuly/ile-gazu,61205),
  [capri.pl forum](https://www.capri.pl/forum/46181)). Some dispensers over-read; the article cites a display showing
  45 L when about 40 L was delivered **[SS]** ([warsztat.pl](https://warsztat.pl/artykuly/ile-gazu,61205)).
- The in-car gauge is a magnetic float dial. It reads percent of volume and moves with temperature, so it is not a
  consumption meter. The tank pressure gauge shows only vapour pressure vs temperature (about 4 bar at 0 °C, 7 bar at
  20 °C, >12 bar at 40 °C), not the amount left **[SS]** ([bruo.eu](https://bruo.eu/wskaznik-poziomu-gazu-lpg-i-manometr/)).
- Also log petrol used for warm-up. If the switch-over temperature or delay is raised, or the car does many short trips,
  more petrol is burned and LPG L/100 km looks *better* while total cost rises. [CALC]
- Record per fill: km, litres, ambient temperature, share of city/highway, A/C use, and any switch-backs to petrol.

---

## 3. Causes of increased LPG consumption, ranked, with symptoms and checks

Ranking is my synthesis of how often each cause is cited and how plausible it is for a gradual +10–20 % rise. [CALC]

| # | Cause | Typical symptoms | How to verify | Sources / confidence |
|---|---|---|---|---|
| 1 | **Measurement / season / usage change** (winter mix, cold, short trips, A/C, route change, fill-valve scatter) | Rise matches season or usage; trims normal | Compare like-for-like periods; average ≥3 fills; check petrol baseline | [fueleconomy.gov](https://www.fueleconomy.gov/feg/coldweather.shtml), [warsztat.pl](https://warsztat.pl/artykuly/ile-gazu,61205) [MS] |
| 2 | **Calibration drift**: injectors wearing, reducer pressure changed, map built on bad petrol baseline | Petrol Tinj or trims differ gas vs petrol; gas ECU adaptation near its limit | Petrol-vs-gas comparison at same rpm/MAP (section 4) | [STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf), [selected.pl](https://selected.pl/instalacja-lpg-wysokie-spalanie-gazu-analiza-ustawien-mapy-i-stanu-filtrow/) [MS] |
| 3 | **Dirty or worn gas injectors** (oily "heavy fraction" deposits, worn seals, weak springs, slow opening) | Uneven running at constant speed, cylinder-to-cylinder differences, rising multiplier needed, higher use | Per-injector test (switch one cylinder to gas at a time, watch Tinj/trims); flow bench; replace or rebuild | [gaga.ba](https://gaga.ba/en/savjeti-za-vozace/najcesci-problemi-auto-plin), [gepard](https://gepard.org.ua/bolshoj-rashod-gaza-gbo-4-pokolenija/), [STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf) [MS] |
| 4 | **Clogged liquid or vapour-phase filter** | Pressure dips under load, hesitation, switch-back to petrol ("low gas pressure"), high positive trims at load | Log gas pressure at WOT/high load; replace filters (cheap) | [link4.pl](https://www.link4.pl/blog/zapchany-filtr-gazu-objawy-przyczyny-i-wymiana), [nagaz24.pl](https://nagaz24.pl/objawy-zapchania-filtrow-w-instalacji-lpg-kiedy-wymienic-elementy-filtrujace/), [digitronicgas.ru](https://www.digitronicgas.ru/stati/filtry-gazovogo-oborudovaniya-na-avto/) [MS] (snippets) |
| 5 | **Reducer faults** (aged diaphragm, weak spring, pressure creep or "floating", undersized for power) | Unstable idle pressure; pressure higher than calibration (rich) or dips under load (lean then enriched); cold-start trouble | Log gas pressure vs calibration pressure at idle, cruise, WOT; watch pressure after overrun cut | [stag.ua](https://stag.ua/ru/article/kakoe-dolzhno-byt-davlenie-reduktora-gbo-4-pokoleniya-i-kak-ego-pravilno-nastroit/), [gaga.ba](https://gaga.ba/en/savjeti-za-vozace/najcesci-problemi-auto-plin), [AC guide](https://www.stag.gr/sites/default/files/wytyczne_montazuen_0.pdf) [MS] |
| 6 | **Engine running cold / thermostat stuck open** | Coolant below about 80 °C on highway or in winter, slow warm-up, P0128, low reducer/gas temperature, long warm-up enrichment | Log coolant temperature (OBD PID 0x05) and reducer temperature over a drive | [obd-codes P0128](https://www.obd-codes.com/p0128), [fridayparts](https://www.fridayparts.com/blog/symptoms-of-the-thermostat-stuck-open-vs-closed) [MS] |
| 7 | **Low reducer or gas temperature** (reducer poorly placed, coolant hoses in series or clogged, heater-circuit issues) | Gas temp stays low, rough on gas until warm, "low gas temperature" message | Compare gas and reducer temperatures vs coolant temperature | [STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf), [AC guide](https://www.stag.gr/sites/default/files/wytyczne_montazuen_0.pdf), [gazeo forum](https://gazeo.pl/forum_lpg_cng/topic/512/temperatura-gazu-w-zimie/) [MS] |
| 8 | **Lambda sensor ageing** | Slow switching, biased voltage, sluggish trims; affects both fuels | Watch switching rate and amplitude (section 4.4) | [aa1car](https://www.aa1car.com/library/o2sensor.htm), [pyrogiv](https://pyrogiv.kiev.ua/uvelichilsya-rasxod-gaza/) [MS] |
| 9 | **Air-path / sensor faults**: vacuum leak, dirty MAF, MAP error, petrol injector deposits (they distort the reference petrol Tinj) | Trims off *on petrol too*; vacuum leak = high positive trims at idle that improve at load; MAF under-read = positive trims across load | Petrol-only trim check first; smoke test; MAF g/s vs expected | [best-obd2-scanner](https://best-obd2-scanner.com/fuel-trims-explained/), [lpg-forum.pl (snippet)](https://lpg-forum.pl/czy-zapieczone-wtryskiwacze-benzynowe-maja-wplyw-na-lpg-t12392.html) [MS] |
| 10 | **Ignition problems**: worn plugs, weak coils, leads | Misfire on gas under load (LPG needs higher spark voltage), shaking, P030x | Misfire counters (Mode 06), plug inspection | [gaz-time.pl](https://gaz-time.pl/dlaczego-rosnie-zuzycie-gazu-w-aucie-od-czego-zalezy-spalanie), [alfaholicy forum](https://www.forum.alfaholicy.org/lpg_zone/116412-przerwa_miedzy_elektrodami_swiecy_przy_zasilaniu_lpg.html) [MS/FO] |
| 11 | **Mechanical**: tight valves (LPG valve recession), low compression, timing jumped a tooth | Rough idle, power loss, hard start; trims high on both fuels | Valve clearance check, compression test, cam/crank correlation | [Oleksyk study](https://repozytorium.biblos.pk.edu.pl/redo/resources/31271/file/suwFiles/OleksykP_WplywZasilania.pdf), [mojaceed forum](https://forum.mojaceed.pl/viewtopic.php?f=36&t=11655) [SS/FO] |
| 12 | **Leaks** (fittings, hoses, multivalve) | Gas smell, fuel "disappears" while parked, level drops overnight | Soap solution or electronic sniffer on all joints; never ignore a smell | [digitronicgas.ru](https://www.digitronicgas.ru/stati/pereraskhod-gaza-na-avto/), [pyrogiv](https://pyrogiv.kiev.ua/uvelichilsya-rasxod-gaza/) [MS] |
| 13 | **Vehicle losses**: under-inflated tyres, dragging brakes, A/C, roof box, load | Consumption up on both fuels, coasting distance shorter, hot wheel | Tyre pressure, brake-drag feel, A/C usage log | [fueleconomy.gov](https://www.fueleconomy.gov/feg/maintain.jsp), [TRR study via ResearchGate](https://www.researchgate.net/publication/289725724_Effects_of_Air_Conditioner_Use_on_Real-World_Fuel_Economy) [MS] |
| 14 | **Fuel quality**: unusual mix, contaminants, water, "heavy fractions" | Sudden change after a new station; oily residue in filters | Change station for 2–3 fills; inspect the filter | [gaz-time.pl](https://gaz-time.pl/dlaczego-rosnie-zuzycie-gazu-w-aucie-od-czego-zalezy-spalanie), [lpgtech.ua (snippet)](https://lpgtech.ua/slivayte-kondensat) [MS] |

### Numbers behind some causes

- **Reducer pressure (differential):** most 4th-gen setups run about 1.0–1.4 bar. STAG Ukraine reports about 80 % of NA
  engines best at 1.0–1.2 bar, turbo 1.2–1.4, and ≤1.6 L engines 0.9–1.1. The typical switch-to-petrol threshold is
  0.5–0.6 bar. **[MS]** ([stag.ua](https://stag.ua/ru/article/kakoe-dolzhno-byt-davlenie-reduktora-gbo-4-pokoleniya-i-kak-ego-pravilno-nastroit/),
  [search summary, RU sites](https://profigas.ua/ru/news/regulirovka-davleniya-reduktora-gbo-4-pokoleniya)). AC describes about 1.5 bar
  as nominal ([AC guide](https://www.stag.gr/sites/default/files/wytyczne_montazuen_0.pdf) [SS]). Zavoli Zeta S is preset
  to 1.4 bar and Zeta N to 1.2 bar ([rangerovers.pub](https://rangerovers.pub/topic/731-advanced-lpg-mapping) [FO]).
  Pressure sensor tolerance is about ±0.1 bar ([stag.ua](https://stag.ua/ru/article/kakoe-dolzhno-byt-davlenie-reduktora-gbo-4-pokoleniya-i-kak-ego-pravilno-nastroit/) [SS]).
- **Pressure drop under load:** an adequately sized reducer should drop no more than about **0.2 bar** on sudden
  acceleration **[SS]** (search summary attributed to [AC publication part IV](https://www.ac.com.pl/customer-publications/3936/basic-installation-and-adjustment-guidelines-for-autogas-systems-part-iv/?ph_kontener_glowny_D_start=show)).
  Polish forums tolerate up to about 30 % **[FO]** ([search summary, elektroda/accordklub](http://www.accordklubpolska.pl/index.php?%2Ftopic%2F44770-spadek-ci%C5%9Bnienia-gazu-na-obci%C4%85%C5%BCeniu%2F=)).
  The gas ECU compensates pressure mathematically, but a large dip means lean spots at high load.
- **Temperatures:** switch-to-gas reducer temperature is usually set at **20–45 °C** (AEB recommendation). Some
  installers want about 35–45 °C coolant before switching **[MS]**
  ([AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf),
  [gaga.ba](https://gaga.ba/en/savjeti-za-vozace/najcesci-problemi-auto-plin)). STAG hot start needs reducer ≥20 °C and
  gas ≥10 °C ([STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf) [SS]).
  Reported gas temperatures: a user saw poor running below about 28 °C gas temperature in winter, fine above 30 °C,
  with reducer at 60 °C ([gazeo forum](https://gazeo.pl/forum_lpg_cng/topic/512/temperatura-gazu-w-zimie/) [FO]).
  Digitronic names 35–55 °C as the optimal range ([digitronicgas.ru](https://www.digitronicgas.ru/stati/kak-nastroit-gbo/) [SS]).
  With a healthy thermostat the reducer (coolant side) sits near engine temperature, about 80–90 °C
  ([search summary, Polish forums](https://www.elektroda.pl/rtvforum/topic1221251.html) [FO]).
- **Thermostat stuck open:** reported penalty 5–25 % (wide spread, generic car-care sites) **[MS, low quality]**
  ([fridayparts](https://www.fridayparts.com/blog/symptoms-of-the-thermostat-stuck-open-vs-closed),
  [smartintlgroup](https://www.smartintlgroup.com/post/can-a-bad-thermostat-cause-poor-fuel-economy)). On LPG it hurts
  twice: the petrol ECU keeps warm-up enrichment longer, *and* reducer and gas temperature fall into the range where the
  temperature correction maps are least accurate [CALC].
- **A/C:** up to about 21 % worse in urban driving and 6–10 % on highway in one study
  ([TRR via ResearchGate](https://www.researchgate.net/publication/289725724_Effects_of_Air_Conditioner_Use_on_Real-World_Fuel_Economy) [SS]).
  A Swiss study found about +10 % urban, 2.8 % suburban and 1.3 % motorway
  ([ScienceDaily](https://www.sciencedaily.com/releases/2010/06/100622081359.htm) [SS]).
- **Tyres:** about 0.2 % loss per 1 psi under-inflation (average of 4 tyres). Correct oil grade is worth 1–2 %. Air filter
  changes do not affect economy on fuel-injected cars (acceleration only) **[SS]** ([fueleconomy.gov](https://www.fueleconomy.gov/feg/maintain.jsp)).
- **Ignition timing:** RU sources say a timing-advance processor can save about 1 L/100 km on gas **[FO]**
  ([gbomotor.ru](https://gbomotor.ru/diagnostika-i-remont/bolshoy-rashod-gaza-na-mashine-s-gbo)). This is mostly relevant
  to older engines. Modern ECUs set timing from knock and load and are not told which fuel is in use.
- **Consequence of rich or lean running:** long operation rich or lean overheats valves and the catalyst
  ([selected.pl](https://selected.pl/instalacja-lpg-wysokie-spalanie-gazu-analiza-ustawien-mapy-i-stanu-filtrow/) [SS]).
  A partly blocked gas nozzle (excess thread glue) made one cylinder lean and burned its valve seat in a Toyota Yaris
  ([Oleksyk](https://repozytorium.biblos.pk.edu.pl/redo/resources/31271/file/suwFiles/OleksykP_WplywZasilania.pdf) [SS]).

---

## 4. Diagnostic methodology

### 4.1 Pre-checks (before touching the gas map)

1. Read DTCs on both ECUs: petrol OBD and the gas ECU error log with its freeze frames (pressure, temperatures, rpm, MAP,
   Tinj) ([STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf)).
2. Confirm the petrol baseline. After a petrol-only drive, trims should be near 0. Installers accept ±5 % as good and
   ±10 % as acceptable ([w203.pl](https://w203.pl/lpg-nowy-dzial-f51/korekty-krotkoterminowe-i-dlugoterminowe-t14820.html) [FO];
   [best-obd2-scanner](https://best-obd2-scanner.com/fuel-trims-explained/) [SS]). ±5–8 % is also cited
   ([wtryskiwacze-benzynowe.pl (snippet)](https://www.wtryskiwacze-benzynowe.pl/aktualnosci/korekty-paliwowe-po-czyszczeniu-wtryskiwaczy-benzynowych-ustalamy-w-normie) [SS]).
   **If petrol trims are off, fix the engine first**: vacuum leaks, MAF, petrol injectors, lambda, timing. Every source
   agrees on this ([AC guide](https://www.stag.gr/sites/default/files/wytyczne_montazuen_0.pdf),
   [AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf),
   [digitronicgas.ru](https://www.digitronicgas.ru/stati/kak-nastroit-gbo/)) **[MS]**.
3. Check that the gas ECU sensor readings are plausible: petrol pulses, rpm, gas and reducer temperatures, gas pressure,
   MAP, lambda ([AC guide](https://www.stag.gr/sites/default/files/wytyczne_montazuen_0.pdf) [SS]).
4. Consumables: filter age (km), plug age, air filter, last injector service.

### 4.2 What to log

| Channel | From | Why |
|---|---|---|
| rpm, MAP (or load %), vehicle speed | OBD + gas ECU | defines the operating point |
| Petrol Tinj (ms), per bank or cylinder | gas ECU | primary calibration reference |
| Gas Tinj (ms), multiplier actually applied | gas ECU | shows how hard the gas ECU is working |
| STFT, LTFT (per bank) | OBD PIDs 0x06–0x09 | closed-loop error signal |
| Fuel system status (OL/CL) | OBD PID 0x03 | separates trimmable from untrimmable areas |
| Upstream O2 voltage or equivalence ratio | OBD PID 0x14–0x1B / 0x24+ / 0x34+ | lambda behaviour |
| Coolant temperature | OBD PID 0x05 | thermostat and warm-up enrichment |
| Intake air temperature, MAF g/s | OBD | air-path sanity |
| Gas pressure (differential), reducer temperature, gas temperature | gas ECU | reducer, filter and temperature issues |
| Current fuel (petrol/gas) flag | gas ECU | needed to split the data |

(OBD PID numbering from [Wikipedia OBD-II PIDs](https://en.wikipedia.org/wiki/OBD-II_PIDs) [SS].)

### 4.3 Comparing like with like

- Bin data by **rpm x MAP (or rpm x petrol Tinj)** and compare petrol-mode vs gas-mode means *in the same bin*. This is
  exactly what STAG's "B" (petrol) and "G" (gas) maps and the "D" deviation map do **[SS]**
  ([STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf)).
- Only use warm, closed-loop, steady-state samples for trims: coolant ≥80 °C, fuel status CL, small change in rpm and MAP
  over about 1–2 s. Drop the first few seconds after a fuel switch or overrun cut. [CALC]
- Collect both maps on the same road, gear and load. STAG suggests about 4 km in one gear (e.g. 4th), varying load so the
  lambda keeps switching **[SS]** (same manual).
- Quick field test ("switch test"): hold steady throttle, note petrol Tinj and STFT, switch to gas, wait 5–10 s, note
  again, switch back **[MS]** ([AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf),
  [motosprawy.pl](https://motosprawy.pl/2026/01/31/rozjechane-korekty-benzyna-lpg/)).

### 4.4 Acceptance thresholds (consolidated)

| Metric | Good | Acceptable | Act | Source |
|---|---|---|---|---|
| STFT+LTFT on petrol (warm, CL) | within ±5 % | ±10 % | beyond ±10 % (fix engine first) | [w203.pl](https://w203.pl/lpg-nowy-dzial-f51/korekty-krotkoterminowe-i-dlugoterminowe-t14820.html) [FO], [motosprawy.pl](https://motosprawy.pl/2026/01/31/rozjechane-korekty-benzyna-lpg/) [SS] |
| Trim shift gas vs petrol, same point | ≤±3 points | ≤±4–5 points | >4–5 points -> adjust K in that cell | [propan.ru](https://www.propan.ru/forum/viewtopic.php?t=5321) [FO], [AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf) [SS], [STAG 4 % threshold](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf) [SS] |
| Petrol Tinj deviation gas vs petrol | ≤±5 % | ≤±10 % | >10 %, or >0.5–1 ms absolute | [STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf), [AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf) [MS] |
| Gas pressure at idle vs calibration pressure | ±0.05 bar | ±0.1 bar (sensor tolerance) | persistent offset >0.1–0.15 bar | [stag.ua](https://stag.ua/ru/article/kakoe-dolzhno-byt-davlenie-reduktora-gbo-4-pokoleniya-i-kak-ego-pravilno-nastroit/) [SS] + [CALC] |
| Gas pressure dip under WOT | <0.1 bar | ≤0.2 bar | >0.2 bar or hits the min-pressure switch-back | [AC pt IV (snippet)](https://www.ac.com.pl/customer-publications/3936/basic-installation-and-adjustment-guidelines-for-autogas-systems-part-iv/?ph_kontener_glowny_D_start=show) [SS] |
| Idle multiplier (STAG-type ECU) | 1.2–1.8 | – | >1.8 (nozzles too small) or gas Tinj < petrol Tinj (nozzles too big) | [STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf) [SS] |

Note: thresholds in the low-load, short-pulse area (idle and light overrun) should be looser, because Tinj scatter is
natural there ([STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf) [SS]).

### 4.5 Reading the lambda sensor

- **Narrowband (zirconia, switching):** about 0.1–0.2 V lean, about 0.8–0.9 V rich, about 0.45 V at stoichiometry.
  A healthy sensor snaps from <0.175 V to >0.8 V in under 100 ms on a throttle snap. On multiport injection it switches
  about 5–7 times per second at 2500 rpm (TBI 2–3, feedback carburettor about 1) **[SS]**
  ([aa1car](https://www.aa1car.com/library/o2sensor.htm)). Scan tools with slow sampling show fewer, roughly 1–3 Hz
  [CALC]. Ageing appears as slower switching, reduced swing (e.g. never below 0.2 V or above 0.7 V) and a biased mean.
  Contaminants include silicone, oil ash, lead, sulphur and additives ([aa1car](https://www.aa1car.com/library/o2sensor.htm) [SS]).
  Suggested replacement: heated sensors about 60k miles; OBD-II era about 100k miles (about 160,000 km) (same source).
- The narrowband sensor detects λ = 1 whatever the fuel, so on LPG the ECU automatically targets the LPG stoichiometric
  ratio (about 15.5–15.7 by mass). No "AFR 14.7 vs 15.7" compensation is needed in closed loop **[FO]**
  ([propan.ru](https://www.propan.ru/forum/viewtopic.php?t=5321)) + [CALC].
- **Wideband / A/F sensor (common upstream on Toyota and many Japanese cars since about 2000):** it does **not** switch.
  The Toyota scan-tool value is about 3.3 V at stoichiometry, lower when rich and higher when lean. A steady value is
  normal. Standard OBD shows it as equivalence ratio (λ) and current **[MS]**
  ([Underhood Service](https://www.underhoodservice.com/diagnostic-solutions-air-fuel-ratio-sensors/),
  [import-car.com](https://www.import-car.com/fuel-control-diagnostics-datastream/)). With these sensors, judge by
  λ ≈ 1.00 and trims, not by switching frequency. The downstream (post-cat) sensor is still narrowband and should sit
  fairly steady around 0.6–0.7 V when the cat is healthy. [CALC/common practice]
- Two-bank engines: STAG describes identifying which gas injector feeds which bank by adding +25 % to one injector and
  seeing which bank's trims fall **[SS]** ([STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf)).

### 4.6 Spotting thermostat and temperature issues from logs

- A normal engine reaches its regulated temperature (typically about 85–95 °C) within about 10–15 min of mixed driving and
  holds it on the highway. If coolant sags on the highway or in cold weather, or never reaches the thermostat range
  (P0128 "coolant below thermostat regulating temperature"), suspect a stuck-open thermostat **[MS]**
  ([obd-codes P0128](https://www.obd-codes.com/p0128), [O'Reilly P0128](https://www.oreillyauto.com/check-engine-light-code-p0128-coolant-temperature-below-thermostat)).
- In the log: plot coolant temperature vs time and speed. Look for a plateau below about 80 °C, falling temperature at
  steady highway speed, or reducer temperature well below coolant temperature (a heating-circuit problem at the reducer).
  [CALC]
- Check how much time is spent in OL at part load (fuel status = OL while coolant is low). That is the extended warm-up
  enrichment, and it is billed at full price on gas. [CALC]

### 4.7 Per-injector checks

- Put one cylinder at a time on gas and watch petrol Tinj and trims. A cylinder that shifts them has a mis-flowing
  injector. Use per-injector % correction only as a last resort, after mechanical faults are excluded; STAG explicitly
  warns against masking worn parts or unequal hose lengths this way **[SS]**
  ([STAG manual](https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf)).
- Sticking or worn injectors often need replacement rather than cleaning, because the plunger guide wears **[FO]**
  ([aulro forum (snippet)](https://www.aulro.com/afvb/lpg/265187-life-lpg-injectors.html)). Ultrasonic cleaning helps with
  early wear ([gaga.ba](https://gaga.ba/en/savjeti-za-vozace/najcesci-problemi-auto-plin) [SS]).

### 4.8 Decision flow (summary)

1. Verify that the consumption rise is real: ≥3 fills, like-for-like season and route, fill valve behaving normally.
2. Look for leaks (smell, soap test).
3. Check the petrol baseline: trims, lambda, coolant temperature curve, misfires. Fix the engine first.
4. Check gas pressure stability (idle, cruise, WOT) and filter age. Replace filters if due.
5. Run the petrol-vs-gas comparison binned by rpm x MAP. If a whole-curve offset shows, look for a pressure change or
   general injector wear. If the offset is load-specific, correct multiplier points. If it is temperature-specific,
   correct the temperature map.
6. Check open-loop areas separately (WOT, warm-up) with a wideband or the OEM A/F sensor's λ reading if available.
7. Per-injector test. Then service or replace injectors, rebuild the reducer, recalibrate (auto-calibration plus road map).

---

## 5. Mechanical and engine health on LPG

### 5.1 Valve seat recession and valve clearance

- Mechanism: gaseous fuel gives no liquid cooling or lubrication at the seat, and burns somewhat hotter and slower,
  especially if lean. Seats and faces wear, the valve sinks and clearance closes up. With zero clearance the valve does
  not seal and the seat or head burns **[MS]**
  ([Oleksyk, Kraków Univ. of Tech.](https://repozytorium.biblos.pk.edu.pl/redo/resources/31271/file/suwFiles/OleksykP_WplywZasilania.pdf),
  [mylpg.eu](https://www.mylpg.eu/useful/valve-lubrication), [eng-tips](https://www.eng-tips.com/threads/valve-seat-recession-on-lpg.357495/)).
- Engines at risk: those **without hydraulic lash adjusters**, i.e. shim-over-bucket or screw adjusters, common in Japanese
  engines (Toyota, Honda, Mazda, Mitsubishi) and some European ones (Fiat FIRE, Ford Duratec 1.8, Subaru) **[MS]**
  ([Oleksyk](https://repozytorium.biblos.pk.edu.pl/redo/resources/31271/file/suwFiles/OleksykP_WplywZasilania.pdf),
  [auto-swiat.pl](https://www.auto-swiat.pl/porady/eksploatacja/regulacja-zaworow-to-szczegolnie-wazne-w-silnikach-z-lpg-kiedy-trzeba-to-zrobic-ile/8th95yq)).
- Measured data (Oleksyk, 10 cars, clearances measured every 10–15k km) **[SS]**:
  - Toyota Corolla 1.4 VVT-i: clearance fell by **0.07–0.10 mm (intake and exhaust) in 15,000 km** on LPG without
    lubricant. Spec is 0.25 mm intake / 0.35 mm exhaust nominal and the maker's check interval is 100k km. The authors
    conclude such engines need adjustment about every **30,000 km** on LPG without a lubricator.
  - Subaru Impreza STI: -0.03 to -0.10 mm in 15k km. Honda Accord 1.8: -0.10 to -0.20 mm after 50k km. Toyota RAV4 2.0:
    -0.05/-0.07 mm in 15k km. With a Flash-Lube doser the loss slowed to about 0.01–0.03 mm per 15k km.
  - Tip from the study: set clearances at the **upper** end of the tolerance when converting. Lubricator dosing (vacuum
    driven) falls off during sustained high-load motorway driving.
  - A partly blocked gas nozzle on one cylinder caused a -0.10/-0.14 mm clearance loss there. Local lean mixture is the
    key accelerator.
- Recommended check intervals on LPG: every 20–30k km or yearly **[MS]**
  ([auto-swiat.pl](https://www.auto-swiat.pl/porady/eksploatacja/regulacja-zaworow-to-szczegolnie-wazne-w-silnikach-z-lpg-kiedy-trzeba-to-zrobic-ile/8th95yq),
  [search summary, dobrymechanik/otomoto](https://dobrymechanik.pl/uslugi/regulacja-luzow-zaworowych/)).
- Symptoms of tight valves: hard (hot) start, rough or erratic idle, power loss, misfire, **increased consumption**,
  stalling ([Oleksyk](https://repozytorium.biblos.pk.edu.pl/redo/resources/31271/file/suwFiles/OleksykP_WplywZasilania.pdf),
  [auto-swiat.pl](https://www.auto-swiat.pl/porady/eksploatacja/regulacja-zaworow-to-szczegolnie-wazne-w-silnikach-z-lpg-kiedy-trzeba-to-zrobic-ile/8th95yq)) **[MS]**.
- Lubricators (Flashlube, ERC, JLM, Valve Saver): about 1 mL of fluid per 1 L of fuel **[SS]**
  ([mylpg.eu](https://www.mylpg.eu/useful/valve-lubrication)). Some gas ECUs (AEB "split fuel") can inject 0–25 % petrol at
  high rpm to protect seats **[SS]** ([AEB manual](https://lpgautosupplies.co.uk/wp-content/uploads/2021/03/AEB-SOFTWARE-MANUAL.pdf)).
- Power impact once recession is advanced: 3 mm recession about -20 % power, 4 mm about -30 %
  ([mylpg.eu](https://www.mylpg.eu/useful/valve-lubrication) [SS, unverified]).

### 5.2 Spark plugs and ignition

- LPG/air mixture needs a higher breakdown voltage. Common advice: reduce the gap by about 0.1–0.2 mm from the petrol
  spec, typically to 0.7–0.9 mm. Do not go below about 0.7 mm **[FO/MS]**
  ([search summary, lpgserwisbialystok](https://lpgserwisbialystok.pl/wymiana-swiec-lpg-co-ile-km-uniknij-bledow-oszczedz-silnik),
  [alfaholicy forum](https://www.forum.alfaholicy.org/lpg_zone/116412-przerwa_miedzy_elektrodami_swiecy_przy_zasilaniu_lpg.html)).
  One user lost two coils running old, wide-gapped plugs on LPG. Healthy ignition systems usually cope with stock gaps.
- Plug interval on LPG is often quoted as about half the petrol interval
  ([search summary, lpgserwisbialystok](https://lpgserwisbialystok.pl/wymiana-swiec-lpg-co-ile-km-uniknij-bledow-oszczedz-silnik) [FO]).
  Replace plugs whenever a coil is replaced (same source).
- Misfire on gas under load but not on petrol points first to ignition (plugs, coils, boots) or to one lean cylinder.
  [CALC]

### 5.3 Compression and other checks

- Compression: the spread between cylinders should be ≤10 % of the specified value (roughly ≤0.7–1.0 bar). A cylinder
  that is low, or a general decline, points to valves, rings or head gasket **[MS]**
  ([search summary, Pico/BlueDriver](https://www.picoauto.com/library/picoscope-automotive-training/training/compression-testing-by-pico-technology),
  [gaga.ba compression](https://gaga.ba/en/savjeti-za-vozace/test-kompresije-motora-simptomi-pada-vrijednosti)). Low
  compression is also listed as a cause of high gas use ([gepard](https://gepard.org.ua/bolshoj-rashod-gaza-gbo-4-pokolenija/) [SS]).
- Timing chain or belt a tooth off gave LTFT +18 % on petrol and +24 % on LPG in one Kia case **[FO]**
  ([mojaceed forum](https://forum.mojaceed.pl/viewtopic.php?f=36&t=11655)).

### 5.4 Service intervals for LPG components (consolidated)

| Item | Interval quoted | Source / confidence |
|---|---|---|
| Liquid + vapour filters | 10–15k km (common); 7–10k km (RU/UA, poor gas); up to 20–30k km / 12–18 months (some makers) | [gaga.ba](https://gaga.ba/en/savjeti-za-vozace/najcesci-problemi-auto-plin), [nagaz24 (snippet)](https://nagaz24.pl/objawy-zapchania-filtrow-w-instalacji-lpg-kiedy-wymienic-elementy-filtrujace/), [omvlgbo (snippet)](https://omvlgbo.ru/faq/filtr-gbo/) [MS] |
| Prins system service | every 25,000 km or 2 years | [Prins](https://www.prinsautogas.com/en/service-your-vsi-system) [SS] |
| Gas injector cleaning | about every 20–30k km (BRC); 30–50k km; 60k km | [BRC](https://brc.it/en/dettaglio-news/impianto-gpl-brc-revisione-e-manutenzione/70554), [RU search summary](https://mirgbo.ru/vse-o-gbo/bolshoy-rashod-gaza-gbo-4-pokoleniya-prichiny/) [MS] |
| Reducer rebuild (diaphragm kit) | 80–120k km | [gaga.ba](https://gaga.ba/en/savjeti-za-vozace/najcesci-problemi-auto-plin) [SS] |
| Reducer condensate / heavy-fraction drain (reducers with drain plug) | quoted as often as every 1000 km (RU), otherwise at filter change | [lpgtech.ua (snippet)](https://lpgtech.ua/slivayte-kondensat) [FO] |
| Gas ECU diagnostic check | every 10k km | [gbomotor.ru](https://gbomotor.ru/diagnostika-i-remont/bolshoy-rashod-gaza-na-mashine-s-gbo) [SS] |
| Valve clearance (non-hydraulic engines) | 20–30k km or yearly | [auto-swiat.pl](https://www.auto-swiat.pl/porady/eksploatacja/regulacja-zaworow-to-szczegolnie-wazne-w-silnikach-z-lpg-kiedy-trzeba-to-zrobic-ile/8th95yq), [Oleksyk](https://repozytorium.biblos.pk.edu.pl/redo/resources/31271/file/suwFiles/OleksykP_WplywZasilania.pdf) [MS] |
| Spark plugs | about half the petrol interval | [search summary](https://lpgserwisbialystok.pl/wymiana-swiec-lpg-co-ile-km-uniknij-bledow-oszczedz-silnik) [FO] |
| LPG tank | replace after 10 years (Italy/EU practice, national rules vary) | [BRC](https://brc.it/en/dettaglio-news/impianto-gpl-brc-revisione-e-manutenzione/70554) [SS] |
| Connectors / wiring | corrosion typical after 5–7+ years | [gaga.ba](https://gaga.ba/en/savjeti-za-vozace/najcesci-problemi-auto-plin) [SS] |

---

## 6. Practical checklist for the "12 -> 14 L/100 km" case

1. Confirm the rise over ≥3 fills and compare with the same season last year. Winter alone can explain +5–15 %
   ([fueleconomy.gov](https://www.fueleconomy.gov/feg/coldweather.shtml),
   [digitronicgas.ru](https://www.digitronicgas.ru/stati/pereraskhod-gaza-na-avto/)).
2. Log a 30–60 min drive including a cold start, city, steady 50/70/90/110 km/h and 2–3 hard accelerations. Record the
   channels in 4.2.
3. Coolant curve: reaches and holds about 85–95 °C? If not, suspect the thermostat.
4. Gas pressure: idle value vs calibration pressure, dips under WOT ≤0.2 bar? Filters due?
5. Binned petrol vs gas comparison: trims and Tinj within the thresholds in 4.4?
   - Uniformly rich on gas (negative trim shift, shorter Tinj on gas): pressure crept up, injector nozzle wear,
     multiplier too high.
   - Lean on gas at high load (positive shift, pressure dips): filter, reducer capacity, slow injectors.
   - Trims off on petrol too: engine or air-path problem (vacuum leak, MAF, lambda, petrol injectors, valves, timing).
6. Open-loop time share and WOT behaviour: check whether the installer enriched open-loop areas.
7. Mechanical: valve clearance if the engine has shims or screw adjusters and has not been checked for >30k km on LPG;
   plugs; compression.

---

## Open questions / conflicting advice

- **How big is the "normal" LPG penalty?** Sources range from +10 % (RU sites) through +15 % (Van Meenen) and +10–20 %
  (PL/UA) to +10–30 % (gbomotor), while the pure energy-per-litre ratio suggests +27–33 %. The gap is usually explained
  by petrol warm-up not being counted and by efficiency gains, but I found no controlled study that splits these. Treat
  +15–25 % vs same-car petrol as a working band, not a spec.
- **Energy-per-litre figures differ:** Wikipedia gives 25.5 (propane) / 28.7 (butane) MJ/L. Density x LHV at 15 °C gives
  about 23.5 / 26.6 MJ/L [CALC]. The difference is probably HHV vs LHV and reference temperature. Ratios, not absolute
  values, matter for the skill.
- **Winter penalty mechanism:** composition shift alone is about 2–3 % [CALC], but denser cold fuel at the pump gives a
  bonus in the other direction. Whether Polish or Ukrainian autogas dispensers temperature-compensate volume was not
  confirmed.
- **Acceptable trim differences:** ±3 % (propan.ru), 3–4 points (AEB), 4 % adaptation threshold (STAG), ±10 % Tinj
  deviation (STAG), ±25 % "normal range" (a drive2 post, which is far too loose). The skill should use ≤±5 % as the target
  and >±10 % as clearly wrong, and be more lenient at idle and short pulses.
- **Reducer pressure:** 1.0–1.2 bar (STAG UA, PL forums) vs about 1.5 bar (AC guide) vs 1.2–1.4 bar (Zavoli presets). It is
  model- and nozzle-dependent. The key point is stability relative to the calibration pressure, not the absolute value.
  Allowed dip under load: 0.2 bar (AC, via snippet) vs "up to 30 %" (forum).
- **Correct gas and reducer temperatures:** figures quoted include 35–55 °C optimal (Digitronic), about 90 °C reducer with a
  good thermostat (forum), gas >28–30 °C for smooth running (forum), and reducer switch-over at 20–45 °C (AEB). These mix
  gas-outlet and coolant-side temperatures. The sensor location differs by kit.
- **Filter intervals** range from 7k km (RU) to 30k km (some makers). Gas quality varies a lot by market.
- **Valve recession risk:** strong measured evidence for some Toyota, Honda and Subaru engines (Oleksyk), but several
  sources say modern hardened seats plus a correct mixture make it a non-issue. Engine-specific data is needed. The
  mylpg.eu figure of 900–950 °C vs 600 °C for LPG vs petrol valve temperatures looks unreliable.
- **Open-loop enrichment on gas:** some installers deliberately enrich for valve protection, which costs fuel. Others tune
  to match petrol exactly. There is no standard. It needs a wideband or OEM A/F-sensor λ reading to verify.
- **Spark plug gap reduction** is contested: helpful on worn ignition systems, unnecessary on healthy modern coil-on-plug
  systems.
- **Shared LTFT between fuels:** well known in forums, but I found no manufacturer documentation of how specific petrol
  ECUs (e.g. Toyota) store adaptation and how fast they relearn after switching fuel.
- **Some sources were only reachable as search snippets** (lpg-forum.pl, lpgserwisbialystok.pl, rangerovers.net,
  ac.com.pl part IV). Their claims are marked but should be re-verified if they become central.
