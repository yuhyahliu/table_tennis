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
        c.results.forEach { r -> println("  %s %.2fs stars=%d worst=%s z=%s | %s".format(spec.key, r.tImpact, r.stars, r.worst.key,
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
