package dev.blooapp.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.blooapp.data.MixedContentPolicy
import dev.blooapp.data.UserAgentMode
import dev.blooapp.data.WebApp
import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.Severity

/**
 * Единственное место, где применяются настройки к WebView.
 *
 * Почему одно место: в аналоге настройки раскиданы по коду, и в результате
 * «выключенный JS» означает разное на разных экранах. Здесь же собраны все
 * платформенные дефолты, которые ломают сайты, — с объяснением, почему мы их
 * меняем (каталог дефектов PLAN.md §4).
 *
 * Вызывать ДО первой навигации.
 */
object WebViewConfigurator {

    /** Desktop-UA: Chrome на Linux. Стабильнее самодельных строк. */
    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/134.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, app: WebApp, diag: (DiagEvent) -> Unit) {
        val s = webView.settings

        s.javaScriptEnabled = app.jsEnabled

        // Дефолт платформы — false. Без него ломается почти всё современное.
        s.domStorageEnabled = app.domStorageEnabled

        // Дефолт платформы — false, и тогда window.open и target="_blank"
        // ЗАМЕНЯЮТ текущую страницу вместо открытия окна. Именно так теряются
        // окна соцлогинов. Требует реализованного onCreateWindow.
        s.setSupportMultipleWindows(app.allowPopups)

        // Остаётся false даже при разрешённых popup-окнах: открытие окна без
        // жеста пользователя — это реклама, а не логин.
        s.javaScriptCanOpenWindowsAutomatically = false

        // Дефолт true. Меняется настройкой, а не глобально: автоплей нужен на
        // своих сайтах и мешает на чужих.
        s.mediaPlaybackRequiresUserGesture = app.requireGestureForMedia

        s.mixedContentMode = resolveMixedContent(app)

        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.textZoom = app.textZoomPercent.coerceIn(50, 200)

        // Геолокация запрашивается страницей и подтверждается пользователем
        // через onGeolocationPermissionsShowPrompt. Здесь только разрешаем
        // саму возможность спросить.
        s.setGeolocationEnabled(false)

        s.userAgentString = when (app.userAgentMode) {
            UserAgentMode.MOBILE -> null // системный UA
            UserAgentMode.DESKTOP -> DESKTOP_UA
            UserAgentMode.CUSTOM -> app.customUserAgent
                ?.replace("\n", "")?.replace("\r", "")?.replace("\u0000", "")
                ?.takeIf { it.isNotBlank() }
        }

        applyDarkening(webView, app.forceDark)
        applyCookies(webView, app, diag)
    }

    /**
     * Смешанный контент. Платформенный дефолт для targetSdk >= 21 —
     * NEVER_ALLOW, и он молча рубит http-ресурсы на страницах домашних
     * серверов. Для локальных адресов по умолчанию мягче.
     */
    private fun resolveMixedContent(app: WebApp): Int = when (app.mixedContentMode) {
        MixedContentPolicy.NEVER_ALLOW -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
        MixedContentPolicy.COMPATIBILITY -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        MixedContentPolicy.ALWAYS_ALLOW -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        MixedContentPolicy.DEFAULT ->
            if (app.isLocal) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
    }

    private fun applyDarkening(webView: WebView, forceDark: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, forceDark)
            }
        }
    }

    private fun applyCookies(webView: WebView, app: WebApp, diag: (DiagEvent) -> Unit) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(app.cookiesEnabled)
        // Per-WebView настройка. Платформа с targetSdk >= 21 запрещает
        // third-party cookies по умолчанию, и это ломает SSO.
        cm.setAcceptThirdPartyCookies(webView, app.thirdPartyCookies)

        if (!app.thirdPartyCookies) {
            diag(
                DiagEvent(
                    System.currentTimeMillis(), Severity.TRACE,
                    Code.THIRD_PARTY_COOKIES_BLOCKED_ON_LOGIN, app.originKey,
                    "third-party cookies выключены — вход через соцсети может не работать",
                )
            )
        }
    }

    /**
     * Сброс cookies на диск. Вызывать при уходе окна в фон: без этого сессия
     * может не дожить до следующего запуска, если процесс убьют (NA#225,
     * «ProtonMail keeps getting logged out»). Метод блокирующий — только с
     * фонового потока.
     */
    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }
}
