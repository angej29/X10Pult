package com.x10pult.app.mi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Вхід у хмару Xiaomi.
 * 1. Користувач входить на сторінці account.xiaomi.com (у вбудованому вікні) — звідти беремо passToken і userId.
 * 2. serviceLogin?sid=xiaomiio з passToken → ssecurity, nonce і адреса сервера токенів (location).
 * 3. location + clientSign → кука serviceToken. Саме на цьому кроці Xiaomi Home показує «Помилка входу»,
 *    тож тут зберігаємо повний текст відповіді сервера.
 */
object MiAuth {
    // Без _json: сервер одразу перенаправляє на сторінку входу /fe/service/login з callback на sts.
    // Варіант із &_json=false сервер Xiaomi не обслуговує (з'єднання висить до тайм-ауту).
    const val LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio"
    const val STS_HOST = "sts.api.io.mi.com"
    private const val SERVICE_LOGIN = "https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true"

    suspend fun session(userId: String, cUserId: String, passToken: String, deviceId: String): MiSession =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(SERVICE_LOGIN)
                .header("User-Agent", MiHttp.userAgent)
                .header("Cookie", "sdkVersion=accountsdk-18.8.15; deviceId=$deviceId; userId=$userId; passToken=$passToken")
                .build()
            val (status, body) = MiHttp.client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            val json = runCatching { JSONObject(body.substringAfter("&&&START&&&")) }.getOrNull()
                ?: throw MiException("serviceLogin", status, "HTTP $status: ${body.take(300)}")

            val code = json.optInt("code", -1)
            val ssecurity = json.optString("ssecurity")
            val location = json.optString("location")
            val nonce = json.opt("nonce")?.toString().orEmpty()
            if (code != 0 || ssecurity.isEmpty() || location.isEmpty()) {
                val text = json.optString("desc").ifEmpty { json.optString("description") }.ifEmpty { body.take(300) }
                throw MiException("serviceLogin", code, text)
            }

            val stsUrl = location + "&clientSign=" + URLEncoder.encode(MiCrypto.clientSign(nonce, ssecurity), "UTF-8")
            val sts = Request.Builder().url(stsUrl).header("User-Agent", MiHttp.userAgent).build()
            val serviceToken = MiHttp.client.newCall(sts).execute().use { response ->
                response.headers("Set-Cookie")
                    .map { it.substringBefore(';').trim() }
                    .firstOrNull { it.startsWith("serviceToken=") }
                    ?.substringAfter('=')
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw MiException("sts", response.code, "HTTP ${response.code}: ${response.body?.string().orEmpty().take(300)}")
            }

            MiSession(
                userId = json.optString("userId").ifEmpty { userId },
                cUserId = json.optString("cUserId").ifEmpty { cUserId },
                passToken = json.optString("passToken").ifEmpty { passToken },
                ssecurity = ssecurity,
                serviceToken = serviceToken,
                deviceId = deviceId,
            )
        }
}
