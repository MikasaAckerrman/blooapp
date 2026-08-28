package dev.webapps.diag

/**
 * Событие диагностики.
 *
 * Смысл модуля: приложение на WebView ломается не там, где падает, а там, где
 * нарушен контракт (не вызван callback, вызван запрещённый метод, схема не
 * обработана). Обычный логгер это не поймает — поэтому каждое событие несёт
 * КОД, по которому его можно автоматически сопоставить с пунктом чек-листа
 * PLAN.md §9, и, где известно, номер issue в чужом трекере, где этот дефект
 * уже проявился у других.
 */
data class DiagEvent(
    val tsMs: Long,
    val severity: Severity,
    val code: Code,
    /** Ключ веб-приложения (originKey) или null для глобальных событий. */
    val webApp: String?,
    /** Короткое человекочитаемое пояснение. */
    val message: String,
    /** Доп. поля: url, схема, версия WebView и т.п. */
    val fields: Map<String, String> = emptyMap(),
) {
    /** NDJSON — одна строка на событие. Формат стабилен, его читает коллектор. */
    fun toNdjson(): String = buildString {
        append('{')
        appendField("ts", tsMs.toString(), quote = false); append(',')
        appendField("sev", severity.name); append(',')
        appendField("code", code.name); append(',')
        appendField("issue", code.knownIssue ?: ""); append(',')
        appendField("app", webApp ?: ""); append(',')
        appendField("msg", message)
        for ((k, v) in fields) {
            append(',')
            appendField("f_$k", v)
        }
        append('}')
    }

    private fun StringBuilder.appendField(k: String, v: String, quote: Boolean = true) {
        append('"').append(escape(k)).append("\":")
        if (quote) append('"').append(escape(v)).append('"') else append(v)
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }
}

enum class Severity {
    /** Обычный ход событий, нужен для контекста при разборе. */
    TRACE,

    /** Подозрительно, но приложение работает. */
    WARN,

    /** Нарушен контракт: функциональность точно сломана для пользователя. */
    ERROR,

    /** Приложение или страница нежизнеспособны. */
    FATAL,
}

/**
 * Коды событий.
 *
 * Каждый код — либо нормальная точка трассировки, либо конкретный дефект из
 * каталога PLAN.md §4. `knownIssue` — номер issue в трекере
 * cylonid/NativeAlphaForAndroid (или иного проекта), где этот дефект
 * подтверждён у людей; служит доказательством, что проверка не выдумана.
 */
enum class Code(val knownIssue: String? = null, val checklist: String? = null) {
    // --- трассировка (Severity.TRACE) ---------------------------------------
    APP_OPENED,
    NAV_START,
    NAV_COMMITTED,
    NAV_FINISHED,
    SETTINGS_APPLIED,
    PROFILE_ATTACHED,

    // --- иконки и ярлыки: 31 issue ------------------------------------------
    ICON_SOURCE_USED(checklist = "иконки"),
    ICON_FALLBACK_MONOGRAM("NA#2", "«иконка не найдена» — не ошибка"),
    ICON_MANIFEST_MALFORMED("NA#28", "манифест — недоверенный вход"),
    SHORTCUT_PIN_REQUESTED(checklist = "ярлыки"),
    SHORTCUT_PIN_NO_CALLBACK("NA#82", "колбэк pin не приходит при отказе"),
    SHORTCUT_ID_COLLISION("NA#82", "shortcutId уникален, 2 ярлыка на сайт"),
    SHORTCUT_UPDATE_RATE_LIMITED(checklist = "updateShortcuts rate-limited"),
    TASK_DESCRIPTION_MISSING("NA#152", "иконка задачи в «Недавних»"),

    // --- файлы: 26 issues ---------------------------------------------------
    FILE_CHOOSER_OPENED(checklist = "onShowFileChooser"),
    FILE_CHOOSER_CALLBACK_LOST("NA#33", "callback вызывать всегда, в т.ч. null"),
    DOWNLOAD_ENQUEUED,
    DOWNLOAD_BLOB_INTERCEPTED("NA#74", "blob: только через inject-скрипт"),
    DOWNLOAD_BLOB_UNHANDLED("NA#74", "blob: только через inject-скрипт"),
    PDF_LINK_DELEGATED("NA#106", "PDF → DownloadManager или ACTION_VIEW"),
    EXPORT_UNVERIFIED("NA#227", "экспорт с проверкой результата"),

    // --- логины и cookies: 21 issue -----------------------------------------
    THIRD_PARTY_COOKIES_BLOCKED_ON_LOGIN("NA#100", "setAcceptThirdPartyCookies"),
    COOKIE_FLUSH_MISSING("NA#225", "CookieManager.flush() в onStop"),
    POPUP_SUPPRESSED_NO_MULTIWINDOW(checklist = "setSupportMultipleWindows"),
    OAUTH_HOST_DELEGATED_TO_CUSTOM_TAB(checklist = "OAuth → Custom Tab"),
    BASEURL_REWRITE_BLOCKED("NA#172", "baseUrl не переписывается по редиректам"),

    // --- медиа: 15 issues ---------------------------------------------------
    PAUSE_TIMERS_CALLED(checklist = "нет pauseTimers в безусловном onPause"),
    FULLSCREEN_CALLBACK_MISSING("NA#45", "onShowCustomView"),
    MEDIA_GESTURE_BLOCKED_PLAYBACK("NA#79", "mediaPlaybackRequiresUserGesture"),

    // --- верстка: 14 issues -------------------------------------------------
    INSETS_NOT_CONSUMED("NA#84", "insets systemBars|displayCutout|ime"),
    IME_OVERLAPS_INPUT("NA#194", "adjustResize + Type.ime()"),

    // --- навигация ----------------------------------------------------------
    URL_SCHEME_UNKNOWN("NA#177", "whitelist схем"),
    URL_LOADED_FROM_OVERRIDE("PWAW#external", "не loadUrl+true в override"),
    EXTERNAL_LINK_DELEGATED,

    // --- сеть и SSL: 26 issues ---------------------------------------------
    SSL_ERROR_SHOWN(checklist = "onReceivedSslError per-site"),
    SSL_ERROR_TRUSTED_BY_USER,
    MIXED_CONTENT_BLOCKED(checklist = "mixedContentMode"),
    URL_REJECTED_BY_VALIDATOR("NA#48", "валидатор пропускает LAN"),

    // --- стабильность: 12 issues -------------------------------------------
    RENDER_PROCESS_GONE(checklist = "onRenderProcessGone → true"),
    CONFIG_READ_FALLBACK("NA#180", "чтение конфигов с дефолтами"),
    CONFIG_CORRUPTED("NA#180", "повреждённая БД → импорт, не краш"),
    UNCAUGHT_EXCEPTION,

    // --- невозможное в WebView (PLAN §4.13) ---------------------------------
    WEB_FEATURE_UNAVAILABLE(checklist = "FAQ: чего не может WebView"),

    // --- изоляция -----------------------------------------------------------
    ISOLATION_UNAVAILABLE,
    ISOLATION_MODE_MISMATCH(checklist = "режим изоляции иммутабелен"),
    ;
}
