// Корневой build-скрипт.
// Android-плагины объявлены, но НЕ применяются здесь: модули :core-model и
// :core-diag — чистый Kotlin/JVM, они собираются и тестируются в песочнице
// без Android SDK. Модуль :app появится позже и будет единственным, кому
// нужен SDK (собирается в CI).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
