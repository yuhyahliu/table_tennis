// 場邊教練 — coaching engine (pure Kotlin, no Android dependencies, unit-tested on the JVM).
// Port of the web version's engine.js, plus: personal target, "focus per game" feedback, upright share.
// World frame: origin = table centre on the floor, X = right as seen from the camera,
// Y = toward the far end, Z = up (metres). Table top z = 0.76, end lines at Y = ±1.37.
package com.yuhyah.ttcoach

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

object Table { const val HALF_W = 0.7625; const val HALF_L = 1.37; const val H = 0.76 }

// ---------- small linear algebra ----------
private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun norm(a: DoubleArray) = sqrt(dot(a, a))
private fun scale(a: DoubleArray, s: Double) = doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)
private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
private fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

internal fun solve(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size
    val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] } }
    for (c in 0 until n) {
        var p = c
        for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
        val t = m[c]; m[c] = m[p]; m[p] = t
        if (abs(m[c][c]) < 1e-12) return null
        for (r in 0 until n) if (r != c) {
            val f = m[r][c] / m[c][c]
            for (k in c..n) m[r][k] -= f * m[c][k]
        }
    }
    return DoubleArray(n) { i -> m[i][n] / m[i][i] }
}

/** homography from world-plane (X,Y) to image (u,v), 4 correspondences */
internal fun homography(world: List<DoubleArray>, img: List<DoubleArray>): Array<DoubleArray>? {
    val a = ArrayList<DoubleArray>(); val b = ArrayList<Double>()
    for (i in 0 until 4) {
        val (x, y) = world[i].let { it[0] to it[1] }; val (u, v) = img[i].let { it[0] to it[1] }
        a.add(doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)); b.add(u)
        a.add(doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)); b.add(v)
    }
    val h = solve(a.toTypedArray(), b.toDoubleArray()) ?: return null
    return arrayOf(doubleArrayOf(h[0], h[1], h[2]), doubleArrayOf(h[3], h[4], h[5]), doubleArrayOf(h[6], h[7], 1.0))
}

/** pinhole camera: R = world->camera rows, C = camera centre (world) */
class Cam(val f: Double, val cx: Double, val cy: Double, val r: Array<DoubleArray>, val c: DoubleArray, val w: Double, val h: Double)

class Calib(val ok: Boolean, val reason: String, val cam: Cam, val err: Double, val hfov: Double)

/**
 * Calibrate from 4 taps on the table top (image pixels):
 * [near-left corner, near-right corner, right sideline at the net, left sideline at the net].
 */
fun calibrate(taps: List<DoubleArray>, w: Double, h: Double): Calib? {
    val hw = Table.HALF_W; val hl = Table.HALF_L; val th = Table.H
    val world = listOf(doubleArrayOf(-hw, -hl), doubleArrayOf(hw, -hl), doubleArrayOf(hw, 0.0), doubleArrayOf(-hw, 0.0))
    val hm = homography(world, taps) ?: return null
    val cx = w / 2; val cy = h / 2
    fun col(j: Int) = doubleArrayOf(hm[0][j] - cx * hm[2][j], hm[1][j] - cy * hm[2][j], hm[2][j])
    val h1 = col(0); val h2 = col(1); val h3 = col(2)
    val cands = ArrayList<Double>()
    val d1 = h1[2] * h2[2]
    if (abs(d1) > 1e-12) cands.add(-(h1[0] * h2[0] + h1[1] * h2[1]) / d1)
    val d2 = h1[2] * h1[2] - h2[2] * h2[2]
    if (abs(d2) > 1e-12) cands.add(-(h1[0] * h1[0] + h1[1] * h1[1] - h2[0] * h2[0] - h2[1] * h2[1]) / d2)
    val good = cands.filter { it > (0.2 * w) * (0.2 * w) && it < (4 * w) * (4 * w) }
    val f = if (good.isNotEmpty()) sqrt(good.average()) else w / 2 / kotlin.math.tan(35 * PI / 180)
    fun kinv(v: DoubleArray) = doubleArrayOf(v[0] / f, v[1] / f, v[2])
    var r1 = kinv(h1); var r2 = kinv(h2); var t = kinv(h3)
    val lam = 2 / (norm(r1) + norm(r2))
    r1 = scale(r1, lam); r2 = scale(r2, lam); t = scale(t, lam)
    if (t[2] < 0) { r1 = scale(r1, -1.0); r2 = scale(r2, -1.0); t = scale(t, -1.0) }
    val r1n = scale(r1, 1 / norm(r1))
    var r2o = sub(r2, scale(r1n, dot(r1n, r2))); r2o = scale(r2o, 1 / norm(r2o))
    var r3 = cross(r1n, r2o)
    fun build(r3x: DoubleArray): Pair<Array<DoubleArray>, DoubleArray> {
        val cols = arrayOf(r1n, r2o, r3x)
        val rows = Array(3) { i -> doubleArrayOf(cols[0][i], cols[1][i], cols[2][i]) }
        val cp = scale(doubleArrayOf(dot(cols[0], t), dot(cols[1], t), dot(cols[2], t)), -1.0)
        return rows to doubleArrayOf(cp[0], cp[1], cp[2] + th)
    }
    var (rows, c) = build(r3)
    if (c[2] < th) { r3 = scale(r3, -1.0); val b = build(r3); rows = b.first; c = b.second }
    val cam = Cam(f, cx, cy, rows, c, w, h)
    val err = world.indices.sumOf { i ->
        val p = project(cam, doubleArrayOf(world[i][0], world[i][1], th))
        hypot(p[0] - taps[i][0], p[1] - taps[i][1])
    } / 4
    val hfov = 2 * atan(w / 2 / f) * 180 / PI
    val ok = c[2] > 0.3 && c[2] < 4 && c[1] < -1.5 && hfov > 30 && hfov < 130
    return Calib(ok, if (ok) "" else "算出的手機位置不合理，請重新點選", cam, err, hfov)
}

