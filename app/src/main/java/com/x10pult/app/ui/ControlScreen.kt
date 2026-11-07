package com.x10pult.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.x10pult.app.AppViewModel
import com.x10pult.app.robot.RobotState
import com.x10pult.app.robot.X10
import kotlinx.coroutines.delay

@Composable
fun ControlScreen(vm: AppViewModel) {
    val d = vm.device ?: return
    val r = vm.robot

    BackHandler { vm.backToDevices() }
    LaunchedEffect(d.did) {
        while (true) {
            vm.refresh()
            delay(5_000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${d.model} · сервер ${d.server}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = vm::backToDevices) { Text("Пристрої") }
        }

        StatusCard(r)

        if (vm.busy) BusyRow(vm.message) else vm.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        vm.error?.let { ErrorCard(it) }

        Button(
            onClick = vm::dryClean,
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Сухе прибирання (лише пилосос)") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::start, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Старт") }
            OutlinedButton(onClick = vm::pause, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Пауза") }
            OutlinedButton(onClick = vm::stop, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Стоп") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::dock, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("На базу") }
            OutlinedButton(onClick = vm::locate, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Знайти робота") }
        }

        ChoiceRow("Режим прибирання", X10.MODES, r?.modeRaw?.let { X10.modeOf(it) }, !vm.busy, vm::setMode)
        ChoiceRow("Потужність всмоктування", X10.SUCTIONS, r?.suction, !vm.busy, vm::setSuction)
        ChoiceRow("Подача води (для миття)", X10.WATERS, r?.water, !vm.busy, vm::setWater)

        Text(
            "Якщо в режимі «Пилосос» робот усе одно миє підлогу — зніміть мопи: без них він тільки пилососить.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(r: RobotState?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (r == null) {
                Text("Отримую стан робота…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(X10.statusText(r.status), style = MaterialTheme.typography.titleMedium)
            val charging = X10.chargingText(r.charging)
            Text("Батарея: ${r.battery?.let { "$it %" } ?: "?"}" + (charging?.let { " · $it" } ?: ""))
            if ((r.fault ?: 0) != 0) {
                Text("Помилка робота: код ${r.fault}", color = MaterialTheme.colorScheme.error)
            }
            Text("Режим: ${X10.modeText(r.modeRaw)}")
            if (r.cleanTime != null || r.cleanArea != null) {
                Text(
                    "Останнє прибирання: ${r.cleanTime ?: "?"} хв, ${r.cleanArea ?: "?"} м²",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
