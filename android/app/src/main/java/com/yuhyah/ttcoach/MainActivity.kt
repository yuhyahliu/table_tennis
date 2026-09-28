package com.yuhyah.ttcoach

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Settings + "start" screen. Everything is built in code (no XML layouts) to keep the project small. */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("tt", Context.MODE_PRIVATE) }
    private var afterPermission: (() -> Unit)? = null
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterPermission?.invoke() else Toast.makeText(this, "需要相機權限才能使用", Toast.LENGTH_LONG).show()
    }
    private fun withCamera(go: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) go()
        else { afterPermission = go; askCamera.launch(Manifest.permission.CAMERA) }
    }
    private lateinit var tips: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(28), dp(18), dp(28)) }
        root.addView(text("場邊教練", 28f, bold = true))
        root.addView(text("v${packageManager.getPackageInfo(packageName, 0).versionName} · 原生版（每秒 60 張）", 13f, Ui.MUTE))
        root.addView(text("給教練看的即時提示：每一分打完告訴你選手的重心、站位、步寬；每局鎖定一個重點，局間整理兩三件事讓你轉述。", 15f, Ui.MUTE).also { it.setPadding(0, dp(8), 0, dp(14)) })

        val shadowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((key, name) in listOf("fh" to "正手", "bh" to "反手")) shadowRow.addView(Button(this).apply {
            text = "空拍練習\n$name"; textSize = 22f; setTextColor(Color.parseColor("#1a1206")); isAllCaps = false
            background = Ui.round(Color.parseColor(if (key == "fh") "#ffb04a" else "#ffc978"), dp(14).toFloat())
            setOnClickListener { withCamera { startActivity(Intent(this@MainActivity, ShadowActivity::class.java).putExtra("stroke", key)) } }
        }, LinearLayout.LayoutParams(0, dp(96), 1f).apply { if (key == "fh") marginEnd = dp(10) })
        root.addView(shadowRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        root.addView(text("用前鏡頭，跟著影子揮拍，不用球桌。手機或平板放在前方 2–3 公尺、胸口高度，也可以投到電視。舉手（手舉過頭）或點一下螢幕＝開始；練習中點一下＝暫停，走出畫面也會自動暫停。只有像樣的揮拍才會「嗶」一聲計數，揮滿 5 下慢動作重播最好和最需要加油的一下。長按畫面＝教練除錯資訊。\n標準動作：正手來自 34 位省隊選手（figshare，CC BY 4.0）；反手來自 TTMD6 動作捕捉資料（figshare，CC BY 4.0）。", 13f, Ui.MUTE).also { it.setPadding(0, 0, 0, dp(14)) })

        val card = card(root, "設定")
        seg(card, "模式", "mode", listOf("match" to "比賽", "practice" to "練習"), "match") { v ->
            prefs.edit().putBoolean("voice", v == "practice").apply(); recreate()
        }
        seg(card, "選手", "hand", listOf("R" to "右手持拍", "L" to "左手持拍"), "R")
        segBool(card, "語音", "voice", prefs.getString("mode", "match") == "practice")
        seg(card, "辨識", "model", listOf("lite" to "快速", "full" to "精準"), "full")

        val data = card(root, "資料收集（用來調整技術提示）")
        segBool(data, "存骨架", "saveData", true)
        segBool(data, "同時錄影", "saveVideo", true)
        data.addView(text("檔案存在手機的「下載/TTCoach」和「影片/TTCoach」。練習幾次後傳給 Claude，用來調整擊球偵測與大臂小臂、轉胯的判斷。", 13f, Ui.MUTE))

        val place = card(root, "手機擺放")
        tips = text("", 15f).also { place.addView(it) }
        paintTips()

        root.addView(Button(this).apply {
            text = "比賽／對打教練（後鏡頭）"; textSize = 20f; setTextColor(Color.parseColor("#052e1c")); isAllCaps = false
            background = Ui.round(Ui.ACC, dp(14).toFloat())
            setOnClickListener { withCamera { startCoach() } }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(8) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    private fun paintTips() {
        tips.text = if (prefs.getString("mode", "match") == "match")
            "1. 手機橫放，放在選手這側端線後方的觀眾區，2–5 公尺。\n2. 盡量放高（1.5–2 公尺往下拍），球的背景才會是球桌和地板。\n3. 畫面要拍到選手全身含腳、這側兩個桌角和球網兩端。放好就別再動。"
        else "1. 手機橫放，放在選手持拍手那側的後方斜 45 度，離選手 2–3 公尺。\n2. 高度約胸口，選手全身至少佔畫面一半高。\n3. 畫面要拍到這側兩個桌角和球網兩端，校正後 App 會告訴你位置對不對。"
    }

    private fun startCoach() = startActivity(Intent(this, CoachActivity::class.java))

    // ---------- tiny UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float, color: Int = Ui.FG, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(0f, 1.25f)
    }
    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Ui.round(Ui.PANEL, dp(16).toFloat(), Ui.LINE)
        }
        c.addView(text(title, 14f, Ui.MUTE, bold = true))
        parent.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        return c
    }
    private fun seg(parent: LinearLayout, label: String, key: String, opts: List<Pair<String, String>>, def: String, onChange: ((String) -> Unit)? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        row.addView(text(label, 15f, Ui.MUTE), LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
        val buttons = ArrayList<TextView>()
        fun paint() { val cur = prefs.getString(key, def); buttons.forEachIndexed { i, b -> val sel = opts[i].first == cur
            b.setTextColor(if (sel) Ui.ACC else Ui.FG); b.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            b.background = Ui.round(if (sel) Color.parseColor("#1f3b31") else Color.TRANSPARENT, dp(10).toFloat(), Ui.LINE) } }
        opts.forEach { (v, name) ->
            val b = text(name, 15f).apply { setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { prefs.edit().putString(key, v).apply(); paint(); if (key == "mode") paintTips(); onChange?.invoke(v) } }
            buttons.add(b); row.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        paint(); parent.addView(row)
    }
    private fun segBool(parent: LinearLayout, label: String, key: String, def: Boolean) {
        if (!prefs.contains(key)) prefs.edit().putBoolean(key, def).apply()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        row.addView(text(label, 15f, Ui.MUTE), LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
        val on = text("開", 15f); val off = text("關", 15f)
        fun paint() { val cur = prefs.getBoolean(key, def)
            listOf(on to cur, off to !cur).forEach { (b, sel) -> b.setTextColor(if (sel) Ui.ACC else Ui.FG)
                b.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                b.background = Ui.round(if (sel) Color.parseColor("#1f3b31") else Color.TRANSPARENT, dp(10).toFloat(), Ui.LINE) } }
        listOf(on to true, off to false).forEach { (b, v) -> b.setPadding(dp(16), dp(8), dp(16), dp(8))
            b.setOnClickListener { prefs.edit().putBoolean(key, v).apply(); paint() }
            row.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }) }
        paint(); parent.addView(row)
    }
}

/** shared colours and drawables */
object Ui {
    val BG = Color.parseColor("#0b1210"); val PANEL = Color.parseColor("#121c19"); val LINE = Color.parseColor("#24332e")
    val FG = Color.parseColor("#e8f0ec"); val MUTE = Color.parseColor("#8fa39b"); val ACC = Color.parseColor("#34d399")
    val WARN = Color.parseColor("#fbbf24"); val OPP = Color.parseColor("#f87171")
    fun round(fill: Int, radius: Float, stroke: Int? = null, strokeW: Int = 2) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius; if (stroke != null) setStroke(strokeW, stroke)
    }
    @Suppress("unused") fun gone(v: View) { v.visibility = View.GONE }
}
