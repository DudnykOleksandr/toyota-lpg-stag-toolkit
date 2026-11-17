# 01. STAG-4 QBOX calibration with AC STAG: practical knowledge base

Target: STAG-4 QBOX BASIC on a Toyota Corolla E15 1.6 1ZR-FE (2011, about 124 PS / 91 kW, 4 cyl, port injection, Dual VVT-i).
Research date: 2026-09. Sources are public manuals, AC S.A. articles and forums (PL/RU/UA).

**Confidence tags**
- **[OFFICIAL]**: AC S.A. manual or AC-authored article (primary source).
- **[MULTI]**: confirmed by several independent sources.
- **[SINGLE]**: one source only.
- **[FORUM]**: forum or blog opinion. Treat as a practitioner's hint, not a spec.
- **[DERIVED]**: my own engineering inference from the sourced facts. Not stated by any source.

**Main manuals used**
- M1.5: STAG-4 QBOX user manual EN v1.5: https://lpgshop.co.uk/content/downloads/lpg-software/ac-stag/STAG-4%20Q-BOX%20-%20Manual%20ENG%20ver1_5.pdf
- M1.6: STAG-4 QBOX / QNEXT / QMAX manual EN v1.6, which has the BASIC layout, ISA3 and OBD adaptation: https://www.stag.gr/sites/default/files/stag-4_q-box_-_manual_eng_ver.1.6.pdf
- AC-IV: AC S.A. article "Podstawy montażu i regulacji instalacji gazowej cz. IV" (Adam Skrzypko, AC technical specialist), mirrored at https://warsztat.pl/artykuly/podstawy-montazu-i-regulacji-instalacji-gazowej-4,58855. The ac.com.pl originals now redirect to mystag.pl.

**Glossary (EN / PL / RU-UA)**
- multiplier / mnożnik / множитель (множник)
- petrol map B / mapa benzynowa B / карта бензина
- gas map G / mapa gazowa G / карта газа
- deviation D / odchyłka / отклонение (відхилення)
- multiplier matching / dopasowanie mnożnika / подгонка множителя
- RPM correction / korekcja obrotów / коррекция по оборотам
- petrol portion / udział benzyny, dotrysk / доля бензина
- extra injections / dotryski / довпрыски
- working pressure / ciśnienie robocze / рабочее давление
- reducer / reduktor, parownik / редуктор
- volatile (vapour) phase filter / filtr fazy lotnej / фильтр летучей (паровой) фазы, ФТО
- liquid phase filter / filtr fazy ciekłej / фильтр жидкой фазы

---

## 1. How the STAG controller doses gas (mental model)

- **Basic model.** The controller takes each petrol injector pulse (Ti, in ms) and turns it into a gas pulse. It uses the **multiplier map** (multiplier as a function of petrol Ti). It then applies corrections for RPM (a 2D RPM×Ti map), gas temperature, reducer temperature and gas pressure. Where autoadaptation is used, it also applies the ISA3/OBD correction map (RPM×MAP). [OFFICIAL] M1.5 §2.9–2.14, M1.6 §2.19–2.22.
- **What "Gas pressure" in the monitor means.** It is the *differential* pressure: rail pressure minus intake manifold pressure. MAP is shown separately as an absolute value. [OFFICIAL] M1.5 §2.5.
- **Why petrol Ti is the "truth" signal.** The petrol ECU keeps controlling the mixture through the lambda sensor. If the gas dose is wrong, the ECU lengthens or shortens its own petrol pulses. So a gas calibration is correct when the **petrol Ti does not change after switching petrol → gas** at the same load. [OFFICIAL] M1.5 §3.5; AC-IV.
  - Petrol Ti gets **longer** on gas → the gas mixture is lean → **raise** the multiplier point for that Ti.
  - Petrol Ti gets **shorter** on gas → the gas mixture is rich → **lower** the point. [OFFICIAL] M1.5 §3.5; AC-IV.
- **Reading map D (deviation).**
  - **Positive** values mean rich: petrol Ti on gas is shorter than on petrol.
  - **Negative** values mean lean. [OFFICIAL] M1.6 §2.18.
  - Note that ISA3's "Temp. dev." field uses the **opposite sign**: positive there means Ti on gas is longer, i.e. lean. [OFFICIAL] M1.6 §2.22.1. Easy to confuse. Always check which window you are reading.

## 2. Recommended calibration workflow in AC STAG

### 2.1 Pre-checks before any calibration

- **Make sure every monitor signal is plausible first:** RPM, MAP, petrol Ti on all 4 channels, gas and reducer temperatures, pressure, lambda. Fix any wrong reading before calibrating. [OFFICIAL] AC-IV.
- **Mechanical state.** Clean filters, a pressure-stable reducer, no vacuum leaks, equal-length injector-to-manifold hoses, reducer mounted below the coolant expansion tank. Put the PS pressure tap *after* the vapour-phase filter, otherwise you see false pressure drops. [FORUM] https://a6klub.pl/lpg/podstawy-montazu-i-strojenia-lpg-t56145.html
- **Per-injector corrections are a last resort.** AC says to use "Gas injector corrections" only after all mechanical faults are fixed. Never use them to hide unequal hose lengths or worn parts. [OFFICIAL] M1.5 §2.4.
- **Extra-injection off threshold.** Petrol pulses shorter than the threshold are ignored, so no gas pulse is made for them. Typical example: threshold 1 ms, which drops 0.8 ms extra pulses. Use "Intelligent extra-injection handling" when the extra pulses are too long to cut. [OFFICIAL] M1.5 §2.4; AC-IV. Toyota 1ZR normally runs clean sequential injection. Check on the oscilloscope for short extra pulses during tip-in before you change this. [DERIVED]

### 2.2 Autocalibration (idle only)

- **Conditions** [OFFICIAL] M1.5 §2.6/§3.1:
  - Engine warm, lambda sensor already switching.
  - Idle. Do not touch the throttle.
  - A/C and lights off. Do not turn the steering wheel.
