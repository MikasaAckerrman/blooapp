package dev.blooapp.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Тесты маршрутизации ссылок.
 *
 * Главный инвариант, добытый из реального дефекта: **ссылка по умолчанию
 * остаётся внутри окна**. Открыть её в системном браузере — значит уйти в
 * чужие cookies, и изолированная сессия окна перестаёт существовать.
 *
 * Robolectric нужен только из-за `Uri.parse`: своя реализация разбора
 * расходилась бы с платформенной, а это источник ровно тех дефектов, которые
 * мы ловим.
 */
@RunWith(RobolectricTestRunner::class)
class LinkRouterTest {

    private val host = "example.com"

    private fun route(
        url: String,
        external: Boolean = false,
        googleOutside: Boolean = false,
        redirect: Boolean = false,
    ) = LinkRouter.route(url, host, external, googleOutside, redirect)

    // --- ГЛАВНОЕ: чужой домен по умолчанию остаётся внутри -------------------

    @Test
    fun `foreign domain stays in app by default`() {
        // Уйти в Custom Tab значит уйти в браузер пользователя с его логинами.
        // Изоляция окна этого не переживёт.
        assertThat(route("https://other.org/page"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `foreign domain goes outside only when explicitly enabled`() {
        val d = route("https://other.org/page", external = true)
        assertThat(d).isInstanceOf(LinkRouter.Decision.OpenInCustomTab::class.java)
        assertThat((d as LinkRouter.Decision.OpenInCustomTab).reason)
            .isEqualTo(LinkRouter.Reason.EXTERNAL_DOMAIN)
    }

    // --- OAuth: спрашиваем, а не уводим молча -------------------------------

    @Test
    fun `google login asks for consent by default`() {
        val d = route("https://accounts.google.com/o/oauth2/auth?x=1")
        assertThat(d).isInstanceOf(LinkRouter.Decision.NeedsExternalLoginConsent::class.java)
    }

    @Test
    fun `google login goes to browser once user allowed it`() {
        val d = route("https://accounts.google.com/o/oauth2/auth", googleOutside = true)
        assertThat(d).isInstanceOf(LinkRouter.Decision.OpenInCustomTab::class.java)
        assertThat((d as LinkRouter.Decision.OpenInCustomTab).reason)
            .isEqualTo(LinkRouter.Reason.OAUTH)
    }

    @Test
    fun `all known oauth hosts are recognised`() {
        for (u in listOf(
            "https://accounts.google.com/signin",
            "https://oauth2.googleapis.com/token",
            "https://signin.google.com/x",
            "https://myaccount.google.com/y",
        )) {
            assertThat(route(u))
                .isInstanceOf(LinkRouter.Decision.NeedsExternalLoginConsent::class.java)
        }
    }

    @Test
    fun `oauth decision wins over redirect flag`() {
        assertThat(route("https://accounts.google.com/signin", redirect = true))
            .isInstanceOf(LinkRouter.Decision.NeedsExternalLoginConsent::class.java)
    }

    // --- своё/чужое ---------------------------------------------------------

    @Test
    fun `same host stays in app`() {
        assertThat(route("https://example.com/page"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `subdomain stays in app`() {
        assertThat(route("https://m.example.com/page"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `parent domain stays in app`() {
        assertThat(LinkRouter.route("https://example.com/x", "www.example.com"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    // --- NA#172: редирект SSO нельзя выбрасывать наружу ---------------------

    @Test
    fun `redirect to foreign host stays in app — issue 172`() {
        assertThat(route("https://sso.corp.net/login", external = true, redirect = true))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    // --- NA#177: ERR_UNKNOWN_URL_SCHEME ------------------------------------

    @Test
    fun `unknown app scheme is ignored silently — issue 177`() {
        val d = route("fb-messenger://threads?vcuid=1")
        assertThat(d).isInstanceOf(LinkRouter.Decision.Ignore::class.java)
        assertThat((d as LinkRouter.Decision.Ignore).scheme).isEqualTo("fb-messenger")
    }

    @Test
    fun `system schemes go to the system handler`() {
        for (u in listOf("tel:+79001234567", "mailto:a@b.c", "geo:55.7,37.6", "market://details?id=x")) {
            assertThat(route(u)).isInstanceOf(LinkRouter.Decision.OpenExternally::class.java)
        }
    }

    @Test
    fun `intent scheme goes to the system handler`() {
        assertThat(route("intent://scan/#Intent;scheme=zxing;end"))
            .isInstanceOf(LinkRouter.Decision.OpenExternally::class.java)
    }

    // --- граничные случаи ---------------------------------------------------

    @Test
    fun `host case and trailing dot do not matter`() {
        assertThat(route("https://EXAMPLE.com./page"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `url without host stays in app`() {
        assertThat(route("https:///page"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `relative url without scheme stays in app`() {
        assertThat(route("/inner/page"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `isSameSite handles empty app host`() {
        assertThat(LinkRouter.isSameSite("anything.com", "")).isTrue()
    }

    @Test
    fun `sibling subdomains are not the same site`() {
        assertThat(LinkRouter.isSameSite("a.example.com", "b.example.com")).isFalse()
    }
}
