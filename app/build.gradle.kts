plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "uk.ccez.clock"
    compileSdk = 37

    defaultConfig {
        applicationId = "uk.ccez.clock"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("CCEZ_CLOCK_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("CCEZ_CLOCK_VERSION_NAME") ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // No server built in (a public repo carries no tailnet address): the
        // phone's `sync.server` setting holds it (`clock.ts set sync.server
        // <url>`). A private build may still pass -PclockServer=<url>.
        val server = (project.findProperty("clockServer") as String?) ?: ""
        buildConfigField("String", "SYNC_SERVER", "\"$server\"")
    }

    signingConfigs {
        // One key for every build (scripts/build.sh points at it), so each
        // APK installs over the last; without it, the debug key.
        val keystore = System.getenv("CCEZ_CLOCK_KEYSTORE")?.let { file(it) }
        if (keystore != null && keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("CCEZ_CLOCK_KEY_PASSWORD")
                keyAlias = "ccez-clock"
                keyPassword = System.getenv("CCEZ_CLOCK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Only the languages the owner uses: library strings for 80 locales are
    // most of a small APK's resources.
    androidResources { localeFilters += listOf("en", "fr") }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // The JVM's android.jar has no working org.json; the real one for tests.
    testImplementation(libs.orgjson)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
