plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Auto-bumped on every build so each sideloaded APK is a distinct version (shown in Settings).
val versionFile = file("version.properties")
val buildNumber = versionFile.takeIf { it.exists() }?.readLines()
    ?.firstOrNull { it.startsWith("versionCode=") }?.substringAfter("=")?.trim()?.toIntOrNull() ?: 1
val bumpVersion by tasks.registering {
    doLast { versionFile.writeText("versionCode=${buildNumber + 1}\n") }
}
tasks.named("preBuild") { finalizedBy(bumpVersion) }

android {
    namespace = "com.aria.air"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.aria.air"
        minSdk = 36
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        val gitCommit = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().ifEmpty { "none" }
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    buildTypes {
        release {
            // Phones only: drops the x86 copies of ML Kit's native OCR libraries (debug keeps them for the emulator).
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work)
    implementation(libs.mlkit.text)
    implementation(libs.androidx.glance)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