- **Possible errors:** "High/low rpm", "No injection pulses", "Intake manifold pressure too high", "Unstable engine operation". The last one points to vacuum leaks or the A/C being on. [OFFICIAL] M1.5 §2.6.
- **Warm engine.** Practitioners warm up fully, to about 90 °C coolant. [FORUM] a6klub; https://www.drive2.ru/c/525240828830942365/. One owner's test found no difference between calibrating at 60, 87 or 92 °C coolant, as long as the engine was out of warm-up mode. [FORUM] same drive2 thread.
- **What autocalibration stores.** It writes the **working pressure** and **calibration gas temperature**. You do not type them in. [OFFICIAL] M1.5 §2.4; [MULTI] https://lpg-forum.pl//cisnienie-robocze-a-rzeczywiste-stag-t25277.html, https://www.drive2.ru/c/525240828830942365/.
- **It is not a finished tune.** Autocalibration sets only the idle point. The software then says "set multiplier on the road". Stopping here is the classic installer mistake. [OFFICIAL] AC-IV.
- **Expected multiplier at the calibration point:**
  - **1.2–1.8** per the AC manual. [OFFICIAL] M1.5 §3.1, M1.6.
  - Above 1.8: at high load the gas pulses may overlap ("looped injection", message "Too long injection time" / "Gas injectors fully open"). You then need a rich lambda at full load, or bigger nozzles. [OFFICIAL] M1.5 §3.1, §2.8.2.
  - Gas Ti shorter than petrol Ti during autocalibration (multiplier < 1) means the nozzles are too big. [OFFICIAL] M1.5 §3.1.
  - Practitioners' "comfort ranges" are narrower and differ: 1.1–1.6 [FORUM] https://gaz-time.ua/nastrojka-gbo-stag; 1.2–1.4, ideally near 1.2 [FORUM] a6klub; 1.0–1.4 [FORUM] https://www.drive2.ru/c/589972926648837734/. See Open questions.
- **Rule of thumb for idle gas pulses.** At idle the gas pulse should be about 1 ms (fast injectors such as Hana) to 2 ms (slower Valtek-type) longer than petrol. The multiplier must never fall below 1.0 anywhere on the curve. [FORUM] a6klub; https://www.elektroda.pl/rtvforum/topic3821493.html (search snippet).

### 2.3 Collecting petrol map B, then gas map G

- **Petrol map (B, blue points).** After autocalibration, switch to petrol and drive about **4 km**. Try to stay in one gear (e.g. 4th) with the lambda actively switching. Vary the load so empty areas fill. The PC does not have to be connected. [OFFICIAL] M1.5 §3.2.
- **Gas map (G, green points).** Switch to gas and collect the gas map on the **same road, same gear, similar loads**. With the laptop connected you can correct multiplier points live. AC strongly recommends doing this, because a badly wrong curve can make the car fall back to petrol or turn on the check-engine lamp. [OFFICIAL] M1.5 §3.3.
- **Identical conditions matter.** Collecting B and G under different load patterns gives a wrong multiplier. Plan how you will accelerate and shift, and repeat it exactly. [OFFICIAL] AC-IV.
- **Tools that help:**
  - The software draws vertical guide lines for load areas that are not collected yet.
  - "State" views show which RPM×MAP cells are filled.
  - SPACE selects the multiplier point nearest the cursor. "Autoselection" follows the cursor.
  - [OFFICIAL] M1.5 §2.9, §2.17.
- **Warm engine for the reference map.** The B map should be collected warm. With ISA active, the minimum engine temperature for storing points is higher, and points collected colder can be deleted. [OFFICIAL] M1.6 §2.22.1. An AC-forum thread title confirms the petrol map is erased if it was collected under non-optimal conditions. [FORUM] https://forum.ac-sa.eu/viewtopic.php?f=24&t=4856 (forum offline at research time; title and snippet only).
- **Real-world example (Kia Rio, QBOX).** Maps were collected over 4–6 km on the highway in 4th–5th gear, first petrol, then gas on the same route. The owner reached about 5 % deviation. [FORUM] https://www.drive2.ru/l/8199569/
- **Incomplete G map.** If G covers a smaller area than B, the 3D deviation looks "spiky". Extend the gas map before chasing the peaks. [FORUM] https://www.drive2.ru/c/512813598658003989/

### 2.4 Deviation D and "Multiplier adjustment" (Dopasowanie mnożnika)

- **When the button unlocks.** Automatic multiplier adjustment becomes available after about **2/3 of the suggested area** is collected on both B and G. It moves the multiplier by the recorded B/G deviation. Small differences are ignored. AC recommends collecting the full suggested range anyway. [OFFICIAL] M1.5 §2.9.1.
- **Locked when autoadaptation is on.** Multiplier matching is **locked** while autoadaptation (ISA3 or OBD) is active. [OFFICIAL] M1.6 §2.9.1.
- **Acceptance criteria:**
  - The AC manual accepts a deviation within **±10 %**. [OFFICIAL] M1.5 §3.4.
  - Many tuners aim for **±5 %**. [FORUM] https://www.elektroda.pl/rtvforum/topic1655850.html (snippet), a6klub, drive2 Kia Rio.
- **Do not chase zero at low load.** In the low-load, short-pulse area, AC explicitly says not to aim for near-zero deviation. The ECU naturally scatters petrol pulses there. [OFFICIAL] M1.6 §2.18.
- **Manual method (more precise than maps):**
  1. Hold a steady load, e.g. Ti ≈ 5 ms. Switch petrol ↔ gas and watch the blue cursor move.
  2. Cursor moves **left** on gas → mixture rich → lower the point.
  3. Cursor moves **right** on gas → mixture lean → raise the point.
  4. Repeat about every 2 ms of Ti from the calibration point up to heavy load. Add points where needed.
  - [OFFICIAL] M1.5 §3.5.
- **Trim method (needs an OBD view).**
  - First note STFT/LTFT on petrol. Then tune gas so the trims behave the same.
  - Trims going negative on gas → rich → lower the point. Trims going positive → lean → raise it. [OFFICIAL] AC-IV.
  - A reader's comment under the AC article adds a caveat: the target is the **same trims as on petrol**, not zero. [FORUM] comment on warsztat.pl AC-IV page.
  - LTFT should settle within about ±5 % (±10 % tolerable). STFT normally swings about ±10 %. [FORUM] a6klub; https://www.drive2.ru/c/512813598658003989/

