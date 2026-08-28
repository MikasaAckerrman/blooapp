pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "blooapp"

// Чистые Kotlin/JVM-модули: собираются и тестируются где угодно, включая
// среду разработки без Android SDK.
include(":core-model")
include(":core-diag")

// Android-модуль подключается только там, где есть SDK.
//
// Причина не в удобстве. Среда, в которой пишется этот код, — Alpine aarch64,
// а у Google нет сборок Android build-tools под linux+aarch64 (проверено по
// репозиторию SDK: ноль архивов с host-os=linux + host-arch=aarch64). Без
// этого условия Gradle пытался бы сконфигурировать :app и падал бы ещё до
// запуска тестов чистых модулей — то есть быстрая петля обратной связи
// ломалась бы полностью.
//
// В CI (ubuntu-latest) SDK есть, ANDROID_HOME задан, и :app подключается.
// Локально можно принудить через BLOOAPP_WITH_APP=1.
val sdkDir = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties")
        .takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("=")

val withApp = sdkDir != null || System.getenv("BLOOAPP_WITH_APP") == "1"
if (withApp) {
    include(":app")
} else {
    logger.lifecycle(
        "Android SDK не найден — модуль :app пропущен. " +
            "Тесты :core-model и :core-diag выполняются как обычно."
    )
}
