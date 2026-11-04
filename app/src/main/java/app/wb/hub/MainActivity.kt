package app.wb.hub

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private var bg = 0; private var cardBg = 0; private var strokeC = 0
    private var ink = 0; private var dim = 0
    private val accent = Color.parseColor("#4F7CFF")
    private val green = Color.parseColor("#34C759")
    private val red = Color.parseColor("#FF453A")
    private val amber = Color.parseColor("#E0A030")

    private var statusPill: TextView? = null
    private var baseVal: TextView? = null
    private var sbh = 0
    private val handler = Handler(Looper.getMainLooper())
    private var autoStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPalette(); sbh = statusBarHeight(); applyBars(bg)
        setContentView(buildPage())
        refreshLoop()
        if (!autoStarted) { autoStarted = true; startGateway() }
    }

    private fun applyPalette() {
        if (isNight()) { bg = Color.parseColor("#202327"); cardBg = Color.parseColor("#2A2E34")
            strokeC = Color.parseColor("#3A3F45"); ink = Color.parseColor("#ECEDEE"); dim = Color.parseColor("#9AA0A6") }
        else { bg = Color.WHITE; cardBg = Color.parseColor("#F5F6F8")
            strokeC = Color.parseColor("#E4E6EA"); ink = Color.parseColor("#16181D"); dim = Color.parseColor("#6B7075") }
    }

    private fun buildPage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            setPadding(dp(24), sbh + dp(26), dp(24), dp(40)) }

        col.addView(TextView(this).apply { text = "WorkBuddy 网关"; textSize = 32f
            setTextColor(ink); typeface = Typeface.create("sans-serif", Typeface.BOLD) })
        col.addView(TextView(this).apply { text = "本地反代 · 多账号 · 8788"; textSize = 14f
            setTextColor(dim); setPadding(0, dp(6), 0, dp(20)) })

        statusPill = TextView(this).apply { text = "●  正在启动…"; textSize = 15f; setTextColor(amber)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(if (isNight()) Color.parseColor("#2A2E34") else Color.parseColor("#EEF0F3")) }
        col.addView(statusPill)

        val infoCard = card()
        infoCard.addView(sectionTitle("接入信息"))
        baseVal = addRow(infoCard, "Base URL", "http://127.0.0.1:8788/v1", true)
        addRow(infoCard, "面板密码", "admin（可改）", false)

        val actionCard = card()
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(pillBtn("启动", green) { startGateway() })
        r1.addView(space(dp(12)))
        r1.addView(pillBtn("停止", red) {
            stopService(Intent(this@MainActivity, GatewayService::class.java))
            statusPill?.text = "●  已停止"; statusPill?.setTextColor(dim) })
        actionCard.addView(r1)
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        r2.addView(pillBtn("打开看板", accent) { openWeb() })
        r2.addView(space(dp(10)))
        r2.addView(pillBtn("日志", Color.parseColor(if (isNight()) "#3A3F45" else "#E1E3E7"), ink) { showLog() })
        actionCard.addView(r2)

        col.addView(infoCard); col.addView(actionCard)
        scroll.addView(col)
        return scroll
    }

    private fun sectionTitle(t: String) = TextView(this).apply { text = t; textSize = 13f
        setTextColor(dim); setPadding(0, 0, 0, dp(8)) }

    private fun addRow(parent: LinearLayout, label: String, value: String, mono: Boolean): TextView {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
        val l = TextView(this).apply { text = label; textSize = 13.5f; setTextColor(dim) }
        val v = TextView(this).apply { text = value; textSize = 13.5f; setTextColor(ink); gravity = Gravity.END
            isClickable = true; if (mono) typeface = Typeface.MONOSPACE }
        l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f)
        r.addView(l); r.addView(v); parent.addView(r)
        v.setOnClickListener { copyText(v.text?.toString().orEmpty()) }
        return v
    }

    private fun card(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
        background = box(cardBg, dp(20).toFloat(), strokeC); setPadding(dp(20), dp(16), dp(20), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) } }

    private fun pill(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(40).toFloat(); setColor(fill) }
    private fun box(fill: Int, radius: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(fill); setStroke(maxOf(1, dp(1)), stroke) }
    private fun pillBtn(label: String, fill: Int, textColor: Int = Color.WHITE, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(textColor)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER; setPadding(dp(18), dp(12), dp(18), dp(12))
        background = pill(fill); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }

    private fun space(px: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px, 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun isNight() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24) }
    private fun applyBars(color: Int) {
        window.statusBarColor = color; window.navigationBarColor = color
        var vis = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val light = Color.luminance(color) > 0.5f
        vis = if (light) vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        window.decorView.systemUiVisibility = vis }

    private fun startGateway() {
        val i = Intent(this, GatewayService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        statusPill?.text = "●  启动中…"; statusPill?.setTextColor(amber) }

    private fun openWeb() {
        if (!GatewayService.running) { Toast.makeText(this, "请先启动网关", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(this, WebViewActivity::class.java)
            .putExtra("url", "http://127.0.0.1:${GatewayService.actualPort}/")) }

    private fun copyText(s: String) {
        if (s.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("wb", s))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show() }

    private fun showLog() {
        val f = File(filesDir, "gateway.log")
        val body = try { if (f.exists()) f.readText().lineSequence().toList().takeLast(200).joinToString("\n") else "暂无日志" }
                   catch (e: Exception) { "读取失败:${e.message}" }
        val tv = TextView(this).apply { text = body; textSize = 11.5f
            setTextColor(if (isNight()) Color.parseColor("#D4D6DA") else Color.parseColor("#2A2D33"))
            typeface = Typeface.MONOSPACE; setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { copyText(body) } }
        val sv = ScrollView(this).apply { addView(tv); isVerticalScrollBarEnabled = false }
        AlertDialog.Builder(this).setTitle("网关日志（点文字复制）").setView(sv).setPositiveButton("关闭", null).show() }

    private fun refreshLoop() {
        handler.postDelayed({
            val running = GatewayService.running
            val port = GatewayService.actualPort
            val err = GatewayService.lastError
            when {
                running -> { statusPill?.text = "●  运行中 · 端口 $port"; statusPill?.setTextColor(green) }
                err != null -> { statusPill?.text = "●  启动失败（点日志查看）"; statusPill?.setTextColor(red) }
                else -> { statusPill?.text = "●  启动中 / 未启动"; statusPill?.setTextColor(amber) }
            }
            baseVal?.text = "http://127.0.0.1:$port/v1"
            refreshLoop()
        }, 1500) }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
