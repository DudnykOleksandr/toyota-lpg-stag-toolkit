package com.dudnyk.corolla.app

import android.content.Context

/** Connection choice and small options, kept in SharedPreferences. */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var source: String
        get() = p.getString("source", SOURCE_BT) ?: SOURCE_BT
        set(v) = p.edit().putString("source", v).apply()
    var btAddress: String
        get() = p.getString("bt_address", "") ?: ""
        set(v) = p.edit().putString("bt_address", v).apply()
    /** Index of the Bluetooth connect way that worked last time (see BluetoothTransport), tried first. */
    var btMethod: Int
        get() = p.getInt("bt_method", 0)
        set(v) = p.edit().putInt("bt_method", v).apply()
    var tcpHost: String
        get() = p.getString("tcp_host", "192.168.1.100") ?: "192.168.1.100"
        set(v) = p.edit().putString("tcp_host", v).apply()
    var tcpPort: Int
        get() = p.getInt("tcp_port", 35000)
        set(v) = p.edit().putInt("tcp_port", v).apply()
    /** Stop by itself after the ECU went silent for this long (engine off); 0 = only by hand. */
    var silentStopMin: Int
        get() = p.getInt("silent_stop_min", 5)
        set(v) = p.edit().putInt("silent_stop_min", v).apply()

    /** Start writing a trip as soon as the ELM answers; off = dashboard only until «Запис». */
    var recordOnConnect: Boolean
        get() = p.getBoolean("record_on_connect", false)
        set(v) = p.edit().putBoolean("record_on_connect", v).apply()

    /** Paired Stag Bluetooth interface; empty = no Stag connection. */
    var stagAddress: String
        get() = p.getString("stag_address", "") ?: ""
        set(v) = p.edit().putString("stag_address", v).apply()
    var stagMethod: Int
        get() = p.getInt("stag_method", 0)
        set(v) = p.edit().putInt("stag_method", v).apply()

    /** Folder for trip files; empty = default `Download/car_logs` (see LoggerService.logsDir). */
    var logDir: String
        get() = p.getString("log_dir", "") ?: ""
        set(v) = p.edit().putString("log_dir", v).apply()

    companion object {
        const val SOURCE_BT = "bt"
        const val SOURCE_TCP = "tcp"
    }
}
