package dev.webapps.diag

/**
 * Монитор контрактов WebView — ядро механизма «находить ошибки в реальном
 * времени».
 *
 * Зачем он нужен. Приложение-обёртка над WebView почти никогда не падает в
 * месте дефекта. Типовая картина из каталога PLAN.md §4:
 *  - не вызван `filePathCallback` → поле `<input type=file>` залипает НАВСЕГДА,
 *    исключения нет, лога нет (NA#33);
 *  - в `shouldOverrideUrlLoading` сделали `loadUrl(url)` + `return true` →
 *    навигация зацикливается, в Android-PWA-Wrapper это давало 200+ вкладок;
 *  - `onRenderProcessGone` вернул `false` → приложение убивает система, и в
 *    отчёте будет «краш», а не «мы не обработали смерть рендерера».
 *
 * Поэтому монитор — это машина состояний над последовательностью событий,
 * которая знает КОНТРАКТЫ и говорит о нарушении сразу, а не после жалобы.
 * Класс намеренно чистый (без Android API), чтобы гоняться юнит-тестами
 * в песочнице без эмулятора.
 *
 * Потокобезопасность: методы синхронизированы. Это обязательно, потому что
 * `shouldInterceptRequest` вызывается НЕ на UI-потоке и из нескольких потоков
 * (проверено в документации WebViewClient).
 */
