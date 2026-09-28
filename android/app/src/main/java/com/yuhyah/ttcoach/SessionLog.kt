package com.yuhyah.ttcoach

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * Writes one gzipped JSON-lines file per session to Download/TTCoach/.
 * Line types: {"hdr":...} once, {"t":..,"p":[[x,y,z,v]*33 per pose],"w":[[x,y,z]*33 of the picked pose],"pick":i} per frame,
 * {"ev":...} for rally events and score presses. Writing happens on its own thread so the camera never waits.
 */
class SessionLog(ctx: Context, val name: String) {
    private val io = Executors.newSingleThreadExecutor()
    private var out: BufferedWriter? = null
    var frames = 0L; private set

    init {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jsonl.gz")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/gzip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TTCoach")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val os = uri?.let { ctx.contentResolver.openOutputStream(it) }
        out = os?.let { BufferedWriter(OutputStreamWriter(GZIPOutputStream(it, 1 shl 16, true), Charsets.UTF_8), 1 shl 16) }
    }

    val ok get() = out != null

    private var lines = 0
    /** flushed every ~200 lines (sync flush): the file stays readable even if the app is closed without finishing it */
    fun line(s: String) { val o = out ?: return; io.execute { try { o.write(s); o.write("\n"); if (++lines % 200 == 0) o.flush() } catch (_: Exception) { } } }

    /** fast fixed 4-decimal formatting (String.format per number was a visible part of the frame time) */
    private fun StringBuilder.num(v: Float): StringBuilder {
        var i = Math.round(v * 10000.0); if (i < 0) { append('-'); i = -i }
        append(i / 10000); append('.'); val r = (i % 10000).toInt()
        if (r < 1000) append('0'); if (r < 100) append('0'); if (r < 10) append('0'); append(r); return this
    }

    /** poses: normalized [x,y,z,visibility] × 33 each; world: metres [x,y,z] × 33 of the picked pose. Formatted on the log thread. */
    fun frame(t: Double, poses: List<FloatArray>, pick: Int, world: FloatArray?) {
        val o = out ?: return
        frames++
        io.execute {
            try {
                val sb = StringBuilder(4000)
                sb.append("{\"t\":").append(String.format(Locale.US, "%.4f", t)).append(",\"pick\":").append(pick).append(",\"p\":[")
                poses.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append('[')
                    for (k in p.indices) { if (k > 0) sb.append(','); sb.num(p[k]) }; sb.append(']') }
                sb.append(']')
                if (world != null) { sb.append(",\"w\":["); for (k in world.indices) { if (k > 0) sb.append(','); sb.num(world[k]) }; sb.append(']') }
                sb.append('}').append('\n')
                o.write(sb.toString()); if (++lines % 200 == 0) o.flush()
            } catch (_: Exception) { }
        }
    }

    fun close() { val o = out ?: return; out = null; io.execute { try { o.flush(); o.close() } catch (_: Exception) { } }; io.shutdown() }
}
