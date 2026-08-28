package dev.blooapp.data

import com.google.common.truth.Truth.assertThat
import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Главный инвариант проекта: несколько окон одного сайта живут независимо.
 *
 * Проверяется без Room и без Android — DAO подменяется фейком. Причина: эти
 * правила важнее, чем SQL, и должны проверяться на каждом прогоне за секунды.
 */
class WebAppRepositoryTest {

    private class FakeDao : WebAppDao {
        val items = mutableListOf<WebApp>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<WebApp>> = flowOf(items.toList())
        override fun observeUngrouped(): Flow<List<WebApp>> =
            flowOf(items.filter { it.groupId == null })
        override fun observeInGroup(groupId: Long): Flow<List<WebApp>> =
            flowOf(items.filter { it.groupId == groupId })
        override suspend fun getAll(): List<WebApp> = items.toList()
        override suspend fun getById(id: Long): WebApp? = items.find { it.id == id }
        override suspend fun getInstancesOf(originKey: String): List<WebApp> =
            items.filter { it.originKey == originKey }.sortedBy { it.instanceIndex }
        override suspend fun countInstancesOf(originKey: String): Int =
            items.count { it.originKey == originKey }
        override suspend fun maxInstanceIndexOf(originKey: String): Int =
            items.filter { it.originKey == originKey }.maxOfOrNull { it.instanceIndex } ?: 0
        override suspend fun getByProfile(profileName: String): WebApp? =
            items.find { it.profileName == profileName }
        override suspend fun allProfileNames(): List<String> = items.mapNotNull { it.profileName }
        override suspend fun count(): Int = items.size
        override suspend fun nextSortOrder(): Int = (items.maxOfOrNull { it.sortOrder } ?: -1) + 1
        override suspend fun insert(app: WebApp): Long {
            val id = nextId++
            items += app.copy(id = id)
            return id
        }
        override suspend fun update(app: WebApp) {
            val i = items.indexOfFirst { it.id == app.id }
            if (i >= 0) items[i] = app
        }
        override suspend fun delete(app: WebApp) {
            items.removeAll { it.id == app.id }
        }
        override suspend fun moveToGroup(id: Long, groupId: Long?, now: Long) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(groupId = groupId, updatedAt = now)
        }
        override suspend fun setSortOrder(id: Long, order: Int, now: Long) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = items[i].copy(sortOrder = order, updatedAt = now)
        }
    }

    private fun repo(dao: FakeDao, events: MutableList<DiagEvent> = mutableListOf()) =
        WebAppRepository(dao, now = { 1_000L }, diag = { events += it })

    private suspend fun forceAdd(rp: WebAppRepository, url: String): WebApp =
        (rp.add(url, force = true) as WebAppRepository.AddResult.Added).app

    // --- ГЛАВНОЕ: несколько окон одного сайта -------------------------------

    @Test
    fun `same site can be added many times`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)

        val first = rp.add("https://gorouter.app")
        assertThat(first).isInstanceOf(WebAppRepository.AddResult.Added::class.java)

        // Второй раз — не отказ, а вопрос.
        val second = rp.add("https://gorouter.app")
        assertThat(second).isInstanceOf(WebAppRepository.AddResult.NeedsConfirmation::class.java)
        val confirm = second as WebAppRepository.AddResult.NeedsConfirmation
        assertThat(confirm.existing).hasSize(1)

        // После подтверждения появляется второе окно.
        val added = rp.add("https://gorouter.app", force = true)
        assertThat(added).isInstanceOf(WebAppRepository.AddResult.Added::class.java)
        assertThat((added as WebAppRepository.AddResult.Added).instanceNumber).isEqualTo(2)
        assertThat(dao.items).hasSize(2)
    }

    @Test
    fun `instances of one site get distinct profiles`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val a = forceAdd(rp, "https://gorouter.app")
        val b = forceAdd(rp, "https://gorouter.app")
        val c = forceAdd(rp, "https://gorouter.app")

        val profiles = listOf(a, b, c).map { it.profileName }
        assertThat(profiles).doesNotContain(null)
        assertThat(profiles.toSet()).hasSize(3)
        // Один и тот же сайт — значит originKey общий.
        assertThat(listOf(a, b, c).map { it.originKey }.toSet()).hasSize(1)
    }

    @Test
    fun `instance numbers are not reused after deletion`(): Unit = runBlocking {
        // Иначе новое окно унаследовало бы имя профиля удалённого и получило
        // его старые cookies — то есть чужую сессию.
        val dao = FakeDao()
        val rp = repo(dao)
        val a = forceAdd(rp, "https://a.com")
        val b = forceAdd(rp, "https://a.com")
        assertThat(b.instanceIndex).isEqualTo(2)

        rp.delete(b)
        val c = forceAdd(rp, "https://a.com")
        assertThat(c.instanceIndex).isEqualTo(3)
        assertThat(c.profileName).isNotEqualTo(b.profileName)
    }

    @Test
    fun `display name distinguishes instances`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val a = forceAdd(rp, "https://gorouter.app")
        val b = forceAdd(rp, "https://gorouter.app")
        assertThat(a.displayName).isEqualTo("gorouter.app")
        assertThat(b.displayName).isEqualTo("gorouter.app (2)")
    }

    @Test
    fun `custom label overrides numbering`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = forceAdd(rp, "https://mail.com")
        rp.rename(app.id, "Рабочий")
        assertThat(dao.items.single().displayName).isEqualTo("Рабочий")
    }

    @Test
    fun `blank label falls back to numbering`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = forceAdd(rp, "https://mail.com")
        rp.rename(app.id, "   ")
        assertThat(dao.items.single().displayName).isEqualTo("mail.com")
    }

    @Test
    fun `shared isolation gets no profile`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val r = rp.add("https://a.com", isolation = IsolationMode.SHARED)
        val app = (r as WebAppRepository.AddResult.Added).app
        assertThat(app.profileName).isNull()
        assertThat(app.hasOwnSession).isFalse()
    }

    @Test
    fun `profile isolation reports own session`(): Unit = runBlocking {
        val dao = FakeDao()
        val app = forceAdd(repo(dao), "https://a.com")
        assertThat(app.isolationMode).isEqualTo(IsolationMode.PROFILE)
        assertThat(app.hasOwnSession).isTrue()
    }

    // --- нормализация и валидация ------------------------------------------

    @Test
    fun `adding a plain url produces normalized app`(): Unit = runBlocking {
        val dao = FakeDao()
        val app = forceAdd(repo(dao), "  HTTPS://Example.COM/Path  ")
        assertThat(app.originKey).isEqualTo("https://example.com")
        assertThat(app.baseUrl).isEqualTo("https://example.com/Path")
        assertThat(app.title).isEqualTo("example.com")
        assertThat(app.isLocal).isFalse()
    }

    @Test
    fun `lan address is accepted and marked local — issue 48`(): Unit = runBlocking {
        val dao = FakeDao()
        val app = forceAdd(repo(dao), "nas:8080/library")
        assertThat(app.isLocal).isTrue()
        assertThat(app.originKey).isEqualTo("https://nas:8080")
    }

    @Test
    fun `different paths of one site are the same site`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        forceAdd(rp, "https://mail.example.com/inbox")
        val second = rp.add("https://mail.example.com/settings")
        // Тот же сайт → спрашиваем про новое окно, а не создаём молча.
        assertThat(second).isInstanceOf(WebAppRepository.AddResult.NeedsConfirmation::class.java)
    }

    @Test
    fun `invalid url is rejected and reported to diagnostics`(): Unit = runBlocking {
        val dao = FakeDao()
        val events = mutableListOf<DiagEvent>()
        val r = repo(dao, events).add("javascript:alert(1)")
        assertThat(r).isInstanceOf(WebAppRepository.AddResult.Rejected::class.java)
        assertThat(events.map { it.code }).contains(Code.URL_REJECTED_BY_VALIDATOR)
    }

    // --- инварианты идентичности --------------------------------------------

    @Test
    fun `changing originKey is refused and reported`(): Unit = runBlocking {
        val dao = FakeDao()
        val events = mutableListOf<DiagEvent>()
        val rp = repo(dao, events)
        val app = forceAdd(rp, "https://a.com")

        assertThat(rp.update(app.copy(originKey = "https://evil.com"))).isFalse()
        assertThat(events.map { it.code }).contains(Code.BASEURL_REWRITE_BLOCKED)
        assertThat(dao.items.single().originKey).isEqualTo("https://a.com")
    }

    @Test
    fun `changing profile name is refused`(): Unit = runBlocking {
        val dao = FakeDao()
        val events = mutableListOf<DiagEvent>()
        val rp = repo(dao, events)
        val app = forceAdd(rp, "https://a.com")

        assertThat(rp.update(app.copy(profileName = "hijacked"))).isFalse()
        assertThat(events.map { it.code }).contains(Code.ISOLATION_MODE_MISMATCH)
    }

    @Test
    fun `changing isolation mode is refused`(): Unit = runBlocking {
        val dao = FakeDao()
        val events = mutableListOf<DiagEvent>()
        val rp = repo(dao, events)
        val app = forceAdd(rp, "https://a.com")

        assertThat(rp.update(app.copy(isolationMode = IsolationMode.SHARED))).isFalse()
        assertThat(events.map { it.code }).contains(Code.ISOLATION_MODE_MISMATCH)
    }

    @Test
    fun `changing instance index is refused`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = forceAdd(rp, "https://a.com")
        assertThat(rp.update(app.copy(instanceIndex = 99))).isFalse()
    }

    @Test
    fun `ordinary settings update succeeds`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = forceAdd(rp, "https://a.com")

        assertThat(rp.update(app.copy(jsEnabled = false, textZoomPercent = 130))).isTrue()
        assertThat(dao.items.single().jsEnabled).isFalse()
        assertThat(dao.items.single().textZoomPercent).isEqualTo(130)
    }

    // --- NA#212: смена стартового адреса без потери сессии ------------------

    @Test
    fun `start url can change within the same origin — issue 212`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = forceAdd(rp, "https://mail.example.com/inbox")

        assertThat(rp.changeStartUrl(app.id, "https://mail.example.com/settings")).isTrue()
        val updated = dao.items.single()
        assertThat(updated.baseUrl).isEqualTo("https://mail.example.com/settings")
        // Идентичность и профиль не изменились — сессия остаётся.
        assertThat(updated.originKey).isEqualTo("https://mail.example.com")
        assertThat(updated.profileName).isEqualTo(app.profileName)
    }

    @Test
    fun `start url cannot jump to another origin`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = forceAdd(rp, "https://a.com")
        assertThat(rp.changeStartUrl(app.id, "https://b.com")).isFalse()
        assertThat(dao.items.single().baseUrl).isEqualTo("https://a.com/")
    }

    // --- группы -------------------------------------------------------------

    @Test
    fun `app can be moved into a group and back`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = forceAdd(rp, "https://a.com")
        assertThat(app.groupId).isNull()

        rp.moveToGroup(app.id, 5L)
        assertThat(dao.items.single().groupId).isEqualTo(5L)

        rp.moveToGroup(app.id, null)
        assertThat(dao.items.single().groupId).isNull()
    }

    @Test
    fun `reorder assigns sequential sort orders`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val a = forceAdd(rp, "https://a.com")
        val b = forceAdd(rp, "https://b.com")
        val c = forceAdd(rp, "https://c.com")

        rp.reorder(listOf(c.id, a.id, b.id))
        assertThat(dao.getById(c.id)!!.sortOrder).isEqualTo(0)
        assertThat(dao.getById(a.id)!!.sortOrder).isEqualTo(1)
        assertThat(dao.getById(b.id)!!.sortOrder).isEqualTo(2)
    }

    @Test
    fun `delete removes only the target instance`(): Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val a = forceAdd(rp, "https://gorouter.app")
        val b = forceAdd(rp, "https://gorouter.app")

        rp.delete(a)
        assertThat(dao.items).hasSize(1)
        assertThat(dao.items.single().id).isEqualTo(b.id)
        // У выжившего окна профиль на месте — его сессия не тронута.
        assertThat(dao.items.single().profileName).isEqualTo(b.profileName)
    }
}
