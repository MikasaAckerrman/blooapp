package dev.webapps.model

/**
 * Нормализация и валидация адреса веб-приложения.
 *
 * Почему это отдельный, отдельно тестируемый файл, а не пара строк в UI:
 * от результата [normalize] зависят имя профиля изоляции, ключ ярлыка и
 * идентификатор задачи в «Недавних». Ошибка здесь = потеря сессии у живого
 * пользователя, что необратимо (см. PLAN.md §1.3).
 *
 * Требования, вытянутые из реальных дефектов (PLAN.md §4.9):
 *  - LAN-адреса без точки в имени хоста ДОЛЖНЫ проходить: `https://nas/path`
 *    (в Native Alpha такой адрес отвергался как «невалидный», issue #48);
 *  - IP, порт, `localhost`, `http://` для локальных адресов — валидны;
 *  - регистр хоста нормализуется, регистр пути — НЕТ (пути чувствительны
 *    к регистру, issue #22 в Native Alpha: «URI converted to lowercase»);
 *  - baseUrl фиксируется по тому, что ввёл пользователь, и НЕ переписывается
 *    по редиректам (issue #172: SSO на поддомене подменял базовый адрес).
 */
object UrlNormalizer {

    /** Схемы, которые мы вообще готовы открывать как веб-приложение. */
    private val ALLOWED_SCHEMES = setOf("http", "https")

    sealed interface Result {
        data class Valid(val url: NormalizedUrl) : Result
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason {
        EMPTY,
        UNSUPPORTED_SCHEME,
        NO_HOST,
        BAD_PORT,
        BAD_HOST_CHARS,
    }

    /**
     * Разобранный и нормализованный адрес.
     *
     * @param full     готовый к загрузке URL
     * @param scheme   всегда в нижнем регистре
     * @param host     всегда в нижнем регистре, без завершающей точки
     * @param port     null, если порт не указан явно
     * @param isLocal  адрес выглядит как локальный/LAN — от этого зависят
     *                 дефолты (mixed content, предупреждение о сертификате)
     */
    data class NormalizedUrl(
        val full: String,
        val scheme: String,
        val host: String,
        val port: Int?,
        val path: String,
        val isLocal: Boolean,
    ) {
        /** Хост с портом — то, как адрес показывается пользователю. */
        val authority: String get() = if (port == null) host else "$host:$port"
    }

    fun normalize(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.EMPTY)

        // Схему определяем ДО всего остального, иначе `javascript:alert(1)`
        // разбирается как хост `javascript` с портом `alert(1)` и отвергается
        // не по той причине. Опасные схемы обязаны отсекаться как схемы.
        val explicitScheme = detectScheme(trimmed)
        if (explicitScheme != null && explicitScheme !in ALLOWED_SCHEMES) {
            return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
        }

        // Схема отсутствует — подставляем https, а не отвергаем ввод.
        val withScheme = if (explicitScheme != null) trimmed else "https://$trimmed"

        val schemeEnd = withScheme.indexOf("://")
        if (schemeEnd <= 0) return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
        val scheme = withScheme.substring(0, schemeEnd).lowercase()
        if (scheme !in ALLOWED_SCHEMES) return Result.Invalid(Reason.UNSUPPORTED_SCHEME)

        val rest = withScheme.substring(schemeEnd + 3)
        if (rest.isEmpty()) return Result.Invalid(Reason.NO_HOST)

        // Отделяем authority от пути/запроса/фрагмента.
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd == -1) rest else rest.substring(0, authorityEnd)
        val tail = if (authorityEnd == -1) "" else rest.substring(authorityEnd)

        // userinfo (user:pass@host) отбрасываем из host, но сохраняем в full.
        val hostPart = authority.substringAfterLast('@')
        if (hostPart.isEmpty()) return Result.Invalid(Reason.NO_HOST)

        val (hostRaw, portRaw) = splitHostPort(hostPart)
            ?: return Result.Invalid(Reason.BAD_PORT)

        val host = hostRaw.lowercase().trimEnd('.')
        if (host.isEmpty()) return Result.Invalid(Reason.NO_HOST)
        if (!HOST_CHARS.matches(host)) return Result.Invalid(Reason.BAD_HOST_CHARS)

