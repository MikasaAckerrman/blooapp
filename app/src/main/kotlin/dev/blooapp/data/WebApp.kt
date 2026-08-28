package dev.blooapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Экземпляр веб-приложения.
 *
 * Ключевое понятие всего проекта, и его легко понять неправильно: одна запись
 * — это НЕ «сайт», а **окно сайта со своей сессией**. Три записи с одним
 * [originKey] = три независимых аккаунта одного сайта, работающих
 * одновременно. Именно это отличает приложение от браузера с закладками.
 *
 * Ошибка, которую здесь уже допустили и исправили: на этапе 1 [originKey] был
 * уникальным ключом, и добавить сайт второй раз было нельзя. Идентичность
 * пришлось расщепить на три разных понятия:
 *
 * | поле          | смысл                              | уникально |
 * |---------------|------------------------------------|-----------|
 * | [originKey]   | к какому сайту относится экземпляр | нет       |
 * | [id]          | сам экземпляр                      | да        |
 * | [profileName] | хранилище сессии (профиль WebView) | да        |
 *
 * Инварианты, нарушение которых необратимо для пользователя:
 *
 * 1. [originKey] иммутабелен. Смена стартовой страницы его НЕ меняет — иначе
 *    экземпляр «переезжает» и теряет сессию (дефект NA#212).
 * 2. [baseUrl] фиксируется по введённому пользователем адресу и НЕ
 *    переписывается по редиректам (дефект NA#172: SSO на поддомене подменял
 *    базовый адрес).
 * 3. [profileName] и [isolationMode] задаются при создании и дальше
 *    иммутабельны: сессия физически лежит либо в каталоге профиля WebView,
 *    либо в suffix-каталоге процесса, и это разные места на диске.
 *
 * Все настройки имеют значения по умолчанию в одном месте — здесь. Дублировать
 * дефолты в UI запрещено: иначе через полгода «выключенный JS» будет означать
 * разное на разных экранах.
 */
@Entity(
    tableName = "web_apps",
    indices = [
        // originKey НЕ уникален: по нему ищем «сколько уже окон этого сайта».
        Index(value = ["origin_key"]),
        // Профиль — хранилище сессии, двух экземпляров на один профиль быть
        // не может.
        Index(value = ["profile_name"], unique = true),
        Index(value = ["group_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Group::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            // Удаление группы не удаляет окна: они возвращаются на главный
            // экран. Потерять сессии из-за удаления папки — недопустимо.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class WebApp(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Схема + host[:port]. Иммутабелен. НЕ уникален — см. описание класса. */
    @ColumnInfo(name = "origin_key")
    val originKey: String,

    /** Полный нормализованный адрес, с которого начинается загрузка. */
    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    /** Отображаемое имя. По умолчанию — host, у второго окна — «host (2)». */
    val title: String,

    /**
     * Номер экземпляра среди окон того же сайта: 1, 2, 3…
     * Нужен для автоматического имени и для устойчивого ключа ярлыка.
     * Не переиспользуется после удаления — как и номера профилей в WebView.
     */
    @ColumnInfo(name = "instance_index")
    val instanceIndex: Int = 1,

    /**
     * Пользовательская метка экземпляра: «Рабочий», «Личный».
     * null — показываем номер.
     */
    @ColumnInfo(name = "instance_label")
    val instanceLabel: String? = null,

    /** Группа (папка). null — лежит на главном экране. */
    @ColumnInfo(name = "group_id")
    val groupId: Long? = null,

    /** Порядок в списке. */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** Локальный/LAN-адрес: влияет на дефолты mixed content и SSL. */
    @ColumnInfo(name = "is_local")
    val isLocal: Boolean = false,

    // --- изоляция -----------------------------------------------------------

    @ColumnInfo(name = "isolation_mode")
    val isolationMode: IsolationMode = IsolationMode.SHARED,

    /**
     * Имя профиля WebView = хранилище сессии. Задаётся при создании,
     * дальше иммутабельно. null только для режима [IsolationMode.SHARED].
     */
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

    /**
     * Открывать ссылки на чужие домены во внешнем браузере.
     *
     * По умолчанию ВЫКЛЮЧЕНО, и это важное решение. Если включить, любая
     * ссылка на другой домен уходит в Custom Tab — то есть в системный
     * браузер пользователя, где у него СВОИ cookies и свои логины. Тогда
     * изолированная сессия окна теряет смысл: пользователь оказывается
     * залогинен там, куда он в этом окне не входил.
     *
     * Именно так и проявился дефект на первом APK: вход на сайте уводил в
     * Firefox, где аккаунт уже был.
     */
    @ColumnInfo(name = "external_links_outside")
    val externalLinksOutside: Boolean = false,

    /**
     * Отдавать вход через Google в системный браузер.
     *
     * Google блокирует OAuth в embedded WebView (`disallowed_useragent`),
     * поэтому иначе вход просто не работает. Но цена — сессия окажется в
     * браузере, а не в профиле окна. Поэтому это осознанный выбор
     * пользователя, а не молчаливое поведение: по умолчанию выключено, и при
     * попытке входа окно объясняет ситуацию.
     */
    @ColumnInfo(name = "google_login_outside")
    val googleLoginOutside: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
) {
    /**
     * Имя, которое видит пользователь. Первое окно сайта показывается просто
     * как «example.com», второе и дальше получают различитель — иначе на
     * сетке из четырёх одинаковых иконок невозможно понять, где какой аккаунт.
     */
    val displayName: String
        get() = when {
            !instanceLabel.isNullOrBlank() -> instanceLabel
            instanceIndex <= 1 -> title
            else -> "$title ($instanceIndex)"
        }

    /** Есть ли у экземпляра собственное хранилище сессии. */
    val hasOwnSession: Boolean
        get() = isolationMode != IsolationMode.SHARED && profileName != null
}

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
