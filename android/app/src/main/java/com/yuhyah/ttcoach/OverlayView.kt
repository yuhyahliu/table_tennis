package com.yuhyah.ttcoach

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Draws the table outline, calibration points (+ magnifier) and the skeleton on top of the camera preview. */
@SuppressLint("ViewConstructor")
class OverlayView(ctx: Context, private val a: CoachActivity) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private var drag = -1
    private var loupeBmp: Bitmap? = null
    private var touch = floatArrayOf(0f, 0f)

    // image pixels <-> view pixels (PreviewView FIT_CENTER letterboxes the same 16:9 frame)
    private fun scale() = min(width / a.imgW, height / a.imgH)
    private fun ox() = (width - a.imgW * scale()) / 2
    private fun oy() = (height - a.imgH * scale()) / 2
    private fun vx(x: Double) = (ox() + x * scale()).toFloat()
    private fun vy(y: Double) = (oy() + y * scale()).toFloat()
    private fun ix(x: Float) = (x - ox()) / scale()
    private fun iy(y: Float) = (y - oy()) / scale()

    private fun poly(c: Canvas, p: List<DoubleArray>, color: Int, w: Float, close: Boolean) {
        if (p.size < 2) return
        val path = Path(); path.moveTo(vx(p[0][0]), vy(p[0][1]))
        for (i in 1 until p.size) path.lineTo(vx(p[i][0]), vy(p[i][1]))
        if (close) path.close()
        line.color = color; line.strokeWidth = w; c.drawPath(path, line)
    }

    private fun drawTable(c: Canvas, cam: Cam, color: Int, w: Float) {
        val hw = Table.HALF_W; val hl = Table.HALF_L; val h = Table.H
        fun p(x: Double, y: Double, z: Double = h) = project(cam, doubleArrayOf(x, y, z))
        poly(c, listOf(p(-hw, -hl), p(hw, -hl), p(hw, hl), p(-hw, hl)), color, w, true)
        poly(c, listOf(p(0.0, -hl), p(0.0, hl)), color, w * 0.5f, false)
        poly(c, listOf(p(-hw - 0.15, 0.0), p(-hw - 0.15, 0.0, h + 0.1525), p(hw + 0.15, 0.0, h + 0.1525), p(hw + 0.15, 0.0)), color, w * 0.7f, false)
    }

    private val bones = arrayOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 27 to 29, 29 to 31,
        27 to 31, 24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32, 0 to 11, 0 to 12)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = 3f * density
        val cal = a.cal
        if (a.phase != CoachActivity.Phase.COACH) {
            if (cal?.ok == true) drawTable(c, cal.cam, Color.parseColor("#34d399"), w)
            val pts = synchronized(a.pts) { a.pts.map { it.copyOf() } }
            if (pts.size > 1) poly(c, pts, Color.argb(160, 255, 255, 255), w * 0.5f, pts.size == 4)
            val r = 11f * density
            label.textSize = 15f * density
            pts.forEachIndexed { i, p ->
                fill.color = if (i == drag) Color.parseColor("#fbbf24") else Color.argb(220, 96, 165, 250)
                c.drawCircle(vx(p[0]), vy(p[1]), r, fill)
                line.color = Color.WHITE; line.strokeWidth = 2f * density; c.drawCircle(vx(p[0]), vy(p[1]), r, line)
                c.drawText("${i + 1}", vx(p[0]), vy(p[1]) - r * 1.6f, label)
            }
            drawLoupe(c)
            return
        }
        if (cal != null) drawTable(c, cal.cam, Color.argb(90, 52, 211, 153), w * 0.8f)
        val poses = a.lastPoses; val pick = a.lastPick
        poses.forEachIndexed { i, lm ->
            line.color = if (i == pick) Color.parseColor("#34d399") else Color.argb(70, 255, 255, 255)
            line.strokeWidth = if (i == pick) w * 1.3f else w * 0.8f
            for ((p, q) in bones) c.drawLine(vx(lm[p].x), vy(lm[p].y), vx(lm[q].x), vy(lm[q].y), line)
        }
        val m = a.lastM
        if (m != null && cal != null) {
            val g = project(cal.cam, doubleArrayOf(m.g[0], m.g[1], 0.0))
            line.color = Color.parseColor("#fbbf24"); line.strokeWidth = w
            val rw = 30f * density; val rh = 9f * density
            c.drawOval(RectF(vx(g[0]) - rw, vy(g[1]) - rh, vx(g[0]) + rw, vy(g[1]) + rh), line)
        }
    }

    private fun drawLoupe(c: Canvas) {
        val bmp = loupeBmp ?: return
        if (drag < 0) return
        val size = 130f * density; val zoom = 3f
        val cx = touch[0].coerceIn(size / 2 + 8, width - size / 2 - 8); val cy = (touch[1] - size).coerceAtLeast(size / 2 + 8)
        val half = size / zoom / 2
        // loupe bitmap is the PreviewView snapshot (view coordinates); scale if its size differs from the view
        val bx = bmp.width.toFloat() / width; val by = bmp.height.toFloat() / height
        val src = Rect(((touch[0] - half) * bx).toInt(), ((touch[1] - half) * by).toInt(), ((touch[0] + half) * bx).toInt(), ((touch[1] + half) * by).toInt())
        val dst = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        c.save()
        val clip = Path().apply { addCircle(cx, cy, size / 2, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawBitmap(bmp, src, dst, null)
        c.restore()
        line.color = Color.WHITE; line.strokeWidth = 2f * density; c.drawCircle(cx, cy, size / 2, line)
        line.color = Color.parseColor("#fbbf24"); line.strokeWidth = 1.5f * density
        c.drawLine(cx - 18 * density, cy, cx + 18 * density, cy, line); c.drawLine(cx, cy - 18 * density, cx, cy + 18 * density, line)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (a.phase != CoachActivity.Phase.CAL) return false
        val x = ix(e.x); val y = iy(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                loupeBmp = a.preview.bitmap
                synchronized(a.pts) {
                    if (a.pts.size < 4) { a.pts.add(doubleArrayOf(x, y)); drag = a.pts.size - 1 } // placing: every tap adds a point
                    else {
                        val hit = 36f * density / scale()
                        drag = a.pts.indices.minByOrNull { hypot(a.pts[it][0] - x, a.pts[it][1] - y) }?.takeIf { hypot(a.pts[it][0] - x, a.pts[it][1] - y) < hit } ?: -1
                    }
                }
                touch = floatArrayOf(e.x, e.y); a.recompute()
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                synchronized(a.pts) { a.pts[drag][0] = x; a.pts[drag][1] = y }
                touch = floatArrayOf(e.x, e.y); a.recompute()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; loupeBmp = null; a.recompute() }
        }
        invalidate()
        return true
    }
}
