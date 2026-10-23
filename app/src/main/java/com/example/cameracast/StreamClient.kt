package com.example.cameracast

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * ฝั่งแท็บเล็ต: เชื่อมต่อมือถือ รับ H.264 -> ถอดรหัสลง Surface ทันที (ไม่มี buffer รอ)
 * และส่งคำสั่ง แตะ/ลาก/ชัตเตอร์ กลับไป
 */
class StreamClient(
    private val host: String,
    private val pin: Int,
    private val surface: Surface,
    @Volatile private var rotation: Int,
    private val cb: Callback
) : Thread("stream-client") {

    interface Callback {
        fun onSize(w: Int, h: Int)
        fun onMessage(text: String)
        fun onClosed(reason: String)
    }

    private val mime = MediaFormat.MIMETYPE_VIDEO_AVC

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopped = false
    @Volatile private var decAlive = false

    private var decoder: MediaCodec? = null
    private var outThread: Thread? = null
    private var pts = 0L
    private var waitKey = true
    private var lastW = 0
    private var lastH = 0
    @Volatile private var restartDecoder = false
    private val sender = Executors.newSingleThreadExecutor()

    init {
        isDaemon = true
    }

    override fun run() {
        var gotSize = false
        var reason = "ตัดการเชื่อมต่อ"
        try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, Protocol.PORT), 4000)
            val os = s.getOutputStream()
            os.write(ByteBuffer.allocate(8).putInt(Protocol.MAGIC).putInt(pin).array())
            os.flush()
            out = os

            val din = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            var buf = ByteArray(1 shl 18)
            while (!stopped) {
                val type = din.readByte()
                val len = din.readInt()
                if (len < 0 || len > 16 * 1024 * 1024) throw IOException("bad length")
                if (buf.size < len) buf = ByteArray(len * 2)
                din.readFully(buf, 0, len)
                when (type) {
                    Protocol.V_SIZE -> {
                        val bb = ByteBuffer.wrap(buf, 0, 8)
                        val w = bb.int
                        val h = bb.int
                        startDecoder(w, h)
                        gotSize = true
                        cb.onSize(w, h)
                    }
                    Protocol.V_FRAME -> feed(buf, len)
                    Protocol.V_MSG -> cb.onMessage(String(buf, 0, len, Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            reason = if (gotSize) "การเชื่อมต่อหลุด" else "เชื่อมต่อไม่ได้ หรือ PIN ไม่ถูกต้อง"
        } finally {
            val wasStopped = stopped
            close()
            if (!wasStopped) cb.onClosed(reason)
        }
    }

    // ---------------------------------------------------------------- decoder

    private fun startDecoder(w: Int, h: Int) {
        stopDecoder()
        lastW = w
        lastH = h
        val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
            if (rotation != 0) setInteger(MediaFormat.KEY_ROTATION, rotation)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)                           // ไม่เก็บเฟรมไว้เรียง
            setInteger(MediaFormat.KEY_PRIORITY, 0)                              // real-time
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)               // Qualcomm
        }
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, surface, null, 0)
        dec.start()
        decoder = dec
        decAlive = true
        waitKey = true
        outThread = Thread {
            val info = MediaCodec.BufferInfo()
            while (decAlive) {
                try {
                    val i = dec.dequeueOutputBuffer(info, 10_000)
                    if (i >= 0) dec.releaseOutputBuffer(i, true) // แสดงทันที
                } catch (e: Exception) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopDecoder() {
        decAlive = false
        try { outThread?.join(300) } catch (_: Exception) {}
        outThread = null
        val dec = decoder ?: return
        decoder = null
        try { dec.stop() } catch (_: Exception) {}
        try { dec.release() } catch (_: Exception) {}
    }

    private fun feed(data: ByteArray, len: Int) {
        if (restartDecoder && lastW > 0) {
            restartDecoder = false
            startDecoder(lastW, lastH) // สร้างใหม่ด้วยค่า rotation ใหม่ แล้วรอเฟรม key
            requestKeyFrame()
        }
        val dec = decoder ?: return
        val key = data[0].toInt() == 1
        if (waitKey) {
            if (!key) return
            waitKey = false
        }
        val i = dec.dequeueInputBuffer(100_000)
        if (i < 0) {
            // decoder ตามไม่ทัน: ทิ้งเฟรม แล้วรอเฟรม key ใหม่เพื่อไม่ให้ภาพเพี้ยนสะสม
            waitKey = true
            requestKeyFrame()
            return
        }
        val ib = dec.getInputBuffer(i) ?: return
        ib.clear()
        ib.put(data, 1, len - 1)
        dec.queueInputBuffer(i, 0, len - 1, pts, 0)
        pts += 16_667
    }

    // ---------------------------------------------------------------- commands (ส่งผ่าน thread แยก ห้ามใช้เน็ตบน UI thread)

    private fun sendBytes(b: ByteArray) {
        try {
            sender.execute {
                try {
                    out?.write(b)
                    out?.flush()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun sendTap(x: Float, y: Float) = sendBytes(
        ByteBuffer.allocate(9).put(Protocol.C_TAP).putFloat(x).putFloat(y).array()
    )

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Int) = sendBytes(
        ByteBuffer.allocate(21).put(Protocol.C_SWIPE)
            .putFloat(x1).putFloat(y1).putFloat(x2).putFloat(y2).putInt(ms).array()
    )

    /** หมุนภาพที่แสดง (0/90/180/270 องศาตามเข็ม) — decoder จะหมุนให้ตอนวาดลง Surface */
    fun setRotation(deg: Int) {
        rotation = deg
        restartDecoder = true
    }

    fun requestKeyFrame() = sendBytes(byteArrayOf(Protocol.C_KEYFRAME))

    /** ปิดการเชื่อมต่อ (เรียกซ้ำได้) */
    fun close() {
        stopped = true
        try { socket?.close() } catch (_: Exception) {}
        stopDecoder()
        try { sender.shutdown() } catch (_: Exception) {}
    }
}
