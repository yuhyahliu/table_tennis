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

/** Kid-facing drawing: mirrored like a mirror, few big shapes that read from 3 m away. */
@SuppressLint("ViewConstructor")
class ShadowView(ctx: Context, private val a: ShadowActivity) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.MONOSPACE }
    private val ORANGE = Color.rgb(255, 150, 40)
    private val GOLD = Color.rgb(255, 215, 0)
    private val YELLOW = Color.rgb(255, 230, 0)
    private val BONES = listOf(J.SH_N to J.SH_P, J.SH_P to J.HIP_P, J.SH_N to J.HIP_N, J.HIP_N to J.HIP_P, J.SH_P to J.EL_P, J.EL_P to J.WR_P,
        J.SH_N to J.EL_N, J.EL_N to J.WR_N, J.HIP_P to J.KN_P, J.KN_P to J.AN_P, J.HIP_N to J.KN_N, J.KN_N to J.AN_N)
    private val MP_BONES = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)

    // image (normalised, un-mirrored) → view, mirrored, letterboxed like PreviewView FIT_CENTER
    private var box = RectF()
    private fun layout(iw: Float, ih: Float) { val s = min(width / iw, height / ih); val w = iw * s; val h = ih * s; box = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2) }
    private fun vx(xn: Double) = (box.right - xn * box.width()).toFloat()
    private fun vy(yn: Double) = (box.top + yn * box.height()).toFloat()

    /** the orange shadow: slim limbs, see-through, so the child's own body stays visible */
    private fun shadow(c: Canvas, pose: Pose, aff: Affine, alpha: Int) {
        val pts = pose.map { aff.map(it) }
        fun X(i: Int) = vx(pts[i][0])
        fun Y(i: Int) = vy(pts[i][1])
        val sw = hypot(X(J.SH_P) - X(J.SH_N), Y(J.SH_P) - Y(J.SH_N)).coerceAtLeast(20f)
        p.style = Paint.Style.STROKE; p.color = ORANGE; p.alpha = alpha; p.strokeWidth = sw * 0.28f
        for ((i, j) in BONES) c.drawLine(X(i), Y(i), X(j), Y(j), p)
        p.style = Paint.Style.FILL; p.alpha = alpha * 2 / 3
        val torso = Path().apply { moveTo(X(J.SH_N), Y(J.SH_N)); lineTo(X(J.SH_P), Y(J.SH_P)); lineTo(X(J.HIP_P), Y(J.HIP_P)); lineTo(X(J.HIP_N), Y(J.HIP_N)); close() }
        c.drawPath(torso, p)
        p.alpha = alpha; c.drawCircle(X(J.HEAD), Y(J.HEAD), sw * 0.33f, p)
        // the racket hand stands out
        p.alpha = 255; c.drawCircle(X(J.WR_P), Y(J.WR_P), sw * 0.2f, p)
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

    private fun head(c: Canvas, x1: Float, y1: Float, ang: Float, w: Float, color: Int) {
        val h = w * 2.6f
        val path = Path().apply { moveTo(x1 + cos(ang) * h * 0.4f, y1 + sin(ang) * h * 0.4f)
            lineTo(x1 - cos(ang - 0.5f) * h, y1 - sin(ang - 0.5f) * h); lineTo(x1 - cos(ang + 0.5f) * h, y1 - sin(ang + 0.5f) * h); close() }
        p.style = Paint.Style.FILL; p.color = Color.BLACK; c.drawPath(path, p)
        p.color = color; c.save(); c.scale(0.8f, 0.8f, x1 - cos(ang) * h * 0.3f, y1 - sin(ang) * h * 0.3f); c.drawPath(path, p); c.restore()
    }

    /** straight arrow, or curved (bulging away from `bulgeFrom`) for turning motions */
    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int, bulgeFrom: FloatArray? = null) {
        val path = Path(); path.moveTo(x0, y0)
        var ang = atan2(y1 - y0, x1 - x0)
        if (bulgeFrom != null) {
            val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2; val d = hypot(x1 - x0, y1 - y0)
            var nx = -(y1 - y0) / d; var ny = (x1 - x0) / d
            if ((mx - bulgeFrom[0]) * nx + (my - bulgeFrom[1]) * ny < 0) { nx = -nx; ny = -ny }
            val cx = mx + nx * d * 0.45f; val cy = my + ny * d * 0.45f
            path.quadTo(cx, cy, x1, y1); ang = atan2(y1 - cy, x1 - cx)
        } else path.lineTo(x1, y1)
        p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = Color.BLACK; c.drawPath(path, p)
        p.color = color; p.strokeWidth = w * 0.7f; c.drawPath(path, p)
        head(c, x1, y1, ang, w, color)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val u = min(width, height).toFloat()
        if (a.phase == ShadowActivity.Phase.REPLAY) { drawReplay(c, u); if (a.debug) drawDebug(c, u); return }
        val now = System.currentTimeMillis()
        layout(a.imgW, a.imgH)
        val aff = a.lastAff; val g = a.ghostNow
        val swinging = a.phase == ShadowActivity.Phase.FOLLOW || a.phase == ShadowActivity.Phase.FOCUS
        if (aff != null && g != null && a.phase != ShadowActivity.Phase.SETUP) shadow(c, g, aff, if (a.phase == ShadowActivity.Phase.DEMO) 160 else 95)
        a.lastLm?.let { kid(c, it, Color.argb(190, 120, 240, 120), u * 0.007f) }

        // colour flash around the whole screen
        if (now < a.flashUntil) { p.style = Paint.Style.STROKE; p.color = a.flashColor; p.strokeWidth = u * 0.06f; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
        if (now < a.starsUntil) text(c, "★".repeat(a.starsShown) + "☆".repeat(3 - a.starsShown), width / 2f, height * 0.52f, u * 0.2f, GOLD)

        if (a.debug && (swinging || a.phase == ShadowActivity.Phase.DEMO)) energyBar(c, u)   // coach only: speed is not the goal
        if (swinging) {
            counter(c, u, now)
            if (a.phase == ShadowActivity.Phase.FOCUS) text(c, a.bigText, width / 2f, height - u * 0.07f, u * 0.085f, YELLOW)
        } else when (a.phase) {
            ShadowActivity.Phase.COUNTDOWN -> {
                val k = 3 - (a.clock - a.phaseStart).toInt()
                if (k >= 1) text(c, "$k", width / 2f, height * 0.62f, u * 0.5f, GOLD)
            }
            ShadowActivity.Phase.READY -> {
                readyRing(c, u)
                text(c, a.bigText, width / 2f, height * 0.16f, u * 0.11f, GOLD)
                text(c, a.subText, width / 2f, height * 0.16f + u * 0.11f, u * 0.065f)
            }
            ShadowActivity.Phase.PAUSED -> {
                p.style = Paint.Style.FILL; p.color = Color.argb(110, 0, 0, 0); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                if (!a.autoPaused) { text(c, "Ⅱ", width / 2f, height * 0.6f, u * 0.25f); readyRing(c, u) }
                text(c, a.bigText, width / 2f, height * 0.16f, u * 0.11f, Ui.WARN)
                text(c, a.subText, width / 2f, height * 0.16f + u * 0.11f, u * 0.065f)
            }
            else -> {
                text(c, a.bigText, width / 2f, height * 0.16f, u * 0.095f)
                text(c, a.subText, width / 2f, height * 0.16f + u * 0.1f, u * 0.065f)
                if (a.phase == ShadowActivity.Phase.DEMO && now - a.pulseAt < 400) text(c, "✓", width / 2f, height * 0.5f, u * 0.2f, Ui.ACC)
            }
        }
        if (a.phase == ShadowActivity.Phase.SETUP && a.lastLm == null) text(c, "看不到你，退後一點", width / 2f, height * 0.55f, u * 0.08f, Ui.WARN)
        if (a.debug) drawDebug(c, u)
    }

    /** a ring around the racket hand that fills while the player holds the backswing */
    private fun readyRing(c: Canvas, u: Float) {
        val lm = a.lastLm ?: return
        val w = lm[if (a.rightHanded) 16 else 15]
        val x = vx(w[0].toDouble()); val y = vy(w[1].toDouble()); val r = u * 0.07f
        p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.018f
        p.color = Color.argb(140, 255, 255, 255); c.drawCircle(x, y, r, p)
        if (a.readyFrac > 0f) { p.color = Ui.ACC; c.drawArc(RectF(x - r, y - r, x + r, y + r), -90f, 360f * a.readyFrac, false, p) }
    }

    /** big "3/5" plus five circles: white the moment a swing is seen, then coloured by how good it was */
    private fun counter(c: Canvas, u: Float, now: Long) {
        val n = a.roundDetected
        val pop = ((now - a.pulseAt).coerceIn(0, 300)).let { 1f + 0.35f * (1f - it / 300f) }
        text(c, "$n/5", width / 2f, u * 0.19f, u * 0.15f * pop, Color.WHITE)
        val r = u * 0.032f; val y = u * 0.27f
        for (i in 0 until 5) {
            val x = width / 2f + (i - 2) * r * 3.0f
            val res = a.roundResults.getOrNull(i)
            p.style = Paint.Style.FILL
            p.color = when {
                res != null -> if ((a.phase == ShadowActivity.Phase.FOCUS && res.focusOk == true) || (a.phase == ShadowActivity.Phase.FOLLOW && res.stars == 3)) Ui.ACC
                               else if (a.phase == ShadowActivity.Phase.FOLLOW && res.stars == 2) Ui.WARN else Ui.OPP
                i < n -> Color.WHITE
                else -> Color.argb(70, 255, 255, 255)
            }
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.005f; p.color = Color.BLACK; c.drawCircle(x, y, r, p)
        }
    }

    /** how hard you are swinging, compared with your own usual swing; the line = counts from here */
    private fun energyBar(c: Canvas, u: Float) {
        val w = u * 0.07f; val h = height * 0.62f; val x = width - w - u * 0.05f; val y0 = (height - h) / 2
        p.style = Paint.Style.FILL; p.color = Color.argb(130, 0, 0, 0); c.drawRoundRect(RectF(x, y0, x + w, y0 + h), w / 2, w / 2, p)
        val e = a.energy.coerceIn(0f, 1.1f) / 1.1f
        val over = a.energy >= a.energyThr
        p.color = if (over) Ui.ACC else ORANGE
        c.drawRoundRect(RectF(x, y0 + h * (1 - e), x + w, y0 + h), w / 2, w / 2, p)
        val ty = y0 + h * (1 - (a.energyThr / 1.1f).coerceIn(0f, 1f))
        p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.008f; p.color = Color.WHITE; c.drawLine(x - w * 0.25f, ty, x + w * 1.25f, ty, p)
        text(c, "⚡", x + w / 2, y0 - u * 0.02f, u * 0.07f, GOLD)
    }

    private fun drawReplay(c: Canvas, u: Float) {
        c.drawColor(Color.rgb(10, 16, 14))
        val s = a.segs.getOrNull(a.segIdx) ?: return
        val bmp = a.replayBmp ?: return
        val f = a.replayFrame ?: return
        layout(bmp.width.toFloat(), bmp.height.toFloat())
        val m = Matrix().apply { postScale(-box.width() / bmp.width, box.height() / bmp.height); postTranslate(box.right, box.top) }
        c.drawBitmap(bmp, m, null)
        val aff = f.aff; val g = a.replayGhost
        if (aff != null && g != null) shadow(c, g, aff, 115)
        kid(c, f.lm, Color.argb(220, 120, 240, 120), u * 0.007f)
        val col = if (s.good) Ui.ACC else Ui.OPP
        p.style = Paint.Style.STROKE; p.color = col; p.strokeWidth = u * 0.035f; c.drawRect(box, p)
        text(c, "第 ${s.num} 下  " + (if (a.roundWasFocusShown) (if (s.good) "★★★" else "★☆☆") else "★".repeat(s.r.stars) + "☆".repeat(3 - s.r.stars)),
            width / 2f, box.top + u * 0.11f, u * 0.075f, col)
        if (!a.frozen) text(c, "慢動作", width - u * 0.16f, box.top + u * 0.11f, u * 0.045f, Color.argb(220, 255, 255, 255))
        if (a.frozen) {
            if (!s.good && s.issue != null && aff != null && g != null) {
                val j = s.issue.joint
                val from = aff.map(f.pose[j]); val to = aff.map(g[j])
                var x0 = vx(from[0]); var y0 = vy(from[1]); var x1 = vx(to[0]); var y1 = vy(to[1])
                val d = hypot(x1 - x0, y1 - y0)
                if (d < u * 0.1f) { val dd = d.coerceAtLeast(1f); x1 = x0 + (x1 - x0) / dd * u * 0.14f; y1 = y0 + (y1 - y0) / dd * u * 0.14f }
                if (d < 1f) { x1 = x0; y1 = y0 - u * 0.14f }
                p.style = Paint.Style.STROKE; p.color = YELLOW; p.strokeWidth = u * 0.012f; c.drawCircle(x0, y0, u * 0.055f, p)
                val pel = aff.map(f.pose[J.PELVIS])
                arrow(c, x0, y0, x1, y1, u * 0.032f, YELLOW, if (s.issue.turn) floatArrayOf(vx(pel[0]), vy(pel[1])) else null)
            }
            text(c, s.caption, width / 2f, box.bottom - u * 0.2f, u * 0.095f, if (s.good) Ui.ACC else YELLOW)
        }
        // thumbnails of the 5 swings (tap = show that one) and the 「下一輪」 button
        thumbs.clear()
        val tw = u * 0.13f; val th = u * 0.1f; val gap = u * 0.025f; val ty = height - th - u * 0.03f
        a.segs.forEachIndexed { i, sg ->
            val r = RectF(u * 0.04f + i * (tw + gap), ty, u * 0.04f + i * (tw + gap) + tw, ty + th); thumbs.add(r)
            p.style = Paint.Style.FILL; p.color = if (sg.good) Ui.ACC else Ui.OPP; p.alpha = if (i == a.segIdx) 255 else 130
            c.drawRoundRect(r, u * 0.02f, u * 0.02f, p); p.alpha = 255
            if (i == a.segIdx) { p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.008f; p.color = Color.WHITE; c.drawRoundRect(r, u * 0.02f, u * 0.02f, p) }
            text(c, "${sg.num}", r.centerX(), r.centerY() + u * 0.025f, u * 0.065f)
        }
        nextBtn.set(width - u * 0.42f, ty - u * 0.01f, width - u * 0.04f, ty + th)
        p.style = Paint.Style.FILL; p.color = GOLD; c.drawRoundRect(nextBtn, u * 0.03f, u * 0.03f, p)
        txt.textSize = u * 0.06f; txt.style = Paint.Style.FILL; txt.color = Color.BLACK
        c.drawText("下一輪 ▶", nextBtn.centerX(), nextBtn.centerY() + u * 0.022f, txt)
    }
    private val thumbs = ArrayList<RectF>()
    private val nextBtn = RectF()

    /** coach only (long-press): why a swing was or was not counted */
    private fun drawDebug(c: Canvas, u: Float) {
        val co = a.coach
        val lines = listOf(
            "${a.spec.key} ${if (a.rightHanded) "R" else "L"} phase=${a.phase} fps=%.0f".format(a.fps),
            "wrist %.1f m/s  thr %.1f  usual %.1f".format(co.speed, co.threshold(), co.typicalSpeed()),
            "last reject: ${co.lastReject.ifEmpty { "-" }}",
            "body turn %.0f°  shadow idx ${a.ghostIdx}/${a.tpl.n}".format(a.turnDeg),
            "counted ${a.roundDetected}  scored ${a.roundResults.size}  all ${co.results.size}  hold=${co.hold}") +
            (co.results.lastOrNull()?.let { r -> listOf("last: like ${(r.shape * 100).toInt()}%  z " + r.z.entries.joinToString(" ") { "${it.key}=%.1f".format(it.value) }) } ?: emptyList())
        mono.textSize = u * 0.03f
        val lh = mono.textSize * 1.25f
        p.style = Paint.Style.FILL; p.color = Color.argb(170, 0, 0, 0)
        c.drawRect(0f, 0f, u * 1.05f, lh * (lines.size + 0.6f), p)
        lines.forEachIndexed { i, s -> c.drawText(s, u * 0.02f, lh * (i + 1), mono) }
    }

    private var downAt = 0L
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downAt = System.currentTimeMillis()
            MotionEvent.ACTION_UP -> {
                if (System.currentTimeMillis() - downAt > 700) { a.debug = !a.debug; invalidate() }
                else if (a.phase == ShadowActivity.Phase.REPLAY) {
                    if (nextBtn.contains(e.x, e.y)) a.nextFromReview()
                    else thumbs.indexOfFirst { it.contains(e.x, e.y) }.takeIf { it >= 0 }?.let { a.showSwing(it) }
                } else a.onTap()
            }
        }
        return true
    }
}
