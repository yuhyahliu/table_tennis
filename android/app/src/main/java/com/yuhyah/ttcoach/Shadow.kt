// 空拍模式 (shadow-swing practice) engine — pure Kotlin, unit-tested on the JVM.
// Works on MediaPipe *world* landmarks (metres, hip-centred), so no table or calibration is needed.
// Standard strokes (assets/*_template.txt):
//   forehand: figshare 10.6084/m9.figshare.28881086 (CC BY 4.0), 340 forehands of 34 provincial athletes (MediaPipe)
//   backhand: figshare 31746358 TTMD6 (CC BY 4.0), backhand-attack motions from infrared motion capture
package com.yuhyah.ttcoach

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 14-joint skeleton, playing side P / other side N, world z up (metres) */
object J { const val PELVIS = 0; const val HEAD = 1; const val SH_N = 2; const val EL_N = 3; const val WR_N = 4; const val SH_P = 5; const val EL_P = 6
    const val WR_P = 7; const val HIP_N = 8; const val KN_N = 9; const val AN_N = 10; const val HIP_P = 11; const val KN_P = 12; const val AN_P = 13
    val NAMES = listOf("pelvis", "head", "shN", "elN", "wrN", "shP", "elP", "wrP", "hipN", "knN", "anN", "hipP", "knP", "anP")
}
typealias Pose = Array<DoubleArray>

