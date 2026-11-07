package com.x10pult.app.mi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/** Xiaomi просить підтвердити вхід (код із SMS чи пошти) на своїй сторінці. */
class MiNeedsVerification(val url: String) : Exception("потрібне підтвердження входу")

/**
 * Вхід у хмару Xiaomi.
 * Основний шлях — логін і пароль (як у Xiaomi Home):
 *   1. serviceLogin?sid=xiaomiio&_json=true → підпис форми _sign;
 *   2. serviceLoginAuth2 (логін + MD5 пароля) → ssecurity, nonce, passToken і адреса сервера токенів;
 *   3. сервер токенів (sts) + clientSign → кука serviceToken.
 * Запасний шлях — сторінка account.xiaomi.com у вбудованому вікні: звідти беремо passToken,
 * далі serviceLogin з passToken → ті самі ssecurity / nonce → sts.
 * Будь-яку відмову сервера зберігаємо з повним текстом, щоб показати на екрані.
 */
object MiAuth {
    // Без _json: сервер одразу перенаправляє на сторінку входу /fe/service/login з callback на sts.
    // Варіант із &_json=false сервер Xiaomi не обслуговує (з'єднання висить до тайм-ауту).
    const val LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio"
    const val STS_HOST = "sts.api.io.mi.com"
    private const val SERVICE_LOGIN = "https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true"
    private const val AUTH2 = "https://account.xiaomi.com/pass/serviceLoginAuth2"
    private const val SDK = "sdkVersion=accountsdk-18.8.15"

    /** Вхід логіном (пошта, телефон або Mi ID) і паролем Xiaomi. */
    suspend fun loginPassword(user: String, password: String, deviceId: String): MiSession =
        withContext(Dispatchers.IO) {
            val baseCookies = "$SDK; deviceId=$deviceId"
            val first = Request.Builder().url(SERVICE_LOGIN)
                .header("User-Agent", MiHttp.userAgent)
                .header("Cookie", baseCookies)
                .build()
            val (signJson, extraCookies) = MiHttp.execute(first).use { r ->
                parse(r.body?.string().orEmpty()) to setCookies(r)
            }
            val sign = signJson?.optString("_sign").orEmpty()
            if (sign.isEmpty()) {
                throw MiException("serviceLogin", signJson?.optInt("code", -1) ?: -1, "сервер не видав підпис форми входу")
            }

            val form = FormBody.Builder()
                .add("sid", "xiaomiio")
                .add("hash", md5Upper(password))
                .add("callback", "https://$STS_HOST/sts")
                .add("qs", "%3Fsid%3Dxiaomiio%26_json%3Dtrue")
                .add("user", user)
                .add("_sign", sign)
                .add("_json", "true")
                .build()
            val second = Request.Builder().url(AUTH2).post(form)
                .header("User-Agent", MiHttp.userAgent)
                .header("Cookie", (listOf(baseCookies) + extraCookies).joinToString("; "))
                .build()
            val (status, body) = MiHttp.execute(second).use { it.code to (it.body?.string().orEmpty()) }
            val json = parse(body) ?: throw MiException("serviceLoginAuth2", status, "HTTP $status: ${body.take(300)}")

            val ssecurity = json.optString("ssecurity")
            val location = json.optString("location")
            if (ssecurity.length > 4 && location.isNotEmpty()) {
                val serviceToken = serviceToken(location, json.opt("nonce")?.toString().orEmpty(), ssecurity)
                return@withContext MiSession(
                    userId = json.optString("userId"),
                    cUserId = json.optString("cUserId"),
                    passToken = json.optString("passToken"),
                    ssecurity = ssecurity,
                    serviceToken = serviceToken,
                    deviceId = deviceId,
                )
            }
            val notification = json.optString("notificationUrl")
            if (notification.isNotEmpty()) {
                throw MiNeedsVerification(if (notification.startsWith("http")) notification else "https://account.xiaomi.com$notification")
            }
            if (json.optString("captchaUrl").isNotEmpty()) {
                throw MiException("captcha", json.optInt("code", -1), "Xiaomi просить ввести капчу")
            }
            throw MiException("serviceLoginAuth2", json.optInt("code", -1), describe(json, body))
        }

    /** Оновлення доступу за збереженим passToken (без пароля). */
    suspend fun session(userId: String, cUserId: String, passToken: String, deviceId: String): MiSession =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(SERVICE_LOGIN)
                .header("User-Agent", MiHttp.userAgent)
                .header("Cookie", "$SDK; deviceId=$deviceId; userId=$userId; passToken=$passToken")
                .build()
            val (status, body) = MiHttp.execute(request).use { it.code to (it.body?.string().orEmpty()) }
            val json = parse(body) ?: throw MiException("serviceLogin", status, "HTTP $status: ${body.take(300)}")

            val code = json.optInt("code", -1)
            val ssecurity = json.optString("ssecurity")
            val location = json.optString("location")
            if (code != 0 || ssecurity.isEmpty() || location.isEmpty()) {
                throw MiException("serviceLogin", code, describe(json, body))
            }
            MiSession(
                userId = json.optString("userId").ifEmpty { userId },
                cUserId = json.optString("cUserId").ifEmpty { cUserId },
                passToken = json.optString("passToken").ifEmpty { passToken },
                ssecurity = ssecurity,
                serviceToken = serviceToken(location, json.opt("nonce")?.toString().orEmpty(), ssecurity),
                deviceId = deviceId,
            )
        }

    /** Сервер токенів Mijia: саме тут Xiaomi Home показує «Помилка входу». */
    private fun serviceToken(location: String, nonce: String, ssecurity: String): String {
        val url = location + "&clientSign=" + URLEncoder.encode(MiCrypto.clientSign(nonce, ssecurity), "UTF-8")
        val request = Request.Builder().url(url).header("User-Agent", MiHttp.userAgent).build()
        return MiHttp.execute(request).use { response ->
            setCookies(response)
                .firstOrNull { it.startsWith("serviceToken=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotEmpty() }
                ?: throw MiException("sts", response.code, "HTTP ${response.code}: ${response.body?.string().orEmpty().take(300)}")
        }
    }

    private fun parse(body: String): JSONObject? = runCatching { JSONObject(body.substringAfter("&&&START&&&")) }.getOrNull()

    private fun setCookies(response: Response): List<String> =
        response.headers("Set-Cookie").map { it.substringBefore(';').trim() }.filter { it.contains('=') }

    private fun describe(json: JSONObject, body: String): String =
        json.optString("desc").ifEmpty { json.optString("description") }.ifEmpty { body.take(300) }

    private fun md5Upper(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
