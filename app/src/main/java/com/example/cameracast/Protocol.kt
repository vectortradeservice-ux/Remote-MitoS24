package com.example.cameracast

/**
 * โปรโตคอลบน TCP (พอร์ตเดียว สองทิศทาง)
 *
 * แท็บเล็ต -> มือถือ  (ตอนเชื่อมต่อ): int MAGIC, int PIN
 *
 * มือถือ -> แท็บเล็ต : [type:1][len:4][payload]
 *   V_SIZE  : int w, int h
 *   V_FRAME : byte key(1/0), ข้อมูล H.264 (Annex-B) ; เฟรม key จะมี SPS/PPS นำหน้า
 *   V_MSG   : ข้อความ UTF-8 (แจ้งเตือน)
 *
 * แท็บเล็ต -> มือถือ : [type:1][fields...]
 *   C_TAP      : float x, float y            (0..1 สัมพันธ์กับหน้าจอ)
 *   C_SWIPE    : float x1,y1,x2,y2, int ms   (ลาก / กดค้าง)
 *   C_KEYFRAME : (ไม่มีข้อมูล)
 */
object Protocol {
    const val PORT = 8554
    const val MAGIC = 0x43414D31

    const val V_SIZE: Byte = 1
    const val V_FRAME: Byte = 2
    const val V_MSG: Byte = 3

    const val C_TAP: Byte = 1
    const val C_SWIPE: Byte = 2
    const val C_KEYFRAME: Byte = 4
}
