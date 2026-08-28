package dev.blooapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WebAppDao {

    @Query("SELECT * FROM web_apps ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<WebApp>>

    /** Окна на главном экране: те, что не лежат ни в одной группе. */
    @Query("SELECT * FROM web_apps WHERE group_id IS NULL ORDER BY sort_order ASC, id ASC")
    fun observeUngrouped(): Flow<List<WebApp>>

    @Query("SELECT * FROM web_apps WHERE group_id = :groupId ORDER BY sort_order ASC, id ASC")
    fun observeInGroup(groupId: Long): Flow<List<WebApp>>

    @Query("SELECT * FROM web_apps ORDER BY sort_order ASC, id ASC")
    suspend fun getAll(): List<WebApp>

    @Query("SELECT * FROM web_apps WHERE id = :id")
    suspend fun getById(id: Long): WebApp?

    /**
     * Все окна одного сайта. Раньше здесь был `getByOrigin`, возвращавший одну
     * запись, и на нём стояла проверка «уже добавлен» — из-за неё нельзя было
     * держать два аккаунта одного сайта. Теперь окон может быть много, и это
     * нормальный случай, а не конфликт.
     */
    @Query("SELECT * FROM web_apps WHERE origin_key = :originKey ORDER BY instance_index ASC")
    suspend fun getInstancesOf(originKey: String): List<WebApp>

    @Query("SELECT COUNT(*) FROM web_apps WHERE origin_key = :originKey")
    suspend fun countInstancesOf(originKey: String): Int

    /**
     * Максимальный использованный номер экземпляра. Новый экземпляр получает
     * max + 1, и номера НЕ переиспользуются после удаления — так же, как
     * WebView не переиспользует номера каталогов профилей. Иначе удалённый и
     * заново созданный экземпляр мог бы унаследовать чужой ярлык.
     */
    @Query("SELECT COALESCE(MAX(instance_index), 0) FROM web_apps WHERE origin_key = :originKey")
    suspend fun maxInstanceIndexOf(originKey: String): Int

    @Query("SELECT * FROM web_apps WHERE profile_name = :profileName LIMIT 1")
    suspend fun getByProfile(profileName: String): WebApp?

    @Query("SELECT profile_name FROM web_apps WHERE profile_name IS NOT NULL")
    suspend fun allProfileNames(): List<String>

    @Query("SELECT COUNT(*) FROM web_apps")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM web_apps")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(app: WebApp): Long

    @Update
    suspend fun update(app: WebApp)

    @Delete
    suspend fun delete(app: WebApp)

    @Query("UPDATE web_apps SET group_id = :groupId, updated_at = :now WHERE id = :id")
    suspend fun moveToGroup(id: Long, groupId: Long?, now: Long)

    @Query("UPDATE web_apps SET sort_order = :order, updated_at = :now WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int, now: Long)
}
