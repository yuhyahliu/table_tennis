package com.yuhyah.ttcoach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * 空拍練習（小孩畫面）: front camera, mirrored like a mirror. Flow:
 * SETUP (whole body visible, stand at the right angle) → DEMO (watch the shadow, 2 warm-up swings)
 * → FOLLOW: every swing is counted at once (beep + big counter), then scored (colour + stars + short voice)
 * → after 5: REPLAY best swing (green, slow motion + shadow) and worst swing (red, freeze + arrow + 口訣)
 * → FOCUS 5 swings on that one point → REPLAY → …
 * Long-press anywhere = coach debug overlay (why a swing was not counted).
 */
class ShadowActivity : ComponentActivity() {
    enum class Phase { SETUP, DEMO, FOLLOW, FOCUS, REPLAY }

    /** one camera frame kept for the replay: small JPEG + 2D landmarks + projection + 3D pose */
    class ClipFrame(val t: Double, val jpeg: ByteArray, val lm: List<FloatArray>, val aff: Affine?, val pose: Pose)
    /** one replay segment: a swing, played slowly, frozen at `freezeT` */
    class Seg(val r: SwingResult, val clip: List<ClipFrame>, val good: Boolean, val issue: IssueDef?, val freezeT: Double, val freezeFor: Double, val caption: String)

    private val prefs by lazy { getSharedPreferences("tt", Context.MODE_PRIVATE) }
    val rightHanded get() = prefs.getString("hand", "R") == "R"
    lateinit var spec: StrokeSpec
    lateinit var tpl: Template
    lateinit var coach: ShadowCoach
    lateinit var view: ShadowView
    private lateinit var tracker: PhaseTracker
    private lateinit var preview: PreviewView
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var landmarker: PoseLandmarker? = null
    private var tts: TextToSpeech? = null
    private var tone: ToneGenerator? = null

    // ---- live state (written on the analysis thread, read by the view) ----
    @Volatile var phase = Phase.SETUP
    @Volatile var imgW = 1280f
    @Volatile var imgH = 720f
    @Volatile var lastLm: List<FloatArray>? = null
    @Volatile var lastAff: Affine? = null
    @Volatile var ghostNow: Pose? = null
    @Volatile var energy = 0f                 // 0..1+, the swing-energy bar (peak-hold)
    @Volatile var energyThr = 0.3f            // where on the bar a swing starts to count
    @Volatile var turnDeg = -1.0
    @Volatile var fps = 0.0
    @Volatile var ghostIdx = 0
    @Volatile var debug = false
    @Volatile var clock = 0.0
    @Volatile private var ghostLocal: Array<Pose> = emptyArray()
    private var kidArm = 0.5
    private val ring = ArrayDeque<ClipFrame>()
    private val pendingClips = ArrayList<SwingResult>()
    private val clips = HashMap<SwingResult, List<ClipFrame>>()
    private var visibleSince = -1.0
    private var angleOkSince = -1.0
    private var angleNagAt = -1.0
    private var phaseStart = 0.0
    private var lastMotion = 0.0
    private var frames = 0; private var fpsT0 = 0.0

    // ---- round state (main thread) ----
    val roundResults = ArrayList<SwingResult>()
    var roundDetected = 0; private set
    private val detectTimes = ArrayList<Double>()
    var round = 1
    var streak = 0
    var pulseAt = 0L                          // counter "pop" animation
    var flashColor = 0; var flashUntil = 0L; var starsShown = 0; var starsUntil = 0L
    var bigText = ""; var subText = ""
    var focusIssue: IssueDef? = null
    private var demoSwings = 0
    private var roundWasFocus = false; private var roundOk = 0; private var roundAllGood = false

