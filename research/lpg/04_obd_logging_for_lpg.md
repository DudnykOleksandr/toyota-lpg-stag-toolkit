# 04 — OBD-II data logging for LPG (autogas) calibration

Scope: using a generic OBD-II link (ELM327 adapters, Torque, OBD Auto Doctor, python-OBD, or the
reader built into AC STAG QBOX PLUS controllers) to judge and correct a sequential LPG system
through the petrol ECU's own fuel trims. This file feeds a Claude skill that reads ELM327 logs
and turns them into STAG calibration changes.

Confidence marks: **[H]** several independent or primary sources agree; **[M]** one good source,
or a derivation from primary facts; **[L]** forum folklore, a single anecdote, or my own inference.
Every claim has its source URL inline. Research date: 2026-09-26.

---

## 0. TL;DR for the skill

- The petrol ECU cannot tell which fuel is burning. When the engine runs on LPG it keeps closed-loop control on the upstream lambda sensor and moves STFT/LTFT to hide any LPG mixture error. **Differences in trims between petrol and LPG at the same operating point
  are the calibration error signal.** [H] — https://www.drive2.ru/c/471565419941986616/ , https://forum.autokacik.pl/topic/359066-korekty-obd2-ltft-stft-fxt/ , https://motosprawy.pl/2026/01/31/rozjechane-korekty-benzyna-lpg/
- Compare **total trim (STFT+LTFT)** in matching cells of RPM × load (or STAG's petrol injection-time axis). Only use samples where fuel system status = closed loop (PID 03 = 2), the engine is warm, and conditions are steady. [H] (see §1, §2)
- Rule of thumb from tuners: aim for gas-vs-petrol trim difference within about ±3–5 %; a lasting positive difference means LPG is lean, so raise the multiplier/correction there; negative means rich, so lower it. [M/L] — https://www.drive2.ru/c/471565419941986616/ ,
  https://motorstate.com.ua/info/sverhtochnaja-optimizacija-gbo-s-ispolzovaniem-elm327.-toplivnaja-korrekcija
- A cheap ELM327 over classic Bluetooth gives roughly **5–25 single-PID responses per second in total** on CAN, fewer on K-line. Log only a few PIDs, and on CAN, where the adapter allows it, pack up to 6 PIDs into one request. [M] (see §3)

---

## 1. Methodology used by LPG tuners

### 1.1 Core principle
- The goal of LPG tuning is that fuel trims look the same on gas as on petrol. Russian-language community consensus says trims on gas should stay within ±3–5 % of petrol, "no more". [M] — https://www.drive2.ru/c/471565419941986616/
- Same principle on Polish forums: watch how trims behave on petrol, then set up LPG so they behave the same way on gas. [M] — https://forum.autokacik.pl/topic/359066-korekty-obd2-ltft-stft-fxt/
- An older equivalent method (from before OBD readers were common) says the same thing using the petrol injection time instead of trims. If the petrol ECU's injection time *drops* after switching to LPG, the gas mixture is rich, so lower the multiplier. If it *rises*, the
  mixture is lean, so raise the multiplier. [M] — https://a6klub.pl/lpg/podstawy-montazu-i-strojenia-lpg-t56145.html The STAG input pulse is roughly the base petrol-map time adjusted by the sum of the ECU's
  STFT+LTFT, so the two methods are equivalent. [M] — summary of elektroda.pl thread in search results, https://www.elektroda.pl/rtvforum/topic2734347.html (page itself timed out)
- STAG's own "D" (deviation) map applies this idea to injection times. A positive deviation means the petrol pulse on gas is shorter than on the petrol reference map, so gas is
  **rich**. Negative means lean. [H, primary] — STAG-4 QBOX manual v1.6, §2.18, https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf

### 1.2 Practical procedure (synthesis)
1. **Fix petrol first.** If the engine runs badly on petrol (fuel pressure, dirty injectors, vacuum leaks, TPS), gas cannot be tuned. In one documented case, moving the MAP sensor and then resetting adaptations took petrol LTFT from +16 % down to +4 %.
   [M] — https://www.drive2.ru/l/8553075/
2. **Optionally reset adaptations** (clear DTCs or disconnect the battery) so both fuels start from a known LTFT. The Ukrainian guide recommends this as step 1 and then a drive of at least
   50 km. [M] — https://motorstate.com.ua/info/sverhtochnaja-optimizacija-gbo-s-ispolzovaniem-elm327.-toplivnaja-korrekcija Newer Toyotas may need Techstream to force some relearns. [L] — https://www.toyotanation.com/threads/long-term-fuel-trim-high-at-idle-p0171.1741483/
3. **Auto-calibrate STAG at idle** once the lambda sensor is active, with no A/C, lights or steering load. The resulting multiplier at idle should be **1.2–1.8**. Above 1.8, gas pulses at high load can overlap; below 1.0 (gas pulse shorter than petrol) the injector nozzles are
   too large. [H, primary] — STAG manual §3.1 (URL above); https://a6klub.pl/lpg/podstawy-montazu-i-strojenia-lpg-t56145.html
4. **Log a petrol baseline** over the whole RPM × load envelope you drive: steady throttle (under about 80 % pedal), 4th/5th gear, about 1000–5000 rpm. [M] — https://motorstate.com.ua/info/sverhtochnaja-optimizacija-gbo-s-ispolzovaniem-elm327.-toplivnaja-korrekcija
5. **Log the same envelope on LPG**, same route and similar temperatures.
6. **Bin both logs** into cells (RPM × MAP/load, or RPM × petrol injection time to match STAG axes). Compute mean total trim per cell and fuel. Discard cells with few samples.
7. **Correct only where the difference is consistent.** If deviations are spread evenly across the map, shift the whole multiplier curve. If they are local, edit only those rows/columns (RPM correction map or MAP correction map).
   [M] — https://motorstate.com.ua/info/sverhtochnaja-optimizacija-gbo-s-ispolzovaniem-elm327.-toplivnaja-korrekcija
8. **Iterate.** A first pass does not need to be precise. What matters is that no RPM/load region runs strongly positive or negative; trims should oscillate around zero. Brief spikes of about ±15 % after lift-off or other transients are acceptable.
   [M] — search summary of civicklub.pl STAG 4+ guide, http://civicklub.pl/forum/archive/index.php/t-239093.html (403 on fetch)

### 1.3 How long LTFT takes, and LTFT being shared between fuels
- LTFT is adaptive and stored **per operating region** (cells), not as one number, so it only changes in cells you actually drive through. [M] — https://www.drive2.ru/c/483792813876576598/
  A GM-type patent describes 8 closed-loop correction cells per bank indexed by airflow, with long-term multipliers updated on the order of every ~10 s. It illustrates the mechanism but is not Toyota-specific. [L] — search summary of
  https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/7673621
- One owner reports LTFT drifting over **~15 min of warm idle**. Idle cells adapt slowly. [L] — https://www.toyotanation.com/threads/long-term-fuel-trim-high-at-idle-p0171.1741483/
- The ECU keeps **one set of LTFT cells for both fuels**. A badly tuned LPG system therefore drags petrol trims too, and petrol runs worse after long gas driving. [M] — https://a6klub.pl/lpg/podstawy-montazu-i-strojenia-lpg-t56145.html ;
  https://rangerovers.pub/topic/731-advanced-lpg-mapping ("stable trims that don't wander off after LPG running")
- Practical consequences (inference, [M]):
  - Right after a fuel switch, the error shows first in **STFT**, then slowly moves into LTFT. Comparing only LTFT a minute after switching underestimates the error. Always use
    **STFT+LTFT** and let each cell settle; the tuner in the drive2 case judged on minutes of steady driving, not seconds. — https://www.drive2.ru/l/8553075/
  - If the petrol baseline was logged right after long gas driving, the petrol LTFT may already carry LPG error. Record petrol after a petrol-only warm-up drive, or right after an adaptation reset.
  - The gas-vs-petrol **difference** in total trim is what matters, not the absolute value. Petrol LTFT of +4 % is fine as long as gas is also about +4 % in that cell.
- STAG's OBD autoadaptation is built on the same logic. It first **records a map of target corrections while driving on petrol** (RPM × MAP axes), then on gas corrects the gas dose only while the gas-side OBD correction lies outside target ± a switch-on threshold (example: 4 %
  around a 0 % target). An "LTFT max" parameter caps how far it can correct. A "Reversed STFT" option exists for ECUs whose trim sign is inverted, and a wrong setting there makes the car undriveable. [H, primary] — STAG manual §2.22.2 (URL above)
