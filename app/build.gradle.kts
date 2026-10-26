plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cheraz.memcard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cheraz.memcard"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // btOnly: version para Play Store, sin permiso INTERNET y sin codigo del drive.
    // full: igual + modo muve drive. Se instalan lado a lado (applicationId distinto).
    flavorDimensions += "dist"
    productFlavors {
        create("btOnly") {
            dimension = "dist"
        }
        create("full") {
            dimension = "dist"
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // libmuve.so es el CLI de muve (un ejecutable, no una libreria): tiene que quedar extraido
            // en nativeLibraryDir para poder ejecutarlo, y sin pasar por strip.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libmuve.so"
        }
    }
}

// El APK full trae el CLI de muve para Android (arm64) como libmuve.so. Se baja del ultimo release
// publico de cHeRaZ-edu/muve la primera vez; para actualizarlo, borra el archivo y vuelve a compilar.
val muveLib = layout.projectDirectory.file("src/full/jniLibs/arm64-v8a/libmuve.so").asFile
val fetchMuve = tasks.register("fetchMuve") {
    description = "Baja muve-android-arm64 a src/full/jniLibs como libmuve.so"
    val out = muveLib
    outputs.file(out)
    onlyIf { !out.exists() }
    doLast {
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, "libmuve.so.part")
        java.net.URI("https://github.com/cHeRaZ-edu/muve/releases/latest/download/muve-android-arm64")
            .toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val head = tmp.inputStream().use { it.readNBytes(4) }
        check(head.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
            "Lo que se bajo no es un ejecutable de Android"
        }
        check(tmp.renameTo(out)) { "No se pudo guardar $out" }
    }
}
tasks.named("preBuild") { dependsOn(fetchMuve) }

dependencies {
    implementation(project(":core"))
    implementation(project(":source-saf"))
    implementation(project(":transport-rfcomm"))
    "fullImplementation"(project(":backend-drive"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile) // solo para comparar su velocidad contra DocumentsContract en el laboratorio SAF

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}