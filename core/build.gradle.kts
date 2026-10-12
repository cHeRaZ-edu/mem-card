import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Logica pura: sin Compose, sin Android SDK en commonMain. Desktop (Linux/Windows) entra despues por el target jvm.
kotlin {
    jvm()

    android {
        namespace = "com.cheraz.memcard.core"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        // Codigo que usa java.io / java.util.zip / java.security y corre igual en Android y en JVM (desktop).
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(getByName("commonMain"))
        }
        getByName("jvmMain").dependsOn(jvmAndroidMain)
        getByName("androidMain").dependsOn(jvmAndroidMain)

        getByName("commonTest").dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