- STAG locks its own "multiplier adjustment" function while ISA3/OBD autoadaptation is active. [H, primary] — STAG manual §2.9.1

### 1.4 Turning a trim difference into a map change
- Direction [H]: gas trim more positive than petrol means LPG is lean, so enrich (raise the multiplier or correction in that region). Gas trim more negative means rich, so lean it out.
  — https://motorstate.com.ua/info/sverhtochnaja-optimizacija-gbo-s-ispolzovaniem-elm327.-toplivnaja-korrekcija ; https://forum.autokacik.pl/topic/359066-korekty-obd2-ltft-stft-fxt/
- Size [M, derived]: the ECU multiplies its base pulse by (1 + STFT + LTFT), and STAG multiplies that pulse by its multiplier. For gas to need the same correction as petrol, scale the STAG multiplier in that cell by:
  `k = (1 + TT_gas) / (1 + TT_petrol)`, where TT = (STFT+LTFT)/100. Example: petrol TT = +1 %, gas TT = −6 % gives k = 0.94/1.01 ≈ 0.931, so reduce by about 7 %. For small values, "reduce by the difference in percent" is close enough.
  Caveats: (a) after the fix, the petrol pulse on gas gets longer by the same ratio, so the operating point moves along STAG's injection-time axis. Edit a band of cells, not a single point. (b) Gas injectors have a fixed opening delay (STAG has a separate constant "injector
  opening correction"), so a percentage fix is less exact for short pulses (idle, low load). — STAG manual §2.x gas injector settings; derivation mine.
