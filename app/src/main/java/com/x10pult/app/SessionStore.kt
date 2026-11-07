package com.x10pult.app

import android.content.Context
import com.x10pult.app.mi.MiDevice
import com.x10pult.app.mi.MiSession
import kotlin.random.Random

/** Ключі доступу й вибраний робот — у приватних налаштуваннях застосунку (без резервного копіювання). */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("x10", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: (1..16)
            .map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }
            .joinToString("")
            .also { prefs.edit().putString("deviceId", it).apply() }

    var session: MiSession?
        get() {
            val userId = prefs.getString("userId", null) ?: return null
            val serviceToken = prefs.getString("serviceToken", null) ?: return null
            return MiSession(
                userId = userId,
                cUserId = prefs.getString("cUserId", "").orEmpty(),
                passToken = prefs.getString("passToken", "").orEmpty(),
                ssecurity = prefs.getString("ssecurity", "").orEmpty(),
                serviceToken = serviceToken,
                deviceId = deviceId,
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    listOf("userId", "cUserId", "passToken", "ssecurity", "serviceToken").forEach { remove(it) }
                } else {
                    putString("userId", value.userId)
                    putString("cUserId", value.cUserId)
                    putString("passToken", value.passToken)
                    putString("ssecurity", value.ssecurity)
                    putString("serviceToken", value.serviceToken)
                }
            }.apply()
        }

    var device: MiDevice?
        get() {
            val did = prefs.getString("did", null) ?: return null
            return MiDevice(
                did = did,
                name = prefs.getString("deviceName", "").orEmpty(),
                model = prefs.getString("deviceModel", "").orEmpty(),
                online = true,
                server = prefs.getString("deviceServer", "de").orEmpty(),
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    listOf("did", "deviceName", "deviceModel", "deviceServer").forEach { remove(it) }
                } else {
                    putString("did", value.did)
                    putString("deviceName", value.name)
                    putString("deviceModel", value.model)
                    putString("deviceServer", value.server)
                }
            }.apply()
        }

    fun clear() {
        session = null
        device = null
    }
}
