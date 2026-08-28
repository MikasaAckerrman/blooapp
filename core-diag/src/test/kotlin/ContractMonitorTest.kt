package dev.webapps.diag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContractMonitorTest {

    private class Fixture(startMs: Long = 1_000_000L) {
        var now = startMs
        val events = mutableListOf<DiagEvent>()
        val monitor = ContractMonitor(clock = { now }, sink = { events += it })
        fun advance(ms: Long) { now += ms }
        fun codes() = events.map { it.code }
        fun errors() = events.filter { it.severity >= Severity.ERROR }
    }

    // --- NA#33: filePathCallback не вызван → input залипает -----------------

    @Test
    fun `lost file chooser callback is reported after timeout`() {
        val f = Fixture()
        f.monitor.fileChooserOpened(1L, "https://site")
        f.advance(121_000)
        f.monitor.sweep()

        assertThat(f.codes()).contains(Code.FILE_CHOOSER_CALLBACK_LOST)
        val e = f.events.last { it.code == Code.FILE_CHOOSER_CALLBACK_LOST }
        assertThat(e.severity).isEqualTo(Severity.ERROR)
        assertThat(e.code.knownIssue).isEqualTo("NA#33")
    }

    @Test
    fun `invoked callback produces no error`() {
        val f = Fixture()
        f.monitor.fileChooserOpened(1L, "https://site")
        f.monitor.fileChooserCallbackInvoked(1L)
        f.advance(300_000)
        f.monitor.sweep()

        assertThat(f.errors()).isEmpty()
        assertThat(f.monitor.snapshot().openFileChoosers).isEqualTo(0)
    }

    @Test
    fun `callback with null on cancel also closes the contract`() {
        // Ключевой момент: отмена пользователем ТОЖЕ обязана вызвать callback.
        val f = Fixture()
        f.monitor.fileChooserOpened(7L, null)
        f.monitor.fileChooserCallbackInvoked(7L) // приложение вызвало с null
        f.advance(200_000); f.monitor.sweep()
        assertThat(f.errors()).isEmpty()
    }

    @Test
    fun `double callback is flagged`() {
        val f = Fixture()
        f.monitor.fileChooserOpened(1L, null)
        f.monitor.fileChooserCallbackInvoked(1L)
        f.monitor.fileChooserCallbackInvoked(1L)
        assertThat(f.codes()).contains(Code.FILE_CHOOSER_CALLBACK_LOST)
    }

    @Test
    fun `closing window reports pending choosers immediately`() {
        val f = Fixture()
        f.monitor.fileChooserOpened(1L, "https://a")
        f.monitor.windowClosing("https://a")
        assertThat(f.errors().map { it.code }).contains(Code.FILE_CHOOSER_CALLBACK_LOST)
    }

    // --- Контракт shouldOverrideUrlLoading ----------------------------------

    @Test
    fun `loadUrl plus return true is a contract violation`() {
        val f = Fixture()
        f.monitor.urlOverride("https://a/x", "https://a", handledByApp = true, calledLoadUrl = true)
        val e = f.events.single { it.code == Code.URL_LOADED_FROM_OVERRIDE }
        assertThat(e.severity).isEqualTo(Severity.ERROR)
        assertThat(e.fields["url"]).isEqualTo("https://a/x")
    }

    @Test
    fun `returning false without loadUrl is fine`() {
        val f = Fixture()
        f.monitor.urlOverride("https://a/x", "https://a", handledByApp = false, calledLoadUrl = false)
        assertThat(f.errors()).isEmpty()
    }

    @Test
    fun `navigation loop is detected`() {
        // Симуляция дефекта Android-PWA-Wrapper: один URL грузится по кругу.
        val f = Fixture()
        repeat(5) {
            f.advance(100)
            f.monitor.urlOverride("https://a/loop", "https://a", handledByApp = false, calledLoadUrl = false)
        }
        val loop = f.events.filter { it.code == Code.URL_LOADED_FROM_OVERRIDE }
        assertThat(loop).isNotEmpty()
        assertThat(loop.last().message).contains("навигационный цикл")
    }

    @Test
    fun `repeats outside the window do not trigger loop detection`() {
        val f = Fixture()
        repeat(5) {
            f.advance(11_000) // больше окна 10с
            f.monitor.urlOverride("https://a/slow", null, handledByApp = false, calledLoadUrl = false)
        }
        assertThat(f.errors()).isEmpty()
    }

    @Test
    fun `nav history does not grow without bound`() {
        val f = Fixture()
        repeat(200) { i ->
            f.monitor.urlOverride("https://a/p$i", null, handledByApp = false, calledLoadUrl = false)
        }
        assertThat(f.monitor.snapshot().trackedUrls).isAtMost(64)
    }

    // --- NA#177: неизвестная схема ------------------------------------------

    @Test
    fun `unknown scheme is warned with issue reference`() {
        val f = Fixture()
        f.monitor.unknownScheme("fb-messenger", "fb-messenger://threads?x=1", "https://facebook.com")
        val e = f.events.single { it.code == Code.URL_SCHEME_UNKNOWN }
        assertThat(e.severity).isEqualTo(Severity.WARN)
        assertThat(e.code.knownIssue).isEqualTo("NA#177")
        assertThat(e.fields["scheme"]).isEqualTo("fb-messenger")
    }

    // --- onRenderProcessGone ------------------------------------------------

    @Test
    fun `returning false from renderProcessGone is fatal`() {
        val f = Fixture()
        f.monitor.renderProcessGone("https://a", crashed = true, returnedTrue = false)
        val e = f.events.single { it.code == Code.RENDER_PROCESS_GONE }
        assertThat(e.severity).isEqualTo(Severity.FATAL)
        assertThat(e.message).contains("система убьёт приложение")
    }

    @Test
    fun `handled renderProcessGone is error but not fatal`() {
        val f = Fixture()
        f.monitor.renderProcessGone("https://a", crashed = true, returnedTrue = true)
        assertThat(f.events.single().severity).isEqualTo(Severity.ERROR)
        assertThat(f.monitor.snapshot().lastRenderGoneHandled).isTrue()
    }

    // --- NA#225: cookies не сброшены на диск --------------------------------

    @Test
    fun `missing cookie flush is warned`() {
        val f = Fixture()
        f.monitor.windowStopped("https://mail", cookiesFlushed = false)
        assertThat(f.events.single().code).isEqualTo(Code.COOKIE_FLUSH_MISSING)
    }

    @Test
    fun `flushed cookies produce nothing`() {
        val f = Fixture()
        f.monitor.windowStopped("https://mail", cookiesFlushed = true)
        assertThat(f.events).isEmpty()
    }

    // --- fullscreen ---------------------------------------------------------

    @Test
    fun `fullscreen left open for hours is warned`() {
        val f = Fixture()
        f.monitor.fullscreenEntered("https://video")
        f.advance(7L * 60 * 60 * 1000)
        f.monitor.sweep()
        assertThat(f.codes()).contains(Code.FULLSCREEN_CALLBACK_MISSING)
    }

    @Test
    fun `properly exited fullscreen is clean`() {
        val f = Fixture()
        f.monitor.fullscreenEntered(null)
        f.monitor.fullscreenExited()
        f.advance(10L * 60 * 60 * 1000)
        f.monitor.sweep()
        assertThat(f.errors()).isEmpty()
        assertThat(f.monitor.snapshot().fullscreenOpen).isFalse()
    }

    // --- формат NDJSON ------------------------------------------------------

    @Test
    fun `ndjson escapes quotes and newlines`() {
        val e = DiagEvent(
            tsMs = 42L,
            severity = Severity.ERROR,
            code = Code.FILE_CHOOSER_CALLBACK_LOST,
            webApp = "https://a",
            message = "он сказал \"привет\"\nи ушёл",
            fields = mapOf("url" to "https://a/?q=1&r=2"),
        )
        val line = e.toNdjson()
        assertThat(line).startsWith("{\"ts\":42,")
        assertThat(line).contains("\\\"привет\\\"")
        assertThat(line).contains("\\n")
        assertThat(line).contains("\"issue\":\"NA#33\"")
        assertThat(line).contains("\"f_url\":\"https://a/?q=1&r=2\"")
        assertThat(line).doesNotContain("\n")
    }

    @Test
    fun `every defect code carries either an issue or a checklist item`() {
        // Дисциплина: код диагностики без ссылки на реальный дефект или пункт
        // чек-листа — это догадка. Трассировочные коды исключены явным списком.
        val traceOnly = setOf(
            Code.APP_OPENED, Code.NAV_START, Code.NAV_COMMITTED, Code.NAV_FINISHED,
            Code.SETTINGS_APPLIED, Code.PROFILE_ATTACHED, Code.DOWNLOAD_ENQUEUED,
            Code.EXTERNAL_LINK_DELEGATED, Code.SSL_ERROR_TRUSTED_BY_USER,
            Code.UNCAUGHT_EXCEPTION, Code.ISOLATION_UNAVAILABLE,
            Code.ICON_SOURCE_USED,
        )
        val bad = Code.entries.filter {
            it !in traceOnly && it.knownIssue == null && it.checklist == null
        }
        assertThat(bad).isEmpty()
    }
}
