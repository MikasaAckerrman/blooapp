package dev.blooapp.data

import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.Severity
import dev.webapps.model.InstanceNaming
import dev.webapps.model.UrlNormalizer
import kotlinx.coroutines.flow.Flow

/**
 * Создание и изменение окон веб-приложений.
 *
 * Главное отличие от версии этапа 1: повторное добавление того же сайта — это
 * НЕ конфликт, а создание нового экземпляра с собственной сессией. Проверка
 * «уже добавлен» убрана; вместо неё [add] сообщает, сколько окон этого сайта
 * уже есть, чтобы UI спросил пользователя, а не отказал ему.
 */
class WebAppRepository(
    private val dao: WebAppDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val diag: (DiagEvent) -> Unit = {},
) {
    fun observeAll(): Flow<List<WebApp>> = dao.observeAll()

    fun observeUngrouped(): Flow<List<WebApp>> = dao.observeUngrouped()

    fun observeInGroup(groupId: Long): Flow<List<WebApp>> = dao.observeInGroup(groupId)

    suspend fun getById(id: Long) = dao.getById(id)

    suspend fun instancesOf(originKey: String) = dao.getInstancesOf(originKey)

    sealed interface AddResult {
        /**
         * Экземпляр создан.
         * @param instanceNumber какой это по счёту экземпляр этого сайта
         */
        data class Added(val app: WebApp, val instanceNumber: Int) : AddResult

        /**
         * Адрес пригоден, но окна этого сайта уже есть. Это НЕ отказ:
         * UI должен спросить «создать ещё одно окно с отдельной сессией?»
         * и при согласии вызвать [addInstance].
         */
        data class NeedsConfirmation(
            val originKey: String,
            val normalizedUrl: String,
            val existing: List<WebApp>,
        ) : AddResult

        data class Rejected(val reason: UrlNormalizer.Reason) : AddResult
    }

    /**
     * Разобрать введённый адрес и решить, что делать.
     *
     * @param force true — не спрашивать, сразу создать ещё один экземпляр
     */
    suspend fun add(
        rawUrl: String,
        titleOverride: String? = null,
        isolation: IsolationMode = IsolationMode.PROFILE,
        groupId: Long? = null,
        force: Boolean = false,
    ): AddResult {
        val parsed = UrlNormalizer.normalize(rawUrl)
        if (parsed is UrlNormalizer.Result.Invalid) {
            diag(
                DiagEvent(
                    now(), Severity.WARN, Code.URL_REJECTED_BY_VALIDATOR, null,
                    "адрес отвергнут валидатором: ${parsed.reason}",
                    mapOf("raw" to rawUrl),
                )
            )
            return AddResult.Rejected(parsed.reason)
        }
        val url = (parsed as UrlNormalizer.Result.Valid).url
        val originKey = UrlNormalizer.originKey(url)

        val existing = dao.getInstancesOf(originKey)
        if (existing.isNotEmpty() && !force) {
            return AddResult.NeedsConfirmation(originKey, url.full, existing)
        }

        return addInstance(url, originKey, titleOverride, isolation, groupId)
    }

    /**
     * Создать экземпляр без вопросов. Вызывается либо для первого окна сайта,
     * либо после подтверждения пользователем.
     */
    suspend fun addInstance(
        url: UrlNormalizer.NormalizedUrl,
        originKey: String,
        titleOverride: String? = null,
        isolation: IsolationMode = IsolationMode.PROFILE,
        groupId: Long? = null,
    ): AddResult.Added {
        // Номер выдаёт монотонный счётчик, а не MAX по существующим записям:
        // иначе после удаления окна номер освобождался бы, и новое окно
        // унаследовало бы имя профиля удалённого вместе с его cookies.
        val index = dao.nextInstanceIndex(originKey)

        val profileName = if (isolation == IsolationMode.SHARED) {
            null
        } else {
            uniqueProfileName(url.host, index)
        }

        val ts = now()
        val app = WebApp(
            originKey = originKey,
            baseUrl = url.full,
            title = titleOverride?.takeIf { it.isNotBlank() } ?: url.host,
            instanceIndex = index,
            instanceLabel = null,
            groupId = groupId,
            sortOrder = dao.nextSortOrder(),
            isLocal = url.isLocal,
            isolationMode = isolation,
            profileName = profileName,
            createdAt = ts,
            updatedAt = ts,
        )
        val id = dao.insert(app)

        diag(
            DiagEvent(
                now(), Severity.TRACE, Code.PROFILE_ATTACHED, originKey,
                "создан экземпляр №$index, профиль ${profileName ?: "общий"}",
                mapOf("index" to index.toString(), "isolation" to isolation.name),
            )
        )
        return AddResult.Added(app.copy(id = id), index)
    }

    /**
     * Имя профиля с защитой от коллизии.
     *
     * Коллизия возможна в вырожденном случае: два разных хоста после очистки
     * дают одинаковую строку (например, обрезка очень длинных имён). Молча
     * отдать одно имя двум экземплярам нельзя — это склеит их сессии.
     */
    private suspend fun uniqueProfileName(host: String, index: Int): String {
        var candidate = InstanceNaming.profileName(host, index)
        var suffix = 0
        while (dao.getByProfile(candidate) != null) {
            suffix++
            candidate = InstanceNaming.profileName(host, index) + "_$suffix"
            if (suffix > 50) {
                // Практически недостижимо; лучше явная ошибка, чем склейка.
                diag(
                    DiagEvent(
                        now(), Severity.ERROR, Code.ISOLATION_MODE_MISMATCH, null,
                        "не удалось подобрать уникальное имя профиля для host=$host",
                    )
                )
                break
            }
        }
        return candidate
    }

    /**
     * Обновить настройки. Поля идентичности защищены: попытка их изменить —
     * баг вызывающего кода, о котором надо узнать сразу, а не когда
     * пользователь потеряет сессию.
     */
    suspend fun update(app: WebApp): Boolean {
        val existing = dao.getById(app.id) ?: return false
        if (existing.originKey != app.originKey) {
            reportImmutable(existing.originKey, "originKey", existing.originKey, app.originKey)
            return false
        }
        if (existing.profileName != app.profileName) {
            reportImmutable(
                existing.originKey, "profileName",
                existing.profileName.toString(), app.profileName.toString(),
            )
            return false
        }
        if (existing.isolationMode != app.isolationMode) {
            reportImmutable(
                existing.originKey, "isolationMode",
                existing.isolationMode.name, app.isolationMode.name,
            )
            return false
        }
        if (existing.instanceIndex != app.instanceIndex) {
            reportImmutable(
                existing.originKey, "instanceIndex",
                existing.instanceIndex.toString(), app.instanceIndex.toString(),
            )
            return false
        }
        dao.update(app.copy(updatedAt = now()))
        return true
    }

    /** Переименование экземпляра — это метка, а не идентичность. */
    suspend fun rename(id: Long, label: String?): Boolean {
        val existing = dao.getById(id) ?: return false
        dao.update(existing.copy(instanceLabel = label?.takeIf { it.isNotBlank() }, updatedAt = now()))
        return true
    }

    /**
     * Смена стартового адреса без потери сессии: меняется только baseUrl, и
     * только в пределах того же origin. Это ровно то, чего просили в NA#212.
     */
    suspend fun changeStartUrl(id: Long, rawUrl: String): Boolean {
        val existing = dao.getById(id) ?: return false
        val parsed = UrlNormalizer.normalize(rawUrl)
        if (parsed !is UrlNormalizer.Result.Valid) return false
        if (UrlNormalizer.originKey(parsed.url) != existing.originKey) return false
        dao.update(existing.copy(baseUrl = parsed.url.full, updatedAt = now()))
        return true
    }

    suspend fun moveToGroup(id: Long, groupId: Long?) = dao.moveToGroup(id, groupId, now())

    suspend fun reorder(orderedIds: List<Long>) {
        val ts = now()
        orderedIds.forEachIndexed { index, id -> dao.setSortOrder(id, index, ts) }
    }

    suspend fun delete(app: WebApp) = dao.delete(app)

    private fun reportImmutable(origin: String, field: String, from: String, to: String) {
        diag(
            DiagEvent(
                now(), Severity.ERROR,
                if (field == "originKey") Code.BASEURL_REWRITE_BLOCKED else Code.ISOLATION_MODE_MISMATCH,
                origin,
                "попытка изменить иммутабельное поле $field: '$from' -> '$to'",
            )
        )
    }
}
