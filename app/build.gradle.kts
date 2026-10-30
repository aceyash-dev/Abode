import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing.
//
// Values come from keystore.properties (gitignored, local) or from environment
// variables of the same name (CI). With neither present the release build is
// simply unsigned, so a fresh clone still assembles.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingValue(key: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(key)

val releaseStoreFile = signingValue("storeFile")
val hasReleaseKeystore = !releaseStoreFile.isNullOrBlank()

android {
    namespace = "dev.abode.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.abode"
        minSdk = 29
        targetSdk = 35
        // The commit the build came from. CI passes it; a local build says "dev"
        // rather than claiming a revision it cannot prove.
        val gitSha = (project.findProperty("gitSha") as String?)?.take(7) ?: "dev"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        versionCode = 10
        versionName = "0.9.0"
    }

    signingConfigs {
        // A debug key checked into the repository on purpose.
        //
        // CI runners create a fresh debug keystore per job, so every nightly came
        // out with a different signature — which is why installing one over the
        // previous one failed with "app not installed" and needed an uninstall
        // first. One shared debug key means nightlies upgrade in place.
        //
        // This key is public by design and must never sign a release.
        getByName("debug") {
            storeFile = rootProject.file("keystore/abode-debug.p12")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    /**
     * One APK per ABI instead of one fat APK.
     *
     * The debug build carries the dictionary and the native libraries of every
     * ABI it is built for, and a phone only ever needs one of them. Splitting
     * halves what has to be downloaded and installed on each device.
     *
     * No universal APK: it would be the same fat build under another name, and
     * having one on the artifact list is how the wrong file gets installed.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":ime"))
    implementation(project(":ai"))
    implementation(project(":engine-voice"))
    implementation(project(":engine-text"))
    implementation(project(":data"))
    implementation(project(":core-ui"))
    implementation(project(":core-common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tabler.icons)

    testImplementation(libs.junit)
}
