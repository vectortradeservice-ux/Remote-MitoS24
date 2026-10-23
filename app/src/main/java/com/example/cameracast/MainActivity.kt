package com.example.cameracast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Ui.BG)
        }
        root.addView(Ui.text(this, "Camera Cast", 30f, true))
        root.addView(
            Ui.text(this, "ถ่ายด้วยแอปกล้อง Samsung บนมือถือ แล้วดูภาพ/โฟกัส/กดชัตเตอร์จากแท็บเล็ต", 14f),
            Ui.lp(Ui.WRAP, Ui.WRAP, Ui.dp(this, 8))
        )
        root.addView(
            Ui.button(this, "เครื่องนี้คือ มือถือ (ตัวส่งภาพ)") {
                startActivity(Intent(this, SenderActivity::class.java))
            },
            Ui.lp(Ui.MATCH, Ui.dp(this, 72), Ui.dp(this, 32))
        )
        root.addView(
            Ui.button(this, "เครื่องนี้คือ แท็บเล็ต (ตัวรับ/มอนิเตอร์)") {
                startActivity(Intent(this, ReceiverActivity::class.java))
            },
            Ui.lp(Ui.MATCH, Ui.dp(this, 72), Ui.dp(this, 12))
        )
        setContentView(root)
    }
}
