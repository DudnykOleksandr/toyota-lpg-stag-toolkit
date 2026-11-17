package com.dudnyk.corolla.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import com.dudnyk.corolla.obd.LiveDiagnostics
import com.dudnyk.corolla.obd.Mark
import com.dudnyk.corolla.obd.Phase
import com.dudnyk.corolla.obd.RecorderStatus
import com.dudnyk.corolla.obd.TcpTransport
import com.dudnyk.corolla.obd.Transport
import com.dudnyk.corolla.obd.TripRecorder
import com.dudnyk.corolla.stag.DemoStag
import com.dudnyk.corolla.stag.StagSession
import com.dudnyk.corolla.stag.StagState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/** Shared between the service and the UI (one process). */
object Hub {
    val status = MutableStateFlow<RecorderStatus?>(null)
    val hints = MutableStateFlow<List<LiveDiagnostics.Hint>>(emptyList())
    val running: StateFlow<Boolean> get() = _running
    internal val _running = MutableStateFlow(false)
    @Volatile internal var recorder: TripRecorder? = null
    val stag = MutableStateFlow<StagState?>(null)
    @Volatile internal var stagSession: StagSession? = null

    fun mark(m: Mark) { recorder?.mark(m) }

    fun stagReadMap() { stagSession?.readMap() }
    fun stagWriteMap(values: List<Double>, axis: List<Double>? = null) { stagSession?.writeMap(values, axis) }

    /** Trip files on/off; the connection and the dashboard keep running. */
    fun setRecording(on: Boolean) { recorder?.setRecording(on) }
}

/**
 * Foreground service that runs one [TripRecorder] session on its own thread: connected = dashboard,
 * trip files only while recording is on. A partial wake lock keeps the CPU polling with the screen
 * off; the notification shows trims and has a Disconnect action.
 */
