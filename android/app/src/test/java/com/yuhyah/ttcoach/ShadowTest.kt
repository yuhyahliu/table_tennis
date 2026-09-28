package com.yuhyah.ttcoach

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/** Replays a figshare athlete's forehand session (MediaPipe world landmarks, resampled to 30 fps like a phone camera). */
class ShadowTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!
    private val tpl by lazy { Template(res("fh_template.txt").bufferedReader().readText()) }

    private fun session(): Pair<List<Pair<Double, List<DoubleArray>>>, List<Double>> {
        val lines = GZIPInputStream(res("fig_p01_30fps.csv.gz")).bufferedReader().readLines()
        val hits = lines[0].substringAfter("hits(s)=").trim().split(' ').map { it.toDouble() }
        val rows = lines.drop(1).map { l -> val p = l.split(',').map { it.toDouble() }; p[0] to (0 until 33).map { i -> doubleArrayOf(p[1 + 3 * i], p[2 + 3 * i], p[3 + 3 * i]) } }
        return rows to hits
    }

    @Test fun templateLoads() {
        assertTrue(tpl.n == 121 && tpl.tree.size == 14 && tpl.stats.size >= 6)
        val g = tpl.build()
        val (v, _, _) = swingValues(g, tpl.t0, tpl.dt)
        println("template itself: shTurn=${v.shTurn} wristRise=${v.wristRise} elbow=${v.elbowImp} upperarm=${v.upperarmImp} knee=${v.kneeBs}")
        assertTrue("template scores itself near the median", v.elbowImp in 80.0..120.0 && v.shTurn > 50)
    }

    @Test fun detectsAndScoresAthleteSwings() {
        val (rows, hits) = session()
        var best: ShadowCoach? = null
        for (right in listOf(true, false)) {
            val c = ShadowCoach(tpl, right)
            for ((t, lm) in rows) c.feed(t, poseFromMpWorld(lm, right))
            if (best == null || c.results.size > best.results.size) best = c
        }
        val c = best!!
        val matched = hits.count { h -> c.results.any { kotlin.math.abs(it.tImpact - h) < 0.15 } }
        c.results.forEach { r -> println("swing %.2fs stars=%d worst=%s z=%s speech=%s".format(r.tImpact, r.stars, r.worst.key,
            r.z.entries.joinToString { "${it.key}:%.1f".format(it.value) }, c.speech(r, 0))) }
        println("right=${c.rightHanded} swings=${c.results.size} labelled=${hits.size} matched=$matched stars avg=${c.results.map { it.stars }.average()}")
        assertTrue("found the labelled forehands", matched >= 8)
        assertTrue("an athlete's forehands mostly score well", c.results.map { it.stars }.average() >= 2.5)
        val focus = c.chooseFocus(); c.focus = focus.key
        println("focus=${focus.key} ${focus.cue}")
    }
}
