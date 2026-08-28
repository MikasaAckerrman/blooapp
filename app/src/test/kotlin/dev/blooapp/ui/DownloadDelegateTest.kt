package dev.blooapp.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Имя скачиваемого файла.
 *
 * Дефект, который это предотвращает: ссылки без заголовка
 * `Content-Disposition` (Orbit отдельно чинил такие APK-ссылки) — файл
 * сохранялся с бессмысленным именем или скачивание вообще не начиналось.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadDelegateTest {

    @Test
    fun `name from content disposition wins`() {
        val n = DownloadDelegate.resolveFileName(
            url = "https://a.com/get?id=7",
            contentDisposition = "attachment; filename=\"report.pdf\"",
            mimeType = "application/pdf",
        )
        assertThat(n).isEqualTo("report.pdf")
    }

    @Test
    fun `name falls back to url path when disposition is absent`() {
        val n = DownloadDelegate.resolveFileName(
            url = "https://f-droid.org/repo/app.apk",
            contentDisposition = null,
            mimeType = "application/vnd.android.package-archive",
        )
        assertThat(n).endsWith(".apk")
    }

    @Test
    fun `synthetic name uses mime extension when url has no filename`() {
        val n = DownloadDelegate.resolveFileName(
            url = "https://a.com/download",
            contentDisposition = null,
            mimeType = "image/png",
        )
        assertThat(n).endsWith(".png")
    }

    @Test
    fun `path separators are stripped from the name`() {
        val n = DownloadDelegate.resolveFileName(
            url = "https://a.com/x",
            contentDisposition = "attachment; filename=\"../../etc/passwd\"",
            mimeType = null,
        )
        assertThat(n).doesNotContain("/")
        assertThat(n).doesNotContain("\\")
    }

    @Test
    fun `very long names are truncated`() {
        val long = "a".repeat(400) + ".txt"
        val n = DownloadDelegate.resolveFileName(
            url = "https://a.com/x",
            contentDisposition = "attachment; filename=\"$long\"",
            mimeType = "text/plain",
        )
        assertThat(n.length).isAtMost(180)
    }

    @Test
    fun `name is never blank`() {
        val n = DownloadDelegate.resolveFileName("https://a.com", null, null)
        assertThat(n).isNotEmpty()
    }
}