class ContractMonitor(
    private val clock: () -> Long,
    private val sink: (DiagEvent) -> Unit,
    /** Порог «сколько раз один и тот же URL можно грузить», защита от циклов. */
    private val navLoopThreshold: Int = 5,
    /** Окно в мс, в котором считаются повторы навигации. */
    private val navLoopWindowMs: Long = 10_000,
    /** Сколько ждать вызова filePathCallback, прежде чем счесть его потерянным. */
    private val fileChooserTimeoutMs: Long = 120_000,
) {
    private val lock = Any()

    /** Открытые запросы файлового выбора: id → когда открыт. */
    private val openFileChoosers = LinkedHashMap<Long, Pending>()

    /** История навигаций для детекции циклов: url → времена загрузок. */
    private val navHistory = LinkedHashMap<String, ArrayDeque<Long>>()

    /** Незакрытые fullscreen-сессии: чтобы поймать «показали и не убрали». */
    private var fullscreenEnteredAt: Long? = null

    /** Было ли отдано управление в onRenderProcessGone. */
    private var renderGoneHandled = false

    private data class Pending(val at: Long, val webApp: String?)

    // ---------------------------------------------------------------- файлы

    /** Вызвать в начале `onShowFileChooser`. Возвращает id для сопоставления. */
    fun fileChooserOpened(id: Long, webApp: String?) = synchronized(lock) {
        openFileChoosers[id] = Pending(clock(), webApp)
        emit(Severity.TRACE, Code.FILE_CHOOSER_OPENED, webApp, "file chooser opened id=$id")
    }

    /**
     * Вызвать ВСЕГДА, когда `filePathCallback` реально вызван — включая
     * вызов с `null` при отмене пользователем.
     */
    fun fileChooserCallbackInvoked(id: Long) = synchronized(lock) {
        val p = openFileChoosers.remove(id)
        if (p == null) {
            emit(
                Severity.WARN, Code.FILE_CHOOSER_CALLBACK_LOST, null,
                "callback вызван для неизвестного id=$id (двойной вызов?)"
            )
        }
    }

    /**
     * Периодическая проверка (например, из `onStop` окна или по таймеру).
     * Всё, что висит дольше [fileChooserTimeoutMs], — потерянный callback:
     * поле ввода на странице залипло, пользователь этого уже не исправит.
     */
    fun sweep() = synchronized(lock) {
        val now = clock()
        val lost = openFileChoosers.filter { now - it.value.at > fileChooserTimeoutMs }
        for ((id, p) in lost) {
            openFileChoosers.remove(id)
            emit(
                Severity.ERROR, Code.FILE_CHOOSER_CALLBACK_LOST, p.webApp,
                "filePathCallback не вызван за ${fileChooserTimeoutMs}ms, id=$id — " +
                    "input type=file на странице залип"
            )
        }
        fullscreenEnteredAt?.let { at ->
            if (now - at > 6L * 60 * 60 * 1000) {
                emit(
                    Severity.WARN, Code.FULLSCREEN_CALLBACK_MISSING, null,
                    "fullscreen-сессия открыта >6ч — onHideCustomView не пришёл"
                )
                fullscreenEnteredAt = null
            }
        }
    }

    /** Окно активности закрывается — всё незакрытое здесь уже точно потеряно. */
    fun windowClosing(webApp: String?) = synchronized(lock) {
        for ((id, p) in openFileChoosers) {
            emit(
                Severity.ERROR, Code.FILE_CHOOSER_CALLBACK_LOST, p.webApp ?: webApp,
                "окно закрыто с незавершённым file chooser id=$id"
            )
        }
        openFileChoosers.clear()
        navHistory.clear()
    }

    // ------------------------------------------------------------ навигация

    /**
     * Вызвать из `shouldOverrideUrlLoading`.
     *
     * @param handledByApp true, если приложение вернуло `true` (то есть само
     *        берёт навигацию на себя)
     * @param calledLoadUrl true, если внутри был вызван `loadUrl` — это
     *        прямое нарушение контракта из документации: «Do not call
     *        WebView.loadUrl with the request's URL and then return true».
     */
    fun urlOverride(
        url: String,
        webApp: String?,
        handledByApp: Boolean,
        calledLoadUrl: Boolean,
    ) = synchronized(lock) {
        if (handledByApp && calledLoadUrl) {
            emit(
                Severity.ERROR, Code.URL_LOADED_FROM_OVERRIDE, webApp,
                "loadUrl() + return true в shouldOverrideUrlLoading — " +
                    "лишняя отмена и перезапуск загрузки, источник навигационных циклов",
                mapOf("url" to url)
            )
        }
        val now = clock()
        val q = navHistory.getOrPut(url) { ArrayDeque() }
        q.addLast(now)
        while (q.isNotEmpty() && now - q.first() > navLoopWindowMs) q.removeFirst()
        if (q.size >= navLoopThreshold) {
            emit(
                Severity.ERROR, Code.URL_LOADED_FROM_OVERRIDE, webApp,
                "один и тот же URL загружен ${q.size} раз за ${navLoopWindowMs}ms — навигационный цикл",
                mapOf("url" to url)
            )
            q.clear()
        }
        // Ограничиваем память: история нужна только для свежих URL.
        if (navHistory.size > 64) {
            val it = navHistory.entries.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
    }

    /** Не-HTTP схема, для которой нет обработчика. */
    fun unknownScheme(scheme: String, url: String, webApp: String?) = synchronized(lock) {
        emit(
            Severity.WARN, Code.URL_SCHEME_UNKNOWN, webApp,
            "схема '$scheme' не в whitelist — WebView вернёт ERR_UNKNOWN_URL_SCHEME",
            mapOf("url" to url, "scheme" to scheme)
        )
    }

    // -------------------------------------------------------------- fullscreen

    fun fullscreenEntered(webApp: String?) = synchronized(lock) {
        fullscreenEnteredAt = clock()
        emit(Severity.TRACE, Code.NAV_COMMITTED, webApp, "fullscreen entered")
    }

    fun fullscreenExited() = synchronized(lock) { fullscreenEnteredAt = null }

    // ------------------------------------------------------------ рендерер

    /**
     * Вызвать из `onRenderProcessGone`.
     * @param returnedTrue что именно вернул наш обработчик
     */
    fun renderProcessGone(webApp: String?, crashed: Boolean, returnedTrue: Boolean) =
        synchronized(lock) {
            renderGoneHandled = returnedTrue
            emit(
                if (returnedTrue) Severity.ERROR else Severity.FATAL,
                Code.RENDER_PROCESS_GONE, webApp,
                if (returnedTrue) {
                    "рендерер ${if (crashed) "упал" else "убит системой"}, обработано"
                } else {
                    "рендерер ${if (crashed) "упал" else "убит системой"}, вернули false — " +
                        "система убьёт приложение"
                },
                mapOf("crashed" to crashed.toString())
            )
        }

    // ------------------------------------------------------------- cookies

    /** Вызывается при уходе окна в фон. */
    fun windowStopped(webApp: String?, cookiesFlushed: Boolean) = synchronized(lock) {
        if (!cookiesFlushed) {
            emit(
                Severity.WARN, Code.COOKIE_FLUSH_MISSING, webApp,
                "окно ушло в фон без CookieManager.flush() — сессия может не дожить " +
                    "до следующего запуска, если процесс убьют"
            )
        }
    }

    // ------------------------------------------------------------------ util

    private fun emit(
        sev: Severity,
        code: Code,
        webApp: String?,
        msg: String,
        fields: Map<String, String> = emptyMap(),
    ) {
        sink(DiagEvent(clock(), sev, code, webApp, msg, fields))
    }

    /** Для тестов и экрана диагностики: что сейчас «висит». */
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            openFileChoosers = openFileChoosers.size,
            trackedUrls = navHistory.size,
            fullscreenOpen = fullscreenEnteredAt != null,
            lastRenderGoneHandled = renderGoneHandled,
        )
    }

    data class Snapshot(
        val openFileChoosers: Int,
        val trackedUrls: Int,
        val fullscreenOpen: Boolean,
        val lastRenderGoneHandled: Boolean,
    )
}
