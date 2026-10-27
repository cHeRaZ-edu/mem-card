plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cheraz.memcard.transport.rfcomm"
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

    testImplementation(libs.junit)
}
