plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets these from scripts/version.sh; local builds use the dev defaults.
val wormholeVersionName = providers.environmentVariable("SECOND_DISPLAY_VERSION_NAME").orElse("0.1.0-dev").get()
val wormholeVersionCode = providers.environmentVariable("SECOND_DISPLAY_VERSION_CODE").orElse("1").get().toInt()
// Release signing: CI decodes the keystore from repository secrets. Without it, release APKs are unsigned.
val releaseKeystorePath = providers.environmentVariable("SECOND_DISPLAY_KEYSTORE_PATH").orNull?.takeIf { it.isNotEmpty() }

android {
    namespace = "io.github.swiderekartmax.seconddisplay"
    compileSdk = 35
    ndkVersion = "27.3.13750724"
    val bundledNdk = File(rootDir, ".tools/ndk-r27d")
    if (bundledNdk.isDirectory) {
        ndkPath = bundledNdk.absolutePath
    }
    signingConfigs {
        if (releaseKeystorePath != null) {
            fun signingEnv(name: String): String =
                providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
                    ?: throw GradleException("SECOND_DISPLAY_KEYSTORE_PATH is set but $name is missing; set all release signing variables")
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = signingEnv("SECOND_DISPLAY_KEYSTORE_PASSWORD")
                keyAlias = signingEnv("SECOND_DISPLAY_KEY_ALIAS")
                keyPassword = signingEnv("SECOND_DISPLAY_KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        // Differs from namespace: the original ID is taken on Google Play (account renamed to swide-git). JNI names follow the
        // Kotlin package (namespace), not this value.
        applicationId = "io.github.swidegit.seconddisplay"
        // Android 11+ phones and tablets. Portals (Android 9-10) are not supported by this build.
        minSdk = 30
        targetSdk = 35
        versionCode = wormholeVersionCode
        versionName = wormholeVersionName
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { targets("wormhole") } }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
}
