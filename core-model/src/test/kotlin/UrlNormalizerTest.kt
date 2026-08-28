package dev.webapps.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Каждый тест назван по дефекту, который он предотвращает.
 * Номера #NN — issues в трекере cylonid/NativeAlphaForAndroid.
 */
class UrlNormalizerTest {

    private fun valid(raw: String): UrlNormalizer.NormalizedUrl {
        val r = UrlNormalizer.normalize(raw)
        assertThat(r).isInstanceOf(UrlNormalizer.Result.Valid::class.java)
        return (r as UrlNormalizer.Result.Valid).url
    }

    private fun invalid(raw: String): UrlNormalizer.Reason {
        val r = UrlNormalizer.normalize(raw)
        assertThat(r).isInstanceOf(UrlNormalizer.Result.Invalid::class.java)
        return (r as UrlNormalizer.Result.Invalid).reason
    }

    // --- #48: LAN-адрес без TLD отвергался как «невалидный» -----------------

    @Test
    fun `hostname without dot is valid — issue 48`() {
        val u = valid("https://nas/some-path")
        assertThat(u.host).isEqualTo("nas")
        assertThat(u.path).isEqualTo("/some-path")
        assertThat(u.isLocal).isTrue()
    }

    @Test
    fun `ip with port is valid`() {
        val u = valid("http://192.168.1.10:8080")
        assertThat(u.host).isEqualTo("192.168.1.10")
        assertThat(u.port).isEqualTo(8080)
        assertThat(u.isLocal).isTrue()
        assertThat(u.full).isEqualTo("http://192.168.1.10:8080/")
    }

    @Test
    fun `localhost with port is local`() {
        assertThat(valid("http://localhost:3000/app").isLocal).isTrue()
    }

    @Test
    fun `public host is not local`() {
        assertThat(valid("https://news.ycombinator.com").isLocal).isFalse()
    }

    @Test
    fun `private ranges are local`() {
        assertThat(UrlNormalizer.isLocalHost("10.0.0.5")).isTrue()
        assertThat(UrlNormalizer.isLocalHost("172.16.4.1")).isTrue()
        assertThat(UrlNormalizer.isLocalHost("172.32.4.1")).isFalse()
        assertThat(UrlNormalizer.isLocalHost("8.8.8.8")).isFalse()
    }

    // --- #22: URI приводился к нижнему регистру целиком ---------------------

    @Test
    fun `host is lowercased but path keeps case — issue 22`() {
        val u = valid("HTTPS://Example.COM/Path/To/File")
        assertThat(u.host).isEqualTo("example.com")
        assertThat(u.path).isEqualTo("/Path/To/File")
    }

    // --- Нормализация, от которой зависит ключ профиля (§1.3) ---------------

    @Test
    fun `missing scheme defaults to https`() {
        assertThat(valid("example.com").scheme).isEqualTo("https")
    }

    @Test
    fun `empty path becomes slash`() {
        assertThat(valid("https://example.com").full).isEqualTo("https://example.com/")
    }

    @Test
    fun `trailing dot in host is stripped`() {
        assertThat(valid("https://example.com./").host).isEqualTo("example.com")
    }

    @Test
    fun `whitespace around input is trimmed`() {
        assertThat(valid("   https://example.com/  ").host).isEqualTo("example.com")
    }

    @Test
    fun `query without path is preserved`() {
        val u = valid("https://example.com?a=1")
        assertThat(u.path).isEqualTo("/?a=1")
    }

    @Test
    fun `userinfo does not leak into host`() {
        assertThat(valid("https://user:pass@example.com/x").host).isEqualTo("example.com")
    }

    @Test
    fun `ipv6 in brackets is parsed`() {
        val u = valid("http://[::1]:8080/x")
        assertThat(u.host).isEqualTo("[::1]")
        assertThat(u.port).isEqualTo(8080)
    }

    @Test
    fun `bare ipv6 without brackets keeps no port`() {
        val u = valid("http://[2001:db8::1]/")
        assertThat(u.host).isEqualTo("[2001:db8::1]")
        assertThat(u.port).isNull()
    }

    // --- #212: смена стартового URL не должна менять идентичность ----------

    @Test
    fun `originKey ignores path — issue 212`() {
        val a = valid("https://mail.example.com/inbox")
        val b = valid("https://mail.example.com/settings/profile")
        assertThat(UrlNormalizer.originKey(a)).isEqualTo(UrlNormalizer.originKey(b))
    }

    @Test
    fun `originKey separates ports`() {
        val a = valid("http://nas:8080/")
        val b = valid("http://nas:9090/")
        assertThat(UrlNormalizer.originKey(a)).isNotEqualTo(UrlNormalizer.originKey(b))
    }

    // --- Отказы -------------------------------------------------------------

    @Test
    fun `empty input is rejected`() {
        assertThat(invalid("   ")).isEqualTo(UrlNormalizer.Reason.EMPTY)
    }

    @Test
    fun `non http scheme is rejected`() {
        assertThat(invalid("ftp://example.com")).isEqualTo(UrlNormalizer.Reason.UNSUPPORTED_SCHEME)
        assertThat(invalid("javascript:alert(1)")).isEqualTo(UrlNormalizer.Reason.UNSUPPORTED_SCHEME)
        assertThat(invalid("file:///etc/passwd")).isEqualTo(UrlNormalizer.Reason.UNSUPPORTED_SCHEME)
        assertThat(invalid("data:text/html,<script>1</script>"))
            .isEqualTo(UrlNormalizer.Reason.UNSUPPORTED_SCHEME)
        assertThat(invalid("intent://x#Intent;end")).isEqualTo(UrlNormalizer.Reason.UNSUPPORTED_SCHEME)
    }

    @Test
    fun `host with port is not mistaken for a scheme`() {
        // `example.com:8080/x` не должно разбираться как схема `example.com`.
        val u = valid("example.com:8080/x")
        assertThat(u.scheme).isEqualTo("https")
        assertThat(u.host).isEqualTo("example.com")
        assertThat(u.port).isEqualTo(8080)
        assertThat(u.path).isEqualTo("/x")
    }

    @Test
    fun `bad port is rejected`() {
        assertThat(invalid("http://example.com:70000/")).isEqualTo(UrlNormalizer.Reason.BAD_PORT)
        assertThat(invalid("http://example.com:abc/")).isEqualTo(UrlNormalizer.Reason.BAD_PORT)
    }

    @Test
    fun `missing host is rejected`() {
        assertThat(invalid("https://")).isEqualTo(UrlNormalizer.Reason.NO_HOST)
    }
}
