# LPG (Stag) diagnostics & calibration toolkit

*English summary:* tools for checking and tuning a 4th-generation sequential LPG system (Stag QBOX controllers,
AC STAG software) with OBD data: an ELM327 logger (Windows/Python and Android), an analyzer that compares fuel
trims on gas vs petrol and points at the multiplier cells to change, an AC STAG `.osc` parser, and an Android app
that talks to the Stag Bluetooth interface (live monitor, trip log, numeric multiplier editor). Built and tested on
one car: Toyota Corolla E150 1.6 (1ZR-FE) with STAG-4 QBOX BASIC, firmware 2.14.2. Docs are in Ukrainian.

---

Набір інструментів для діагностики й калібровки послідовного ГБО 4-го покоління (Stag QBOX, програма AC STAG)
за даними OBD. Ідея: правду про калібровку показує бензиновий ECU — якщо в тих самих режимах на газу трими
STFT+LTFT інші, ніж на бензині, множник у цій зоні часу впорску треба правити.

Зроблено й перевірено на одній машині: **Toyota Corolla E150 1.6 (1ZR-FE), STAG-4 QBOX BASIC, прошивка 2.14.2,
AC STAG 0.64.4**, ELM327 v1.5. На інших авто й контролерах логер і аналізатор мають працювати (стандартні
PID OBD-II), а протокол Stag — не перевірено.

## Що тут є

| Шлях | Що там |
|---|---|
| `logger/` | ELM327-логер для Windows (Python), аналізатор, симулятор ELM + ECU, парсер `.osc`, `.bat`-ярлики |
| `android/` | Android-додаток: ELM-дашборд і запис поїздок + зв'язок зі Stag по Bluetooth (монітор, редактор множника) |
| `docs/01_AC_STAG_guide.md` | що і куди натискати в AC STAG (QBOX BASIC), резервна копія, карти Б/Г/D, осцилограф |
| `docs/02_drive_plan.md` | план поїздки для збору даних |
| `docs/03_android_logger_plan.md` | план і стан Android-логера |
| `docs/04_stag_link_plan.md` | зв'язок зі Stag без ноутбука, підслуховування обміну AC STAG ↔ Stag |
| `docs/05_stag_protocol.md` | розібраний протокол Stag (монітор, читання й запис множника) |
| `docs/img/acstag_demo/` | скріншоти AC STAG у демо-режимі з позначками |
| `research/lpg/` | теорія: калібровка Stag, діагностика ГБО, 1ZR-FE, OBD-логування |
| `stag/osc_format/FORMAT.md` | формат файлів осцилографа AC STAG |
| `.claude/skills/lpg-gbo-tuning/` | скіл для Claude Code: діагностика й калібровка ГБО з цими інструментами |
| `logs/`, `analysis/`, `stag/osc/`, `stag/backup/` | порожні: сюди йдуть ваші логи, звіти, записи `.osc` і копії `.set` (у `.gitignore`) |

## Логер (Windows)

Python 3.10+, пакети з `logger/requirements.txt` (`pyserial pandas matplotlib`).

