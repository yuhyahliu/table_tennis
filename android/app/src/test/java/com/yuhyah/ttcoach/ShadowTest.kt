package com.yuhyah.ttcoach

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Replays real strokes (resampled to 30 fps like a phone camera) through the shadow coach:
 *  - forehand: a figshare athlete's session (MediaPipe world landmarks)
 *  - backhand: 10 TTMD6 backhand-attack motion-capture strokes converted to MediaPipe world coordinates
 */
class ShadowTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!
    private fun tpl(spec: StrokeSpec) = Template(res(spec.asset).bufferedReader().readText())

    private fun session(file: String): Pair<List<Pair<Double, List<DoubleArray>>>, List<Double>> {
        val lines = GZIPInputStream(res(file)).bufferedReader().readLines()
        val hits = lines[0].substringAfter("hits(s)=").trim().split(' ').map { it.toDouble() }
        val rows = lines.drop(1).filter { it.isNotBlank() }.map { l -> val p = l.split(',').map { it.toDouble() }; p[0] to (0 until 33).map { i -> doubleArrayOf(p[1 + 3 * i], p[2 + 3 * i], p[3 + 3 * i]) } }
        return rows to hits
    }

    @Test fun templatesLoad() {
        for (spec in listOf(StrokeSpec.FOREHAND, StrokeSpec.BACKHAND)) {
            val tpl = tpl(spec)
            assertTrue(tpl.n == 121 && tpl.tree.size == 14 && spec.issues.all { tpl.stats.containsKey(it.key) })
            val g = tpl.build()
            val (v, ib, i1) = swingValues(g, tpl.t0, tpl.dt, spec.key)
            println("${spec.key} template itself: ${v.entries.joinToString { "${it.key}=%.2f".format(it.value) }} ib=${tpl.time(ib)} i1=${tpl.time(i1)}")
            assertTrue("elbow angle sane", (v["elbow_imp"] ?: 0.0) in 70.0..140.0)
            assertTrue("backswing before impact, finish after", ib < tpl.impact && i1 > tpl.impact)
        }
    }

    private fun run(spec: StrokeSpec, file: String, minMatched: Int, minStars: Double) {
        val tpl = tpl(spec)
        val (rows, hits) = session(file)
        fun matchedOf(c: ShadowCoach) = hits.count { h -> c.results.any { abs(it.tImpact - h) < 0.2 } }
        var best: ShadowCoach? = null; var bestDet = 0
        for (right in listOf(true, false)) {       // the app gets handedness from settings; here we try both
            val c = ShadowCoach(tpl, right, spec); var det = 0
            for ((t, lm) in rows) for (e in c.feed(t, poseFromMpWorld(lm, right))) if (e is ShadowEvent.Detected) det++
            println("  ${spec.key} right=$right detected=$det matched=${matchedOf(c)} reject='${c.lastReject}'")
            if (best == null || matchedOf(c) > matchedOf(best)) { best = c; bestDet = det }
        }
        val c = best!!
        val matched = matchedOf(c)
        println("  shape rejects: " + c.shapeRejects.joinToString { "%.2fs:%.2f".format(it.first, it.second) })
        c.results.forEach { r -> println("  %s %.2fs shape=%.2f stars=%d worst=%s z=%s | %s".format(spec.key, r.tImpact, r.shape, r.stars, r.worst.key,
            r.z.entries.joinToString { "${it.key}:%.1f".format(it.value) }, c.speech(r, 0))) }
        println("${spec.key}: right=${c.rightHanded} detected=$bestDet scored=${c.results.size} labelled=${hits.size} matched=$matched " +
            "stars avg=%.2f thr=%.2f typical=%.2f".format(c.results.map { it.stars }.average(), c.threshold(), c.typicalSpeed()))
        assertTrue("found the labelled strokes ($matched)", matched >= minMatched)
        // the figshare labels skip a few of the athlete's (evenly spaced) strokes, so check for double counts instead of extras
        val gaps = c.results.zipWithNext { a, b -> b.tImpact - a.tImpact }
        assertTrue("no double counts (min gap %.2f s)".format(gaps.minOrNull() ?: 9.0), (gaps.minOrNull() ?: 9.0) > 0.8)
        assertTrue("real strokes mostly score well", c.results.map { it.stars }.average() >= minStars)
        val focus = c.chooseFocus(); c.focus = focus.key
        println("  focus=${focus.key} ${focus.cue}")
    }

    @Test fun forehandAthlete() = run(StrokeSpec.FOREHAND, "fig_p01_30fps.csv.gz", 8, 2.5)
    @Test fun backhandMocap() = run(StrokeSpec.BACKHAND, "ttmd_bh_30fps.csv.gz", 8, 2.3)

    /** things that are not the stroke must not be counted: the other arm, the stroke played backwards */
    @Test fun ignoresNonStrokes() {
        val tpl = tpl(StrokeSpec.FOREHAND)
        val (rows, _) = session("fig_p01_30fps.csv.gz")
        val wrongArm = ShadowCoach(tpl, false, StrokeSpec.FOREHAND)
        for ((t, lm) in rows) wrongArm.feed(t, poseFromMpWorld(lm, false))
        val tEnd = rows.last().first
        val backwards = ShadowCoach(tpl, true, StrokeSpec.FOREHAND)
        for ((t, lm) in rows.reversed()) backwards.feed(tEnd - t, poseFromMpWorld(lm, true))
        val asBackhand = ShadowCoach(tpl(StrokeSpec.BACKHAND), true, StrokeSpec.BACKHAND)
        for ((t, lm) in rows) asBackhand.feed(t, poseFromMpWorld(lm, true))
        println("non-strokes counted: other arm=${wrongArm.results.size} backwards=${backwards.results.size} forehands-as-backhand=${asBackhand.results.size}")
        println("  other arm shapes: " + wrongArm.shapeRejects.joinToString { "%.2f".format(it.second) } + " | accepted " + wrongArm.results.joinToString { "%.2f".format(it.shape) })
        println("  backwards shapes: " + backwards.shapeRejects.joinToString { "%.2f".format(it.second) } + " | accepted " + backwards.results.joinToString { "%.2f".format(it.shape) })
        println("  fh-as-bh shapes: " + asBackhand.shapeRejects.joinToString { "%.2f".format(it.second) } + " | accepted " + asBackhand.results.joinToString { "%.2f".format(it.shape) })
        assertTrue(wrongArm.results.size <= 3 && backwards.results.size <= 1 && asBackhand.results.size <= 1)
    }

    /** 引拍 starts a round: the standard backswing end is "near", the ready stance, contact and finish are not */
    @Test fun backswingStartPose() {
        for (spec in listOf(StrokeSpec.FOREHAND, StrokeSpec.BACKHAND)) {
            val tpl = tpl(spec); val g = tpl.build()
            val arm = (tpl.lengths["shP-elP"] ?: 0.28) + (tpl.lengths["elP-wrP"] ?: 0.21)
            val bp = BackswingPose(tpl, true)
            val d = (0 until tpl.n step 6).map { i -> "%.2f:%.2f".format(tpl.time(i), bp.distance(g[i], arm)) }
            println("${spec.key} backswing distance over the stroke: " + d.joinToString(" "))
            assertTrue(bp.distance(g[bp.index], arm) < 0.05)
            assertTrue("contact is not a start", bp.distance(g[tpl.impact], arm) > bp.near)
            assertTrue("finish is not a start", bp.distance(g[tpl.index(tpl.finishT)], arm) > bp.near)
            // athletes: how much of a real session sits in the start pose (only brief moments while swinging)
        }
        // a relaxed stance (arms hanging) is not a start
        val tpl = tpl(StrokeSpec.FOREHAND)
        val (rows, _) = session("fig_p01_30fps.csv.gz")
        val p0 = poseFromMpWorld(rows[0].second, true)
        val hang = Array(14) { p0[it].copyOf() }
        for ((sh, el, wr) in listOf(Triple(J.SH_P, J.EL_P, J.WR_P), Triple(J.SH_N, J.EL_N, J.WR_N))) {
            hang[el] = doubleArrayOf(hang[sh][0], hang[sh][1], hang[sh][2] - 0.28); hang[wr] = doubleArrayOf(hang[sh][0], hang[sh][1], hang[sh][2] - 0.52) }
        for (spec in listOf(StrokeSpec.FOREHAND, StrokeSpec.BACKHAND)) {
            val bp = BackswingPose(tpl(spec), true)
            val dh = bp.distance(hang, 0.5)
            println("${spec.key} arms hanging: %.2f (near < %.2f)".format(dh, bp.near))
            assertTrue(dh > bp.near)
        }
    }

    /** small / far-away players and slow phones: jittery landmarks (4 cm), 15 fps, and a 1.5× slower swing still count */
    @Test fun robustToNoiseLowFpsAndSlowSwings() {
        val tpl = tpl(StrokeSpec.FOREHAND)
        val (rows, hits) = session("fig_p01_30fps.csv.gz")
        val rnd = java.util.Random(7)
        val c = ShadowCoach(tpl, true, StrokeSpec.FOREHAND)
        rows.filterIndexed { i, _ -> i % 2 == 0 }.forEach { (t, lm) ->
            c.feed(t * 1.5, poseFromMpWorld(lm.map { doubleArrayOf(it[0] + rnd.nextGaussian() * 0.04, it[1] + rnd.nextGaussian() * 0.04, it[2] + rnd.nextGaussian() * 0.08) }, true)) }
        val m = hits.count { h -> c.results.any { abs(it.tImpact - h * 1.5) < 0.3 } }
        println("noisy+15fps+slow: counted=${c.results.size} matched=$m/10 tempo=" + c.results.joinToString(" ") { "%.2f".format(it.scale) })
        assertTrue(m >= 8)
    }

    /** the moment of contact used for the replay freeze and all measurements: close to the dataset's labelled hits */
    @Test fun impactTiming() {
        val tpl = tpl(StrokeSpec.FOREHAND)
        val (rows, hits) = session("fig_p01_30fps.csv.gz")
        for (step in listOf(1, 2)) {
            val c = ShadowCoach(tpl, true, StrokeSpec.FOREHAND)
            rows.filterIndexed { i, _ -> i % step == 0 }.forEach { (t, lm) -> c.feed(t, poseFromMpWorld(lm, true)) }
            val off = hits.mapNotNull { h -> c.results.minByOrNull { abs(it.tImpact - h) }?.let { it.tImpact - h }?.takeIf { abs(it) < 0.3 } }
            val err = off.map { abs(it) }.average()
            println("${30 / step} fps: contact error %.0f ms (mean |.|)".format(err * 1000))
            assertTrue(off.size >= 9 && err < 0.025)
        }
    }

    /** the app pairs each count with its score by time: both events must carry the same time (0.10 broke this) */
    @Test fun countAndScoreAgree() {
        val tpl = tpl(StrokeSpec.FOREHAND)
        val (rows, _) = session("fig_p01_30fps.csv.gz")
        val c = ShadowCoach(tpl, true, StrokeSpec.FOREHAND)
        var det = 0; var paired = 0
        for ((t, lm) in rows) {
            val ev = c.feed(t, poseFromMpWorld(lm, true))
            val d = ev.filterIsInstance<ShadowEvent.Detected>(); val s = ev.filterIsInstance<ShadowEvent.Scored>()
            det += d.size; paired += d.count { x -> s.any { it.r.tImpact == x.t } }
        }
        println("counted $det, paired with a score $paired")
        assertTrue(det >= 10 && paired == det)
    }

    @Test fun phaseTrackerFollowsTheTemplate() {
        val tpl = tpl(StrokeSpec.FOREHAND)
        val g = tpl.build()
        val arm = (tpl.lengths["shP-elP"] ?: 0.28) + (tpl.lengths["elP-wrP"] ?: 0.21)
        val tr = PhaseTracker(tpl, true)
        var maxErr = 0
        for (i in 0 until tpl.n step 4) { val k = tr.update(g[i], arm); maxErr = maxOf(maxErr, abs(k - i)) }
        println("phase tracker max error on the template itself: $maxErr frames")
        assertTrue(maxErr <= 12)
    }
}