- Where to put the change on STAG [H, primary, STAG manual]:
  - **Multiplier map**: 2-D curve, multiplier vs petrol injection time [ms]. Use it when the error depends on load and is similar at all RPM.
  - **RPM correction map**: plane of petrol injection time × RPM, enrich/lean per area (Ctrl+↑/↓, Shift for 10× step). Use it when the error depends on RPM.
  - **MAP correction map (autoadaptation map)**: RPM × MAP (manifold pressure). OBD/ISA3 adaptation writes here. With adaptation off, it can hold manual corrections. Regions can be locked from adaptation.
  - Per-cylinder % correction and absolute injector-opening correction: last resort only, for mechanical rail differences.
  - Gas temperature, reducer temperature and gas pressure correction maps also exist. Leave them alone for trim-based work unless the error tracks those temperatures or pressures.
- The controller applies RPM, temperature, pressure and OBD corrections **together** at a given point. Avoid "fixing" the same error in two maps. [M] — search summary of lpg-forum.pl thread https://lpg-forum.pl/stag-obd-mapa-korekt-t67902.html (server refused fetch)

### 1.5 Pitfalls
- **Open loop**: in power enrichment, warm-up, or on sensor faults the ECU ignores lambda and trims are frozen or meaningless. Filter on PID 03 = 2. [H] — https://en.wikipedia.org/wiki/OBD-II_PIDs ; https://www.hptuners.eu/help/vcm_editor_parameters_gm_eng_fuel_loop.htm
  Note that STAG's own "adaptation in OL" needs a lambda signal or lambda PID. [H] — STAG manual §2.22
- **Fuel cut / overrun**: releasing the throttle resets or distorts lambda correction and corrupts cell averages. Hold the throttle steady or drop samples in decel (PID 03 = 4, or MAP/load very low with RPM above idle). [M] —
  https://motorstate.com.ua/info/sverhtochnaja-optimizacija-gbo-s-ispolzovaniem-elm327.-toplivnaja-korrekcija ; fuel-cut lean spikes visible on Toyota AF data: https://www.motor.com/magazinepdfs/022015_5.pdf
- **Transients**: tip-in/tip-out makes trims spike. Tuners accept short ±15 % spikes. Use a steady-state filter (e.g. |dRPM/dt| and |dMAP/dt| small for about 2 s). [M] — civicklub summary above
- **Cold engine**: trims on a cold engine differ (e.g. −24 % STFT at idle early on, heading to zero when warm). STAG ISA3 recommends acquiring reference maps warm and enforces a minimum temperature. Toyota monitors trims only with ECT above 75 °C. [H] —
  https://www.drive2.ru/l/8553075/ ; STAG manual §2.22.1 ; https://www.trav4.net/system_too-423.html
- **Idle / short pulses**: STAG warns not to chase deviations to zero at low load and short petrol pulses, because the ECU's pulse-to-pulse spread is natural there. ISA3's minimum threshold is 4 %. [H, primary] — STAG manual §2.18, §2.22.1
- **Extra (asynchronous) injections**: some ECUs add extra petrol pulses. STAG has an "extra-injection off threshold" (ignore pulses shorter than X) and "intelligent extra-injection
  handling" (lean such pulses). Mishandled extra injections cause gas-only rich/lean spots. [H, primary] — STAG manual §2.x
- **High load**: if gas pulses approach the available window (overlapping injections), STAG reports "too long injection time". Set "max load on gas" to about 95 % if petrol injectors can go fully open. [H, primary] — STAG manual §2.x, §3.1
- **Enriching the top end upsets cruise trims**: raising LPG by a large % for WOT lean spots throws off normal-driving trims. Keep load-dependent fixes local. [M] — https://rangerovers.pub/topic/731-advanced-lpg-mapping
- **Vacuum leaks / MAF faults** show as positive trims that shrink with RPM, on both fuels. Do not blame LPG for them. [H] — search summaries incl. https://www.motor.com/magazine-summary/lean-on-me/ and https://carcodefinder.com/guides/understanding-fuel-trim/
- **Two banks** (V engines): STAG must be told which gas injector feeds which bank, or adaptation goes wrong. Test by adding +25 % to one injector and watching which bank's trims drop. [H, primary] — STAG manual §2.21
- **LPG composition and temperature** (propane/butane ratio changes seasonally) change density and energy per volume, so a map tuned in summer may drift in winter. [M] — https://www.sciencedirect.com/science/article/abs/pii/S0360544221025457 (abstract/search summary)

---

## 2. Interpreting the data

### 2.1 Fuel system status (PID 03)
Two bytes, A = fuel system 1, B = fuel system 2 (if present), same coding for both. [H] —
https://en.wikipedia.org/wiki/OBD-II_PIDs

| Value | Meaning | Use for calibration? |
|---|---|---|
| 0 | engine off | no |
| 1 (0x01) | open loop, not warm enough yet | no |
| 2 (0x02) | closed loop, O2 feedback in use | **yes** |
| 4 (0x04) | open loop due to load (enrichment) or decel fuel cut | no (but useful for PE-region checks with a wideband) |
| 8 (0x08) | open loop due to system failure | no |
| 16 (0x10) | closed loop, but a feedback sensor has a fault | treat with suspicion |

