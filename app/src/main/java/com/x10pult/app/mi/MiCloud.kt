package com.x10pult.app.mi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** Виклики хмари Xiaomi: список пристроїв, властивості й дії MIoT. */
object MiCloud {
    /** Регіональні сервери хмари. Робот прив'язаний до одного з них — шукаємо на всіх. */
    val SERVERS = listOf("de", "ru", "sg", "us", "i2", "cn", "tw")

    private fun base(server: String) =
        if (server == "cn") "https://api.io.mi.com/app" else "https://$server.api.io.mi.com/app"

    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    suspend fun call(session: MiSession, server: String, path: String, data: String): JSONObject =
        withContext(Dispatchers.IO) {
            val nonce = MiCrypto.nonce()
            val signedNonce = MiCrypto.signedNonce(session.ssecurity, nonce)

            val plain = mutableListOf("data" to data)
            plain += "rc4_hash__" to MiCrypto.signature("POST", path, signedNonce, plain)
            val params = plain.map { (key, value) -> key to MiCrypto.encrypt(signedNonce, value) }.toMutableList()
            params += "signature" to MiCrypto.signature("POST", path, signedNonce, params)
            params += "ssecurity" to session.ssecurity
            params += "_nonce" to nonce

            val url = (base(server) + path).toHttpUrl().newBuilder().apply {
                params.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()

            val request = Request.Builder().url(url)
                .post(ByteArray(0).toRequestBody(FORM))
                .header("User-Agent", MiHttp.userAgent)
                .header("Accept-Encoding", "identity")
                .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
                .header("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4")
                .header("Cookie", cookies(session))
                .build()

            MiHttp.execute(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) throw MiAuthExpired()
                if (response.code != 200) throw MiException(path, response.code, "HTTP ${response.code}: ${body.take(300)}")
                val text = if (body.trimStart().startsWith("{")) body else {
                    runCatching { MiCrypto.decrypt(signedNonce, body) }
                        .getOrElse { throw MiException(path, -1, "не вдалося розшифрувати відповідь сервера") }
                }
                val json = runCatching { JSONObject(text) }
                    .getOrElse { throw MiException(path, -1, "незрозуміла відповідь: ${text.take(200)}") }
                val code = json.optInt("code", 0)
                if (code != 0) throw MiException(path, code, json.optString("message").ifEmpty { text.take(200) })
                json
            }
        }

    private fun cookies(session: MiSession): String {
        val zone = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val minutes = zone.getRawOffset() / 60_000
        val sign = if (minutes < 0) "-" else "+"
        val gmt = "GMT%s%02d:%02d".format(sign, kotlin.math.abs(minutes) / 60, kotlin.math.abs(minutes) % 60)
        val daylight = zone.inDaylightTime(java.util.Date(now))
        return "userId=${session.userId}; yetAnotherServiceToken=${session.serviceToken}; " +
            "serviceToken=${session.serviceToken}; locale=en_GB; timezone=$gmt; " +
            "is_daylight=${if (daylight) 1 else 0}; dst_offset=${if (daylight) zone.dstSavings else 0}; channel=MI_APP_STORE"
    }

    /** Пристрої одного сервера + коротка довідка, що саме повернула хмара (для журналу пошуку). */
    data class ScanResult(val devices: List<MiDevice>, val note: String)

    suspend fun scanServer(session: MiSession, server: String): ScanResult {
        // 1. Класичний список пристроїв акаунта (свої й ті, якими поділилися)
        val data = """{"getVirtualModel":true,"getHuamiDevices":1,"get_split_device":false,"support_smart_home":true}"""
        val response = call(session, server, "/home/device_list", data)
        val result = response.optJSONObject("result")
        val list = result?.optJSONArray("list")
        val direct = parseDevices(list, server)

        // 2. Будинки акаунта (власні й спільні) → пристрої кожного будинку
        var homeCount = -1
        var homeDevices = emptyList<MiDevice>()
        runCatching {
            val homesResult = call(
                session, server, "/v2/homeroom/gethome",
                """{"fg":true,"fetch_share":true,"fetch_share_dev":true,"limit":300,"app_ver":7}""",
            ).optJSONObject("result")
            val homes = ArrayList<JSONObject>()
            listOf("homelist", "share_home_list").forEach { key ->
                homesResult?.optJSONArray(key)?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(homes::add) }
            }
            homeCount = homes.size
            homeDevices = homes.flatMap { home -> devicesOfHome(session, server, home) }
        }

        val all = (direct + homeDevices).distinctBy { it.did }
        val note = buildString {
            append("список ${list?.length() ?: "—"}, будинків ${if (homeCount < 0) "—" else homeCount}, у будинках ${homeDevices.size}")
            if (all.isEmpty()) append(" · відповідь: ").append((result ?: response).toString().take(140))
        }
        return ScanResult(all, note)
    }

    private suspend fun devicesOfHome(session: MiSession, server: String, home: JSONObject): List<MiDevice> {
        val data = JSONObject()
            .put("home_owner", home.opt("uid") ?: session.userId)
            .put("home_id", home.opt("id"))
            .put("limit", 200)
            .put("get_split_device", true)
            .put("support_smart_home", true)
        val result = runCatching { call(session, server, "/v2/home/home_device_list", data.toString()) }.getOrNull()
        return parseDevices(result?.optJSONObject("result")?.optJSONArray("device_info"), server)
    }

    private fun parseDevices(list: JSONArray?, server: String): List<MiDevice> {
        if (list == null) return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val d = list.optJSONObject(i) ?: return@mapNotNull null
            val did = d.opt("did")?.toString().orEmpty()
            if (did.isEmpty()) return@mapNotNull null
            MiDevice(
                did = did,
                name = d.optString("name").ifEmpty { d.optString("model") },
                model = d.optString("model"),
                online = d.optBoolean("isOnline", true),
                server = server,
            )
        }
    }

    suspend fun getProps(session: MiSession, server: String, did: String, props: List<Prop>): Map<Prop, Any> {
        val params = JSONArray()
        props.forEach { p -> params.put(JSONObject().put("did", did).put("siid", p.siid).put("piid", p.piid)) }
        val data = JSONObject().put("datasource", 1).put("params", params).toString()
        val result = call(session, server, "/miotspec/prop/get", data).optJSONArray("result") ?: return emptyMap()
        val out = HashMap<Prop, Any>()
        for (i in 0 until result.length()) {
            val o = result.optJSONObject(i) ?: continue
            if (o.optInt("code", -1) != 0 || !o.has("value")) continue
            out[Prop(o.optInt("siid"), o.optInt("piid"))] = o.get("value")
        }
        return out
    }

    /** Повертає код робота: 0 або 1 — прийнято. */
    suspend fun setProp(session: MiSession, server: String, did: String, prop: Prop, value: Any): Int {
        val item = JSONObject().put("did", did).put("siid", prop.siid).put("piid", prop.piid).put("value", value)
        val data = JSONObject().put("params", JSONArray().put(item)).toString()
        val result = call(session, server, "/miotspec/prop/set", data).optJSONArray("result")
        return result?.optJSONObject(0)?.optInt("code", -1) ?: -1
    }

    suspend fun action(session: MiSession, server: String, did: String, act: Act): Int {
        val params = JSONObject().put("did", did).put("siid", act.siid).put("aiid", act.aiid).put("in", JSONArray())
        val data = JSONObject().put("params", params).toString()
        val result = call(session, server, "/miotspec/action", data).optJSONObject("result")
        return result?.optInt("code", -1) ?: -1
    }
}