### 2.5 RPM correction map (Korekcja obrotów)

- **What it is.** A 2D plane over petrol Ti × RPM, used to enrich or lean specific RPM/Ti regions *on top of* the multiplier. [OFFICIAL] M1.5 §2.10.
- **Editing.**
  - Select an area by dragging with the mouse, or with SHIFT + arrows.
  - CTRL + up arrow = richer, CTRL + down arrow = leaner.
  - SHIFT multiplies the step by 10.
  - [OFFICIAL] M1.5 §2.10.
- **Typical use.** Fix lean running at high RPM or high load that the 1D multiplier cannot express. AC-IV says that if lambda reads near-lean under hard acceleration, check in this order: reducer pressure drop, nozzle size, then enrichment on the RPM (3D) map. [OFFICIAL] AC-IV.
- **Full-load mixture.** Under hard acceleration (open loop) a narrow-band lambda must show **rich**. [OFFICIAL] AC-IV. Only a wideband AFR gauge really verifies open-loop mixture. Rough targets: stoich about 14.7 for petrol and about 15.5 for LPG; peak power about 12.6–13:1 on a naturally aspirated engine. [FORUM] a6klub.
- **Newer firmware.** With autoadaptation off, the "MAP correction Bx" map (RPM × MAP) can also be used for manual corrections. [OFFICIAL] M1.6 §2.19.

### 2.6 Gas temperature, reducer temperature and pressure correction maps

- **Built-in corrections.** Automatic temperature and pressure compensation are built in. The user maps are **additional %** corrections. [OFFICIAL] M1.5 §2.12–2.14.
- **Gas temperature map procedure:**
  1. Do it only after autocalibration and the multiplier are correct.
  2. Start from a **cold** engine.
  3. From the switch-over temperature upward, compare petrol Ti before and after switching to gas.
  4. Ti longer on gas → raise the correction at that gas temperature. Ti shorter → lower it.
  5. Repeat in **5 °C steps** across the range.
  - [OFFICIAL] M1.5 §3.6. The same procedure appears at [FORUM] a6klub (overnight cold start).
  - Example: a Kia Rio owner needed up to **−13 %** at low gas temperatures, starting from 0 °C. [FORUM] https://www.drive2.ru/l/8199569/
  - Another owner says temperature correction is mandatory after autocalibration, typically over about −5…+75 °C. [FORUM] https://www.drive2.ru/c/525240828830942365/
- **Reducer temperature map.** Useful when the petrol ECU's fuelling depends strongly on engine temperature. [OFFICIAL] M1.6 §2.13.
- **Pressure correction map.**
  - It exists because reducers are not ideal: pressure drifts with temperature and sags in transients. [FORUM] https://www.drive2.ru/c/602498288234554538/
  - Several practitioners leave it at zero unless there is a clear problem. [FORUM] same thread.
- **Cold-engine jerking.** Use "Leaning on cold engine" (max engine temp, max RPM, max gas Ti). AC warns: always verify the mixture stays rich under high load on a warm engine. [OFFICIAL] M1.5 §2.4.

### 2.7 Petrol portion ("dotrysk" / доля бензина)

- **What it does.** A 2D RPM × Ti map of the % of petrol injected alongside gas. Example: 20 % in the 10 ms column = 2 ms of petrol. Afterwards verify the mixture in those areas. [OFFICIAL] M1.5 §2.11.
- **Simple mode.** Above set minimums of RPM and Ti, add a fixed petrol time. [OFFICIAL] M1.5 §2.4.
- **Why AC prefers it.** AC names petrol co-injection as its preferred **valve-seat protection**. In AC tests, lubricant dosed into one cylinder did not stop seat wear, while co-injected petrol in another cylinder removed the problem. AC also blames lubricant combustion products for clogging catalyst channels. Cost estimate: about 1 L petrol per 100 km. [OFFICIAL, AC quoted in press] https://gazeo.pl/poradniki/eksploatacja/Dodatkowa-ochrona-zaworow-w-instalacjach-STAG,artykul,10393.html
- **A Ukrainian installer's scheme for this Corolla 1.6:**
  - gas only below 4000 rpm
  - gas + 20–40 % petrol at 4000–4500 rpm
  - petrol only above 4500 rpm
  - [SINGLE] https://www.avtogas.center/ustanovka-gbo-na-toyota-corolla-tojota-korolla-1-6-124hp/
- **"Max load on gas".** If the petrol injectors ever reach 100 % duty, set this to about 95 %, so the car returns to petrol before the gas injectors go fully open. [OFFICIAL] M1.5 §2.4.

---

## 3. ISA3 autoadaptation

### 3.1 What it is

- **Availability on QBOX BASIC.**
  - M1.6 lists autoadaptation under QBOX PLUS.
  - Firmware **2.10.2 (09.03.2018)** made **ISA3 available on QBOX BASIC and QNEXT BASIC** as well. [MULTI] search snippet of https://forum.ac-sa.eu/viewtopic.php?f=32&t=8856; owner report on BASIC after 2.10.2: https://www.drive2.ru/l/514007393407861087/; https://www.drive2.ru/c/545705763880829559/
  - The OBD adaptation mode still needs the PLUS (built-in OBD). [OFFICIAL] M1.6 §2.20–2.22.
- **How it works.** ISA3 corrects the gas dose in real time by comparing live petrol Ti on gas with the **reference petrol map (B)** at the same RPM/MAP. It writes to a dedicated correction map over RPM × MAP. [OFFICIAL] M1.6 §2.22.1. AC/gazeo add that it recognises open and closed loop in real time and stores the reference map together with engine temperature. [MULTI] https://gazeo.pl/informacje/instalacje/produkty/q-generacja-stag-inteligentne-sterowniki-lpg,artykul,8970.html (search snippet), https://www.drive2.ru/c/567405450488711456/
- **Lambda wire required on BASIC.** Connect the controller's lambda input (purple wire) in parallel with the **upstream** O2 sensor and enable lambda in settings. One owner saw **no benefit** until lambda was connected. [FORUM] https://www.drive2.ru/c/545705763880829559/, https://www.drive2.ru/l/514007393407861087/

