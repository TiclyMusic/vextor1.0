plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vextor.app"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.vextor.app"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        ndk {
            // Galaxy S25 e tutti i telefoni moderni sono arm64
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                // opzionale: -PllamaSourceDir=/percorso/llama.cpp per non riscaricarlo
                (project.findProperty("llamaSourceDir") as String?)?.let {
                    arguments += "-DLLAMA_SOURCE_DIR=$it"
                }
                cppFlags += listOf("-O3")
            }
        }
    }

    signingConfigs {
        // Chiave di test inclusa nel repo: così ogni APK costruito su GitHub
        // si installa come aggiornamento del precedente. NON usarla per il Play Store.
        create("vextor") {
            storeFile = file("vextor-test.keystore")
            storePassword = "vextor-test"
            keyAlias = "vextor"
            keyPassword = "vextor-test"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("vextor")
        }
        debug {
            signingConfig = signingConfigs.getByName("vextor")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        // ggml carica le varianti della CPU con dlopen dalla cartella nativeLibraryDir:
        // le .so devono essere estratte dall'APK.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
