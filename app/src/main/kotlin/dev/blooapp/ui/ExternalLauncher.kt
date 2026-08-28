package dev.blooapp.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import dev.blooapp.R
import dev.blooapp.diag.DiagBootstrap
import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.Severity

/**
 * Открытие ссылок вне окна веб-приложения.
 *
 * Custom Tab — не украшение, а единственный способ провести вход через Google:
 * OAuth в embedded WebView запрещён с 2016 года и отдаёт
 * `disallowed_useragent`.
 *
 * Если Custom Tabs недоступны (нет подходящего браузера), падать нельзя —
 * откатываемся на обычный ACTION_VIEW, а если и это невозможно, сообщаем
 * пользователю, а не молчим.
 */
object ExternalLauncher {

    fun openCustomTab(context: Context, url: String, originKey: String?) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
                .launchUrl(context, uri)
            report(originKey, "открыто в Custom Tab", url)
        } catch (_: ActivityNotFoundException) {
            openSystem(context, url, originKey)
        }
    }

    fun openSystem(context: Context, url: String, originKey: String?) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            report(originKey, "передано системному обработчику", url)
        } catch (_: ActivityNotFoundException) {
            // Нет приложения для схемы. Сообщаем, но не роняем окно.
            Toast.makeText(context, R.string.no_handler_for_link, Toast.LENGTH_SHORT).show()
            DiagBootstrap.emit(
                DiagEvent(
                    System.currentTimeMillis(), Severity.WARN, Code.URL_SCHEME_UNKNOWN,
                    originKey, "нет обработчика для ссылки", mapOf("url" to url),
                )
            )
        }
    }

    private fun report(originKey: String?, msg: String, url: String) {
        DiagBootstrap.emit(
            DiagEvent(
                System.currentTimeMillis(), Severity.TRACE, Code.EXTERNAL_LINK_DELEGATED,
                originKey, msg, mapOf("url" to url),
            )
        )
    }
}
