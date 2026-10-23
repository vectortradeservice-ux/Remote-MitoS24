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
import android.view.HapticFeedbackConstants
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
 * ฝั่งแท็บเล็ต: แสดงภาพจากมือถือเต็มจอ
 *  - แตะบนภาพ = โฟกัส, ลาก (เริ่มที่ไอคอนดวงอาทิตย์/แถบความสว่าง) = ปรับความสว่าง, กดค้าง = ล็อก AE/AF
 *  - ปุ่มชัตเตอร์ใหญ่แยกต่างหากด้านขวา (ถ่ายภาพ หรือเริ่ม/หยุดอัดวิดีโอ ตามโหมดของแอปกล้อง)
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback, StreamClient.Callback {

    private lateinit var prefs: SharedPreferences
    private lateinit var form: View
    private lateinit var monitor: View
    private lateinit var video: FitSurfaceView
    private lateinit var etIp: EditText
    private lateinit var etPin: EditText
    private lateinit var btnCal: Button

    private var client: StreamClient? = null
    private var pendingConnect = false
    private var pendingHost = ""
    private var pendingPin = 0
    private var calibrating = false
    private var shutX = -1f
    private var shutY = -1f
    private var wifiLock: WifiManager.WifiLock? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("cc", MODE_PRIVATE)
        shutX = prefs.getFloat("shutX", -1f)
        shutY = prefs.getFloat("shutY", -1f)

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
            setPadding(pad * 3, pad, pad * 3, pad)
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }

        val frame = FrameLayout(this)
        video = FitSurfaceView(this)
        video.holder.addCallback(this)
        frame.addView(video, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        row.addView(frame, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))

        // --- แตะ/ลากบนภาพ ---
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
        var dx = 0f
        var dy = 0f
        var t0 = 0L
        var moved = false
        video.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.x; dy = e.y; t0 = e.eventTime; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - dx, e.y - dy) > slop) moved = true
                }
                MotionEvent.ACTION_UP -> {
                    val w = v.width.toFloat()
                    val h = v.height.toFloat()
                    val x1 = (dx / w).coerceIn(0f, 1f)
                    val y1 = (dy / h).coerceIn(0f, 1f)
                    val x2 = (e.x / w).coerceIn(0f, 1f)
                    val y2 = (e.y / h).coerceIn(0f, 1f)
                    val dur = (e.eventTime - t0).toInt()
                    if (calibrating) {
                        calibrating = false
                        shutX = x1
                        shutY = y1
                        prefs.edit().putFloat("shutX", shutX).putFloat("shutY", shutY).apply()
                        btnCal.text = "ตั้งตำแหน่งชัตเตอร์ ✓"
                        toast("บันทึกตำแหน่งปุ่มชัตเตอร์แล้ว")
                    } else if (!moved && dur < 350) {
                        client?.sendTap(x1, y1)
                    } else {
                        client?.sendSwipe(x1, y1, x2, y2, dur)
                    }
                }
            }
            true
        }

        // --- แผงควบคุมด้านขวา ---
        val pad = Ui.dp(this, 16)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Ui.PANEL)
        }
        val size = Ui.dp(this, 150)
        val shutter = Button(this).apply {
            text = "ถ่าย / REC"
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(Ui.dp(this@ReceiverActivity, 6), Color.rgb(220, 40, 40))
            }
            // ส่งคำสั่งตอน "แตะลง" เพื่อให้ตอบสนองเร็วที่สุด
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        v.alpha = 0.6f
                        client?.sendShutter(shutX, shutY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                true
            }
        }
        panel.addView(shutter, Ui.lp(size, size))

        btnCal = Ui.button(this, if (shutX >= 0f) "ตั้งตำแหน่งชัตเตอร์ ✓" else "ตั้งตำแหน่งชัตเตอร์") {
            calibrating = true
            toast("แตะที่ปุ่มชัตเตอร์บนภาพ (กดค้างปุ่มนี้เพื่อกลับไปโหมดหาปุ่มอัตโนมัติ)")
        }
        btnCal.setOnLongClickListener {
            shutX = -1f
            shutY = -1f
            prefs.edit().remove("shutX").remove("shutY").apply()
            btnCal.text = "ตั้งตำแหน่งชัตเตอร์"
            toast("ใช้โหมดหาปุ่มชัตเตอร์อัตโนมัติ")
            true
        }
        panel.addView(btnCal, Ui.lp(Ui.MATCH, Ui.WRAP, Ui.dp(this, 32)))
        panel.addView(Ui.button(this, "ตัดการเชื่อมต่อ") { disconnect() }, Ui.lp(Ui.MATCH, Ui.WRAP, Ui.dp(this, 8)))

        row.addView(panel, LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.MATCH))
        return row
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
        client = StreamClient(pendingHost, pendingPin, video.holder.surface, this).also { it.start() }
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
        ui.post { video.setVideoSize(w, h) }
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
