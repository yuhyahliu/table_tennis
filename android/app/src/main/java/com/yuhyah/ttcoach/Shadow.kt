// 空拍模式 (shadow-swing practice) engine — pure Kotlin, unit-tested on the JVM.
// Works on MediaPipe *world* landmarks (metres, hip-centred), so no table or calibration is needed.
// The standard forehand comes from the CC BY 4.0 figshare dataset 10.6084/m9.figshare.28881086
// (340 forehands of 34 provincial athletes, see assets/fh_template.txt).
package com.yuhyah.ttcoach

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 14-joint skeleton, playing side P / other side N, world z up (metres) */
object J { const val PELVIS = 0; const val HEAD = 1; const val SH_N = 2; const val EL_N = 3; const val WR_N = 4; const val SH_P = 5; const val EL_P = 6
    const val WR_P = 7; const val HIP_N = 8; const val KN_N = 9; const val AN_N = 10; const val HIP_P = 11; const val KN_P = 12; const val AN_P = 13
    val NAMES = listOf("pelvis", "head", "shN", "elN", "wrN", "shP", "elP", "wrP", "hipN", "knN", "anN", "hipP", "knP", "anP")
    val EDGES = listOf(8 to 11, 8 to 9, 9 to 10, 11 to 12, 12 to 13, 2 to 5, 2 to 3, 3 to 4, 5 to 6, 6 to 7, 2 to 8, 5 to 11)
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

private fun angle(a: DoubleArray, b: DoubleArray, c: DoubleArray): Double {
    val u = a - b; val w = c - b; val cs = u.dot(w) / (u.len() * w.len() + 1e-9)
    return acos(cs.coerceIn(-1.0, 1.0)) * 180 / PI
}
private fun heading(d: DoubleArray) = atan2(d[1], d[0]) * 180 / PI

/** stroke-fixed frame: origin between the ankles on the floor, x toward the playing-side ankle, z up, y forward */
class BodyFrame(val o: DoubleArray, val x: DoubleArray, val y: DoubleArray) {
    fun toLocal(p: DoubleArray): DoubleArray { val d = p - o; return v(d.dot(x), d.dot(y), d[2]) }
    fun toWorld(l: DoubleArray) = o + x * l[0] + y * l[1] + v(0.0, 0.0, l[2])
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

/** the standard forehand (bone directions per 1/120 s around impact) */
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

/** per-swing values compared with the standard (same definitions as the offline analysis) */
class SwingValues(val shTurn: Double, val wristRise: Double, val elbowImp: Double, val upperarmImp: Double, val kneeBs: Double, val pelTurn: Double) {
    fun get(k: String) = when (k) { "sh_turn" -> shTurn; "wrist_rise" -> wristRise; "elbow_imp" -> elbowImp; "upperarm_imp" -> upperarmImp; "knee_bs" -> kneeBs; else -> pelTurn }
}

/** local-frame curves → key values at backswing end, impact and finish */
fun swingValues(local: Array<Pose>, t0: Double, dt: Double): Triple<SwingValues, Int, Int> {
    val n = local.size; val i0 = ((0 - t0) / dt).toInt().coerceIn(1, n - 2)
    val fwd = DoubleArray(n) { local[it][J.WR_P][1] - local[it][J.PELVIS][1] }
    val wh = DoubleArray(n) { local[it][J.WR_P][2] - local[it][J.PELVIS][2] }
    // same phase definitions as the template statistics: backswing end = most-back wrist before impact,
    // finish = highest wrist within 0.35 s after impact
    val ib = (0 until i0).minByOrNull { fwd[it] } ?: 0
    val fe = ((0.35 - t0) / dt).toInt().coerceAtMost(n - 1)
    val i1 = (i0..fe).maxByOrNull { wh[it] } ?: i0
    fun shRot(i: Int) = heading(local[i][J.SH_P] - local[i][J.SH_N])
    fun pelRot(i: Int) = heading(local[i][J.HIP_P] - local[i][J.HIP_N])
    fun knee(i: Int) = (180 - angle(local[i][J.HIP_P], local[i][J.KN_P], local[i][J.AN_P]) + 180 - angle(local[i][J.HIP_N], local[i][J.KN_N], local[i][J.AN_N])) / 2
    val v = SwingValues(shRot(i1) - shRot(ib), wh[i1] - wh[ib], angle(local[i0][J.SH_P], local[i0][J.EL_P], local[i0][J.WR_P]),
        angle(local[i0][J.EL_P], local[i0][J.SH_P], local[i0][J.HIP_P]), knee(ib), pelRot(i1) - pelRot(ib))
    return Triple(v, ib, i1)
}

/** kid-friendly wording; `bad` = +1 when a larger value is the problem */
class IssueDef(val key: String, val bad: Int, val cue: String, val focusOk: String, val part: String)
val ISSUES = listOf(
    IssueDef("knee_bs", -1, "膝蓋彎，像坐高腳椅", "膝蓋有彎，好棒！", "膝蓋"),
    IssueDef("sh_turn", -1, "腰轉過去，像關門", "腰有轉，好棒！", "轉腰"),
    IssueDef("wrist_rise", -1, "球拍從口袋刷到額頭", "有往上刷，好棒！", "往上刷"),
    IssueDef("elbow_imp", +1, "手臂彎，像抱一顆大氣球", "手臂有彎，好棒！", "手臂"),
    IssueDef("upperarm_imp", +1, "手肘靠近身體，夾一張紙", "手肘有夾住，好棒！", "手肘"),
    IssueDef("pel_turn", -1, "屁股也要跟著轉", "屁股有轉，好棒！", "轉屁股"),
)

class SwingResult(
    val tImpact: Double, val stars: Int, val values: SwingValues, val z: Map<String, Double>, val worst: IssueDef,
    val local: Array<Pose>, val frame: BodyFrame, val ib: Int, val i1: Int, val focusOk: Boolean?,
)

/**
 * Detects forehand shadow swings from a stream of poses and scores each one against the template.
 * feed() returns a result about 0.45 s after each swing (it needs the follow-through).
 */
class ShadowCoach(val tpl: Template, var rightHanded: Boolean = true) {
    private class F(val t: Double, val p: Pose)
    private val buf = ArrayDeque<F>()
    private var lastSwing = -9.0
    private var pending: Double? = null
    private var sp1 = 0.0; private var sp2 = 0.0
    var focus: String? = null
    val results = ArrayList<SwingResult>()
    var minSpeed = 1.8

