package dev.blooapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * База данных приложения.
 *
 * Room с версионированными миграциями — с первого коммита, осознанно.
 * Альтернатива (хранить конфиги как JSON-блоб в SharedPreferences) в аналоге
 * привела к дефекту, который стоит помнить: NA#180 «Instant crash on launch» —
 * NPE при чтении блоба настроек, приложение полностью нерабочее, и
 * пользователи не могли даже экспортировать свои конфиги, чтобы начать заново.
 * 24 комментария, issue до сих пор открыт.
 *
 * `fallbackToDestructiveMigration` НЕ используется нигде и никогда: это
 * молчаливое уничтожение данных пользователя.
 */
@Database(
    entities = [WebApp::class, Group::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BlooDatabase : RoomDatabase() {
    abstract fun webApps(): WebAppDao
    abstract fun groups(): GroupDao

    companion object {
        const val NAME = "blooapp.db"

        /**
         * Миграция 1 → 2: одна запись = один экземпляр (окно), а не сайт.
         *
         * Что изменилось и почему. В версии 1 на `origin_key` стоял
         * уникальный индекс, и добавить сайт второй раз было нельзя. Но два
         * аккаунта одного сайта одновременно — главная функция приложения,
         * значит идентичность была смоделирована неверно: уникальным должен
         * быть экземпляр, а не сайт.
         *
         * SQLite не умеет ни убирать ограничения столбцов, ни удалять индексы
         * выборочно в рамках ALTER TABLE, поэтому таблица пересоздаётся.
         * Данные переносятся полностью: существующие записи получают
         * `instance_index = 1` и остаются первыми окнами своих сайтов.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Группы — новая таблица.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `groups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `icon_ref` TEXT,
                        `color_argb` INTEGER,
                        `sort_order` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_groups_sort_order` ON `groups` (`sort_order`)")

                // 2. Новая таблица окон — без уникальности origin_key,
                //    с номером экземпляра, меткой и группой.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `web_apps_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `origin_key` TEXT NOT NULL,
                        `base_url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `instance_index` INTEGER NOT NULL,
                        `instance_label` TEXT,
                        `group_id` INTEGER,
                        `sort_order` INTEGER NOT NULL,
                        `is_local` INTEGER NOT NULL,
                        `isolation_mode` TEXT NOT NULL,
                        `profile_name` TEXT,
                        `js_enabled` INTEGER NOT NULL,
                        `dom_storage_enabled` INTEGER NOT NULL,
                        `cookies_enabled` INTEGER NOT NULL,
                        `third_party_cookies` INTEGER NOT NULL,
                        `require_gesture_for_media` INTEGER NOT NULL,
                        `pause_timers_in_background` INTEGER NOT NULL,
                        `allow_popups` INTEGER NOT NULL,
                        `user_agent_mode` TEXT NOT NULL,
                        `custom_user_agent` TEXT,
                        `text_zoom_percent` INTEGER NOT NULL,
                        `force_dark` INTEGER NOT NULL,
                        `mixed_content_mode` TEXT NOT NULL,
                        `keep_screen_on` INTEGER NOT NULL,
                        `external_links_outside` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`group_id`) REFERENCES `groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )

                // 3. Перенос данных. instance_index = 1: всё, что было,
                //    становится первым окном своего сайта.
                db.execSQL(
                    """
                    INSERT INTO `web_apps_new` (
                        id, origin_key, base_url, title, instance_index, instance_label,
                        group_id, sort_order, is_local, isolation_mode, profile_name,
                        js_enabled, dom_storage_enabled, cookies_enabled, third_party_cookies,
                        require_gesture_for_media, pause_timers_in_background, allow_popups,
                        user_agent_mode, custom_user_agent, text_zoom_percent, force_dark,
                        mixed_content_mode, keep_screen_on, external_links_outside,
                        created_at, updated_at
                    )
                    SELECT
                        id, origin_key, base_url, title, 1, NULL,
                        NULL, sort_order, is_local, isolation_mode, profile_name,
                        js_enabled, dom_storage_enabled, cookies_enabled, third_party_cookies,
                        require_gesture_for_media, pause_timers_in_background, allow_popups,
                        user_agent_mode, custom_user_agent, text_zoom_percent, force_dark,
                        mixed_content_mode, keep_screen_on, external_links_outside,
                        created_at, updated_at
                    FROM `web_apps`
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE `web_apps`")
                db.execSQL("ALTER TABLE `web_apps_new` RENAME TO `web_apps`")

                // 4. Индексы: origin_key НЕ уникален, profile_name уникален.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_web_apps_origin_key` ON `web_apps` (`origin_key`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_web_apps_profile_name` ON `web_apps` (`profile_name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_web_apps_group_id` ON `web_apps` (`group_id`)")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}

class Converters {
    @TypeConverter
    fun isolationToString(v: IsolationMode): String = v.name

    @TypeConverter
    fun stringToIsolation(v: String): IsolationMode =
        runCatching { IsolationMode.valueOf(v) }.getOrDefault(IsolationMode.SHARED)

    @TypeConverter
    fun uaToString(v: UserAgentMode): String = v.name

    @TypeConverter
    fun stringToUa(v: String): UserAgentMode =
        runCatching { UserAgentMode.valueOf(v) }.getOrDefault(UserAgentMode.MOBILE)

    @TypeConverter
    fun mixedToString(v: MixedContentPolicy): String = v.name

    @TypeConverter
    fun stringToMixed(v: String): MixedContentPolicy =
        runCatching { MixedContentPolicy.valueOf(v) }.getOrDefault(MixedContentPolicy.DEFAULT)
}
