import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

// Claves locales (no se suben al repo): OPENWEATHER_API_KEY, ABRIGATE_API_URL,
// ADMOB_APP_ID y ADMOB_BANNER_ID.
val props = Properties().apply {
    val archivo = rootProject.file("local.properties")
    if (archivo.exists()) load(FileInputStream(archivo))
}

// IDs de prueba publicados por Google. Se usan en debug para no hacer clics
// sobre anuncios reales propios (AdMob puede suspender la cuenta por eso).
val admobAppPrueba = "ca-app-pub-3940256099942544~3347511713"
val admobBannerPrueba = "ca-app-pub-3940256099942544/6300978111"

android {
    namespace = "com.utn.abrigate"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.utn.abrigate"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "OPENWEATHER_API_KEY",
            "\"${props.getProperty("OPENWEATHER_API_KEY") ?: ""}\""
        )

        // URL del servicio abrigate-api en Cloud Run (termina en "/").
        // Valida las compras de Abrigate Plus del lado del servidor.
        buildConfigField(
            "String",
            "ABRIGATE_API_URL",
            "\"${props.getProperty("ABRIGATE_API_URL") ?: ""}\""
        )
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = admobAppPrueba
            buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerPrueba\"")
        }
        release {
            manifestPlaceholders["admobAppId"] =
                props.getProperty("ADMOB_APP_ID") ?: admobAppPrueba
            buildConfigField(
                "String",
                "ADMOB_BANNER_ID",
                "\"${props.getProperty("ADMOB_BANNER_ID") ?: admobBannerPrueba}\""
            )
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.1.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-auth")

    // TP4: monetización
    implementation("com.android.billingclient:billing-ktx:8.3.0")
    implementation("com.google.android.gms:play-services-ads:25.5.0")

    // TP6: retención (notificaciones push y locales, pedido de reseña)
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.play:review-ktx:2.0.2")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}