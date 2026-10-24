import java.util.Properties

/**
 * Release signing from Gradle properties (`-POFFGRID_RELEASE_STORE_FILE=…`, or `~/.gradle/gradle.properties`) or
 * environment variables of the same names. No key lives in this repository. Without any of them the release APK
 * is built unsigned (`app-release-unsigned.apk`); it is never signed with the debug key.
 */
val releaseSigningNames = listOf(
    "OFFGRID_RELEASE_STORE_FILE", "OFFGRID_RELEASE_STORE_PASSWORD", "OFFGRID_RELEASE_KEY_ALIAS", "OFFGRID_RELEASE_KEY_PASSWORD",
)
val releaseSigning: Map<String, String> = releaseSigningNames.mapNotNull { name ->
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull)?.takeIf { it.isNotBlank() }?.let { name to it }
}.toMap()
if (releaseSigning.isNotEmpty() && releaseSigning.size < releaseSigningNames.size) {
    throw GradleException("Release signing is incomplete, missing: ${(releaseSigningNames - releaseSigning.keys).joinToString()}")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ch.offgrid.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.offgrid.android"
        minSdk = 26
        targetSdk = 35
        // 3: database version 3 (delivery details), Nils' 0.3.0 contract and the lab.3 settings.
        versionCode = 5
        versionName = "0.3.1"

        // PostHog project key from the untracked local.properties (`POSTHOG_KEY=phc_...`). Empty means
        // analytics is a no-op, which is the default for every checkout.
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
        }
        val posthogKey = local.getProperty("POSTHOG_KEY", "").trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        buildConfigField("String", "POSTHOG_KEY", "\"$posthogKey\"")
        // Optional serial-to-TCP bridge to a real antenna for debug builds (`OFFGRID_TCP_LINK=10.0.2.2:5555`).
        val tcpLink = local.getProperty("OFFGRID_TCP_LINK", "").trim().filter { it.isLetterOrDigit() || it in ".:-" }
        buildConfigField("String", "TCP_LINK", "\"$tcpLink\"")
        // Optional TEXT codec the app selects on a lab radio image (`OFFGRID_TEXT_CODEC=ogz1` or `raw`). Empty
        // leaves the antenna's setting alone; the team firmware has no codec and ignores it.
        val textCodec = local.getProperty("OFFGRID_TEXT_CODEC", "").trim().filter { it.isLetterOrDigit() }
        buildConfigField("String", "TEXT_CODEC", "\"$textCodec\"")
    }

    signingConfigs {
        if (releaseSigning.isNotEmpty()) {
            create("release") {
                val store = file(releaseSigning.getValue("OFFGRID_RELEASE_STORE_FILE"))
                if (!store.isFile) throw GradleException("OFFGRID_RELEASE_STORE_FILE does not exist: $store")
                storeFile = store
                storePassword = releaseSigning.getValue("OFFGRID_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("OFFGRID_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("OFFGRID_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Debug-only bridges: no TCP link to a dev machine and no forced TEXT codec in a release.
            buildConfigField("String", "TCP_LINK", "\"\"")
            buildConfigField("String", "TEXT_CODEC", "\"\"")
            // Unsigned when the release key is not configured (see releaseSigning above), never the debug key.
            signingConfig = if (releaseSigning.isNotEmpty()) signingConfigs.getByName("release") else null
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/{AL2.0,LGPL2.1}")
    }
    lint {
        // Lint must pass on every build; treat new problems as errors but keep the report readable.
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.usb.serial)
    implementation(libs.bouncycastle)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Runs the Room migration SQL on a plain SQLite database; Room's MigrationTestHelper needs a device.
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
}
