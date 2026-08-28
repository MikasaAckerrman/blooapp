package dev.webapps.model

/**
 * Имена, которые должны быть устойчивы во времени: профиль WebView (хранилище
 * сессии) и ключ ярлыка на домашнем экране.
 *
 * Почему это отдельный файл с тестами, а не строковая конкатенация по месту:
 * оба имени иммутабельны после создания. Если генерация изменится или даст
 * коллизию, пользователь потеряет сессию или получит ярлык, ведущий в чужое
 * окно. Отменить это нельзя — данные уже на диске.
 *
 * Требования, которые здесь соблюдаются:
 *
 * 1. Имя профиля не зависит от URL. Иначе смена стартовой страницы «переезжала»
 *    бы в другой профиль (дефект NA#212 в аналоге: правка start URL стирала
 *    сессию).
 * 2. Имя профиля не переиспользуется после удаления экземпляра. WebView
 *    поступает так же со своими каталогами: счётчик номеров монотонный
 *    (`AssignNewProfileNumber` в AwBrowserContextStore), номера не
 *    переиспользуются.
 * 3. Имя не содержит символов, способных сломать путь на диске или сравнение
 *    строк.
 * 4. Ключ ярлыка уникален для каждого экземпляра. В аналоге ярлыки
 *    конфликтовали, и пользователь обходил это пробелом в названии (NA#82:
 *    два ярлыка одного сайта на складном телефоне).
 */
object InstanceNaming {

    /** Профиль по умолчанию в WebView; его нельзя удалить и нельзя занимать. */
    const val DEFAULT_PROFILE = "Default"

    private const val PREFIX = "wa"
    private const val MAX_HOST_PART = 24

    /**
     * Имя профиля WebView для экземпляра.
     *
     * Формат: `wa_<host>_<index>`, где host очищен от небезопасных символов.
     * Индекс включён, чтобы два окна одного сайта получили разные профили;
     * host — чтобы имя было читаемым при отладке и в списке
     * `ProfileStore.getAllProfileNames()`.
     *
     * @param host          host сайта (нормализованный, в нижнем регистре)
     * @param instanceIndex номер экземпляра, начиная с 1
     */
    fun profileName(host: String, instanceIndex: Int): String {
        require(instanceIndex >= 1) { "instanceIndex должен быть >= 1, получено $instanceIndex" }
        val safe = sanitize(host).take(MAX_HOST_PART).ifEmpty { "site" }
        return "${PREFIX}_${safe}_$instanceIndex"
    }

    /**
     * Ключ ярлыка на домашнем экране.
     *
     * Привязан к id записи в БД, а не к названию: название пользователь меняет,
     * и ярлык не должен от этого «переезжать». Именно смешение названия и
     * идентификатора приводило к конфликтам в аналоге.
     */
    fun shortcutId(webAppId: Long): String = "app_$webAppId"

    /** Ключ ярлыка группы. Отдельное пространство имён, чтобы не пересекаться. */
    fun groupShortcutId(groupId: Long): String = "group_$groupId"

    /**
     * Автоматическое имя экземпляра для показа пользователю.
     * Первое окно — просто host, дальше с номером: иначе на сетке из
     * одинаковых иконок невозможно понять, где какой аккаунт.
     */
    fun autoTitle(host: String, instanceIndex: Int): String =
        if (instanceIndex <= 1) host else "$host ($instanceIndex)"

    /**
     * Проверка, что имя профиля пригодно: непустое, без разделителей пути,
     * не совпадает с зарезервированным `Default`.
     */
    fun isValidProfileName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name == DEFAULT_PROFILE) return false
        if (name.any { it == '/' || it == '\\' || it == '\u0000' }) return false
        if (name == "." || name == "..") return false
        return true
    }

    private fun sanitize(s: String): String = buildString {
        for (c in s.lowercase()) {
            append(
                when {
                    c.isLetterOrDigit() && c.code < 128 -> c
                    c == '-' || c == '_' -> c
                    else -> '_'
                }
            )
        }
    }
}