        val port = when {
            portRaw == null -> null
            else -> portRaw.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: return Result.Invalid(Reason.BAD_PORT)
        }

        // Путь: пустой приводим к "/", регистр НЕ трогаем.
        val path = when {
            tail.isEmpty() -> "/"
            tail.startsWith("?") || tail.startsWith("#") -> "/$tail"
            else -> tail
        }

        val authorityOut = if (port == null) host else "$host:$port"
        val full = "$scheme://$authorityOut$path"

        return Result.Valid(
            NormalizedUrl(
                full = full,
                scheme = scheme,
                host = host,
                port = port,
                path = path,
                isLocal = isLocalHost(host),
            )
        )
    }

    /**
     * Ключ для идентификации веб-приложения: схема + authority.
     * Путь в ключ НЕ входит — иначе смена стартовой страницы порождала бы
     * новое приложение и теряла сессию (issue #212).
     */
    fun originKey(url: NormalizedUrl): String = "${url.scheme}://${url.authority}"

    /**
     * Локальный ли адрес. Нужно для дефолтов: на локальных адресах
     * разумно разрешить mixed content и предлагать доверие самоподписанному
     * сертификату, на публичных — нет.
     */
    fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "127.0.0.1" || host.startsWith("127.")) return true
        if (host == "::1" || host == "[::1]") return true
        if (host.endsWith(".local") || host.endsWith(".home") || host.endsWith(".lan")) return true
        // Имя хоста без точки — почти всегда машина в локальной сети (`https://nas`).
        if (!host.contains('.') && !host.startsWith("[")) return true
        privateIpv4(host)?.let { return it }
        return false
    }

    /** true/false для приватных диапазонов IPv4, null — это не IPv4. */
    private fun privateIpv4(host: String): Boolean? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it !in 0..255 }) return null
        val (a, b) = nums[0] to nums[1]
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    /**
     * Делит `host[:port]`, корректно обрабатывая IPv6 в скобках.
     * Возвращает null, если синтаксис порта заведомо сломан.
     */
    private fun splitHostPort(s: String): Pair<String, String?>? {
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close == -1) return null
            val host = s.substring(0, close + 1)
            val after = s.substring(close + 1)
            return when {
                after.isEmpty() -> host to null
                after.startsWith(":") -> host to after.substring(1)
                else -> null
            }
        }
        val colon = s.lastIndexOf(':')
        if (colon == -1) return s to null
        // Несколько двоеточий без скобок — это голый IPv6, порта тут нет.
        if (s.count { it == ':' } > 1) return s to null
        return s.substring(0, colon) to s.substring(colon + 1)
    }

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * Возвращает схему в нижнем регистре, если она указана явно, иначе null.
     * Распознаёт и `scheme://` (иерархические), и `scheme:` (opaque, вроде
     * `javascript:` и `mailto:`) — последние обязаны отсекаться как
     * неподдерживаемые схемы, а не разбираться как хост.
     */
    private fun detectScheme(s: String): String? {
        SCHEME_PREFIX.find(s)?.let { return it.value.dropLast(3).lowercase() }
        val colon = s.indexOf(':')
        if (colon <= 0) return null
        val candidate = s.substring(0, colon)
        if (!SCHEME_NAME.matches(candidate)) return null
        // `example.com:8080/path` — это не схема, а хост с портом.
        val after = s.substring(colon + 1)
        if (after.isNotEmpty() && after.all { it.isDigit() }) return null
        if (after.takeWhile { it.isDigit() }.isNotEmpty() &&
            after.dropWhile { it.isDigit() }.firstOrNull() in setOf('/', '?', '#')
        ) return null
        return candidate.lowercase()
    }

    private val SCHEME_NAME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*$")

    // Буквы/цифры/дефис/точка/подчёркивание, плюс IPv6 в скобках и
    // не-ASCII (IDN) — валидацию punycode оставляем платформе.
    private val HOST_CHARS = Regex("^(\\[[0-9a-f:.]+]|[^\\s/?#@:\\\\]+)$")
}
