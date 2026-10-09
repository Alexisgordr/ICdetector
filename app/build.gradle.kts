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

android {
    namespace = "com.alexisgordr.icdetector"

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileSdk = 37

    defaultConfig {
        applicationId = "com.alexisgordr.icdetector"
        minSdk = 29
        targetSdk = 37

        versionCode = 42
        versionName = "3.0.0-beta4"

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
    }

    lint {
        // v2.1 — Aquí había un `disable += "ForegroundServiceType"` a nivel de proyecto.
        // Apagar globalmente un aviso sobre el uso de ubicación en primer plano es justo lo que
        // no debe hacer una app que pide ser auditada: silencia el aviso en TODO el módulo,
        // incluido el código que se escriba mañana. La supresión vive ahora donde corresponde,
        // acotada al elemento concreto del manifiesto y con su justificación al lado.
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
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.json:json:20260522")

    testImplementation("junit:junit:4.13.2")
    // 3.0 — Solo para pruebas: SQLite en la JVM para probar las migraciones de esquema contra
    // un esquema anterior real. No entra en el APK.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
