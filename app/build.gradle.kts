/*
 * ICdetection - IMSI-Catcher Detection Tool
 * Copyright (C) 2026 Alexis Gómez Rodríguez
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 3.0 — Commit de esta compilación, para AppVersion (`versionName+commit`, ver core/AppBuildId.kt).
// Desde el repositorio: `git describe` (commit corto, `-dirty` con cambios sin commitear). Desde un
// ZIP de `git archive`: el fichero BUILD_COMMIT, que git rellena (export-subst). Si no: "nogit".
// Con proveedores de Gradle, compatible con la caché de configuración.
val buildCommit: String = run {
    val commit = Regex("[0-9a-f]{7,40}(-dirty)?")
    val fromGit = runCatching {
        providers.exec {
            commandLine("git", "describe", "--always", "--dirty", "--abbrev=7", "--exclude=*")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
    val fromArchive = providers.fileContents(rootProject.layout.projectDirectory.file("BUILD_COMMIT"))
        .asText.orNull?.trim().orEmpty()
    when {
        commit.matches(fromGit) -> fromGit
        commit.matches(fromArchive) -> fromArchive
        else -> "nogit"
    }
}

android {
    namespace = "com.alexisgordr.icdetector"

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileSdk = 37

    defaultConfig {
        applicationId = "com.alexisgordr.icdetector"
        minSdk = 31
        targetSdk = 37

        versionCode = 43
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        versionName = "3.1.3"

        // 3.0 — Las betas usan el mismo identificador y nombre que la versión final, para poder
        // actualizar de beta a release. La 3.0 requiere instalación limpia (ver README/IMPORTANT);
        // de ahí en adelante cada versión actualiza a la anterior.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // v2.1 — Aquí había un `disable += "ForegroundServiceType"` a nivel de proyecto.
        // Apagar globalmente un aviso sobre el uso de ubicación en primer plano es justo lo que
        // no debe hacer una app que pide ser auditada: silencia el aviso en TODO el módulo,
        // incluido el código que se escriba mañana. La supresión vive ahora donde corresponde,
        // acotada al elemento concreto del manifiesto y con su justificación al lado.
    }

    // 3.1.0 — La app cambia de idioma por dentro (Ajustes → Idioma, LocaleController). En un App
    // Bundle, Google Play reparte los idiomas por separado y el teléfono solo descarga el suyo:
    // al elegir el otro faltarían sus textos. Sin el reparto, los dos idiomas van siempre. No
    // afecta a los APK (F-Droid, GitHub).
    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        // 3.0 — libandroidx.graphics.path.so viene de Compose (androidx.graphics:graphics-path) ya
        // compilada. Sin NDK instalado, Gradle no puede quitarle los símbolos, avisa en cada build y
        // la empaqueta tal cual; con NDK la quitaría. Se deja SIEMPRE tal cual para que el APK sea
        // el mismo en cualquier máquina (y para la compilación reproducible de F-Droid).
        jniLibs {
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    // 3.0 — org.json solo para las pruebas JVM: en el teléfono la app usa el org.json de Android
    // (las clases del framework se cargan antes que las de cualquier JAR), y en android.jar son
    // stubs que no sirven en la JVM. Todas las llamadas usadas existen en Android desde la API 1.
    testImplementation("org.json:json:20260814")
    // 3.0 — Solo para pruebas: SQLite en la JVM para probar las migraciones de esquema contra
    // un esquema anterior real. No entra en el APK.
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
