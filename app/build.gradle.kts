import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    signingConfigs {
        create("Release") {
            val propertiesFile = rootProject.file("signing.properties")
            if (propertiesFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(propertiesFile))

                storeFile = file(properties["KEYSTORE_FILE"] as String)
                storePassword = properties["KEYSTORE_PASSWORD"] as String
                keyAlias = properties["KEY_ALIAS"] as String
                keyPassword = properties["KEY_PASSWORD"] as String
            } else {
                println("Warning: signing.properties file not found!")
            }
        }
    }
    namespace = "com.immichframe.immichframe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.immichframe.immichframe"
        minSdk = 23
        targetSdk = 36
        versionCode = 50
        versionName = "1.0.50.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("Release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.retrofit)
    implementation(libs.retrofitgson)
    implementation(libs.nanohttpd)
    implementation(libs.androidx.preference)
}