fun project(cam: Cam, p: DoubleArray): DoubleArray {
    val d = sub(p, cam.c)
    val c = doubleArrayOf(dot(cam.r[0], d), dot(cam.r[1], d), dot(cam.r[2], d))
    return doubleArrayOf(cam.f * c[0] / c[2] + cam.cx, cam.f * c[1] / c[2] + cam.cy)
}

private fun ray(cam: Cam, u: Double, v: Double): DoubleArray {
    val dc = doubleArrayOf((u - cam.cx) / cam.f, (v - cam.cy) / cam.f, 1.0)
    val r = cam.r
    val d = doubleArrayOf(
        r[0][0] * dc[0] + r[1][0] * dc[1] + r[2][0] * dc[2],
        r[0][1] * dc[0] + r[1][1] * dc[1] + r[2][1] * dc[2],
        r[0][2] * dc[0] + r[1][2] * dc[1] + r[2][2] * dc[2])
    return scale(d, 1 / norm(d))
}

fun toFloor(cam: Cam, u: Double, v: Double, z: Double = 0.03): DoubleArray? {
    val d = ray(cam, u, v); if (d[2] > -1e-6) return null
    return add(cam.c, scale(d, (z - cam.c[2]) / d[2]))
}

private fun heightAt(cam: Cam, u: Double, v: Double, g: DoubleArray): Double {
    val d = ray(cam, u, v); var n = sub(g, cam.c); n[2] = 0.0; n = scale(n, 1 / norm(n))
    val s = dot(sub(g, cam.c), n) / dot(d, n); return cam.c[2] + s * d[2]
}

// ---------- per-frame measurement ----------
/** one pose landmark in image pixels */
class Lm(val x: Double, val y: Double, val vis: Double = 1.0)

class Measure(
    val g: DoubleArray, val fl: DoubleArray, val fr: DoubleArray, val nose: Double, val width: Double, val dist: Double,
    val wrist: DoubleArray, val wristL: DoubleArray, val bodyPx: Double,
)

fun measure(cam: Cam, lm: List<Lm>): Measure? {
    if (max(lm[29].vis, lm[31].vis) < 0.2 || max(lm[30].vis, lm[32].vis) < 0.2) return null // feet not visible
    fun mid(a: Int, b: Int) = doubleArrayOf((lm[a].x + lm[b].x) / 2, (lm[a].y + lm[b].y) / 2)
    val fl = mid(29, 31); val fr = mid(30, 32)
    val gl = toFloor(cam, fl[0], fl[1]) ?: return null
    val gr = toFloor(cam, fr[0], fr[1]) ?: return null
    val g = doubleArrayOf((gl[0] + gr[0]) / 2, (gl[1] + gr[1]) / 2, 0.0)
    if (!(g[1] < -1.0 && g[1] > -7 && abs(g[0]) < 2.8)) return null // not the near player
    val nose = heightAt(cam, lm[0].x, lm[0].y, g)
    val width = hypot(gl[0] - gr[0], gl[1] - gr[1])
    val bodyPx = max(40.0, max(lm[29].y, lm[30].y) - lm[0].y)
    return Measure(g, gl, gr, nose, width, -g[1] - Table.HALF_L,
        doubleArrayOf(lm[16].x, lm[16].y), doubleArrayOf(lm[15].x, lm[15].y), bodyPx)
}

