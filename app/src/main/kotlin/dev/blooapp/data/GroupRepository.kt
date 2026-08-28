package dev.blooapp.data

import kotlinx.coroutines.flow.Flow

/** Группы (папки). Отдельно от окон, чтобы удаление группы не трогало сессии. */
class GroupRepository(
    private val dao: GroupDao,
    private val webApps: WebAppDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<Group>> = dao.observeAll()

    suspend fun getById(id: Long) = dao.getById(id)

    suspend fun create(title: String, colorArgb: Int? = null): Group {
        val ts = now()
        val group = Group(
            title = title.trim().ifEmpty { "Группа" },
            colorArgb = colorArgb,
            sortOrder = dao.nextSortOrder(),
            createdAt = ts,
            updatedAt = ts,
        )
        return group.copy(id = dao.insert(group))
    }

    suspend fun rename(id: Long, title: String): Boolean {
        val g = dao.getById(id) ?: return false
        dao.update(g.copy(title = title.trim().ifEmpty { g.title }, updatedAt = now()))
        return true
    }

    /**
     * Удалить группу. Окна внутри возвращаются на главный экран, а не
     * удаляются: у каждого своя сессия, и потеря её из-за удаления папки была
     * бы несоразмерной ценой.
     */
    suspend fun delete(id: Long): Boolean {
        val g = dao.getById(id) ?: return false
        val ts = now()
        for (app in webApps.observeInGroupOnce(id)) {
            webApps.moveToGroup(app.id, null, ts)
        }
        dao.delete(g)
        return true
    }

    suspend fun reorder(orderedIds: List<Long>) {
        val ts = now()
        orderedIds.forEachIndexed { index, id ->
            dao.getById(id)?.let { dao.update(it.copy(sortOrder = index, updatedAt = ts)) }
        }
    }
}

/**
 * Разовое чтение окон группы. Отдельным расширением, чтобы не смешивать
 * наблюдение (Flow для UI) и однократный запрос (для операций).
 */
private suspend fun WebAppDao.observeInGroupOnce(groupId: Long): List<WebApp> =
    getAll().filter { it.groupId == groupId }
