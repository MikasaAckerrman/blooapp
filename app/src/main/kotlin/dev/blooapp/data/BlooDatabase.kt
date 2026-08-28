package dev.blooapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * База данных приложения.
 *
 * Room с версионированными миграциями — с первого коммита, осознанно.
 * Альтернатива (хранить конфиги как JSON-блоб в SharedPreferences) в аналоге
 * привела к дефекту, который стоит запомнить: NA#180 «Instant crash on launch»
 * — NPE при чтении блоба настроек, приложение полностью нерабочее, и
 * пользователи не могли даже экспортировать свои конфиги, чтобы начать заново.
 * 24 комментария, issue до сих пор открыт.
 *
 * `fallbackToDestructiveMigration` НЕ используется нигде и никогда: это
 * молчаливое уничтожение данных пользователя.
 */
@Database(
    entities = [WebApp::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BlooDatabase : RoomDatabase() {
    abstract fun webApps(): WebAppDao

    companion object {
        const val NAME = "blooapp.db"
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