class LoggerService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { Hub.recorder?.stop(); Hub.stagSession?.stop() }
            ACTION_START -> if (!Hub._running.value) start()
            ACTION_RECORD_ON -> Hub.setRecording(true)
            ACTION_RECORD_OFF -> Hub.setRecording(false)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val settings = Settings(this)
        createChannel()
        startForeground(NOTIF_ID, notification("Підключення…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "corolla:logger").also { it.acquire(12 * 3600_000L) }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val dir = logsDir(this)

        val sim = settings.source == Settings.SOURCE_TCP
        val useElm = sim || settings.btAddress.isNotEmpty()
        val demoStag = settings.stagAddress == DemoStag.ADDRESS
        val useStag = settings.stagAddress.isNotEmpty() && (!sim || demoStag)
        var recRef: TripRecorder? = null          // transport and Stag log lines go to the recorder's log

        val stag = if (useStag) {
            val address = settings.stagAddress
            StagSession(
                linkFactory = {
                    if (demoStag) DemoStag() else BluetoothTransport(adapter.getRemoteDevice(address), preferred = settings.stagMethod,
                        onConnected = { settings.stagMethod = it }, log = { m -> recRef?.log("STAG $m") ?: Unit },
                        hint = "Stag interface powered (ignition on)? StagMobile or the laptop connected to it?")
                },
                log = { m -> recRef?.log(m) ?: Unit },
                onState = { Hub.stag.value = it },
                // demo map backups stay out of the real logs folder
                mapDir = { if (demoStag) File(cacheDir, "demo_maps") else logsDir(this) },
            )
        } else null
        Hub.stagSession = stag

        val rec = if (useElm) {
            val factory: () -> Transport = if (sim) {
                val host = settings.tcpHost
                val port = settings.tcpPort
                { TcpTransport(host, port) }
            } else {
                val address = settings.btAddress
                {
                    BluetoothTransport(adapter.getRemoteDevice(address), preferred = settings.btMethod,
                        onConnected = { settings.btMethod = it }, log = { recRef?.log(it) })
                }
            }
            val diag = LiveDiagnostics()
            TripRecorder(
                dir = dir,
                transportFactory = factory,
                options = TripRecorder.Options(sim = sim, silentStopMin = settings.silentStopMin.toDouble(),
                    recordFromStart = settings.recordOnConnect),
                onStatus = { st ->
                    diag.feed(st.values, st.state["fuel"] ?: "unknown")
                    Hub.status.value = st
                    Hub.hints.value = diag.hints(st.values)
                    maybeNotify(st)
                },
                tripListener = { base -> if (base == null) stag?.stopCsv() else stag?.startCsv(File(base.path + ".stag.csv")) },
                stagFuel = {
                    stag?.fresh()?.let { m -> when (m.status) { 10 -> "gas"; 4 -> "petrol"; else -> null } }
                },
            )
        } else null
        recRef = rec
        Hub.recorder = rec
        Hub._running.value = true

        val workers = listOfNotNull(
            rec?.let { r -> thread(name = "trip-recorder") {
                try { r.run() } catch (e: Exception) { r.log("recorder crashed: $e") }
                // without the ELM the session ends; the Stag link goes with it
                stag?.stop()
            } },
            stag?.let { st -> thread(name = "stag-session") {
                try { st.run() } catch (e: Exception) { recRef?.log("STAG session crashed: $e") }
            } },
        )
        thread(name = "logger-service-wait") {
            workers.forEach { it.join() }
            Hub.recorder = null
            Hub.stagSession = null
            Hub._running.value = false
            wakeLock?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun maybeNotify(st: RecorderStatus) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 5000 && st.phase != Phase.STOPPED) return
        lastNotify = now
        val v = st.values
        fun f(k: String) = (v[k] as? Number)?.let { String.format(Locale.ROOT, "%+.1f", it.toDouble()) } ?: "--"
        val trims = "STFT ${f("stft")}  LTFT ${f("ltft")}  ECT ${v["ect"] ?: "--"}"
        val text = when (st.phase) {
            Phase.RECORDING -> "● запис · $trims · ${st.rows} рядків"
            Phase.LIVE -> "дашборд · $trims"
            Phase.RECONNECTING -> "Зв'язок втрачено — перепідключення…" + if (st.recording) " (запис триває)" else ""
            Phase.STOPPED -> "Відключено"
            else -> "Підключення до ELM…"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, st.recording))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Запис поїздки", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(text: String, recording: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, LoggerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val rec = PendingIntent.getService(this, 2, Intent(this, LoggerService::class.java)
            .setAction(if (recording) ACTION_RECORD_OFF else ACTION_RECORD_ON), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (recording) "Corolla: запис" else "Corolla: дашборд")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (recording) "Стоп запису" else "Запис", rec).build())
            .addAction(Notification.Action.Builder(null, "Відключити", stop).build())
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dudnyk.corolla.START"
        const val ACTION_STOP = "com.dudnyk.corolla.STOP"
        const val ACTION_RECORD_ON = "com.dudnyk.corolla.RECORD_ON"
        const val ACTION_RECORD_OFF = "com.dudnyk.corolla.RECORD_OFF"
        private const val CHANNEL = "trip"
        private const val NOTIF_ID = 1

        /** App-private folder (always writable, but hard to reach from other apps). */
        fun appLogsDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs")

        val DEFAULT_SHARED: File get() =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "car_logs")

        /** True when files outside the app folder can be written (Android «All files access»). */
        fun sharedStorageOk(): Boolean = Environment.isExternalStorageManager()

        /**
         * Where trips go: the folder chosen in the settings, by default Download/car_logs. Without
         * «All files access» - the app-private folder, so recording never fails for lack of a permission.
         */
        fun logsDir(ctx: Context): File {
            val chosen = Settings(ctx).logDir
            if (!sharedStorageOk()) return appLogsDir(ctx)
            return if (chosen.isNotEmpty()) File(chosen) else DEFAULT_SHARED
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, LoggerService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LoggerService::class.java).setAction(ACTION_STOP))
        }
    }
}
