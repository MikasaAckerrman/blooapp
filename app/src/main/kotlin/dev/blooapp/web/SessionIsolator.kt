package dev.blooapp.web

import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.blooapp.data.IsolationMode
import dev.blooapp.data.WebApp
import dev.webapps.diag.Code
import dev.webapps.diag.DiagEvent
import dev.webapps.diag.Severity

/**
 * Изоляция сессий: у каждого окна свои cookies, своё локальное хранилище,
 * свои разрешения. Это то, ради чего приложение существует — два аккаунта
 * одного сайта одновременно.
 *
 * Механизм — multi-profile API из androidx.webkit. Проверено на реальном
 * устройстве (vivo V2425A, WebView 150.0.7871.181): `MULTI_PROFILE = true`,
 * `multiprocess = true`. Профиль изолирует `CookieManager`, `WebStorage`,
 * `GeolocationPermissions` и `ServiceWorkerController`.
 *
 * Ограничения `WebViewCompat.setProfile`, каждое из которых бросает
 * `IllegalStateException` (проверено по javadoc androidx.webkit):
 *  - вызывать только ДО любой навигации;
 *  - до первого `evaluateJavascript`;
 *  - до первого `WebViewCompat.getProfile`;
 *  - не более одного раза на экземпляр WebView.
 *
 * Отсюда единственный безопасный порядок: создать WebView → [attach] →
 * применить настройки → `loadUrl`. Любой другой порядок — краш, поэтому
 * порядок зафиксирован в [attach] и в вызывающем коде.
 *
 * Требование доступности проверяется в рантайме, а не предполагается:
 * MULTI_PROFILE требует и поддержки в WebView APK, и включённого
 * многопроцессного режима, который пользователь может отключить в настройках
 * разработчика.
 */
object SessionIsolator {

    /** Почему изоляция недоступна — нужно для честного сообщения в UI. */
    enum class Unavailable {
        /** Всё в порядке, изоляция доступна. */
        NONE,

        /** Версия WebView не умеет multi-profile (нужен WebView M119+). */
        WEBVIEW_TOO_OLD,

        /** Многопроцессный режим WebView выключен пользователем. */
        MULTIPROCESS_DISABLED,
    }