private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
private operator fun DoubleArray.minus(o: DoubleArray) = v(this[0] - o[0], this[1] - o[1], this[2] - o[2])
private operator fun DoubleArray.plus(o: DoubleArray) = v(this[0] + o[0], this[1] + o[1], this[2] + o[2])
private operator fun DoubleArray.times(s: Double) = v(this[0] * s, this[1] * s, this[2] * s)
private fun DoubleArray.dot(o: DoubleArray) = this[0] * o[0] + this[1] * o[1] + this[2] * o[2]
private fun DoubleArray.len() = sqrt(dot(this))
private fun mid(a: DoubleArray, b: DoubleArray) = v((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)

/** MediaPipe world landmarks (x right, y down, z away from camera) → 14-joint z-up pose */
fun poseFromMpWorld(lm: List<DoubleArray>, rightHanded: Boolean): Pose {
    fun w(i: Int) = v(lm[i][0], lm[i][2], -lm[i][1])
    val s = if (rightHanded) intArrayOf(12, 14, 16, 24, 26, 28, 11, 13, 15, 23, 25, 27) else intArrayOf(11, 13, 15, 23, 25, 27, 12, 14, 16, 24, 26, 28)
    return arrayOf(mid(w(23), w(24)), w(0), w(s[6]), w(s[7]), w(s[8]), w(s[0]), w(s[1]), w(s[2]), w(s[9]), w(s[10]), w(s[11]), w(s[3]), w(s[4]), w(s[5]))
}
/** raw MediaPipe world landmark → z-up world point (same convention as poseFromMpWorld) */
fun mpWorldToZUp(x: Double, y: Double, z: Double) = doubleArrayOf(x, z, -y)

/** how much the shoulder line is turned away from facing the camera (0° = square on, 90° = side on), from raw MediaPipe world landmarks */
fun bodyTurnDeg(lm: List<DoubleArray>): Double {
    val dx = lm[12][0] - lm[11][0]; val dz = lm[12][2] - lm[11][2]
    return atan2(abs(dz), abs(dx)) * 180 / PI
}

private fun angle(a: DoubleArray, b: DoubleArray, c: DoubleArray): Double {
    val u = a - b; val w = c - b; val cs = u.dot(w) / (u.len() * w.len() + 1e-9)
    return acos(cs.coerceIn(-1.0, 1.0)) * 180 / PI
}
private fun heading(d: DoubleArray) = atan2(d[1], d[0]) * 180 / PI

/** stroke-fixed frame: origin between the ankles on the floor, x toward the playing-side ankle, z up, y forward */
class BodyFrame(val o: DoubleArray, val x: DoubleArray, val y: DoubleArray) {
    fun toLocal(p: DoubleArray): DoubleArray { val d = p - o; return v(d.dot(x), d.dot(y), d[2]) }
    fun toWorld(l: DoubleArray) = o + x * l[0] + y * l[1] + v(0.0, 0.0, l[2])
    fun flipped() = BodyFrame(o, x, y * -1.0)
    companion object {
        fun of(p: Pose, rightHanded: Boolean): BodyFrame {
            val d = p[J.AN_P] - p[J.AN_N]; d[2] = 0.0; val n = max(1e-6, d.len()); val x = d * (1 / n)
            var y = v(-x[1], x[0], 0.0)            // z × x
            if (!rightHanded) y = y * -1.0         // mirror image for left-handers
            val o = mid(p[J.AN_N], p[J.AN_P]); o[2] = min(p[J.AN_N][2], p[J.AN_P][2])
            return BodyFrame(o, x, y)
        }
    }
}

/** the standard stroke (bone directions per 1/120 s around impact) */
class Template(text: String) {
    var t0 = -0.6; var dt = 1 / 120.0; var n = 121; var impact = 72; var backswingT = -0.15; var finishT = 0.15
    val tree = ArrayList<Pair<String, String>>(); val dirs = HashMap<String, Array<DoubleArray>>()
    var pelvis: Array<DoubleArray> = emptyArray(); val lengths = HashMap<String, Double>(); val stats = HashMap<String, Pair<Double, Double>>()
    init {
        for (line in text.lines()) {
            val p = line.trim().split(' '); if (p.size < 2 || p[0].startsWith("#")) continue
            when (p[0]) {
                "meta" -> { t0 = p[1].toDouble(); dt = p[2].toDouble(); n = p[3].toInt(); impact = p[4].toInt(); backswingT = p[5].toDouble(); finishT = p[6].toDouble() }
                "dir" -> { val k = p[1]; val nums = p.drop(2).map { it.toDouble() }; dirs[k] = Array(n) { i -> v(nums[3 * i], nums[3 * i + 1], nums[3 * i + 2]) }
                    val (a, b) = k.split('-'); tree.add(a to b) }
                "pelvis" -> { val nums = p.drop(1).map { it.toDouble() }; pelvis = Array(n) { i -> v(nums[3 * i], nums[3 * i + 1], nums[3 * i + 2]) } }
                "len" -> lengths[p[1]] = p[2].toDouble()
                "stat" -> stats[p[1]] = p[2].toDouble() to p[3].toDouble()
            }
        }
    }
    fun time(i: Int) = t0 + i * dt
    fun index(t: Double) = ((t - t0) / dt).toInt().coerceIn(0, n - 1)

    /** rebuild the standard motion with the given segment lengths; returns n local-frame poses */
    fun build(len: Map<String, Double> = lengths): Array<Pose> {
        val leg = (len["hipP-knP"] ?: 0.4) + (len["knP-anP"] ?: 0.4)
        val out = Array(n) { i ->
            val P = HashMap<String, DoubleArray>(); P["pelvis"] = pelvis[i] * leg
            for ((a, b) in tree) P[b] = P[a]!! + dirs["$a-$b"]!![i] * (len["$a-$b"] ?: lengths["$a-$b"] ?: 0.3)
            Array(14) { j -> P[J.NAMES[j]]!!.copyOf() }
        }
        val floor = min(out[impact][J.AN_N][2], out[impact][J.AN_P][2])
        out.forEach { p -> p.forEach { it[2] -= floor } }
        return out
    }
}

fun segmentLengths(poses: List<Pose>): Map<String, Double> {
    fun at(p: Pose, n: String) = if (n == "neck") mid(p[J.SH_N], p[J.SH_P]) else p[J.NAMES.indexOf(n)]
    val pairs = listOf("pelvis" to "hipP", "pelvis" to "hipN", "hipP" to "knP", "knP" to "anP", "hipN" to "knN", "knN" to "anN", "pelvis" to "neck",
        "neck" to "shP", "neck" to "shN", "shP" to "elP", "elP" to "wrP", "shN" to "elN", "elN" to "wrN", "neck" to "head")
    return pairs.associate { (a, b) -> "$a-$b" to pct(poses.map { (at(it, b) - at(it, a)).len() }, 0.5) }
}

/** kid-friendly wording; `bad` = +1 when a larger value is the problem; `at` = which moment shows it best */
class IssueDef(val key: String, val bad: Int, val cue: String, val focusOk: String, val joint: Int, val at: String, val turn: Boolean = false)

/** forehand / backhand differences: template, which way the swing goes sideways, what we judge */
class StrokeSpec(val key: String, val name: String, val asset: String, val lateralSign: Int, val issues: List<IssueDef>) {
    companion object {
        val FOREHAND = StrokeSpec("fh", "正手", "fh_template.txt", -1, listOf(
            IssueDef("knee_bs", -1, "膝蓋彎，像坐高腳椅", "膝蓋有彎，好棒！", J.PELVIS, "bs"),
            IssueDef("sh_turn", -1, "腰轉過去，像關門", "腰有轉，好棒！", J.SH_P, "fin", turn = true),
            IssueDef("wrist_rise", -1, "球拍從口袋刷到額頭", "有往上刷，好棒！", J.WR_P, "fin"),
            IssueDef("elbow_imp", +1, "手臂彎，像抱一顆大氣球", "手臂有彎，好棒！", J.WR_P, "imp"),
            IssueDef("upperarm_imp", +1, "手肘靠近身體，夾一張紙", "手肘有夾住，好棒！", J.EL_P, "imp"),
            IssueDef("pel_turn", -1, "屁股也要跟著轉", "屁股有轉，好棒！", J.HIP_P, "fin", turn = true)))
        val BACKHAND = StrokeSpec("bh", "反手", "bh_template.txt", +1, listOf(
            IssueDef("knee_bs", -1, "膝蓋彎，像坐高腳椅", "膝蓋有彎，好棒！", J.PELVIS, "bs"),
            IssueDef("contact_fwd", -1, "在身體前面打球", "有在前面打，好棒！", J.WR_P, "imp"),
            IssueDef("elbow_fwd", -1, "手肘放在肚子前面", "手肘位置很好！", J.EL_P, "imp"),
            IssueDef("wrist_rise", -1, "球拍往前上方送出去", "有往前送，好棒！", J.WR_P, "fin")))
        fun of(key: String?) = if (key == "bh") BACKHAND else FOREHAND
    }
}

/** local-frame curves → all key values; ib/i1 = backswing end / finish indices on the template grid */
fun swingValues(local: Array<Pose>, t0: Double, dt: Double, stroke: String = "fh"): Triple<Map<String, Double>, Int, Int> {
    val n = local.size; val i0 = ((0 - t0) / dt).toInt().coerceIn(1, n - 2)
    val fwd = DoubleArray(n) { local[it][J.WR_P][1] - local[it][J.PELVIS][1] }
    val wh = DoubleArray(n) { local[it][J.WR_P][2] - local[it][J.PELVIS][2] }
    // same phase definitions as the template statistics (export_tpl.py)
    val ib = if (stroke == "bh") (((-0.4 - t0) / dt).toInt().coerceAtLeast(0) until i0).minByOrNull { wh[it] } ?: 0
             else (0 until i0).minByOrNull { fwd[it] } ?: 0
    val fe = ((0.35 - t0) / dt).toInt().coerceAtMost(n - 1)
    val i1 = (i0..fe).maxByOrNull { wh[it] } ?: i0
    fun shRot(i: Int) = heading(local[i][J.SH_P] - local[i][J.SH_N])
    fun pelRot(i: Int) = heading(local[i][J.HIP_P] - local[i][J.HIP_N])
    fun knee(i: Int) = (180 - angle(local[i][J.HIP_P], local[i][J.KN_P], local[i][J.AN_P]) + 180 - angle(local[i][J.HIP_N], local[i][J.KN_N], local[i][J.AN_N])) / 2
    val m = mapOf(
        "sh_turn" to shRot(i1) - shRot(ib), "wrist_rise" to wh[i1] - wh[ib],
        "elbow_imp" to angle(local[i0][J.SH_P], local[i0][J.EL_P], local[i0][J.WR_P]),
        "upperarm_imp" to angle(local[i0][J.EL_P], local[i0][J.SH_P], local[i0][J.HIP_P]),
        "knee_bs" to knee(ib), "pel_turn" to pelRot(i1) - pelRot(ib),
        "contact_fwd" to fwd[i0], "elbow_fwd" to local[i0][J.EL_P][1] - local[i0][J.PELVIS][1])
    return Triple(m, ib, i1)
}

class SwingResult(
    val tImpact: Double, val stars: Int, val values: Map<String, Double>, val z: Map<String, Double>, val worst: IssueDef,
    val local: Array<Pose>, val frame: BodyFrame, val ib: Int, val i1: Int, val focusOk: Boolean?,
    /** 0..1: how much the wrist path (sideways + up/down, the directions a camera sees well) looks like the standard stroke */
    val shape: Double = 1.0,
) {
    val zmax get() = z.values.maxOrNull() ?: 0.0
}

sealed class ShadowEvent {
    /** a swing was counted (right after its follow-through, once its path was checked) */
    class Detected(val t: Double, val speed: Double, val count: Int) : ShadowEvent()
    /** its score (sent together with Detected) */
    class Scored(val r: SwingResult) : ShadowEvent()
}

/**
 * Counts swings of the chosen stroke and scores them against the template.
 * 1. candidate = a peak of wrist speed above an adaptive threshold (kids swing slower than athletes), moving the right way sideways;
 * 2. ~0.4 s later, once the follow-through is in, the wrist path around the peak must look like the standard stroke
 *    (sideways + vertical only: both lie in the image plane; depth from one camera is unreliable). Waving, walking,
 *    the return movement and random flailing fail this check and are not counted.
 * `hold` = not listening (paused, between rounds, player walking).
 */
class ShadowCoach(val tpl: Template, var rightHanded: Boolean = true, val spec: StrokeSpec = StrokeSpec.FOREHAND) {
    private class F(val t: Double, val p: Pose)
    private val buf = ArrayDeque<F>()
    private var lastSwing = -9.0
    private val pending = ArrayDeque<Double>()
    private var sp1 = 0.0; private var sp2 = 0.0
    private val peaks = ArrayDeque<Double>()
    var focus: String? = null
    var hold = false
    private var lastCand = -9.0
    private val pendSpeed = HashMap<Double, Double>()
    /** the standard wrist path (sideways, up) relative to the pelvis, in arm lengths, on the template grid */
    private val tplPath: Array<DoubleArray> = tpl.build().let { g ->
        val arm = (tpl.lengths["shP-elP"] ?: 0.28) + (tpl.lengths["elP-wrP"] ?: 0.21)
        Array(tpl.n) { i -> doubleArrayOf((g[i][J.WR_P][0] - g[i][J.PELVIS][0]) / arm, (g[i][J.WR_P][2] - g[i][J.PELVIS][2]) / arm) }
    }
    val results = ArrayList<SwingResult>()
    /** candidates that failed the path check (time, shape), for tests and the debug view */
    val shapeRejects = ArrayList<Pair<Double, Double>>()
    var count = 0; private set
    /** live wrist speed (m/s) and the current counting threshold, for the energy bar and the debug view */
    var speed = 0.0; private set
    var lastReject = ""; private set

    fun threshold(): Double {
        if (peaks.size < 3) return 1.0
        return (0.45 * pct(peaks.toList(), 0.5)).coerceIn(0.8, 2.5)
    }
    /** a "full" energy bar = the player's own typical swing speed */
    fun typicalSpeed(): Double = if (peaks.size < 3) 3.0 else pct(peaks.toList(), 0.5)

    fun reset() { buf.clear(); pending.clear(); pendSpeed.clear(); results.clear(); lastSwing = -9.0; lastCand = -9.0; count = 0 }
    fun resetCount() { count = 0 }
    fun recentPoses(): List<Pose> = buf.map { it.p }

    fun feed(t: Double, p: Pose): List<ShadowEvent> {
        val ev = ArrayList<ShadowEvent>()
        buf.addLast(F(t, p)); while (buf.isNotEmpty() && t - buf.first().t > 2.5) buf.removeFirst()
        if (buf.size >= 3) {
            val a = buf[buf.size - 3]; val b = buf[buf.size - 2]; val c = buf[buf.size - 1]
            val dtc = max(1e-3, c.t - a.t)
            val sp = (c.p[J.WR_P] - a.p[J.WR_P]).len() / dtc
            speed = sp
            if (sp1 > sp2 && sp1 >= sp) {                        // local maximum at b
                val thr = threshold()
                val fr = BodyFrame.of(b.p, rightHanded)
                val vel = (c.p[J.WR_P] - a.p[J.WR_P]) * (1 / dtc)
                val lateral = vel.dot(fr.x) * spec.lateralSign     // toward the right side for this stroke?
                when {
                    hold -> {}
                    sp1 < thr -> lastReject = "太慢 %.1f < %.1f m/s".format(sp1, thr)
                    b.t - lastSwing < MIN_GAP -> lastReject = "太接近上一下"
                    b.t - lastCand < 0.3 -> {}                                     // same movement
                    lateral < -0.3 -> lastReject = "方向不對（收拍回來？）"
                    vel[2] < -0.8 -> lastReject = "往下揮"
                    else -> { lastCand = b.t; pending.addLast(b.t); pendSpeed[b.t] = sp1 }
                }
            }
            sp2 = sp1; sp1 = sp
        }
        while (pending.isNotEmpty() && t >= pending.first() + 0.42) {
            val tp = pending.removeFirst(); val sp = pendSpeed.remove(tp) ?: 0.0
            if (hold || tp - lastSwing < MIN_GAP) continue
            val r = evaluate(tp) ?: continue
            if (r.shape < SHAPE_MIN) { lastReject = "不像${spec.name}（像 %.0f%%）".format(r.shape * 100); shapeRejects.add(tp to r.shape); continue }
            lastSwing = tp; count++; lastReject = ""
            peaks.addLast(sp); while (peaks.size > 8) peaks.removeFirst()
            results.add(r)
            ev.add(ShadowEvent.Detected(tp, sp, count)); ev.add(ShadowEvent.Scored(r))
        }
        return ev
    }

    private fun sample(tq: Double): Pose {
        val arr = buf.toList(); val k = arr.indexOfFirst { it.t >= tq }
        if (k <= 0) return arr[if (k == 0) 0 else arr.size - 1].p
        val a = arr[k - 1]; val b = arr[k]; val w = (tq - a.t) / max(1e-6, b.t - a.t)
        return Array(14) { j -> a.p[j] * (1 - w) + b.p[j] * w }
    }

    private fun evaluate(tImpact: Double): SwingResult? {
        if (buf.isEmpty() || buf.first().t > tImpact + tpl.t0 + 0.1) return null
        val world = Array(tpl.n) { i -> sample(tImpact + tpl.time(i)) }
        var fr = BodyFrame.of(world[tpl.impact], rightHanded)
        // forward = the way the wrist travels through impact (robust to left/right conventions)
        val a = world[tpl.index(-0.25)][J.WR_P]; val b = world[tpl.index(0.1)][J.WR_P]
        if ((b - a).dot(fr.y) < 0) fr = fr.flipped()
        val local = Array(tpl.n) { i -> Array(14) { j -> fr.toLocal(world[i][j]) } }
        val (vals, ib, i1) = swingValues(local, tpl.t0, tpl.dt, spec.key)
        val z = HashMap<String, Double>()
        for (d in spec.issues) { val (med, iqr) = tpl.stats[d.key] ?: continue; z[d.key] = d.bad * ((vals[d.key] ?: med) - med) / (iqr / 1.35 + 1e-9) }
        val worst = spec.issues.maxByOrNull { z[it.key] ?: -9.0 }!!
        val zmax = z.values.maxOrNull() ?: 0.0
        // lenient on purpose: ~90% of the athletes' own strokes score 3 stars at 30 fps
        val stars = if (zmax < 2.5) 3 else if (zmax < 4.0) 2 else 1
        val fok = focus?.let { (z[it] ?: 0.0) < 1.0 }
        return SwingResult(tImpact, stars, vals, z, worst, local, fr, ib, i1, fok, shapeScore(local))
    }

    /**
     * how much the wrist path looks like the standard one: correlation of positions AND of velocities
     * (the velocity term tells a swing from the same path played backwards), best of small time shifts,
     * damped if the swing is much smaller than the standard one
     */
    fun shapeScore(local: Array<Pose>): Double {
        val arm = pct(local.map { (it[J.SH_P] - it[J.EL_P]).len() + (it[J.EL_P] - it[J.WR_P]).len() }, 0.5).coerceAtLeast(0.2)
        val kid = Array(local.size) { i -> doubleArrayOf((local[i][J.WR_P][0] - local[i][J.PELVIS][0]) / arm, (local[i][J.WR_P][2] - local[i][J.PELVIS][2]) / arm) }
        val a = tpl.index(-0.45); val b = tpl.index(0.30)
        val tRange = (a..b).maxOf { tplPath[it][0] } - (a..b).minOf { tplPath[it][0] }
        fun corr(f: (Int) -> DoubleArray, g: (Int) -> DoubleArray, lo: Int, hi: Int): Double {
            val m = hi - lo + 1
            val fm = DoubleArray(2) { d -> (lo..hi).sumOf { f(it)[d] } / m }; val gm = DoubleArray(2) { d -> (lo..hi).sumOf { g(it)[d] } / m }
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0
            for (i in lo..hi) { val x = f(i); val y = g(i); for (d in 0..1) { val u = x[d] - fm[d]; val w = y[d] - gm[d]; sxy += u * w; sxx += u * u; syy += w * w } }
            return sxy / sqrt(sxx * syy + 1e-12)
        }
        val h = 3
        fun vel(p: Array<DoubleArray>, i: Int) = doubleArrayOf(p[i + h][0] - p[i - h][0], p[i + h][1] - p[i - h][1])
        var best = -1.0
        for (lag in -12..12 step 3) {
            val lo = max(max(a, h), h - lag); val hi = min(min(b, tpl.n - 1 - h), local.size - 1 - h - lag); if (hi - lo < 20) continue
            val rp = corr({ kid[it + lag] }, { tplPath[it] }, lo, hi)
            val rv = corr({ vel(kid, it + lag) }, { vel(tplPath, it) }, lo, hi)
            val kRange = (lo..hi).maxOf { kid[it + lag][0] } - (lo..hi).minOf { kid[it + lag][0] }
            val amp = (kRange / (tRange + 1e-9) / 0.35).coerceAtMost(1.0)
            best = max(best, min(rp, rv) * amp)
        }
        return best
    }

    companion object { const val SHAPE_MIN = 0.65; const val MIN_GAP = 0.6 }

    /** the issue to work on after a set: the one with the largest total badness */
    fun chooseFocus(last: Int = 5): IssueDef {
        val rs = results.takeLast(last)
        return spec.issues.maxByOrNull { d -> rs.sumOf { max(0.0, it.z[d.key] ?: 0.0) } }!!
    }

    /** spoken line for a swing: praise first, then at most one fix */
    fun speech(r: SwingResult, streak: Int): String {
        val f = focus
        if (f != null && r.focusOk != null) {
            val d = spec.issues.first { it.key == f }
            return if (r.focusOk) (if (streak >= 3) "連續 $streak 下！" else "") + d.focusOk else "再一次，" + d.cue
        }
        return when (r.stars) { 3 -> if (streak >= 3) "連續 $streak 下，很漂亮！" else listOf("好棒！", "這下很漂亮！", "漂亮！").random()
            2 -> "不錯！" + r.worst.cue
            else -> "加油，" + r.worst.cue }
    }
}

/**
 * Follows where the player is in the swing so the shadow moves with them instead of on a fixed clock.
 * Matches the playing wrist (sideways + height relative to the pelvis, the two directions a camera sees well)
 * to the template, only allowing small steps back, and restarts from the ready pose when the player returns to it.
 */
class PhaseTracker(tpl: Template, private val rightHanded: Boolean) {
    private val g = tpl.build()
    private val n = tpl.n
    private val arm = (tpl.lengths["shP-elP"] ?: 0.28) + (tpl.lengths["elP-wrP"] ?: 0.21)
    private val traj = Array(n) { i -> doubleArrayOf((g[i][J.WR_P][0] - g[i][J.PELVIS][0]) / arm, (g[i][J.WR_P][2] - g[i][J.PELVIS][2]) / arm) }
    var index = 0; private set

    fun update(kid: Pose, kidArm: Double): Int {
        val fr = BodyFrame.of(kid, rightHanded)
        val w = fr.toLocal(kid[J.WR_P]); val pv = fr.toLocal(kid[J.PELVIS])
        val q = doubleArrayOf((w[0] - pv[0]) / kidArm, (w[2] - pv[2]) / kidArm)
        fun d(i: Int) = (traj[i][0] - q[0]).let { it * it } + (traj[i][1] - q[1]).let { it * it }
        val lo = max(0, index - 6); val hi = min(n - 1, index + 30)
        var best = index; var bd = Double.MAX_VALUE
        for (i in lo..hi) { val c = d(i) + (if (i < index) 0.002 * (index - i) else 0.0); if (c < bd) { bd = c; best = i } }
        // back at the ready pose after a finish → start over
        if (index > n * 3 / 4 && d(0) < bd * 0.6) best = 0
        index = best
        return index
    }
}

/** least-squares affine map from z-up world points to image points (weak perspective), for drawing the shadow */
class Affine(private val m: Array<DoubleArray>) {
    fun map(p: DoubleArray) = doubleArrayOf(m[0][0] * p[0] + m[1][0] * p[1] + m[2][0] * p[2] + m[3][0], m[0][1] * p[0] + m[1][1] * p[1] + m[2][1] * p[2] + m[3][1])
    companion object {
        fun fit(world: List<DoubleArray>, img: List<DoubleArray>, w: List<Double>): Affine? {
            val A = Array(4) { DoubleArray(4) }; val bx = DoubleArray(4); val by = DoubleArray(4)
            for (k in world.indices) {
                val r = doubleArrayOf(world[k][0], world[k][1], world[k][2], 1.0); val wk = w[k]
                for (i in 0 until 4) { for (j in 0 until 4) A[i][j] += wk * r[i] * r[j]; bx[i] += wk * r[i] * img[k][0]; by[i] += wk * r[i] * img[k][1] }
            }
            for (i in 0 until 3) A[i][i] += 1e-6
            val sx = solve(A.map { it.copyOf() }.toTypedArray(), bx) ?: return null
            val sy = solve(A.map { it.copyOf() }.toTypedArray(), by) ?: return null
            return Affine(Array(4) { i -> doubleArrayOf(sx[i], sy[i]) })
        }
    }
}

/** place a local-frame template pose on the player: their facing, their pelvis position, their floor */
fun placeOn(local: Pose, kid: Pose, rightHanded: Boolean): Pose {
    val fr = BodyFrame.of(kid, rightHanded)
    val w = Array(14) { fr.toWorld(local[it]) }
    val dx = kid[J.PELVIS][0] - w[J.PELVIS][0]; val dy = kid[J.PELVIS][1] - w[J.PELVIS][1]
    w.forEach { it[0] += dx; it[1] += dy }
    val dz = min(kid[J.AN_N][2], kid[J.AN_P][2]) - min(w[J.AN_N][2], w[J.AN_P][2]); w.forEach { it[2] += dz }
    return w
}

/** template grid index to show at time dt (s, relative to the player's impact) so the shadow hits, finishes when they do */
fun warpIndex(tpl: Template, r: SwingResult, dt: Double): Int {
    val kb = tpl.time(r.ib); val kf = tpl.time(r.i1)
    val gb = tpl.backswingT; val gf = tpl.finishT
    val tau = when {
        dt <= kb -> gb + (dt - kb)
        dt <= 0 -> if (kb < -1e-6) gb * (dt / kb) else 0.0
        dt <= kf -> if (kf > 1e-6) gf * (dt / kf) else 0.0
        else -> gf + (dt - kf)
    }
    return tpl.index(tau)
}