### 3.2 Settings (M1.6 §2.22.1 unless noted)

- **Adaptation switch-on threshold.** Deviation between live Ti and reference Ti below which ISA3 does nothing. **Minimum 4 %**, because of the natural scatter of petrol pulses. [OFFICIAL]
- **LTFT maximum.** Maximum correction ISA3 may apply. A commonly posted value is **25 %**. [OFFICIAL param / FORUM value] https://www.drive2.ru/c/567405450488711456/
- **Adaptation in OL.** Adapt in open loop too. Needs a voltage-type lambda and the Fuel Loop Status reading. [OFFICIAL]
- **Map autocomplete.** Auto-switches to **petrol** in areas that have no reference petrol points, within **1500–6000 rpm**. Side effect: unexpected petrol episodes until B is complete. [OFFICIAL / DERIVED]
- **Auto stop.** Locks cells automatically once they are successfully adapted. [OFFICIAL]
- **Manual lock.** Right-click on the correction map gives "adaptation lock/unlock" for ranges where you do not want adaptation. Locked cells show grey. [OFFICIAL]
- **Popular community preset:** ISA3 on, auto stop on, OL adaptation on, threshold 4, LTFT 25. [FORUM] https://www.drive2.ru/c/567405450488711456/
- **Firmware notes:**
  - Later firmware reduced ISA3 sensitivity at low revs.
  - OBD adaptation stops at extreme LTFT readings (−100 % / +99.2 %) to avoid false leaning during OL transitions.
  - [OFFICIAL, changelog snippet] https://www.ac.com.pl/en-downloads-firmware-qgeneration

### 3.3 When to use it, risks, relation to map B

- **ISA3 is only as good as map B.** A petrol reference collected cold, partial, or under odd loads makes ISA3 "correct" towards the wrong target. Practitioners delete the old maps and collect a fresh warm B before enabling it. [MULTI] M1.6 §2.22.1; https://www.drive2.ru/c/567405450488711456/; https://www.drive2.ru/l/514007393407861087/
- **Settling time.** It needs about **500–600 km** to settle. [FORUM] https://www.drive2.ru/c/567405450488711456/
- **Positive reports.** One owner (Octavia 1.8, city) went from 14.0 to 12.2 L/100 km LPG after ISA3 with lambda connected. [FORUM, single measurement] https://www.drive2.ru/l/514007393407861087/
- **Negative reports:**
  - Up to +1 L/100 km versus a manual tune.
  - Rich running in heat, or in winter when the reducer warms slowly.
  - Jerking for about 30 s after each adaptation step.
  - [FORUM] https://www.drive2.ru/c/567405450488711456/, https://www.drive2.ru/c/545705763880829559/, snippet of https://lpg-forum.pl/autoadaptacja-isa3-vs-obd-co-lepsze-t69246.html
- **Practical stance** [DERIVED, from the above]:
  1. Get a good manual calibration first: autocal → B/G → multiplier → temperature map.
  2. Then enable ISA3 with threshold ≥ 4 % and LTFT max ≤ 25 %.
  3. Watch the ISA3 correction map. Large or systematic corrections mean a **mechanical** drift to fix: filters, pressure, injectors. They are not something to "let ISA3 absorb".
  4. Disable ISA3 while you do manual multiplier work, because matching is locked while it is active.

---

## 4. Тиск редуктора / reducer pressure (high priority)

### 4.1 How it is set physically

- **Where it is set.** Output pressure of a 4th-gen reducer is set with an **adjusting screw**, usually a hex/Allen screw on the reducer cover. It preloads the spring on the regulating diaphragm. Turning it in raises the pressure; backing it out lowers it. [MULTI] https://www.elektroda.pl/rtvforum/topic1379446.html (KME Silver snippet), a6klub, https://www.drive2.ru/c/589972926648837734/
- **How it is measured.** The reducer is referenced to manifold vacuum, so it holds a roughly constant **pressure over MAP**. STAG displays exactly that differential pressure. [OFFICIAL] M1.5 §2.5; [FORUM] https://www.drive2.ru/c/602498288234554538/
- **Conditions for adjusting.** Engine warm, **on gas at idle**, watching the "Gas pressure" in AC STAG. One tip: after adjusting, stop and restart the engine before autocalibration. [FORUM] https://www.drive2.ru/c/589972926648837734/, https://stag.ua/ru/article/kakoe-dolzhno-byt-davlenie-reduktora-gbo-4-pokoleniya-i-kak-ego-pravilno-nastroit/
- **Typical values:**
  - **1.0–1.2 bar** for naturally aspirated engines. This is the most common recommendation. [MULTI] snippets of https://www.elektroda.pl/rtvforum/topic2629730.html and https://lpg-forum.pl/ustawienie-cisnienia-gazu-na-reduktorze-t58982.html; stag.ua; a6klub
  - 1.2–1.4 bar for turbo engines. [SINGLE] stag.ua
  - Wider operating range 1.0–1.4 bar, with minimum pressure set around 0.5–0.6 bar. [FORUM] drive2 589972926648837734 and RU search snippets.
- **Per reducer:**