### 2.2 Healthy trim ranges (generic)
- STFT should move around 0; LTFT in a healthy engine stays within ±10 %. [H] — https://motosprawy.pl/2026/01/31/rozjechane-korekty-benzyna-lpg/ ; https://iamcarhacker.com/learn/obd2-scanner/basics/fuel-trims-explained-stft-ltft/
- Total (STFT+LTFT) beyond ±10 % in normal driving is worth investigating. [H] — https://carcodefinder.com/guides/understanding-fuel-trim/ (search summary)
- Tuner-facing scale used in this project: **±5 % good, ±5–10 % watch, ±10–20 % fix, beyond ±20–25 % fault or DTC territory**. [M] — composite of https://motorstate.com.ua/info/sverhtochnaja-optimizacija-gbo-s-ispolzovaniem-elm327.-toplivnaja-korrekcija
  (±5 % stoichiometric, ±25 % CEL), https://www.drive2.ru/c/483792813876576598/ (±25 %), https://a6klub.pl/lpg/podstawy-montazu-i-strojenia-lpg-t56145.html (LTFT ±5 %, STFT ±10 %)
- In one STAG-4 case, the tuned result was LTFT +1–2.5 % in steady highway driving and about +4.5 % under acceleration. Corrections creeping above 5–6 % under load were treated as "gas too lean, enrich". [L, single case] — https://www.drive2.ru/l/8553075/

### 2.3 Toyota P0171 / P0172 thresholds
- The ECM compares **total (short + long) fuel trim**. Monitoring requires ECT above 75 °C and stable A/F feedback, with 2-trip detection logic. [H] — https://www.tovenza.com/system_too_lean_bank_1_p0171_p0172_-1007.html ; https://www.trav4.net/system_too-423.html
- The threshold varies by model and year: about **±35 %** average learned trim on 2AZ RAV4 (the "normal" statement there says within 20 %), ±35 % on 2ZR-FXE Prius (expressed as
  1.35/0.65), **+33/−30 %** on some Prius, and **±38 %** in a 2002 manual. [H for "about ±30–38 %"; exact value per engine M] — https://www.trav4.net/system_too-423.html ; https://manuals.plus/m/8cf61b552016a43f0a5664ad32c1669eb3b79d58c0628729ffe53203eee928df ;
  https://caphector.com/atoyota/techinfo.toyota.com/ileaf/02toyrm/02toypdf/02rmsour/2002/02echorm/di/eg/cip0171.pdf (search summary)
- Toyota's confirmation drive pattern: 60–120 km/h, 1400–3200 rpm, 3–5 min, accessories off. This is also a sensible steady-state band for LPG trim comparison. [M] — https://www.trav4.net/system_too-423.html
- Conclusion for the skill: a Toyota on LPG sets P0171/P0172 only when the LPG error is gross (about 30 %+). Staying clear of DTCs does **not** mean the calibration is good.

### 2.4 Narrowband (switching) sensors, and what "lazy" means
- Rich reads up to about 0.9 V, lean about 0.1–0.2 V, stoichiometric crossing about 0.45 V. [H] — https://www.aa1car.com/library/o2sensor.htm
- Healthy closed-loop switching at 2500 rpm on multiport injection is about **5–7 times per second** (TBI 2–3, feedback carburettor about 1). [M] — https://www.aa1car.com/library/o2sensor.htm
- **Lazy** sensor: switches slower than new. A common test criterion is a snap-throttle jump from below 175 mV to above 800 mV in **under 100 ms**. A badly degraded sensor may cycle only every 2–3 s. [M] — https://www.aa1car.com/library/o2sensor.htm ; search summary incl.
  https://bobistheoilguy.com/forums/threads/lazy-oxygen-sensors.207145/
- Heated sensors reach operating temperature about 20–60 s after start. [M] — aa1car (above)
- **ELM327 cannot resolve switching**: at 5–7 Hz switching you would need well over 14 Hz on the O2 PID alone (Nyquist). A Bluetooth ELM327 polling 8 PIDs gets maybe 2–3 Hz per PID. Switching counts from such logs are unreliable. Use mean voltage/trim, or a scope. [M, derived]

### 2.5 Wideband / Toyota A/F sensor PIDs
- PIDs 0x24–0x2B report λ (bytes A,B) plus a voltage (C,D). PIDs 0x34–0x3B report λ plus a current in mA (C,D). [H] — https://en.wikipedia.org/wiki/OBD-II_PIDs
- On Toyota, the ECU biases the A/F sensor at about 3.3 V. Generic scan tools often show that voltage **divided by 5** (0.66 V ≈ stoich). Lean reads higher and rich reads lower, the opposite of narrowband behaviour. Prefer the λ value. [M] — https://alflash.com.ua/aevsafs.htm ;
  https://www.motor.com/magazinepdfs/022015_5.pdf ; https://www.underhoodservice.com/emissions-update-understanding-how-wide-ratio-air-fuel-wraf-sensors-work/ (search summary)
- On A/F-sensor cars, PID 0x14 (B1S1 narrowband) is usually absent. Downstream **PID 0x15 (B1S2)** remains a classic narrowband sensor. In its STFT byte, 0xFF means "not used for trim". [H] — https://en.wikipedia.org/wiki/OBD-II_PIDs
- STAG's built-in OBD reader lists 96 standard parameters, shows up to 6 at once, and AC explicitly suggests fuel loop status, STFT/LTFT and wideband results as the calibration set. [H, primary] — STAG manual §2.20

