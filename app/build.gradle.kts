plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Auto-bumped on every local build so each sideloaded APK is a distinct version (shown in Settings). CI builds the
// committed number as is: a push to main whose number has no GitHub release yet gets released as v1.0.<versionCode>.
val versionFile = file("version.properties")
val buildNumber = versionFile.takeIf { it.exists() }?.readLines()
    ?.firstOrNull { it.startsWith("versionCode=") }?.substringAfter("=")?.trim()?.toIntOrNull() ?: 1
val bumpVersion by tasks.registering {
    doLast { versionFile.writeText("versionCode=${buildNumber + 1}\n") }
}
if (System.getenv("CI") == null) tasks.named("preBuild") { finalizedBy(bumpVersion) }

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

    // Releases are signed with the key in the AIR_KEYSTORE_* environment variables (set by CI from repository secrets);
    // without them, the debug key. Updates only install over an APK signed with the same key.
    val keystore = System.getenv("AIR_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("AIR_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("AIR_KEY_ALIAS")
            keyPassword = System.getenv("AIR_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Phones only: drops the x86 copies of ML Kit's native OCR libraries (debug keeps them for the emulator).
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
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
