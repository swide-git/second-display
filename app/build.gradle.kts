plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets these from scripts/version.sh; local builds use the dev defaults.
val appVersionName = providers.environmentVariable("SECOND_DISPLAY_VERSION_NAME").orElse("0.1.0-dev").get()
val appVersionCode = providers.environmentVariable("SECOND_DISPLAY_VERSION_CODE").orElse("1").get().toInt()
// Release signing: CI decodes the keystore from repository secrets. Without it, release APKs are unsigned.
val releaseKeystorePath = providers.environmentVariable("SECOND_DISPLAY_KEYSTORE_PATH").orNull?.takeIf { it.isNotEmpty() }

android {
    namespace = "io.github.swiderekartmax.seconddisplay"
    compileSdk = 36
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
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                targets("seconddisplay")
                // 16 KB ELF segment alignment: Android 15+ devices with 16 KB memory pages
                // cannot load 4 KB-aligned libraries, and Google Play withholds such apps from
                // them ("can't download"). NDK r27 still defaults to 4 KB.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
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
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
}
