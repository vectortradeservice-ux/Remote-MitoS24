package com.example.cameracast

import android.content.Context
import android.view.SurfaceView
import kotlin.math.min

/** SurfaceView ที่ปรับขนาดให้พอดีกับสัดส่วนวิดีโอ (ไม่ยืด) เพื่อให้พิกัดสัมผัสตรงกับหน้าจอมือถือ */
class FitSurfaceView(context: Context) : SurfaceView(context) {
    private var vw = 0
    private var vh = 0

    fun setVideoSize(w: Int, h: Int) {
        if (w != vw || h != vh) {
            vw = w
            vh = h
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (vw == 0 || vh == 0) {
            setMeasuredDimension(maxW, maxH)
            return
        }
        val s = min(maxW.toFloat() / vw, maxH.toFloat() / vh)
        setMeasuredDimension((vw * s).toInt(), (vh * s).toInt())
    }
}
