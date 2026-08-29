import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val releaseSigningPropertiesFile = File(
    System.getProperty("user.home"),
    ".gradle/gwa-manager-signing.properties"
)
val releaseSigningProperties = Properties()
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
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
        versionCode = 2
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningPropertiesFile.isFile) {
            create("release") {
                storeFile = file(
                    requireNotNull(releaseSigningProperties.getProperty("storeFile")) {
                        "Missing storeFile in $releaseSigningPropertiesFile"
                    }
                )
                storePassword = requireNotNull(
                    releaseSigningProperties.getProperty("storePassword")
                ) {
                    "Missing storePassword in $releaseSigningPropertiesFile"
                }
                keyAlias = requireNotNull(
                    releaseSigningProperties.getProperty("keyAlias")
                ) {
                    "Missing keyAlias in $releaseSigningPropertiesFile"
                }
                keyPassword = requireNotNull(
                    releaseSigningProperties.getProperty("keyPassword")
                ) {
                    "Missing keyPassword in $releaseSigningPropertiesFile"
                }
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (releaseSigningPropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
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