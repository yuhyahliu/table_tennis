package com.yuhyah.ttcoach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 空拍練習（小孩畫面）: front camera, mirrored like a mirror. Flow:
 * SETUP (stand so the whole body is visible) → DEMO (watch the shadow) → FOLLOW 5 swings (stars + colour flash + one spoken cue)
 * → REVIEW (frozen photo of one swing with a big arrow and the target pose) → FOCUS 5 swings on that one point → REVIEW → …
 */
class ShadowActivity : ComponentActivity() {
    enum class Phase { SETUP, DEMO, FOLLOW, REVIEW, FOCUS }

    /** everything needed to draw one moment of a swing later (photo, 2D landmarks, projection, poses) */
    class Snap(val bmp: Bitmap, val lm: List<FloatArray>, val aff: Affine, val kid: Pose, val ghost: Pose)
    class Frame(val t: Double, val bmp: Bitmap, val lm: List<FloatArray>, val aff: Affine?, val pose: Pose)

    private val prefs by lazy { getSharedPreferences("tt", Context.MODE_PRIVATE) }
    val rightHanded get() = prefs.getString("hand", "R") == "R"
    lateinit var tpl: Template
    lateinit var coach: ShadowCoach
    lateinit var view: ShadowView
    private lateinit var preview: PreviewView
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var landmarker: PoseLandmarker? = null
    private var tts: TextToSpeech? = null

    // ---- live state (written on the analysis thread, read by the view) ----
    @Volatile var phase = Phase.SETUP
    @Volatile var imgW = 1280f
    @Volatile var imgH = 720f
    @Volatile var lastLm: List<FloatArray>? = null
    @Volatile var lastAff: Affine? = null
    @Volatile var lastPose: Pose? = null
    @Volatile var ghostNow: Pose? = null            // world pose of the shadow at this instant
    @Volatile var lengths: Map<String, Double>? = null
    private var ghostLocal: Array<Pose>? = null
    private val ring = ArrayDeque<Frame>()
    private var visibleSince = -1.0
    private var phaseStart = 0.0
    @Volatile var clock = 0.0