| Reducer | Rating | Output range / typical | Source |
|---|---|---|---|
| AC STAG R02 | 136 or 150 KM variants; TWIN 320 KM | adjustable **0.9–1.5 bar**; pressure independent of tank level | https://mystag.pl/produkt/stag-r02/ , https://jacus.pl/reduktor-ac-stag-r02-136km-z-zintegrowanym-zaworem-gazowym-p-1875.html [OFFICIAL/SELLER] |
| Tomasetto AT07 | up to about 140 HP | typically set about 1.0–1.1 bar | https://www.agsiedlik.pl/en/tomasetto-at07-regulator--up-to-140-hp-i3175.html [SELLER]; [FORUM] elektroda AT07 threads |
| Tomasetto AT09 Alaska / Nordic XP | Nordic XP about 244 KM | Nordic XP range 0.7–1.9 bar; users run about 1.1 bar and report trouble at 1.6 bar | https://www.drypa.pl/produkt53985.html ; https://forum.octaviaclub.pl/forum/octavia-i-octavia-tour-1u/problemy-i-usterki-mechaniczne/53192-o1-reduktor-tomasetto-alaska-at09 [SELLER/FORUM] |
| KME Silver | n/a | Allen screw, range about 0.9–1.7 bar | https://www.elektroda.pl/rtvforum/topic1379446.html [FORUM snippet] |
| Landi Renzo IG1 | n/a | about 0.95–1.00 bar; works best around 1 bar | https://lpg-forum.pl/igs-jak-prawidlowo-ustawic-cisnienie-0-95-bar-w-ig1-t20678.html ; a6klub [FORUM] |
| Hana injectors (not a reducer) | n/a | tolerate up to about 1.4 bar | a6klub [FORUM] |

- Valtek and Energy Reggiani: no reliable factory figures found. Assume the generic 1.0–1.2 bar.
- The **AC W02** injector is specified at **120 l/min per section at 1.2 bar**, max 400 kPa, open/close 2.0/1.0 ms. That suggests AC's own design point is about 1.2 bar. [OFFICIAL datasheet] https://instal-gaz.com.pl/files/img/STAG_AC_W02_W03_PL.pdf

### 4.2 How the STAG "Operational pressure / Ciśnienie robocze" relates to it

- **Definition.** "Operational pressure" is the pressure **at which the controller was calibrated**. Autocalibration records it. It can be edited by hand, but AC warns: **"each change of the operational LPG pressure requires multiplier map calibration"**. [OFFICIAL] M1.5 §2.4.
- **Automatic compensation.** The controller automatically corrects gas Ti for the **measured** pressure against this reference: higher pressure gives shorter Ti, lower pressure longer Ti. The user pressure map is only an extra correction on top. [OFFICIAL] M1.5 §2.14; [FORUM] https://lpg-forum.pl//cisnienie-robocze-a-rzeczywiste-stag-t25277.html (snippet: the working pressure comes from autocalibration; to change it, adjust the reducer and repeat autocalibration).
- **"Do not touch it" advice.** Forums repeat: leave working and minimum pressure as autocalibration set them. [FORUM] elektroda / lpg-forum snippets in the §4.1 sources.
- **If someone turned the reducer screw after calibration** and did not recalibrate [DERIVED, not documented by AC]:
  - The pressure correction will partly compensate, because it sees a steady offset from the reference.
  - The compensation model cannot capture changed injector opening and closing dynamics. Those matter most at **short pulses (idle, low load)**. Expect the error there first: idle or low-load rich/lean, and a shifted deviation map.
  - The **Minimum pressure** threshold stays where it was. After a pressure *increase*, the margin to fallback grows. After a *decrease*, the car may drop to petrol ("LPG pressure too low") under load.
  - If instead someone edited the *Operational pressure* number in software without touching the screw, the automatic correction mis-scales every pulse. Result: a systematic rich/lean offset across the whole map.

### 4.3 Correct procedure after changing reducer pressure

1. **Record the current state.** Screenshot or save: Parameters (working pressure, minimum pressure, gas temperature at calibration, injector type), the multiplier map, B/G/D maps, and the correction maps. [DERIVED]
2. Warm the engine, run **on gas at idle**, and set the screw to the target pressure. Change it in small steps, e.g. **0.1 bar at a time**. [FORUM] https://lpg-forum.pl/autokalibracja-dysze-zbyt-duze-t41460.html (snippet: reduce by 0.1 bar and retry)
3. Stop and restart the engine. Run **autocalibration again**. It stores the new operational pressure and a new multiplier point. [MULTI] M1.5 §2.4; https://www.drive2.ru/l/8199569/ ("after every pressure change redo autocalibration"); https://www.drive2.ru/c/478039344406331608/ (raise to 1.20, update working pressure, autocalibrate)
4. Check that the multiplier at the calibration point is within **1.2–1.8**. [OFFICIAL]
5. Re-check **Minimum pressure**. Community values: about 0.3–0.4 bar below working pressure [FORUM a6klub], or 0.5–0.65 bar absolute [FORUM drive2 Kia Rio / RU snippets]. Also check the pressure error delay.
6. Re-collect B (if old or cold) and G, then fix the multiplier until D is within ±10 % (±5 % preferred). Then redo the gas temperature correction. [OFFICIAL] M1.5 §3.
7. Verify under hard acceleration: the pressure sag and a rich lambda (see §4.5).

### 4.4 Why installers raise or lower pressure, and the side effects

- **Pressure, nozzle size and multiplier move together.**
  - Higher pressure or bigger nozzles → more flow per ms → **lower multiplier**, shorter gas Ti.
  - Lower pressure or smaller nozzles → higher multiplier. [MULTI] a6klub; https://www.drive2.ru/c/589972926648837734/; https://7er.pl/showthread.php/21457-Jak-dobiera%C4%87-ci%C5%9Bnienie-wzgl%C4%99dem-rozmiaru-dysz (title/snippet)
- **Reasons to raise pressure:**
  - Nozzles too small, multiplier > 1.8.
  - "Gas injectors fully open" message or looping pulses at high load.
  - Lean lambda at full load.
  - Gas Ti at idle too long. Example: 6.3–7.1 ms on Lovato injectors was cured by raising to 1.2 bar plus autocalibration.
  - [OFFICIAL] M1.5 §2.8.2, §3.1; [FORUM] https://www.drive2.ru/c/478039344406331608/
  - A P0171-on-gas case was fixed with nozzles 2.4 → 2.8 mm, **1.25 bar** and a full recalibration. [FORUM] https://www.drive2.ru/c/588654062451295338/
- **Reasons to lower pressure:**
  - Nozzles too big. The autocalibration message "nozzles too large" appears when the multiplier falls below about 1.0. The advice is to drop about 0.1 bar, e.g. to 1.2 bar. [FORUM] https://lpg-forum.pl/autokalibracja-dysze-zbyt-duze-t41460.html, https://www.audiklub.org/forum/viewtopic.php?f=31&t=121201
  - Lower pressure is said to be kinder to the reducer and injectors. [FORUM] a6klub
