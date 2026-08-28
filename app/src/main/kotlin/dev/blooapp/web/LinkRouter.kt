package dev.blooapp.web

import android.net.Uri

/**
 * Куда отправить ссылку: оставить в окне, отдать системе или открыть в
 * Custom Tab.
 *
 * Выделено в чистую функцию без Android-зависимостей (кроме разбора строки),
 * чтобы решение проверялось юнит-тестами. Именно здесь совершаются ошибки,
 * которые пользователь видит как «приложение открыло 200 вкладок в Chrome»
 * или «ссылка вообще не работает».
 */
object LinkRouter {

    /** Схемы, которые осмысленно отдавать системе. */
    val SYSTEM_SCHEMES = setOf(
        "tel", "sms", "smsto", "mailto", "geo", "market",
        "whatsapp", "tg", "bitcoin", "maps",
    )

    /**
     * Хосты, где вход в WebView невозможен принципиально.
     *
     * Google запретил OAuth в embedded WebView в 2016 году (официальный анонс
     * Google Developers Blog) и отдаёт `disallowed_useragent`. Подмена
     * User-Agent — гонка, которую не выиграть, и прямое нарушение правил.
     * Единственный работающий путь — системный браузер / Custom Tab.
     */
    val OAUTH_HOSTS = setOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "oauth2.googleapis.com",
        "signin.google.com",
        "myaccount.google.com",
    )

    sealed interface Decision {
        /** Грузим в текущем окне: вернуть false из shouldOverrideUrlLoading. */
        data object KeepInApp : Decision

        /** Отдать системному обработчику (tel:, mailto:, market:, …). */
        data class OpenExternally(val uri: String) : Decision

        /** Открыть в Custom Tab: OAuth и явно внешние ссылки. */
        data class OpenInCustomTab(val uri: String, val reason: Reason) : Decision

        /** Схема неизвестна — тихо проглотить, не показывая ошибку. */
        data class Ignore(val scheme: String) : Decision
    }

    enum class Reason { OAUTH, EXTERNAL_DOMAIN }

    /**
     * @param url            адрес из WebResourceRequest
     * @param appOriginHost  host веб-приложения (для сравнения «свой/чужой»)
     * @param externalOutside настройка «чужие домены открывать снаружи»
     * @param isRedirect     true для HTTP-редиректа: редиректы внутри цепочки
     *                       логина нельзя выкидывать наружу, иначе вход
     *                       разорвётся (дефект NA#172)
     */
    fun route(
        url: String,
        appOriginHost: String,
        externalOutside: Boolean,
        isRedirect: Boolean = false,
    ): Decision {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return Decision.KeepInApp
        val scheme = uri.scheme?.lowercase()
        // Пустая строка от Uri.parse значит «хоста нет» так же, как и null:
        // `https:///page` даёт host="" — без этой нормализации такая ссылка
        // уходила бы во внешний браузер как «чужой домен».
        val host = uri.host?.lowercase()?.removeSuffix(".")?.takeIf { it.isNotEmpty() }

        if (scheme == null) return Decision.KeepInApp

        if (scheme != "http" && scheme != "https") {
            return if (scheme in SYSTEM_SCHEMES || scheme == "intent") {
                Decision.OpenExternally(url)
            } else {
                // Неизвестная схема (fb-messenger://, myapp://…). WebView вернёт
                // ERR_UNKNOWN_URL_SCHEME и покажет страницу ошибки — поэтому
                // перехватываем и молчим (дефект NA#177).
                Decision.Ignore(scheme)
            }
        }

        if (host != null && host in OAUTH_HOSTS) {
            return Decision.OpenInCustomTab(url, Reason.OAUTH)
        }

        if (host == null) return Decision.KeepInApp
        if (isSameSite(host, appOriginHost)) return Decision.KeepInApp

        // Редирект чужого домена оставляем внутри: это почти всегда шаг
        // логина через SSO, и выброс наружу разорвёт цепочку.
        if (isRedirect) return Decision.KeepInApp

        return if (externalOutside) {
            Decision.OpenInCustomTab(url, Reason.EXTERNAL_DOMAIN)
        } else {
            Decision.KeepInApp
        }
    }

    /**
     * «Тот же сайт» — совпадение host или отношение поддомен/родитель.
     * Сознательно НЕ используем public suffix list: он большой, а нам нужно
     * лишь не выкидывать наружу `m.example.com` при базовом `example.com`.
     */
    fun isSameSite(host: String, appHost: String): Boolean {
        if (appHost.isEmpty()) return true
        if (host == appHost) return true
        if (host.endsWith(".$appHost")) return true
        if (appHost.endsWith(".$host")) return true
        return false
    }
}
