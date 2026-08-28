package dev.blooapp.diag

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.blooapp.BuildConfig
import dev.webapps.diag.Code
import dev.webapps.diag.ContractMonitor
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.DiagSink
import dev.webapps.diag.Severity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Подключение диагностики к Android.
 *
 * Разделение ответственности осознанное: логика обнаружения дефектов живёт в
 * `:core-diag` (чистый Kotlin, 28 тестов, гоняется без эмулятора), а здесь —
 * только транспорт и сбор окружения.
 *
 * Транспорт работает ТОЛЬКО в debug-сборке. В release события всё равно
 * собираются монитором (они нужны для будущего экрана диагностики), но никуда
 * не отправляются: приложение не должно ходить в сеть с телеметрией без
 * явного согласия пользователя.
 */
object DiagBootstrap {

    private const val TAG = "blooapp.diag"

    /** Отдельный поток: отправка не должна касаться UI и не должна плодить потоки. */
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diag-io").apply { isDaemon = true }
    }

    lateinit var sink: DiagSink
        private set

    lateinit var monitor: ContractMonitor
        private set

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true

        val transport = if (BuildConfig.DEBUG) HttpTransport(BuildConfig.DIAG_ENDPOINT) else NoopTransport
        sink = DiagSink(transport)
        monitor = ContractMonitor(clock = System::currentTimeMillis, sink = ::emit)

        // Периодически: выявить «висящие» контракты (потерянный
        // filePathCallback, незакрытый fullscreen) и отправить накопленное.
        io.scheduleWithFixedDelay(
            {
                runCatching {
                    monitor.sweep()
                    sink.flush()
                }.onFailure { Log.w(TAG, "sweep/flush failed", it) }
            },
            5, 15, TimeUnit.SECONDS,
        )

        emit(environmentEvent(context))
    }

    /** Единая точка: и в буфер на отправку, и в logcat для локальной отладки. */
    fun emit(event: DiagEvent) {
        if (!started) return
        sink.offer(event)
        if (BuildConfig.DEBUG) {
            val line = "${event.code} ${event.message}"
            when (event.severity) {
                Severity.FATAL, Severity.ERROR -> Log.e(TAG, line)
                Severity.WARN -> Log.w(TAG, line)
                Severity.TRACE -> Log.d(TAG, line)
            }
        }
    }

    /** Принудительно вытолкнуть буфер (например, из onStop окна). */
    fun flushAsync() {
        if (!started) return
        io.execute { runCatching { sink.flush() } }
    }

    /**
     * Снимок окружения. Именно эти три факта определяют, доступна ли изоляция
     * сессий, и именно их бессмысленно угадывать заранее:
     * MULTI_PROFILE требует и поддержки в WebView APK, и включённого
     * многопроцессного режима (проверено в исходниках androidx.webkit).
     */
    fun environmentEvent(context: Context): DiagEvent {
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val multiProfile = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }.getOrDefault(false)
        val multiProcess = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROCESS) &&
                WebViewCompat.isMultiProcessEnabled()
        }.getOrDefault(false)

        return DiagEvent(
            tsMs = System.currentTimeMillis(),
            severity = Severity.TRACE,
            code = Code.APP_OPENED,
            webApp = null,
            message = "окружение собрано",
            fields = mapOf(
                "app_version" to BuildConfig.VERSION_NAME,
                "sdk_int" to Build.VERSION.SDK_INT.toString(),
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "webview_pkg" to (pkg?.packageName ?: "нет"),
                "webview_ver" to (pkg?.versionName ?: "нет"),
                "multi_profile" to multiProfile.toString(),
                "multi_process" to multiProcess.toString(),
                "document_start_script" to runCatching {
                    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                }.getOrDefault(false).toString(),
                "algorithmic_darkening" to runCatching {
                    WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
                }.getOrDefault(false).toString(),
            ),
        )
    }

    private object NoopTransport : DiagSink.Transport {
        override fun send(ndjsonLines: List<String>) = true
    }

    /**
     * Отправка NDJSON на коллектор. Любая ошибка сети — это `false`, а не
     * исключение: диагностика не имеет права ломать приложение.
     */
    private class HttpTransport(private val endpoint: String) : DiagSink.Transport {
        override fun send(ndjsonLines: List<String>): Boolean {
            if (ndjsonLines.isEmpty()) return true
            return try {
                val body = ndjsonLines.joinToString("\n").toByteArray()
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1500
                    readTimeout = 1500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-ndjson")
                }
                conn.outputStream.use { it.write(body) }
                val ok = conn.responseCode in 200..299
                conn.disconnect()
                ok
            } catch (_: Throwable) {
                false
            }
        }
    }
}