/** index + measurement of the near player (feet behind the near end line, largest), or null */
fun pickNearPlayer(cam: Cam, poses: List<List<Lm>>): Pair<Int, Measure>? {
    var best: Pair<Int, Measure>? = null
    poses.forEachIndexed { i, lm ->
        val m = measure(cam, lm) ?: return@forEachIndexed
        if (best == null || m.bodyPx > best.second.bodyPx) best = i to m
    }
    return best
}

// ---------- coach ----------
internal fun pct(a: List<Double>, p: Double): Double {
    if (a.isEmpty()) return Double.NaN
    val s = a.sorted(); return s[min(s.size - 1, (p * (s.size - 1)).toInt())]
}
private fun P(v: Double) = round(v * 100).toInt()

class Opts(
    var rightHanded: Boolean = true, val targetRatio: Double = 0.90, val sideLimit: Double = 0.25, val minWidth: Double = 0.40,
    val zoneX: Double = 0.95, val zoneY: Double = -2.35, val minDist: Double = 0.15, val maxDist: Double = 1.3,
    val wristRef: Double = 1.0, val tau: Double = 0.4, val actOn: Double = 0.7, val hotT: Double = 0.5,
    val coldT: Double = 1.2, val outT: Double = 0.5, val uprightAt: Double = 0.97, val uprightMax: Double = 0.35,
)

class Issue(val k: String, val sev: Double, val say: List<String>)

class RallySum(
    val t0: Double, val t1: Double, val dur: Double, val ratio: Double, val xMed: Double, val leftShare: Double,
    val width: Double, val dist: Double, val upright: Double, val game: Int, var won: Boolean? = null,
    var issues: List<Issue> = emptyList(), var target: Double = 0.9,
) {
    fun detail(): String = listOf(
        if (ratio.isFinite()) "重心 ${P(ratio)}%（目標 ${P(target)}%）" else "重心校準中", "反手側 ${P(leftShare)}%",
        "步寬 ${P(width)} 公分", "離桌 ${"%.1f".format(dist)} 公尺", "${round(dur).toInt()} 秒").joinToString(" · ")
}

class Cue(val k: String, val text: String, val good: Boolean)

sealed class CoachEvent {
    object Start : CoachEvent()
    object Drop : CoachEvent()
    class End(val rally: RallySum, val cue: Cue?) : CoachEvent()
}

val FOCUS_NAME = mapOf("low" to "重心", "side" to "站位", "width" to "步寬", "far" to "離桌距離", "near" to "離桌距離", "upright" to "保持蹲低")

class Coach(val o: Opts = Opts()) {
    val noseHist = ArrayList<Double>()
    val rallies = ArrayList<RallySum>()
    var game = 0
    var focus: String? = null
        private set
    /** focus and crouch target are fixed for a whole game so the ✓/✗ feedback is judged against a stable bar */
    val focusByGame = HashMap<Int, String>()
    private var gameTarget: Double? = null
    private var state = 0 // 0 idle, 1 rally
    private var hot = 0.0; private var cold = 0.0; private var out = 0.0; private var act = 0.0
    private var lastT: Double? = null
    private var prevM: Measure? = null; private var prevT = 0.0
    private var rT0 = 0.0
    private val rNose = ArrayList<Double>(); private val rX = ArrayList<Double>(); private val rW = ArrayList<Double>(); private val rD = ArrayList<Double>()
    private val lastCues = ArrayList<String>()
    private var goodStreak = 0
    private val rnd = java.util.Random(7)

    val standing: Double get() { val s = pct(noseHist, 0.95); return if (s.isFinite()) s.coerceIn(0.9, 2.1) else Double.NaN }
    val inRally get() = state == 1

