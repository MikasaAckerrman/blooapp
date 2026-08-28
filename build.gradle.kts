// Корневой build-скрипт.
//
// Плагины объявлены с apply false, чтобы зафиксировать версии в одном месте;
// применяются они в модулях. :core-model и :core-diag — чистый Kotlin/JVM,
// они собираются и тестируются в песочнице разработки без Android SDK.
// :app — единственный модуль, которому нужен SDK; он собирается в CI.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}
