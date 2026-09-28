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
import kotlin.math.hypot
import kotlin.math.max

/**
 * 空拍練習（小孩畫面）: front camera, mirrored like a mirror. Flow:
 * SETUP (whole body visible; any natural stance, only fully side-on is corrected) → DEMO (watch the shadow, 2 warm-up swings)
 * → READY: walk into place, hold the backswing (引拍) like the frozen shadow = start (the coach can also tap: 3-2-1)
 * → FOLLOW: each real swing beeps + big counter + colour/stars/short voice
 * → after 5: REVIEW carousel of all 5 swings (slow motion + shadow, good ones green, others freeze with arrow + 口訣),
 *   looping until 「下一輪」 is tapped → READY → FOCUS 5 swings on that one point → REVIEW → READY → …
 * Nothing is counted outside a round. Tap during a round = pause; stepping out of the picture pauses by itself;
 * walking around is ignored. Long-press anywhere = coach debug overlay (why a swing was not counted).
 * With 「存骨架」 on, every session is saved to Download/TTCoach/shadow_*.jsonl.gz for tuning.
 */
class ShadowActivity : ComponentActivity() {
    enum class Phase { SETUP, DEMO, READY, COUNTDOWN, FOLLOW, FOCUS, PAUSED, REPLAY }

    /** one camera frame kept for the replay: small JPEG + 2D landmarks + projection + 3D pose */
    class ClipFrame(val t: Double, val jpeg: ByteArray, val lm: List<FloatArray>, val aff: Affine?, val pose: Pose)
    /** one replay segment: a swing, played slowly, frozen at `freezeT` */
    class Seg(val num: Int, val r: SwingResult, val clip: List<ClipFrame>, val good: Boolean, val issue: IssueDef?, val freezeT: Double, val freezeFor: Double, val caption: String)

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
    @Volatile var phaseStart = 0.0
    private var lastMotion = 0.0
    private var readySince = -1.0
    /** 0..1 while the player holds the backswing to start (drawn as a ring around the racket hand) */
    @Volatile var readyFrac = 0f
    private lateinit var bsPose: BackswingPose
    private var lostSince = -1.0
    private var lastHip: DoubleArray? = null; private var lastHipT = 0.0
    private var movingUntil = 0.0
    private var log: SessionLog? = null
    private var lastLoggedReject = ""
    private var frames = 0; private var fpsT0 = 0.0; private var frameNo = 0L

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
    /** the round that starts after READY / the one a pause interrupted */
    var nextPhase = Phase.FOLLOW
    private var focusTargetNext = 1.0
    private var resumeAfterCountdown = false
    /** PAUSED because the player left the picture (resumes by itself) rather than a tap */
    var autoPaused = false
    private var demoSwings = 0
    /** the review being shown is of a focus round (✓/✗ instead of stars) */
    var roundWasFocusShown = false
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
    private var reviewPass = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        spec = StrokeSpec.of(intent.getStringExtra("stroke"))
        tpl = Template(assets.open(spec.asset).bufferedReader().readText())
        coach = ShadowCoach(tpl, rightHanded, spec)
        tracker = PhaseTracker(tpl, rightHanded)
        bsPose = BackswingPose(tpl, rightHanded)
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
        if (prefs.getBoolean("saveData", true)) try {
            val name = "shadow_${spec.key}_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
            log = SessionLog(this, name).also { L ->
                if (!L.ok) log = null
                else L.line("{\"hdr\":1,\"app\":\"ttcoach-shadow\",\"version\":\"${packageManager.getPackageInfo(packageName, 0).versionName}\",\"stroke\":\"${spec.key}\",\"hand\":\"${if (rightHanded) "R" else "L"}\",\"camera\":\"front\"}")
            }
        } catch (e: Exception) { Log.w("TTShadow", "log", e); log = null }
        goPhase(Phase.SETUP)
        exec.execute { createLandmarker() }
        startCamera()
    }

    fun say(s: String, interrupt: Boolean = true, queue: Boolean = false) {
        if (s.isBlank()) return
        val t = tts ?: return
        if (!interrupt && !queue && t.isSpeaking) return
        t.speak(s, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "sh")
    }
    private fun beep() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 70) } catch (_: Exception) {} }

    val strokeName get() = spec.name
    /** stand as at the table with the phone on the far side: any natural stance works; only fully side-on hides the racket arm */
    private val angleOk get() = turnSmooth < 70.0
    private val angleHint = "身體轉回來一點，面對手機"
    @Volatile var turnSmooth = 0.0

    fun goPhase(p: Phase, line: String? = null, resume: Boolean = false) {
        phase = p; phaseStart = clock; readySince = -1.0; readyFrac = 0f
        log?.line(String.format(Locale.US, "{\"ev\":\"phase\",\"p\":\"%s\",\"t\":%.3f}", p.name, clock))
        when (p) {
            Phase.SETUP -> { bigText = "站到畫面中間"; subText = "頭到腳都要拍到" }
            Phase.DEMO -> { bigText = "看影子，輕輕揮兩下"; subText = ""; demoSwings = 0; say("看影子怎麼打${strokeName}，跟著輕輕揮兩下") }
            Phase.READY -> {
                coach.resetSpeed()                 // the next player may be slower (a child after an adult)
                bigText = "引拍準備"
                subText = if (nextPhase == Phase.FOCUS) "這一輪只想：${focusIssue?.cue ?: ""}" else "像影子一樣把拍子拉到後面，停一下就開始"
                say(line ?: (if (nextPhase == Phase.FOCUS) "下一輪只想一件事，${focusIssue?.cue}。站好，引拍準備就開始" else "站好，引拍準備就開始"))
            }
            Phase.COUNTDOWN -> { bigText = ""; subText = ""; say("三，二，一，開始！") }
            Phase.PAUSED -> { bigText = if (autoPaused) "回到畫面中間" else "暫停"; subText = if (autoPaused) "" else "引拍準備或點一下繼續"; if (!autoPaused) say("暫停") }
            Phase.FOLLOW -> { if (!resume) startRound(null); bigText = ""; subText = ""; line?.let { say(it) } }
            Phase.FOCUS -> { val d = focusIssue!!; if (!resume) startRound(d); bigText = d.cue; subText = ""; line?.let { say(it) } }
            Phase.REPLAY -> {}
        }
        view.postInvalidate()
    }

    private fun startCountdown(resume: Boolean) { resumeAfterCountdown = resume; goPhase(Phase.COUNTDOWN) }
    /** started by holding the backswing: go at once, the player is already in position */
    private fun startNow(resume: Boolean) { beep(); say("開始！"); goPhase(nextPhase, resume = resume) }
    private val inRound get() = phase == Phase.FOLLOW || phase == Phase.FOCUS

    private fun startRound(focus: IssueDef?) {
        roundResults.clear(); roundDetected = 0; detectTimes.clear(); coach.resetCount(); coach.focus = focus?.key
        coach.focusTarget = if (focus != null) focusTargetNext else 1.0
        synchronized(clips) { clips.clear() }
    }

    // ================= camera + pose =================
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val res = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(960, 540), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()   // enough for the pose model; faster
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
            if (n == null || w == null) { lastLm = null; visibleSince = -1.0; angleOkSince = -1.0; energy *= 0.85f; readySince = -1.0; readyFrac = 0f
                if (lostSince < 0) lostSince = t
                coach.hold = true; tick(t); view.postInvalidate(); return }
            val lm = n.map { floatArrayOf(it.x(), it.y(), it.visibility().orElse(0f)) }
            val world = w.map { doubleArrayOf(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }
            val pose = poseFromMpWorld(world, rightHanded)
            val zup = world.map { mpWorldToZUp(it[0], it[1], it[2]) }
            val aff = Affine.fit(zup, lm.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }, lm.map { maxOf(0.05, it[2].toDouble()) })
            lastLm = lm; lastAff = aff
            turnDeg = bodyTurnDeg(world); turnSmooth = 0.9 * turnSmooth + 0.1 * turnDeg     // depth is noisy: smooth it
            log?.let { L ->
                L.frame(t, listOf(FloatArray(33 * 4) { k -> val q = n[k / 4]; when (k % 4) { 0 -> q.x(); 1 -> q.y(); 2 -> q.z(); else -> q.visibility().orElse(0f) } }), 0,
                    FloatArray(33 * 3) { k -> val q = w[k / 3]; when (k % 3) { 0 -> q.x(); 1 -> q.y(); else -> q.z() } })
            }

            // replay material: small JPEG of every other frame for the last few seconds (compressing every frame cost frame rate)
            val keep = (frameNo++ % 2L == 0L)
            val jpg = if (!keep) null else {
                val small = Bitmap.createScaledBitmap(bmp, 480, 480 * bmp.height / bmp.width, true)
                ByteArrayOutputStream(24_000).also { small.compress(Bitmap.CompressFormat.JPEG, 65, it) }.toByteArray().also { small.recycle() }
            }
            synchronized(ring) {
                if (jpg != null) { ring.addLast(ClipFrame(t, jpg, lm, aff, pose)); while (ring.isNotEmpty() && t - ring.first().t > 3.5) ring.removeFirst() }
                // swing clip: from a bit before its backswing to a bit after its finish (slow swings are longer)
                val done = pendingClips.filter { t >= it.tImpact + 0.5 * it.scale + 0.1 }
                for (r in done) { val c = ring.filter { it.t >= r.tImpact - 0.75 * r.scale && it.t <= r.tImpact + 0.5 * r.scale }; synchronized(clips) { clips[r] = c } }
                pendingClips.removeAll(done.toSet())
            }

            // whole body visible? standing at the right angle?
            val body = listOf(27, 28, 0).all { lm[it][2] > 0.5 && lm[it][1] in 0.02f..0.99f }
            visibleSince = if (body) (if (visibleSince < 0) t else visibleSince) else -1.0
            angleOkSince = if (body && angleOk) (if (angleOkSince < 0) t else angleOkSince) else -1.0
            lostSince = if (body) -1.0 else (if (lostSince < 0) t else lostSince)
            // 引拍準備 (hold the backswing, whole body in the picture) = start / continue
            val waiting = phase == Phase.READY || (phase == Phase.PAUSED && !autoPaused)
            val inBackswing = waiting && body && bsPose.distance(pose, kidArm) < bsPose.near && coach.speed < 0.8
            readySince = if (inBackswing) (if (readySince < 0) t else readySince) else -1.0
            readyFrac = if (readySince > 0) ((t - readySince) / READY_HOLD).toFloat().coerceIn(0f, 1f) else 0f
            // walking around (hips moving across the picture) is not a swing
            val hip = doubleArrayOf(((lm[23][0] + lm[24][0]) / 2).toDouble(), ((lm[23][1] + lm[24][1]) / 2).toDouble())
            lastHip?.let { h -> val v = hypot(hip[0] - h[0], hip[1] - h[1]) / max(1e-3, t - lastHipT); if (v > 0.35) movingUntil = t + 0.8 }
            lastHip = hip; lastHipT = t
            val listening = phase == Phase.DEMO || inRound
            coach.hold = !listening || t < movingUntil

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
                if (e is ShadowEvent.Scored) {
                    synchronized(ring) { pendingClips.add(e.r) }
                    val r = e.r
                    log?.line(String.format(Locale.US, "{\"ev\":\"swing\",\"t\":%.3f,\"shape\":%.3f,\"stars\":%d,\"z\":{%s},\"v\":{%s}}", r.tImpact, r.shape, r.stars,
                        r.z.entries.joinToString(",") { String.format(Locale.US, "\"%s\":%.2f", it.key, it.value) },
                        r.values.entries.joinToString(",") { String.format(Locale.US, "\"%s\":%.3f", it.key, it.value) }))
                }
                main.post { onEvent(e) }
            }
            if (coach.lastReject != lastLoggedReject) {
                lastLoggedReject = coach.lastReject
                if (lastLoggedReject.isNotEmpty()) log?.line(String.format(Locale.US, "{\"ev\":\"reject\",\"t\":%.3f,\"why\":\"%s\"}", t, lastLoggedReject))
            }

            // the shadow: follows the player's own swing; plays the stroke by itself when they stand still
            val idle = t - lastMotion > 3.0
            val gi = if (phase == Phase.READY || phase == Phase.PAUSED) bsPose.index        // show the pose to hold
                     else if (!inRound || idle) loopIndex(t, if (phase == Phase.DEMO) 2.0 else 1.0)
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
                    val waited = t - visibleSince > 5.0              // depth is noisy: never get stuck here
                    if (ok || waited) main.post { if (phase == Phase.SETUP) goPhase(Phase.DEMO) }
                    else {
                        main.post { if (phase == Phase.SETUP) { bigText = angleHint; subText = "" } }
                        if (angleNagAt < 0 || t - angleNagAt > 6.0) { angleNagAt = t; main.post { say(angleHint) } }
                    }
                } else main.post { if (phase == Phase.SETUP) { bigText = "站到畫面中間"; subText = "頭到腳都要拍到" } }
            }
            Phase.DEMO -> if (t - phaseStart > 5.0 && (demoSwings >= 2 || t - phaseStart > 12.0)) main.post { if (phase == Phase.DEMO) { nextPhase = Phase.FOLLOW; goPhase(Phase.READY, "看懂了嗎？像影子一樣引拍準備，就開始") } }
            Phase.READY -> if (readySince > 0 && t - readySince > READY_HOLD && t - phaseStart > 1.0) main.post { if (phase == Phase.READY) startNow(false) }
            Phase.COUNTDOWN -> if (t - phaseStart > 3.0) main.post { if (phase == Phase.COUNTDOWN) goPhase(nextPhase, resume = resumeAfterCountdown) }
            Phase.PAUSED -> main.post {
                if (phase != Phase.PAUSED) return@post
                if (autoPaused && visibleSince > 0 && t - visibleSince > 1.0) { autoPaused = false; say("繼續"); goPhase(nextPhase, resume = true) }
                else if (!autoPaused && readySince > 0 && t - readySince > READY_HOLD && t - phaseStart > 1.0) startNow(true)
            }
            Phase.FOLLOW, Phase.FOCUS -> if (lostSince > 0 && t - lostSince > 0.7) main.post { if (inRound) pause(auto = true) }
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
        roundWasFocus = focus != null; roundWasFocusShown = roundWasFocus
        roundOk = rs.count { it.focusOk == true }
        val issue = focus ?: coach.chooseFocus(rs.size)
        val zOf = { r: SwingResult -> r.z[issue.key] ?: 0.0 }
        val byClip = synchronized(clips) { rs.filter { (clips[it]?.size ?: 0) > 4 }.associateWith { clips[it]!! } }
        roundAllGood = if (focus != null) roundOk >= 4 else rs.all { it.stars == 3 } && rs.maxOf(zOf) < 1.5
        focusIssue = issue
        // the next focus round asks for a step from where the player is now, not the athletes' level at once
        val base = pct(rs.map { it.z[issue.key] ?: 0.0 }, 0.5)
        focusTargetNext = max(1.0, 0.6 * base)
        val list = ArrayList<Seg>()
        rs.forEachIndexed { k, r ->
            val clip = byClip[r] ?: return@forEachIndexed
            val good = if (focus != null) r.focusOk == true else r.stars == 3
            val iss = focus ?: r.worst
            val ft = if (good) 0.0 else when (iss.at) { "bs" -> tpl.time(r.ib) * r.scale; "fin" -> tpl.time(r.i1) * r.scale; else -> 0.0 }
            list.add(Seg(k + 1, r, clip, good, if (good) null else iss, ft, if (good) 1.2 else 2.5, if (good) "很棒！" else iss.cue))
        }
        val nGood = rs.count { if (focus != null) it.focusOk == true else it.stars == 3 }
        val summary = (if (steadyRhythm()) "節奏很穩！" else "") + "這一輪 $nGood 下很棒。看看每一下，看完點下一輪"
        if (list.isEmpty()) { say(summary); nextRound(); return }
        say(summary)
        startReplay(list)
    }

    // ================= replay =================
    private val replayTick = object : Runnable {
        override fun run() {
            if (phase != Phase.REPLAY) return
            val dt = 1 / 30.0
            val s = segs[segIdx]
            if (frozen) {
                freezeLeft -= dt
                if (freezeLeft <= 0) frozen = false
            } else {
                val before = segT
                segT += dt * 0.5                                    // half speed
                if (before < s.freezeT && segT >= s.freezeT && freezeLeft > 0) {
                    segT = s.freezeT; frozen = true
                    if (reviewPass == 0 && saidForSeg != segIdx) { saidForSeg = segIdx; say("第${s.num}下，${s.caption}", queue = true) }
                }
                val end = s.clip.last().t - s.r.tImpact
                if (segT > end) {
                    if (segIdx + 1 < segs.size) segIdx++ else { segIdx = 0; reviewPass++ }      // keeps looping until 下一輪
                    startSeg()
                }
            }
            showAt(segs[segIdx], segT)
            view.invalidate()
            main.postDelayed(this, 33)
        }
    }

    private fun startReplay(list: List<Seg>) {
        segs = list; segIdx = 0; saidForSeg = -1; reviewPass = 0
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

    /** review: jump to one swing (thumbnail) */
    fun showSwing(i: Int) { if (phase == Phase.REPLAY && i in segs.indices) { segIdx = i; startSeg(); view.invalidate() } }
    /** review: 「下一輪」 */
    fun nextFromReview() { if (phase == Phase.REPLAY) finishReplay() }

    fun onTap() {
        when (phase) {
            Phase.REPLAY -> {}
            Phase.SETUP, Phase.DEMO -> { nextPhase = Phase.FOLLOW; goPhase(Phase.READY) }
            Phase.READY -> startCountdown(false)
            Phase.FOLLOW, Phase.FOCUS -> pause(auto = false)
            Phase.PAUSED -> { autoPaused = false; startCountdown(true) }
            Phase.COUNTDOWN -> {}
        }
    }

    private fun pause(auto: Boolean) { nextPhase = phase; autoPaused = auto; goPhase(Phase.PAUSED) }

    /** a good round → free swings again; otherwise 5 swings thinking about the one point */
    private fun nextRound() {
        nextPhase = if (roundAllGood) Phase.FOLLOW else Phase.FOCUS
        goPhase(Phase.READY, when {
            roundAllGood && roundWasFocus -> "做到了！下一輪自由揮。站好，引拍準備就開始"
            roundAllGood -> "五下都很棒！站好，引拍準備就開始"
            else -> null })
    }

    companion object { const val READY_HOLD = 0.7 }

    override fun onDestroy() {
        main.removeCallbacks(replayTick)
        log?.let { it.line("{\"ev\":\"close\",\"frames\":${it.frames}}"); it.close() }; log = null
        tts?.shutdown(); tone?.release(); exec.execute { landmarker?.close() }; exec.shutdown(); super.onDestroy()
    }
}
