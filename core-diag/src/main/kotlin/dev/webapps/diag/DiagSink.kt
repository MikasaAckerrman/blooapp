package dev.webapps.diag

/**
 * Буферизованная отправка событий диагностики.
 *
 * Требования, из-за которых это отдельный класс с тестами, а не пара строк:
 *
 * 1. **Диагностика не имеет права ломать приложение.** Если коллектор
 *    недоступен (обычная ситуация: он живёт только на машине разработчика),
 *    события просто копятся и вытесняются, без исключений наружу.
 * 2. **Не блокировать вызывающий поток.** События приходят в том числе из
 *    `shouldInterceptRequest`, который вызывается не на UI-потоке и часто.
 *    Поэтому [offer] только кладёт в буфер, а отправку делает [flush],
 *    вызываемый с фонового потока.
 * 3. **Не течь памятью.** Буфер ограничен; при переполнении выбрасываются
 *    самые старые TRACE-события, а ERROR/FATAL сохраняются — они и нужны.
 * 4. **Порядок сохраняется**, иначе разбор цепочки «открыли chooser →
 *    callback не пришёл» становится невозможным.
 *
 * Транспорт абстрагирован ([Transport]), чтобы этот класс тестировался в
 * песочнице без Android и без сети.
 */
class DiagSink(
    private val transport: Transport,
    private val maxBuffered: Int = 512,
    private val batchSize: Int = 64,
    /** Минимальная важность, которая вообще попадает в буфер. */
    private val minSeverity: Severity = Severity.TRACE,
) {
    fun interface Transport {
        /**
         * Отправить батч. Вернуть true при успехе.
         * Реализация обязана не бросать исключения — их проглатывает [flush],
         * но лучше не рассчитывать на это.
         */
        fun send(ndjsonLines: List<String>): Boolean
    }

    private val lock = Any()
    private val buffer = ArrayDeque<DiagEvent>()
    private var droppedTrace = 0L
    private var droppedImportant = 0L
    private var sentOk = 0L
    private var sendFailures = 0L

    /** Положить событие в буфер. Никогда не бросает и не блокирует надолго. */
    fun offer(event: DiagEvent) {
        if (event.severity < minSeverity) return
        synchronized(lock) {
            if (buffer.size >= maxBuffered) evictOne()
            buffer.addLast(event)
        }
    }

    /**
     * Отправить накопленное. Вызывать с фонового потока.
     * @return сколько событий успешно ушло
     */
    fun flush(): Int {
        var total = 0
        while (true) {
            val batch = synchronized(lock) {
                if (buffer.isEmpty()) return total
                val n = minOf(batchSize, buffer.size)
                List(n) { buffer.removeFirst() }
            }
            val lines = batch.map { it.toNdjson() }
            val ok = try {
                transport.send(lines)
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                synchronized(lock) { sentOk += batch.size }
                total += batch.size
            } else {
                // Возвращаем батч в начало буфера, сохраняя порядок,
                // и прекращаем попытки до следующего flush.
                synchronized(lock) {
                    sendFailures++
                    for (e in batch.asReversed()) {
                        if (buffer.size >= maxBuffered) {
                            // Места нет — жертвуем менее важным из уже лежащего.
                            evictOne()
                        }
                        buffer.addFirst(e)
                    }
                }
                return total
            }
        }
    }

    /**
     * Вытесняет одно событие: сначала самый старый TRACE, и только если
     * TRACE не осталось — самое старое из важных. Так буфер под нагрузкой
     * трассировки не топит ERROR/FATAL.
     */
    private fun evictOne() {
        val idx = buffer.indexOfFirst { it.severity == Severity.TRACE }
        if (idx >= 0) {
            buffer.removeAt(idx)
            droppedTrace++
        } else {
            buffer.removeFirst()
            droppedImportant++
        }
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            buffered = buffer.size,
            sentOk = sentOk,
            sendFailures = sendFailures,
            droppedTrace = droppedTrace,
            droppedImportant = droppedImportant,
        )
    }

    data class Stats(
        val buffered: Int,
        val sentOk: Long,
        val sendFailures: Long,
        val droppedTrace: Long,
        val droppedImportant: Long,
    )
}