### 2.6 Commanded equivalence ratio (PID 0x44)
- SAE definition as quoted by MOTOR: with conventional O2 sensors it reads the commanded open-loop value in open loop and **1.0 in closed loop**. With wide-range sensors it shows the commanded value in both modes. Commanded AFR = stoich × value (e.g. 14.64 × 0.95 = 13.9),
  so in practice it behaves as commanded λ. [H] — https://www.motor.com/magazinepdfs/022015_5.pdf ; https://en.wikipedia.org/wiki/OBD-II_PIDs
- No bank-specific version exists, and on a 2010 Tacoma it did not track fuel-cut events well. Treat it as a hint, not a measurement. [M] — MOTOR (above)
- Use: value < 1.0 together with PID 03 = 4 marks power enrichment. Exclude these samples from trim comparison, and check them with a wideband instead.

---

## 3. ELM327 practicalities

### 3.1 Real sampling rates
- Typical cheap Bluetooth clone on CAN: about **20–25 PIDs/s total** (Torque admin figure). ISO 9141 / KWP cars (e.g. Toyota Aygo, Citroën C1) about **5 PIDs/s**, up to about 7 with
  Torque's "faster communication". [M] — https://torque-bhp.com/community/main-forum/elm-327-bluetooth-adapter-speed-issues/ ; https://torque-bhp.com/community/main-forum/elm-327-read-speed/
- Bad clones can drop to 2.5 PIDs/s average. [M] — same Torque thread
- STN-based adapters (OBDLink) are much faster. Marketing claims are 3–10× faster, and a forum figure is about 70 PIDs/s for OBDLink MX. [L] — search summaries, https://mazdas247.com/forum/threads/obdii-and-data-logging-for-30-torque-and-elm327-bluetooth-adapter.123837473/
- On pre-CAN cars the ECU itself is the bottleneck, and adapter upgrades help little. [M] — https://torque-bhp.com/community/main-forum/elm-327-read-speed/
- Before the April 2002 J1979 revision, J1850 requests faster than every 100 ms were forbidden, and older ECUs may not tolerate fast polling. [H, primary] — ELM327 datasheet, https://www.elmelectronics.com/wp-content/uploads/2016/07//ELM327DS.pdf

### 3.2 Multi-PID requests (CAN only)
- J1979 allows **up to 6 PIDs in one mode-01 request, only on CAN (ISO 15765-4)**. The ECU returns one message with PID/data pairs, not necessarily in request order. Requests with more than 6 PIDs are not answered. [H, primary] — ELM327 datasheet "Multiple PID Requests";
  https://github.com/Ircama/ELM327-emulator/issues/38