    // ---- replay state (main thread) ----
    var segs: List<Seg> = emptyList()
    var segIdx = 0
    var segT = 0.0                            // clip-time being shown (s, relative to impact)
    var frozen = false
    var replayBmp: Bitmap? = null
    var replayFrame: ClipFrame? = null
    var replayGhost: Pose? = null
    private var shownFrame: ClipFrame? = null
    private var freezeLeft = 0.0
    private var saidForSeg = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        spec = StrokeSpec.of(intent.getStringExtra("stroke"))
        tpl = Template(assets.open(spec.asset).bufferedReader().readText())
        coach = ShadowCoach(tpl, rightHanded, spec)
        tracker = PhaseTracker(tpl, rightHanded)
        ghostLocal = tpl.build()
        kidArm = (tpl.lengths["shP-elP"] ?: 0.28) + (tpl.lengths["elP-wrP"] ?: 0.21)
        val root = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        view = ShadowView(this, this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.TAIWAN) }
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: Exception) { null }
        goPhase(Phase.SETUP)
        exec.execute { createLandmarker() }
        startCamera()
    }

    fun say(s: String, interrupt: Boolean = true) {
        if (s.isBlank()) return
        val t = tts ?: return
        if (!interrupt && t.isSpeaking) return
        t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "sh")
    }
    private fun beep() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 70) } catch (_: Exception) {} }

    val strokeName get() = spec.name
    private val wantAngle get() = if (spec.key == "bh") 0.0..45.0 else 25.0..65.0
    private val angleHint get() = if (spec.key == "bh") "面向手機站好" else "身體站斜一點，像站在球桌前"

    fun goPhase(p: Phase, line: String? = null) {
        phase = p; phaseStart = clock
        when (p) {
            Phase.SETUP -> { bigText = "站到畫面中間"; subText = "頭到腳都要拍到" }
            Phase.DEMO -> { bigText = "看影子，輕輕揮兩下"; subText = ""; demoSwings = 0; say("看影子怎麼打${strokeName}，跟著輕輕揮兩下") }
            Phase.FOLLOW -> { startRound(null); bigText = ""; subText = ""; say(line ?: "換你！跟著影子，${strokeName}揮五下") }
            Phase.FOCUS -> { val d = focusIssue!!; startRound(d); bigText = d.cue; subText = ""; say(line ?: "這五下，只想一件事，${d.cue}") }
            Phase.REPLAY -> {}
        }
        view.postInvalidate()
    }

    private fun startRound(focus: IssueDef?) {
        roundResults.clear(); roundDetected = 0; detectTimes.clear(); coach.resetCount(); coach.focus = focus?.key
        synchronized(clips) { clips.clear() }
    }

    // ================= camera + pose =================
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val res = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val pv = Preview.Builder().setResolutionSelector(res).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val an = ImageAnalysis.Builder().setResolutionSelector(res).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build().also { it.setAnalyzer(exec) { img -> analyze(img) } }
            val sel = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll(); provider.bindToLifecycle(this, sel, pv, an)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createLandmarker() {
        for (d in listOf(Delegate.GPU, Delegate.CPU)) try {
            landmarker = PoseLandmarker.createFromOptions(this, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").setDelegate(d).build())
                .setRunningMode(RunningMode.VIDEO).setNumPoses(1).build()); return
        } catch (e: Exception) { Log.w("TTShadow", "landmarker $d", e) }
    }

    private var lastTs = -1L
    private var lastLenT = -9.0
    private fun analyze(img: ImageProxy) {
        try {
            val lmk = landmarker ?: return
            val rot = img.imageInfo.rotationDegrees
            var bmp = img.toBitmap()
            if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            imgW = bmp.width.toFloat(); imgH = bmp.height.toFloat()
            var ts = img.imageInfo.timestamp / 1_000_000L; if (ts <= lastTs) ts = lastTs + 1; lastTs = ts
            val t = ts / 1000.0; clock = t
            frames++; if (t - fpsT0 >= 1.0) { fps = frames / (t - fpsT0); frames = 0; fpsT0 = t }
            val res = lmk.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            val n = res.landmarks().firstOrNull(); val w = res.worldLandmarks().firstOrNull()
            if (n == null || w == null) { lastLm = null; visibleSince = -1.0; angleOkSince = -1.0; energy *= 0.85f; tick(t); view.postInvalidate(); return }
            val lm = n.map { floatArrayOf(it.x(), it.y(), it.visibility().orElse(0f)) }
            val world = w.map { doubleArrayOf(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }
            val pose = poseFromMpWorld(world, rightHanded)
            val zup = world.map { mpWorldToZUp(it[0], it[1], it[2]) }
            val aff = Affine.fit(zup, lm.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }, lm.map { maxOf(0.05, it[2].toDouble()) })
            lastLm = lm; lastAff = aff
            turnDeg = bodyTurnDeg(world)

            // replay material: small JPEG of every frame for the last few seconds
            val small = Bitmap.createScaledBitmap(bmp, 480, 480 * bmp.height / bmp.width, true)
            val jpg = ByteArrayOutputStream(24_000).also { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
            small.recycle()
            synchronized(ring) {
                ring.addLast(ClipFrame(t, jpg, lm, aff, pose)); while (ring.isNotEmpty() && t - ring.first().t > 3.0) ring.removeFirst()
                val done = pendingClips.filter { t >= it.tImpact + 0.55 }
                for (r in done) { val c = ring.filter { it.t >= r.tImpact - 0.75 && it.t <= r.tImpact + 0.55 }; synchronized(clips) { clips[r] = c } }
                pendingClips.removeAll(done.toSet())
            }

            // whole body visible? standing at the right angle?
            val body = listOf(27, 28, 0).all { lm[it][2] > 0.5 && lm[it][1] in 0.02f..0.99f }
            visibleSince = if (body) (if (visibleSince < 0) t else visibleSince) else -1.0
            angleOkSince = if (body && turnDeg in wantAngle) (if (angleOkSince < 0) t else angleOkSince) else -1.0

            // the player's own bone lengths → the shadow is their size
            val lp = coach.recentPoses()
            if (lp.size > 20 && t - lastLenT > 2.0) {
                val len = segmentLengths(lp); ghostLocal = tpl.build(len); lastLenT = t
                kidArm = (len["shP-elP"] ?: 0.28) + (len["elP-wrP"] ?: 0.21)
            }

            // swings
            val events = coach.feed(t, pose)
            val typ = coach.typicalSpeed()
            energy = max(energy * 0.88f, (coach.speed / typ).toFloat())
            energyThr = (coach.threshold() / typ).toFloat().coerceIn(0.05f, 0.95f)
            if (coach.speed > 0.7) lastMotion = t
            for (e in events) {
                if (e is ShadowEvent.Scored) synchronized(ring) { pendingClips.add(e.r) }
                main.post { onEvent(e) }
            }

            // the shadow: follows the player's own swing; plays the stroke by itself when they stand still
            val idle = t - lastMotion > 3.0
            val gi = if (phase == Phase.DEMO || phase == Phase.SETUP || idle) loopIndex(t, if (phase == Phase.DEMO) 2.0 else 1.0)
                     else tracker.update(pose, kidArm)
            ghostIdx = gi
            val g = ghostLocal
            ghostNow = if (g.isNotEmpty()) placeOn(g[gi.coerceIn(0, g.size - 1)], pose, rightHanded) else null
            tick(t)
            view.postInvalidate()
        } catch (e: Exception) { Log.w("TTShadow", "analyze", e) } finally { img.close() }
    }

    /** demo loop: hold 0.6 s → stroke 1.0 s → hold 0.4 s (times `slow`) */
    private fun loopIndex(t: Double, slow: Double): Int {
        val cyc = (t % (2.0 * slow)) / slow
        return when { cyc < 0.6 -> 0; cyc < 1.6 -> ((cyc - 0.6) / 1.0 * (tpl.n - 1)).toInt(); else -> tpl.n - 1 }.coerceIn(0, tpl.n - 1)
    }

    private fun tick(t: Double) {
        when (phase) {
            Phase.SETUP -> {
                if (visibleSince > 0 && t - visibleSince > 1.0) {
                    val ok = angleOkSince > 0 && t - angleOkSince > 0.8
                    val waited = t - visibleSince > 8.0              // depth is noisy: don't get stuck here
                    if (ok || waited) main.post { if (phase == Phase.SETUP) goPhase(Phase.DEMO) }
                    else {
                        main.post { if (phase == Phase.SETUP) { bigText = angleHint; subText = "" } }
                        if (angleNagAt < 0 || t - angleNagAt > 6.0) { angleNagAt = t; main.post { say(angleHint) } }
                    }
                } else main.post { if (phase == Phase.SETUP) { bigText = "站到畫面中間"; subText = "頭到腳都要拍到" } }
            }
            Phase.DEMO -> if (t - phaseStart > 5.0 && (demoSwings >= 2 || t - phaseStart > 12.0)) main.post { if (phase == Phase.DEMO) goPhase(Phase.FOLLOW) }
            else -> {}
        }
    }

    // ================= swings =================
    private fun onEvent(e: ShadowEvent) {
        when (e) {
            is ShadowEvent.Detected -> {
                if (phase == Phase.DEMO) { demoSwings++; beep(); pulseAt = System.currentTimeMillis(); return }
                if (phase != Phase.FOLLOW && phase != Phase.FOCUS) return
                if (roundDetected >= 5) return
                roundDetected++; detectTimes.add(e.t); beep(); pulseAt = System.currentTimeMillis()
                if (roundDetected == 5) main.postDelayed({ if ((phase == Phase.FOLLOW || phase == Phase.FOCUS) && roundResults.size in 1..4) endRound() }, 1500)
            }
            is ShadowEvent.Scored -> {
                val r = e.r
                if (phase != Phase.FOLLOW && phase != Phase.FOCUS) return
                if (roundResults.size >= 5 || detectTimes.none { abs(it - r.tImpact) < 1e-6 }) return   // only swings counted in this round
                roundResults.add(r)
                val good = if (phase == Phase.FOCUS) r.focusOk == true else r.stars == 3
                streak = if (good) streak + 1 else 0
                flashColor = when { phase == Phase.FOCUS -> if (r.focusOk == true) Ui.ACC else Ui.OPP; r.stars == 3 -> Ui.ACC; r.stars == 2 -> Ui.WARN; else -> Ui.OPP }
                flashUntil = System.currentTimeMillis() + 700
                starsShown = if (phase == Phase.FOCUS) (if (r.focusOk == true) 3 else 1) else r.stars
                starsUntil = System.currentTimeMillis() + 1100
                // swings come every ~1 s: a short word each time, never talk over the last one
                say(if (good) (if (streak >= 3) "連續 $streak 下！" else listOf("好！", "漂亮！", "很好！").random()) else coach.speech(r, streak), interrupt = false)
                if (roundResults.size >= 5) main.postDelayed({ endRound() }, 800)
            }
        }
        view.postInvalidate()
    }

    /** a steady rhythm (all gaps within ±0.25 s) is worth praising */
    private fun steadyRhythm(): Boolean {
        if (detectTimes.size < 4) return false
        val gaps = detectTimes.zipWithNext { a, b -> b - a }
        return gaps.all { it < 2.5 } && (gaps.max() - gaps.min()) < 0.5
    }

    private fun endRound() {
        if (phase != Phase.FOLLOW && phase != Phase.FOCUS) return
        val rs = roundResults.toList(); if (rs.isEmpty()) return
        val focus = coach.focus?.let { k -> spec.issues.first { it.key == k } }
        roundWasFocus = focus != null
        roundOk = rs.count { it.focusOk == true }
        val issue = focus ?: coach.chooseFocus(rs.size)
        val zOf = { r: SwingResult -> r.z[issue.key] ?: 0.0 }
        val byClip = synchronized(clips) { rs.filter { (clips[it]?.size ?: 0) > 8 }.associateWith { clips[it]!! } }
        val best = (if (focus != null) byClip.keys.minByOrNull(zOf) else byClip.keys.minByOrNull { it.zmax })
        val worst = byClip.keys.maxByOrNull(zOf)
        roundAllGood = if (focus != null) roundOk >= 4 else rs.all { it.stars == 3 } && rs.maxOf(zOf) < 1.5
        focusIssue = issue
        val rhythm = steadyRhythm()
        val list = ArrayList<Seg>()
        if (best != null) list.add(Seg(best, byClip[best]!!, true, null, 0.0, 1.4, if (rhythm) "這下很棒！節奏也很穩" else "這下很棒！"))
        if (worst != null && worst !== best && zOf(worst) >= 1.0 && !(focus != null && roundOk >= 4 && zOf(worst) < 1.5)) {
            val ft = when (issue.at) { "bs" -> tpl.time(worst.ib); "fin" -> tpl.time(worst.i1); else -> 0.0 }
            list.add(Seg(worst, byClip[worst]!!, false, issue, ft, 3.0, issue.cue))
        }
        if (list.isEmpty()) { nextRound(); return }
        startReplay(list)
    }

    // ================= replay =================
    private val replayTick = object : Runnable {
        override fun run() {
            if (phase != Phase.REPLAY) return
            val dt = 1 / 30.0
            val s = segs[segIdx]
            if (saidForSeg != segIdx) { saidForSeg = segIdx; if (s.good) say(s.caption) }   // the worst swing speaks at its freeze
            if (frozen) {
                freezeLeft -= dt
                if (freezeLeft <= 0) frozen = false
            } else {
                val before = segT
                segT += dt * 0.5                                    // half speed
                if (before < s.freezeT && segT >= s.freezeT && freezeLeft > 0) {
                    segT = s.freezeT; frozen = true
                    if (!s.good) say(s.caption)
                }
                val end = s.clip.last().t - s.r.tImpact
                if (segT > end) {
                    if (segIdx + 1 < segs.size) { segIdx++; startSeg() } else { finishReplay(); return }
                }
            }
            showAt(segs[segIdx], segT)
            view.invalidate()
            main.postDelayed(this, 33)
        }
    }

    private fun startReplay(list: List<Seg>) {
        segs = list; segIdx = 0; saidForSeg = -1
        phase = Phase.REPLAY; phaseStart = clock; round++
        startSeg()
        main.removeCallbacks(replayTick); main.post(replayTick)
    }

    private fun startSeg() {
        val s = segs[segIdx]
        segT = s.clip.first().t - s.r.tImpact; frozen = false; freezeLeft = s.freezeFor; shownFrame = null
        showAt(s, segT)
    }

    private fun showAt(s: Seg, tRel: Double) {
        val f = s.clip.minByOrNull { abs(it.t - s.r.tImpact - tRel) } ?: return
        if (f !== shownFrame) {
            shownFrame = f
            replayBmp = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)
            replayFrame = f
        }
        val g = ghostLocal
        replayGhost = if (g.isNotEmpty()) placeOn(g[warpIndex(tpl, s.r, tRel)], f.pose, rightHanded) else null
    }

    private fun finishReplay() {
        main.removeCallbacks(replayTick)
        replayBmp = null; replayFrame = null; replayGhost = null
        nextRound()
    }

    fun onTap() { if (phase == Phase.REPLAY) finishReplay() }

    /** a good round → free swings again; otherwise 5 swings thinking about the one point */
    private fun nextRound() {
        if (roundAllGood) goPhase(Phase.FOLLOW, if (roundWasFocus) "做到了！再來五下，跟著影子" else "五下都很棒！再來五下")
        else goPhase(Phase.FOCUS)
    }

    override fun onDestroy() {
        main.removeCallbacks(replayTick)
        tts?.shutdown(); tone?.release(); exec.execute { landmarker?.close() }; exec.shutdown(); super.onDestroy()
    }
}
