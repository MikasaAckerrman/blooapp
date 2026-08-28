package dev.blooapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Query("SELECT * FROM groups ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<Group>>

    @Query("SELECT * FROM groups ORDER BY sort_order ASC, id ASC")
    suspend fun getAll(): List<Group>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getById(id: Long): Group?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM groups")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(group: Group): Long

    @Update
    suspend fun update(group: Group)

    /**
     * Удаление группы. Окна внутри НЕ удаляются — по внешнему ключу
     * `onDelete = SET_NULL` они возвращаются на главный экран. Потерять сессии
     * из-за удаления папки было бы неприемлемо.
     */
    @Delete
    suspend fun delete(group: Group)
}
