plugins { id("com.android.application") }

// AGP 9 has built-in Kotlin support; no separate kotlin-android plugin is needed.
android {
    namespace = "ru.grain.monitor"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.grain.monitor"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "2.0-kotlin"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