    /** personal crouch target: 2 points better than the player's own median (reachable), never easier than 95%
     *  and never stricter than the default. Moves down as the player improves. */
    fun target(): Double = gameTarget ?: o.targetRatio
    private fun personalTarget(): Double {
        val rs = rallies.map { it.ratio }.filter { it.isFinite() }
        if (rs.size < 5) return o.targetRatio
        return max(o.targetRatio, pct(rs, 0.5) - 0.02).coerceAtMost(0.95)
    }

    /** feed one measurement (or null) at time t (seconds, monotonic) */
    fun update(m: Measure?, t: Double): CoachEvent? {
        var ev: CoachEvent? = null
        val dt = lastT?.let { min(0.25, max(0.0, t - it)) } ?: 0.0; lastT = t
        val inZone = m != null && abs(m.g[0]) < o.zoneX && m.g[1] > o.zoneY
        if (m != null) {
            if (inZone) { if (noseHist.size < 3000) noseHist.add(m.nose) else noseHist[rnd.nextInt(3000)] = m.nose }
            val p = prevM
            if (p != null && t > prevT && t - prevT < 0.5) {
                val d = t - prevT
                val w = max(hypot(m.wrist[0] - p.wrist[0], m.wrist[1] - p.wrist[1]),
                    hypot(m.wristL[0] - p.wristL[0], m.wristL[1] - p.wristL[1])) / m.bodyPx / d
                val a = min(w / o.wristRef, 3.0)
                act += (a - act) * (1 - exp(-d / o.tau))
            }
            prevM = m; prevT = t
        } else act *= exp(-dt / o.tau)
        out = if (inZone) 0.0 else out + dt
        val active = inZone && act > o.actOn
        if (state == 0) {
            hot = if (active) hot + dt else 0.0
            if (hot >= o.hotT) {
                state = 1; rT0 = t - hot; cold = 0.0
                rNose.clear(); rX.clear(); rW.clear(); rD.clear(); ev = CoachEvent.Start
            }
        } else {
            if (m != null && inZone) { rNose.add(m.nose); rX.add(if (o.rightHanded) m.g[0] else -m.g[0]); rW.add(m.width); rD.add(m.dist) }
            cold = if (active) 0.0 else cold + dt
            if (out >= o.outT || cold >= o.coldT || t - rT0 > 30) {
                state = 0; hot = 0.0
                val t1 = t - max(out, cold)
                ev = if (t1 - rT0 >= 1.5 && rNose.size > 10) {
                    val s = summarise(rT0, t1); rallies.add(s)
                    if (focus == null && game == 0 && rallies.size >= 3) chooseFocus()
                    CoachEvent.End(s, cue(s))
                } else CoachEvent.Drop
            }
        }
        return ev
    }

    private fun summarise(t0: Double, t1: Double): RallySum {
        val st = if (noseHist.size >= 60) standing else Double.NaN
        val s = RallySum(t0, t1, t1 - t0, pct(rNose, 0.5) / st, pct(rX, 0.5),
            rX.count { it < -o.sideLimit }.toDouble() / rX.size, pct(rW, 0.5), pct(rD, 0.5),
            if (st.isFinite()) rNose.count { it / st > o.uprightAt }.toDouble() / rNose.size else Double.NaN, game)
        s.target = target(); s.issues = issues(s); return s
    }

    private fun issues(s: RallySum): List<Issue> {
        val l = ArrayList<Issue>()
        if (s.ratio.isFinite() && s.ratio > s.target + 0.01) l.add(Issue("low", (s.ratio - s.target) * 20, listOf("重心放低，膝蓋彎", "再蹲低一點", "別站直，膝蓋保持彎")))
        if (s.leftShare > 0.7) l.add(Issue("side", s.leftShare * 1.2, listOf("準備位置往${if (o.rightHanded) "右" else "左"}一點，顧正手", "回到中間準備", "站位別太偏反手")))
        if (s.dist > o.maxDist) l.add(Issue("far", (s.dist - o.maxDist) * 3, listOf("離桌太遠，往前站", "別退太多，靠近球桌")))
        if (s.dist < o.minDist) l.add(Issue("near", (o.minDist - s.dist) * 6, listOf("離桌太近，退半步", "站遠一點，留出揮拍空間")))
        if (s.width < o.minWidth) l.add(Issue("width", (o.minWidth - s.width) * 8, listOf("兩腳再開一點", "步子站寬一點")))
        if (s.upright.isFinite() && s.upright > o.uprightMax) l.add(Issue("upright", (s.upright - o.uprightMax) * 4, listOf("打完別站起來", "每板之間保持蹲低")))
        return l.sortedByDescending { it.sev }
    }

