import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val fixedDebugKeystore = layout.buildDirectory.file("fixed-debug.keystore").get().asFile
if (!fixedDebugKeystore.exists()) {
    fixedDebugKeystore.parentFile.mkdirs()
    fixedDebugKeystore.writeBytes(
        Base64.getDecoder().decode(
            file("fixed-debug-keystore.b64").readText().trim()
        )
    )
}

android {
    namespace = "com.limradigitals.mobilebackup"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.limradigitals.mobilebackup"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = fixedDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        create("release") {
            val keystoreBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
            val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")

            if (!keystoreBase64.isNullOrBlank() &&
                !keystorePassword.isNullOrBlank() &&
                !keyAlias.isNullOrBlank() &&
                !keyPassword.isNullOrBlank()
            ) {
                val releaseKeystore = layout.buildDirectory.file("release-keystore.jks").get().asFile
                if (!releaseKeystore.exists()) {
                    releaseKeystore.parentFile.mkdirs()
                    releaseKeystore.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                }
                storeFile = releaseKeystore
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            val hasReleaseSigning = !System.getenv("ANDROID_KEYSTORE_BASE64").isNullOrBlank()
            if (!hasReleaseSigning) {
                throw GradleException(
                    "Release signing secrets are missing. Configure ANDROID_KEYSTORE_BASE64, " +
                        "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD."
                )
            }
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.api-client:google-api-client-android:2.7.2")
    implementation("com.google.apis:google-api-services-drive:v3-rev20260901-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.45.3")
}
