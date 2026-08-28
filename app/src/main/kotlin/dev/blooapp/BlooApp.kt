package dev.blooapp

import android.app.Application
import android.util.Log
import androidx.room.Room
import dev.blooapp.data.BlooDatabase
import dev.blooapp.data.WebAppRepository
import dev.blooapp.diag.DiagBootstrap
import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.Severity

class BlooApp : Application() {

    lateinit var database: BlooDatabase
        private set

    lateinit var repository: WebAppRepository
        private set

    /**
     * true, если БД не удалось открыть. В этом случае приложение обязано
     * запуститься с пустым списком и предложить импорт, а НЕ упасть.
     *
     * Это прямая реакция на дефект NA#180: там NPE при чтении настроек делал
     * приложение полностью нерабочим, и пользователи не могли даже
     * экспортировать свои конфиги, чтобы начать заново.
     */
    @Volatile
    var storageBroken = false
        private set

    override fun onCreate() {
        super.onCreate()

        // Диагностика поднимается ПЕРВОЙ: иначе сбой инициализации остального
        // будет некому зафиксировать.
        DiagBootstrap.start(this)

        // Любое необработанное исключение попадает в диагностику до того, как
        // процесс умрёт. Предыдущий обработчик вызывается — мы его не глотаем.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                DiagBootstrap.emit(
                    DiagEvent(
                        System.currentTimeMillis(), Severity.FATAL, Code.UNCAUGHT_EXCEPTION, null,
                        "${error.javaClass.simpleName}: ${error.message}",
                        mapOf(
                            "thread" to thread.name,
                            "stack" to error.stackTrace.take(8).joinToString(" | "),
                        ),
                    )
                )
                DiagBootstrap.sink.flush()
            }
            previous?.uncaughtException(thread, error)
        }

        database = openDatabase()
        repository = WebAppRepository(
            dao = database.webApps(),
            diag = DiagBootstrap::emit,
        )
    }

    private fun openDatabase(): BlooDatabase = try {
        Room.databaseBuilder(this, BlooDatabase::class.java, BlooDatabase.NAME)
            // Никакого fallbackToDestructiveMigration: это молчаливое
            // уничтожение данных пользователя.
            .build()
    } catch (e: Throwable) {
        storageBroken = true
        Log.e("blooapp", "не удалось открыть БД", e)
        DiagBootstrap.emit(
            DiagEvent(
                System.currentTimeMillis(), Severity.ERROR, Code.CONFIG_CORRUPTED, null,
                "БД не открылась: ${e.javaClass.simpleName}: ${e.message}",
            )
        )
        // Резервный вариант — база в памяти: список будет пуст, но приложение
        // запустится и даст пользователю добраться до импорта.
        Room.inMemoryDatabaseBuilder(this, BlooDatabase::class.java).build()
    }
}
