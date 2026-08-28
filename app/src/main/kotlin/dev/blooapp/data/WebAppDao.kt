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

    @Query("SELECT * FROM web_apps ORDER BY sort_order ASC, id ASC")
    suspend fun getAll(): List<WebApp>

    @Query("SELECT * FROM web_apps WHERE id = :id")
    suspend fun getById(id: Long): WebApp?

    @Query("SELECT * FROM web_apps WHERE origin_key = :originKey LIMIT 1")
    suspend fun getByOrigin(originKey: String): WebApp?

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
}
