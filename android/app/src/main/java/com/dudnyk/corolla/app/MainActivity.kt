package com.dudnyk.corolla.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import android.os.Environment
import android.provider.DocumentsContract
import com.dudnyk.corolla.stag.DemoStag
import com.dudnyk.corolla.stag.MultiplierMap
import com.dudnyk.corolla.stag.StagPhase
import com.dudnyk.corolla.stag.StagState
import java.io.File
import com.dudnyk.corolla.obd.LiveDiagnostics
import com.dudnyk.corolla.obd.Mark
import com.dudnyk.corolla.obd.Phase
import com.dudnyk.corolla.obd.RecorderStatus
import java.util.Locale

private val Green = Color(0xFF4CAF50)
private val Amber = Color(0xFFFFB300)
private val Red = Color(0xFFE53935)
private val TileBg = Color(0xFF1E1F22)
private val Bg = Color(0xFF111214)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // dashboard in the car
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // contentColor explicitly: for a colour outside the scheme Surface falls back to black text
                Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Color.White) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val need = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) perms.launch(need.toTypedArray())
    }
    var tab by remember { mutableIntStateOf(0) }
    val status by Hub.status.collectAsState()
    val hints by Hub.hints.collectAsState()
    val running by Hub.running.collectAsState()
    val stag by Hub.stag.collectAsState()

    Scaffold(containerColor = Bg, contentColor = Color.White) { pad ->
        Column(Modifier.padding(pad)) {
            TopBar(status, running)
            // tab labels in dp: with a large system font "Дашборд" / "Налашт." would not fit a quarter of the width
            val tabSp = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toSp() }
            TabRow(selectedTabIndex = tab) {
                listOf("Дашборд", "Stag", "Поїздки", "Налашт.").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1, softWrap = false, fontSize = tabSp) })
                }
            }
            when (tab) {
                0 -> Dashboard(status, hints, running, stag)
                1 -> StagScreen(stag, running)
                2 -> TripsScreen(running)
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun TopBar(st: RecorderStatus?, running: Boolean) {
    val ctx = LocalContext.current
    // status on one full-width line above the buttons, so the header stays two short rows tall
    Column(Modifier.fillMaxWidth().padding(12.dp, 6.dp)) {
        val phase = if (!running) "відключено" else when (st?.phase) {
            Phase.RECORDING -> "● запис"
            Phase.LIVE -> "дашборд (без запису)"
            Phase.RECONNECTING -> if (st.recording) "перепідключення… (запис триває)" else "перепідключення…"
            Phase.STOPPED -> "відключено"
            else -> "підключення…"
        }
        val color = when {
            !running -> Color.Gray
            st?.phase == Phase.RECORDING -> Red
            st?.phase == Phase.LIVE -> Green
            else -> Amber
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(phase, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color, maxLines = 1, softWrap = false)
            if (st != null && running) {
                val line = if (st.recording) {
                    String.format(Locale.ROOT, "%.0f с · %d рядків · %.1f Гц · %s", st.tripS, st.rows, st.hz, st.baseName)
                } else {
                    String.format(Locale.ROOT, "%.1f Гц · підключено %.0f с", st.hz, st.sessionS)
                }
                Text("  $line", fontSize = 12.sp, color = Color.Gray, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            if (running) {
                val recording = st?.recording == true
                Button(onClick = { Hub.setRecording(!recording) }, modifier = Modifier.weight(1f).padding(end = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) Red else Color(0xFF3A3B3F))) {
                    Text(if (recording) "■ Стоп запису" else "● Запис", maxLines = 1, color = Color.White)
                }
                OutlinedButton(onClick = { LoggerService.stop(ctx) }, modifier = Modifier.weight(1f)) { Text("Відключити", maxLines = 1) }
            } else {
                Button(onClick = { connect(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Підключити") }
            }
        }
    }
}

private fun connect(ctx: Context) {
    val s = Settings(ctx)
    if (s.source == Settings.SOURCE_BT && s.btAddress.isEmpty() && s.stagAddress.isEmpty()) {
        android.widget.Toast.makeText(ctx, "Вибери ELM і/або Stag у «Налаштуваннях»", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        android.widget.Toast.makeText(ctx, "Потрібен дозвіл «Пристрої поблизу» (Bluetooth)", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    LoggerService.start(ctx)
}

// ---------------------------------------------------------------- dashboard
@Composable
private fun Dashboard(st: RecorderStatus?, hints: List<LiveDiagnostics.Hint>, running: Boolean, stag: StagState?) {
    val v = st?.values.orEmpty()
    fun d(k: String) = (v[k] as? Number)?.toDouble()
    val stft = d("stft")
    val ltft = d("ltft")
    val sum = if (stft != null && ltft != null) stft + ltft else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row {
            Tile("STFT", fmtSigned(stft), "%", trimColor(stft))
            Tile("LTFT", fmtSigned(ltft), "%", trimColor(ltft))
            Tile("СУМА", fmtSigned(sum), "%", trimColor(sum), big = true)
        }
        Row {
            Tile("Оберти", fmt(d("rpm"), 0), "об/хв")
            Tile("Швидкість", fmt(d("speed"), 0), "км/год")
            Tile("Навантаж.", fmt(d("load"), 0), "%")
        }
        Row {
            val ect = d("ect")
            Tile("ОЖ", fmt(ect, 0), "°C", when {
                ect == null -> Color.White; ect > 105 -> Red; ect < 75 -> Amber; else -> Green })
            val atf = d("atf_c")
            Tile("ATF (експ.)", fmt(atf, 0), "°C", if (atf != null && atf > 110) Red else Color.White)
            val volt = d("elm_v")
            val rpm = d("rpm")
            Tile("Напруга", fmt(volt, 1), "В", when {
                volt == null -> Color.White
                volt > 15.0 -> Red
                rpm != null && rpm > 500 && volt < 13.2 -> Amber
                else -> Color.White })
        }
        Row {
            Tile("MAF", fmt(d("maf"), 1), "г/с")
            Tile("Повітря", fmt(d("iat"), 0), "°C")
            Tile("Випередж.", fmt(d("timing"), 0), "°")
        }
        Row {
            Tile("O2 B1S1", fmt(d("o2s1_v"), 2), "В")
            Tile("O2 B1S2", fmt(d("o2s2_v"), 2), "В")
            Tile("Режим", (v["fuel_status"] as? String) ?: "--", "", if (v["fuel_status"] == "CL") Green else Color.White)
        }
        stag?.let { StagTiles(it) }
        Spacer(Modifier.height(8.dp))
        Markers(st, running)
        Spacer(Modifier.height(8.dp))
        for (h in hints) {
            val c = when (h.level) { LiveDiagnostics.Level.ALERT -> Red; LiveDiagnostics.Level.WARN -> Amber; else -> Color.Gray }
            Text("• " + h.text, color = c, fontSize = 14.sp, modifier = Modifier.padding(4.dp, 2.dp))
        }
        if (hints.isNotEmpty()) {
            Text("Підказки — куди дивитись, не діагноз. Повний розбір — analyze.py по файлу поїздки.",
                color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
        }
        if (st != null) {
            Spacer(Modifier.height(8.dp))
            Text("ELM: ${st.elm}  ${st.protocol}", color = Color.Gray, fontSize = 11.sp)
            for (e in st.lastEvents.takeLast(6)) {
                Text(e, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String, unit: String, color: Color = Color.White, big: Boolean = false) {
    Column(Modifier.weight(1f).padding(3.dp).background(TileBg, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            // one line always: long values (OL_cold, 4-digit rpm) get a smaller font instead of wrapping
            val len = value.length + unit.length / 2.0
            val size = when {
                len > 6.5 -> 18.sp
                len > 6 -> 20.sp
                len > 5.5 -> 22.sp
                big -> 34.sp
                else -> 28.sp
            }
            Text(value, color = color, fontSize = size, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
            if (unit.isNotEmpty()) Text(" $unit", color = Color.Gray, fontSize = 12.sp, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun Markers(st: RecorderStatus?, running: Boolean) {
    val state = st?.state.orEmpty()
    val idle = state["idle"] == "on"
    val ac = state["ac"] == "on"
    Column {
        Text("Теги: паливо і дорога — одне з двох; холості й A/C — окремі перемикачі", color = Color.Gray, fontSize = 12.sp)
        Row {
            MarkButton("ГАЗ", Mark.GAS, state["fuel"] == "gas", running)
            MarkButton("БЕНЗИН", Mark.PETROL, state["fuel"] == "petrol", running)
        }
        Row {
            MarkButton("Місто", Mark.CITY, state["road"] == "city", running)
            MarkButton("Траса", Mark.HIGHWAY, state["road"] == "highway", running)
        }
        Row {
            MarkButton("Холості", if (idle) Mark.IDLE_OFF else Mark.IDLE_ON, idle, running)
            MarkButton("A/C", if (ac) Mark.AC_OFF else Mark.AC_ON, ac, running)
            MarkButton("Позначка", Mark.GENERIC, false, running)
        }
    }
}

@Composable
private fun RowScope.MarkButton(text: String, mark: Mark, active: Boolean, enabled: Boolean) {
    val mod = Modifier.weight(1f).padding(3.dp).height(56.dp)
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
    if (active) {
        Button(onClick = { Hub.mark(mark) }, enabled = enabled, modifier = mod, contentPadding = pad) { Text(text, fontSize = 16.sp, maxLines = 1, softWrap = false) }
    } else {
        OutlinedButton(onClick = { Hub.mark(mark) }, enabled = enabled, modifier = mod, contentPadding = pad,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text(text, fontSize = 16.sp, maxLines = 1, softWrap = false) }
    }
}

private fun fmt(x: Double?, digits: Int) = x?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "--"
private fun fmtSigned(x: Double?) = x?.let { String.format(Locale.ROOT, "%+.1f", it) } ?: "--"
private fun trimColor(x: Double?) = when {
    x == null -> Color.White
    Math.abs(x) < 5 -> Green
    Math.abs(x) < 10 -> Amber
    else -> Red
}

// ---------------------------------------------------------------- stag
private fun stagPhaseText(st: StagState?): Pair<String, Color> = when (st?.phase) {
    null, StagPhase.OFF -> "Stag: не підключено" to Color.Gray
    StagPhase.CONNECTING -> "Stag: підключення…" to Amber
    StagPhase.RECONNECTING -> "Stag: зв'язок втрачено — перепідключення…" to Amber
    StagPhase.LIVE -> (if (st.monitor != null) "Stag: на зв'язку" else "Stag: чекаю відповіді…") to
        (if (st.monitor != null) Green else Amber)
    StagPhase.STOPPED -> "Stag: відключено" to Color.Gray
}

private fun stagStatusText(status: Int?) = when (status) {
    null -> "--"; 10 -> "ГАЗ"; 4 -> "БЕНЗИН"; 5, 8 -> "перехід"; else -> "код $status"
}

@Composable
private fun StagTiles(st: StagState) {
    val m = st.monitor
    val (text, color) = stagPhaseText(st)
    Text(text + if (st.reconnects > 0) " · перепідключень ${st.reconnects}" else "", color = color, fontSize = 12.sp,
        modifier = Modifier.padding(4.dp, 6.dp, 4.dp, 0.dp))
    Row {
        Tile("Stag", stagStatusText(m?.status), "", if (m?.onGas == true) Green else Color.White)
        Tile("Тиск газу", fmt(m?.gasPBar, 2), "бар", if (m != null && m.gasPBar < 0.9) Red else Color.White)
        Tile("Редуктор", m?.reducerTempC?.toString() ?: "--", "°C")
    }
    Row {
        Tile("Б", fmt(m?.petrolAvg, 2), "мс")
        Tile("Г", fmt(m?.gasAvg, 2), "мс")
        Tile("Г/Б", fmt(m?.ratio, 2), "")
    }
}

private class EditRow(val axisMs: Double, val isNew: Boolean, text: String) {
    var text by mutableStateOf(text)
}

private fun rowsOf(map: MultiplierMap) =
    map.axisMs.indices.map { EditRow(map.axisMs[it], false, String.format(Locale.ROOT, "%.2f", map.values[it])) }

@Composable
private fun StagScreen(st: StagState?, running: Boolean) {
    val ctx = LocalContext.current
    val map = st?.map
    // edited points: axis + value text; reset whenever a new map arrives from the controller
    val rows = remember(map) { mutableStateListOf<EditRow>().apply { map?.let { addAll(rowsOf(it)) } } }
    var newPoint by remember(map) { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<List<Double>, List<Double>>?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        if (Settings(ctx).stagAddress.isEmpty()) {
            Text("Stag не вибрано: «Налашт.» → Stag (Bluetooth-інтерфейс, спарений у телефоні).", color = Amber)
            return@Column
        }
        if (!running || st == null) {
            Text("Натисни «Підключити» вгорі (ELM і Stag підключаються разом).", color = Color.Gray)
            return@Column
        }
        StagTiles(st)
        Text(String.format(Locale.ROOT, "%.1f Гц · %s", st.hz, st.label) +
            (if (st.csvRows > 0) " · у поїздку записано ${st.csvRows}" else ""), color = Color.Gray, fontSize = 11.sp)
        st.errorsHex?.let { if (it.replace("00", "").isNotBlank()) Text("Блок помилок Stag: $it", color = Amber, fontSize = 12.sp) }

        Spacer(Modifier.height(12.dp))
        Text("Множитель (Б-1)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Вісь — час впорску бензину Б. За раз ≤ ±10 % від лінії; перед записом додаток читає карту ще раз " +
            "і зберігає копію stag_map_…_before.json, після — читає назад і звіряє.", color = Color.Gray, fontSize = 12.sp)
        Row {
            OutlinedButton(onClick = { Hub.stagReadMap() }, enabled = st.phase == StagPhase.LIVE && !st.mapBusy,
                modifier = Modifier.padding(end = 8.dp)) { Text("Прочитати") }
            if (map != null) {
                OutlinedButton(onClick = { rows.clear(); rows.addAll(rowsOf(map)) }, enabled = !st.mapBusy) { Text("Скинути правки") }
            }
        }
        if (st.mapBusy) Text("Виконується…", color = Amber)
        if (st.mapMessage.isNotEmpty()) Text(st.mapMessage,
            color = if (st.mapMessage.startsWith("Помилка") || st.mapMessage.startsWith("Зв'язок")) Red else Green, fontSize = 13.sp)
        if (map != null) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Б, мс", Modifier.weight(1f), color = Color.Gray, fontSize = 12.sp)
                Text("зараз", Modifier.weight(0.9f), color = Color.Gray, fontSize = 12.sp)
                Text("нове", Modifier.weight(1.3f), color = Color.Gray, fontSize = 12.sp)
                Text("Δ", Modifier.weight(0.9f), color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.width(36.dp))
            }
            val parsed = rows.map { it.text.replace(',', '.').trim().toDoubleOrNull() }
            rows.forEachIndexed { i, r ->
                val ref = map.valueAt(r.axisMs)
                val nv = parsed[i]
                val d = nv?.let { (it / ref - 1) * 100 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(r.axisMs, 3), Modifier.weight(1f), fontFamily = FontFamily.Monospace, color = if (r.isNew) Amber else Color.White)
                    Text(if (r.isNew) "нова" else fmt(ref, 2), Modifier.weight(0.9f), fontFamily = FontFamily.Monospace,
                        color = if (r.isNew) Amber else Color.White)
                    OutlinedTextField(r.text, { r.text = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.3f).padding(end = 6.dp))
                    Text(d?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "?", Modifier.weight(0.9f),
                        color = when { d == null -> Red; Math.abs(d) < 0.05 -> Color.Gray; Math.abs(d) > 10.0 -> Red; else -> Amber },
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    val edge = i == 0 || i == rows.lastIndex
                    if (edge) Spacer(Modifier.width(36.dp)) else
                        OutlinedButton(onClick = { rows.removeAt(i) }, modifier = Modifier.width(36.dp).height(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("×", color = Red) }
                }
            }
            // add a point: its value starts on the current line, so adding alone changes nothing
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(newPoint, { newPoint = it }, singleLine = true, label = { Text("нова точка, Б мс") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).padding(end = 8.dp))
                val ms = newPoint.replace(',', '.').trim().toDoubleOrNull()?.let { MultiplierMap.msToRaw(it) * 0.005 }
                val addProblem = when {
                    ms == null -> null
                    ms <= rows.first().axisMs || ms >= rows.last().axisMs -> "між ${fmt(rows.first().axisMs, 2)} і ${fmt(rows.last().axisMs, 2)} мс"
                    rows.any { Math.abs(it.axisMs - ms) < MultiplierMap.MIN_GAP_MS - 1e-9 } -> "надто близько до наявної точки (< ${MultiplierMap.MIN_GAP_MS} мс)"
                    rows.size >= MultiplierMap.MAX_POINTS -> "не більше ${MultiplierMap.MAX_POINTS} точок"
                    else -> null
                }
                OutlinedButton(onClick = {
                    val at = rows.indexOfFirst { it.axisMs > ms!! }
                    rows.add(at, EditRow(ms!!, true, String.format(Locale.ROOT, "%.2f", map.valueAt(ms))))
                    newPoint = ""
                }, enabled = ms != null && addProblem == null) { Text("+ Додати") }
                if (addProblem != null) Text(addProblem, color = Red, fontSize = 12.sp)
            }
            val axis = rows.map { it.axisMs }
            val values = parsed.takeIf { p -> p.all { it != null } }?.map { Math.round(it!! * 100) / 100.0 }
            val problem = if (values == null) "не всі значення — числа" else MultiplierMap.validate(map, axis, values)
            if (problem != null && problem != "нічого не змінено") Text(problem, color = Red, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { confirm = axis to values!! }, enabled = problem == null && st.phase == StagPhase.LIVE && !st.mapBusy,
                colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Записати в Stag…") }
            Text("Писати на місці або на холостих; не натискати під час розгону. Першу й останню точки не прибрати. " +
                "Автокалібровка, тиск, інші карти — лише в AC STAG.", color = Color.Gray, fontSize = 11.sp)
        }
    }
    confirm?.let { (axis, values) ->
        val m = map ?: return@let
        val curRaw = m.axisRaw()
        val newRaw = axis.map { MultiplierMap.msToRaw(it) }
        val lines = mutableListOf<String>()
        for (i in axis.indices) {
            val ref = m.valueAt(axis[i])
            when {
                newRaw[i] !in curRaw -> lines += String.format(Locale.ROOT, "+ нова точка %.3f мс: %.2f (лінія %.2f, %+.1f %%)",
                    axis[i], values[i], ref, (values[i] / ref - 1) * 100)
                Math.round(values[i] * 100) != Math.round(ref * 100) -> lines += String.format(Locale.ROOT, "%.3f мс: %.2f → %.2f (%+.1f %%)",
                    axis[i], ref, values[i], (values[i] / ref - 1) * 100)
            }
        }
        for (i in m.axisMs.indices) if (curRaw[i] !in newRaw)
            lines += String.format(Locale.ROOT, "− прибрати точку %.3f мс (%.2f)", m.axisMs[i], m.values[i])
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Записати множник у Stag?") },
            text = { Text(lines.joinToString("\n") + "\n\nТочок: ${m.n} → ${axis.size}. Копія поточної карти збережеться перед записом.") },
            confirmButton = { Button(onClick = {
                Hub.stagWriteMap(values, if (newRaw == curRaw) null else axis); confirm = null
            }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Записати") } },
            dismissButton = { OutlinedButton(onClick = { confirm = null }) { Text("Скасувати") } },
        )
    }
}

// ---------------------------------------------------------------- trips
@Composable
private fun TripsScreen(running: Boolean) {
    val ctx = LocalContext.current
    val status by Hub.status.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val dir = LoggerService.logsDir(ctx)
    val trips = remember(running, status?.rows?.div(200), refresh) { Trips.list(dir) }
    val selected = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Папка: ${dir.path}", color = Color.Gray, fontSize = 11.sp)
        if (!LoggerService.sharedStorageOk()) Text("Нема дозволу «Доступ до всіх файлів» — пишу в папку додатку. " +
            "Дай дозвіл у «Налашт.» → Папка логів, тоді — Download/car_logs.", color = Amber, fontSize = 12.sp)
        Text("Архів → розпакувати в logs/ репозиторію, далі logger\\analyze.bat.", color = Color.Gray, fontSize = 11.sp)
        Row {
            val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            Button(onClick = { Trips.share(ctx, trips.filter { it.day == today }) }, enabled = trips.any { it.day == today },
                modifier = Modifier.padding(end = 6.dp)) { Text("Усе за сьогодні") }
            Button(onClick = { Trips.share(ctx, trips.filter { it.base in selected }) }, enabled = selected.isNotEmpty(),
                modifier = Modifier.padding(end = 6.dp)) { Text("Вибрані (${selected.size})") }
            OutlinedButton(onClick = { refresh++ }) { Text("↻") }
        }
        Spacer(Modifier.height(8.dp))
        if (trips.isEmpty()) Text("Записів ще нема", color = Color.Gray)
        for (t in trips) {
            val current = running && status?.recording == true && status?.baseName == t.base
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(TileBg, RoundedCornerShape(8.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t.base in selected, onCheckedChange = { if (it) selected.add(t.base) else selected.remove(t.base) })
                Column(Modifier.weight(1f)) {
                    Text(t.base, fontFamily = FontFamily.Monospace, color = if (t.isMap) Amber else Color.White, fontSize = 13.sp)
                    Text("${t.files.size} файли · ${t.bytes / 1024} КБ" + (if (t.hasStag) " · Stag" else "") +
                        if (current) " · пишеться" else "", color = Color.Gray, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { Trips.share(ctx, listOf(t)) }) { Text("↗") }
            }
        }
    }
}

/** `content://…/tree/primary:Download/car_logs` -> `/storage/emulated/0/Download/car_logs`. */
private fun treeUriToPath(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = id.split(":", limit = 2)
    val vol = parts[0]
    val rel = parts.getOrElse(1) { "" }
    val root = if (vol == "primary") Environment.getExternalStorageDirectory().path else "/storage/$vol"
    return if (rel.isEmpty()) root else "$root/$rel"
}

// ---------------------------------------------------------------- settings
@SuppressLint("MissingPermission")
@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    var source by remember { mutableStateOf(s.source) }
    var address by remember { mutableStateOf(s.btAddress) }
    var stagAddress by remember { mutableStateOf(s.stagAddress) }
    var host by remember { mutableStateOf(s.tcpHost) }
    var port by remember { mutableStateOf(s.tcpPort.toString()) }
    var silent by remember { mutableStateOf(s.silentStopMin.toString()) }
    var logDir by remember { mutableStateOf(s.logDir) }
    var filesOk by remember { mutableStateOf(LoggerService.sharedStorageOk()) }
    var moved by remember { mutableStateOf("") }
    val granted = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val devices = remember(granted) {
        if (!granted) emptyList() else
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices
                ?.map { (it.name ?: "?") to it.address }?.sortedBy { if (it.first == "OBDII") 0 else 1 }.orEmpty()
    }
    LaunchedEffect(devices) {
        if (address.isEmpty()) devices.firstOrNull { it.first == "OBDII" }?.let { address = it.second; s.btAddress = it.second }
    }
    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUriToPath(it) }?.let { logDir = it; s.logDir = it }
    }
    LaunchedEffect(Unit) {
        // the «All files access» screen returns without a result; re-check while this screen is open
        while (true) { filesOk = LoggerService.sharedStorageOk(); kotlinx.coroutines.delay(1000) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("ELM327 (OBD)", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = source == Settings.SOURCE_BT, onClick = { source = Settings.SOURCE_BT; s.source = source })
            Text("ELM327 по Bluetooth")
        }
        if (!granted) Text("Нема дозволу «Пристрої поблизу» — дай його в налаштуваннях додатку.", color = Amber, fontSize = 13.sp)
        if (granted && devices.isEmpty()) Text("Спарених пристроїв нема. Спар ELM (OBDII, PIN 1234) і Stag у налаштуваннях Bluetooth телефона.",
            color = Amber, fontSize = 13.sp)
        Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = address.isEmpty(), onClick = { address = ""; s.btAddress = "" })
            Text("без ELM (лише Stag)", fontSize = 14.sp)
        }
        for ((name, addr) in devices) {
            Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = address == addr, onClick = { address = addr; s.btAddress = addr })
                Text("$name  $addr", fontSize = 14.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = source == Settings.SOURCE_TCP, onClick = { source = Settings.SOURCE_TCP; s.source = source })
            Text("Симулятор (logger/sim.py --tcp на ПК)")
        }
        if (source == Settings.SOURCE_TCP) {
            Row(Modifier.padding(start = 24.dp)) {
                OutlinedTextField(host, { host = it; s.tcpHost = it.trim() }, label = { Text("IP ПК") }, modifier = Modifier.weight(2f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(port, { port = it; it.toIntOrNull()?.let { p -> s.tcpPort = p } }, label = { Text("порт") },
                    modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Stag (Bluetooth-інтерфейс газового контролера)", fontWeight = FontWeight.Bold)
        Text("Пароль спарювання зазвичай 1234 або 0000. StagMobile і AC STAG мають бути відключені — інтерфейс тримає одне з'єднання.",
            fontSize = 12.sp, color = Color.Gray)
        Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = stagAddress.isEmpty(), onClick = { stagAddress = ""; s.stagAddress = "" })
            Text("без Stag", fontSize = 14.sp)
        }
        Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = stagAddress == DemoStag.ADDRESS, onClick = { stagAddress = DemoStag.ADDRESS; s.stagAddress = DemoStag.ADDRESS })
            Text("Демо Stag (без машини, карта в пам'яті)", fontSize = 14.sp, color = Amber)
        }
        if (stagAddress == DemoStag.ADDRESS) Text("Для демо в ELM вибери «без ELM», далі «Підключити» і вкладка Stag. " +
            "У справжній Stag демо нічого не пише.", fontSize = 12.sp, color = Amber, modifier = Modifier.padding(start = 24.dp))
        for ((name, addr) in devices) {
            if (addr == address) continue
            Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = stagAddress == addr, onClick = { stagAddress = addr; s.stagAddress = addr })
                Text("$name  $addr", fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Папка логів", fontWeight = FontWeight.Bold)
        Text(LoggerService.logsDir(ctx).path + if (filesOk) "" else " (папка додатку)", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        if (!filesOk) {
            Text("Щоб писати в Download/car_logs (видно у файловому менеджері й месенджерах), потрібен дозвіл «Доступ до всіх файлів». " +
                "Поки його нема — пишу в папку додатку.", color = Amber, fontSize = 13.sp)
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")))
            }) { Text("Дати доступ до файлів") }
        } else {
            Row {
                OutlinedButton(onClick = { pickDir.launch(null) }, modifier = Modifier.padding(end = 8.dp)) { Text("Вибрати папку") }
                OutlinedButton(onClick = { logDir = ""; s.logDir = "" }, enabled = logDir.isNotEmpty()) { Text("Download/car_logs") }
            }
            val old = LoggerService.appLogsDir(ctx)
            val oldCount = remember(moved, logDir) { Trips.list(old).size }
            if (oldCount > 0) {
                OutlinedButton(onClick = {
                    val n = Trips.moveAll(old, LoggerService.logsDir(ctx))
                    moved = "Перенесено файлів: $n"
                }) { Text("Перенести старі записи ($oldCount) сюди") }
            }
            if (moved.isNotEmpty()) Text(moved, color = Green, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(silent, { silent = it; it.toIntOrNull()?.let { m -> s.silentStopMin = m } },
            label = { Text("Відключитись, якщо ECU мовчить N хв (0 = лише вручну)") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            var auto by remember { mutableStateOf(s.recordOnConnect) }
            Checkbox(checked = auto, onCheckedChange = { auto = it; s.recordOnConnect = it })
            Text("Писати лог одразу після підключення")
        }

        Spacer(Modifier.height(16.dp))
        Text("Фонова робота (MIUI)", fontWeight = FontWeight.Bold)
        Text("Щоб запис не обривався з вимкненим екраном: батарея — «Без обмежень», і ввімкнений «Автозапуск».",
            fontSize = 13.sp, color = Color.Gray)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        Text(if (ignoring) "Оптимізація батареї: вимкнена ✓" else "Оптимізація батареї: увімкнена", color = if (ignoring) Green else Amber)
        Row {
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")))
            }, modifier = Modifier.padding(end = 8.dp)) { Text("Батарея") }
            OutlinedButton(onClick = { openAutostart(ctx) }) { Text("Автозапуск") }
        }
        Spacer(Modifier.height(16.dp))
        Text("ECU лише читається: додаток не стирає коди (сервіс 04), команди ELM перевіряються білим списком. " +
            "У Stag додаток пише лише множник (вкладка Stag) — і лише після підтвердження.",
            fontSize = 12.sp, color = Color.Gray)
        Box(Modifier.height(24.dp))
    }
}

/** MIUI autostart screen; on other phones - the app's system settings page. */
private fun openAutostart(ctx: Context) {
    val miui = Intent().setComponent(ComponentName("com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    try {
        ctx.startActivity(miui)
    } catch (_: Exception) {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }
}
