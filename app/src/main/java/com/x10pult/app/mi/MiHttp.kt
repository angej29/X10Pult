package com.x10pult.app.mi

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal object MiHttp {
    /** Без автоматичних переходів: куки serviceToken приходять саме у відповіді-переході. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val agentId: String = (1..13).map { "ABCDE"[Random.nextInt(5)] }.joinToString("")

    /** Хмара Xiaomi очікує клієнт у форматі мобільного застосунку. */
    val userAgent: String = "Android-7.1.1-1.0.0-ONEPLUS A3010-136-$agentId APP/xiaomi.smarthome APPV/62830"
}