- **Side effects of too high pressure:**
  - Short gas pulses near the injector's non-linear region, so poor idle and low-load dosing. [DERIVED]
  - Higher consumption, slower injector response, faster diaphragm wear. Some injector types stick at about 2 bar. [FORUM] https://stag.ua/ru/article/kakoe-dolzhno-byt-davlenie-reduktora-gbo-4-pokoleniya-i-kak-ego-pravilno-nastroit/; elektroda IG1/KME snippets.
- **Side effects of too low pressure:**
  - Lean mixture at high load, gas injectors near 100 % duty, higher combustion temperatures.
  - Fallback to petrol on pressure dips. [MULTI] stag.ua; M1.5 §1.6.
- **Cold weather.** Pressure reads about 0.2 bar higher cold and lower hot on some reducers. [FORUM] https://www.drive2.ru/c/602498288234554538/. That is a reason for the temperature and pressure correction maps, not for re-screwing the reducer seasonally. [DERIVED]

### 4.5 Symptoms in AC STAG data that reveal a pressure mismatch or problem

- **Idle drift.** Warm idle "Gas pressure" in the Monitor differs from **Parameters → Operational pressure** by more than about 0.1 bar, consistently. This suggests the screw was changed or the reducer drifted. Normal fluctuation is about **0.05–0.1 bar**. Some Tomasetto units drift 0.3+ bar. [FORUM] https://www.drive2.ru/c/589972926648837734/
- **Sag under full load:**
  - It should be **no more than about 0.2 bar**. Bigger drops mean the reducer is too small or worn, or a filter is clogged. Replace the reducer or add a second one. [OFFICIAL] AC-IV.
  - An alternative forum rule: under 20 % of the idle value. [FORUM, attribution uncertain: Polish forum search snippet]
  - Repeated "Low gas pressure" errors with frozen frames at high RPM/MAP confirm it. [OFFICIAL] M1.5 §2.8.1.
- **Pressure rising in cut-off / after shutdown.** On overrun, pressure creeps well above working pressure. "High gas pressure" error = pressure **2× operating pressure for 60 s**, typically reducer trouble such as a leaking valve seat or diaphragm. The QBOX "Pressure release" function vents this by pulsing the injectors down to 1.5 bar. [OFFICIAL] M1.5 §2.4, §2.8.1.
- **Multiplier and map pattern.** Autocalibration point outside 1.2–1.8. Or D shows a uniform offset, positive (rich) or negative (lean), across all Ti after a pressure change without recalibration. [DERIVED from OFFICIAL rules]

---

## 5. Typical gas settings (starting points, not specs)

- **Switch-over temperature (reducer):**
  - STAG default about **35 °C**. [FORUM snippet] https://www.elektroda.pl/rtvforum/topic1432471.html
  - Community range 30–45 °C: Kia Rio example 35 °C, raised to **40–45 °C in winter**. [FORUM] https://www.drive2.ru/l/8199569/, https://selected.pl/jakie-znaczenie-ma-temperatura-przelaczenia-na-gaz/
  - Dropping below about 30 °C is discouraged: the reducer gets too cold, diaphragms are stressed, consumption rises. [MULTI] selected.pl; https://gbo-master.zp.ua/chto-takoe-temperatura-reduktora-dlya-perexoda-na-gaz-i-kakoe-ona-okazyvaet-vliyanie/
- **Other switch-over parameters (Kia Rio, QBOX):** switch-over RPM threshold 600 (below 700 means switching at idle), switch-over delay 7 s, working pressure 1.1 bar. Idle MAP about 0.28–0.33 bar. [FORUM] https://www.drive2.ru/l/8199569/
- **Cylinder switch-over delay.** 200–300 ms per cylinder smooths the switch. [OFFICIAL param] M1.5 §2.4; [FORUM] Polish switch-jerk threads (see §6).
- **Hot start.** Allowed when the reducer is at or below switch-over temperature but at least 20 °C, and gas at least 10 °C. [OFFICIAL] M1.6.
- **Min gas temperature.** The controller returns to petrol below this. There is a "Low gas temperature" message. [OFFICIAL] M1.5.
- **Normal temperatures while driving:**
  - Reducer about **60–85 °C**, some report 85–95 °C.
  - Gas about **30–45 °C**; no corrections needed in that band.
  - Gas temperature falls under sustained load (one report: +7 °C at full throttle on the highway).
  - [MULTI] https://www.drive2.ru/c/548357785927025209/, https://stag.ua/ru/article/kak-pravilno-podklyuchit-reduktor-gbo-k-sisteme-ohlazhdeniya-avtomobilya/
  - Forum rule: gas should not fall below about 30 °C at maximum load. [FORUM snippet, attribution uncertain]
- **Reducer heating circuit.** Connect in **parallel** with the heater core circuit (small coolant loop), with the reducer below the expansion tank, and bleed the air. [SINGLE/vendor] stag.ua cooling article.
- **Injector nozzle sizing (AC table at 1 bar)** [OFFICIAL] M1.5 §1.7 (rough values):

| Power per cylinder (KM) | Nozzle (mm) |
|---|---|
| 11–16 | 1.7–1.8 |
| 17–28 | 1.9–2.2 |
| 29–34 | 2.3–2.5 |
| 35–40 | 2.6–2.8 |
| 41–48 | 2.9–3.0 |
| 49–55 | 3.1–3.2 |

  - For 1ZR-FE: 124 KM / 4 = **31 KM per cylinder**, so about **2.3–2.5 mm at 1 bar**, somewhat smaller at 1.2 bar. [DERIVED]
  - AC W02 nozzles come as 1.5–2.6 mm and can be drilled up to 2.8 mm. [OFFICIAL] W02 datasheet.
