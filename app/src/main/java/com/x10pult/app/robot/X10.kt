package com.x10pult.app.robot

import com.x10pult.app.mi.Act
import com.x10pult.app.mi.Prop

/**
 * Xiaomi Robot Vacuum X10+ (dreame.vacuum.p2114a) — номери з відкритої специфікації MIoT
 * (miot-spec.org, urn:miot-spec-v2:device:vacuum:0000A006:dreame-p2114a).
 */
object X10 {
    val STATUS = Prop(2, 1)
    val FAULT = Prop(2, 2)
    val BATTERY = Prop(3, 1)
    val CHARGING = Prop(3, 2)
    val CLEAN_TIME = Prop(4, 2)
    val CLEAN_AREA = Prop(4, 3)
    val SUCTION = Prop(4, 4)
    val WATER = Prop(4, 5)

    /**
     * Режим прибирання Dreame (S4P23). Нижні біти — режим, старші байти — інші налаштування станції,
     * тому під час зміни режиму їх зберігаємо.
     */
    val CLEAN_MODE = Prop(4, 23)

    val START = Act(2, 1)
    val PAUSE = Act(2, 2)
    val STOP = Act(4, 2)
    val CHARGE = Act(3, 1)
    val LOCATE = Act(7, 1)

    val STATE_PROPS = listOf(STATUS, FAULT, BATTERY, CHARGING, CLEAN_TIME, CLEAN_AREA, SUCTION, WATER, CLEAN_MODE)

    const val MODE_SWEEP = 0
    const val MODE_MOP = 1
    const val MODE_SWEEP_MOP = 2

    val MODES = listOf(MODE_SWEEP to "Пилосос", MODE_MOP to "Миття", MODE_SWEEP_MOP to "Пилосос + миття")
    val SUCTIONS = listOf(0 to "Тиха", 1 to "Стандарт", 2 to "Середня", 3 to "Сильна")
    val WATERS = listOf(1 to "Мало", 2 to "Середньо", 3 to "Багато")

    fun modeOf(raw: Long): Int = (raw and 0x3L).toInt()
    fun withMode(raw: Long, mode: Int): Long = (raw and 0x3L.inv()) or mode.toLong()

    fun modeText(raw: Long?): String = when (raw) {
        null -> "невідомо"
        else -> when (modeOf(raw)) {
            MODE_SWEEP -> "пилосос"
            MODE_MOP -> "миття"
            MODE_SWEEP_MOP -> "пилосос + миття"
            else -> "миття після пилососа"
        }
    }

    fun statusText(status: Int?): String = when (status) {
        1 -> "Прибирає (пилосос)"
        2 -> "Очікує"
        3 -> "Пауза"
        4 -> "Помилка"
        5 -> "Повертається на базу"
        6 -> "Заряджається"
        7 -> "Миє підлогу"
        8 -> "Сушить мопи"
        9 -> "Миє мопи"
        10 -> "Їде мити мопи"
        11 -> "Будує карту"
        12 -> "Пилосос і миття"
        13 -> "Заряджено"
        null -> "Стан невідомий"
        else -> "Стан $status"
    }

    fun chargingText(state: Int?): String? = when (state) {
        1 -> "заряджається"
        5 -> "їде на зарядку"
        else -> null
    }
}

data class RobotState(
    val status: Int?,
    val fault: Int?,
    val battery: Int?,
    val charging: Int?,
    val cleanTime: Int?,
    val cleanArea: Int?,
    val suction: Int?,
    val water: Int?,
    val modeRaw: Long?,
) {
    companion object {
        fun from(values: Map<Prop, Any>): RobotState {
            fun long(p: Prop): Long? = (values[p] as? Number)?.toLong() ?: (values[p] as? String)?.toLongOrNull()
            fun int(p: Prop): Int? = long(p)?.toInt()
            return RobotState(
                status = int(X10.STATUS),
                fault = int(X10.FAULT),
                battery = int(X10.BATTERY),
                charging = int(X10.CHARGING),
                cleanTime = int(X10.CLEAN_TIME),
                cleanArea = int(X10.CLEAN_AREA),
                suction = int(X10.SUCTION),
                water = int(X10.WATER),
                modeRaw = long(X10.CLEAN_MODE),
            )
        }
    }
}
