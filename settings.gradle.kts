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

rootProject.name = "webapps"

// Чистые Kotlin/JVM-модули — тестируются локально в песочнице.
include(":core-model")
include(":core-diag")

// :app добавится на этапе 1 (нужен Android SDK, собирается в CI).