- **Reducer sizing.** The reducer must be rated at or above engine power, or pressure collapses at wide-open throttle and the car falls back to petrol. [OFFICIAL] M1.5 §1.6. For 124 KM an R02 136/150 KM or AT07 (140 HP) is adequate but not generous. [DERIVED] AC's own configuration proposal for 1ZR-FE: STAG-4 Q-Box Plus + R02 + W02. [SINGLE, search snippet] https://lpg-forum.pl/toyota-corolla-e15-1zr-fe-1-6-dual-vvt-i-124-km-2007r-t32237.html

## 6. Typical faults and how they look in STAG data

| Symptom | What STAG shows | Likely causes | Sources |
|---|---|---|---|
| Falls back to petrol under hard acceleration | "Low gas pressure" error; pressure sags more than 0.2 bar; frozen frame at high RPM/MAP | clogged vapour or liquid filter (sometimes a *new* cheap filter), reducer undersized or worn, solenoid not opening fully, pipe too thin, cold gas | AC-IV; M1.5 §2.8; https://www.elektroda.pl/rtvforum/topic1956615.html ; https://www.elektroda.pl/rtvforum/topic3884107.html [MULTI] |
| Lean at high load, P0171 on gas | petrol Ti on gas longer than on petrol at high Ti; negative D; LTFT positive; lambda lean at WOT | pressure sag, nozzles too small, multiplier or RPM map too lean, air leaks, dirty injectors, map collected wrongly | AC-IV; https://www.drive2.ru/c/588654062451295338/ ; https://www.drive2.ru/c/560608819361613243/ ; https://gaz-time.ua/bednaya-smes-na-gazu-prichiny-simptomy-i-sposoby-ustraneniya [MULTI] |
| Rich, P0172 on gas | petrol Ti shorter on gas; positive D; LTFT negative | multiplier too high, nozzles too big or pressure too high, sticking injector (magnetic sticking from worn damper rings), gas too cold without correction | M1.6 §2.18; https://avtogbo-kiev.com.ua/about11/1_diagnostika-neispravnosti-chistka-i-remont-gazovykh-forsunok [MULTI/FORUM] |
| Reducer icing, low reducer temperature | reducer temperature stays low; "Low gas temperature" message | bad coolant circulation (air lock, weak pump, serial connection, thermostat), reducer above expansion tank, solenoid not closing | stag.ua cooling article; https://gaz-time.ua/obmerzanie-reduktora-gbo-prichiny-i-resheniya ; https://www.drive2.ru/c/525558037935555475/ [MULTI] |
| Jerk on switch-over | at the moment of switching: dip in RPM, lambda spikes | switch-over temperature too low, all cylinders switched at once, long gas lines with stale gas, injector heating quirk, switching at high RPM | M1.5/M1.6 (cylinder delay, "Fuel overlapping", reducer filling time, cold leaning); https://lpg-forum.pl/szarpie-przy-przelaczaniu-t60043.html (snippet) [MULTI/FORUM] |
| Uneven idle, knocking injectors, cylinder imbalance | per-cylinder petrol Ti differ on gas but not on petrol | injector wear (damper rings, seat rubber), dirt, Valtek stroke drift; check by disabling one gas injector at a time | M1.5 §2.4; https://www.agcentrum.pl/forum/viewtopic.php?p=5655 ; https://lpg-forum.pl/valtek-rail30-skok-tloczka-t8660.html [MULTI/FORUM] |
| "Gas injectors fully open!" | overlapping gas pulses at high Ti | nozzles too small or pressure too low; ignore only if lambda reads rich at WOT | M1.5 §2.8.2; https://lpg-forum.pl/wtryskiwacze-gazowe-calkowicie-otwarte-t62873.html [OFFICIAL] |
| Consumption creeping up | ISA3 corrections growing, D drifting, pressure unstable | filters, injector wear, reducer drift, map drift after parts swapped | https://selected.pl/instalacja-lpg-wysokie-spalanie-gazu-analiza-ustawien-mapy-i-stanu-filtrow/ [SINGLE] |

**Injector notes**
- **Valtek Type 30:**
  - Correct armature stroke about **0.4–0.45 mm**. Cleanings without re-setting stroke leave cylinders unequal, e.g. 0.3 vs 0.6 mm. [FORUM] https://lpg-forum.pl/valtek-rail30-skok-tloczka-t8660.html ; https://callibration.com/kalibrovka-gazovyh-forsunok/
  - Said to drift and need attention every **15–20k km**. [FORUM] https://www.agcentrum.pl/forum/viewtopic.php?p=5655
- **AC W02/W03.** Rated durability **100k km city / 200k km highway**. [OFFICIAL] W02 datasheet.
  - The STAG injector warranty extends from 24 months to 3 years if a **STAG GF01 glass-fibre vapour-phase filter** is fitted and both filters are changed every 10k km / 1 year. [OFFICIAL] https://www.stag.pl/gwarancja-wtryskiwacze/
- **Hana.** Regarded as fast and tolerant of higher pressure (up to about 1.4 bar), with a smaller idle Ti margin (about +1 ms vs petrol). [FORUM] a6klub.

**Consumption sanity check**
- LPG consumption should be **higher** than petrol in litres, typically **+10–20 %**. LPG consumption at or below petrol consumption means a dangerously lean mixture (burned valve seats or pistons). [MULTI] https://selected.pl/instalacja-lpg-wysokie-spalanie-gazu-analiza-ustawien-mapy-i-stanu-filtrow/ ; https://www.stag.pl/instalacja-autogaz/

## 7. Service intervals

- **Filters (liquid and vapour phase):**
  - **Every 10,000 km or 1 year.** AC/STAG official for the injector warranty. [OFFICIAL] https://www.stag.pl/gwarancja-wtryskiwacze/
  - Other sources give 10–15k km for liquid and 20–30k km for vapour, or 15k km for both. [FORUM/vendor] https://mr-c.systems/pl/blog/jak-rozpoznac-ze-czas-wymienic-filtry-lpg ; https://www.drive2.ru/c/2135921/ ; elektroda snippets.
  - With dirty LPG, change them more often.
- **Service visit.** Leak check, filters and a **computer calibration check** at every service. STAG calls calibration the most important part of the check. [OFFICIAL] https://www.stag.pl/instalacja-autogaz/
- **Reducer rebuild (diaphragms, valve rubber).**
  - Typical life **80–120k km**, sometimes about 50k. Rebuild kits exist. [MULTI] https://warsztat.pl/artykuly/naprawy-reduktorow,60788
  - Some advise a kit at 70–80k km, or when pressure sags under load. [FORUM]