    /** the one thing to work on this game: the issue with the largest total severity over recent rallies */
    fun chooseFocus() {
        val tally = HashMap<String, Double>()
        rallies.takeLast(10).forEach { r -> r.issues.forEach { tally[it.k] = (tally[it.k] ?: 0.0) + it.sev } }
        focus = tally.maxByOrNull { it.value }?.key
        gameTarget = personalTarget()
        focus?.let { focusByGame[game] = it }
    }

    private fun focusOk(k: String, s: RallySum): Boolean? = when (k) {
        "low" -> if (s.ratio.isFinite()) s.ratio <= s.target + 0.01 else null
        "side" -> s.leftShare <= 0.7
        "width" -> s.width >= o.minWidth
        "far", "near" -> s.dist in o.minDist..o.maxDist
        "upright" -> if (s.upright.isFinite()) s.upright <= o.uprightMax else null
        else -> null
    }

    private fun focusText(k: String, ok: Boolean, s: RallySum, streak: Int): String {
        val base = when (k) {
            "low" -> if (ok) "✓ 蹲住了 ${P(s.ratio)}%" else "✗ 又站直了 ${P(s.ratio)}%"
            "side" -> if (ok) "✓ 有回中間" else "✗ 又偏反手 ${P(s.leftShare)}%"
            "width" -> if (ok) "✓ 步寬夠" else "✗ 腳站太窄 ${P(s.width)} 公分"
            "far", "near" -> if (ok) "✓ 離桌距離剛好" else "✗ 離桌 ${"%.1f".format(s.dist)} 公尺"
            "upright" -> if (ok) "✓ 一直保持蹲低" else "✗ 打完就站起來"
            else -> ""
        }
        return if (ok && streak >= 2) "$base（連續 $streak 分）" else base
    }

    fun cue(s: RallySum): Cue? {
        val f = focus
        if (f != null) {
            val ok = focusOk(f, s)
            if (ok != null) {
                var streak = 0
                for (r in rallies.asReversed()) { if (r.game != s.game || focusOk(f, r) != true) break; streak++ }
                return Cue(f, focusText(f, ok, s, streak), ok)
            }
        }
        val list = s.issues
        var pick = list.firstOrNull()
        if (pick != null && lastCues.takeLast(2).let { it.size == 2 && it.all { c -> c == pick.k } } && list.size > 1) pick = list[1]
        if (pick == null && !s.ratio.isFinite()) return null
        if (pick == null) { goodStreak++; lastCues.add("good"); return if (goodStreak % 2 == 1) Cue("good", "很好，保持", true) else null }
        goodStreak = 0
        val n = lastCues.count { it == pick.k }; lastCues.add(pick.k)
        return Cue(pick.k, pick.say[n % pick.say.size], false)
    }

    /** the scorer pressed a button: attach the result to the rally that just ended */
    fun markPoint(meWon: Boolean, t: Double): RallySum? {
        val r = rallies.lastOrNull { it.won == null } ?: return null
        if (t - r.t1 >= 25) return null
        r.won = meWon; return r
    }
    fun undoPoint() { rallies.lastOrNull { it.won != null }?.won = null }
    fun newGame() { game++; chooseFocus() }

