package com.x10pult.app

import android.app.Application
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.x10pult.app.mi.MiAuth
import com.x10pult.app.mi.MiAuthExpired
import com.x10pult.app.mi.MiCloud
import com.x10pult.app.mi.MiDevice
import com.x10pult.app.mi.MiException
import com.x10pult.app.mi.MiNeedsCaptcha
import com.x10pult.app.mi.MiNeedsVerification
import com.x10pult.app.mi.MiNetException
import com.x10pult.app.mi.MiSession
import com.x10pult.app.robot.RobotState
import com.x10pult.app.robot.X10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Screen { Login, LoginWeb, Devices, Control }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)

    var screen by mutableStateOf(
        when {
            store.session == null -> Screen.Login
            store.device != null -> Screen.Control
            else -> Screen.Devices
        }
    )
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var devices by mutableStateOf<List<MiDevice>>(emptyList())
        private set
    var scanLog by mutableStateOf<List<String>>(emptyList())
        private set
    var device by mutableStateOf(store.device)
        private set
    var robot by mutableStateOf<RobotState?>(null)
        private set

    init {
        if (screen == Screen.Devices) scan()
    }

    /** Адреса сторінки у вбудованому вікні: вхід Xiaomi або підтвердження входу кодом. */
    var webUrl by mutableStateOf(MiAuth.LOGIN_URL)
        private set

    val lastUser: String get() = store.lastUser

    /** Посилання Xiaomi для підтвердження входу кодом (якщо Xiaomi його попросив). */
    var verifyUrl by mutableStateOf<String?>(null)
        private set

    /** Капча Xiaomi (картинка й кука ick), якщо Xiaomi її попросив. */
    var captcha by mutableStateOf<MiNeedsCaptcha?>(null)
        private set

    // ---------- Вхід ----------

    fun loginWithPassword(user: String, password: String, captchaCode: String? = null) {
        if (busy || user.isBlank() || password.isEmpty()) return
        store.lastUser = user
        val shownCaptcha = captcha
        viewModelScope.launch {
            busy = true
            error = null
            verifyUrl = null
            progress = "Входжу в акаунт Xiaomi…"
            val ok = try {
                store.session = MiAuth.loginPassword(
                    user, password, store.deviceId,
                    captchaCode = captchaCode.takeIf { shownCaptcha != null },
                    ick = shownCaptcha?.ick,
                )
                captcha = null
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: MiNeedsVerification) {
                captcha = null
                verifyUrl = e.url
                false
            } catch (e: MiNeedsCaptcha) {
                captcha = e
                if (shownCaptcha != null && !captchaCode.isNullOrBlank()) {
                    error = "Символи з картинки не підійшли — ось нова картинка, спробуйте ще раз."
                }
                false
            } catch (e: Exception) {
                error = describe(e)
                false
            } finally {
                busy = false
                progress = null
            }
            if (ok) {
                screen = Screen.Devices
                scan()
            }
        }
    }

    fun startWebLogin() {
        CookieManager.getInstance().removeAllCookies(null)
        webUrl = MiAuth.LOGIN_URL
        error = null
        screen = Screen.LoginWeb
    }

    /** Результат «Перевірити зв'язок із Xiaomi». */
    var checkLines by mutableStateOf<List<String>>(emptyList())
        private set

    fun checkConnection() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            progress = "Перевіряю зв'язок із серверами Xiaomi…"
            try {
                checkLines = MiAuth.checkServers(store.deviceId)
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun refreshCaptcha() {
        val current = captcha ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                captcha = MiAuth.refreshCaptcha(current.url, store.deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    /** Запасний варіант: сторінка підтвердження у вбудованому вікні. */
    fun openVerificationHere() {
        val url = verifyUrl ?: return
        CookieManager.getInstance().removeAllCookies(null)
        webUrl = url
        error = "Введіть код із SMS або пошти на сторінці Xiaomi."
        screen = Screen.LoginWeb
    }

    fun cancelWebLogin() {
        screen = Screen.Login
    }

    fun onWebLoginFinished(cookies: Map<String, String>) {
        screen = Screen.Login
        val passToken = cookies["passToken"].orEmpty()
        val userId = cookies["userId"].orEmpty()
        if (passToken.isEmpty() || userId.isEmpty()) {
            error = "Вхід не завершено: сторінка Xiaomi не передала ключ входу. Увійдіть ще раз і дочекайтеся, " +
                "поки сторінка сама закриється."
            return
        }
        viewModelScope.launch {
            busy = true
            progress = "Отримую доступ до сервісу Mijia…"
            val ok = try {
                store.session = MiAuth.session(userId, cookies["cUserId"].orEmpty(), passToken, store.deviceId)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
                false
            } finally {
                busy = false
                progress = null
            }
            if (ok) {
                screen = Screen.Devices
                scan()
            }
        }
    }

    fun logout() {
        store.clear()
        CookieManager.getInstance().removeAllCookies(null)
        devices = emptyList()
        device = null
        robot = null
        message = null
        error = null
        screen = Screen.Login
    }

    // ---------- Пристрої ----------

    fun scan() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            val found = ArrayList<MiDevice>()
            val log = ArrayList<String>()
            for (server in MiCloud.SERVERS) {
                progress = "Шукаю пристрої на сервері $server…"
                try {
                    val scan = withAuth { s -> MiCloud.scanServer(s, server) }
                    log += "$server: пристроїв ${scan.devices.size} (${scan.note})"
                    found += scan.devices
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log += "$server: ${describe(e)}"
                }
            }
            devices = found.distinctBy { it.did }.sortedByDescending { it.isVacuum }
            scanLog = log
            progress = null
            busy = false
            if (devices.isEmpty()) {
                error = "У хмарі Xiaomi для цього акаунта не знайдено жодного пристрою."
            } else if (device == null) {
                devices.singleOrNull { it.isVacuum }?.let { select(it) }
            }
        }
    }

    fun select(d: MiDevice) {
        store.device = d
        device = d
        robot = null
        message = null
        error = null
        screen = Screen.Control
    }

    fun backToDevices() {
        screen = Screen.Devices
        if (devices.isEmpty()) scan()
    }

    // ---------- Робот ----------

    /** Викликається з екрана пульта кожні кілька секунд. */
    suspend fun refresh() {
        val d = device ?: return
        try {
            val values = withAuth { s -> MiCloud.getProps(s, d.server, d.did, X10.STATE_PROPS) }
            robot = RobotState.from(values)
            if (error?.startsWith(REFRESH) == true) error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!busy) error = "$REFRESH ${describe(e)}"
        }
    }

    fun start() = command("Старт") { s, d -> done(MiCloud.action(s, d.server, d.did, X10.START)) }
    fun pause() = command("Пауза") { s, d -> done(MiCloud.action(s, d.server, d.did, X10.PAUSE)) }
    fun stop() = command("Стоп") { s, d -> done(MiCloud.action(s, d.server, d.did, X10.STOP)) }
    fun dock() = command("На базу") { s, d -> done(MiCloud.action(s, d.server, d.did, X10.CHARGE)) }
    fun locate() = command("Пошук робота") { s, d -> done(MiCloud.action(s, d.server, d.did, X10.LOCATE)) }

    fun setSuction(level: Int) = command("Потужність") { s, d ->
        done(MiCloud.setProp(s, d.server, d.did, X10.SUCTION, level))
    }

    fun setWater(level: Int) = command("Подача води") { s, d ->
        done(MiCloud.setProp(s, d.server, d.did, X10.WATER, level))
    }

    fun setMode(mode: Int) = command("Режим") { s, d -> applyMode(s, d, mode) }

    /** Сухе прибирання: режим «Пилосос», потім старт. */
    fun dryClean() = command("Сухе прибирання") { s, d ->
        val modeNote = applyMode(s, d, X10.MODE_SWEEP)
        val started = done(MiCloud.action(s, d.server, d.did, X10.START))
        "$started. $modeNote"
    }

    private suspend fun applyMode(s: MiSession, d: MiDevice, mode: Int): String {
        val before = readMode(s, d)
            ?: return "Режим прочитати не вдалося — робот працюватиме в поточному режимі."
        if (X10.modeOf(before) != mode) {
            val code = MiCloud.setProp(s, d.server, d.did, X10.CLEAN_MODE, X10.withMode(before, mode))
            if (code != 0 && code != 1) throw MiException("режим", code, "робот не прийняв режим (код $code)")
        }
        val after = readMode(s, d)
        return if (after != null && X10.modeOf(after) == mode) "Режим: ${X10.modeText(after)}."
        else "Робот лишив режим «${X10.modeText(after)}» (значення $after)."
    }

    private suspend fun readMode(s: MiSession, d: MiDevice): Long? =
        (MiCloud.getProps(s, d.server, d.did, listOf(X10.CLEAN_MODE))[X10.CLEAN_MODE] as? Number)?.toLong()

    private fun done(code: Int): String = when (code) {
        0, 1 -> "виконано"
        else -> throw MiException("команда", code, "робот відповів кодом $code")
    }

    private fun command(label: String, block: suspend (MiSession, MiDevice) -> String) {
        val d = device ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            message = "$label…"
            try {
                message = "$label: ${withAuth { s -> block(s, d) }}"
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = null
                error = "$label: ${describe(e)}"
            } finally {
                busy = false
            }
        }
    }

    // ---------- Спільне ----------

    /** Виконати запит; якщо ключ застарів — оновити його через passToken і повторити один раз. */
    private suspend fun <T> withAuth(block: suspend (MiSession) -> T): T {
        val current = store.session ?: throw MiException("вхід", 0, "потрібно увійти в акаунт Xiaomi")
        return try {
            block(current)
        } catch (e: MiAuthExpired) {
            val fresh = MiAuth.session(current.userId, current.cUserId, current.passToken, current.deviceId)
            store.session = fresh
            block(fresh)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is MiException -> when (e.stage) {
            "serviceLoginAuth2" -> if (e.code == 70016) "Невірний логін або пароль Xiaomi (код 70016)."
                else "Xiaomi не прийняв вхід (код ${e.code}): ${e.message}"
            "captcha" -> "Xiaomi просить ввести капчу. Скористайтеся кнопкою «Увійти через сторінку Xiaomi»."
            "serviceLogin" -> "Xiaomi не видав доступ до сервісу Mijia (код ${e.code}): ${e.message}"
            "sts" -> "Сервер токенів Mijia відмовив (HTTP ${e.code}): ${e.message}"
            else -> "${e.message} [${e.stage}, код ${e.code}]"
        }
        is MiAuthExpired -> "доступ застарів, увійдіть у акаунт ще раз"
        is MiNetException -> "Сервер ${e.host}: " + when (val cause = e.cause) {
            is javax.net.ssl.SSLHandshakeException ->
                if (cause.message.orEmpty().contains("Trust anchor")) "телефон не довіряє сертифікату сервера ($cause)"
                else "не вдалося встановити захищене з'єднання (${cause.message})"
            is java.net.SocketTimeoutException -> "не відповідає (${cause.message})"
            is java.net.UnknownHostException -> "немає з'єднання з інтернетом або DNS (${cause.message})"
            else -> "помилка мережі (${e.message})"
        }
        is java.net.UnknownHostException -> "немає з'єднання з сервером (${e.message})"
        is java.io.IOException -> "помилка мережі: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    private companion object {
        const val REFRESH = "Оновлення стану:"
    }
}