| Ярлик | Що робить |
|---|---|
| `logger\run_sim.bat` | тест без машини (симулятор ELM + ECU) |
| `logger\probe.bat` | COM-порти, підключення до ELM, підтримувані PID і стан двигуна (коди, монітори, пропуски запалювання) |
| `logger\run_logger.bat --port COMx` | запис поїздки в `logs/` |
| `logger\analyze.bat` | аналіз останнього логу (+ записи `.osc` з `stag\osc\`, що перетинаються за часом) → `analysis/<лог>/report.md` |
| `python logger\stag_osc.py файл.osc` | `.osc` → CSV |
| `python logger\stag_sniff.py --real COMx --virt COMy` | підслуховування обміну AC STAG ↔ Stag через пару com0com (лише пересилає й пише лог) |

Клавіші під час запису: `g` газ · `b` бензин · `a`/`s` A/C увімк./вимк. · `h` траса · `c` місто ·
`i` холості · `m` позначка · `q` вихід.

Логер лише читає: сервіс 04 («стерти коди») не надсилається ніколи. Власний драйвер ELM327 пакує кілька PID
в один CAN-запит; обрив Bluetooth → перепідключення в той самий файл.

**Аналіз:** прогрітий двигун (ОЖ ≥ 75 °C), closed loop, без накатів, 20 с після перемикання палива відкинуто;
газ і бензин порівнюються лише в сусідніх за часом відрізках (±5 хв) — тому в поїздці чергуйте паливо
відрізками по 4–5 хв. Звіт: трими газ проти бензину за режимами й на сітці оберти × навантаження, таблиця
клітинок, упорядкованих за часом впорску бензину (вісь множника), з розрахованим k і % зміни точки;
лямбда, B1S2 за каталізатором, прогрів, A/C, тиск газу, оцінка витрати з MAF.

Як переносити трими в множник: `research/lpg/01_stag_calibration.md` і
`.claude/skills/lpg-gbo-tuning/references/calibration.md`. Коротко: k = (1 + TT_газ) / (1 + TT_бензин),
TT = (STFT + LTFT) / 100; перший крок — 60–80 % від k, правити у відсотках від поточного значення точки.

## Android-додаток (`android/`)

- **ELM327:** дашборд (трими, оберти, ОЖ, MAF, O2, напруга…), запис поїздок у ті самі файли, що й логер на ПК
  (`elm_*.csv` + `.log` + `.meta.json` + `.health.json`), теги газ/бензин, місто/траса, холості, A/C.
- **Stag (Bluetooth-інтерфейс контролера, замість StagMobile):** тиск газу, температура редуктора, Б, Г, Г/Б;
  перепідключається сам; монітор ~10 Гц пишеться в `<поїздка>.stag.csv`, `analyze.py` бере його замість `.osc`.
- **Множитель (Б-1):** прочитати карту, ввести числа, записати. Не більше ±10 % від поточної лінії за раз;
  перед записом карта читається ще раз і зберігається копія `stag_map_…_before.json`, після — читання назад
  і звірка. Можна додати точку (до 12) або прибрати (крім першої й останньої).
- **Демо Stag:** «Налашт.» → Stag → «Демо Stag» — емуляція контролера в додатку, щоб спробувати без машини.
- **Папка логів:** за замовчуванням `Download/car_logs`, можна вибрати іншу; «Поїздки» → кілька або «Усе за
  сьогодні» → один zip.

Готовий APK — у [Releases](../../releases) (debug-підпис). Збирання: JDK 21 + Android SDK,
`cd android` → `gradlew.bat assembleDebug` → `app\build\outputs\apk\debug\app-debug.apk`.
Тести: `gradlew.bat testDebugUnitTest` (кадри Stag звірені з записом обміну AC STAG).

На Xiaomi (MIUI): батарея «Без обмежень» і «Автозапуск» для додатку, інакше система може зупинити запис.

## Застереження

- **Запис у контролер — на ваш ризик.** Перед будь-якою правкою: копія налаштувань в AC STAG
  («Инструменты → Сохранить настройки»). Протокол Stag розібраний з запису обміну на одній машині й одній
  прошивці; інші моделі й прошивки можуть відповідати інакше — тоді додаток покаже помилку й нічого не запише,
  але гарантій немає.
- Бідна суміш на газу під навантаженням шкодить клапанам. Якщо сумніваєтесь — краще трохи багатше.
- Bluetooth-інтерфейс Stag має стандартний PIN (`1234` / `0000`); після сесії його краще вийняти.
- Проєкт не пов'язаний з AC S.A. (Stag); назви — їхні торгові марки.

## Ліцензія

MIT — див. [LICENSE](LICENSE).
