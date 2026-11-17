# 03 — Corolla E150 (2007–2013), 1.6 1ZR-FE Dual VVT-i, U340E 4AT, on sequential LPG (STAG in mind)

Research date: 2026-09-26. These notes come from public web sources and are written in my own words.

Confidence marks used:
- **[M]**: several independent sources agree
- **[S]**: one source only (a spec sheet, catalogue or article)
- **[F]**: forum or owner opinion, or a single anecdote
- **[D]**: my own derivation or calculation from the cited numbers. Treat it as an estimate.

---

## 0. Key corrections to assumptions in the brief

1. **The valve train uses roller rocker arms with hydraulic lash adjusters (HLA). It is *not* shim-under-bucket.**
   There is no clearance spec to set or check. Section 1.5 has the sources.
   The figures that show up in searches for "1ZR-FE valve clearance" (0.15–0.25 mm intake, 0.25–0.35 mm exhaust) are the **1ZZ-FE** spec. They do not apply here.
2. **European and Russian-market 1ZR-FE cars use a narrowband switching O2 sensor upstream (89465-…), not an A/F sensor (89467-…).**
   Several sources say this, but one conflicts. See section 1.3.
3. **Early cars may not use CAN for OBD.** One compatibility list gives a 2007 Auris as ISO 9141-2 (K-line). Auto-detect the protocol instead of hard-coding CAN 11/500. See section 4.1.
4. **In Europe the 1.6 E150 came mostly as a manual or MultiMode (MMT).** The 4-speed torque-converter automatic (U340E-type, "Super ECT") was the version sold in Russia, Ukraine, China and SE Asia.
   - Russian-market owners describe their 1.6 cars as 4-speed "АКПП": [drive2 10147202](https://www.drive2.ru/l/10147202/), [drive2 2819603](https://www.drive2.ru/l/2819603/).
   - Wikipedia lists a "Super ECT 4-speed automatic" for the 1ZR-FE in several Asian markets: [Wikipedia E140/E150](https://en.wikipedia.org/wiki/Toyota_Corolla_(E140)).
   - **[M]**

---

## 1. Engine facts relevant to LPG

### 1.1 Basic data
- **Geometry:** 1598 cm³, bore × stroke 80.5 × 78.5 mm, compression ratio 10.2:1. [Wikipedia ZR](https://en.wikipedia.org/wiki/Toyota_ZR_engine), [autodata.ru ZR](https://autodata.ru/article/all/dvigateli_serii_zr/). **[M]**
- **Swept volume per cylinder:** about 400 cm³. This matters for choosing gas-injector nozzles (section 2.4). **[D]**
- **Output:** 91 kW (122–124 hp) at 6400 rpm and 153–157 N·m at about 5200 rpm. Toyota Europe quotes "124 KM". [Wikipedia ZR](https://en.wikipedia.org/wiki/Toyota_ZR_engine), [gazeo.pl Auris 1.6](https://gazeo.pl/lpg/jaka-instalacje-gazowa-wybrac-lpg-problemy/Toyota-Auris-2008-1.6-Dual-VTT-i-124-KM-91-kW,o,5504.html). **[M]**
- **Fuel:** 95 RON required. [autodata.ru](https://autodata.ru/article/all/dvigateli_serii_zr/), [enginetechspecs](https://enginetechspecs.com/toyota_1zr_fe_technical_data.html). **[M]**
- **Timing and EGR:** chain-driven cams, Dual VVT-i (intake and exhaust phasing). There is no external EGR; only the 2ZR-FXE has EGR. [toyota-club.net ZR FAQ](https://toyota-club.net/files/faq/19-09-30_faq_zr-engine.htm). **[S]**
- **Firing order:** 1-3-4-2. [enginetechspecs 1ZR-FE](https://enginetechspecs.com/toyota_1zr_fe_technical_data.html). This is standard for Toyota inline-4 engines. **[S]**
  - Gas injectors must be mapped to the same cylinder as their petrol injectors. The firing order matters only for STAG's per-cylinder correction.
- **Idle speed:** about 700 ± 50 rpm when warm. [enginetechspecs](https://enginetechspecs.com/toyota_1zr_fe_technical_data.html). **[S]**
- **Petrol fuel pressure:** about 300 kPa (returnless). [enginetechspecs](https://enginetechspecs.com/toyota_1zr_fe_technical_data.html). **[S]**

### 1.2 Air metering: MAF, not MAP
- The 1ZR-FE measures air with a **hot-wire MAF that has the intake air temperature (IAT) sensor built in**. It has no MAP sensor.
  - Sources: [autodata.ru ZR](https://autodata.ru/article/all/dvigateli_serii_zr/), [toyota-club.net ZR FAQ](https://toyota-club.net/files/faq/19-09-30_faq_zr-engine.htm).
  - The toyota-club DTC list for the 1ZR-FE includes P0102/P0103 (MAF circuit) and has no P0105–P0108 (MAP) codes: [toyota-club DTC 1ZR-FE](https://toyota-club.net/files/techdata/dtc/ems_1zrfe.htm).
  - **[M]**
- **Consequences for LPG and OBD:**
  - Over OBD, expect PID 0x10 (MAF) to be supported and PID 0x0B (MAP) to be missing or unsupported. **[D]**
  - The STAG controller takes its manifold vacuum reading from **its own MAP sensor**, the one on the gas filter or reducer, and not from the car.
  - A drive2 LPG thread on this car shows a STAG-type MAP reading of **0.35 bar absolute at idle**: [drive2 c/512289131611554227](https://www.drive2.ru/c/512289131611554227/). **[F]**
- **Dirty MAF:** a dirty or contaminated MAF causes lean trims on *both* fuels. That makes it a classic confounder when LPG consumption is rising. See the P0171 causes in [motostacja P0171](https://motostacja.com/blog/porady-techniczne/kod-p0171-p0174-mieszanka-uboga-przyczyny.html). **[M]**

### 1.3 Oxygen sensors
- **Upstream (B1S1)**
  - For European and Russian-market ZRE151/ZRE152 1.6 cars, the upstream sensor is a **4-wire heated planar switching (narrowband) zirconia sensor**.
  - Toyota numbers: 89465-12880 and 89465-02310. Cross-references: Denso DOX-0542, and the Denso DOX-0252 is often used as a substitute.
  - Evidence:
    - The Denso DOX-0542 listing (M18×1.5, 4 pins, "switching", planar) with OE 8946502310 and 8946512880: [sparkplugs.co.uk](https://www.sparkplugs.co.uk/denso-lambda-sensor-dox-0542).
    - A drive2 owner replaced the upstream sensor 89465-12880 on a 1ZR-FE. It is a 4-wire narrowband sensor, and the manual gives the heater at 1.8–3.4 Ω: [drive2 b/2896513](https://www.drive2.ru/b/2896513/).
    - Another 1ZR-FE owner had DTC **P0134**, a narrowband "no activity" code, and fixed it with a DOX-0252: [drive2 609598659448818413](https://www.drive2.ru/l/609598659448818413/).
    - autodata.ru describes a planar O2 sensor, 89465-: [autodata.ru](https://autodata.ru/article/all/dvigateli_serii_zr/).
  - **[M]**
- **Conflicting information**
  - toyota-club.net's general ZR FAQ says an A/F sensor (89467-) is used upstream: [toyota-club ZR FAQ](https://toyota-club.net/files/faq/19-09-30_faq_zr-engine.htm).
  - Its 1ZR-FE DTC table lists A/F codes (P2195, P2A00, P2237…): [toyota-club DTC 1ZR-FE](https://toyota-club.net/files/techdata/dtc/ems_1zrfe.htm).
  - This probably describes other markets or model years; for example, US 2ZR-FE Corollas use an A/F sensor, as the [2ZR-FE DTC list](https://toyota-club.net/files/techdata/dtc/ems_2zrfe.htm) shows. **[S]**
  - **Practical test:**
    - If OBD PID 0x14 (B1S1 voltage) switches roughly 0.1↔0.8 V, the car has a narrowband sensor.
    - If the ECU reports PID 0x24 or 0x34 (wideband equivalence ratio) instead, it has an A/F sensor. **[D]**
- **Downstream (B1S2):** a conventional narrowband sensor used for catalyst monitoring.
  - Part 89465-02280 is sold as the downstream sensor for Corolla/Auris 1.6/1.8: [Amazon listing](https://www.amazon.com/Downstream-compatible-Corolla-89465-02280-8946502280/dp/B0DBR58NX6).
  - The toyota-club FAQ also describes a cup-type O2 sensor after the catalyst: [toyota-club ZR FAQ](https://toyota-club.net/files/faq/19-09-30_faq_zr-engine.htm).
  - **[M]**
- **Relevance to STAG:**
  - With a narrowband upstream sensor, STAG's OBD adaptation and lambda display see a switching signal plus STFT/LTFT.
  - Fuel trims (PID 0x06/0x07) are the main feedback for gas-map correction. **[D]**

### 1.4 Petrol injectors
- The engine uses long-nozzle (extended-tip) injectors in the head that aim fuel close to the intake valves: [autodata.ru](https://autodata.ru/article/all/dvigateli_serii_zr/), [toyota-club ZR FAQ](https://toyota-club.net/files/faq/19-09-30_faq_zr-engine.htm). **[M]**
- The ZR family uses Denso EV14-type **12-hole** extended-tip injectors, for example 23250-37010 / 23250-0T020 on the 2ZR-FE. The 1ZR-FE's own part number and flow rate were not confirmed. [injector cross-reference listing](https://www.cspinjectors.com/product-page/oem-denso-fuel-injectors-set-4-23250-0t020-for-2009-2019-toyota-pontiac-1-8-i4). **[S]**
- **Warm-idle petrol injection time is short: about 1.9–2.25 ms.**
  - A Corolla 150 1.6 owner measured 1.9 ms on petrol at idle. Commenters said about 2.25 ms is typical: [drive2 c/512289131611554227](https://www.drive2.ru/c/512289131611554227/). **[F]**
  - This pushes the setup towards **fast gas injectors**. For example, ALEX Barracuda needs petrol times of at least 2.0 ms and Super B at least 1.6 ms, while slower rails such as RAIL-001 want at least 3.0 ms: [ALEX injector table PDF](https://autogas-alex.com/wp-content/uploads/tabela-przeplywow-statycznych-rail-001-flipper-barracuda-super-b-2024-pl-1.pdf). **[S]**

### 1.5 Valve train and valve clearances
- **Design:** roller rocker arms (with needle bearings) sit on **hydraulic lash adjusters**. There are no shims or selective buckets, and **no clearance specification** to set or check.
  - [autodata.ru](https://autodata.ru/article/all/dvigateli_serii_zr/): hydraulic adjusters with roller rockers.
  - [toyota-club ZR FAQ](https://toyota-club.net/files/faq/19-09-30_faq_zr-engine.htm): same.
  - Toyota OEM hydraulic lash adjusters are sold for 1ZR/2ZR: [Monkeywrench Racing](https://www.monkeywrenchracing.com/product/toyota-oem-hydraulic-lash-adjuster-1-1zr-2zr-2gr-1ur-3ur/).
  - Description of the 2ZR design, which is the same: [australiancar.reviews 2ZR-FE](https://www.australiancar.reviews/2zr-fe-toyota-engine/).
  - Polish databases also list "hydraulic valve clearance adjustment": [motobenzyna 1ZR-FE](https://www.motobenzyna.pl/silniki/3589-silnik-1-6-16v-dual-vvt-i-1zr-fe.html).
  - **[M]**
- **HLA check procedure:** with piston 1 at top dead centre, press on the rocker. If it moves with almost no effort, the lash adjuster is faulty. Other signs are clatter after a cold start and oil sludge. [carpedia.club HLA 1NR/1ZR](https://carpedia.club/Proverka-promyvka-i-zamena-gidrokompensatorov-zazorov-privoda-klapanov-dvigatelei-1NR-FE-i-1ZR-FE-Toyota-Corolla). **[S]**
- **Contradicting claims (probably wrong)**
  - [autohints 1ZR-FAE](https://www.autohints.com/toyota-1zr-fae-16l-132hp-inline-4-cylinder-engine-14492) claims mechanical bucket lifters that need bucket swaps. **[S, likely wrong]**
  - An autocentrum/motobenzyna user comment says that after about 100,000 km on LPG the clearance dropped to zero and "8 adjustment discs" had to be replaced (search summary of [autocentrum / motobenzyna comments](https://www.motobenzyna.pl/silniki/3589-silnik-1-6-16v-dual-vvt-i-1zr-fe.html)). **[F, inconsistent with the HLA design; possibly confused with 1ZZ/4ZZ]**
- **How LPG affects valves on an HLA engine** **[D, general mechanism]**
  - Valve seat recession (from higher exhaust temperature and dry gas with no lubrication) does not show up as a *clearance* problem at first. The HLA absorbs it by collapsing further.
  - Once the lash adjuster runs out of travel, the valve no longer closes fully. The result is loss of compression, misfires, a burnt exhaust valve, and a rise in fuel use.
  - Because nobody measures clearances on this engine, recession is invisible until then. **A compression test or leak-down test is the only practical check.**
  - The mechanism is described generally in [amrautos VSR](http://www.amrautos.co.uk/index.php/lpg-systems/26-valve-seat-recession-explained).
- **Recommended check intervals on LPG**
  - No Toyota interval exists, because the valve train is maintenance-free.
  - Suggested practice: a **compression test every about 30,000–40,000 km on LPG**, plus a check whenever consumption rises or the idle roughens. **[D]**
  - drive2 owners of shim/bucket Toyota engines with LPG adjust valves every 50,000–60,000 km. That does not apply to the 1ZR, but it gives a sense of how fast wear progresses: [drive2 1AZ/2AZ](https://www.drive2.ru/l/552330596316086358/). **[F]**

---

## 2. LPG suitability of the 1ZR-FE

### 2.1 Reputation
- **Polish databases and reviews:** generally described as "works well with LPG" and durable, with the main advantage being hydraulic lash adjustment.
  - Sources: [motobenzyna 1ZR-FE](https://www.motobenzyna.pl/silniki/3589-silnik-1-6-16v-dual-vvt-i-1zr-fe.html), [motolpg 1ZR](https://www.motolpg.pl/silniki/lpg-w-silniku-toyota-1-6-16v-dual-vvt-i-1zr.html). motolpg gives an average installation cost of about 4500 PLN.
  - According to search summaries, gazeo.pl's entry says no valve lubrication is needed: [gazeo.pl Auris 1.6 124 KM](https://gazeo.pl/lpg/jaka-instalacje-gazowa-wybrac-lpg-problemy/Toyota-Auris-2008-1.6-Dual-VTT-i-124-KM-91-kW,o,5504.html). The page did not render in full, so treat this as **[S]**.
  - **[M]**
- **AutoCentrum LPG ratings for the Corolla X 1.6 124 KM (2007–2010):** 7 opinions, average **4.6/5**. [autocentrum opinie-lpg](https://www.autocentrum.pl/opinie-lpg/toyota/corolla/x/sedan/silnik-benzynowy-1.6-i-16v-vvt-i-124km-2007-2010/). **[F, but aggregated]**
  - Systems reported: BRC Sequent 32 (90,000 km with no faults), BRC Sequent 24, AC STAG 300 Premium (×3), and AC (unspecified).
  - Reported LPG use: about **9.0–9.4 L/100 km**. One of these owners reports 8.2 L/100 km on petrol.
  - Complaints: a loss of power with poor-quality gas.
- **Long-term reports**
  - An Auris 1.6 owner has 255,000 km in total, **110,000 km of it on LPG**, with no engine failures and only two ignition coils replaced: [motobenzyna Auris 1.6](https://www.motobenzyna.pl/samochody/1918-toyota-auris-i-1-6-16v-vvt-i-124km.html). **[F]**
  - A Russian owner fitted LPG at 30,000 km on a 2011 car. At 208,000 km compression was **10.5 on all cylinders**, with no oil consumption (search summary of the drive2 / motorist.expert results for "1ZR-FE ГБО прогар"). **[F]**
- **Negative reports**
  - Some owners say the valve seats are "soft or delicate" and wear quickly on gas: [motobenzyna comments](https://www.motobenzyna.pl/silniki/3589-silnik-1-6-16v-dual-vvt-i-1zr-fe.html). **[F]**
  - There are documented 1ZR-FE cases of burnt exhaust valves, head-gasket burn-through between cylinders 1–2, and compression of 4 bar vs 10 bar. The LPG link is not always clear, e.g. [drive2 553481784990368684](https://www.drive2.ru/l/553481784990368684/). **[F]**

### 2.2 Valve lubrication (Flashlube / Valve Saver)
- **Mixed advice**
  - Polish sources say it is **not required**, thanks to the hydraulic adjusters. [motobenzyna](https://www.motobenzyna.pl/silniki/3589-silnik-1-6-16v-dual-vvt-i-1zr-fe.html), [gazeo.pl entry summary](https://gazeo.pl/lpg/jaka-instalacje-gazowa-wybrac-lpg-problemy/Toyota-Auris-2008-1.6-Dual-VTT-i-124-KM-91-kW,o,5504.html).
  - An English-language article **recommends Flashlube** for the 1ZR family: [autohints](https://www.autohints.com/toyota-1zr-fae-16l-132hp-inline-4-cylinder-engine-14492). Its reasoning rests on the wrong "bucket" premise.
  - **[F/conflicting]**
- **Suggested position for the skill:** optional but low-cost insurance for high-mileage, gas-only use. It is more useful to stop gas maps from running lean and to watch compression. **[D]**

### 2.3 Reducer and gas injectors used by installers (about 91 kW / 124 hp)
| Source | Controller | Reducer | Injectors | Notes |
|---|---|---|---|---|
| [gaz-time.ua Corolla 1.6 2008](https://gaz-time.ua/gallery/toyota/corolla-16-2008-kme) | MRC G-04 | Tomasetto Nordic (≤170 hp) | MRC Solo | 54 L toroidal tank; see section 5 for consumption **[S]** |
| [drive2 c/512289131611554227](https://www.drive2.ru/c/512289131611554227/) | Digitronic Maxi2 | Tomasetto AT09 | Barracuda | 1.24 bar, gas 2.85 ms at idle **[F]** |
| [forumsamochodowe 1ZR-FE](https://www.forumsamochodowe.pl/lpg-do-toyota-corolla-1-6-16v-dual-vvt-i-1zr-fe-vt119959.htm) | STAG 4 Q-Box Plus proposed | Advice: avoid AC reducers (unstable pressure); Nordic preferred | AC W02 acceptable; Hercules GISM preferred | **[F]** |
| [drive2 501645859054683134](https://www.drive2.ru/l/501645859054683134/) | Lovato 4th gen | Lovato | Lovato | noisy injectors; 53 L tank **[F]** |
| [autocentrum](https://www.autocentrum.pl/opinie-lpg/toyota/corolla/x/sedan/silnik-benzynowy-1.6-i-16v-vvt-i-124km-2007-2010/) | STAG 300 Premium, BRC SQ32/24 | – | – | **[F]** |

- **Takeaway:** any reducer rated about 120–170 hp is adequate, for example Tomasetto AT09/Nordic, KME Silver or STAG R02. **[D, from the table]**
- **Injector choice:** use **fast** gas injectors, because petrol idle time is only about 2 ms.

### 2.4 Nozzle size and working pressure
- The ALEX flow table maps nozzle diameter to hp per cylinder: [ALEX PDF](https://autogas-alex.com/wp-content/uploads/tabela-przeplywow-statycznych-rail-001-flipper-barracuda-super-b-2024-pl-1.pdf). **[S]**
  - 2.0 mm is good for about 21 hp/cyl at 1.0 bar and about 23 hp/cyl at 1.2 bar.
  - 2.2 mm: about 24 / 27 hp/cyl.
  - 2.4 mm: about 27 / 31 hp/cyl.
  - The same table's rule of thumb by cylinder volume: 1.8 mm up to 333 cm³, **2.5 mm for 333–399 cm³**, 2.8 mm for 400–450 cm³. The 1ZR sits right at the boundary, at 399.5 cm³.
  - The table notes that power values can differ from reality by up to 30% depending on LPG composition.
- **Expected nozzle for 31 hp/cyl** **[D]:** about **2.2–2.5 mm at 1.0–1.3 bar** (the STAG pressure differential).
- **Idle injection time:** the drive2 thread aimed for gas injection time at idle of about **3.4–3.5 ms** (Barracuda). Their 2.85 ms was considered too short: [drive2 c/512289131611554227](https://www.drive2.ru/c/512289131611554227/). **[F]**
- **Working pressure:** the usual target is **1.0–1.2 bar** above manifold pressure. Higher pressure shortens injector life (Polish forum consensus in the search results, e.g. [mondeoklubpolska LPG](https://www.mondeoklubpolska.pl/showthread.php/91565-Prosz%C4%99-o-pomoc-w-regulacji-LPG)). **[F/M]**
- **Choosing a nozzle by idle time:** pick the nozzle so that gas injection time at idle is just above the rail's stable minimum. [gazmap.ru](https://gazmap.ru/komplektuiuschie-vpriska/vibor-zhiklerov-gazovich-forsunok). **[S]**
- **Stable minimum opening times at 1.0 bar (ALEX):** Barracuda 2.0 ms, Super B 1.6 ms, Flipper 2.6 ms, RAIL-001 3.5 ms. [ALEX PDF](https://autogas-alex.com/wp-content/uploads/tabela-przeplywow-statycznych-rail-001-flipper-barracuda-super-b-2024-pl-1.pdf). **[S]**

---

## 3. Coolant and thermostat

### 3.1 Thermostat and normal temperatures
- **Thermostat opening temperature: 82 °C.** It is fully open at about 95 °C.
  - OBD observation: the thermostat opened at +82 °C, with a maximum of about 89–92 °C: [drive2 662027771907687903](https://www.drive2.ru/l/662027771907687903/).
  - Replacement units are 82 °C (TAMA WV56TB82A, HKT ZB56TB82): [drive2 527582513720197723](https://www.drive2.ru/l/527582513720197723/).
  - Spec sheet: [enginetechspecs](https://enginetechspecs.com/toyota_1zr_fe_technical_data.html).
  - toyota-club says "78–82 °C, cold type": [toyota-club ZR FAQ](https://toyota-club.net/files/faq/19-09-30_faq_zr-engine.htm).
  - **[M]**
- **Normal warm operating temperature (OBD PID 0x05):**
  - About **86–94 °C**. Examples: 88–89 °C on the highway, 89–93 °C in town or in heat, 86 °C at idle at −2 °C.
  - [drive2 527582513720197723](https://www.drive2.ru/l/527582513720197723/), [drive2 691714310979732317](https://www.drive2.ru/l/691714310979732317/). **[M]**
- **The dash gauge hides a lot:**
  - The needle reaches mid-scale at only about 53–54 °C and looks "normal" from about 78 °C.
  - So a thermostat stuck open at 70–75 °C **does not show on the gauge**. Read it over OBD.
  - [drive2 662027771907687903](https://www.drive2.ru/l/662027771907687903/). **[S]**
- **Winter heater effect:** at maximum heater fan in frost, coolant can fall below 70 °C and the thermostat cycles. [drive2 662027771907687903](https://www.drive2.ru/l/662027771907687903/). **[S]**

### 3.2 Stuck-open thermostat: symptoms and prevalence
- **Example 1:** after −40 °C frost, the coolant never went above **68 °C**. A new 82 °C thermostat restored 88–93 °C. [drive2 527582513720197723](https://www.drive2.ru/l/527582513720197723/). **[F]**
- **Example 2:** an aftermarket thermostat opened crooked and only partly. The throttle-body heating circuit had no flow at idle. On that car the **LPG reducer was teed into the throttle-body heating circuit**, which exposed the fault. [drive2 691714310979732317](https://www.drive2.ru/l/691714310979732317/). **[F]**
- **Prevalence:** I found no statistics. Such cases are reported as occasional, mostly in cold climates or with cheap aftermarket units. **[F]**
- **Toyota P0128 logic (generic Toyota manual):**
  - The ECM estimates coolant temperature from start temperature, load and rpm.
  - When the estimate reaches **75 °C**, it compares it with the real sensor reading. If the real value is still below 75 °C, it sets P0128 (two-trip logic).
  - [Toyota Venza manual P0128](https://www.tovenza.com/coolant_thermostat_coolant_temperature_below_thermostat_regulating_temperature_p0128_-1005.html). **[S, other model]**
  - P0128 was **not listed** in the toyota-club DTC table for the 1ZR-FE: [toyota-club DTC 1ZR-FE](https://toyota-club.net/files/techdata/dtc/ems_1zrfe.htm). So a stuck-open thermostat may produce **no MIL at all** on some calibrations. **[S]**
- **Effect on fuel consumption**
  - General effects of an over-cooled engine: warm-up enrichment, thicker oil, more friction. [iCarsoft P0128](https://www.icarsoft-us.com/blogs/news/p0128-code). **[M, generic]**
  - LPG-specific effects **[D]**:
    - The ECU's base injection time rises when coolant temperature is low. STAG scales gas time from petrol time, so gas use rises in the same proportion.
    - The reducer and gas run colder. The controller's gas-temperature correction must compensate, and it is often mis-set.
    - Switch-over to gas happens later.
    - A 5–15% rise in consumption in winter is plausible from these effects combined.

---

## 4. OBD-II on the Corolla E150

### 4.1 Protocol
- **Later cars:** expect **ISO 15765-4 CAN, 11-bit IDs, 500 kbit/s**. Requests go to 7DF (functional) or 7E0 (engine); the engine replies on 7E8. [ELM327 header notes, rdforum](https://www.rdforum.org/threads/134525/), [python-OBD CAN 11/500](https://github.com/brendan-w/python-OBD/issues/134). **[S/D]**
- **Early cars:** a Russian compatibility list gives the **2007 Auris (same platform) as ISO 9141-2 (K-line, 10.4 kbaud)**: [elm327rus.ru](https://elm327rus.ru/sovmestimost/). **[S]**
  - Early 2007–2008 cars may therefore use K-line, which gives only a few PIDs per second.
  - Use protocol auto-detection (ATSP0) and log which protocol was found (ATDPN).
- **Engine and gearbox control:** on the 4AT car the transmission is controlled by the engine ECM ("ECT"), so normally only one ECU answers mode 01. **[D]**

### 4.2 Mode 01 PIDs to expect
Confirm on the car with PIDs 0x00, 0x20 and 0x40. This list is derived from the sensor set **[D]**:

| PID | Meaning | Expected on 1ZR-FE |
|---|---|---|
| 0x04 | calculated load | yes |
| 0x05 | coolant temperature | yes |
| 0x06 / 0x07 | STFT / LTFT, bank 1 | yes |
| 0x0B | MAP | **probably not** (MAF engine) |
| 0x0C / 0x0D | rpm / speed | yes |
| 0x0E | ignition advance | yes |
| 0x0F | IAT | yes (inside the MAF) |
| 0x10 | MAF (g/s) | **yes** |
| 0x11 | throttle position | yes (electronic throttle) |
| 0x13 | O2 sensors present | yes |
| 0x14 / 0x15 | B1S1 / B1S2 voltage + STFT | **yes, if narrowband (EU/RU)** |
| 0x24 / 0x34 | wideband λ | only if an A/F sensor is fitted (non-EU) |
| 0x1F, 0x21, 0x2F, 0x33, 0x42, 0x46 | run time, distance with MIL, fuel level, baro, module voltage, ambient temperature | varies |

### 4.3 ELM327 quirks with Toyota
- **Header:** set the header to 7E0 so only the engine ECU answers and you don't get replies from other modules. [rdforum OBD2 oddities](https://www.rdforum.org/threads/134525/). **[S]**
- **Mode 04 does not reset LTFT.** Clearing DTCs with a generic tool **does not reset long-term trims** on Toyota. You need an ECU power reset or Techstream. [justanswer summary / toyotanation](https://www.toyotanation.com/threads/techstream-learning-value-reset.1683570/). **[F/M]**
- **Toyota-specific data needs Toyota tools.** Injection time in ms, the "AF lambda" value and the learning values are only in Toyota enhanced data (Techstream mode 21/22). Generic OBD has no injector-pulse PID, so fuel rate has to be estimated from MAF. **[D]**
- **Speed on K-line:** throughput is low, so poll only a few PIDs. **[D]**

### 4.4 Fuel-trim behaviour and reset
- **Normal band:** about **±10%** total trim is considered normal. [toyotanation / generic](https://www.toyotanation.com/threads/quick-question-on-fuel-trims.1729452/). **[M]**
- **P0171 / P0172:** set when the average learned trim (STFT + LTFT) is **≥ +35% or ≤ −35%** with coolant above 75 °C. [Toyota Venza manual](https://www.tovenza.com/system_too_lean_bank_1_p0171_p0172_-1007.html). **[S, other model]**
  - Other Toyota manuals use thresholds of +33/−30% or ±38%, depending on the model (search summary of Toyota repair manuals). **[S]**
  - Polish LPG forums report lean codes starting at LTFT around +24%. [motostacja](https://motostacja.com/blog/porady-techniczne/kod-p0171-p0174-mieszanka-uboga-przyczyny.html). **[F]**
- **Trims are shared between fuels.** The ECU keeps **one set of trims**. Whatever it learns while on LPG also applies on petrol, and the reverse. **[D]**
  - The LPG calibration goal is for LTFT on gas to match LTFT on petrol within a few percent across the load map.
  - Positive trims on gas mean the gas map is lean.
- **How to reset trims**
  - Disconnect the battery for 15 minutes or more, or pull the **EFI fuse** for 1 minute or more.
  - Or use Techstream: Engine → Utility → "Learning Value Reset" (some models also have a "Fuel Density Learning Value Reset").
  - The ECU relearns over a few drive cycles.
  - [toyotanation Techstream reset](https://www.toyotanation.com/threads/techstream-learning-value-reset.1683570/), [toyotanation ECU reset](https://www.toyotanation.com/threads/ecu-reset.1649480/), [ih8mud EFI fuse](https://forum.ih8mud.com/threads/pulling-efi-fuse.923240/). **[M]**

### 4.5 Typical MAF and load values
- **Warm idle MAF:** about **1.5–2.5 g/s**, rising to about 5–10 g/s at 2500–3000 rpm with no load. This comes from a Russian search summary of the 1ZR-FE scanner norms. **[S/F]**
  - A Toyota manual for an older Corolla gives **1.4–2.3 g/s** at idle and **11.3–20.0%** calculated load at idle (search summary of [scannerdanner/justanswer](https://www.scannerdanner.com/forum/post-your-repair-questions-here/10636-toyota-corolla-maf-sensor.html?start=6)). **[S]**
- **Warm idle load (PID 0x04):** expect about **15–25%**. With A/C or D-range load it runs higher. **[D, from above]**
- **At 90 km/h steady (4th gear, lock-up)** **[D]:**
  - Owner data puts consumption at 80–100 km/h at about 5.3–6.0 L/100 km: [drive2 10147202](https://www.drive2.ru/l/10147202/).
  - At 90 km/h and 5.7 L/100 km, fuel flow is 5.1 L/h. That is 3.8 kg/h, or 1.06 g/s of fuel.
  - At an air-fuel ratio of 14.7, **MAF ≈ 15–16 g/s**.
  - Calculated load is likely **about 30–40%** at about 2300–2500 rpm. The rpm was not confirmed by a source.
- **Idle fuel flow estimate:** 1.5–2.5 g/s of air works out to about 0.10–0.17 g/s of petrol, or **about 0.5–0.8 L/h at idle** (petrol equivalent). **[D]**

---

## 5. Real-world consumption and whether 12→14 L/100 km on LPG is abnormal

### 5.1 Petrol, 1.6 with 4AT (E150)
- **Official figures:** about 9.3 L/100 km city, 6.0 highway, 6.9–7.2 combined.
  - [drom catalogue](https://www.drom.ru/catalog/toyota/corolla/specs/fuel_consumption/) gives 6.9 combined for the 1.6 AT.
  - The Russian passport figures of 9.3 / 6.0 come from a search summary of [auto.ru specs](https://auto.ru/catalog/cars/toyota/corolla/2309501/2309502/specifications/2309502__20784024/).
  - **[M]**
- **Owners:**
  - **Measured by fill-up:** 9.1–9.5 L/100 km mixed, with A/C and lights on. Highway: 5.3–5.5 at 80 km/h and 5.7–6.0 at 100 km/h. [drive2 10147202](https://www.drive2.ru/l/10147202/). **[F]**
  - **Winter city with short trips and long idling:** 12–13.5 L/100 km, peaks of 15.1, which owners called normal. Highway at −26 °C was 7.2. [drive2 2819603](https://www.drive2.ru/l/2819603/). **[F]**
  - **Polish data for the Auris/Corolla 1.6, mostly manual:** 8–8.5 city/mixed, about 10 at 140 km/h, 6–6.5 driven gently. [motobenzyna 1ZR-FE](https://www.motobenzyna.pl/silniki/3589-silnik-1-6-16v-dual-vvt-i-1zr-fe.html). **[F]**
  - **Other owners:** 6.5 highway and more than 8 city. [forumsamochodowe](https://www.forumsamochodowe.pl/lpg-do-toyota-corolla-1-6-16v-dual-vvt-i-1zr-fe-vt119959.htm). **[F]**
  - **A counter-example:** a dead ignition coil (aftermarket BREMI) gave no DTC but caused a huge rise in fuel use and loss of power. It was found by swapping coils. [drive2 482415400684880315](https://www.drive2.ru/l/482415400684880315/). **[F]**

### 5.2 LPG
- **AutoCentrum owners:** 9.0–9.4 L/100 km. One reports 9.4 LPG vs 8.2 petrol, so LPG ≈ 1.15 × petrol. [autocentrum](https://www.autocentrum.pl/opinie-lpg/toyota/corolla/x/sedan/silnik-benzynowy-1.6-i-16v-vvt-i-124km-2007-2010/). **[F]**
- **Installer's figures for a Corolla 1.6 2008:** petrol 9.3 city and 6.0 highway; LPG **10.7 city and 6.9 highway**, so LPG ≈ 1.15 × petrol. [gaz-time.ua](https://gaz-time.ua/gallery/toyota/corolla-16-2008-kme). **[S]**
- **Russian owners:** a 42 L tank gives 330–360 km of range. The usable fill is about 34 L, so about 9.5–10.5 L/100 km. The fill figure is my derivation. [drive2 501645859054683134](https://www.drive2.ru/l/501645859054683134/). **[F/D]**

### 5.3 Verdict on 12 → 14 L/100 km
**[D]** The expected LPG-to-petrol ratio is about 1.15–1.25 by volume.
- **Mixed or summer driving:** petrol is 8–9.5 L/100 km, so expect **about 9.5–11.5 L/100 km of LPG**.
- **Winter city with short trips:** petrol is 12–13.5, so LPG can reach **14–16 L/100 km** and still be "normal" for that duty.

So a baseline of **12 L/100 km** is plausible for mostly city driving with an automatic.

A rise to **14 L/100 km (+17%)** under *the same* route and season is **not normal** and deserves diagnosis. Check in this order:
1. **Coolant temperature on OBD.** Is it holding 86–94 °C, or is the thermostat stuck open below 80 °C?
2. **Fuel trims on gas vs petrol** at matching rpm/load cells, to see whether the gas map has drifted.
3. **Reducer pressure stability and gas temperature.** Check the gas filters too.
4. **Misfire sources:** coils (1ZR coils do fail), spark plugs (1.1 mm gap, and LPG likes a slightly smaller gap), and a compression test for seat recession.
5. **MAF contamination.**
6. **Upstream O2 sensor aging.** A slow switching sensor gives rich bias; it can show as P0134 or a catalyst code.

A rise that happens only in winter and coincides with lower coolant temperature points first at the thermostat and the heater circuit.

---

## Open questions / conflicting info
1. **Upstream sensor type.**
   - Catalogue and owner evidence says narrowband 89465-12880 / -02310 for European and Russian cars.
   - toyota-club.net says A/F 89467 and lists A/F DTCs.
   - Resolve on the actual car by checking whether PID 0x14 switches, or by reading the part number on the sensor.
2. **OBD protocol for early (2007–2008) European cars:** K-line (ISO 9141-2 / KWP) or CAN? This is unconfirmed. Auto-detect it.
3. **Valve train claims.** The HLA and roller-rocker design is well supported. Treat the autohints "buckets" claim and the "8 adjustment discs" user story as errors.
   - Unknown: how much seat recession the HLA can absorb before valves stop closing. No public figure was found.
4. **Flashlube.** Polish databases say it is unnecessary; one English article says it is required. There is no controlled data.
5. **Idle and cruise reference values** (MAF g/s, load %, rpm at 90 km/h in 4th gear) are not from a 1ZR-FE Toyota repair-manual data list. The idle MAF of 1.5–2.5 g/s and the petrol idle injection time of 1.9–2.25 ms are forum or summary level. The 90 km/h numbers are derived.
6. **P0128 on the 1ZR-FE.** It was missing from the toyota-club DTC table, so a stuck-open thermostat may not trigger a MIL. Unverified.
7. **The exact petrol injector part number and flow for the 1ZR-FE** were not confirmed. The ZR-family 12-hole EV14 is assumed.
8. **Nozzle size.** The value depends on the injector brand's own table. The ALEX table suggests 2.2–2.5 mm at 1.0–1.3 bar. No 1ZR-specific installer figure was found, and lpg-forum.pl threads on the Corolla E15 1ZR-FE could not be reached.
9. **Prevalence of stuck thermostats** on the E150: anecdotal only. There are no statistics.
