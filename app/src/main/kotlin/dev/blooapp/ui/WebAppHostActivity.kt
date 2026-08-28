package dev.blooapp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import dev.blooapp.BlooApp
import dev.blooapp.R
import dev.blooapp.data.WebApp
import dev.blooapp.diag.DiagBootstrap
import dev.blooapp.web.LinkRouter
import dev.blooapp.web.WebViewConfigurator
import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.Severity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * Окно веб-приложения.
 *
 * Здесь собраны ВСЕ обязательные колбэки WebView. Отсутствие любого из них —
 * это не «недоделка», а сломанная функциональность, которую пользователь
 * увидит как баг приложения (каталог дефектов PLAN.md §4):
 *
 *  - `onShowFileChooser`      — без него `<input type=file>` мёртв (NA#33);
 *  - `onShowCustomView`       — без него не работает fullscreen-видео (NA#45);
 *  - `onCreateWindow`         — без него popup-окна логина заменяют страницу;
 *  - `onPermissionRequest`    — микрофон и камера;
 *  - `onGeolocationPermissionsShowPrompt` — геолокация;
 *  - `onRenderProcessGone`    — без `true` систему убивает приложение;
 *  - `onReceivedSslError`     — самоподписанные сертификаты домашних серверов;
 *  - `DownloadListener`       — скачивания.
 *
 * Каждый из них дополнительно рапортует в [DiagBootstrap.monitor], поэтому
 * нарушение контракта видно в отчёте коллектора, а не только в отзывах Play.
 */
class WebAppHostActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WEB_APP_ID = "web_app_id"

        /**
         * Уникальный `data`-URI на веб-приложение. Задачи в «Недавних»
         * различаются по интенту, поэтому идентификатор обязан быть в data,
         * а не только в extras.
         */
        fun intentFor(context: android.content.Context, appId: Long): Intent =
            Intent(context, WebAppHostActivity::class.java).apply {
                data = Uri.parse("blooapp://webapp/$appId")
                putExtra(EXTRA_WEB_APP_ID, appId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
            }
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout

    private var webApp: WebApp? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** Незавершённый файловый выбор. */
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserId: Long = 0
    private val chooserIds = AtomicLong(1)

    private val monitor get() = DiagBootstrap.monitor
    private val originKey get() = webApp?.originKey

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // КРИТИЧНО: callback вызывается ВСЕГДА, в том числе с null при отмене.
        // Иначе поле <input type=file> на странице залипает навсегда (NA#33) —
        // без исключения, без лога, пользователь этого не исправит.
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else {
            null
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
        monitor.fileChooserCallbackInvoked(fileChooserId)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appId = intent.getLongExtra(EXTRA_WEB_APP_ID, -1L)
            .takeIf { it > 0 }
            ?: intent.data?.lastPathSegment?.toLongOrNull()
            ?: -1L

        // Edge-to-edge не опция: на targetSdk 36 отказаться от него нельзя
        // (windowOptOutEdgeToEdgeEnforcement отключён).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = FrameLayout(this)
        webView = WebView(this)
        fullscreenContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            fullscreenContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        applyInsets()
        setupBackHandling()

        if (appId <= 0) {
            finish()
            return
        }

        lifecycleScope.launch {
            val app = (application as BlooApp).repository.getById(appId)
            if (app == null) {
                Toast.makeText(this@WebAppHostActivity, R.string.webapp_missing, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            webApp = app
            title = app.title
            bind(app, savedInstanceState)
        }
    }

    /**
     * Отступы под системные панели, вырез камеры И клавиатуру.
     *
     * `windowSoftInputMode="adjustResize"` в манифесте сам по себе НЕ решает
     * проблему в edge-to-edge режиме — нужен `Type.ime()`. Без этого поле
     * ввода внизу страницы уходит под клавиатуру: в трекере аналога это
     * дефекты NA#194 и NA#167, оба открыты.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // Во время fullscreen-видео отступы замораживаем: иначе layout
            // дёргается при скрытии системных панелей.
            if (customView != null) {
                view.updatePadding(0, 0, 0, 0)
                return@setOnApplyWindowInsetsListener insets
            }
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
    }

    /**
     * Predictive back: на targetSdk 36 нельзя опираться на `onBackPressed()`.
     * Используем OnBackPressedCallback.
     */
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        customView != null -> hideCustomView()
                        webView.canGoBack() -> webView.goBack()
                        else -> finish()
                    }
                }
            },
        )
    }

    private fun bind(app: WebApp, savedInstanceState: Bundle?) {
        WebViewConfigurator.configure(webView, app, DiagBootstrap::emit)

        if (app.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        webView.webViewClient = HostWebViewClient(app)
        webView.webChromeClient = HostChromeClient(app)
        webView.setDownloadListener { url, userAgent, disposition, mime, size ->
            onDownloadRequested(app, url, userAgent, disposition, mime, size)
        }

        DiagBootstrap.emit(
            DiagEvent(
                System.currentTimeMillis(), Severity.TRACE, Code.SETTINGS_APPLIED,
                app.originKey, "настройки применены до навигации",
            )
        )

        // Восстановление состояния вместо ставки на широкий configChanges:
        // на adaptive layouts (targetSdk 36) пересоздание Activity нормально.
        val restored = savedInstanceState?.let { webView.restoreState(it) != null } ?: false
        if (!restored) {
            webView.loadUrl(app.baseUrl)
            DiagBootstrap.emit(
                DiagEvent(
                    System.currentTimeMillis(), Severity.TRACE, Code.NAV_START,
                    app.originKey, "первая навигация", mapOf("url" to app.baseUrl),
                )
            )
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (webApp?.pauseTimersInBackground == true) webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        // pauseTimers() ГЛОБАЛЬНЫЙ для всех WebView процесса. Безусловный
        // вызов здесь — причина отзыва «видео останавливается при
        // сворачивании». Только по явной настройке.
        if (webApp?.pauseTimersInBackground == true) webView.pauseTimers()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Сессия должна дожить до следующего запуска, даже если процесс убьют
        // (NA#225 «ProtonMail keeps getting logged out»). flush() блокирующий,
        // поэтому не на главном потоке.
        Thread { WebViewConfigurator.flushCookies() }.apply { isDaemon = true }.start()
        monitor.windowStopped(originKey, cookiesFlushed = true)
        DiagBootstrap.flushAsync()
    }

    override fun onDestroy() {
        monitor.windowClosing(originKey)
        // Незавершённый файловый выбор обязан получить null, иначе страница
        // остаётся с залипшим полем.
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        DiagBootstrap.flushAsync()
        super.onDestroy()
    }

    // ---------------------------------------------------------- fullscreen

    private fun showCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = cb
        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        fullscreenContainer.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        monitor.fullscreenEntered(originKey)
        ViewCompat.requestApplyInsets(root)
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        customView = null
        runCatching { customViewCallback?.onCustomViewHidden() }
        customViewCallback = null
        monitor.fullscreenExited()
        ViewCompat.requestApplyInsets(root)
    }

    // ------------------------------------------------------------ downloads

    private fun onDownloadRequested(
        app: WebApp,
        url: String,
        userAgent: String?,
        disposition: String?,
        mime: String?,
        size: Long,
    ) {
        // blob: через DownloadManager скачать нельзя — нужен inject-скрипт
        // (этап 3). Пока честно сообщаем и фиксируем в диагностике, а не
        // молча ничего не делаем, как это происходит в аналоге (NA#74).
        if (url.startsWith("blob:")) {
            DiagBootstrap.emit(
                DiagEvent(
                    System.currentTimeMillis(), Severity.WARN, Code.DOWNLOAD_BLOB_UNHANDLED,
                    app.originKey, "blob:-скачивание пока не поддержано", mapOf("url" to url),
                )
            )
            Toast.makeText(this, R.string.download_blob_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        DownloadDelegate.enqueue(this, url, userAgent, disposition, mime, size, app.originKey)
    }

    // ------------------------------------------------------------- clients

    private inner class HostWebViewClient(private val app: WebApp) : android.webkit.WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            val decision = LinkRouter.route(
                url = url,
                appOriginHost = Uri.parse(app.baseUrl).host?.lowercase().orEmpty(),
                externalOutside = app.externalLinksOutside,
                isRedirect = request.isRedirect,
            )
            // Контракт из документации: НИКОГДА не звать loadUrl(request.url)
            // и не возвращать true — это лишняя отмена и перезапуск загрузки,
            // источник навигационных циклов (в Android-PWA-Wrapper это давало
            // 200+ вкладок на один тап).
            val handled = decision !is LinkRouter.Decision.KeepInApp
            monitor.urlOverride(url, app.originKey, handledByApp = handled, calledLoadUrl = false)

            return when (decision) {
                is LinkRouter.Decision.KeepInApp -> false

                is LinkRouter.Decision.OpenExternally -> {
                    ExternalLauncher.openSystem(this@WebAppHostActivity, decision.uri, app.originKey)
                    true
                }

                is LinkRouter.Decision.OpenInCustomTab -> {
                    if (decision.reason == LinkRouter.Reason.OAUTH) {
                        DiagBootstrap.emit(
                            DiagEvent(
                                System.currentTimeMillis(), Severity.TRACE,
                                Code.OAUTH_HOST_DELEGATED_TO_CUSTOM_TAB, app.originKey,
                                "OAuth-хост отдан в Custom Tab: WebView для него запрещён Google",
                                mapOf("url" to decision.uri),
                            )
                        )
                    }
                    ExternalLauncher.openCustomTab(this@WebAppHostActivity, decision.uri, app.originKey)
                    true
                }

                is LinkRouter.Decision.Ignore -> {
                    // WebView вернул бы ERR_UNKNOWN_URL_SCHEME и показал
                    // страницу ошибки (NA#177). Молчим.
                    monitor.unknownScheme(decision.scheme, url, app.originKey)
                    true
                }
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            DiagBootstrap.emit(
                DiagEvent(
                    System.currentTimeMillis(), Severity.TRACE, Code.NAV_FINISHED,
                    app.originKey, "страница загружена", mapOf("url" to url),
                )
            )
        }

        /**
         * Смерть рендерера. Возврат `true` обязателен: иначе, по документации,
         * «application will crash if render process crashed, or be killed if
         * render process was killed by the system». Переданный WebView
         * использовать нельзя — убираем из иерархии и обнуляем ссылки.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                detail?.didCrash() ?: true
            } else {
                true
            }
            monitor.renderProcessGone(app.originKey, crashed = crashed, returnedTrue = true)

            runCatching {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            if (!isFinishing) {
                AlertDialog.Builder(this@WebAppHostActivity)
                    .setTitle(R.string.render_gone_title)
                    .setMessage(R.string.render_gone_message)
                    .setPositiveButton(R.string.action_reload) { _, _ -> recreate() }
                    .setNegativeButton(R.string.action_close) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
            return true
        }

        /**
         * Ошибка сертификата. Глобального «отключить SSL» не будет: это риск
         * отклонения в Play. Вместо этого — доверие конкретному сайту по
         * явному решению пользователя (просьба из NA#16, #37, #43, #131, #192).
         */
        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: android.net.http.SslError,
        ) {
            DiagBootstrap.emit(
                DiagEvent(
                    System.currentTimeMillis(), Severity.WARN, Code.SSL_ERROR_SHOWN,
                    app.originKey, "ошибка сертификата: ${error.primaryError}",
                    mapOf("url" to error.url),
                )
            )
            AlertDialog.Builder(this@WebAppHostActivity)
                .setTitle(R.string.ssl_error_title)
                .setMessage(getString(R.string.ssl_error_message, error.url))
                .setPositiveButton(R.string.action_proceed_once) { _, _ ->
                    DiagBootstrap.emit(
                        DiagEvent(
                            System.currentTimeMillis(), Severity.WARN,
                            Code.SSL_ERROR_TRUSTED_BY_USER, app.originKey,
                            "пользователь разрешил загрузку с плохим сертификатом",
                        )
                    )
                    handler.proceed()
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .show()
        }
    }

    private inner class HostChromeClient(private val app: WebApp) : WebChromeClient() {

        /**
         * Без этого колбэка любой `<input type=file>` на странице не работает
         * (NA#33 «File picker is broken»).
         */
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            // Предыдущий незавершённый выбор закрываем, иначе утечка callback.
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            fileChooserId = chooserIds.getAndIncrement()
            monitor.fileChooserOpened(fileChooserId, app.originKey)

            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (_: Throwable) {
                // Не смогли открыть выбор — обязаны вернуть результат, иначе
                // поле останется залипшим.
                fileCallback?.onReceiveValue(null)
                fileCallback = null
                monitor.fileChooserCallbackInvoked(fileChooserId)
                false
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        /**
         * Popup-окна. При `setSupportMultipleWindows(true)` без этого колбэка
         * окно просто не откроется — и вход через соцсети сломается.
         * Первый шаг: открываем в Custom Tab, что покрывает логины.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?,
        ): Boolean {
            if (!isUserGesture) {
                // Открытие окна без жеста — это реклама.
                DiagBootstrap.emit(
                    DiagEvent(
                        System.currentTimeMillis(), Severity.TRACE,
                        Code.POPUP_SUPPRESSED_NO_MULTIWINDOW, app.originKey,
                        "popup без пользовательского жеста подавлен",
                    )
                )
                return false
            }
            // URL появится в transport-WebView; проще и надёжнее отдать
            // последний запрошенный адрес системе через Custom Tab.
            val url = view.url
            if (url != null) {
                ExternalLauncher.openCustomTab(this@WebAppHostActivity, url, app.originKey)
            }
            return false
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
            // Микрофон и камера прокидываются платформой в приложение
            // (статус ASK). Полноценный UI — этап 4; пока честный отказ,
            // а не молчаливое зависание запроса.
            DiagBootstrap.emit(
                DiagEvent(
                    System.currentTimeMillis(), Severity.WARN, Code.WEB_FEATURE_UNAVAILABLE,
                    app.originKey, "запрос разрешений страницы отклонён (UI появится на этапе 4)",
                    mapOf("resources" to request.resources.joinToString(",")),
                )
            )
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: android.webkit.GeolocationPermissions.Callback,
        ) {
            // Обязательно вызвать callback, иначе страница ждёт вечно.
            callback.invoke(origin, false, false)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) this@WebAppHostActivity.title = title
        }
    }
}
