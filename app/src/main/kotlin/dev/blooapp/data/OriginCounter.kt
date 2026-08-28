package dev.blooapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Счётчик выданных номеров экземпляров для одного сайта.
 *
 * Зачем отдельная таблица вместо `MAX(instance_index)`: максимум считается по
 * СУЩЕСТВУЮЩИМ записям, и после удаления окна номер освобождается. Тогда новое
 * окно получит имя профиля удалённого — то есть его cookies, то есть чужую
 * сессию. Тест `instance numbers are not reused after deletion` поймал это
 * ровно так.
 *
 * WebView решает ту же задачу так же: у него есть отдельный
 * `prefs::kProfileCounterPref`, счётчик монотонный, номера каталогов
 * профилей не переиспользуются (`AssignNewProfileNumber` в
 * AwBrowserContextStore). Копируем проверенное поведение, а не изобретаем.
 *
 * Запись живёт дольше окон: удаление всех окон сайта НЕ сбрасывает счётчик.
 */
@Entity(tableName = "origin_counters")
data class OriginCounter(
    @PrimaryKey
    @ColumnInfo(name = "origin_key")
    val originKey: String,

    /** Последний выданный номер. Только растёт. */
    @ColumnInfo(name = "last_index")
    val lastIndex: Int,
)
