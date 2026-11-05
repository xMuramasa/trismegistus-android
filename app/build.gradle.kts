import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release key, mounted read-only by scripts/build.sh; without it the release APK stays unsigned.
val signingFile = file("/signing/keystore.properties")
val signing = if (signingFile.isFile) Properties().apply { signingFile.inputStream().use(::load) } else null

android {
    namespace = "dev.xmuramasa.trismegistus"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.xmuramasa.trismegistus"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = signingFile.parentFile.resolve(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            // The token test compares the generated Tokens.kt with the design files themselves.
            it.systemProperty("trismegistus.designDir", rootProject.file("design").absolutePath)
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    implementation(libs.commonmark)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
