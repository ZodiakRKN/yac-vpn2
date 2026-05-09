package com.yacvpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class TunnelService : VpnService() {

    companion object {
        private const val TAG = "TunnelService"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIF_ID = 1

        val status = MutableLiveData("⚪ Отключено")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var webSocket: WebSocket? = null
    private val streams = ConcurrentHashMap<Int, StreamCtx>()
    private val streamSeq = AtomicInteger(1)
    private val msgSeq = AtomicInteger(0)
    private var connected = false

    // Frame types
    private val T_HELLO: Byte = 0x01
    private val T_HELLO_ACK: Byte = 0x02
    private val T_OPEN: Byte = 0x03
    private val T_OPEN_OK: Byte = 0x04
    private val T_DATA: Byte = 0x05
    private val T_FIN: Byte = 0x06
    private val T_PING: Byte = 0x07
    private val T_PONG: Byte = 0x08
    private val HDR = 11 // 1+4+4+2

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gw = intent.getStringExtra("gateway") ?: return START_NOT_STICKY
                val token = intent.getStringExtra("token") ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotif("Подключение..."))
                connect(gw, token)
            }
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun connect(gatewayUrl: String, token: String) {
        status.postValue("🔄 Подключение...")
        val client = OkHttpClient()
        val req = Request.Builder().url(gatewayUrl).build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                sendFrame(ws, T_HELLO, 0, token.toByteArray())
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleFrame(bytes.toByteArray())
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                status.postValue("❌ Ошибка: ${t.message}")
                connected = false
                scheduleReconnect(gatewayUrl, token)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                status.postValue("⚪ Отключено")
            }
        })
    }

    private fun handleFrame(data: ByteArray) {
        if (data.size < HDR) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val type = buf.get()
        val sid = buf.int
        buf.int // seqId
        val len = buf.short.toInt() and 0xFFFF
        val payload = if (len > 0) ByteArray(len).also { buf.get(it) } else ByteArray(0)

        when (type) {
            T_HELLO_ACK -> {
                connected = true
                status.postValue("✅ Подключено")
                updateNotif("✅ Подключено")
                startSocks5()
            }
            T_OPEN_OK -> streams[sid]?.openOk?.complete(Unit)
            T_DATA -> {
                streams[sid]?.queue?.offer(payload)
            }
            T_FIN -> {
                streams[sid]?.closed = true
                streams.remove(sid)
            }
            T_PONG -> Log.d(TAG, "pong")
        }
    }

    private fun sendFrame(ws: WebSocket, type: Byte, sid: Int, payload: ByteArray = ByteArray(0)) {
        val buf = ByteBuffer.allocate(HDR + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(type)
        buf.putInt(sid)
        buf.putInt(msgSeq.getAndIncrement())
        buf.putShort(payload.size.toShort())
        if (payload.isNotEmpty()) buf.put(payload)
        ws.send(buf.array().toByteString())
    }

    private fun startSocks5() {
        scope.launch {
            val server = ServerSocket(1080, 50, InetAddress.getByName("127.0.0.1"))
            while (isActive && connected) {
                try {
                    val client = server.accept()
                    launch { handleSocks5(client) }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "accept: ${e.message}")
                }
            }
            server.close()
        }
    }

    private suspend fun handleSocks5(sock: Socket) = withContext(Dispatchers.IO) {
        try {
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()
            // Auth
            inp.read(); val n = inp.read(); repeat(n) { inp.read() }
            out.write(byteArrayOf(5, 0))
            // Request
            val hdr = ByteArray(4).also { inp.read(it) }
            if (hdr[1] != 1.toByte()) { sock.close(); return@withContext }
            val host = when (hdr[3].toInt()) {
                1 -> { val a = ByteArray(4).also { inp.read(it) }; InetAddress.getByAddress(a).hostAddress!! }
                3 -> { val l = inp.read(); ByteArray(l).also { inp.read(it) }.toString(Charsets.UTF_8) }
                else -> { sock.close(); return@withContext }
            }
            val port = (inp.read() shl 8) or inp.read()

            // Open tunnel stream
            val ws = webSocket ?: run { sock.close(); return@withContext }
            val sid = streamSeq.getAndAdd(2)
            val ctx = StreamCtx()
            streams[sid] = ctx
            sendFrame(ws, T_OPEN, sid, "$host:$port".toByteArray())

            // Wait open ack
            try { withTimeout(8000) { ctx.openOk.await() } }
            catch (e: Exception) {
                out.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0))
                sock.close(); return@withContext
            }
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            // Relay
            val readJob = launch {
                val buf = ByteArray(8192)
                try {
                    while (isActive && !ctx.closed) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        sendFrame(ws, T_DATA, sid, buf.copyOf(n))
                    }
                } finally { sendFrame(ws, T_FIN, sid) }
            }
            val writeJob = launch {
                try {
                    while (isActive && !ctx.closed) {
                        val d = ctx.queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        out.write(d); out.flush()
                    }
                } catch (_: Exception) {}
            }
            readJob.join()
            writeJob.cancelAndJoin()
        } catch (e: Exception) {
            Log.e(TAG, "socks5: ${e.message}")
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun scheduleReconnect(gw: String, token: String) {
        scope.launch { delay(5000); connect(gw, token) }
    }

    fun stop() {
        scope.cancel()
        webSocket?.close(1000, "stop")
        vpnInterface?.close()
        vpnInterface = null
        connected = false
        status.postValue("⚪ Отключено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stop(); super.onDestroy() }

    private fun buildNotif(text: String): Notification {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("YAC Bridge VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    private fun updateNotif(text: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotif(text))
    }
}

class StreamCtx {
    val queue = LinkedBlockingQueue<ByteArray>()
    val openOk = CompletableDeferred<Unit>()
    var closed = false
}
