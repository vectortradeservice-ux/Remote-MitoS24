package com.example.cameracast

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** ตัวช่วยสร้างหน้าจอด้วยโค้ด (ไม่ใช้ XML layout) */
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    val BG: Int = Color.parseColor("#101418")
    val PANEL: Int = Color.parseColor("#1A1F26")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun text(c: Context, s: String, sizeSp: Float = 16f, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = sizeSp
            setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(c: Context, s: String, onClick: () -> Unit): Button =
        Button(c).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    fun lp(w: Int, h: Int, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { topMargin = top }
}