- Request size: 1 mode byte + 6 PIDs = 7 bytes, which is exactly one CAN single frame (7 data bytes after the PCI byte). [H] — ELM datasheet; https://en.wikipedia.org/wiki/OBD-II_PIDs (CAN frame layout)
- The **reply** is often multi-frame. Example from the datasheet: `01 04 05 0B 0C` gives a 10-byte answer split into lines `0:` and `1:` with a length header `00A`. The parser must know each PID's data length (e.g. 0C = 2 bytes, 04 = 1 byte). [H, primary] — ELM datasheet
- Tools: python-OBD does not do multi-PID natively (open issue #31). Torque sends single PIDs such as `010C1`. [M] — https://github.com/brendan-w/python-OBD/issues/31 ; https://torque-bhp.com/community/main-forum/can-multi-pid-request/
- Clone caveat: some fakes have tiny buffers or mishandle multi-frame data, so multi-PID may fail. Test and fall back to single PIDs. [L/M] — https://netcult.ch/elmue/hud%20ecu%20hacker/

### 3.3 Response-count suffix, adaptive timing, timeouts
- Adding one hex digit after the request (e.g. `01 05 1`) tells the ELM327 how many responses to wait for. It returns as soon as they arrive instead of waiting out the timeout. Default AT ST is 0x32 ≈ 200 ms (units of 4 ms). A count that is too small can cause problems.
  [H, primary] — ELM327 datasheet
- python-OBD's `fast=True` (default) appends the expected frame count and repeats the previous command by sending a bare CR. Set `fast=False` for unaltered commands. Other defaults:
  timeout 0.1 s, `check_voltage=True`. [H] — https://python-obd.readthedocs.io/en/latest/Connections/ ; https://github.com/brendan-w/python-OBD/blob/master/obd/obd.py
- Adaptive timing: **AT AT1** (default, recommended) learns the vehicle's real response time and shortens the wait. **AT AT2** is more aggressive and mainly helps slow links. **AT AT0** disables it (uses AT ST). AT ST is always the upper bound. [H, primary] — ELM327 datasheet
- In Torque, disabling "ELM327 auto timing" slows logging, and extra manufacturer headers (e.g. a Nissan profile) cut throughput. [M] — https://torque-bhp.com/community/main-forum/elm-327-bluetooth-adapter-speed-issues/

### 3.4 Headers and filtering (ATH1, ATSH, ATCRA)
- 11-bit OBD: functional request ID 0x7DF (all ECUs). The engine ECU usually takes 0x7E0 and replies on **0x7E8**. A second ECU uses 7E1/7E9, and so on up to 7EF. [H] — https://netcult.ch/elmue/hud%20ecu%20hacker/ ; https://en.wikipedia.org/wiki/OBD-II_PIDs
- `AT H1` shows the CAN ID in each reply line, needed to tell 7E8 (engine) from 7E9 (e.g. transmission) when several ECUs answer. [H, primary] — ELM datasheet
- `AT SH 7E0` sends requests physically to the engine ECU only. `AT CRA 7E8` accepts only replies from ID 7E8 (`AT CRA 7EX` = any 7Ex; `AT CRA` or `AT AR` resets). Bit-level filtering
  uses `AT CF`/`AT CM` (e.g. CF 7E8, CM 7F8). [H, primary] — ELM datasheet "CAN Receive Filtering"
- Why it matters for logging (inference, [M]): with functional addressing, a count suffix of `1` may return the *first* responder, which is not necessarily the engine. Addressing 7E0 plus filtering 7E8 makes the count reliable and saves bus time.
- Protocol: `AT SP 0` = auto search. Forcing the known protocol (e.g. `AT SP 6` = ISO 15765-4 CAN 11-bit/500k) skips the search. [H] — ELM datasheet

### 3.5 Clone limitations
- Elm Electronics never released a v1.5. Clones descend from the unprotected v1.4 firmware and may report any version. [H] — https://en.wikipedia.org/wiki/ELM327
- FORScan's analysis of "v2.1" fakes: many standard commands missing, simplified or incorrect logic, and at most 2 bytes taken from a command string. These fakes were later relabelled v1.5. PIC18F25K80-based "v1.5" units are generally the better clones, but even that chip can
  be faked. [M] — search summaries of https://forscan.org/forum/viewtopic.php?t=22 and https://forscan.org/forum/viewtopic.php?t=1575 (403 on direct fetch)
- Car Scanner's author disagrees with version/chip rules: there are good v2.1 and bad PIC adapters. [M] — https://www.carscanner.info/choosing-obdii-adapter/
- OBD Auto Doctor advises against clones generally: identical housings hide different electronics. [M] — https://www.obdautodoctor.com/obd-adapters/
- For our logs: detect the clone early (`AT I`, `AT @1`, test `AT CRA`, `AT AT1`, a multi-PID request, response-count suffix). Degrade gracefully when a command returns `?`.

### 3.6 Windows: SPP COM ports vs BLE
- Classic Bluetooth (SPP) adapters appear as a **virtual COM port** after pairing (PIN usually 1234 or 0000). Windows creates **outgoing** and **incoming** ports. Software must open the
  *outgoing* one (Settings → Devices → More Bluetooth options → COM Ports). [H] — https://inthehand.com/2020/11/22/bluetooth-virtual-com-ports/ ;
  https://www.totalcardiagnostics.com/support/Knowledgebase/Article/View/17/0/how-to-install-elm327-bluetooth-obd-scanner-on-windows (search summary)
- **BLE (Bluetooth 4.0 LE) adapters do not get a COM port.** The Windows serial-over-Bluetooth driver does not support BLE, so apps must speak GATT directly or use a bridge such as `ble-serial`. [H] — https://inthehand.com/2020/11/22/bluetooth-virtual-com-ports/ ;
  https://blog.ja-ke.tech/2019/12/18/ble-serial.html ; https://www.obdautodoctor.com/obd-adapters/
- Classic BT is faster than BLE and more reliable than Wi-Fi on Android. iOS supports only BLE, Wi-Fi or MFi. [M] — https://www.carscanner.info/choosing-obdii-adapter/
- Dual-mode adapters (e.g. Vgate iCar Pro 2.0 + 4.0) expose SPP for Windows. [M] — https://www.astools.eu/products/vgate-icar-pro-elm327-interface-bluetooth-4-0 (search summary)
- STAG QBOX PLUS has its own OBD interface (CAN 250/500k, ISO 14230, ISO 9141). When the car is on LPG with the STAG reader active, an external scanner may conflict. AC says to switch to petrol and cycle the ignition before using an external scanner, because the STAG OBD link is
  inactive in petrol mode. [H, primary] — STAG manual §2.20 → For ELM327 logging *on gas*, disable the STAG OBD reader/adaptation, or expect bus contention. [M, inference]

---

## 4. PID formulas (Service 01) for our log set
Source for all rows: https://en.wikipedia.org/wiki/OBD-II_PIDs (verified by fetch). A,B,C,D =
data bytes 1–4 after the PID echo. [H]

| PID | Bytes | Name | Formula | Range / unit |
|---|---|---|---|---|
| 03 | 2 | Fuel system status | A = system 1, B = system 2, coded 1/2/4/8/16 (§2.1) | enum |
| 04 | 1 | Calculated engine load | A/2.55 | 0–100 % |
| 05 | 1 | Coolant temp | A − 40 | −40…215 °C |
| 06 | 1 | STFT bank 1 | A/1.28 − 100 | −100…99.2 % |
| 07 | 1 | LTFT bank 1 | A/1.28 − 100 | −100…99.2 % |
| (08/09) | 1 | STFT/LTFT bank 2 | same | % |
| 0B | 1 | Intake manifold abs. pressure | A | 0–255 kPa |
| 0C | 2 | Engine speed | (256A + B)/4 | 0–16383.75 rpm |
| 0D | 1 | Vehicle speed | A | 0–255 km/h |
| 0E | 1 | Timing advance | A/2 − 64 | −64…63.5 ° BTDC |
| 0F | 1 | Intake air temp | A − 40 | −40…215 °C |
| 10 | 2 | MAF air flow | (256A + B)/100 | 0–655.35 g/s |
| 11 | 1 | Throttle position | A/2.55 | 0–100 % |
| 14 | 2 | O2 B1S1 (narrowband) | V = A/200; STFT = B/1.28 − 100 (B = 0xFF: not used in trim) | 0–1.275 V; % |
| 15 | 2 | O2 B1S2 (narrowband) | same as 14 | 0–1.275 V; % |
| 24 | 4 | O2 S1 wide-range: λ + voltage | λ = (256A + B)·2/65536; V = (256C + D)·8/65536 | λ 0–<2; 0–<8 V |
| 34 | 4 | O2 S1 wide-range: λ + current | λ = (256A + B)·2/65536; I = (256C + D)/256 − 128 | λ 0–<2; −128…<128 mA |
| 3C | 2 | Catalyst temp B1S1 | (256A + B)/10 − 40 | −40…6513.5 °C |
| 42 | 2 | Control module voltage | (256A + B)/1000 | 0–65.535 V |
| 43 | 2 | Absolute load value | (256A + B)·100/255 | 0–25700 % |
| 44 | 2 | Commanded equivalence ratio (λ) | (256A + B)·2/65536 | 0–<2 |
| 46 | 1 | Ambient air temp | A − 40 | −40…215 °C |

Notes:
- Trim formula equivalently: (B − 128)·100/128. Byte 0x80 = 0 %. [H]
- 0x14/0x15 are only present on narrowband-equipped positions. Check PID 0x13 (O2 sensors present bitmap: A0–A3 bank 1 sensors 1–4, A4–A7 bank 2). [H] — Wikipedia (above)
- PID 0x51 fuel type (5 = LPG, 9–16 = bifuel variants) is rarely supported on retrofits and is not needed here. [H] — Wikipedia
- Bytes per PID matter for multi-PID parsing: 1 byte → 04 05 06 07 0B 0D 0E 0F 11 46; 2 bytes → 03 0C 10 14 15 3C 42 43 44; 4 bytes → 24 34.

---

## 5. Fuel consumption from MAF

### 5.1 Formula
`fuel [L/h] = MAF[g/s] × 3600 / (AFR × ρ[g/L])` — equivalently ρ in kg/L × 1000.
L/100 km = L/h × 100 / speed[km/h] (undefined at standstill; use L/h there).
[H] — https://arxiv.org/pdf/1611.09065 (DrivingStyles paper, eq. 2)

Use the **actual** AFR: stoich × λ_actual. In closed loop λ ≈ 1. In open-loop enrichment, use
commanded λ (PID 44) or a wideband reading. Setting fuel = 0 when fuel cut is suspected (PID 03 = 4
and low load) avoids counting fuel that is not being burned. [M, derived]

### 5.2 Constants

| Fuel | Stoich AFR (mass) | Liquid density | Source |
|---|---|---|---|
| Petrol E5/E10 (EN 228) | 14.7 (E0; E10 ≈ 14.1 [L]) | 720–775 kg/m³ at 15 °C, typically ≈ 745 | [H] https://metacad.io/en/reference/fuel-density/gasoline/ ; https://measurlabs.com/products/en-228-gasoline/ ; HPA table below |
| Propane | 15.5–15.67 | ≈ 0.51 kg/L at 15 °C | [M] https://www.hpacademy.com/assets/Uploads/045852edfc/Stoichiometric-air-to-fuel-ratios-of-common-fuels-Sheet1-3.pdf (15.5); Cengel thermo solutions via search (15.67); density: https://www.dragongaz.pl/wp-content/uploads/2020/09/Physical-characteristics.pdf |
| Butane | ≈ 15.4–15.45 | ≈ 0.58 kg/L at 15 °C | [M] same sources |
| LPG autogas mix | ≈ 15.4–15.6 | 0.50–0.58 kg/L at 20 °C (mix-dependent; ~0.54 for 40/60 P/B) | [H] https://iea-amf.org/content/fuel_information/lpg/fuel_properties |

- The DrivingStyles paper uses 820 g/L for petrol, which is **too high** compared with EN 228 (720–775). Do not copy it. [H] — compare arxiv 1611.09065 vs EN 228 sources above
- LPG lower heating value is 44–46 MJ/kg but only 23–26 MJ/L, against petrol's about 32 MJ/L. So even a perfect tune burns about 20–30 % more litres of LPG than petrol. [M] — https://iea-amf.org/content/fuel_information/lpg/fuel_properties (LPG); petrol MJ/L is a
  common reference value [L]

### 5.3 Worked example
MAF 10 g/s at λ = 1:
- petrol: 10×3600/(14.7×745) = **3.29 L/h**
- LPG: 10×3600/(15.5×540) = **4.30 L/h** (+31 % by volume)

### 5.4 Accuracy caveats and use in the skill
- MAF-based estimates include every error of the MAF calibration, and ignore trims unless you apply them. Air mass is what the ECU sees. With the LPG trim error folded into STFT/LTFT, the
  *air* stays right, but fuel consumed ≈ air/(AFR·λ). [M]
- LPG density changes with temperature and propane/butane ratio, which gives several percent of uncertainty. Pump dispensers are temperature-compensated in some countries but not others.
  [M] — https://www.sciencedirect.com/science/article/abs/pii/S0360544221025457 ; IEA-AMF (above)
- Cars without PID 0x10: estimate MAF from MAP, RPM, IAT and displacement (speed-density; needs a volumetric-efficiency guess) or from absolute load (PID 43). These are less accurate. [M] — https://arxiv.org/pdf/1611.09065
- Low sample rates under-sample transients. Integrate using actual timestamps (trapezoid), not "samples × nominal interval". [M, derived]
- Use: compare **theoretical LPG L/100 km from MAF** with fill-up records. A sustained excess of more than 10–15 % beyond the expected energy ratio points to rich regions (check negative gas
  trims), petrol extra-injection while on gas (STAG "petrol portion" map), or frequent fallback to petrol. [L, inference]

---

## 6. Suggested log schema and filters for the skill (derived, [M])
- Minimal fast set (CAN, one multi-PID request): `01 0C 0B 06 07 03 04` gives RPM, MAP, STFT, LTFT, status and load in one round trip. Second, slower set: 05, 0F, 0D, 10, 11, 15/24/34, 44, 42, 0E.
- Record: timestamp (ms), fuel (petrol/LPG, from the user or the STAG log), raw hex + decoded values.
- Keep a sample only if: PID 03 = 2; ECT ≥ 75–80 °C; |ΔRPM| < 150 and |ΔMAP| < 5 kPa over 2 s; not within about 3 s after a fuel switch or after lift-off; RPM > idle + 200 unless analysing idle separately.
- Cells: RPM bins of 500 (800–4500) × MAP bins of 10 kPa, or STAG injection-time bins (≈ 2, 3, 4, 5, 6, 8, 10, 12, 15 ms). Require at least 20 samples or 10 s per cell per fuel.
- Output per cell: mean TT_petrol, mean TT_gas, Δ, suggested k (§1.4), sample count, and a confidence flag.

---

## 7. Open questions / conflicting advice
1. **Can generic OBD do multi-PID at all?** A Torque forum thread claims standard OBD does not support it, but the ELM327 datasheet and J1979 say up to 6 PIDs on CAN. The datasheet wins for CAN cars; K-line Toyotas (older Yaris/Aygo) cannot. Whether a given clone handles the
   multi-frame reply is untested. — https://torque-bhp.com/community/main-forum/can-multi-pid-request/ vs ELM327DS.pdf
2. **Response-count digit with multi-frame CAN replies**: the datasheet describes it as "number of responses". python-OBD treats it as a frame count. For a 2-frame multi-PID reply, is `1` or `2` correct on genuine and clone chips? Needs a bench test.
3. **Acceptable gas-vs-petrol difference**: ±3–5 % (drive2), ±5 % per cell (motorstate), "just oscillate around zero, spikes to ±15 % OK" (civicklub). No authoritative number. The skill should present its thresholds as configurable.
4. **Compare STFT+LTFT or LTFT only?** Most guides say both. Some Russian guides focus on LTFT only. Because LTFT is shared between fuels and adapts slowly, a total-trim comparison is safer, but it needs steady state and plenty of samples.
5. **Toyota LTFT adaptation speed and cell layout** (idle vs off-idle, number of load cells) remains undocumented publicly. The ~15 min idle anecdote and the ~10 s patent interval are weak evidence.
6. **Does STAG OBD adaptation use STFT, LTFT or the sum?** The manual speaks of "OBD corrections" and shows STFT/LTFT fields and an "LTFT max" limit, but does not give the exact formula. Also unclear whether its "target map" should be re-acquired after petrol-side
   repairs (probably yes).
7. **ELM327 on the bus while STAG's OBD reader is active on gas**: the manual implies conflict, but does not say whether passive coexistence is possible. Safest is to disable the STAG reader during ELM logging.
8. **Stoich AFR for LPG**: values quoted range from 15.4 (IEA-AMF) through 15.5 (HPA, propane) to 15.67 (thermodynamic propane). Seasonal mix changes matter more than this spread for consumption estimates.
9. **Petrol density used in consumption apps** varies (745 typical, 820 in one paper). Use 745 unless the user supplies a value.
10. **Clone quality heuristics** conflict: FORScan says fake v2.1 is bad and PIC18F25K80 v1.5 is good; Car Scanner says version and chip mean nothing. The skill should test capabilities empirically rather than trusting the version string.
11. Several Polish primary threads (lpg-forum.pl, forum.ac-sa.eu, civicklub.pl, elektroda.pl) were unreachable (refused, 403 or timeout). Their content above comes only from search-engine summaries and should be re-checked if they become reachable.
