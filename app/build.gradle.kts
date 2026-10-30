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

        versionCode = 5
        versionName = "0.5.0"
    }

    signingConfigs {
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
