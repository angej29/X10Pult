package com.x10pult.app.mi

/** Доступ до хмари Xiaomi: зберігається лише на цьому пристрої. */
data class MiSession(
    val userId: String,
    val cUserId: String,
    val passToken: String,
    val ssecurity: String,
    val serviceToken: String,
    val deviceId: String,
)

data class MiDevice(
    val did: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val server: String,
) {
    val isVacuum: Boolean get() = model.contains(".vacuum.")
}

/** Властивість (siid/piid) і дія (siid/aiid) зі специфікації MIoT. */
data class Prop(val siid: Int, val piid: Int)
data class Act(val siid: Int, val aiid: Int)

/** Відмова сервера Xiaomi: на якому кроці, код і текст відповіді. */
class MiException(val stage: String, val code: Int, message: String) : Exception(message)

/** Ключ доступу застарів (HTTP 401) — треба оновити через passToken. */
class MiAuthExpired : Exception("HTTP 401")
