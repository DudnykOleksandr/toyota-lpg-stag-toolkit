package com.dudnyk.corolla.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.dudnyk.corolla.obd.StreamTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bluetooth Classic SPP to an already paired ELM327 (`OBDII`, PIN 1234) or the Stag Bluetooth interface. Clones differ in what they
 * accept, so three ways are tried: secure SPP, insecure SPP, and the hidden RFCOMM channel 1.
 * The one that worked last time ([preferred]) goes first; [onConnected] reports the one that worked.
 * `BluetoothSocket.connect()` has no timeout of its own (a failing way can block 10-30 s), so each
 * attempt is cut after [attemptTimeoutMs]. Every attempt goes to [log] with its duration.
 * The caller holds BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    private val device: BluetoothDevice,
    private val preferred: Int = 0,
    private val onConnected: (Int) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val attemptTimeoutMs: Long = 8000,
    /** Shown in the final error: what should be powered / free. */
    private val hint: String = "ELM powered? another app or the laptop connected to it?",
) : StreamTransport() {
    private var socket: BluetoothSocket? = null
    override val label: String get() = "${device.name ?: "?"} (${device.address})"

    private val ways: List<Pair<String, () -> BluetoothSocket>> = listOf(
        "secure SPP" to { device.createRfcommSocketToServiceRecord(SPP) },
        "insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP) },
        "RFCOMM channel 1" to {
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
        },
    )

    override fun connect(): Pair<InputStream, OutputStream> {
        val order = listOf(preferred.coerceIn(ways.indices)) + ways.indices.filter { it != preferred }
        var last: Exception? = null
        for (i in order) {
            val (name, make) = ways[i]
            val t0 = System.nanoTime()
            fun secs() = String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e9)
            val s = try {
                make()
            } catch (e: Exception) {
                log("BT $name: cannot create socket: ${e.message}")
                last = e
                continue
            }
            val pool = Executors.newSingleThreadExecutor()
            try {
                pool.submit { s.connect() }.get(attemptTimeoutMs, TimeUnit.MILLISECONDS)
                socket = s
                log("BT connected via $name in ${secs()} s")
                onConnected(i)
                return s.inputStream to s.outputStream
            } catch (e: TimeoutException) {
                log("BT $name: no answer in ${secs()} s")
                last = IOException("timeout", e)
                try { s.close() } catch (_: IOException) {}     // unblocks connect()
            } catch (e: Exception) {
                val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                log("BT $name: failed in ${secs()} s: ${cause.message}")
                last = cause as? Exception ?: e
                try { s.close() } catch (_: IOException) {}
            } finally {
                pool.shutdownNow()
            }
        }
        throw IOException("${last?.message ?: "no socket"} ($hint)", last)
    }

    override fun disconnect() {
        socket?.close()
        socket = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
