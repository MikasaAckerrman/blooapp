package dev.blooapp.data

import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.Severity
import dev.webapps.model.UrlNormalizer

/**
 * Создание и изменение веб-приложений.
 *
 * Вся валидация адреса идёт через [UrlNormalizer] — единственный источник
 * истины. Здесь же соблюдается инвариант «originKey и profileName иммутабельны».
 */
class WebAppRepository(
    private val dao: WebAppDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val diag: (DiagEvent) -> Unit = {},
) {
    fun observeAll() = dao.observeAll()

    suspend fun getById(id: Long) = dao.getById(id)

    sealed interface AddResult {
        data class Added(val app: WebApp) : AddResult
        data class Duplicate(val existing: WebApp) : AddResult
        data class Rejected(val reason: UrlNormalizer.Reason) : AddResult
    }

    /**
     * Добавить веб-приложение по введённому пользователем адресу.
     *
     * Возвращает [AddResult.Rejected] только если адрес действительно
     * непригоден. Важно: LAN-адреса без TLD, IP и порты — пригодны
     * (дефект NA#48, где `https://nas/path` отвергался).
     */
    suspend fun add(rawUrl: String, titleOverride: String? = null): AddResult {
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

        dao.getByOrigin(originKey)?.let { return AddResult.Duplicate(it) }

        val ts = now()
        val app = WebApp(
            originKey = originKey,
            baseUrl = url.full,
            title = titleOverride?.takeIf { it.isNotBlank() } ?: url.host,
            sortOrder = dao.nextSortOrder(),
            isLocal = url.isLocal,
            createdAt = ts,
            updatedAt = ts,
        )
        val id = dao.insert(app)
        return AddResult.Added(app.copy(id = id))
    }

    /**
     * Обновить настройки. Поля идентичности защищены: попытка их изменить —
     * это баг вызывающего кода, о котором надо узнать сразу, а не когда
     * пользователь потеряет сессию.
     */
    suspend fun update(app: WebApp): Boolean {
        val existing = dao.getById(app.id) ?: return false
        if (existing.originKey != app.originKey) {
            diag(
                DiagEvent(
                    now(), Severity.ERROR, Code.BASEURL_REWRITE_BLOCKED, existing.originKey,
                    "попытка изменить originKey: '${existing.originKey}' -> '${app.originKey}'",
                )
            )
            return false
        }
        if (existing.profileName != null && existing.profileName != app.profileName) {
            diag(
                DiagEvent(
                    now(), Severity.ERROR, Code.ISOLATION_MODE_MISMATCH, existing.originKey,
                    "попытка изменить profileName у существующего приложения",
                )
            )
            return false
        }
        if (existing.isolationMode != app.isolationMode) {
            diag(
                DiagEvent(
                    now(), Severity.ERROR, Code.ISOLATION_MODE_MISMATCH, existing.originKey,
                    "попытка изменить режим изоляции: " +
                        "${existing.isolationMode} -> ${app.isolationMode}",
                )
            )
            return false
        }
        dao.update(app.copy(updatedAt = now()))
        return true
    }

    /**
     * Смена стартового адреса без потери сессии: меняется только baseUrl,
     * и только в пределах того же origin. Это ровно то, чего просили в NA#212.
     */
    suspend fun changeStartUrl(id: Long, rawUrl: String): Boolean {
        val existing = dao.getById(id) ?: return false
        val parsed = UrlNormalizer.normalize(rawUrl)
        if (parsed !is UrlNormalizer.Result.Valid) return false
        if (UrlNormalizer.originKey(parsed.url) != existing.originKey) return false
        dao.update(existing.copy(baseUrl = parsed.url.full, updatedAt = now()))
        return true
    }

    suspend fun delete(app: WebApp) = dao.delete(app)
}
