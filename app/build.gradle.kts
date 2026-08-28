plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.blooapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.blooapp"
        // minSdk 26 = покрытие ~96.1% устройств (apilevels.com, 28.08.2026).
        minSdk = 26
        // targetSdk 36 обязателен для публикации в Play с 31.08.2026.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Адрес коллектора диагностики. Используется только в debug-сборке;
        // в release транспорт выключен на уровне кода (см. DiagBootstrap).
        buildConfigField("String", "DIAG_ENDPOINT", "\"http://127.0.0.1:8799/ingest\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        // Явно, чтобы не зависеть от версии плагина: часть версий добавляет
        // src/*/kotlin сама, часть — нет.
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE*",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room экспортирует схему — это позволяет ревьюить миграции в диффе,
// а не узнавать о них после релиза.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-diag"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
