package dev.blooapp.data

import com.google.common.truth.Truth.assertThat
import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Инварианты идентичности веб-приложения.
 *
 * Проверяется без Room и без Android: DAO подменяется фейком. Причина — эти
 * инварианты важнее, чем SQL, и должны проверяться быстро, на каждом прогоне.
 */
class WebAppRepositoryTest {

    private class FakeDao : WebAppDao {
        val items = mutableListOf<WebApp>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<WebApp>> = flowOf(items.toList())
        override suspend fun getAll(): List<WebApp> = items.toList()
        override suspend fun getById(id: Long): WebApp? = items.find { it.id == id }
        override suspend fun getByOrigin(originKey: String): WebApp? =
            items.find { it.originKey == originKey }
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
    }

    private fun repo(dao: FakeDao, events: MutableList<DiagEvent> = mutableListOf()) =
        WebAppRepository(dao, now = { 1_000L }, diag = { events += it })

    @Test
    fun `adding a plain url produces normalized app`() : Unit = runBlocking {
        val dao = FakeDao()
        val r = repo(dao).add("  HTTPS://Example.COM/Path  ")
        assertThat(r).isInstanceOf(WebAppRepository.AddResult.Added::class.java)
        val app = (r as WebAppRepository.AddResult.Added).app
        assertThat(app.originKey).isEqualTo("https://example.com")
        assertThat(app.baseUrl).isEqualTo("https://example.com/Path")
        assertThat(app.title).isEqualTo("example.com")
        assertThat(app.isLocal).isFalse()
    }

    @Test
    fun `lan address is accepted and marked local — issue 48`() : Unit = runBlocking {
        val dao = FakeDao()
        val r = repo(dao).add("nas:8080/library")
        val app = (r as WebAppRepository.AddResult.Added).app
        assertThat(app.isLocal).isTrue()
        assertThat(app.originKey).isEqualTo("https://nas:8080")
    }

    @Test
    fun `same origin different path is a duplicate`() : Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        rp.add("https://mail.example.com/inbox")
        val second = rp.add("https://mail.example.com/settings")
        assertThat(second).isInstanceOf(WebAppRepository.AddResult.Duplicate::class.java)
        assertThat(dao.items).hasSize(1)
    }

    @Test
    fun `invalid url is rejected and reported to diagnostics`() : Unit = runBlocking {
        val dao = FakeDao()
        val events = mutableListOf<DiagEvent>()
        val r = repo(dao, events).add("javascript:alert(1)")
        assertThat(r).isInstanceOf(WebAppRepository.AddResult.Rejected::class.java)
        assertThat(events.map { it.code }).contains(Code.URL_REJECTED_BY_VALIDATOR)
    }

    @Test
    fun `sort order increments`() : Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        rp.add("a.com"); rp.add("b.com"); rp.add("c.com")
        assertThat(dao.items.map { it.sortOrder }).containsExactly(0, 1, 2).inOrder()
    }

    // --- инварианты идентичности --------------------------------------------

    @Test
    fun `changing originKey is refused and reported`() : Unit = runBlocking {
        val dao = FakeDao()
        val events = mutableListOf<DiagEvent>()
        val rp = repo(dao, events)
        val app = (rp.add("https://a.com") as WebAppRepository.AddResult.Added).app

        val ok = rp.update(app.copy(originKey = "https://evil.com"))
        assertThat(ok).isFalse()
        assertThat(events.map { it.code }).contains(Code.BASEURL_REWRITE_BLOCKED)
        assertThat(dao.items.single().originKey).isEqualTo("https://a.com")
    }

    @Test
    fun `changing isolation mode is refused`() : Unit = runBlocking {
        val dao = FakeDao()
        val events = mutableListOf<DiagEvent>()
        val rp = repo(dao, events)
        val app = (rp.add("https://a.com") as WebAppRepository.AddResult.Added).app

        val ok = rp.update(app.copy(isolationMode = IsolationMode.PROFILE))
        assertThat(ok).isFalse()
        assertThat(events.map { it.code }).contains(Code.ISOLATION_MODE_MISMATCH)
    }

    @Test
    fun `changing profile name of existing app is refused`() : Unit = runBlocking {
        val dao = FakeDao()
        val events = mutableListOf<DiagEvent>()
        val rp = repo(dao, events)
        dao.items += WebApp(
            id = 1, originKey = "https://a.com", baseUrl = "https://a.com/",
            title = "a", profileName = "p1", isolationMode = IsolationMode.SHARED,
        )
        val ok = rp.update(dao.items.single().copy(profileName = "p2"))
        assertThat(ok).isFalse()
        assertThat(events.map { it.code }).contains(Code.ISOLATION_MODE_MISMATCH)
    }

    @Test
    fun `ordinary settings update succeeds`() : Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = (rp.add("https://a.com") as WebAppRepository.AddResult.Added).app

        val ok = rp.update(app.copy(jsEnabled = false, textZoomPercent = 130))
        assertThat(ok).isTrue()
        assertThat(dao.items.single().jsEnabled).isFalse()
        assertThat(dao.items.single().textZoomPercent).isEqualTo(130)
    }

    // --- NA#212: смена стартового адреса без потери сессии ------------------

    @Test
    fun `start url can change within the same origin — issue 212`() : Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = (rp.add("https://mail.example.com/inbox") as WebAppRepository.AddResult.Added).app

        val ok = rp.changeStartUrl(app.id, "https://mail.example.com/settings/profile")
        assertThat(ok).isTrue()
        val updated = dao.items.single()
        assertThat(updated.baseUrl).isEqualTo("https://mail.example.com/settings/profile")
        // Идентичность не изменилась — сессия остаётся.
        assertThat(updated.originKey).isEqualTo("https://mail.example.com")
    }

    @Test
    fun `start url cannot jump to another origin`() : Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val app = (rp.add("https://a.com") as WebAppRepository.AddResult.Added).app
        assertThat(rp.changeStartUrl(app.id, "https://b.com")).isFalse()
        assertThat(dao.items.single().baseUrl).isEqualTo("https://a.com/")
    }

    @Test
    fun `delete removes only the target`() : Unit = runBlocking {
        val dao = FakeDao()
        val rp = repo(dao)
        val a = (rp.add("https://a.com") as WebAppRepository.AddResult.Added).app
        rp.add("https://b.com")
        rp.delete(a)
        assertThat(dao.items.map { it.originKey }).containsExactly("https://b.com")
    }
}
