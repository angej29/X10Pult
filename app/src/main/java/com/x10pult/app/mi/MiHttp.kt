package com.x10pult.app.mi

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.random.Random

/** Мережева помилка з назвою сервера — щоб на екрані було видно, який саме сервер недоступний. */
class MiNetException(val host: String, cause: IOException) :
    IOException("${cause.javaClass.simpleName}: ${cause.message}", cause)

/**
 * Сокети з меншим розміром TCP-сегмента (MSS 1300).
 * У деяких мережах (віртуалка BlueStacks, VPN, PPPoE) повнорозмірні пакети губляться, і з'єднання
 * з сервером входу Xiaomi зависає на рукостисканні TLS («SSL handshake timed out»). Менший MSS
 * змушує сервер надсилати дрібніші пакети, які проходять. На звичайних мережах різниці не видно.
 */
private class SmallSegmentSocketFactory(private val mss: Int) : SocketFactory() {
    private val base: SocketFactory = SocketFactory.getDefault()

    override fun createSocket(): Socket = tune(base.createSocket())

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }

    private fun tune(socket: Socket): Socket {
        runCatching {
            socket.tcpNoDelay = true // створює дескриптор сокета ще до з'єднання
            if (Build.VERSION.SDK_INT >= 30) {
                // Android 11+: fromSocket дає копію дескриптора — її можна спокійно закрити
                ParcelFileDescriptor.fromSocket(socket)?.use { pfd ->
                    Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_MAXSEG, mss)
                }
            } else {
                // Android 9–10: fromSocket обгортає сам дескриптор сокета, і будь-яке його закриття
                // чи від'єднання обриває з'єднання («Socket closed»). Беремо дескриптор напряму;
                // якщо не вийде — просто лишаємо звичайний розмір пакетів.
                val fd = Socket::class.java.getMethod("getFileDescriptor\$").invoke(socket) as? FileDescriptor
                if (fd != null && fd.valid()) {
                    Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_MAXSEG, mss)
                }
            }
        }
        return socket
    }

    private companion object {
        const val TCP_MAXSEG = 2 // Linux: IPPROTO_TCP / TCP_MAXSEG
    }
}

internal object MiHttp {
    /** Без автоматичних переходів: куки serviceToken приходять саме у відповіді-переході. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(SmallSegmentSocketFactory(1300))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val agentId: String = (1..13).map { "ABCDE"[Random.nextInt(5)] }.joinToString("")

    /** Хмара Xiaomi очікує клієнт у форматі мобільного застосунку. */
    val userAgent: String = "Android-7.1.1-1.0.0-ONEPLUS A3010-136-$agentId APP/xiaomi.smarthome APPV/62830"

    fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw MiNetException(request.url.host, e)
    }
}
