package com.example.cameracast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * ฝั่งมือถือ: จับภาพหน้าจอ (MediaProjection) -> เข้ารหัส H.264 แบบ low-latency -> ส่งผ่าน TCP
 * และรับคำสั่งจากแท็บเล็ตเพื่อสั่ง CameraControlService
 */
class ScreenCastService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_PIN = "pin"
        const val ACTION_STOP = "com.example.cameracast.STOP"

        @Volatile
        var running = false

        // ===== ปรับตรงนี้เพื่อแลก "ความคม" กับ "delay" =====
        const val MAX_SIDE = 1920          // ด้านยาวสุดของภาพที่ส่ง (พิกเซล)
        const val BITRATE = 12_000_000     // bps
        const val FPS = 60

        private const val CHANNEL_ID = "cast"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private class Pipeline(val enc: MediaCodec, val surface: Surface, val w: Int, val h: Int) {
        @Volatile
        var alive = true
        var worker: Thread? = null
    }

    private val pipeLock = Any()
    private val writeLock = Any()

    private var projection: MediaProjection? = null
    private var projectionCb: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var pipeline: Pipeline? = null
    private var server: ServerSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pin = 0

    @Volatile
    private var clientSocket: Socket? = null
    private var clientOut: OutputStream? = null

    @Volatile
    private var needKey = true
    private var sendBuf = ByteArray(1 shl 18)

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // ต้องเรียก startForeground ทุกครั้งที่ถูกสั่งด้วย startForegroundService
        startForegroundNotification()
        if (running) return START_NOT_STICKY

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
        pin = intent.getIntExtra(EXTRA_PIN, 0)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(code, data!!)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }
        projectionCb = cb
        proj.registerCallback(cb, Handler(Looper.getMainLooper()))

        running = true
        acquireWifiLock()
        startServer()
        startPipeline()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) startPipeline() // ขนาดหน้าจอเปลี่ยน (หมุนจอ) -> สร้าง encoder ใหม่
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        synchronized(writeLock) { closeClientLocked() }
        try { display?.release() } catch (_: Exception) {}
        display = null
        synchronized(pipeLock) {
            pipeline?.let { stopPipeline(it) }
            pipeline = null
        }
        projectionCb?.let { try { projection?.unregisterCallback(it) } catch (_: Exception) {} }
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification / wifi

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Camera Cast", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "หยุดส่งภาพ", stopPi
        ).build()
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Cast กำลังส่งภาพหน้าจอ")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "cameracast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ---------------------------------------------------------------- network

    private fun startServer() {
        thread(name = "cast-accept", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Protocol.PORT))
                server = ss
                while (running) {
                    handleClient(ss.accept())
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handleClient(s: Socket) {
        thread(name = "cast-client", isDaemon = true) {
            try {
                s.tcpNoDelay = true
                s.soTimeout = 3000
                val din = DataInputStream(BufferedInputStream(s.getInputStream()))
                if (din.readInt() != Protocol.MAGIC || din.readInt() != pin) {
                    s.close()
                    return@thread
                }
                s.soTimeout = 0
                s.sendBufferSize = 128 * 1024 // buffer เล็ก = ไม่สะสม delay

                // ตัดผู้เชื่อมต่อเก่า (ปิดนอก lock เพื่อให้ write ที่ค้างอยู่หลุดออกมา)
                try { clientSocket?.close() } catch (_: Exception) {}
                synchronized(writeLock) {
                    clientSocket = s
                    clientOut = s.getOutputStream()
                    needKey = true
                    pipeline?.let {
                        writeLocked(Protocol.V_SIZE, null, sizePayload(it.w, it.h), null, 0)
                    }
                }
                requestKeyFrame()
                controlLoop(din, s)
            } catch (_: Exception) {
            } finally {
                synchronized(writeLock) {
                    if (clientSocket === s) closeClientLocked()
                }
                try { s.close() } catch (_: Exception) {}
            }
        }
    }

    private fun controlLoop(din: DataInputStream, s: Socket) {
        while (!s.isClosed) {
            when (din.readByte()) {
                Protocol.C_TAP -> {
                    val x = din.readFloat()
                    val y = din.readFloat()
                    withControl { it.tap(x, y) }
                }
                Protocol.C_SWIPE -> {
                    val x1 = din.readFloat()
                    val y1 = din.readFloat()
                    val x2 = din.readFloat()
                    val y2 = din.readFloat()
                    val ms = din.readInt()
                    withControl { it.swipe(x1, y1, x2, y2, ms) }
                }
                Protocol.C_SHUTTER -> {
                    val x = din.readFloat()
                    val y = din.readFloat()
                    withControl {
                        if (!it.shutter(x, y)) {
                            sendMessage("หาปุ่มชัตเตอร์ไม่เจอ: กด \"ตั้งตำแหน่งชัตเตอร์\" แล้วแตะที่ปุ่มชัตเตอร์บนภาพ")
                        }
                    }
                }
                Protocol.C_KEYFRAME -> {
                    needKey = true
                    requestKeyFrame()
                }
                else -> return
            }
        }
    }

    private inline fun withControl(block: (CameraControlService) -> Unit) {
        val svc = CameraControlService.instance
        if (svc == null) {
            sendMessage("มือถือยังไม่ได้เปิดสิทธิ์ Accessibility ของ Camera Cast")
        } else {
            block(svc)
        }
    }

    private fun sendMessage(msg: String) {
        synchronized(writeLock) {
            writeLocked(Protocol.V_MSG, null, msg.toByteArray(Charsets.UTF_8), null, 0)
        }
    }

    private fun closeClientLocked() {
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        clientOut = null
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    private fun sizePayload(w: Int, h: Int): ByteArray =
        ByteBuffer.allocate(8).putInt(w).putInt(h).array()

    /** เขียน 1 แพ็กเก็ตด้วย write ครั้งเดียว (ต้องเรียกภายใน synchronized(writeLock)) */
    private fun writeLocked(type: Byte, prefix: ByteArray?, a: ByteArray?, b: ByteBuffer?, bSize: Int) {
        val out = clientOut ?: return
        val payload = (prefix?.size ?: 0) + (a?.size ?: 0) + bSize
        if (sendBuf.size < 5 + payload) sendBuf = ByteArray((5 + payload) * 2)
        sendBuf[0] = type
        putInt(sendBuf, 1, payload)
        var o = 5
        if (prefix != null) {
            System.arraycopy(prefix, 0, sendBuf, o, prefix.size)
            o += prefix.size
        }
        if (a != null) {
            System.arraycopy(a, 0, sendBuf, o, a.size)
            o += a.size
        }
        if (b != null && bSize > 0) {
            b.get(sendBuf, o, bSize)
            o += bSize
        }
        try {
            out.write(sendBuf, 0, o)
            out.flush()
        } catch (e: Exception) {
            closeClientLocked()
        }
    }

    private val keyPrefix = byteArrayOf(1)
    private val deltaPrefix = byteArrayOf(0)

    private fun sendFrame(buf: ByteBuffer, size: Int, key: Boolean, cfg: ByteArray?) {
        synchronized(writeLock) {
            if (clientOut == null) return
            if (needKey) {
                if (!key) return
                needKey = false
            }
            writeLocked(
                Protocol.V_FRAME,
                if (key) keyPrefix else deltaPrefix,
                if (key) cfg else null,
                buf, size
            )
        }
    }

    private fun requestKeyFrame() {
        try {
            pipeline?.enc?.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            )
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- capture + encode

    @Suppress("DEPRECATION")
    private fun startPipeline() {
        synchronized(pipeLock) {
            val proj = projection ?: return
            val m = DisplayMetrics()
            (getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
            val scale = min(1f, MAX_SIDE.toFloat() / max(m.widthPixels, m.heightPixels))
            val w = (m.widthPixels * scale).toInt() / 8 * 8
            val h = (m.heightPixels * scale).toInt() / 8 * 8

            val cur = pipeline
            if (cur != null && cur.w == w && cur.h == h) return
            cur?.alive = false

            val enc = createEncoder(w, h)
            val surface = enc.createInputSurface()
            enc.start()
            val p = Pipeline(enc, surface, w, h)
            pipeline = p

            val vd = display
            if (vd == null) {
                display = proj.createVirtualDisplay(
                    "CameraCast", w, h, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
                )
            } else {
                vd.resize(w, h, m.densityDpi)
                vd.surface = surface
            }

            synchronized(writeLock) {
                needKey = true
                writeLocked(Protocol.V_SIZE, null, sizePayload(w, h), null, 0)
            }
            p.worker = thread(name = "cast-enc", isDaemon = true) { drain(p) }
            cur?.let { stopPipeline(it) }
        }
    }

    private fun createEncoder(w: Int, h: Int): MediaCodec {
        fun base() = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val tuned = base().apply {
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)          // ไม่มี B-frame = ไม่มี delay จากการเรียงเฟรม
            setInteger(MediaFormat.KEY_LATENCY, 1)               // บอก encoder ให้เน้น latency ต่ำ
            setInteger(MediaFormat.KEY_PRIORITY, 0)              // real-time
            setInteger(MediaFormat.KEY_OPERATING_RATE, FPS)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L) // จอนิ่งก็ยังมีเฟรมออกมา
        }

        var enc = MediaCodec.createEncoderByType(MIME)
        try {
            enc.configure(tuned, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // encoder บางตัวไม่รับบาง key -> ลองใหม่แบบพื้นฐาน
            enc.release()
            enc = MediaCodec.createEncoderByType(MIME)
            enc.configure(base(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return enc
    }

    private fun drain(p: Pipeline) {
        val info = MediaCodec.BufferInfo()
        var cfg: ByteArray? = null
        try {
            while (p.alive) {
                val i = p.enc.dequeueOutputBuffer(info, 10_000)
                if (i < 0) continue
                val buf = p.enc.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        cfg = ByteArray(info.size).also { buf.get(it) }
                    } else if (p.alive) {
                        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        sendFrame(buf, info.size, key, cfg)
                    }
                }
                p.enc.releaseOutputBuffer(i, false)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopPipeline(p: Pipeline) {
        p.alive = false
        try { p.worker?.join(300) } catch (_: Exception) {}
        try { p.enc.stop() } catch (_: Exception) {}
        try { p.enc.release() } catch (_: Exception) {}
        try { p.surface.release() } catch (_: Exception) {}
    }
}
