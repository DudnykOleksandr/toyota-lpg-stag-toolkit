package com.dudnyk.corolla.obd

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** The link is gone (Bluetooth dropped, socket closed). The recorder reconnects. */
class LinkLost(msg: String, cause: Throwable? = null) : IOException(msg, cause)

/** The ELM answered, but not as expected (timeout, error text). */
class ElmError(msg: String) : Exception(msg)

/** Byte link to an ELM327: Bluetooth SPP in the car, TCP to `logger/sim.py --tcp` for tests. */
interface Transport : Closeable {
    val label: String
    fun open()
    fun write(data: ByteArray)
    /** Bytes up to and including the '>' prompt, or whatever arrived before the timeout. */
    fun readUntilPrompt(timeoutMs: Long): ByteArray
    fun clearInput()
}

/** Raw byte link (the Stag controller speaks binary frames, not ELM text with a prompt). */
interface ByteLink : Closeable {
    val label: String
    fun open()
    fun write(data: ByteArray)
    /** Whatever arrived, waiting up to [timeoutMs] for at least one byte; empty on timeout. */
    fun readSome(timeoutMs: Long): ByteArray
}

/**
 * Blocking streams (Bluetooth sockets have no read timeout) are drained by a reader thread into a
 * buffer; [readUntilPrompt] waits on that buffer with a deadline, like pyserial's `read_until`.
 */
abstract class StreamTransport : Transport, ByteLink {
    private val lock = Object()
    private val buf = ByteArrayOutputStream()
    private var failure: IOException? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    protected abstract fun connect(): Pair<InputStream, OutputStream>
    protected abstract fun disconnect()

    override fun open() {
        val (input, out) = try {
            connect()
        } catch (e: IOException) {
            throw LinkLost("connect failed: ${e.message}", e)
        }
        synchronized(lock) {
            failure = null
            buf.reset()
        }
        output = out
        reader = thread(isDaemon = true, name = "elm-rx") {
            val b = ByteArray(512)
            try {
                while (true) {
                    val n = input.read(b)
                    if (n < 0) throw IOException("stream closed")
                    synchronized(lock) {
                        buf.write(b, 0, n)
                        lock.notifyAll()
                    }
                }
            } catch (e: IOException) {
                synchronized(lock) {
                    failure = e
                    lock.notifyAll()
                }
            }
        }
    }

    override fun write(data: ByteArray) {
        val out = output ?: throw LinkLost("port not open")
        synchronized(lock) { failure?.let { throw LinkLost("link lost: ${it.message}", it) } }
        try {
            out.write(data)
            out.flush()
        } catch (e: IOException) {
            throw LinkLost("write failed: ${e.message}", e)
        }
    }

    override fun readUntilPrompt(timeoutMs: Long): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (true) {
                val bytes = buf.toByteArray()
                val idx = bytes.indexOf('>'.code.toByte())
                if (idx >= 0) {
                    buf.reset()
                    buf.write(bytes, idx + 1, bytes.size - idx - 1)
                    return bytes.copyOf(idx + 1)
                }
                failure?.let { throw LinkLost("link lost: ${it.message}", it) }
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) {
                    buf.reset()
                    return bytes
                }
                lock.wait(leftMs)
            }
        }
    }

    override fun readSome(timeoutMs: Long): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (true) {
                if (buf.size() > 0) return buf.toByteArray().also { buf.reset() }
                failure?.let { throw LinkLost("link lost: ${it.message}", it) }
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) return ByteArray(0)
                lock.wait(leftMs)
            }
        }
    }

    override fun clearInput() {
        synchronized(lock) { buf.reset() }
    }

    override fun close() {
        output = null
        try {
            disconnect()
        } catch (_: IOException) {
        }
        reader?.interrupt()
        reader = null
    }
}

/** TCP link to the simulator (`python logger/sim.py --tcp 35000`). */
class TcpTransport(private val host: String, private val port: Int) : StreamTransport() {
    private var socket: Socket? = null
    override val label get() = "tcp://$host:$port"

    override fun connect(): Pair<InputStream, OutputStream> {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 5000)
        socket = s
        return s.getInputStream() to s.getOutputStream()
    }

    override fun disconnect() {
        socket?.close()
        socket = null
    }
}
