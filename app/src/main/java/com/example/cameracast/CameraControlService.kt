package com.example.cameracast

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * รับคำสั่งจากแท็บเล็ต (ผ่าน ScreenCastService) แล้วแตะ/ลาก/กดค้างบนหน้าจอมือถือ
 * ทุกอย่างในแอปกล้อง (โฟกัส ความสว่าง ชัตเตอร์ สลับโหมด ซูม) ทำได้ด้วยการสัมผัสโดยตรง
 */
class CameraControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CameraControlService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    @Suppress("DEPRECATION")
    private fun screen(): DisplayMetrics {
        val m = DisplayMetrics()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m
    }

    /** แตะ (พิกัด 0..1) */
    fun tap(nx: Float, ny: Float) {
        val m = screen()
        val x = nx * m.widthPixels
        val y = ny * m.heightPixels
        gesture(x, y, x, y, 40)
    }

    /** ลาก หรือกดค้าง (ถ้าจุดเริ่ม=จุดจบ) */
    fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durMs: Int) {
        val m = screen()
        gesture(
            nx1 * m.widthPixels, ny1 * m.heightPixels,
            nx2 * m.widthPixels, ny2 * m.heightPixels, durMs.toLong()
        )
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val m = screen()
        val maxX = (m.widthPixels - 1).toFloat()
        val maxY = (m.heightPixels - 1).toFloat()
        val path = Path().apply {
            moveTo(x1.coerceIn(0f, maxX), y1.coerceIn(0f, maxY))
            if (abs(x2 - x1) > 2f || abs(y2 - y1) > 2f) {
                lineTo(x2.coerceIn(0f, maxX), y2.coerceIn(0f, maxY))
            }
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceIn(20L, 8000L)))
            .build()
        main.post { dispatchGesture(g, null, null) }
    }
}