    /** between-games notes for the coach: up to 3 short lines, most important first */
    fun notes(g: Int = game): List<String> {
        val r = rallies.filter { it.game == g && it.ratio.isFinite() }
        if (r.size < 2) return listOf("這局資料還不夠，先照原本的打法。")
        class Line(val sev: Double, val t: String)
        val out = ArrayList<Line>()
        val ratio = pct(r.map { it.ratio }, 0.5); val tgt = target(); val hi = tgt + 0.01
        val lab = r.filter { it.won != null }
        val low = lab.filter { it.ratio <= hi }; val up = lab.filter { it.ratio > hi }
        fun w(a: List<RallySum>) = a.count { it.won == true }
        if (low.size >= 2 && up.size >= 2 && w(low).toDouble() / low.size - w(up).toDouble() / up.size >= 0.2)
            out.add(Line(3.0, "蹲低的分贏 ${w(low)}/${low.size}，站直的分只贏 ${w(up)}/${up.size}，重心是這局關鍵"))
        else if (ratio > hi) out.add(Line(2 + (ratio - hi) * 20, "重心偏高（站直的 ${P(ratio)}%，目標 ${P(tgt)}%），先提醒蹲低"))
        val first = rallies.filter { it.game == 0 && it.ratio.isFinite() }
        if (g > 0 && first.size >= 3) { val d = ratio - pct(first.map { it.ratio }, 0.5); if (d > 0.02) out.add(Line(2 + d * 20, "比第一局站得更直（多 ${P(d)}%），可能累了")) }
        val left = r.map { it.leftShare }.average()
        if (left > 0.6) out.add(Line(1 + left, "${P(left)}% 時間站在反手側，正手大角容易空出來"))
        val up2 = r.map { it.upright }.filter { it.isFinite() }
        if (up2.isNotEmpty() && up2.average() > o.uprightMax) out.add(Line(1.4, "每分有 ${P(up2.average())}% 時間是站直的，打完一板就站起來"))
        val dist = pct(r.map { it.dist }, 0.5)
        if (dist > o.maxDist) out.add(Line(1.5, "平均離桌 ${"%.1f".format(dist)} 公尺，退太遠"))
        val width = pct(r.map { it.width }, 0.5)
        if (width < o.minWidth) out.add(Line(1.2, "步寬只有 ${P(width)} 公分，站太窄"))
        val f = focusByGame[g]
        if (f != null) {
            val judged = r.mapNotNull { focusOk(f, it) }
            if (judged.isNotEmpty()) out.add(Line(10.0, "本局重點「${FOCUS_NAME[f]}」做到 ${judged.count { it }}/${judged.size} 分"))
        }
        val lines = out.sortedByDescending { it.sev }.take(3).map { it.t }
        return lines.ifEmpty { listOf("重心 ${P(ratio)}%、站位都在目標內，維持") }
    }
    fun summary(g: Int = game) = notes(g).joinToString("。") + "。"
}

// ---------- where should the phone go? ----------
class Advice(val good: Boolean, val text: String)

fun placementAdvice(cam: Cam, match: Boolean, rightHanded: Boolean): List<Advice> {
    val c = cam.c; val behind = -c[1] - Table.HALF_L; val tips = ArrayList<Advice>()
    fun f1(v: Double) = "%.1f".format(v)
    if (match) {
        tips.add(Advice(c[2] >= 1.4, if (c[2] >= 1.4) "高度 ${f1(c[2])} 公尺，很好" else "手機高度 ${f1(c[2])} 公尺，能再放高到 1.5 公尺以上，球會比較好追"))
        tips.add(Advice(behind in 1.5..6.0, when {
            behind < 1.5 -> "離端線只有 ${f1(behind)} 公尺，往後退一點，選手才會整個入鏡"
            behind > 6 -> "離端線 ${f1(behind)} 公尺，太遠了，人會太小"
            else -> "離端線 ${f1(behind)} 公尺，很好" }))
        tips.add(Advice(abs(c[0]) <= 1.5, if (abs(c[0]) <= 1.5) "左右位置很好" else "手機偏向${if (c[0] > 0) "右" else "左"}邊 ${f1(abs(c[0]))} 公尺，盡量對著球桌中線"))
    } else {
        val side = if (rightHanded) 1.0 else -1.0; val py = -Table.HALF_L - 0.5
        val az = atan2(c[0] * side, py - c[1]) * 180 / PI; val d = hypot(c[0], c[1] - py)
        val hand = if (rightHanded) "右" else "左"
        tips.add(Advice(az in 30.0..60.0, when {
            az < 30 -> "角度只有 ${round(az).toInt()} 度，手機往選手${hand}手邊移，斜 45 度最好"
            az > 60 -> "角度 ${round(az).toInt()} 度太側面了，往選手後方移一點"
            else -> "角度 ${round(az).toInt()} 度，很好" }))
        tips.add(Advice(d in 1.8..3.8, when {
            d < 1.8 -> "離選手只有 ${f1(d)} 公尺，往後退一點"
            d > 3.8 -> "離選手 ${f1(d)} 公尺，靠近一點動作才看得清楚"
            else -> "距離 ${f1(d)} 公尺，很好" }))
        tips.add(Advice(c[2] in 0.9..1.7, when {
            c[2] < 0.9 -> "手機太低，放到胸口高度"
            c[2] > 1.7 -> "手機太高，放到胸口高度"
            else -> "高度 ${f1(c[2])} 公尺，很好" }))
    }
    return tips
}
