package com.dudnyk.corolla.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One recorded trip: all files `elm_YYYYMMDD_HHMMSS.*` in the logs folder (with the Stag monitor
 * `.stag.csv`), or one Stag map backup `stag_map_YYYYMMDD_HHMMSS_<before|after>.json`.
 */
data class Trip(val base: String, val files: List<File>) {
    val bytes get() = files.sumOf { it.length() }
    val csv get() = files.firstOrNull { it.name.endsWith(".csv") && !it.name.endsWith(".stag.csv") }
    val isMap get() = base.startsWith("stag_map_")
    val hasStag get() = files.any { it.name.endsWith(".stag.csv") }
    /** `YYYYMMDD` of the trip, for "today" selection. */
    val day get() = Regex("_(\\d{8})_").find(base + "_")?.groupValues?.get(1) ?: ""
}

object Trips {
    private val NAME = Regex("^(elm_\\d{8}_\\d{6}(?:_sim)?)\\.(csv|log|meta\\.json|health\\.json|stag\\.csv)$")
    private val MAP = Regex("^(stag_map_\\d{8}_\\d{6}_\\w+)\\.json$")

    fun list(dir: File): List<Trip> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { f -> (NAME.find(f.name) ?: MAP.find(f.name))?.let { it.groupValues[1] to f } }
            .groupBy({ it.first }, { it.second })
            .map { (base, files) -> Trip(base, files.sortedBy { it.name }) }
            .sortedByDescending { it.base.removePrefix("elm_").removePrefix("stag_map_") }

    /** Zip the trips into the cache (one archive, flat, ready to unpack into `logs/`) and open the share sheet. */
    fun share(ctx: Context, trips: List<Trip>) {
        if (trips.isEmpty()) return
        val outDir = File(ctx.cacheDir, "share").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }          // old archives, our own cache only
        val name = if (trips.size == 1) trips[0].base else "car_logs_${trips.size}_${trips.maxOf { it.base }.takeLast(15)}"
        val zip = File(outDir, "$name.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (f in trips.flatMap { it.files }.distinctBy { it.name }) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Поділитися ($name)"))
    }

    /** Move trips from [from] into [to] (old app-private folder -> shared folder). Returns files moved. */
    fun moveAll(from: File, to: File): Int {
        if (from.canonicalPath == to.canonicalPath) return 0
        to.mkdirs()
        var n = 0
        for (t in list(from)) for (f in t.files) {
            val dst = File(to, f.name)
            if (dst.exists()) continue
            if (f.renameTo(dst) || (runCatching { f.copyTo(dst); f.delete() }.isSuccess)) n++
        }
        return n
    }
}
