package dev.blooapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Веб-приложение — единица, вокруг которой построено всё остальное.
 *
 * Ключевые инварианты (PLAN.md §1.3), нарушение которых необратимо для
 * пользователя:
 *
 * 1. [originKey] — иммутабелен. Это идентичность приложения: от него зависят
 *    имя профиля изоляции, ключ ярлыка и задача в «Недавних». Смена стартовой
 *    страницы НЕ меняет originKey (иначе теряется сессия — дефект NA#212).
 * 2. [baseUrl] фиксируется по тому, что ввёл пользователь, и НЕ переписывается
 *    по редиректам (дефект NA#172: SSO на поддомене подменял базовый адрес).
 * 3. [isolationMode] выбирается один раз при создании и больше не меняется:
 *    сессия физически лежит либо в профиле WebView, либо в suffix-каталоге,
 *    и это разные места.
 *
 * Все настройки имеют значения по умолчанию в одном месте — здесь. Дублировать
 * дефолты в UI запрещено: иначе через полгода «выключенный JS» будет означать
 * разное на разных экранах.
 */
@Entity(tableName = "web_apps")
data class WebApp(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Схема + host[:port]. Иммутабелен. Уникален среди приложений. */
    @ColumnInfo(name = "origin_key")
    val originKey: String,

    /** Полный нормализованный адрес, с которого начинается загрузка. */
    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    /** Отображаемое имя. По умолчанию — host. */
    val title: String,

    /** Порядок в списке. */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** Локальный/LAN-адрес: влияет на дефолты mixed content и SSL. */
    @ColumnInfo(name = "is_local")
    val isLocal: Boolean = false,

    // --- изоляция (этап 6, но поле нужно с первого дня) ---------------------

    @ColumnInfo(name = "isolation_mode")
    val isolationMode: IsolationMode = IsolationMode.SHARED,

    /** Имя профиля WebView. Задаётся при создании, дальше иммутабельно. */
    @ColumnInfo(name = "profile_name")
    val profileName: String? = null,

    // --- настройки движка ---------------------------------------------------

    @ColumnInfo(name = "js_enabled")
    val jsEnabled: Boolean = true,

    @ColumnInfo(name = "dom_storage_enabled")
    val domStorageEnabled: Boolean = true,

    @ColumnInfo(name = "cookies_enabled")
    val cookiesEnabled: Boolean = true,

    /**
     * Third-party cookies. По умолчанию РАЗРЕШЕНЫ — сознательное решение.
     * Платформа с targetSdk >= 21 их запрещает, и это ломает SSO: в трекере
     * Native Alpha это дефект #100 «Doesn't save cookies», который лечился
     * именно включением этой настройки. Приватность даём отдельной опцией,
     * а не сломанным входом по умолчанию.
     */
    @ColumnInfo(name = "third_party_cookies")
    val thirdPartyCookies: Boolean = true,

    /**
     * `mediaPlaybackRequiresUserGesture`. Дефолт платформы true, оставляем:
     * автоплей нужен на своих сайтах (дефект #79 «нет звука») и мешает на
     * чужих (дефект #215 «отключите автоплей»). Поэтому — настройка, не глобаль.
     */
    @ColumnInfo(name = "require_gesture_for_media")
    val requireGestureForMedia: Boolean = true,

    /**
     * Останавливать JS-таймеры при сворачивании.
     * По умолчанию false: `pauseTimers()` глобальный для процесса, и его
     * безусловный вызов в onPause — причина отзыва «видео останавливается
     * при сворачивании».
     */
    @ColumnInfo(name = "pause_timers_in_background")
    val pauseTimersInBackground: Boolean = false,

    /** Поддержка popup-окон. Нужна для соцлогинов (window.open / target=_blank). */
    @ColumnInfo(name = "allow_popups")
    val allowPopups: Boolean = true,

    @ColumnInfo(name = "user_agent_mode")
    val userAgentMode: UserAgentMode = UserAgentMode.MOBILE,

    @ColumnInfo(name = "custom_user_agent")
    val customUserAgent: String? = null,

    @ColumnInfo(name = "text_zoom_percent")
    val textZoomPercent: Int = 100,

    /** Алгоритмическое затемнение страницы (независимо от темы приложения). */
    @ColumnInfo(name = "force_dark")
    val forceDark: Boolean = false,

    /**
     * Режим смешанного контента. На локальных адресах по умолчанию
     * COMPATIBILITY: страницы домашних серверов часто тянут http-ресурсы,
     * а платформенный дефолт NEVER_ALLOW их молча рубит.
     */
    @ColumnInfo(name = "mixed_content_mode")
    val mixedContentMode: MixedContentPolicy = MixedContentPolicy.DEFAULT,

    /** Не давать экрану гаснуть, пока окно открыто. */
    @ColumnInfo(name = "keep_screen_on")
    val keepScreenOn: Boolean = false,

    /** Открывать ссылки на чужие домены во внешнем браузере. */
    @ColumnInfo(name = "external_links_outside")
    val externalLinksOutside: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
)

enum class IsolationMode {
    /** Общая сессия со всеми (как в бесплатных версиях аналогов). */
    SHARED,

    /** Отдельный Profile WebView (нужен MULTI_PROFILE + multiprocess). */
    PROFILE,

    /** Отдельный процесс + setDataDirectorySuffix (запасной путь, API 28+). */
    PROCESS,
}

enum class UserAgentMode { MOBILE, DESKTOP, CUSTOM }

enum class MixedContentPolicy {
    /** Решает приложение: локальные адреса — COMPATIBILITY, прочие — NEVER. */
    DEFAULT,
    NEVER_ALLOW,
    COMPATIBILITY,
    ALWAYS_ALLOW,
}
