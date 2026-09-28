package com.yuhyah.ttcoach

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Kid-facing drawing: mirrored like a mirror, big shapes that read from 3 m away. */
@SuppressLint("ViewConstructor")
class ShadowView(ctx: Context, private val a: ShadowActivity) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val ORANGE = Color.rgb(255, 150, 40)
    private val BONES = listOf(J.SH_N to J.SH_P, J.SH_P to J.HIP_P, J.SH_N to J.HIP_N, J.HIP_N to J.HIP_P, J.SH_P to J.EL_P, J.EL_P to J.WR_P,
        J.SH_N to J.EL_N, J.EL_N to J.WR_N, J.HIP_P to J.KN_P, J.KN_P to J.AN_P, J.HIP_N to J.KN_N, J.KN_N to J.AN_N)
    private val MP_BONES = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)

    // image (normalised, un-mirrored) → view, mirrored, letterboxed like PreviewView FIT_CENTER
    private var box = RectF()
    private fun layout(iw: Float, ih: Float) { val s = min(width / iw, height / ih); val w = iw * s; val h = ih * s; box = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2) }
    private fun vx(xn: Double) = (box.right - xn * box.width()).toFloat()
    private fun vy(yn: Double) = (box.top + yn * box.height()).toFloat()

    private fun shadow(c: Canvas, pose: Pose, aff: Affine, color: Int, alpha: Int, scaleTo: RectF? = null) {
        val pts = pose.map { aff.map(it) }
        var X = { q: DoubleArray -> vx(q[0]) }; var Y = { q: DoubleArray -> vy(q[1]) }
        if (scaleTo != null) { // fit into an inset box
            val xs = pts.map { vx(it[0]) }; val ys = pts.map { vy(it[1]) }
            val bw = xs.max() - xs.min(); val bh = ys.max() - ys.min(); val s = min(scaleTo.width() / (bw + 1), scaleTo.height() * 0.8f / (bh + 1))
            val cx = (xs.max() + xs.min()) / 2; val cy = (ys.max() + ys.min()) / 2
            X = { q -> scaleTo.centerX() + (vx(q[0]) - cx) * s }; Y = { q -> scaleTo.centerY() + 0.05f * scaleTo.height() + (vy(q[1]) - cy) * s }
        }
        val sw = hypot(X(pts[J.SH_P]) - X(pts[J.SH_N]), Y(pts[J.SH_P]) - Y(pts[J.SH_N])).coerceAtLeast(20f)
        p.style = Paint.Style.STROKE; p.color = color; p.alpha = alpha; p.strokeWidth = sw * 0.42f
        for ((i, j) in BONES) c.drawLine(X(pts[i]), Y(pts[i]), X(pts[j]), Y(pts[j]), p)
        // torso fill + head
        p.style = Paint.Style.FILL
        val torso = Path().apply { moveTo(X(pts[J.SH_N]), Y(pts[J.SH_N])); lineTo(X(pts[J.SH_P]), Y(pts[J.SH_P])); lineTo(X(pts[J.HIP_P]), Y(pts[J.HIP_P])); lineTo(X(pts[J.HIP_N]), Y(pts[J.HIP_N])); close() }
        c.drawPath(torso, p)
        c.drawCircle(X(pts[J.HEAD]), Y(pts[J.HEAD]), sw * 0.42f, p)
        p.alpha = 255
    }

    private fun kid(c: Canvas, lm: List<FloatArray>, color: Int, w: Float) {
        p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w
        for ((i, j) in MP_BONES) if (lm[i][2] > 0.3f && lm[j][2] > 0.3f) c.drawLine(vx(lm[i][0].toDouble()), vy(lm[i][1].toDouble()), vx(lm[j][0].toDouble()), vy(lm[j][1].toDouble()), p)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
        if (s.isEmpty()) return
        txt.textSize = size; txt.color = Color.BLACK; txt.style = Paint.Style.STROKE; txt.strokeWidth = size / 7; c.drawText(s, x, y, txt)
        txt.color = color; txt.style = Paint.Style.FILL; c.drawText(s, x, y, txt)
    }

    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = Color.BLACK; c.drawLine(x0, y0, x1, y1, p)
        p.color = color; p.strokeWidth = w * 0.7f; c.drawLine(x0, y0, x1, y1, p)
        val ang = atan2(y1 - y0, x1 - x0); val h = w * 2.6f
        val path = Path().apply { moveTo(x1 + cos(ang) * h * 0.4f, y1 + sin(ang) * h * 0.4f)
            lineTo(x1 - cos(ang - 0.5f) * h, y1 - sin(ang - 0.5f) * h); lineTo(x1 - cos(ang + 0.5f) * h, y1 - sin(ang + 0.5f) * h); close() }
        p.style = Paint.Style.FILL; c.drawPath(path, p)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val u = min(width, height).toFloat()
        val now = System.currentTimeMillis()
        if (a.phase == ShadowActivity.Phase.REVIEW) { drawReview(c, u); return }
        layout(a.imgW, a.imgH)
        val aff = a.lastAff; val g = a.ghostNow
        if (aff != null && g != null && a.phase != ShadowActivity.Phase.SETUP) shadow(c, g, aff, ORANGE, if (a.phase == ShadowActivity.Phase.DEMO) 170 else 105)
        a.lastLm?.let { kid(c, it, Color.argb(200, 120, 240, 120), u * 0.008f) }
        // colour flash around the whole screen (visible from far away)
        if (now < a.flashUntil) { p.style = Paint.Style.STROKE; p.color = a.flashColor; p.strokeWidth = u * 0.06f; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
        // stars
        if (now < a.starsUntil) {
            val s = "★".repeat(a.starsShown) + "☆".repeat(3 - a.starsShown)
            text(c, s, width / 2f, height * 0.30f, u * 0.22f, Color.rgb(255, 215, 0))
        }
        // headline + hint
        text(c, a.bigText, width / 2f, height * 0.14f, u * 0.10f)
        text(c, a.smallText, width / 2f, height - u * 0.14f, u * 0.055f)
        // round progress: 5 dots
        if (a.phase == ShadowActivity.Phase.FOLLOW || a.phase == ShadowActivity.Phase.FOCUS) {
            val r = u * 0.025f; val y = height - u * 0.06f
            for (i in 0 until 5) {
                val x = width / 2f + (i - 2) * r * 3.2f
                p.style = Paint.Style.FILL
                p.color = if (i < a.roundResults.size) { val rr = a.roundResults[i]; if ((a.phase == ShadowActivity.Phase.FOCUS && rr.focusOk == true) || rr.stars == 3) Ui.ACC else if (rr.stars == 2) Ui.WARN else Ui.OPP } else Color.argb(120, 255, 255, 255)
                c.drawCircle(x, y, r, p)
            }
        }
        if (a.phase == ShadowActivity.Phase.SETUP && a.lastLm == null) text(c, "看不到你，退後一點", width / 2f, height * 0.5f, u * 0.07f, Ui.WARN)
    }

    private fun drawReview(c: Canvas, u: Float) {
        c.drawColor(Color.rgb(10, 16, 14))
        val (r, s) = a.review ?: return
        val issue = a.reviewIssue ?: return
        layout(s.bmp.width.toFloat(), s.bmp.height.toFloat())
        // the photo, mirrored like the live view
        val m = Matrix().apply { postScale(-box.width() / s.bmp.width, box.height() / s.bmp.height); postTranslate(box.right, box.top) }
        c.drawBitmap(s.bmp, m, null)
        shadow(c, s.ghost, s.aff, ORANGE, 90)
        kid(c, s.lm, Color.argb(220, 120, 240, 120), u * 0.007f)
        // arrow from the kid's joint to where it should be
        val j = when (issue.key) { "elbow_imp", "wrist_rise" -> J.WR_P; "upperarm_imp" -> J.EL_P; "knee_bs" -> J.PELVIS; "sh_turn" -> J.SH_P; else -> J.HIP_P }
        val from = s.aff.map(s.kid[j]); val to = s.aff.map(s.ghost[j])
        var x0 = vx(from[0]); var y0 = vy(from[1]); var x1 = vx(to[0]); var y1 = vy(to[1])
        if (hypot(x1 - x0, y1 - y0) < u * 0.08f) { // too short to see: stretch it in the same direction
            val d = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f); x1 = x0 + (x1 - x0) / d * u * 0.12f; y1 = y0 + (y1 - y0) / d * u * 0.12f
        }
        p.style = Paint.Style.STROKE; p.color = Color.rgb(255, 230, 0); p.strokeWidth = u * 0.012f; c.drawCircle(x0, y0, u * 0.05f, p)
        arrow(c, x0, y0, x1, y1, u * 0.03f, Color.rgb(255, 230, 0))
        // target inset
        val inset = RectF(width - u * 0.42f, u * 0.04f, width - u * 0.04f, u * 0.62f)
        p.style = Paint.Style.FILL; p.color = Color.argb(215, 12, 24, 20); c.drawRoundRect(inset, u * 0.03f, u * 0.03f, p)
        shadow(c, s.ghost, s.aff, ORANGE, 255, inset)
        text(c, "目標", inset.centerX(), inset.top + u * 0.07f, u * 0.055f, Color.rgb(255, 200, 120))
        // words
        text(c, issue.cue, width / 2f, height - u * 0.20f, u * 0.085f, Color.rgb(255, 230, 0))
        text(c, a.smallText, width / 2f, height - u * 0.11f, u * 0.05f)
        text(c, "點一下螢幕，再來 5 下", width / 2f, height - u * 0.04f, u * 0.04f, Color.argb(200, 255, 255, 255))
        @Suppress("UNUSED_VARIABLE") val unused = r
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && a.phase == ShadowActivity.Phase.REVIEW) { a.onTapReview(); return true }
        return true
    }
}
