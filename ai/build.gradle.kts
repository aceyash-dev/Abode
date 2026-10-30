plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)

}

val giphyKey: String =
    (project.findProperty("giphyKey") as String?)
        ?: System.getenv("GIPHY_API_KEY")
        ?: ""

android {
    namespace = "dev.abode.ai"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Empty in a build that has no secret, and the GIF tab then says so rather
        // than failing a request. The key is never in the source.
        buildConfigField("String", "GIPHY_API_KEY", "\"$giphyKey\"")
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.kotlinx.coroutines.android)
}
