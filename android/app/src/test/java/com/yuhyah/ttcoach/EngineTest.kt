package com.yuhyah.ttcoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Replays the pose track of the reference match video (1920×1080, 29.97 fps, near player 劉允翔)
 * through the engine and checks it against what the web version and the offline analysis found.
 */
class EngineTest {
    private val taps = listOf(doubleArrayOf(788.3, 630.7), doubleArrayOf(1158.3, 637.3), doubleArrayOf(1131.7, 592.7), doubleArrayOf(880.0, 590.7))
    // hand-labelled rally windows (seconds) from the offline analysis
    private val truth = listOf(0.0 to 6.0, 18.6 to 22.4, 27.4 to 35.8, 38.4 to 43.8, 47.6 to 53.2, 55.6 to 59.8, 63.2 to 67.8, 72.6 to 76.4,
        81.4 to 85.4, 89.4 to 94.2, 98.4 to 104.2, 107.6 to 112.2, 118.6 to 123.6, 128.2 to 133.2, 137.6 to 144.2, 149.4 to 155.2, 157.4 to 162.5)

    private fun frames(): List<Pair<Int, List<Lm>?>> {
        val s = javaClass.classLoader!!.getResourceAsStream("near_pose.csv.gz")!!
        return GZIPInputStream(s).bufferedReader().readLines().map { line ->
            val p = line.split(','); val f = p[0].toInt()
            if (p.size < 100) f to null
            else f to (0 until 33).map { i -> Lm(p[1 + 3 * i].toDouble(), p[2 + 3 * i].toDouble(), p[3 + 3 * i].toDouble()) }
        }
    }

    private fun run(step: Int = 1, onEnd: (Coach, CoachEvent.End) -> Unit = { _, _ -> }): Coach {
        val cal = calibrate(taps, 1920.0, 1080.0)!!
        val coach = Coach()
        for ((f, lm) in frames()) {
            if (f % step != 0) continue
            val t = f / (30000.0 / 1001)
            val pick = lm?.let { pickNearPlayer(cal.cam, listOf(it)) }
            val ev = coach.update(pick?.second, t)
            if (ev is CoachEvent.End) onEnd(coach, ev)
        }
        return coach
    }

    @Test fun calibrationMatchesOfflineSolve() {
        val c = calibrate(taps, 1920.0, 1080.0)
        assertNotNull(c); c!!
        assertTrue("calibration ok", c.ok)
        assertTrue("focal ${c.cam.f}", c.cam.f in 600.0..800.0)
        assertEquals(0.43, c.cam.c[0], 0.3); assertEquals(-4.16, c.cam.c[1], 0.4); assertEquals(1.28, c.cam.c[2], 0.2)
        assertTrue("reprojection ${c.err}", c.err < 5)
        val adv = placementAdvice(c.cam, match = true, rightHanded = true)
        assertTrue(adv[0].text.contains("1.3"))
    }

    @Test fun ralliesFoundAt30and15fps() {
        for (step in listOf(1, 2)) {
            val coach = run(step)
            var hit = 0
            for ((a, b) in truth) if (coach.rallies.any { minOf(it.t1, b) - maxOf(it.t0, a) > 0.5 * (b - a) }) hit++
            val falsePos = coach.rallies.count { r -> truth.none { (a, b) -> minOf(r.t1, b) - maxOf(r.t0, a) > 0.5 * (b - a) } }
            println("step $step: ${coach.rallies.size} rallies, $hit/${truth.size} matched, $falsePos false")
            assertTrue("step $step matched $hit", hit >= 14)
            assertTrue("step $step false $falsePos", falsePos <= 1)
        }
    }

    @Test fun cuesFocusAndNotes() {
        val cues = ArrayList<String>()
        var won = 0
        val coach = run { c, e ->
            cues.add(e.cue?.text ?: "-")
            c.markPoint(won++ % 3 != 0, e.rally.t1 + 2)
            if (c.rallies.size == 8) c.newGame()
        }
        cues.forEach { println(it) }
        coach.rallies.forEach { println("%.1f-%.1f ratio %.2f target %.2f upright %.2f".format(it.t0, it.t1, it.ratio, it.target, it.upright)) }
        println("focus=${coach.focus} notes0=${coach.notes(0)} notes1=${coach.notes(1)}")
        assertNotNull(coach.focus)
        assertTrue(cues.count { it.startsWith("✓") || it.startsWith("✗") } >= 5)
        assertTrue(coach.notes(1).isNotEmpty())
        assertTrue(abs(coach.standing - 1.27) < 0.1)
    }
}