    // ---- round state (main thread) ----
    val roundResults = ArrayList<SwingResult>()
    val snaps = HashMap<SwingResult, Map<String, Snap>>()
    var review: Pair<SwingResult, Snap>? = null
    var reviewIssue: IssueDef? = null
    var round = 1
    private var lastRoundWasFocus = false; private var lastOk = 0
    var streak = 0
    var flashColor = 0; var flashUntil = 0L; var starsShown = 0; var starsUntil = 0L
    var bigText = ""; var smallText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tpl = Template(assets.open("fh_template.txt").bufferedReader().readText())
        coach = ShadowCoach(tpl, rightHanded)
        ghostLocal = tpl.build()
        val root = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        view = ShadowView(this, this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.TAIWAN) }
        goPhase(Phase.SETUP)
        exec.execute { createLandmarker() }
        startCamera()
    }

    fun say(s: String) { if (s.isNotBlank()) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "sh") }

    fun goPhase(p: Phase) {
        phase = p; phaseStart = clock
        when (p) {
            Phase.SETUP -> { bigText = "站到畫面中間"; smallText = "頭到腳都要拍到，離手機 2–3 公尺" }
            Phase.DEMO -> { bigText = "看影子怎麼打"; smallText = "橘色影子是標準正手"; say("看影子怎麼打，等一下跟著做") }
            Phase.FOLLOW -> { roundResults.clear(); snaps.clear(); coach.focus = null; bigText = ""; smallText = "跟著影子，正手揮 5 下"; say("換你！跟著影子揮五下") }
            Phase.FOCUS -> { roundResults.clear(); snaps.clear(); val d = reviewIssue!!; coach.focus = d.key; bigText = ""; smallText = "這 5 下只想一件事：${d.cue}"; say("這五下，只想一件事，${d.cue}") }
            Phase.REVIEW -> {}
        }
        view.postInvalidate()
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
    private fun analyze(img: ImageProxy) {
        try {
            val lmk = landmarker ?: return
            val rot = img.imageInfo.rotationDegrees
            var bmp = img.toBitmap()
            if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            imgW = bmp.width.toFloat(); imgH = bmp.height.toFloat()
            var ts = img.imageInfo.timestamp / 1_000_000L; if (ts <= lastTs) ts = lastTs + 1; lastTs = ts
            val t = ts / 1000.0; clock = t
            val res = lmk.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            val n = res.landmarks().firstOrNull(); val w = res.worldLandmarks().firstOrNull()
            if (n == null || w == null) { lastLm = null; lastPose = null; visibleSince = -1.0; tick(t); view.postInvalidate(); return }
            val lm = n.map { floatArrayOf(it.x(), it.y(), it.visibility().orElse(0f)) }
            val world = w.map { doubleArrayOf(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }
            val pose = poseFromMpWorld(world, rightHanded)
            val zup = world.map { mpWorldToZUp(it[0], it[1], it[2]) }
            val aff = Affine.fit(zup, lm.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }, lm.map { maxOf(0.05, it[2].toDouble()) })
            lastLm = lm; lastPose = pose; lastAff = aff
            // small copy of the frame for the review photo
            val small = Bitmap.createScaledBitmap(bmp, 640, (640 * bmp.height / bmp.width), true)
            synchronized(ring) { ring.addLast(Frame(t, small, lm, aff, pose)); while (ring.isNotEmpty() && t - ring.first().t > 1.6) ring.removeFirst() }
            // whole body visible?
            val feet = listOf(27, 28, 0).all { lm[it][2] > 0.5 && lm[it][1] in 0.02f..0.99f }
            visibleSince = if (feet) (if (visibleSince < 0) t else visibleSince) else -1.0
            if (lengths == null || coach.recentPoses().size > 40 && (t * 10).toInt() % 20 == 0) {
                val lp = coach.recentPoses(); if (lp.size > 20) { lengths = segmentLengths(lp); ghostLocal = tpl.build(lengths!!) }
            }
            val r = if (phase == Phase.FOLLOW || phase == Phase.FOCUS) coach.feed(t, pose) else { coach.feed(t, pose); null }
            if (r != null) { val snap = capture(r); main.post { onSwing(r, snap) } }
            ghostNow = ghostAt(t, pose)
            tick(t)
            view.postInvalidate()
        } catch (e: Exception) { Log.w("TTShadow", "analyze", e) } finally { img.close() }
    }

    /** the shadow pose to draw now: loops hold 0.6 s → swing 1.0 s → hold 0.4 s, anchored at the kid's pelvis */
    private fun ghostAt(t: Double, kid: Pose): Pose? {
        val g = ghostLocal ?: return null
        val slow = if (phase == Phase.DEMO) 2.0 else 1.0
        val cyc = (t % (2.0 * slow)) / slow
        val i = when { cyc < 0.6 -> 0; cyc < 1.6 -> ((cyc - 0.6) / 1.0 * (tpl.n - 1)).toInt(); else -> tpl.n - 1 }.coerceIn(0, tpl.n - 1)
        return place(g[i], kid)
    }

    fun place(local: Pose, kid: Pose): Pose {
        val fr = BodyFrame.of(kid, rightHanded)
        val w = Array(14) { fr.toWorld(local[it]) }
        val dx = kid[J.PELVIS][0] - w[J.PELVIS][0]; val dy = kid[J.PELVIS][1] - w[J.PELVIS][1]
        w.forEach { it[0] += dx; it[1] += dy }
        // stand the shadow on the kid's floor level
        val dz = minOf(kid[J.AN_N][2], kid[J.AN_P][2]) - minOf(w[J.AN_N][2], w[J.AN_P][2]); w.forEach { it[2] += dz }
        return w
    }

    /** keep the photo, landmarks and aligned shadow at backswing end, impact and finish of a swing */
    private fun capture(r: SwingResult): Map<String, Snap> {
        val g = ghostLocal ?: return emptyMap()
        val out = HashMap<String, Snap>()
        val times = mapOf("bs" to (r.tImpact + tpl.time(r.ib)), "imp" to r.tImpact, "fin" to (r.tImpact + tpl.time(r.i1)))
        val gi = mapOf("bs" to ((tpl.backswingT - tpl.t0) / tpl.dt).toInt(), "imp" to tpl.impact, "fin" to ((tpl.finishT - tpl.t0) / tpl.dt).toInt())
        synchronized(ring) {
            for ((k, tq) in times) {
                val f = ring.minByOrNull { kotlin.math.abs(it.t - tq) } ?: continue
                val aff = f.aff ?: continue
                out[k] = Snap(f.bmp, f.lm, aff, f.pose, place(g[gi[k]!!.coerceIn(0, tpl.n - 1)], f.pose))
            }
        }
        return out
    }

    private fun tick(t: Double) {
        when (phase) {
            Phase.SETUP -> if (visibleSince > 0 && t - visibleSince > 1.0) main.post { if (phase == Phase.SETUP) goPhase(Phase.DEMO) }
            Phase.DEMO -> if (t - phaseStart > 5.0) main.post { if (phase == Phase.DEMO) goPhase(Phase.FOLLOW) }
            Phase.REVIEW -> if (t - phaseStart > 14.0) main.post { if (phase == Phase.REVIEW) nextRound() }
            else -> {}
        }
    }

    // ================= swings =================
    private fun onSwing(r: SwingResult, snap: Map<String, Snap>) {
        if (phase != Phase.FOLLOW && phase != Phase.FOCUS) return
        roundResults.add(r); snaps[r] = snap
        val good = if (phase == Phase.FOCUS) r.focusOk == true else r.stars == 3
        streak = if (good) streak + 1 else 0
        flashColor = when { phase == Phase.FOCUS -> if (r.focusOk == true) Ui.ACC else Ui.OPP; r.stars == 3 -> Ui.ACC; r.stars == 2 -> Ui.WARN; else -> Ui.OPP }
        flashUntil = System.currentTimeMillis() + 900
        starsShown = if (phase == Phase.FOCUS) (if (r.focusOk == true) 3 else 1) else r.stars
        starsUntil = System.currentTimeMillis() + 1500
        say(coach.speech(r, streak))
        if (roundResults.size >= 5) main.postDelayed({ startReview() }, 1600)
        view.postInvalidate()
    }

    private fun startReview() {
        val focusKey = coach.focus
        val issue = if (focusKey != null) ISSUES.first { it.key == focusKey } else coach.chooseFocus(5)
        // the swing where the issue shows most
        val r = roundResults.maxByOrNull { it.z[issue.key] ?: 0.0 } ?: return goPhase(Phase.FOLLOW)
        val moment = when (issue.key) { "knee_bs" -> "bs"; "elbow_imp", "upperarm_imp" -> "imp"; else -> "fin" }
        val s = snaps[r]?.get(moment) ?: snaps[r]?.values?.firstOrNull() ?: return goPhase(Phase.FOLLOW)
        reviewIssue = issue; review = r to s
        val okCount = roundResults.count { it.focusOk == true }
        lastRoundWasFocus = focusKey != null; lastOk = okCount
        bigText = issue.cue
        smallText = if (focusKey != null) "這一輪做到 $okCount/5 下" else "這一輪 " + roundResults.joinToString(" ") { "★".repeat(it.stars) }
        phase = Phase.REVIEW; phaseStart = clock; round++
        say(if (focusKey != null && okCount >= 4) "太棒了，做到 $okCount 下！看一下，再加強" else "看這張，${issue.cue}")
        view.postInvalidate()
    }

    fun onTapReview() { if (phase == Phase.REVIEW) nextRound() }
    /** after a focus round that went well (4+/5), go back to free swings and find the next thing to work on */
    private fun nextRound() = goPhase(if (lastRoundWasFocus && lastOk >= 4) Phase.FOLLOW else Phase.FOCUS)

    override fun onDestroy() {
        tts?.shutdown(); exec.execute { landmarker?.close() }; exec.shutdown(); super.onDestroy()
    }
}
