package com.example.cameracast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/** หน้าจอฝั่งมือถือ: เปิดสิทธิ์ เริ่ม/หยุดส่งภาพ แสดง IP + PIN */
class SenderActivity : Activity() {

    private val reqCapture = 100
    private val reqNotif = 101

    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private var pin = 0
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cc", MODE_PRIVATE)
        pin = prefs.getInt("pin", 0)
        if (pin == 0) {
            pin = Random.nextInt(100000, 999999)
            prefs.edit().putInt("pin", pin).apply()
        }

        val pad = Ui.dp(this, 20)
        val gap = Ui.dp(this, 12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Ui.BG)
        }
        root.addView(Ui.text(this, "มือถือ (ตัวส่งภาพ)", 24f, true))
        status = Ui.text(this, "", 16f)
        root.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP, gap))

        root.addView(
            Ui.button(this, "1) เปิดสิทธิ์ Accessibility (ให้แท็บเล็ตสั่งแตะ/ชัตเตอร์)") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Ui.lp(Ui.MATCH, Ui.WRAP, gap)
        )
        btnStart = Ui.button(this, "2) เริ่มส่งภาพ") { toggle() }
        root.addView(btnStart, Ui.lp(Ui.MATCH, Ui.WRAP, gap))
        root.addView(
            Ui.button(this, "3) เปิดแอปกล้อง") { openCamera() },
            Ui.lp(Ui.MATCH, Ui.WRAP, gap)
        )
        root.addView(
            Ui.text(
                this,
                "หมายเหตุ: ตอนระบบถามสิทธิ์จับภาพหน้าจอ ให้เลือก \"ทั้งหน้าจอ\" (Entire screen) " +
                    "ไม่ใช่ \"แอปเดียว\"\n\nแนะนำให้เปิด Mobile Hotspot (ย่าน 5 GHz) จากมือถือเครื่องนี้ " +
                    "แล้วให้แท็บเล็ตต่อเข้ามา จะได้ delay ต่ำที่สุด",
                13f
            ),
            Ui.lp(Ui.MATCH, Ui.WRAP, gap * 2)
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val ips = localIps()
        val a11y = if (CameraControlService.instance != null) "เปิดแล้ว ✓" else "ยังไม่เปิด ✗"
        val cast = if (ScreenCastService.running) "กำลังส่ง ●" else "หยุดอยู่"
        status.text = "IP มือถือ: ${if (ips.isEmpty()) "(ไม่พบ - ต่อ Wi-Fi/เปิด Hotspot ก่อน)" else ips.joinToString(", ")}\n" +
            "พอร์ต: ${Protocol.PORT}\nPIN: $pin\n\nAccessibility: $a11y\nการส่งภาพ: $cast"
        btnStart.text = if (ScreenCastService.running) "2) หยุดส่งภาพ" else "2) เริ่มส่งภาพ"
    }

    private fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toggle() {
        if (ScreenCastService.running) {
            startService(Intent(this, ScreenCastService::class.java).setAction(ScreenCastService.ACTION_STOP))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
        } else {
            launchCapture()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == reqNotif) launchCapture() // ไม่ว่าจะอนุญาตหรือไม่ ก็ไปต่อได้
    }

    private fun launchCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "ไม่ได้รับอนุญาตให้จับภาพหน้าจอ", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, ScreenCastService::class.java)
            .putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCastService.EXTRA_DATA, data)
            .putExtra(ScreenCastService.EXTRA_PIN, pin)
        startForegroundService(i)
        ui.postDelayed({ openCamera() }, 400)
    }

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (e: Exception) {
            Toast.makeText(this, "เปิดแอปกล้องไม่ได้ ให้เปิดเองจากหน้าหลัก", Toast.LENGTH_SHORT).show()
        }
    }
}
