package dev.blooapp.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import dev.blooapp.R
import dev.blooapp.diag.DiagBootstrap
import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.Severity

/**
 * Скачивания через системный DownloadManager.
 *
 * Детали, из-за которых наивная реализация ломается (каталог PLAN.md §4.6):
 *  - имя файла не всегда есть в `Content-Disposition` — тогда выводим его из
 *    URL и MIME (Orbit отдельно чинил «APK-ссылки без Content-Disposition»);
 *  - cookies надо передать вручную, иначе скачивание из-под логина вернёт
 *    страницу входа вместо файла;
 *  - PDF: у WebView НЕТ встроенного просмотрщика, попытка открыть его внутри
 *    роняет приложение (NA#106). Отдаём в DownloadManager.
 */
object DownloadDelegate {

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        originKey: String?,
    ) {
        val fileName = resolveFileName(url, contentDisposition, mimeType)
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let {
                    addRequestHeader("Cookie", it)
                }
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                setTitle(fileName)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)

            DiagBootstrap.emit(
                DiagEvent(
                    System.currentTimeMillis(), Severity.TRACE, Code.DOWNLOAD_ENQUEUED,
                    originKey, "скачивание поставлено в очередь",
                    mapOf("file" to fileName, "id" to id.toString(), "size" to contentLength.toString()),
                )
            )
            Toast.makeText(
                context,
                context.getString(R.string.download_started, fileName),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Throwable) {
            DiagBootstrap.emit(
                DiagEvent(
                    System.currentTimeMillis(), Severity.ERROR, Code.DOWNLOAD_ENQUEUED,
                    originKey, "не удалось поставить скачивание: ${e.javaClass.simpleName}",
                    mapOf("url" to url),
                )
            )
            Toast.makeText(context, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Имя файла: из Content-Disposition, иначе из URL, иначе синтетическое с
     * расширением по MIME.
     */
    fun resolveFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val guessed = runCatching {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }.getOrNull()
        if (!guessed.isNullOrBlank() && guessed != "downloadfile.bin") return sanitize(guessed)

        val fromPath = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        if (!fromPath.isNullOrBlank() && fromPath.contains('.')) return sanitize(fromPath)

        val ext = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: "bin"
        return "download_${System.currentTimeMillis()}.$ext"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(180)
}