    /** Доступна ли изоляция на этом устройстве прямо сейчас. */
    fun availability(): Unavailable = when {
        !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) -> {
            // Различаем две причины: пользователю они означают разное.
            // «Обновите WebView» и «включите многопроцессный режим» — разные
            // действия, и путать их нельзя.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROCESS) &&
                !runCatching { WebViewCompat.isMultiProcessEnabled() }.getOrDefault(true)
            ) {
                Unavailable.MULTIPROCESS_DISABLED
            } else {
                Unavailable.WEBVIEW_TOO_OLD
            }
        }
        else -> Unavailable.NONE
    }

    fun isAvailable(): Boolean = availability() == Unavailable.NONE

    /**
     * Результат попытки прикрепить профиль. Возвращается, а не бросается:
     * невозможность изоляции — это состояние, о котором надо сказать
     * пользователю, а не аварийная ситуация.
     */
    sealed interface Attached {
        /** Профиль прикреплён, сессия изолирована. */
        data class Isolated(val profileName: String) : Attached

        /** Работаем в общей сессии. */
        data class Shared(val reason: Unavailable) : Attached
    }

    /**
     * Прикрепить профиль к WebView. Вызывать СРАЗУ после создания WebView,
     * до применения настроек и до навигации.
     */
    fun attach(webView: WebView, app: WebApp, diag: (DiagEvent) -> Unit): Attached {
        val profileName = app.profileName
        if (app.isolationMode == IsolationMode.SHARED || profileName == null) {
            return Attached.Shared(Unavailable.NONE)
        }

        val availability = availability()
        if (availability != Unavailable.NONE) {
            diag(
                DiagEvent(
                    System.currentTimeMillis(), Severity.WARN, Code.ISOLATION_UNAVAILABLE,
                    app.originKey,
                    "изоляция недоступна ($availability) — окно работает в общей сессии",
                    mapOf("profile" to profileName, "reason" to availability.name),
                )
            )
            return Attached.Shared(availability)
        }

        return try {
            // Создаёт профиль, если его ещё нет.
            WebViewCompat.setProfile(webView, profileName)
            diag(
                DiagEvent(
                    System.currentTimeMillis(), Severity.TRACE, Code.PROFILE_ATTACHED,
                    app.originKey, "профиль прикреплён", mapOf("profile" to profileName),
                )
            )
            Attached.Isolated(profileName)
        } catch (e: Throwable) {
            // Нарушение порядка вызовов или отсутствие фичи. Это баг в нашем
            // коде, и он должен быть виден, а не проглочен.
            diag(
                DiagEvent(
                    System.currentTimeMillis(), Severity.ERROR, Code.ISOLATION_MODE_MISMATCH,
                    app.originKey,
                    "setProfile упал: ${e.javaClass.simpleName}: ${e.message}",
                    mapOf("profile" to profileName),
                )
            )
            Attached.Shared(Unavailable.NONE)
        }
    }

    /** Список профилей, известных WebView. Для экрана диагностики. */
    fun knownProfiles(): List<String> = runCatching {
        if (!isAvailable()) return emptyList()
        ProfileStore.getInstance().getAllProfileNames()
    }.getOrDefault(emptyList())

    /**
     * Удалить данные профиля (выйти из аккаунта и стереть следы).
     *
     * Последовательность важна и вытекает из документации: `deleteProfile`
     * бросает `IllegalStateException`, если на профиле есть живые WebView ИЛИ
     * профиль загружен в память через `getProfile`/`getOrCreateProfile`.
     * Поэтому:
     *  1. окно экземпляра должно быть закрыто вызывающим кодом ДО этого метода;
     *  2. сначала пробуем полное удаление;
     *  3. если профиль всё ещё «занят» — чистим содержимое через его
     *     CookieManager/WebStorage, что даёт тот же результат для пользователя.
     *
     * @return true, если данные удалены (полностью или через очистку)
     */
    fun wipe(profileName: String, diag: (DiagEvent) -> Unit): Boolean {
        if (!isAvailable()) return false
        if (profileName == Profile.DEFAULT_PROFILE_NAME) {
            // Профиль по умолчанию удалить нельзя — это IllegalArgumentException.
            return clearInPlace(profileName, diag)
        }
        return try {
            val deleted = ProfileStore.getInstance().deleteProfile(profileName)
            diag(
                DiagEvent(
                    System.currentTimeMillis(), Severity.TRACE, Code.PROFILE_ATTACHED, null,
                    if (deleted) "профиль удалён" else "профиля не было",
                    mapOf("profile" to profileName),
                )
            )
            deleted
        } catch (e: IllegalStateException) {
            // Профиль загружен или используется — чистим содержимое.
            clearInPlace(profileName, diag)
        } catch (e: Throwable) {
            diag(
                DiagEvent(
                    System.currentTimeMillis(), Severity.WARN, Code.ISOLATION_MODE_MISMATCH, null,
                    "не удалось удалить профиль: ${e.javaClass.simpleName}",
                    mapOf("profile" to profileName),
                )
            )
            false
        }
    }

    /** Очистка содержимого профиля, когда удалить его нельзя. */
    private fun clearInPlace(profileName: String, diag: (DiagEvent) -> Unit): Boolean = try {
        val profile = ProfileStore.getInstance().getProfile(profileName)
        if (profile == null) {
            false
        } else {
            profile.getCookieManager().removeAllCookies(null)
            profile.getCookieManager().flush()
            profile.getWebStorage().deleteAllData()
            profile.getGeolocationPermissions().clearAll()
            diag(
                DiagEvent(
                    System.currentTimeMillis(), Severity.TRACE, Code.PROFILE_ATTACHED, null,
                    "профиль занят — данные очищены на месте",
                    mapOf("profile" to profileName),
                )
            )
            true
        }
    } catch (e: Throwable) {
        diag(
            DiagEvent(
                System.currentTimeMillis(), Severity.WARN, Code.ISOLATION_MODE_MISMATCH, null,
                "очистка профиля не удалась: ${e.javaClass.simpleName}",
                mapOf("profile" to profileName),
            )
        )
        false
    }
}
