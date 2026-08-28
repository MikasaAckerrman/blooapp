package dev.blooapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Группа — папка для окон веб-приложений.
 *
 * Зачем она сущность в БД, а не просто тег: у группы есть свой ярлык на
 * домашнем экране. Пользователь создаёт ярлык «gorouter», тапает — открывается
 * экран с окнами внутри этой группы. Технически это тот же
 * `requestPinShortcut`, только интент ведёт в `GroupActivity`, а не в окно
 * сайта, поэтому группе нужен стабильный id и своя иконка.
 */
@Entity(
    tableName = "groups",
    indices = [Index(value = ["sort_order"])],
)
data class Group(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,

    /**
     * Иконка группы. null — рисуем мозаику из иконок первых четырёх окон
     * внутри, как это делают лончеры для папок.
     */
    @ColumnInfo(name = "icon_ref")
    val iconRef: String? = null,

    /** Акцентный цвет плитки, ARGB. null — цвет темы. */
    @ColumnInfo(name = "color_argb")
    val colorArgb: Int? = null,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
)
