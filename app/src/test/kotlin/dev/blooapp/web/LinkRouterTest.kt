package dev.blooapp.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Тесты маршрутизации ссылок.
 *
 * Robolectric нужен только из-за `Uri.parse` — своей реализации разбора не
 * пишем, потому что расхождение с платформенной было бы источником именно тех
 * дефектов, которые мы ловим.
 *
 * Каждый тест назван по дефекту, который предотвращает.
 */
@RunWith(RobolectricTestRunner::class)
class LinkRouterTest {

    private val host = "example.com"

    private fun route(url: String, external: Boolean = true, redirect: Boolean = false) =
        LinkRouter.route(url, host, external, redirect)

    // --- своё/чужое ---------------------------------------------------------

    @Test
    fun `same host stays in app`() {
        assertThat(route("https://example.com/page"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `subdomain stays in app`() {
        // m.example.com — тот же сайт, выкидывать наружу нельзя.
        assertThat(route("https://m.example.com/page"))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `parent domain stays in app`() {
        val d = LinkRouter.route("https://example.com/x", "www.example.com", true)
        assertThat(d).isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    @Test
    fun `external domain goes to custom tab when enabled`() {
        val d = route("https://other.org/page")
        assertThat(d).isInstanceOf(LinkRouter.Decision.OpenInCustomTab::class.java)
        assertThat((d as LinkRouter.Decision.OpenInCustomTab).reason)
            .isEqualTo(LinkRouter.Reason.EXTERNAL_DOMAIN)
    }

    @Test
    fun `external domain stays in app when setting is off`() {
        assertThat(route("https://other.org/page", external = false))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    // --- NA#172: редирект SSO нельзя выбрасывать наружу ---------------------

    @Test
    fun `redirect to foreign host stays in app — issue 172`() {
        // Цепочка входа через SSO на отдельном домене должна продолжаться
        // внутри окна, иначе вход разрывается.
        assertThat(route("https://sso.corp.net/login", redirect = true))
            .isInstanceOf(LinkRouter.Decision.KeepInApp::class.java)
    }

    // --- OAuth: WebView запрещён Google с 2016 ------------------------------

    @Test
    fun `google oauth always goes to custom tab`() {
        for (u in listOf(
            "https://accounts.google.com/o/oauth2/auth?x=1",
            "https://oauth2.googleapis.com/token",
            "https://signin.google.com/x",
        )) {
            val d = route(u)
            assertThat(d).isInstanceOf(LinkRouter.Decision.OpenInCustomTab::class.java)
            assertThat((d as LinkRouter.Decision.OpenInCustomTab).reason)
                .isEqualTo(LinkRouter.Reason.OAUTH)
        }
    }

    @Test
    fun `oauth wins over redirect and over external setting`() {
        val d = LinkRouter.route(
            "https://accounts.google.com/signin", host, externalOutside = false, isRedirect = true,
        )
        assertThat(d).isInstanceOf(LinkRouter.Decision.OpenInCustomTab::class.java)
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
    fun `isSameSite handles empty app host`() {
        assertThat(LinkRouter.isSameSite("anything.com", "")).isTrue()
    }

    @Test
    fun `sibling subdomains are not the same site`() {
        assertThat(LinkRouter.isSameSite("a.example.com", "b.example.com")).isFalse()
    }
}
