import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

compose.desktop {
    application {
        mainClass = "com.cheraz.memcard.desktop.MainKt"

        nativeDistributions {
            // El CI usa createDistributable (carpeta portable con Java incluido); Msi queda por si luego se quiere instalador.
            targetFormats(TargetFormat.Msi)
            packageName = "mem-card"
            packageVersion = "0.1.0"
            vendor = "cHeRaZ-edu"
            description = "Historial y sync de partidas guardadas"
            // Todo el runtime de Java: pesa mas, pero no truena en una PC ajena por un modulo que faltaba.
            includeAllModules = true
            // muve.exe (lo baja el CI) se copia aqui: resources/windows/muve.exe
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
