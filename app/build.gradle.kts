plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.edwardlucas.gwamanager"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.edwardlucas.gwamanager"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    implementation(libs.material)
    implementation(libs.mozilla.geckoview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}