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
        out = os?.let { BufferedWriter(OutputStreamWriter(GZIPOutputStream(it, 1 shl 16), Charsets.UTF_8), 1 shl 16) }
    }

    val ok get() = out != null

    fun line(s: String) { val o = out ?: return; io.execute { try { o.write(s); o.write("\n") } catch (_: Exception) { } } }

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    /** poses: normalized [x,y,z,visibility] × 33 each; world: metres [x,y,z] × 33 of the picked pose */
    fun frame(t: Double, poses: List<FloatArray>, pick: Int, world: FloatArray?) {
        if (out == null) return
        frames++
        val sb = StringBuilder(4000)
        sb.append("{\"t\":").append(String.format(Locale.US, "%.4f", t)).append(",\"pick\":").append(pick).append(",\"p\":[")
        poses.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append('[')
            for (k in p.indices) { if (k > 0) sb.append(','); sb.append(f(p[k])) }; sb.append(']') }
        sb.append(']')
        if (world != null) { sb.append(",\"w\":["); for (k in world.indices) { if (k > 0) sb.append(','); sb.append(f(world[k])) }; sb.append(']') }
        sb.append('}')
        line(sb.toString())
    }

    fun close() { val o = out ?: return; out = null; io.execute { try { o.flush(); o.close() } catch (_: Exception) { } }; io.shutdown() }
}
