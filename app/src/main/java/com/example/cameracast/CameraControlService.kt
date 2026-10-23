package com.example.cameracast

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * รับคำสั่งจากแท็บเล็ต (ผ่าน ScreenCastService) แล้วแตะ/ลาก/กดชัตเตอร์บนหน้าจอมือถือ
 * โดยทำงานกับแอปกล้องเดิมของเครื่อง (Samsung Camera)
 */
class CameraControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CameraControlService? = null

        private val SHUTTER_IDS = listOf(
            "com.sec.android.app.camera:id/normal_center_button",
            "com.sec.android.app.camera:id/center_button"
        )
        private val SHUTTER_DESC = Regex(
            "(take picture|shutter|start recording|stop recording|record video|ถ่ายภาพ|ชัตเตอร์|เริ่มบันทึก|หยุดบันทึก|บันทึกวิดีโอ)",
            RegexOption.IGNORE_CASE
        )
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

    /**
     * กดชัตเตอร์ (ถ่ายภาพ หรือเริ่ม/หยุดอัดวิดีโอ ตามโหมดที่แอปกล้องเปิดอยู่)
     * - ถ้าแท็บเล็ตส่งตำแหน่งที่ตั้งไว้มา -> แตะตำแหน่งนั้นทันที (เร็วและแน่นอนที่สุด)
     * - ถ้าไม่ -> หาปุ่มชัตเตอร์จากโครงสร้างหน้าจอ
     * คืน false ถ้าหาปุ่มไม่เจอ
     */
    fun shutter(nx: Float, ny: Float): Boolean {
        if (nx in 0f..1f && ny in 0f..1f) {
            tap(nx, ny)
            return true
        }
        val node = findShutter() ?: return false
        val r = Rect()
        node.getBoundsInScreen(r)
        gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 40)
        return true
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

    private fun findShutter(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (id in SHUTTER_IDS) {
            val hit = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }
            if (hit != null) return hit
        }
        return search(root, 0)
    }

    private fun search(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 25) return null
        val id = n.viewIdResourceName
        val desc = n.contentDescription?.toString()
        if (n.isVisibleToUser &&
            ((id != null && (id.endsWith("center_button") || id.contains("shutter", true))) ||
                (desc != null && SHUTTER_DESC.containsMatchIn(desc)))
        ) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            search(c, depth + 1)?.let { return it }
        }
        return null
    }
}
