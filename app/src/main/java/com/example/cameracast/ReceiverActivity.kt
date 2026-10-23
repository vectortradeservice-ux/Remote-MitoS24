package com.example.cameracast

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.hypot

/**
 * ฝั่งแท็บเล็ต: แสดงภาพจากมือถือเต็มจอ แล้ว "แตะ/ลาก/กดค้าง" บนภาพได้เหมือนแตะมือถือโดยตรง
 * (รวมถึงปุ่มชัตเตอร์ของแอปกล้อง) มีปุ่มเล็กมุมขวาบนสำหรับหมุนภาพและตัดการเชื่อมต่อ
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback, StreamClient.Callback {

    private lateinit var prefs: SharedPreferences
    private lateinit var form: View
    private lateinit var monitor: View
    private lateinit var video: FitSurfaceView
    private lateinit var etIp: EditText
    private lateinit var etPin: EditText

    private var client: StreamClient? = null
    private var pendingConnect = false
    private var pendingHost = ""
    private var pendingPin = 0
    private var rotation = 0          // 0/90/180/270 (ตามเข็ม)
    private var vidW = 0
    private var vidH = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("cc", MODE_PRIVATE)
        rotation = prefs.getInt("rotation", 0)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        form = buildForm()
        monitor = buildMonitor()
        monitor.visibility = View.GONE
        root.addView(form)
        root.addView(monitor)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "cameracast-rx").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        client?.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (monitor.visibility == View.VISIBLE) disconnect() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- UI

    private fun buildForm(): View {
        val pad = Ui.dp(this, 32)
        val gap = Ui.dp(this, 12)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(Ui.BG)
        }
        box.addView(Ui.text(this, "เชื่อมต่อกับมือถือ", 24f, true))

        etIp = EditText(this).apply {
            hint = "IP ของมือถือ เช่น 192.168.43.1"
            setText(prefs.getString("ip", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        etPin = EditText(this).apply {
            hint = "PIN 6 หลัก (ดูบนหน้าจอมือถือ)"
            setText(prefs.getString("pinText", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        box.addView(etIp, Ui.lp(Ui.MATCH, Ui.WRAP, gap))
        box.addView(etPin, Ui.lp(Ui.MATCH, Ui.WRAP, gap))
        box.addView(
            Ui.button(this, "ใช้ IP ของ Hotspot (gateway อัตโนมัติ)") {
                val gw = gatewayIp()
                if (gw == null) toast("ไม่พบ gateway: ต่อ Wi-Fi ของมือถือก่อน") else etIp.setText(gw)
            },
            Ui.lp(Ui.MATCH, Ui.WRAP, gap)
        )
        box.addView(Ui.button(this, "เชื่อมต่อ") { connect() }, Ui.lp(Ui.MATCH, Ui.dp(this, 64), gap))

        if (etIp.text.isNullOrBlank()) gatewayIp()?.let { etIp.setText(it) }
        return box
    }

    private fun buildMonitor(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        video = FitSurfaceView(this)
        video.holder.addCallback(this)
        frame.addView(video, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER))

        // --- แตะ/ลาก/กดค้างบนภาพ -> ส่งไปทำบนมือถือ ---
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
        var dx = 0f
        var dy = 0f
        var t0 = 0L
        var moved = false
        video.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.x
                    dy = e.y
                    t0 = e.eventTime
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - dx, e.y - dy) > slop) moved = true
                }
                MotionEvent.ACTION_UP -> {
                    val w = v.width.toFloat()
                    val h = v.height.toFloat()
                    val (x1, y1) = mapToPhone((dx / w).coerceIn(0f, 1f), (dy / h).coerceIn(0f, 1f))
                    val (x2, y2) = mapToPhone((e.x / w).coerceIn(0f, 1f), (e.y / h).coerceIn(0f, 1f))
                    val dur = (e.eventTime - t0).toInt()
                    if (!moved && dur < 350) {
                        client?.sendTap(x1, y1)
                    } else {
                        client?.sendSwipe(x1, y1, x2, y2, dur)
                    }
                }
            }
            true
        }

        // --- ปุ่มเล็กมุมขวาบน: หมุนภาพ / ตัดการเชื่อมต่อ ---
        val size = Ui.dp(this, 48)
        val margin = Ui.dp(this, 12)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(roundButton("↻", size) { rotateView() })
        bar.addView(
            roundButton("✕", size) { disconnect() },
            LinearLayout.LayoutParams(size, size).apply { leftMargin = margin }
        )
        frame.addView(
            bar,
            FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply {
                setMargins(margin, margin, margin, margin)
            }
        )
        return frame
    }

    private fun roundButton(label: String, size: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            alpha = 0.55f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#99000000"))
                setStroke(Ui.dp(this@ReceiverActivity, 1), Color.parseColor("#66FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }

    // ---------------------------------------------------------------- rotation

    /** แปลงพิกัดบนภาพที่ถูกหมุนแล้ว (0..1) กลับเป็นพิกัดบนหน้าจอมือถือ */
    private fun mapToPhone(u: Float, v: Float): Pair<Float, Float> = when (rotation) {
        90 -> Pair(v, 1f - u)
        180 -> Pair(1f - u, 1f - v)
        270 -> Pair(1f - v, u)
        else -> Pair(u, v)
    }

    private fun rotateView() {
        rotation = (rotation + 90) % 360
        prefs.edit().putInt("rotation", rotation).apply()
        client?.setRotation(rotation)
        updateVideoSize()
    }

    private fun updateVideoSize() {
        if (vidW == 0 || vidH == 0) return
        if (rotation == 90 || rotation == 270) video.setVideoSize(vidH, vidW) else video.setVideoSize(vidW, vidH)
    }

    // ---------------------------------------------------------------- connection

    private fun connect() {
        val ip = etIp.text.toString().trim()
        val pin = etPin.text.toString().trim().toIntOrNull()
        if (ip.isEmpty() || pin == null) {
            toast("กรอก IP และ PIN ให้ครบ")
            return
        }
        prefs.edit().putString("ip", ip).putString("pinText", pin.toString()).apply()
        pendingHost = ip
        pendingPin = pin
        pendingConnect = true
        form.visibility = View.GONE
        monitor.visibility = View.VISIBLE
        if (video.holder.surface.isValid) startClient()
    }

    private fun startClient() {
        if (!pendingConnect) return
        pendingConnect = false
        client?.close()
        client = StreamClient(pendingHost, pendingPin, video.holder.surface, rotation, this).also { it.start() }
    }

    private fun disconnect() {
        pendingConnect = false
        client?.close()
        client = null
        monitor.visibility = View.GONE
        form.visibility = View.VISIBLE
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (pendingConnect) startClient()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // ออกจากแอป/พักจอ -> หยุดสตรีม (decoder ต้องมี Surface อยู่)
        if (client != null) disconnect()
    }

    // StreamClient.Callback (เรียกจาก thread เครือข่าย)
    override fun onSize(w: Int, h: Int) {
        ui.post {
            vidW = w
            vidH = h
            updateVideoSize()
        }
    }

    override fun onMessage(text: String) {
        ui.post { toast(text) }
    }

    override fun onClosed(reason: String) {
        ui.post {
            disconnect()
            toast(reason)
        }
    }

    // ---------------------------------------------------------------- helpers

    @Suppress("DEPRECATION")
    private fun gatewayIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val gw = wm.dhcpInfo?.gateway ?: 0
            if (gw == 0) null
            else "${gw and 0xff}.${(gw shr 8) and 0xff}.${(gw shr 16) and 0xff}.${(gw shr 24) and 0xff}"
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
