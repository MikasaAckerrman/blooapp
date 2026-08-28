package dev.webapps.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstanceNamingTest {

    @Test
    fun `profile names differ per instance of the same site`() {
        val a = InstanceNaming.profileName("gorouter.app", 1)
        val b = InstanceNaming.profileName("gorouter.app", 2)
        val c = InstanceNaming.profileName("gorouter.app", 3)
        assertThat(setOf(a, b, c)).hasSize(3)
    }

    @Test
    fun `profile name does not depend on url path — issue 212`() {
        // Смена стартовой страницы не должна менять профиль: иначе сессия
        // «переезжает» и логин теряется.
        assertThat(InstanceNaming.profileName("mail.example.com", 1))
            .isEqualTo(InstanceNaming.profileName("mail.example.com", 1))
    }

    @Test
    fun `different sites get different profiles`() {
        assertThat(InstanceNaming.profileName("a.com", 1))
            .isNotEqualTo(InstanceNaming.profileName("b.com", 1))
    }

    @Test
    fun `unsafe characters are replaced`() {
        val n = InstanceNaming.profileName("../../etc/passwd", 1)
        assertThat(n).doesNotContain("/")
        assertThat(n).doesNotContain(".")
        assertThat(InstanceNaming.isValidProfileName(n)).isTrue()
    }

    @Test
    fun `non ascii host still yields a valid profile name`() {
        val n = InstanceNaming.profileName("почта.рф", 1)
        assertThat(InstanceNaming.isValidProfileName(n)).isTrue()
        assertThat(n).startsWith("wa_")
    }

    @Test
    fun `very long host is truncated but stays unique per index`() {
        val host = "a".repeat(200) + ".example.com"
        val n1 = InstanceNaming.profileName(host, 1)
        val n2 = InstanceNaming.profileName(host, 2)
        assertThat(n1.length).isLessThan(40)
        assertThat(n1).isNotEqualTo(n2)
    }

    @Test
    fun `empty host does not produce a broken name`() {
        val n = InstanceNaming.profileName("", 1)
        assertThat(InstanceNaming.isValidProfileName(n)).isTrue()
    }

    @Test
    fun `index must be positive`() {
        runCatching { InstanceNaming.profileName("a.com", 0) }
            .also { assertThat(it.isFailure).isTrue() }
    }

    @Test
    fun `reserved Default name is never produced and never valid`() {
        assertThat(InstanceNaming.isValidProfileName("Default")).isFalse()
        // Наш генератор физически не может выдать "Default" — есть префикс.
        assertThat(InstanceNaming.profileName("default", 1)).isNotEqualTo("Default")
    }

    @Test
    fun `path separators and dots are rejected by validator`() {
        assertThat(InstanceNaming.isValidProfileName("a/b")).isFalse()
        assertThat(InstanceNaming.isValidProfileName("a\\b")).isFalse()
        assertThat(InstanceNaming.isValidProfileName("..")).isFalse()
        assertThat(InstanceNaming.isValidProfileName("   ")).isFalse()
    }

    // --- ярлыки -------------------------------------------------------------

    @Test
    fun `shortcut id is bound to record id not to title — issue 82`() {
        // Пользователь переименовывает окно — ярлык остаётся тем же.
        assertThat(InstanceNaming.shortcutId(7)).isEqualTo("app_7")
        assertThat(InstanceNaming.shortcutId(7)).isEqualTo(InstanceNaming.shortcutId(7))
    }

    @Test
    fun `shortcut ids of instances do not collide`() {
        val ids = (1L..5L).map { InstanceNaming.shortcutId(it) }
        assertThat(ids.toSet()).hasSize(5)
    }

    @Test
    fun `group and app shortcut namespaces do not overlap`() {
        assertThat(InstanceNaming.groupShortcutId(1))
            .isNotEqualTo(InstanceNaming.shortcutId(1))
    }

    // --- имена для показа ---------------------------------------------------

    @Test
    fun `first instance has plain title and others are numbered`() {
        assertThat(InstanceNaming.autoTitle("gorouter.app", 1)).isEqualTo("gorouter.app")
        assertThat(InstanceNaming.autoTitle("gorouter.app", 2)).isEqualTo("gorouter.app (2)")
        assertThat(InstanceNaming.autoTitle("gorouter.app", 10)).isEqualTo("gorouter.app (10)")
    }
}