- **Injector check or cleaning.** Check after about 20k km on cheap injectors (Valtek). Cleaning needs dynamic flow testing, and re-calibration of stroke on Valtek. [FORUM] agcentrum; https://gazeo.pl/poradniki/eksploatacja/Naprawa-wtryskiwaczy-gazowych,artykul,5722.html
- **Spark plugs on LPG:**
  - LPG needs higher ignition voltage. Common advice is to change plugs about twice as often as on petrol, i.e. every 10–20k km for conventional plugs, and/or reduce the gap by about 0.1–0.2 mm (0.7–0.8 mm for single-electrode plugs). Worn plugs overload the coils. [MULTI] https://gazeo.pl/poradniki/eksploatacja/Swiece-i-przewody-zaplonowe-do-silnikow-zasilanych-LPG,artykul,6432.html ; https://dbamtomam.pl/jezdzisz-na-lpg-zadbaj-o-regularna-wymiane-swiec-zaplonowych/
  - 1ZR-FE OE plug: Denso **SC16HR11** iridium, gap **1.1 mm**, long life on petrol. [MULTI] https://www.densoproducts.com/denso-3499-sc16hr11-iridium-long-life-spark-plug ; https://plugspecs.com/best-spark-plugs-for-toyota-corolla/
  - On LPG, a shorter interval (e.g. 30–40k km) is prudent. [DERIVED]
- **Valve clearance on 1ZR-FE:**
  - 1ZR uses **roller rocker arms on hydraulic lash adjusters**, so there is no periodic shim adjustment. [MULTI] https://www.monkeywrenchracing.com/product/toyota-oem-hydraulic-lash-adjuster-1-1zr-2zr-2gr-1ur-3ur/ ; http://mywikimotors.com/toyota-1zr/ ; https://www.motobenzyna.pl/silniki/3589-silnik-1-6-16v-dual-vvt-i-1zr-fe.html
  - Some sites claim hydraulic adjusters make valve burning impossible even with a bad tune. [FORUM, overclaim] https://otoba.ru/dvigatel/toyota/1zr-fe.html ; https://www.avtogas.center/ustanovka-gbo-na-toyota-corolla-tojota-korolla-1-6-124hp/
  - Caution [DERIVED]: hydraulic adjusters only *hide* seat recession until they run out of travel. A lean LPG mixture still overheats valves and seats. Keep the WOT mixture rich and consider petrol co-injection at high RPM/load.
- **Valve protection options:**
  - Lubricators (Flashlube Valve Saver: about 1 L per 10k km at the standard dose). [SELLER/FORUM] https://www.flashlube.com/products/valve-saver-fluid ; BITOG thread
  - Fuel additives.
  - Ignition timing processors.
  - AC prefers **STAG petrol co-injection** (about 1 L petrol per 100 km, no maintenance, no catalyst clogging). [OFFICIAL/press] gazeo valve-protection article.
  - Polish sources say 1ZR-FE, having hydraulic adjusters, generally does not *need* lubrication or co-injection. [FORUM] motobenzyna.pl.

---

## 8. Condensed checklist for the Corolla 1ZR-FE + QBOX BASIC

1. Read and save the current Parameters. Compare **Operational pressure** with the warm-idle Monitor pressure (§4.5).
2. Check filters and reducer temperature (60–85 °C when warm). Log a WOT pull: pressure sag ≤ 0.2 bar, lambda rich.
3. If pressure is off target (1.0–1.2 bar) or was tampered with: set it at warm idle on gas, restart, autocalibrate, and check the multiplier is 1.2–1.8.
4. Collect a warm B map (about 4 km, steady gear), then G on the same route. Fix the multiplier until D is within ±10 % (±5 % if achievable). Ignore scatter at the shortest pulses.
5. Use the RPM correction map for high-RPM lean spots. Consider petrol portion above about 4000–4500 rpm.
6. Next cold morning: gas temperature correction in 5 °C steps.
7. Optional: connect lambda (purple wire), update firmware (≥ 2.10.2), enable ISA3 (threshold 4 %, LTFT max 25 %, auto stop). Monitor it for 500–600 km.
8. Service: filters every 10k km / 1 yr, plugs more often than the petrol schedule, reducer kit around 80–120k km.

---

## 9. Open questions and conflicting advice

- **Multiplier "good" range.** AC manual 1.2–1.8, versus practitioners' 1.1–1.6, 1.2–1.4, or 1.0–1.4. The ideal depends on injector type (opening lag) and pressure. For fast AC W02 at 1.2 bar, the low end is plausible. Nothing AC-official beyond 1.2–1.8.
- **Acceptable deviation.** AC manual ±10 %, community ±5 %. AC also says not to chase zero at low load.
- **Trim target.** Zero (AC-IV article text) versus "same as on petrol" (reader comment). For Toyota, compare against the car's own petrol LTFT baseline.
- **ISA3 on or off.** Owners report both fuel savings and +1 L/100 km or jerking. The outcome depends on map B quality and a connected lambda. No controlled comparison was found.
- **How well the automatic pressure correction absorbs a physical pressure change** without autocalibration. AC only says a changed *operational pressure* needs multiplier recalibration. The size of the residual error is not documented (§4.2 is inference).
- **Minimum pressure setting.** 0.3–0.4 bar below working, versus 0.5–0.65 bar absolute, versus whatever autocalibration sets. No AC figure found.
- **Filter intervals.** 10k km/1 yr (AC official) versus 15–30k km (vendors and forums).
- **1ZR-FE valve protection.** "Not needed thanks to hydraulic adjusters" versus the installer scheme with 20–40 % petrol at 4000–4500 rpm. Toyota LPG data for the ZR family is thin.
- **Sources not reachable during research:** forum.ac-sa.eu (offline), lpg-forum.pl (connection refused), elektroda.pl (blocked). Several claims from them rest on search-engine snippets only and are marked as such.
