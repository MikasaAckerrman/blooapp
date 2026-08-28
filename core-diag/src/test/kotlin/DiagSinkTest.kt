package dev.webapps.diag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagSinkTest {

    private fun ev(sev: Severity, code: Code = Code.NAV_START, msg: String = "m") =
        DiagEvent(1L, sev, code, "https://a", msg)

    private class FakeTransport(var online: Boolean = true) : DiagSink.Transport {
        val sent = mutableListOf<List<String>>()
        var throwOnSend = false
        override fun send(ndjsonLines: List<String>): Boolean {
            if (throwOnSend) throw RuntimeException("network down")
            if (!online) return false
            sent += ndjsonLines
            return true
        }
        fun allLines() = sent.flatten()
    }

    @Test
    fun `events are sent in order`() {
        val t = FakeTransport()
        val s = DiagSink(t)
        s.offer(ev(Severity.TRACE, msg = "1"))
        s.offer(ev(Severity.ERROR, msg = "2"))
        s.offer(ev(Severity.WARN, msg = "3"))
        assertThat(s.flush()).isEqualTo(3)

        val lines = t.allLines()
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).contains("\"msg\":\"1\"")
        assertThat(lines[1]).contains("\"msg\":\"2\"")
        assertThat(lines[2]).contains("\"msg\":\"3\"")
    }

    @Test
    fun `offline transport keeps events buffered without loss`() {
        val t = FakeTransport(online = false)
        val s = DiagSink(t)
        s.offer(ev(Severity.ERROR, msg = "a"))
        s.offer(ev(Severity.ERROR, msg = "b"))

        assertThat(s.flush()).isEqualTo(0)
        assertThat(s.stats().buffered).isEqualTo(2)
        assertThat(s.stats().sendFailures).isEqualTo(1)

        t.online = true
        assertThat(s.flush()).isEqualTo(2)
        assertThat(t.allLines()[0]).contains("\"msg\":\"a\"")
        assertThat(t.allLines()[1]).contains("\"msg\":\"b\"")
        assertThat(s.stats().buffered).isEqualTo(0)
    }

    @Test
    fun `throwing transport does not propagate`() {
        val t = FakeTransport().apply { throwOnSend = true }
        val s = DiagSink(t)
        s.offer(ev(Severity.FATAL))
        // Главное требование: диагностика не роняет приложение.
        assertThat(s.flush()).isEqualTo(0)
        assertThat(s.stats().buffered).isEqualTo(1)
    }

    @Test
    fun `buffer overflow drops trace before important events`() {
        val t = FakeTransport(online = false)
        val s = DiagSink(t, maxBuffered = 10)
        repeat(9) { s.offer(ev(Severity.TRACE, msg = "t$it")) }
        s.offer(ev(Severity.FATAL, msg = "fatal"))
        // Ещё 20 трассировок — они должны вытеснять только трассировки.
        repeat(20) { s.offer(ev(Severity.TRACE, msg = "x$it")) }

        t.online = true
        s.flush()
        val lines = t.allLines()
        assertThat(lines.count { it.contains("\"msg\":\"fatal\"") }).isEqualTo(1)
        assertThat(s.stats().droppedTrace).isGreaterThan(0L)
        assertThat(s.stats().droppedImportant).isEqualTo(0L)
    }

    @Test
    fun `only important events survive a flood of them`() {
        val t = FakeTransport(online = false)
        val s = DiagSink(t, maxBuffered = 4)
        repeat(10) { s.offer(ev(Severity.ERROR, msg = "e$it")) }
        // Трассировок нет — вытесняются самые старые важные.
        assertThat(s.stats().buffered).isEqualTo(4)
        assertThat(s.stats().droppedImportant).isEqualTo(6L)

        t.online = true
        s.flush()
        // Остались последние 4.
        val lines = t.allLines()
        assertThat(lines).hasSize(4)
        assertThat(lines.last()).contains("\"msg\":\"e9\"")
    }

    @Test
    fun `minSeverity filters noise at the source`() {
        val t = FakeTransport()
        val s = DiagSink(t, minSeverity = Severity.WARN)
        s.offer(ev(Severity.TRACE))
        s.offer(ev(Severity.WARN))
        assertThat(s.flush()).isEqualTo(1)
    }

    @Test
    fun `batching splits large buffers`() {
        val t = FakeTransport()
        val s = DiagSink(t, maxBuffered = 1000, batchSize = 10)
        repeat(25) { s.offer(ev(Severity.WARN, msg = "w$it")) }
        assertThat(s.flush()).isEqualTo(25)
        assertThat(t.sent.map { it.size }).containsExactly(10, 10, 5).inOrder()
    }

    @Test
    fun `flush on empty buffer is a no-op`() {
        val t = FakeTransport()
        val s = DiagSink(t)
        assertThat(s.flush()).isEqualTo(0)
        assertThat(t.sent).isEmpty()
    }

    @Test
    fun `concurrent offers do not lose or corrupt events`() {
        // shouldInterceptRequest вызывается из нескольких потоков — проверяем,
        // что буфер это переживает.
        val t = FakeTransport(online = false)
        val s = DiagSink(t, maxBuffered = 10_000)
        val threads = (1..8).map { n ->
            Thread {
                repeat(200) { i -> s.offer(ev(Severity.WARN, msg = "t$n-$i")) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(s.stats().buffered).isEqualTo(1600)
        t.online = true
        assertThat(s.flush()).isEqualTo(1600)
        assertThat(t.allLines()).hasSize(1600)
        // Все строки — валидный однострочный NDJSON.
        assertThat(t.allLines().all { it.startsWith("{") && !it.contains("\n") }).isTrue()
    }
}
