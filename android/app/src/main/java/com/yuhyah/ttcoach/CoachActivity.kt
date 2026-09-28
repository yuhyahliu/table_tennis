package com.yuhyah.ttcoach

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Live coaching screen: camera → pose → engine → cues, plus calibration, scoring and data logging. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CoachActivity : ComponentActivity() {
    enum class Phase { CAL, LOADING, COACH }

    private val prefs by lazy { getSharedPreferences("tt", Context.MODE_PRIVATE) }
    private val match get() = prefs.getString("mode", "match") == "match"
    private val rightHanded get() = prefs.getString("hand", "R") == "R"

    // ---- views ----
    lateinit var preview: PreviewView
    lateinit var overlay: OverlayView
    private lateinit var hud: TextView
    private lateinit var cue: TextView
    private lateinit var calHelp: TextView
    private lateinit var calBar: LinearLayout
    private lateinit var scoreBar: LinearLayout
    private lateinit var board: TextView
    private lateinit var goBtn: Button

    // ---- state (image frame = analysis image after rotation, pixels) ----
    @Volatile var phase = Phase.CAL
    @Volatile var imgW = 1280.0
    @Volatile var imgH = 720.0
    val pts = ArrayList<DoubleArray>()
    @Volatile var cal: Calib? = null
    val coach = Coach(Opts(rightHanded = true))
    @Volatile var lastPoses: List<List<Lm>> = emptyList()
    @Volatile var lastPick = -1
    @Volatile var lastM: Measure? = null
    @Volatile private var lastT = 0.0
    private var fpsRange = Range(30, 30)
    private var fpsCount = 0; private var fpsT0 = 0L; @Volatile private var fps = 0
    private var videoOn = false

    // ---- engines ----
    private val analysisExec: ExecutorService = Executors.newSingleThreadExecutor()
    private var landmarker: PoseLandmarker? = null
    private var delegateName = ""
    private var tts: TextToSpeech? = null
    private var log: SessionLog? = null
    private var recording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private val sessionName = "tt_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ---- score ----
    private var me = 0; private var opp = 0; private var gMe = 0; private var gOpp = 0
    private data class Snap(val me: Int, val opp: Int, val gMe: Int, val gOpp: Int, val game: Int)
    private val hist = ArrayList<Snap>()
    private val logLines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        coach.o.rightHanded = rightHanded
        buildUi()
        tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.TAIWAN) }
        loadSavedCalibration()
        startCamera()
        analysisExec.execute { createLandmarker() } // warm up while the coach taps
    }

    // ================= UI =================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(size: Float = 15f) = TextView(this).apply {
        textSize = size; setTextColor(Ui.FG); setPadding(dp(12), dp(6), dp(12), dp(6)); background = Ui.round(Color.argb(200, 10, 18, 16), dp(18).toFloat())
    }
    private fun button(label: String, color: Int = Ui.FG, fill: Int = Color.argb(200, 18, 28, 25), onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(color); textSize = 15f; background = Ui.round(fill, dp(12).toFloat(), Ui.LINE)
        setPadding(dp(12), 0, dp(12), 0); setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        overlay = OverlayView(this, this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        hud = pill(14f).apply { text = "準備中" }
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(8), dp(10), 0) })

        cue = TextView(this).apply {
            textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(20), dp(8), dp(20), dp(10))
            background = Ui.round(Color.argb(235, 6, 30, 22), dp(18).toFloat(), Ui.ACC, dp(2)); visibility = android.view.View.GONE
        }
        root.addView(cue, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(48) })

        calHelp = pill(15f).apply { gravity = Gravity.CENTER }
        root.addView(calHelp, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

        calBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        calBar.addView(button("重點") { pts.clear(); recompute() }, LinearLayout.LayoutParams(dp(96), dp(48)).apply { marginEnd = dp(8) })
        calBar.addView(button("返回") { finishSession() }, LinearLayout.LayoutParams(dp(96), dp(48)).apply { marginEnd = dp(8) })
        goBtn = button("開始", Color.parseColor("#052e1c"), Ui.ACC) { startCoach() }
        calBar.addView(goBtn, LinearLayout.LayoutParams(dp(120), dp(48)))
        root.addView(calBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(10) })

        scoreBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = android.view.View.GONE; setPadding(dp(8), 0, dp(8), dp(8)) }
        val bOpp = button("對手 +1", Ui.OPP, Color.argb(150, 18, 28, 25)) { point(false) }.apply { textSize = 20f; background = Ui.round(Color.argb(150, 18, 28, 25), dp(14).toFloat(), Ui.OPP, dp(2)) }
        val bMe = button("我方 +1", Ui.ACC, Color.argb(150, 18, 28, 25)) { point(true) }.apply { textSize = 20f; background = Ui.round(Color.argb(150, 18, 28, 25), dp(14).toFloat(), Ui.ACC, dp(2)) }
        bOpp.setOnLongClickListener { undo(); true }; bMe.setOnLongClickListener { undo(); true }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        board = pill(26f).apply { gravity = Gravity.CENTER }
        mid.addView(board)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("局間重點" to { showNotes("第 ${coach.game + 1} 局目前重點", synchronized(coach) { coach.notes() }) },
            "紀錄" to { showLog() }, "校正" to { enterCal() }, "結束" to { finishSession() }).forEach { (l, f) ->
            tools.addView(button(l) { f() }.apply { textSize = 13f }, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(4); topMargin = dp(4) })
        }
        mid.addView(tools)
        scoreBar.addView(bOpp, LinearLayout.LayoutParams(0, dp(60), 1f))
        scoreBar.addView(mid, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8); marginEnd = dp(8) })
        scoreBar.addView(bMe, LinearLayout.LayoutParams(0, dp(60), 1f))
        root.addView(scoreBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        paintScore(); recompute()
    }

    // ================= calibration =================
    private val stepText = listOf("點 ① 選手這側的「左」桌角", "點 ② 選手這側的「右」桌角", "點 ③ 「右」邊線和球網的交點", "點 ④ 「左」邊線和球網的交點")

    fun recompute() {
        cal = if (pts.size == 4) calibrate(pts.toList(), imgW, imgH) else null
        val c = cal
        calHelp.text = when {
            pts.size < 4 -> stepText[pts.size] + "\n放大鏡幫你對準；點完四個點後可以拖動微調"
            c == null || !c.ok -> "⚠️ ${c?.reason ?: "四個點排列不正確"}\n順序：① 左角 ② 右角 ③ 右邊網 ④ 左邊網"
            else -> "綠框有貼齊球桌就按「開始」\n" + placementAdvice(c.cam, match, rightHanded).joinToString("\n") { (if (it.good) "✅ " else "⚠️ ") + it.text }
        }
        goBtn.isEnabled = c?.ok == true; goBtn.alpha = if (goBtn.isEnabled) 1f else 0.4f
        overlay.postInvalidate()
    }

    private fun loadSavedCalibration() {
        val s = prefs.getString("calib", null) ?: return
        val v = s.split(',').mapNotNull { it.toDoubleOrNull() }
        if (v.size == 10) { imgW = v[0]; imgH = v[1]; for (i in 0 until 4) pts.add(doubleArrayOf(v[2 + 2 * i] * imgW, v[3 + 2 * i] * imgH)) }
    }
    private fun saveCalibration() {
        prefs.edit().putString("calib", (listOf(imgW, imgH) + pts.flatMap { listOf(it[0] / imgW, it[1] / imgH) }).joinToString(",")).apply()
    }

    private fun enterCal() {
        phase = Phase.CAL; calHelp.visibility = android.view.View.VISIBLE; calBar.visibility = android.view.View.VISIBLE
        scoreBar.visibility = android.view.View.GONE; cue.visibility = android.view.View.GONE; recompute()
    }

    private fun startCoach() {
        if (cal?.ok != true) return
        saveCalibration()
        calBar.visibility = android.view.View.GONE
        calHelp.text = "載入辨識模型中…"
        phase = Phase.LOADING
        analysisExec.execute {
            if (landmarker == null) createLandmarker()
            runOnUiThread {
                if (landmarker == null) { calHelp.text = "⚠️ 辨識模型載入失敗"; calBar.visibility = android.view.View.VISIBLE; phase = Phase.CAL; return@runOnUiThread }
                calHelp.visibility = android.view.View.GONE; scoreBar.visibility = android.view.View.VISIBLE
                phase = Phase.COACH
                startLogging()
                say("開始")
            }
        }
    }

    // ================= camera =================
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val info = selector.filter(provider.availableCameraInfos).firstOrNull()
            val ranges = info?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }?.toList() ?: emptyList()
            fpsRange = ranges.firstOrNull { it.lower == 60 && it.upper == 60 } ?: ranges.filter { it.lower == it.upper }.maxByOrNull { it.upper } ?: Range(30, 30)
            val res = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val pb = Preview.Builder().setResolutionSelector(res)
            Camera2Interop.Extender(pb).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            val pv = pb.build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val ab = ImageAnalysis.Builder().setResolutionSelector(res)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            Camera2Interop.Extender(ab).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            val analysis = ab.build().also { it.setAnalyzer(analysisExec) { img -> analyze(img) } }

            val wantVideo = prefs.getBoolean("saveVideo", true)
            provider.unbindAll()
            var bound = false
            if (wantVideo) {
                try {
                    val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                    val vc = VideoCapture.withOutput(recorder)
                    provider.bindToLifecycle(this, selector, pv, analysis, vc)
                    videoCapture = vc; videoOn = true; bound = true
                } catch (e: Exception) {
                    Log.w(TAG, "preview+analysis+video not supported", e); provider.unbindAll()
                    Toast.makeText(this, "這支手機無法同時錄影和分析，已關閉錄影（骨架資料照常儲存）", Toast.LENGTH_LONG).show()
                }
            }
            if (!bound) provider.bindToLifecycle(this, selector, pv, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createLandmarker() {
        val model = if (prefs.getString("model", "full") == "lite") "pose_landmarker_lite.task" else "pose_landmarker_full.task"
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(model).setDelegate(d).build())
                    .setRunningMode(RunningMode.VIDEO).setNumPoses(2)
                    .setMinPoseDetectionConfidence(0.5f).setMinPosePresenceConfidence(0.5f).setMinTrackingConfidence(0.5f)
                    .build()
                landmarker = PoseLandmarker.createFromOptions(this, opts); delegateName = d.name; return
            } catch (e: Exception) { Log.w(TAG, "landmarker $d failed", e) }
        }
    }

    private var lastTsMs = -1L
    private fun analyze(img: ImageProxy) {
        try {
            val rot = img.imageInfo.rotationDegrees
            val w = if (rot % 180 == 0) img.width else img.height
            val h = if (rot % 180 == 0) img.height else img.width
            if (w.toDouble() != imgW || h.toDouble() != imgH) {
                // keep calibration taps in proportion if the analysis size differs from the saved one
                val sx = w / imgW; val sy = h / imgH
                synchronized(pts) { pts.forEach { it[0] *= sx; it[1] *= sy } }
                imgW = w.toDouble(); imgH = h.toDouble(); runOnUiThread { recompute() }
            }
            val lm = landmarker
            val c = cal
            if (phase != Phase.COACH || lm == null || c == null) return
            var bmp: Bitmap = img.toBitmap()
            if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            var ts = img.imageInfo.timestamp / 1_000_000L
            if (ts <= lastTsMs) ts = lastTsMs + 1
            lastTsMs = ts
            val res = lm.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            val norm = res.landmarks()
            val poses = norm.map { p -> p.map { q -> Lm(q.x() * imgW, q.y() * imgH, q.visibility().orElse(1f).toDouble()) } }
            val t = ts / 1000.0
            val pick = pickNearPlayer(c.cam, poses)
            val ev = synchronized(coach) { coach.update(pick?.second, t) }
            lastPoses = poses; lastPick = pick?.first ?: -1; lastM = pick?.second; lastT = t
            log?.let { L ->
                val raw = norm.map { p -> FloatArray(33 * 4) { k -> val q = p[k / 4]; when (k % 4) { 0 -> q.x(); 1 -> q.y(); 2 -> q.z(); else -> q.visibility().orElse(0f) } } }
                val world = pick?.first?.let { i -> res.worldLandmarks().getOrNull(i) }?.let { p -> FloatArray(33 * 3) { k -> val q = p[k / 3]; when (k % 3) { 0 -> q.x(); 1 -> q.y(); else -> q.z() } } }
                L.frame(t, raw, lastPick, world)
            }
            fpsCount++
            val now = SystemClock.elapsedRealtime()
            if (now - fpsT0 >= 1000) { fps = (fpsCount * 1000 / (now - fpsT0)).toInt(); fpsCount = 0; fpsT0 = now; runOnUiThread { paintHud() } }
            if (ev != null) runOnUiThread { onEvent(ev) }
            overlay.postInvalidate()
        } catch (e: Exception) {
            Log.w(TAG, "analyze", e)
        } finally { img.close() }
    }

    // ================= coaching =================
    private fun onEvent(ev: CoachEvent) {
        when (ev) {
            is CoachEvent.Start -> { cue.visibility = android.view.View.GONE; log?.line("{\"ev\":\"start\",\"t\":$lastT}") }
            is CoachEvent.Drop -> {}
            is CoachEvent.End -> {
                val r = ev.rally
                val n = synchronized(coach) { coach.rallies.size }
                addLog("第 $n 分 ${r.detail()}" + (ev.cue?.let { " → ${it.text}" } ?: ""))
                log?.line(String.format(Locale.US, "{\"ev\":\"end\",\"t0\":%.3f,\"t1\":%.3f,\"ratio\":%.4f,\"left\":%.3f,\"width\":%.3f,\"dist\":%.3f,\"upright\":%.3f,\"cue\":\"%s\"}",
                    r.t0, r.t1, r.ratio, r.leftShare, r.width, r.dist, r.upright, ev.cue?.k ?: ""))
                ev.cue?.let { c ->
                    cue.text = c.text + "\n" + r.detail()
                    cue.background = Ui.round(Color.argb(235, 6, 30, 22), dp(18).toFloat(), if (c.good) Color.parseColor("#60a5fa") else Ui.ACC, dp(2))
                    cue.visibility = android.view.View.VISIBLE
                    say(c.text.replace("✓", "").replace("✗", ""))
                }
            }
        }
        paintHud()
    }

    fun paintHud() {
        val m = lastM; val st = coach.standing
        val parts = ArrayList<String>()
        parts.add(if (coach.inRally) "🔴 回合中" else "⏸ 分與分之間")
        synchronized(coach) { coach.focus }?.let { parts.add("本局重點：${FOCUS_NAME[it]}") }
        if (m == null) parts.add(if (phase == Phase.COACH) "找不到選手" else "")
        else {
            parts.add(if (coach.noseHist.size < 60 || !st.isFinite()) "重心 校準中" else "重心 ${(m.nose / st * 100).roundToInt()}%")
            val x = if (rightHanded) m.g[0] else -m.g[0]
            parts.add("站位 " + if (x < -0.25) "偏反手" else if (x > 0.25) "偏正手" else "中間")
        }
        parts.add("$fps/${fpsRange.upper} fps")
        if (log != null) parts.add(if (videoOn && recording != null) "● 錄影" else "● 記錄")
        hud.text = parts.filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    private fun paintScore() { board.text = "$me : $opp   局 $gMe:$gOpp" }

    private fun point(meWon: Boolean) {
        hist.add(Snap(me, opp, gMe, gOpp, coach.game))
        if (meWon) me++ else opp++
        val r = synchronized(coach) { coach.markPoint(meWon, lastT) }
        addLog((if (meWon) "得分 " else "失分 ") + "$me:$opp" + if (r == null) "（這分沒抓到回合）" else "")
        log?.line("{\"ev\":\"point\",\"me\":$meWon,\"t\":$lastT,\"score\":\"$me:$opp\"}")
        if ((me >= 11 || opp >= 11) && kotlin.math.abs(me - opp) >= 2) {
            val g = coach.game
            if (me > opp) gMe++ else gOpp++
            val notes = synchronized(coach) { coach.notes(g) }
            showNotes("第 ${g + 1} 局結束 $me:$opp · 局間重點", notes)
            synchronized(coach) { coach.newGame() }
            me = 0; opp = 0
        }
        paintScore()
    }

    private fun undo() {
        val s = hist.removeLastOrNull() ?: return
        me = s.me; opp = s.opp; gMe = s.gMe; gOpp = s.gOpp
        synchronized(coach) { coach.game = s.game; coach.undoPoint() }
        addLog("↩︎ 收回上一分"); paintScore()
        Toast.makeText(this, "已收回上一分", Toast.LENGTH_SHORT).show()
    }

    private fun showNotes(title: String, lines: List<String>) {
        val tv = TextView(this).apply {
            text = lines.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n\n"); textSize = 22f; setTextColor(Ui.FG); setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setView(tv).setPositiveButton("關閉", null).show()
        say(lines.joinToString("。"))
        addLog("📋 " + lines.joinToString("；"))
    }

    private fun showLog() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("紀錄")
            .setMessage(logLines.asReversed().joinToString("\n")).setPositiveButton("關閉", null).show()
    }
    private fun addLog(s: String) { logLines.add(s) }

    private fun say(text: String) {
        if (!prefs.getBoolean("voice", !match)) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tt")
    }

    // ================= data logging =================
    @SuppressLint("MissingPermission")
    private fun startLogging() {
        if (log != null || !prefs.getBoolean("saveData", true)) { paintHud(); return }
        log = SessionLog(this, sessionName).also { L ->
            val c = cal!!.cam
            L.line(String.format(Locale.US,
                "{\"hdr\":1,\"app\":\"ttcoach\",\"version\":\"%s\",\"imgW\":%.0f,\"imgH\":%.0f,\"fpsRange\":[%d,%d],\"delegate\":\"%s\",\"model\":\"%s\",\"mode\":\"%s\",\"hand\":\"%s\",\"taps\":[%s],\"camC\":[%.4f,%.4f,%.4f],\"f\":%.2f,\"video\":%s}",
                packageManager.getPackageInfo(packageName, 0).versionName, imgW, imgH, fpsRange.lower, fpsRange.upper, delegateName,
                prefs.getString("model", "full"), if (match) "match" else "practice", if (rightHanded) "R" else "L",
                pts.joinToString(",") { String.format(Locale.US, "[%.1f,%.1f]", it[0], it[1]) }, c.c[0], c.c[1], c.c[2], c.f, videoOn))
        }
        val vc = videoCapture
        if (videoOn && vc != null) {
            val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$sessionName.mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/TTCoach")
                }).build()
            recording = vc.output.prepareRecording(this, opts).start(ContextCompat.getMainExecutor(this)) { }
        }
        paintHud()
    }

    private fun stopLogging() {
        recording?.stop(); recording = null
        log?.let { it.line("{\"ev\":\"close\",\"frames\":${it.frames}}"); it.close() }; log = null
    }

    private fun finishSession() {
        val had = log != null
        stopLogging()
        if (had) Toast.makeText(this, "已儲存：下載/TTCoach/$sessionName" + if (videoOn) "（含影片）" else "", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onPause() { super.onPause(); if (isFinishing) stopLogging() }
    override fun onDestroy() {
        stopLogging()
        tts?.shutdown()
        analysisExec.execute { landmarker?.close(); landmarker = null }
        analysisExec.shutdown()
        super.onDestroy()
    }

    companion object { const val TAG = "TTCoach" }
}

