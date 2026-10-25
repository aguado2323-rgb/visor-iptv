import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Clave de TMDB opcional. Se inyecta al compilar (secreto TMDB_API_KEY en GitHub
// o propiedad tmdbApiKey en ~/.gradle/gradle.properties). Si falta, la opción
// "Carátulas de TMDB" aparece desactivada en Ajustes.
val tmdbKey: String = (System.getenv("TMDB_API_KEY") ?: (project.findProperty("tmdbApiKey") as String?) ?: "")

android {
    namespace = "com.visor.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.visor.tv"
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // Clave fija del proyecto (repo privado): todas las compilaciones firman igual,
            // así el APK se actualiza encima sin desinstalar ni perder la sesión.
            val props = Properties()
            val pf = file("signing.properties")
            if (pf.exists()) pf.inputStream().use { props.load(it) }
            // GitHub pasa los secretos que no existen como cadena vacía: se tratan como ausentes.
            fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
            val ks = env("VISOR_KEYSTORE_PATH")?.let { file(it) } ?: file("visor-release.jks")
            if (ks.exists()) {
                val pass = env("VISOR_KEYSTORE_PASSWORD") ?: props.getProperty("visorStorePassword")
                storeFile = ks
                storePassword = pass
                keyAlias = env("VISOR_KEY_ALIAS") ?: "visor"
                keyPassword = env("VISOR_KEY_PASSWORD") ?: pass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("org.videolan.android:libvlc-all:3.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