    fun reset() { buf.clear(); pending = null; results.clear(); lastSwing = -9.0 }

    /** current poses of the player in the recent window (for lengths / overlay) */
    fun recentPoses(): List<Pose> = buf.map { it.p }

    fun feed(t: Double, p: Pose): SwingResult? {
        buf.addLast(F(t, p)); while (buf.isNotEmpty() && t - buf.first().t > 2.5) buf.removeFirst()
        // playing-wrist speed (central difference over the last three samples)
        if (buf.size >= 3) {
            val a = buf[buf.size - 3]; val c = buf[buf.size - 1]; val b = buf[buf.size - 2]
            val sp = ((c.p[J.WR_P] - a.p[J.WR_P]).len() / max(1e-3, c.t - a.t))
            // local maximum at b?
            if (sp1 > sp2 && sp1 >= sp && sp1 > minSpeed && b.t - lastSwing > 0.6 && pending == null) {
                val fr = BodyFrame.of(b.p, rightHanded)
                val vel = (c.p[J.WR_P] - a.p[J.WR_P]) * (1 / max(1e-3, c.t - a.t))
                val fwd = vel.dot(fr.y); val up = vel[2]; val side = (b.p[J.WR_P] - b.p[J.PELVIS]).dot(fr.x)
                if (fwd > 0.5 && up > 0.2 && side > -0.05) { pending = buf[buf.size - 2].t; lastSwing = pending!! }
            }
            sp2 = sp1; sp1 = sp
        }
        val tp = pending ?: return null
        if (t < tp + 0.42) return null
        pending = null
        return evaluate(tp)
    }

    private fun sample(tq: Double): Pose {
        val arr = buf.toList(); var k = arr.indexOfFirst { it.t >= tq }
        if (k <= 0) return arr[if (k == 0) 0 else arr.size - 1].p
        val a = arr[k - 1]; val b = arr[k]; val w = (tq - a.t) / max(1e-6, b.t - a.t)
        return Array(14) { j -> a.p[j] * (1 - w) + b.p[j] * w }
    }

    private fun evaluate(tImpact: Double): SwingResult? {
        if (buf.isEmpty() || buf.first().t > tImpact + tpl.t0 + 0.05) return null
        val world = Array(tpl.n) { i -> sample(tImpact + tpl.time(i)) }
        val fr = BodyFrame.of(world[tpl.impact], rightHanded)
        val local = Array(tpl.n) { i -> Array(14) { j -> fr.toLocal(world[i][j]) } }
        val (vals, ib, i1) = swingValues(local, tpl.t0, tpl.dt)
        val z = HashMap<String, Double>()
        for (d in ISSUES) { val (med, iqr) = tpl.stats[d.key] ?: continue; z[d.key] = d.bad * (vals.get(d.key) - med) / (iqr / 1.35 + 1e-9) }
        val worst = ISSUES.maxByOrNull { z[it.key] ?: -9.0 }!!
        val zmax = z.values.maxOrNull() ?: 0.0
        // lenient on purpose: ~90% of the athletes' own forehands score 3 stars at 30 fps
        val stars = if (zmax < 2.5) 3 else if (zmax < 4.0) 2 else 1
        val f = focus
        val fok = f?.let { (z[it] ?: 0.0) < 1.0 }
        val r = SwingResult(tImpact, stars, vals, z, worst, local, fr, ib, i1, fok)
        results.add(r); return r
    }

    /** the issue to work on after a set: the one with the largest total badness */
    fun chooseFocus(last: Int = 5): IssueDef {
        val rs = results.takeLast(last)
        return ISSUES.maxByOrNull { d -> rs.sumOf { max(0.0, it.z[d.key] ?: 0.0) } }!!
    }

    /** spoken line for a swing: praise first, then at most one fix */
    fun speech(r: SwingResult, streak: Int): String {
        val f = focus
        if (f != null && r.focusOk != null) {
            val d = ISSUES.first { it.key == f }
            return if (r.focusOk) (if (streak >= 3) "連續 $streak 下！" else "") + d.focusOk else "再一次，" + d.cue
        }
        return when (r.stars) { 3 -> if (streak >= 3) "連續 $streak 下，很漂亮！" else listOf("好棒！", "這下很漂亮！", "漂亮！").random()
            2 -> "不錯！" + r.worst.cue
            else -> "加油，" + r.worst.cue }
    }
}

/** least-squares affine map from z-up world points to image points (weak perspective), for drawing the ghost */
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

/** raw MediaPipe world landmark (x right, y down, z depth) → z-up world point (same convention as poseFromMpWorld) */
fun mpWorldToZUp(x: Double, y: Double, z: Double) = doubleArrayOf(x, z, -y)

