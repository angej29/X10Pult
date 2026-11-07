package com.x10pult.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var user by rememberSaveable { mutableStateOf(vm.lastUser) }
    var password by remember { mutableStateOf("") }
    val submit = { vm.loginWithPassword(user.trim(), password) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("X10 Пульт", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Керування пилососом Xiaomi Robot Vacuum X10+ — без застосунку Xiaomi Home.",
            style = MaterialTheme.typography.bodyLarge,
        )
        vm.error?.let { ErrorCard(it) }
        if (vm.busy) BusyRow(vm.progress)

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Пошта, телефон або Mi ID") },
            singleLine = true,
            enabled = !vm.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль Xiaomi") },
            singleLine = true,
            enabled = !vm.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { submit() },
            enabled = !vm.busy && user.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Увійти") }
        TextButton(onClick = vm::startWebLogin, enabled = !vm.busy) { Text("Увійти через сторінку Xiaomi") }

        Text(
            "Пароль надсилається лише на сервер входу Xiaomi (account.xiaomi.com) і ніде не зберігається. " +
                "Застосунок пам'ятає тільки ключі доступу до хмари на цьому пристрої.",
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

    val url = vm.webUrl
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::cancelWebLogin) { Text("Скасувати") }
            TextButton(onClick = { holder.web?.loadUrl(url) }) { Text("Оновити") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = finish) { Text("Я вже увійшов") }
        }
        vm.error?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    // Помилки сторінки — у журнал (logcat, тег X10Web), щоб було видно, чому вона порожня
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            Log.i("X10Web", "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            Log.w("X10Web", "load error ${error.errorCode} ${error.description} ${request.url}")
                        }

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
                    loadUrl(url)
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
