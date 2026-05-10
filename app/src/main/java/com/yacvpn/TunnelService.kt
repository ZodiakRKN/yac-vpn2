package com.yacvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TunnelService : VpnService() {

    companion object {
        private const val TAG = "TunnelService"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val CHANNEL_ID = "vpn_ch"
        const val NOTIF_ID = 1
        val status = MutableLiveData("⚪ Отключено")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val streams = ConcurrentHashMap<Int, StreamCtx>()
    private val streamSeq = AtomicInteger(1)
    private val msgSeq = AtomicInteger(0)

    @Volatile private var connected = false
    @Volatile private var running = false

    private val T_HELLO: Byte = 0x01
    private val T_HELLO_ACK: Byte = 0x02
    private val T_OPEN: Byte = 0x03
    private val T_OPEN_OK: Byte = 0x04
    private val T_DATA: Byte = 0x05
    private val T_FIN: Byte = 0x06
    private val HDR = 11

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gw = intent.getStringExtra("gateway")
                val token = intent.getStringExtra("token")
                if (gw == null || token == null) {
                    status.postValue("❌ gateway или token = null")
                    stopSelf()
                    return START_NOT_STICKY
                }
                createChannel()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, buildNotif("Подключение..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, buildNotif("Подключение..."))
                }
                running = true
                scope.launch { connect(gw, token) }
            }
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private suspend fun connect(gw: String, token: String) {
        status.postValue("🔄 Подключение...")
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder().url(gw).build()
            webSocket = client.newWebSocket(req, WsListener(gw, token))
        } catch (e: Exception) {
            Log.e(TAG, "connect: ${e.message}", e)
            status.postValue("❌ ${e.message}")
        }
    }

    inner class WsListener(
        private val gw: String,
        private val token: String
    ) : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "WS opened")
            sendFrame(ws, T_HELLO, 0, token.toByteArray())
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            handleFrame(bytes.toByteArray())
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message}")
            connected = false
            status.postValue("❌ ${t.message}")
            if (running) {
                scope.launch {
                    delay(5000)
                    connect(gw, token)
                }
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            connected = false
            status.postValue("⚪ Отключено")
        }
    }

    private fun handleFrame(data: ByteArray) {
        if (data.size < HDR) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val type = buf.get()
        val sid = buf.int
        buf.int
        val len = buf.short.toInt() and 0xFFFF
        val payload = if (len > 0) ByteArray(len).also { buf.get(it) } else ByteArray(0)

        when (type) {
            T_HELLO_ACK -> {
                connected = true
                status.postValue("✅ Подключено")
                updateNotif("✅ Подключено")
                scope.launch { startSocks5() }
            }
            T_OPEN_OK -> streams[sid]?.openOk?.complete(Unit)
            T_DATA -> streams[sid]?.queue?.offer(payload)
            T_FIN -> {
                streams[sid]?.closed = true
                streams.remove(sid)
            }
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

    private suspend fun startSocks5() {
        val server = try {
            ServerSocket(1080, 50, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            status.postValue("❌ Порт занят: ${e.message}")
            return
        }
        while (running && connected) {
            try {
                val client = withContext(Dispatchers.IO) { server.accept() }
                scope.launch { handleSocks5(client) }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "accept: ${e.message}")
            }
        }
        server.close()
    }

    private suspend fun handleSocks5(sock: Socket) = withContext(Dispatchers.IO) {
        try {
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()
            inp.read()
            val n = inp.read()
            repeat(n) { inp.read() }
            out.write(byteArrayOf(5, 0))

            val hdr = ByteArray(4).also { inp.read(it) }
            if (hdr[1] != 1.toByte()) {
                sock.close()
                return@withContext
            }

            val host = when (hdr[3].toInt()) {
                1 -> {
                    val a = ByteArray(4).also { inp.read(it) }
                    InetAddress.getByAddress(a).hostAddress!!
                }
                3 -> {
                    val l = inp.read()
                    ByteArray(l).also { inp.read(it) }.toString(Charsets.UTF_8)
                }
                else -> {
                    sock.close()
                    return@withContext
                }
            }
            val port = (inp.read() shl 8) or inp.read()

            val ws = webSocket ?: run { sock.close(); return@withContext }
            val sid = streamSeq.getAndAdd(2)
            val ctx = StreamCtx()
            streams[sid] = ctx
            sendFrame(ws, T_OPEN, sid, "$host:$port".toByteArray())

            try {
                withTimeout(8000) { ctx.openOk.await() }
            } catch (e: Exception) {
                out.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0))
                sock.close()
                return@withContext
            }

            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            val r = launch {
                val readBuf = ByteArray(8192)
                try {
                    while (isActive && !ctx.closed) {
                        val read = inp.read(readBuf)
                        if (read < 0) break
                        sendFrame(ws, T_DATA, sid, readBuf.copyOf(read))
                    }
                } finally {
                    sendFrame(ws, T_FIN, sid)
                }
            }

            val w = launch {
                try {
                    while (isActive && !ctx.closed) {
                        val d = ctx.queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        out.write(d)
                        out.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "write done: ${e.message}")
                }
            }

            r.join()
            w.cancelAndJoin()

        } catch (e: Exception) {
            Log.e(TAG, "socks5: ${e.message}")
        } finally {
            try { sock.close() } catch (e: Exception) { }
        }
    }

    fun stop() {
        running = false
        connected = false
        scope.cancel()
        webSocket?.close(1000, "stop")
        webSocket = null
        streams.clear()
        status.postValue("⚪ Отключено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotif(text: String): Notification {
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
