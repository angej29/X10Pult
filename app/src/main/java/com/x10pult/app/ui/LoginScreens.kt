package com.x10pult.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.x10pult.app.AppViewModel
import com.x10pult.app.BuildConfig
import com.x10pult.app.mi.MiAuth

@Composable
fun LoginScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("X10 Пульт", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Керування пилососом Xiaomi Robot Vacuum X10+ через хмару Xiaomi — без застосунку Xiaomi Home.",
            style = MaterialTheme.typography.bodyLarge,
        )
        vm.error?.let { ErrorCard(it) }
        if (vm.busy) BusyRow(vm.progress)
        Button(
            onClick = vm::startWebLogin,
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Увійти в акаунт Xiaomi") }
        Text(
            "Відкриється сторінка входу Xiaomi. Увійдіть поштою або номером телефону й паролем Xiaomi: " +
                "вхід через Google у вбудованому вікні Google може не пропустити. Код із SMS чи пошти " +
                "вводьте на тій самій сторінці.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Пароль вводиться лише на сторінці Xiaomi. Застосунок зберігає на цьому пристрої тільки ключі доступу до хмари.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Версія ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Сторінка входу Xiaomi. Коли вона переходить на сервер токенів (sts), вхід завершено — беремо куки. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebScreen(vm: AppViewModel) {
    val holder = remember { WebHolder() }
    val finish: () -> Unit = remember {
        {
            if (!holder.finished) {
                holder.finished = true
                vm.onWebLoginFinished(readXiaomiCookies())
            }
        }
    }

    BackHandler {
        val web = holder.web
        if (web != null && web.canGoBack()) web.goBack() else vm.cancelWebLogin()
    }
    DisposableEffect(Unit) {
        onDispose { holder.web?.destroy() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::cancelWebLogin) { Text("Скасувати") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = finish) { Text("Я вже увійшов") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.url.host == MiAuth.STS_HOST) {
                                finish()
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            if (url != null && Uri.parse(url).host == MiAuth.STS_HOST) {
                                view.stopLoading()
                                finish()
                            }
                        }
                    }
                    loadUrl(MiAuth.LOGIN_URL)
                    holder.web = this
                }
            },
        )
    }
}

private class WebHolder {
    var web: WebView? = null
    var finished = false
}

private fun readXiaomiCookies(): Map<String, String> {
    val manager = CookieManager.getInstance()
    manager.flush()
    val out = LinkedHashMap<String, String>()
    listOf(
        "https://account.xiaomi.com/",
        "https://account.xiaomi.com/pass/",
        "https://account.xiaomi.com/fe/service/",
    ).forEach { url ->
        manager.getCookie(url)?.split(';')?.forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) {
                val key = part.substring(0, i).trim()
                if (key !in out) out[key] = part.substring(i + 1).trim()
            }
        }
    }
    return out
}
