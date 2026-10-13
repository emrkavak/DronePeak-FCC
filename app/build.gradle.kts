import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Production builds never fall back to the public debug test key.
val activationFile = rootProject.file("activation.properties")
val activationProps = Properties().apply {
    if (activationFile.exists()) activationFile.inputStream().use { load(it) }
}
val activationSecret = providers.environmentVariable("DRONEPEAK_ACTIVATION_SECRET")
    .orElse(activationProps.getProperty("secret", "")).get()
require(activationSecret.isEmpty() || activationSecret.matches(Regex("[A-Z2-7]{32}"))) {
    "Activation secret must be a 32-character Base32 key."
}

android {
    namespace = "com.dronepeak.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dronepeak.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 37
        versionName = "1.5.5-dp.8"
        val dronePeakRepo = providers.gradleProperty("dronePeakRepo")
            .orElse(providers.environmentVariable("DRONEPEAK_REPO"))
            .orElse("emrkavak/DronePeak-FCC")
        buildConfigField("String", "DRONEPEAK_REPO", "\"${dronePeakRepo.get()}\"")
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }
    val releaseStoreFile = keystoreProps.getProperty("storeFile")
        ?: System.getenv("SIGNING_STORE_FILE")
    val releaseStorePassword = keystoreProps.getProperty("storePassword")
        ?: System.getenv("SIGNING_STORE_PASSWORD")
    val releaseKeyAlias = keystoreProps.getProperty("keyAlias")
        ?: System.getenv("SIGNING_KEY_ALIAS")
    val releaseKeyPassword = keystoreProps.getProperty("keyPassword")
        ?: System.getenv("SIGNING_KEY_PASSWORD")
    val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "ACTIVATION_SECRET", "\"${activationSecret.ifEmpty { "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" }}\"")
        }
        release {
            buildConfigField("String", "ACTIVATION_SECRET", "\"$activationSecret\"")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = null
            }
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
}

// Check task execution rather than task names: aggregate assemble/build tasks also include release.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        require(activationSecret.isNotEmpty()) {
            "Release requires DRONEPEAK_ACTIVATION_SECRET or activation.properties."
        }
    }
}
