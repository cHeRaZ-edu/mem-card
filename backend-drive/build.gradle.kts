plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cheraz.memcard.backend.drive"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core"))
    // M5: implementation(files("libs/muve-android.aar"))
}
